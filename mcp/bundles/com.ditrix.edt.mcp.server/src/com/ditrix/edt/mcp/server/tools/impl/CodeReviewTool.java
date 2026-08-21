/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslLsReport;
import com.ditrix.edt.mcp.server.utils.BslLsReport.Finding;
import com.ditrix.edt.mcp.server.utils.BslLsReport.Severity;
import com.ditrix.edt.mcp.server.utils.BslLsRunner;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Reviews BSL code quality by running the external BSL Language Server engine over a
 * project (or one module) and rendering its diagnostics as an actionable table.
 * <p>
 * <b>This is the engine's FULL diagnostic catalog, not a curated "metrics-only"
 * subset</b> — every rule the engine ships (magic number, cyclomatic/cognitive
 * complexity, method/line length, nesting, naming, unused code, … together, well over
 * a hundred rules) comes through. Some of these OVERLAP with EDT's own
 * {@code v8-code-style} checks surfaced by {@code get_project_errors} (both are BSL
 * static analyzers with a partially shared rule set) — this is not a strict delta over
 * it. Use {@code get_project_errors} for EDT's native check surface; use this tool for
 * the (larger, partially different) BSL Language Server rule set, or to cross-check the
 * two. Pass {@code excludeRule} to drop rule ids you already get elsewhere (e.g. ones
 * {@code get_project_errors} already reports) so they do not double up in your review.
 * <p>
 * The engine runs as a subprocess (see {@link BslLsRunner}); we do not implement any
 * rules ourselves. Each row is a concrete defect located by {@code Module path} +
 * {@code Line} — exactly what {@code read_module_source}/{@code write_module_source}
 * take — so the intended loop is <b>review → fix → re-run to verify</b>.
 */
public class CodeReviewTool implements IMcpTool
{
    public static final String NAME = "code_review"; //$NON-NLS-1$

    /** Accepted values of the {@code severity} minimum-severity filter. */
    static final List<String> SEVERITY_VALUES = Arrays.asList("error", "warning", "information", "hint"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Review BSL code quality with the BSL Language Server engine: its FULL diagnostic catalog " //$NON-NLS-1$
            + "(magic number, cyclomatic/cognitive complexity, method/line length, nesting, naming, unused " //$NON-NLS-1$
            + "code, …) — this overlaps with EDT's own v8-code-style checks (get_project_errors), it is not a " //$NON-NLS-1$
            + "strict delta over them; use excludeRule to drop rule ids you already get elsewhere. Each " //$NON-NLS-1$
            + "finding is a defect to FIX: it carries the rule, severity, Module path and Line, ready for " //$NON-NLS-1$
            + "read_module_source / write_module_source — fix each, then re-run code_review to verify. " //$NON-NLS-1$
            + "Scope the whole project or one module; filter by severity, rule or excludeRule. Needs the " //$NON-NLS-1$
            + "engine jar (see the guide). Full parameters and examples: call get_tool_guide('code_review')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name to review.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Optional: narrow the review to a single module, path from src/ " //$NON-NLS-1$
                    + "(e.g. 'CommonModules/Calc/Module.bsl'); must be a .bsl module. Omit to review " //$NON-NLS-1$
                    + "the whole configuration - a scoped run cannot see cross-module context, so " //$NON-NLS-1$
                    + "rules like unused-export are only reliable without it.") //$NON-NLS-1$
            .enumProperty("severity", //$NON-NLS-1$
                "Optional: minimum severity to report (error > warning > information > hint). Omit to report all.", //$NON-NLS-1$
                "error", "warning", "information", "hint") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .stringProperty("rule", //$NON-NLS-1$
                "Optional: report only diagnostics whose rule id contains this substring (e.g. 'Magic', 'Complexity').") //$NON-NLS-1$
            .stringProperty("excludeRule", //$NON-NLS-1$
                "Optional: drop diagnostics whose rule id contains this substring — e.g. to exclude rules " //$NON-NLS-1$
                    + "you already get from get_project_errors and avoid double-reporting the same issue.") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Max findings; default 100, max 1000 (optional).") //$NON-NLS-1$
            .build();
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // Analysis-only: launches the engine subprocess which READS .bsl files; never mutates the EDT
        // model or writes into the project. readOnly + idempotent so clients don't gate it behind a
        // write-confirmation.
        return new ToolAnnotations(null, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE);
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String missing = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME);
        if (missing != null)
        {
            return missing;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String rule = JsonUtils.extractStringArgument(params, "rule"); //$NON-NLS-1$
        String excludeRule = JsonUtils.extractStringArgument(params, "excludeRule"); //$NON-NLS-1$
        int limit = Pagination.clampLimit(JsonUtils.extractIntArgument(params, McpKeys.LIMIT, DEFAULT_LIMIT), MAX_LIMIT);

        if (severity != null && !severity.isEmpty()
            && !SEVERITY_VALUES.contains(severity.toLowerCase(Locale.ROOT)))
        {
            return ToolResult.error("Invalid severity: '" + severity + "'. Must be one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", SEVERITY_VALUES)).toJson(); //$NON-NLS-1$
        }

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        // A CLOSED project still answers exists() and still has its sources on disk, so without
        // this the engine would analyse them and report findings as if the project were open - or
        // fail later with a misleading "no src/ folder to review". Named separately from
        // not-found because the remedy is different (open it, not check the name).
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open it in EDT, then retry code_review.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        IFolder srcFolder = project.getFolder("src"); //$NON-NLS-1$
        if (srcFolder.getLocation() == null || !srcFolder.getLocation().toFile().isDirectory())
        {
            return ToolResult.error("Project '" + projectName + "' has no src/ folder to review.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        File srcRoot = srcFolder.getLocation().toFile();

        // Scope: whole src, or the folder of a single requested module (findings are still
        // filtered to that exact module below).
        File scopeDir = srcRoot;
        String targetAbsPath = null;
        if (modulePath != null && !modulePath.isEmpty())
        {
            IFile moduleFile = BslModuleUtils.resolveModuleFile(project, modulePath);
            // resolveModuleFile hands back a (possibly non-existent) handle, so check the file is
            // really on disk here — otherwise a bad path leaks out later as the runner's internal
            // "source directory does not exist" instead of an actionable module-not-found error.
            File moduleOsFile = moduleFile == null || moduleFile.getLocation() == null
                ? null : moduleFile.getLocation().toFile();
            if (moduleOsFile == null || !moduleOsFile.isFile())
            {
                return ToolResult.error("Module not found: src/" + modulePath //$NON-NLS-1$
                    + ". Pass a path from src/, e.g. 'CommonModules/Calc/Module.bsl'.").toJson(); //$NON-NLS-1$
            }
            // resolveModuleFile also accepts absolute paths and workspace-relative paths carrying
            // '..' segments, resolving against the WHOLE Eclipse workspace rather than just this
            // project's src/. Without this check a caller could point modulePath at a sibling
            // project (or any workspace-visible location) and have it silently analyzed instead of
            // rejected as out of scope for the requested project.
            if (!isWithinSrc(srcRoot, moduleOsFile))
            {
                return ToolResult.error("modulePath '" + modulePath + "' resolves outside project '" //$NON-NLS-1$ //$NON-NLS-2$
                    + projectName + "'s own src/ folder (" + srcRoot.getAbsolutePath() + "). Pass a path " //$NON-NLS-1$ //$NON-NLS-2$
                    + "relative to src/ that stays inside this project, e.g. 'CommonModules/Calc/Module.bsl' " //$NON-NLS-1$
                    + "— not an absolute path or one using '..' to escape src/.").toJson(); //$NON-NLS-1$
            }
            // The engine reports diagnostics for BSL modules only. Any OTHER existing file under
            // src/ (Configuration.mdo, a template, a picture) passes the checks above, gets its
            // containing folder analysed, and then matches nothing when findings are filtered to
            // this exact path - so the tool would answer "no issues" for a file it could never have
            // reviewed. Reject it by name rather than report a false clean.
            if (!isBslModule(moduleOsFile.getName()))
            {
                return ToolResult.error("modulePath '" + modulePath + "' is not a BSL module. " //$NON-NLS-1$ //$NON-NLS-2$
                    + "code_review analyses BSL sources (*.bsl); a metadata or template file has no " //$NON-NLS-1$
                    + "code-quality diagnostics, so reviewing it would report 'no issues' for a file " //$NON-NLS-1$
                    + "that was never checked. Pass a module path such as " //$NON-NLS-1$
                    + "'CommonModules/Calc/Module.bsl', or omit modulePath to review the whole " //$NON-NLS-1$
                    + "project.").toJson(); //$NON-NLS-1$
            }
            targetAbsPath = normalize(moduleOsFile.getAbsolutePath());
            scopeDir = moduleOsFile.getParentFile();
        }

        BslLsRunner.Request request = new BslLsRunner.Request(scopeDir)
            .configFile(projectConfig(srcRoot, project))
            // Pin the workspace root to the project's own src/ regardless of how narrow scopeDir is
            // for a single-module review, so report paths and workspace-local settings stay scoped
            // to THIS project (see BslLsRunner#buildCommand).
            .workspaceDir(srcRoot);
        BslLsRunner.Result result = BslLsRunner.run(request);
        if (!result.ok())
        {
            return ToolResult.error(result.errorMessage()).toJson();
        }

        return render(result.report(), projectName, modulePath, srcRoot, targetAbsPath, severity, rule,
            excludeRule, limit);
    }

    /**
     * Resolves the project's own {@code .bsl-language-server.json} if present; otherwise
     * {@code null}, letting {@link BslLsRunner} fall back to the engine-home config or the
     * engine defaults.
     */
    private static File projectConfig(File srcRoot, IProject project)
    {
        // Config conventionally sits at the project root (parent of src/).
        File projectRoot = srcRoot.getParentFile();
        if (projectRoot != null)
        {
            File cfg = new File(projectRoot, ".bsl-language-server.json"); //$NON-NLS-1$
            if (cfg.isFile())
            {
                return cfg;
            }
        }
        IPath loc = project.getLocation();
        if (loc != null)
        {
            File cfg = new File(loc.toFile(), ".bsl-language-server.json"); //$NON-NLS-1$
            if (cfg.isFile())
            {
                return cfg;
            }
        }
        return null;
    }

    /**
     * Renders the report as an actionable Markdown table. Package-private and static so
     * it is unit-testable against a {@link BslLsReport} built from a captured JSON sample
     * without spawning the engine.
     *
     * @param report the parsed engine report
     * @param projectName the reviewed project
     * @param modulePath the requested single-module scope, or {@code null} for whole-project
     * @param srcRoot the project's {@code src} directory (to relativize paths to {@code Module path})
     * @param targetAbsPath when scoped to one module, its normalized absolute path (both the
     *            summary counts and the displayed rows are narrowed to it, since the engine
     *            analyzes the whole containing directory but the caller asked about one file);
     *            {@code null} for whole-project
     * @param severityMin the minimum-severity filter name, or {@code null} for all
     * @param rule the rule-substring INCLUDE filter, or {@code null} for all
     * @param excludeRule the rule-substring EXCLUDE filter (e.g. to drop rules already covered by
     *            get_project_errors), or {@code null} to exclude none
     * @param limit the maximum number of rows to render
     * @return the Markdown result
     */
    static String render(BslLsReport report, String projectName, String modulePath, File srcRoot,
        String targetAbsPath, String severityMin, String rule, String excludeRule, int limit)
    {
        int minRank = severityMin == null || severityMin.isEmpty() ? Integer.MIN_VALUE
            : rank(Severity.valueOf(severityMin.toUpperCase(Locale.ROOT)));
        // isEmpty() as well as null, matching excludeRule/severityMin above: a client that sends
        // "" for an unset optional parameter meant "no filter". Without it, contains("") keeps every
        // finding that HAS a code and silently drops the ones whose code the engine omitted.
        String ruleNeedle = rule == null || rule.isEmpty() ? null : rule.toLowerCase(Locale.ROOT);
        String excludeNeedle = excludeRule == null || excludeRule.isEmpty() ? null : excludeRule.toLowerCase(Locale.ROOT);

        // Module scope FIRST: when modulePath narrows to one file, the engine still analyzed the
        // whole containing directory (its unit of analysis), so report.findings() carries every
        // sibling's diagnostics too. "scoped" is what the requested review is actually ABOUT — the
        // summary counts below are computed from it (not the raw, unfiltered report) so a
        // single-module review's totals never include issues from files the caller never asked
        // about. severity/rule stay pure DISPLAY filters on top of that (independent of the totals,
        // same as the `limit` cap — see the class guide).
        List<Finding> scoped = new ArrayList<>();
        for (Finding f : report.findings())
        {
            if (targetAbsPath != null && !isSamePath(targetAbsPath, f.path()))
            {
                continue;
            }
            scoped.add(f);
        }

        List<Finding> filtered = new ArrayList<>();
        for (Finding f : scoped)
        {
            if (rank(f.severity()) < minRank)
            {
                continue;
            }
            if (ruleNeedle != null && (f.code() == null || !f.code().toLowerCase(Locale.ROOT).contains(ruleNeedle)))
            {
                continue;
            }
            if (excludeNeedle != null && f.code() != null && f.code().toLowerCase(Locale.ROOT).contains(excludeNeedle))
            {
                continue;
            }
            filtered.add(f);
        }
        // Relativize ONCE per finding, not once per comparison: modulePathOf normalizes two
        // absolute paths, and a comparator key extractor is called O(n log n) times - on a
        // whole-project review of a large configuration that is hundreds of thousands of path
        // normalizations for a sort of a few thousand rows.
        Map<Finding, String> modulePaths = new IdentityHashMap<>();
        for (Finding f : filtered)
        {
            modulePaths.put(f, modulePathOf(srcRoot, f.path()));
        }
        filtered.sort(Comparator.comparingInt((Finding f) -> rank(f.severity())).reversed()
            .thenComparing(modulePaths::get)
            .thenComparingInt(Finding::line));

        StringBuilder md = new StringBuilder();
        String scope = modulePath == null || modulePath.isEmpty() ? projectName : projectName + " / " + modulePath; //$NON-NLS-1$
        md.append("# Code review — ").append(scope).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // The counts describe the SCOPE, not the filtered table: a reader must not conclude that a
        // project is clean because they filtered the rows. But a bare scope total above a shorter
        // table is its own trap - "26 finding(s)" over four rows reads as a rendering bug - so when
        // a filter actually removed rows, the line says how many are being shown as well. Both
        // numbers, no ambiguity either way.
        md.append("**").append(scoped.size()).append("** finding(s) in scope: ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(countBySeverity(scoped, Severity.ERROR)).append(" error, ") //$NON-NLS-1$
            .append(countBySeverity(scoped, Severity.WARNING)).append(" warning, ") //$NON-NLS-1$
            .append(countBySeverity(scoped, Severity.INFORMATION)).append(" information, ") //$NON-NLS-1$
            .append(countBySeverity(scoped, Severity.HINT)).append(" hint."); //$NON-NLS-1$
        if (filtered.size() != scoped.size())
        {
            // "match", not "showing": the table below is additionally capped at 'limit' rows, so a
            // "showing N" phrasing would contradict the row count whenever the cap bites.
            md.append(" **").append(filtered.size()) //$NON-NLS-1$
                .append("** of them match the severity/rule filters."); //$NON-NLS-1$
        }
        md.append("\n\n"); //$NON-NLS-1$

        if (scoped.isEmpty())
        {
            md.append("No BSL code-quality issues found. "); //$NON-NLS-1$
            md.append("(If you expected findings, confirm the engine jar and configuration — see get_tool_guide('code_review').)\n"); //$NON-NLS-1$
            return md.toString();
        }

        md.append("Each row is a code defect. Fix it at its `Module path` + `Line` via write_module_source " //$NON-NLS-1$
            + "(inspect with read_module_source), then re-run code_review to verify. Mechanical issues " //$NON-NLS-1$
            + "(e.g. MagicNumber) can be fixed directly; complexity/nesting may need a judged refactor.\n\n"); //$NON-NLS-1$

        if (filtered.isEmpty())
        {
            md.append("_No findings match the current filters._\n"); //$NON-NLS-1$
            return md.toString();
        }

        boolean capped = filtered.size() > limit;
        List<Finding> shown = capped ? filtered.subList(0, limit) : filtered;

        md.append(MarkdownUtils.tableHeader("Severity", "Rule", "Module path", "Line", "Message", "Docs")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        for (Finding f : shown)
        {
            md.append(MarkdownUtils.tableRow(
                label(f.severity()),
                nullToEmpty(f.code()),
                modulePathOf(srcRoot, f.path()),
                String.valueOf(f.line()),
                nullToEmpty(f.message()),
                nullToEmpty(f.href())));
        }

        if (capped)
        {
            md.append('\n').append(Pagination.limitReachedNotice(limit));
        }
        return md.toString();
    }

    /**
     * @param findings the findings to count over (already scoped to what the summary should
     *            reflect — see the module-scope note in {@link #render})
     * @param severity the severity to count
     * @return how many entries of {@code findings} carry that severity
     */
    private static int countBySeverity(List<Finding> findings, Severity severity)
    {
        int n = 0;
        for (Finding f : findings)
        {
            if (f.severity() == severity)
            {
                n++;
            }
        }
        return n;
    }

    /**
     * Guards {@code modulePath} resolution: {@link BslModuleUtils#resolveModuleFile} accepts
     * both a {@code src/}-relative path AND an absolute path (resolved against the WHOLE
     * Eclipse workspace, not just this project), and a relative path can carry {@code ..}
     * segments. Without this check a caller could point {@code modulePath} outside the
     * requested project's own {@code src/} (a sibling project, or any workspace-visible
     * location) and have it silently analyzed instead of rejected as out of scope.
     *
     * Lexical on purpose. This guard exists for a CALLER-supplied escape (an absolute path, or
     * {@code ..} segments) - CLAUDE.md pre-push #4 - and normalize + startsWith answers exactly
     * that. Following symlinks here would NOT close the symlink concern (a whole-project review
     * walks {@code src/} and reads the same linked file with no modulePath involved) while it WOULD
     * break the legitimate layout where shared BSL is linked into {@code src/}: the module stays
     * reviewable project-wide but becomes unaddressable per-module. Deciding whether the engine may
     * follow links out of a project is a srcDir-level policy question, not something to bolt onto
     * this one check.
     *
     * @param srcRoot the requested project's own {@code src} directory
     * @param candidate the resolved module file's on-disk location
     * @return {@code true} when {@code candidate} is {@code srcRoot} itself or a descendant of it
     */
    static boolean isWithinSrc(File srcRoot, File candidate)
    {
        if (srcRoot == null || candidate == null)
        {
            return false;
        }
        try
        {
            Path root = srcRoot.toPath().toAbsolutePath().normalize();
            Path c = candidate.toPath().toAbsolutePath().normalize();
            return c.startsWith(root);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** Severity importance rank; higher is more severe (Error highest, Hint lowest). */
    private static int rank(Severity s)
    {
        switch (s)
        {
        case ERROR:
            return 3;
        case WARNING:
            return 2;
        case INFORMATION:
            return 1;
        case HINT:
        default:
            return 0;
        }
    }

    private static String label(Severity s)
    {
        switch (s)
        {
        case ERROR:
            return "Error"; //$NON-NLS-1$
        case WARNING:
            return "Warning"; //$NON-NLS-1$
        case INFORMATION:
            return "Information"; //$NON-NLS-1$
        case HINT:
        default:
            return "Hint"; //$NON-NLS-1$
        }
    }

    /**
     * Whether {@code fileName} names a BSL module — the only thing the engine produces diagnostics
     * for.
     * <p>
     * Package-visible so the rule is pinned by a unit test rather than only by the live e2e: any
     * other existing file under {@code src/} would otherwise pass the path checks, get its folder
     * analysed, and match nothing when findings are filtered to it — answering "no issues" for a
     * file that was never reviewed.
     *
     * @param fileName the resolved file's name
     * @return {@code true} when it is a {@code .bsl} module
     */
    static boolean isBslModule(String fileName)
    {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".bsl"); //$NON-NLS-1$
    }

    /**
     * Relativizes an absolute finding path to the project {@code src} root, yielding the
     * {@code modulePath} form ({@code CommonModules/Calc/Module.bsl}) that
     * read/write_module_source accept. Falls back to the absolute path when the finding is
     * not under {@code src}.
     */
    private static String modulePathOf(File srcRoot, String absPath)
    {
        if (absPath == null)
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            Path root = srcRoot.toPath().toAbsolutePath().normalize();
            Path p = Paths.get(absPath).toAbsolutePath().normalize();
            if (p.startsWith(root))
            {
                return root.relativize(p).toString().replace('\\', '/');
            }
        }
        catch (RuntimeException e)
        {
            // fall through to the absolute path
        }
        return absPath;
    }

    /**
     * Whether two absolute paths denote the SAME file, asking the filesystem rather than comparing
     * text.
     * <p>
     * The scoped review filters the engine's findings down to the requested module by path, and a
     * plain string compare gets that wrong on a case-insensitive filesystem (Windows): a caller may
     * legitimately pass {@code commonmodules/calc/module.bsl} while the engine reports the on-disk
     * casing, and every finding would then be filtered out - reporting the module CLEAN when it is
     * not. That false clean is the worst answer this tool can give, so identity is decided by
     * {@link Files#isSameFile} where both paths exist.
     * <p>
     * Falls back to a case-insensitive text compare when the files cannot be probed (one of them
     * gone, or an IO error): still better than an exact compare, and never throws out of a review
     * that otherwise succeeded.
     *
     * @param targetAbsPath the normalized path of the module the caller scoped to
     * @param findingPath the path the engine reported for a finding (may be {@code null})
     * @return {@code true} when both denote the same module file
     */
    private static boolean isSamePath(String targetAbsPath, String findingPath)
    {
        String normalized = normalize(findingPath);
        if (normalized == null)
        {
            return false;
        }
        try
        {
            Path a = Paths.get(targetAbsPath);
            Path b = Paths.get(normalized);
            if (Files.exists(a) && Files.exists(b))
            {
                return Files.isSameFile(a, b);
            }
        }
        catch (IOException | RuntimeException e)
        {
            // fall through to the textual comparison below
        }
        return targetAbsPath.equalsIgnoreCase(normalized);
    }

    private static String normalize(String path)
    {
        if (path == null)
        {
            return null;
        }
        try
        {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        }
        catch (RuntimeException e)
        {
            return path;
        }
    }

    private static String nullToEmpty(String s)
    {
        return s == null ? "" : s; //$NON-NLS-1$
    }
}
