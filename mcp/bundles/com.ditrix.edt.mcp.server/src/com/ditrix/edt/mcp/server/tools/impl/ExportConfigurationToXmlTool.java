/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.CliReflectionErrors;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
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

    /** Bounded wait for the project and its dependents before the first export attempt. */
    static final long PRE_EXPORT_SETTLE_TIMEOUT_MS = 10_000L;

    /** Bounded wait for the project and its dependents before the single retry. */
    static final long RETRY_SETTLE_TIMEOUT_MS = 20_000L;

    /** (projectName, budgetMs) to the names of projects still not ready after the bounded wait. */
    private final BiFunction<String, Long, List<String>> settle;

    /** Whether the response of this call was already sent (the caller interrupted and left). */
    private final BooleanSupplier responseDelivered;

    /** Production wiring: the shared project-and-dependents settle and the executing call's state. */
    public ExportConfigurationToXmlTool()
    {
        this((project, budgetMs) -> ProjectStateChecker.awaitSearchScopeReady(project, budgetMs)
            .getNotReadyProjectNames(), ExportConfigurationToXmlTool::executingResponseDelivered);
    }

    /** Test seam: both the bounded wait and the delivered-response probe are injectable. */
    ExportConfigurationToXmlTool(BiFunction<String, Long, List<String>> settle,
        BooleanSupplier responseDelivered)
    {
        this.settle = settle;
        this.responseDelivered = responseDelivered;
    }

    private static boolean executingResponseDelivered()
    {
        var activator = Activator.getDefault();
        var server = activator == null ? null : activator.getMcpServer();
        var call = server == null ? null : server.getExecutingToolCall();
        return call != null && call.isResponseDelivered();
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

            // EDT starts the export while the project context may still be (re)starting, e.g. right
            // after clean_project. Wait a bounded time (outside any BM transaction) for the project and
            // its dependents; the result never blocks the export, it only feeds the error text.
            List<String> notReady = settle.apply(projectName, PRE_EXPORT_SETTLE_TIMEOUT_MS);

            // A flag set before the call is the caller's real cancellation: no retry, flag kept.
            boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
            RootBaseline before = RootBaseline.read(rootXml);
            boolean cancelled = invokeExport(method, api, projectName, outputPath, interruptedOnEntry);
            boolean proven = !cancelled && rootProven(rootXml, before);
            boolean retried = false;

            // The CLI returns normally on cancellation or without writing; retry exactly once for those,
            // never when the root exists but a rewrite is merely unprovable, and never once the caller
            // has already been answered.
            if (!proven && !interruptedOnEntry && (cancelled || !Files.isRegularFile(rootXml))
                && !responseDelivered.getAsBoolean())
            {
                Activator.logInfo("export_configuration_to_xml: EDT returned without a completed root '" //$NON-NLS-1$
                    + rootXml + "' for project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + (cancelled ? " (cancelled by interrupt)" : "") + "; retrying once."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                notReady = settle.apply(projectName, RETRY_SETTLE_TIMEOUT_MS);
                if (!Thread.currentThread().isInterrupted())
                {
                    retried = true;
                    // The baseline is the state AFTER the first attempt: a partial root it left must
                    // not count as proof for the second.
                    before = RootBaseline.read(rootXml);
                    boolean cancelledAgain = invokeExport(method, api, projectName, outputPath, false);
                    cancelled = cancelled || cancelledAgain;
                    proven = !cancelledAgain && rootProven(rootXml, before);
                }
            }
            if (!proven)
            {
                return ToolResult.error(notExportedError(rootXml, projectName, cancelled, retried, notReady))
                    .toJson();
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

    /** Root state before an attempt; {@code null} (no baseline) when no regular root existed. */
    private static final class RootBaseline
    {
        final FileTime mtime;
        final long size;
        final String digest;

        private RootBaseline(FileTime mtime, long size, String digest)
        {
            this.mtime = mtime;
            this.size = size;
            this.digest = digest;
        }

        static RootBaseline read(Path root) throws IOException
        {
            try
            {
                BasicFileAttributes attrs = Files.readAttributes(root, BasicFileAttributes.class);
                if (!attrs.isRegularFile())
                {
                    return null;
                }
                return new RootBaseline(attrs.lastModifiedTime(), attrs.size(), MergeRulesCodec.fileDigest(root));
            }
            catch (NoSuchFileException missing)
            {
                return null; // An absent root is a valid initial state.
            }
        }
    }

    /** Whether the attempt created the root or provably rewrote it against the baseline. */
    private static boolean rootProven(Path rootXml, RootBaseline before) throws IOException
    {
        if (!Files.isRegularFile(rootXml))
        {
            return false;
        }
        if (before == null)
        {
            return true;
        }
        BasicFileAttributes written = Files.readAttributes(rootXml, BasicFileAttributes.class);
        // Equal/coarse timestamps prove nothing; changed bytes can still prove a rewrite.
        return written.lastModifiedTime().compareTo(before.mtime) > 0
            || written.size() != before.size
            || !before.digest.equals(MergeRulesCodec.fileDigest(rootXml));
    }

    /**
     * One CLI call. EDT turns an interrupt of the calling thread into a CANCEL status that the CLI
     * drops, and leaves the flag set on return; that is the only observable cancellation.
     *
     * @return true when EDT cancelled this call (the foreign flag is cleared); false otherwise
     */
    private static boolean invokeExport(Method method, Object api, String projectName, Path outputPath,
        boolean interruptedOnEntry) throws Exception
    {
        method.invoke(api, projectName, outputPath);
        return !interruptedOnEntry && Thread.interrupted();
    }

    /** The final error when no attempt proved the root. */
    static String notExportedError(Path rootXml, String projectName, boolean cancelled, boolean retried,
        List<String> notReady)
    {
        StringBuilder sb = new StringBuilder();
        if (cancelled)
        {
            sb.append("EDT cancelled the export (the export thread was interrupted inside EDT); root file '") //$NON-NLS-1$
                .append(rootXml).append("' was not proven rewritten. "); //$NON-NLS-1$
        }
        else
        {
            sb.append("EDT returned normally without writing root file '").append(rootXml) //$NON-NLS-1$
                .append("': it is missing or cannot be proven rewritten. "); //$NON-NLS-1$
        }
        sb.append(retried ? "The export was attempted twice. " : "The export was attempted once. "); //$NON-NLS-1$ //$NON-NLS-2$
        if (notReady != null && !notReady.isEmpty())
        {
            sb.append("Projects not ready at the last check: ").append(String.join(", ", notReady)).append(". "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        sb.append("Export preparation may have failed, the export may have been cancelled, or timestamps may be too coarse. "); //$NON-NLS-1$
        if (cancelled)
        {
            sb.append("Files from a cancelled attempt may remain in outputPath. "); //$NON-NLS-1$
        }
        sb.append("Check the EDT Error Log, wait until list_projects shows project '").append(projectName) //$NON-NLS-1$
            .append("' as ready, verify permissions, and retry into a fresh outputPath directory."); //$NON-NLS-1$
        return sb.toString();
    }
}
