/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.FrontMatter;

/**
 * Tool to read BSL module source code (whole file or line range).
 * Returns YAML frontmatter metadata (startLine, endLine, totalLines; plus
 * {@code truncated: true} only when the range was clamped by MAX_LINES)
 * followed by the source in a fenced bsl block. Supports range extraction and
 * large file protection (max 5000 lines per call).
 */
public class ReadModuleSourceTool implements IMcpTool
{
    public static final String NAME = "read_module_source"; //$NON-NLS-1$

    /** Maximum lines to return in a single call */
    private static final int MAX_LINES = 5000;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read BSL module source code from EDT project. " + //$NON-NLS-1$
               "Returns YAML frontmatter (projectName, module, startLine, endLine, totalLines; " + //$NON-NLS-1$
               "plus truncated: true when the range was clamped by the 5000-line limit) " + //$NON-NLS-1$
               "followed by clean source in a fenced bsl block (no line-number prefixes). " + //$NON-NLS-1$
               "For an empty file, startLine/endLine are omitted and totalLines is 0. " + //$NON-NLS-1$
               "Supports reading full file or a specific line range. Max 5000 lines per call."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path from src/ folder, e.g. 'CommonModules/MyModule/Module.bsl' or " + //$NON-NLS-1$
                "'Documents/SalesOrder/ObjectModule.bsl' (required)", true) //$NON-NLS-1$
            .integerProperty("startLine", //$NON-NLS-1$
                "Start line number (1-based, inclusive). If omitted, reads from beginning.") //$NON-NLS-1$
            .integerProperty("endLine", //$NON-NLS-1$
                "End line number (1-based, inclusive). If omitted, reads to end.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        int startLine = JsonUtils.extractIntArgument(params, "startLine", -1); //$NON-NLS-1$
        int endLine = JsonUtils.extractIntArgument(params, "endLine", -1); //$NON-NLS-1$

        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required. Example: 'CommonModules/MyModule/Module.bsl'"; //$NON-NLS-1$
        }

        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        // Get file
        IFile file = project.getFile(new Path("src").append(modulePath)); //$NON-NLS-1$
        if (!file.exists())
        {
            return "Error: File not found: src/" + modulePath + //$NON-NLS-1$
                   ". Use format like 'CommonModules/ModuleName/Module.bsl' or " + //$NON-NLS-1$
                   "'Documents/DocName/ObjectModule.bsl'"; //$NON-NLS-1$
        }

        try
        {
            // Read file content with UTF-8 BOM detection
            List<String> allLines = BslModuleUtils.readFileLines(file);

            int totalLines = allLines.size();

            // Handle empty file
            if (totalLines == 0)
            {
                return formatOutput(projectName, modulePath, allLines, 0, 0, 0, false);
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

            // Clamp to MAX_LINES
            boolean truncated = false;
            if (to - from + 1 > MAX_LINES)
            {
                to = from + MAX_LINES - 1;
                truncated = true;
            }

            return formatOutput(projectName, modulePath, allLines, from, to, totalLines, truncated);
        }
        catch (Exception e)
        {
            return "Error reading file: " + e.getMessage(); //$NON-NLS-1$
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
     * @param truncated true if the returned range was clamped by MAX_LINES
     * @return formatted result string
     */
    static String formatOutput(String projectName, String modulePath, List<String> allLines,
        int from, int to, int totalLines, boolean truncated)
    {
        FrontMatter fm = FrontMatter.create()
            .put("projectName", projectName) //$NON-NLS-1$
            .put("module", modulePath); //$NON-NLS-1$

        if (totalLines > 0)
        {
            fm.put("startLine", from) //$NON-NLS-1$
                .put("endLine", to); //$NON-NLS-1$
        }

        fm.put("totalLines", totalLines); //$NON-NLS-1$

        if (truncated)
        {
            fm.put("truncated", true); //$NON-NLS-1$
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
}
