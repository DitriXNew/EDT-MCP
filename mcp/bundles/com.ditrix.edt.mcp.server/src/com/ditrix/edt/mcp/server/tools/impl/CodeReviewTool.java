/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
 * project (or one module) and rendering its diagnostics as an actionable table. This
 * is the delta over {@code get_project_errors}: EDT's own {@code v8-code-style}
 * checks already surface there, but the engine's <b>metrics</b> (magic number,
 * cyclomatic/cognitive complexity, method/line length, nesting, …) are not in EDT.
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
        return "Review BSL code quality with the BSL Language Server engine: reports code-metric defects " //$NON-NLS-1$
            + "(magic number, cyclomatic/cognitive complexity, method/line length, nesting, …) that EDT's own " //$NON-NLS-1$
            + "checks do not cover. Each finding is a defect to FIX: it carries the rule, severity, Module path and " //$NON-NLS-1$
            + "Line, ready for read_module_source / write_module_source — fix each, then re-run code_review to verify. " //$NON-NLS-1$
            + "Scope the whole project or one module; filter by severity or rule. Needs the engine jar (see the guide). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('code_review')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name to review.", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Optional: narrow the review to a single module, path from src/ " //$NON-NLS-1$
                    + "(e.g. 'CommonModules/Calc/Module.bsl'). Omit to review the whole configuration.") //$NON-NLS-1$
            .enumProperty("severity", //$NON-NLS-1$
                "Optional: minimum severity to report (error > warning > information > hint). Omit to report all.", //$NON-NLS-1$
                "error", "warning", "information", "hint") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            .stringProperty("rule", //$NON-NLS-1$
                "Optional: report only diagnostics whose rule id contains this substring (e.g. 'Magic', 'Complexity').") //$NON-NLS-1$
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
            targetAbsPath = normalize(moduleOsFile.getAbsolutePath());
            scopeDir = moduleOsFile.getParentFile();
        }

        BslLsRunner.Request request = new BslLsRunner.Request(scopeDir).configFile(projectConfig(srcRoot, project));
        BslLsRunner.Result result = BslLsRunner.run(request);
        if (!result.ok())
        {
            return ToolResult.error(result.errorMessage()).toJson();
        }

        return render(result.report(), projectName, modulePath, srcRoot, targetAbsPath, severity, rule, limit);
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
     * @param targetAbsPath when scoped to one module, its normalized absolute path (findings are
     *            filtered to it); {@code null} for whole-project
     * @param severityMin the minimum-severity filter name, or {@code null} for all
     * @param rule the rule-substring filter, or {@code null} for all
     * @param limit the maximum number of rows to render
     * @return the Markdown result
     */
    static String render(BslLsReport report, String projectName, String modulePath, File srcRoot,
        String targetAbsPath, String severityMin, String rule, int limit)
    {
        int minRank = severityMin == null || severityMin.isEmpty() ? Integer.MIN_VALUE
            : rank(Severity.valueOf(severityMin.toUpperCase(Locale.ROOT)));
        String ruleNeedle = rule == null ? null : rule.toLowerCase(Locale.ROOT);

        List<Finding> filtered = new ArrayList<>();
        for (Finding f : report.findings())
        {
            if (targetAbsPath != null && !targetAbsPath.equals(normalize(f.path())))
            {
                continue;
            }
            if (rank(f.severity()) < minRank)
            {
                continue;
            }
            if (ruleNeedle != null && (f.code() == null || !f.code().toLowerCase(Locale.ROOT).contains(ruleNeedle)))
            {
                continue;
            }
            filtered.add(f);
        }
        filtered.sort(Comparator.comparingInt((Finding f) -> rank(f.severity())).reversed()
            .thenComparing(f -> modulePathOf(srcRoot, f.path()))
            .thenComparingInt(Finding::line));

        StringBuilder md = new StringBuilder();
        String scope = modulePath == null || modulePath.isEmpty() ? projectName : projectName + " / " + modulePath; //$NON-NLS-1$
        md.append("# Code review — ").append(MarkdownUtils.escapeForTable(scope)).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        md.append("**").append(report.total()).append("** finding(s): ") //$NON-NLS-1$ //$NON-NLS-2$
            .append(report.count(Severity.ERROR)).append(" error, ") //$NON-NLS-1$
            .append(report.count(Severity.WARNING)).append(" warning, ") //$NON-NLS-1$
            .append(report.count(Severity.INFORMATION)).append(" information, ") //$NON-NLS-1$
            .append(report.count(Severity.HINT)).append(" hint.\n\n"); //$NON-NLS-1$

        if (report.total() == 0)
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
