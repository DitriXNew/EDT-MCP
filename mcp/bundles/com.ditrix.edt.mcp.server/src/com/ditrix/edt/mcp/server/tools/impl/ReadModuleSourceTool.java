/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.InterceptionUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ReadToolExecution;
import com.ditrix.edt.mcp.server.utils.Refusals;

/**
 * Tool to read BSL module source code (whole file or line range).
 * Returns YAML frontmatter (projectName, module, startLine, endLine, totalLines;
 * plus truncated: true, nextStartLine, hint when clamped by the configured line
 * limit) followed by the source in a fenced bsl block. For an empty file,
 * startLine/endLine are omitted and totalLines is 0.
 */
public class ReadModuleSourceTool implements IMcpTool
{
    public static final String NAME = "read_module_source"; //$NON-NLS-1$

    private static final String START_LINE = "startLine"; //$NON-NLS-1$
    private static final String END_LINE = "endLine"; //$NON-NLS-1$

    /** Fallback when the {@code maxLines} tool parameter is not configured */
    private static final int DEFAULT_MAX_LINES = 500;

    /** Maximum time spent waiting for the optional UI-thread interception footer. */
    private static final long INTERCEPTION_FOOTER_TIMEOUT_SECONDS = 3;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Inspect the source of a complete BSL module. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('read_module_source')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty(McpKeys.MODULE_PATH,
                "Path from src/, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .integerProperty(START_LINE,
                "First line, 1-based inclusive; omit to read from the start.") //$NON-NLS-1$
            .integerProperty(END_LINE,
                "Last line, 1-based inclusive; omit to read to the end.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        if (modulePath != null && !modulePath.isEmpty())
        {
            String safeName = modulePath.replace("/", "-").replace("\\", "-").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return "source-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Validate required parameters
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String modulePath = JsonUtils.extractStringArgument(params, McpKeys.MODULE_PATH);
        int startLine = JsonUtils.extractIntArgument(params, START_LINE, -1);
        int endLine = JsonUtils.extractIntArgument(params, END_LINE, -1);

        err = JsonUtils.requireArgument(params, McpKeys.MODULE_PATH, ". Example: 'CommonModules/MyModule/Module.bsl'"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        // Get project
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        try
        {
            // Get file
            IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
            if (!file.exists())
            {
                return ToolResult.error("File not found: src/" + modulePath + //$NON-NLS-1$
                       ". Use format like 'CommonModules/ModuleName/Module.bsl' or " + //$NON-NLS-1$
                       "'Documents/DocName/ObjectModule.bsl'").toJson(); //$NON-NLS-1$
            }

            // Canonicalize the display path; the footer uses this same resolved file.
            String canonicalModulePath = BslModuleUtils.sourceRelativePath(file);

            // Read file content with UTF-8 BOM detection
            List<String> allLines = BslModuleUtils.readFileLines(file);

            int totalLines = allLines.size();

            // Revision token for the optimistic-lock round-trip: hash the WHOLE file
            // (not the returned range) from the same canonical text write_module_source
            // recomputes (readFileText, \n-normalized), so a write with this exact
            // expectedHash matches when nothing changed. Whole-file even on a range read,
            // because a write targets the whole module.
            String contentHash = ContentHash.of(BslModuleUtils.readFileText(file).replace("\r\n", "\n")); //$NON-NLS-1$ //$NON-NLS-2$

            // Handle empty file
            if (totalLines == 0)
            {
                return formatOutput(projectName, canonicalModulePath, allLines, 0, 0, 0, false, contentHash);
            }

            // Determine range
            int from = 1;
            int to = totalLines;

            if (startLine > 0)
            {
                from = Math.max(1, Math.min(startLine, totalLines));
            }
            if (endLine > 0)
            {
                to = Math.max(from, Math.min(endLine, totalLines));
            }

            // Clamp to the configured line limit
            int maxLines = ToolParameterSettings.getInstance()
                .getParameterValue(NAME, "maxLines", DEFAULT_MAX_LINES); //$NON-NLS-1$
            boolean truncated = false;
            if (to - from + 1 > maxLines)
            {
                to = from + maxLines - 1;
                truncated = true;
            }

            String output = formatOutput(projectName, canonicalModulePath, allLines, from, to, totalLines, truncated, contentHash);

            // Keep the optional model footer on the windowed path; file reads also work without it.
            String interception = interceptionFooterOnUi(file);
            return interception != null ? output + interception : output;
        }
        catch (Exception e)
        {
            String refusal = Refusals.messageOf(e);
            if (refusal != null)
            {
                return ToolResult.error(refusal).toJson();
            }
            return ToolResult.error("Error reading file: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Formats module source output as YAML frontmatter + fenced BSL code block.
     *
     * @param projectName EDT project name (for frontmatter)
     * @param modulePath path from src/ (for frontmatter)
     * @param allLines all source lines of the file
     * @param from 1-based start line (inclusive); ignored when totalLines == 0
     * @param to 1-based end line (inclusive); ignored when totalLines == 0
     * @param totalLines total line count in the file (0 for empty file)
     * @param truncated true if the returned range was clamped by the configured line limit
     * @param contentHash opaque whole-file revision token to round-trip into
     *     write_module_source's expectedHash; omitted from the frontmatter when null
     * @return formatted result string
     */
    static String formatOutput(String projectName, String modulePath, List<String> allLines, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        int from, int to, int totalLines, boolean truncated, String contentHash)
    {
        FrontMatter fm = FrontMatter.create()
            .put(McpKeys.PROJECT_NAME, projectName)
            .put("module", modulePath); //$NON-NLS-1$

        if (contentHash != null)
        {
            fm.put("contentHash", contentHash); //$NON-NLS-1$
        }

        if (totalLines > 0)
        {
            fm.put(START_LINE, from)
                .put(END_LINE, to);
        }

        fm.put("totalLines", totalLines); //$NON-NLS-1$

        if (truncated)
        {
            fm.put("truncated", true); //$NON-NLS-1$
            fm.put("nextStartLine", to + 1); //$NON-NLS-1$
            fm.put("hint", //$NON-NLS-1$
                "Output clamped to the configured line limit. " //$NON-NLS-1$
                + "To continue reading, call read_module_source again with the same projectName and modulePath " //$NON-NLS-1$
                + "and startLine=" + (to + 1) + ". " //$NON-NLS-1$
                + "For an overview of procedures, functions and regions, call get_module_structure."); //$NON-NLS-1$
        }

        StringBuilder sb = new StringBuilder();
        sb.append("```bsl\n"); //$NON-NLS-1$
        if (totalLines > 0)
        {
            for (int i = from - 1; i < to; i++)
            {
                sb.append(allLines.get(i)).append('\n');
            }
        }
        sb.append("```\n"); //$NON-NLS-1$

        return fm.wrapContent(sb.toString());
    }

    /** Optional windowed enrichment for the file already selected by the caller. */
    private static String interceptionFooterOnUi(IFile file)
    {
        if (HostKind.current() == HostKind.HEADLESS)
        {
            return null;
        }
        AtomicReference<String> ref = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean expired = new AtomicBoolean();
        Runnable task = () -> runFooterTaskUnlessExpired(expired, finished, () -> {
            try
            {
                ref.set(ReadToolExecution.read(file.getProject(), NAME,
                    () -> InterceptionUtils.moduleFooter(BslModuleUtils.loadModule(file))));
            }
            catch (RuntimeException e)
            {
                failure.set(e);
            }
        });
        try
        {
            if (!ReadToolExecution.dispatchAsync(task))
            {
                String result = awaitComputedValue(ref, finished,
                    INTERCEPTION_FOOTER_TIMEOUT_SECONDS, TimeUnit.SECONDS, expired);
                if (expired.get() || Thread.currentThread().isInterrupted())
                {
                    String reason = Thread.currentThread().isInterrupted()
                        ? "waiting was interrupted" //$NON-NLS-1$
                        : "the UI thread did not respond within " //$NON-NLS-1$
                            + INTERCEPTION_FOOTER_TIMEOUT_SECONDS + " seconds"; //$NON-NLS-1$
                    Activator.logWarning("read_module_source: interception footer unavailable: " //$NON-NLS-1$
                        + reason + "; returning the source without it"); //$NON-NLS-1$
                    return null;
                }
                RuntimeException taskFailure = failure.get();
                if (taskFailure != null)
                {
                    Activator.logWarning("read_module_source: interception footer unavailable: " //$NON-NLS-1$
                        + taskFailure.getMessage());
                }
                return result;
            }
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("read_module_source: interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
        }
        RuntimeException taskFailure = failure.get();
        if (taskFailure != null)
        {
            Activator.logWarning("read_module_source: interception footer unavailable: " //$NON-NLS-1$
                + taskFailure.getMessage());
        }
        return ref.get();
    }

    /** Drops queued UI work whose caller has already stopped waiting. */
    static void runFooterTaskUnlessExpired(AtomicBoolean expired, CountDownLatch finished,
        Runnable work)
    {
        try
        {
            if (!expired.get())
            {
                work.run();
            }
        }
        finally
        {
            finished.countDown();
        }
    }

    static <T> T awaitComputedValue(AtomicReference<T> value, CountDownLatch finished,
            long timeout, TimeUnit unit, AtomicBoolean deadlineElapsed)
    {
        try
        {
            if (finished.await(timeout, unit))
            {
                return value.get();
            }
            deadlineElapsed.set(true);
            return null;
        }
        catch (InterruptedException e)
        {
            deadlineElapsed.set(true);
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
