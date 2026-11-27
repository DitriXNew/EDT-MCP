/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.ExecutingToolCall;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.InterruptiblePause;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.WorkspacePaths;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec;

/**
 * Tool that wraps the EDT "Export → Configuration to XML Files" action.
 *
 * <p>Equivalent of the EDT context-menu action <em>Export → Configuration to
 * XML Files</em>. Dumps the configuration of an EDT project to a directory of
 * XML source files (the same format produced by the 1C platform's
 * {@code DumpConfigToFiles} command).
 *
 * <p>Wraps {@code com._1c.g5.v8.dt.cli.api.workspace.IExportConfigurationFilesApi}
 * via reflection so this bundle has no build-time dependency on the API
 * package (the API ships with EDT 2025.x and 2026.1, but reflection keeps the
 * plugin portable).
 */
public class ExportConfigurationToXmlTool implements IMcpTool
{
    public static final String NAME = "export_configuration_to_xml"; //$NON-NLS-1$

    /** Input param: filesystem path of the output directory for the XML files. */
    private static final String KEY_OUTPUT_PATH = "outputPath"; //$NON-NLS-1$

    /** Bounded wait for the project and its dependents before the single retry. */
    static final long RETRY_SETTLE_TIMEOUT_MS = 20_000L;

    /**
     * Minimum pause before the retry: the settle may return at once when the snapshot already
     * reads ready, while EDT may still be restarting the project context.
     */
    static final long RETRY_PAUSE_MS = 750L;

    /** (projectName, budgetMs) to the names of projects still not ready after the bounded wait. */
    private final BiFunction<String, Long, List<String>> settle;

    /** Whether the response of this call was already sent (the caller interrupted and left). */
    private final BooleanSupplier responseDelivered;

    /** Sleeps the given milliseconds; false when the thread was interrupted (flag restored). */
    private final LongPredicate pause;

    /** Production wiring: the shared project-and-dependents settle and the executing call's state. */
    public ExportConfigurationToXmlTool()
    {
        this((project, budgetMs) -> ProjectStateChecker.awaitSearchScopeReady(project, budgetMs)
            .getNotReadyProjectNames(), ExecutingToolCall::isResponseDelivered,
            InterruptiblePause::pause);
    }

    /** Test seam: the bounded wait, the delivered-response probe and the pause are injectable. */
    ExportConfigurationToXmlTool(BiFunction<String, Long, List<String>> settle,
        BooleanSupplier responseDelivered, LongPredicate pause)
    {
        this.settle = settle;
        this.responseDelivered = responseDelivered;
        this.pause = pause;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Export an EDT configuration into 1C XML files. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('export_configuration_to_xml')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name to export (required)", true) //$NON-NLS-1$
            .stringProperty(KEY_OUTPUT_PATH,
                "Filesystem path of the output directory for the XML files (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String outputPathStr = JsonUtils.extractStringArgument(params, KEY_OUTPUT_PATH);

        String err = JsonUtils.requireArguments(params, McpKeys.PROJECT_NAME, KEY_OUTPUT_PATH);
        if (err != null)
        {
            return err;
        }

        try
        {
            // Normalize to an absolute path so the underlying CLI API isn't
            // surprised by relative paths resolved against an unexpected
            // working directory. Reject early if the path exists but is a
            // file (not a directory); create the directory if it does not
            // exist yet so the export has a deterministic destination.
            Path outputPath = Paths.get(outputPathStr).toAbsolutePath().normalize();
            if (Files.exists(outputPath) && !Files.isDirectory(outputPath))
            {
                return ToolResult.error(
                    "outputPath exists but is not a directory: " + outputPath).toJson(); //$NON-NLS-1$
            }

            // Resolve the project before the CLI API and before creating outputPath: an
            // unknown name is a caller mistake, so it gets the standard value-naming error
            // instead of a CliApiException that CliReflectionErrors logs at ERROR with a
            // stack (#683), and no empty directory is left behind. This is the same lookup
            // the API does (IWorkspaceRoot.getProject(name).exists()). A closed project is
            // NOT refused: the API opens it itself (IWorkspaceSupport.setUpProject).
            if (!ProjectContext.of(projectName).exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
            Files.createDirectories(outputPath);

            // Defense-in-depth: export can write to ANY absolute path. With the
            // server bound to loopback + optional token this is trusted-caller-only; // NOSONAR explanatory comment, not commented-out code
            // still flag writes outside the workspace so an injected/erroneous call
            // is visible. Non-breaking: warn, do not reject (local export to an
            // external dir is a legitimate action).
            boolean outsideWorkspace = WorkspacePaths.isOutsideWorkspace(outputPath);
            if (outsideWorkspace)
            {
                Activator.logWarning("export_configuration_to_xml: outputPath is OUTSIDE the EDT workspace: " //$NON-NLS-1$
                    + outputPath + " (trusted-caller-only — see docs/security.md)."); //$NON-NLS-1$
            }

            Object api = Activator.getDefault().getExportConfigurationFilesApi();
            if (api == null)
            {
                return ToolResult.error(
                    "IExportConfigurationFilesApi is not available. " //$NON-NLS-1$
                  + "Required EDT plugin com._1c.g5.v8.dt.cli.api is not installed.").toJson(); //$NON-NLS-1$
            }

            // exportProject(String projectName, Path outputPath)
            Method method = api.getClass().getMethod("exportProject", //$NON-NLS-1$
                String.class, Path.class);
            Path rootXml = outputPath.resolve("Configuration.xml"); //$NON-NLS-1$

            // The CLI itself waits for the project (STARTED) and for export preparation, so the
            // first attempt is not preceded by any wait of ours. A flag set before the call is the
            // caller's real cancellation: no retry, flag kept. The flag is never cleared here.
            boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
            RootBaseline before = RootBaseline.read(rootXml);
            invokeExport(method, api, projectName, outputPath);
            boolean proven = rootProven(rootXml, before);
            boolean retried = false;
            List<String> notReady = List.of();

            // EDT can return normally without writing anything while the project context restarts
            // (right after clean_project). Retry exactly once, and only when the root was truly
            // absent before and is still absent: an existing root whose rewrite is merely
            // unprovable, or a non-file at its path, is not retried. Never once the caller has
            // left or been answered.
            if (!proven && before.isAbsent() && !interruptedOnEntry
                && !Thread.currentThread().isInterrupted() && !responseDelivered.getAsBoolean())
            {
                Activator.logInfo("export_configuration_to_xml: EDT returned without writing '" //$NON-NLS-1$
                    + rootXml + "' for project '" + projectName + "'; retrying once."); //$NON-NLS-1$ //$NON-NLS-2$
                // Minimum pause first (the settle can return at once), then the bounded settle.
                if (pause.test(RETRY_PAUSE_MS))
                {
                    notReady = settle.apply(projectName, RETRY_SETTLE_TIMEOUT_MS);
                    // The wait took time: re-read the root and re-check the caller before
                    // exporting a second time.
                    RootBaseline now = RootBaseline.read(rootXml);
                    if (now.isRegular())
                    {
                        // The first attempt finished during the wait: that is its completion,
                        // not a baseline for a second export.
                        proven = true;
                    }
                    else if (now.isAbsent() && !Thread.currentThread().isInterrupted()
                        && !responseDelivered.getAsBoolean())
                    {
                        retried = true;
                        before = now;
                        invokeExport(method, api, projectName, outputPath);
                        proven = rootProven(rootXml, before);
                    }
                }
            }
            if (!proven)
            {
                return ToolResult.error(notExportedError(rootXml, projectName, retried, notReady)).toJson();
            }

            // Action result: status + the project and destination path. There is no
            // round-trip ID, machine-structured position, declared outputSchema, or
            // UI-bound payload here, so MARKDOWN is the right format (see the
            // "Response format policy" in docs/output-formats.md / edt-mcp-tool-conventions).
            FrontMatter fm = FrontMatter.create()
                .put("tool", NAME) //$NON-NLS-1$
                .put("status", "success") //$NON-NLS-1$ //$NON-NLS-2$
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_OUTPUT_PATH, outputPath.toString());
            if (outsideWorkspace)
            {
                fm.put("outsideWorkspace", true); //$NON-NLS-1$
            }

            StringBuilder body = new StringBuilder();
            body.append("# Configuration exported to XML files\n\n"); //$NON-NLS-1$
            body.append("- Project: ").append(projectName).append('\n'); //$NON-NLS-1$
            body.append("- Output path: ").append(outputPath).append('\n'); //$NON-NLS-1$
            if (outsideWorkspace)
            {
                body.append('\n')
                    .append("> Note: outputPath is outside the EDT workspace; ") //$NON-NLS-1$
                    .append("ensure the caller is trusted.\n"); //$NON-NLS-1$
            }

            return fm.wrapContent(body.toString());
        }
        catch (Exception e)
        {
            return CliReflectionErrors.toErrorJson(e, "Export", "CLI"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Root state before an attempt. The path is classified without following links: absent
     * ({@link NoSuchFileException}), a regular file (with mtime, size and digest), or something
     * else that exists (a directory, FIFO, symbolic link, even a dangling one). Only a truly
     * absent root may be retried.
     */
    private static final class RootBaseline
    {
        private static final RootBaseline ABSENT = new RootBaseline(0, null, 0L, null);
        private static final RootBaseline OTHER = new RootBaseline(1, null, 0L, null);

        /** 0 absent, 1 exists but is not a regular file, 2 regular file. */
        final int kind;
        final FileTime mtime;
        final long size;
        final String digest;

        private RootBaseline(int kind, FileTime mtime, long size, String digest)
        {
            this.kind = kind;
            this.mtime = mtime;
            this.size = size;
            this.digest = digest;
        }

        boolean isAbsent()
        {
            return kind == 0;
        }

        boolean isRegular()
        {
            return kind == 2;
        }

        static RootBaseline read(Path root) throws IOException
        {
            try
            {
                BasicFileAttributes attrs = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile())
                {
                    return OTHER;
                }
                return new RootBaseline(2, attrs.lastModifiedTime(), attrs.size(),
                    MergeRulesCodec.fileDigest(root));
            }
            catch (NoSuchFileException missing)
            {
                return ABSENT; // An absent root is a valid initial state.
            }
        }
    }

    /** Whether the attempt created the root or provably rewrote it against the baseline. */
    private static boolean rootProven(Path rootXml, RootBaseline before) throws IOException
    {
        if (before.kind == 1 || !Files.isRegularFile(rootXml))
        {
            return false; // A pre-existing non-file root is never proof of an export.
        }
        if (before.isAbsent())
        {
            return true;
        }
        BasicFileAttributes written = Files.readAttributes(rootXml, BasicFileAttributes.class);
        // Equal/coarse timestamps prove nothing; changed bytes can still prove a rewrite.
        return written.lastModifiedTime().compareTo(before.mtime) > 0
            || written.size() != before.size
            || !before.digest.equals(MergeRulesCodec.fileDigest(rootXml));
    }

    /** One CLI call; EDT reports failures by exception, an interrupt flag is left untouched. */
    private static void invokeExport(Method method, Object api, String projectName, Path outputPath)
        throws Exception
    {
        method.invoke(api, projectName, outputPath);
    }

    /** The final error when no attempt proved the root. */
    static String notExportedError(Path rootXml, String projectName, boolean retried, List<String> notReady)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("EDT returned without writing root file '").append(rootXml).append("' ("); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(retried ? "attempted twice" : "attempted once"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("; it may have been cancelled while the project was restarting). "); //$NON-NLS-1$
        if (notReady != null && !notReady.isEmpty())
        {
            sb.append("Projects not ready at the last check: ").append(String.join(", ", notReady)).append(". "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("Export preparation may have failed, or an existing root was not proven rewritten "); //$NON-NLS-1$
        sb.append("(timestamps may be too coarse). "); //$NON-NLS-1$
        if (retried)
        {
            sb.append("Files from the first attempt may remain in outputPath. "); //$NON-NLS-1$
        }
        sb.append("Check the EDT Error Log, wait until list_projects shows project '").append(projectName) //$NON-NLS-1$
            .append("' as ready, verify permissions, and retry into a fresh outputPath directory."); //$NON-NLS-1$
        return sb.toString();
    }
}
