/**
 * Copyright (c) 2026 Diversus
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

/**
 * Tool to read BSL module source code (whole file or line range).
 * Supports reading with line numbers, range extraction, and large file protection.
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
               "Returns source with line numbers. Supports reading full file or a specific line range. " + //$NON-NLS-1$
               "Max 5000 lines per call."; //$NON-NLS-1$
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
                return "## " + modulePath + "\n\n**Lines:** 0 (empty file)\n\n```bsl\n```\n"; //$NON-NLS-1$ //$NON-NLS-2$
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

            // Build output
            StringBuilder sb = new StringBuilder();
            sb.append("## ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("**Lines:** ").append(from).append("-").append(to); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(" of ").append(totalLines).append(" total"); //$NON-NLS-1$ //$NON-NLS-2$
            if (truncated)
            {
                sb.append(" (truncated to ").append(MAX_LINES).append(" lines)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n\n"); //$NON-NLS-1$

            sb.append("```bsl\n"); //$NON-NLS-1$
            for (int i = from - 1; i < to; i++)
            {
                sb.append(String.format("%d: %s\n", i + 1, allLines.get(i))); //$NON-NLS-1$
            }
            sb.append("```\n"); //$NON-NLS-1$

            return sb.toString();
        }
        catch (Exception e)
        {
            return "Error reading file: " + e.getMessage(); //$NON-NLS-1$
        }
    }
}
