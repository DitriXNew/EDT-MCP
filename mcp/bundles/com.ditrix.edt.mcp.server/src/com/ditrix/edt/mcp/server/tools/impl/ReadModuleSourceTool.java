/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ContentHash;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.InterceptionUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

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

    /** Fallback when the {@code maxLines} tool parameter is not configured */
    private static final int DEFAULT_MAX_LINES = 500;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read BSL module source code from an EDT project, whole file or a line range. " + //$NON-NLS-1$
               "Returns YAML frontmatter (including a contentHash revision token to round-trip into " + //$NON-NLS-1$
               "write_module_source's expectedHash) followed by clean source in a fenced bsl block. " + //$NON-NLS-1$
               "Use this for the whole module; to read just one procedure/function body use read_method_source. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('read_module_source')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/, e.g. 'CommonModules/MyModule/Module.bsl' (required)", true) //$NON-NLS-1$
            .integerProperty("startLine", //$NON-NLS-1$
                "First line, 1-based inclusive; omit to read from the start.") //$NON-NLS-1$
            .integerProperty("endLine", //$NON-NLS-1$
                "Last line, 1-based inclusive; omit to read to the end.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# read_module_source\n\n" //$NON-NLS-1$
            + "Reads a BSL module's source from an EDT project, either the whole file or a specific " //$NON-NLS-1$
            + "line range, and returns it as clean source (no line-number prefixes) inside a fenced " //$NON-NLS-1$
            + "bsl block, preceded by YAML frontmatter.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "- To read a module before editing it, and to obtain the `contentHash` revision token " //$NON-NLS-1$
            + "that `write_module_source` accepts as `expectedHash` to guard against a lost update.\n" //$NON-NLS-1$
            + "- For a structural overview (procedures, functions, regions) rather than raw text, " //$NON-NLS-1$
            + "prefer `get_module_structure`. For a single method body, prefer `read_method_source`.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- `projectName` (string, required): EDT project name.\n" //$NON-NLS-1$
            + "- `modulePath` (string, required): path from the `src/` folder, e.g. " //$NON-NLS-1$
            + "`CommonModules/MyModule/Module.bsl` or `Documents/SalesOrder/ObjectModule.bsl`.\n" //$NON-NLS-1$
            + "- `startLine` (integer, optional): 1-based inclusive first line; omit to read from " //$NON-NLS-1$
            + "the beginning.\n" //$NON-NLS-1$
            + "- `endLine` (integer, optional): 1-based inclusive last line; omit to read to the end.\n\n" //$NON-NLS-1$
            + "## Output\n\n" //$NON-NLS-1$
            + "YAML frontmatter followed by a fenced `bsl` block. Frontmatter fields:\n\n" //$NON-NLS-1$
            + "- `projectName`, `module`: echo of the inputs.\n" //$NON-NLS-1$
            + "- `contentHash`: an opaque whole-file revision token. It is computed over the WHOLE " //$NON-NLS-1$
            + "file (not just the returned range) from the same canonical, `\\n`-normalized text that " //$NON-NLS-1$
            + "`write_module_source` recomputes, so a write carrying this exact `expectedHash` matches " //$NON-NLS-1$
            + "as long as nothing changed on disk. Whole-file even for a range read, because a write " //$NON-NLS-1$
            + "targets the whole module. Omitted only when no token is available.\n" //$NON-NLS-1$
            + "- `startLine`, `endLine`: the 1-based inclusive range actually returned.\n" //$NON-NLS-1$
            + "- `totalLines`: total line count of the file.\n" //$NON-NLS-1$
            + "- `truncated: true`, `nextStartLine`, `hint`: present only when the requested range was " //$NON-NLS-1$
            + "clamped by the configured line limit (the `maxLines` tool parameter, default 500). " //$NON-NLS-1$
            + "`nextStartLine` is the line to resume from.\n\n" //$NON-NLS-1$
            + "## Continuation (large files)\n\n" //$NON-NLS-1$
            + "When `truncated: true`, call this tool again with the same `projectName` and " //$NON-NLS-1$
            + "`modulePath` and `startLine` set to the `nextStartLine` value to fetch the next chunk. " //$NON-NLS-1$
            + "Repeat until `truncated` is absent.\n\n" //$NON-NLS-1$
            + "## Empty file\n\n" //$NON-NLS-1$
            + "For an empty file, `startLine`/`endLine` are omitted, `totalLines` is `0`, and the bsl " //$NON-NLS-1$
            + "block is empty.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "- Whole module: `{ \"projectName\": \"MyProject\", " //$NON-NLS-1$
            + "\"modulePath\": \"CommonModules/MyModule/Module.bsl\" }`\n" //$NON-NLS-1$
            + "- Lines 100-150: add `\"startLine\": 100, \"endLine\": 150`.\n" //$NON-NLS-1$
            + "- From line 200 to the end: add `\"startLine\": 200` only.\n\n" //$NON-NLS-1$
            + "## Notes\n\n" //$NON-NLS-1$
            + "- The returned source is clean BSL with no line-number prefixes; line numbers live in " //$NON-NLS-1$
            + "the frontmatter only.\n" //$NON-NLS-1$
            + "- The source may contain Cyrillic identifiers (procedure/variable names); this tool " //$NON-NLS-1$
            + "returns the text verbatim and is not dialect-aware.\n" //$NON-NLS-1$
            + "- To round-trip a safe edit: read here, take `contentHash`, then pass it as " //$NON-NLS-1$
            + "`write_module_source`'s `expectedHash`."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        int startLine = JsonUtils.extractIntArgument(params, "startLine", -1); //$NON-NLS-1$
        int endLine = JsonUtils.extractIntArgument(params, "endLine", -1); //$NON-NLS-1$

        err = JsonUtils.requireArgument(params, "modulePath", ". Example: 'CommonModules/MyModule/Module.bsl'"); //$NON-NLS-1$ //$NON-NLS-2$
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

        // Get file
        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (!file.exists())
        {
            return ToolResult.error("File not found: src/" + modulePath + //$NON-NLS-1$
                   ". Use format like 'CommonModules/ModuleName/Module.bsl' or " + //$NON-NLS-1$
                   "'Documents/DocName/ObjectModule.bsl'").toJson(); //$NON-NLS-1$
        }

        try
        {
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
                return formatOutput(projectName, modulePath, allLines, 0, 0, 0, false, contentHash);
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

            String output = formatOutput(projectName, modulePath, allLines, from, to, totalLines, truncated, contentHash);

            // Extension interception (module-level, best-effort): if this module is
            // adopted/intercepted across the base<->extension boundary, append the
            // links below the code. The footer touches the EMF/Xtext model, so it MUST
            // run on the UI thread like the sibling code tools (read_method_source /
            // get_module_structure) - the rest of this read is plain file I/O off-thread.
            String interception = interceptionFooterOnUi(project, modulePath);
            return interception != null ? output + interception : output;
        }
        catch (Exception e)
        {
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
    static String formatOutput(String projectName, String modulePath, List<String> allLines,
        int from, int to, int totalLines, boolean truncated, String contentHash)
    {
        FrontMatter fm = FrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath); //$NON-NLS-1$

        if (contentHash != null)
        {
            fm.put("contentHash", contentHash); //$NON-NLS-1$
        }

        if (totalLines > 0)
        {
            fm.put("startLine", from) //$NON-NLS-1$
                .put("endLine", to); //$NON-NLS-1$
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

    /**
     * Computes the module-level extension-interception footer on the UI thread.
     * Loading the BSL module and navigating the platform interception service touch
     * the EMF/Xtext model, which must happen on the UI thread (the sibling code tools
     * read_method_source / get_module_structure already do their model access there).
     * The rest of this tool is plain file I/O and stays off-thread. Best-effort: any
     * failure (model not indexed, no display) yields {@code null} and the footer is
     * simply omitted, leaving the code read unaffected.
     *
     * @param project the project handle
     * @param modulePath the module path from src/
     * @return the markdown footer, or {@code null} when there is none / on failure
     */
    private static String interceptionFooterOnUi(IProject project, String modulePath)
    {
        AtomicReference<String> ref = new AtomicReference<>();
        Runnable task = () -> ref.set(InterceptionUtils.moduleFooter(BslModuleUtils.loadModule(project, modulePath)));
        try
        {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display.getThread() == Thread.currentThread())
            {
                task.run();
            }
            else
            {
                display.syncExec(task);
            }
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("read_module_source: interception footer unavailable: " + e.getMessage()); //$NON-NLS-1$
        }
        return ref.get();
    }
}
