/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;

/**
 * Tool to read BSL module source code (entire file or a specific line range).
 * Returns source code with line numbers for easy reference.
 */
public class ReadModuleSourceTool implements IMcpTool
{
    public static final String NAME = "read_module_source"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read BSL module source code (entire file or specific line range). " + //$NON-NLS-1$
               "Returns source code with line numbers. Use startLine/endLine to read " + //$NON-NLS-1$
               "portions of large modules instead of loading the entire file."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringProperty("modulePath", //$NON-NLS-1$
                "Path to BSL file relative to project's src folder " + //$NON-NLS-1$
                "(e.g. 'CommonModules/MyModule/Module.bsl', " + //$NON-NLS-1$
                "'Documents/SalesOrder/Ext/ObjectModule.bsl')", true) //$NON-NLS-1$
            .integerProperty("startLine", //$NON-NLS-1$
                "Start line number (1-based). If omitted, reads from the beginning") //$NON-NLS-1$
            .integerProperty("endLine", //$NON-NLS-1$
                "End line number (1-based, inclusive). If omitted, reads to the end") //$NON-NLS-1$
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
            String safeName = modulePath.replace("/", "-").replace("\\", "-") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                .replace(".bsl", "").toLowerCase(); //$NON-NLS-1$ //$NON-NLS-2$
            return "source-" + safeName + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "module-source.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String modulePath = JsonUtils.extractStringArgument(params, "modulePath"); //$NON-NLS-1$
        String startLineStr = JsonUtils.extractStringArgument(params, "startLine"); //$NON-NLS-1$
        String endLineStr = JsonUtils.extractStringArgument(params, "endLine"); //$NON-NLS-1$

        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        if (modulePath == null || modulePath.isEmpty())
        {
            return "Error: modulePath is required"; //$NON-NLS-1$
        }

        int startLine = parseIntParam(startLineStr, 0);
        int endLine = parseIntParam(endLineStr, 0);

        // Resolve project and file
        IProject project = BslModuleUtils.resolveProject(projectName);
        if (project == null)
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }

        IFile file = BslModuleUtils.resolveModuleFile(project, modulePath);
        if (file == null)
        {
            return "Error: Module not found: " + modulePath; //$NON-NLS-1$
        }

        // Count total lines
        int totalLines = BslModuleUtils.countLines(file);
        if (totalLines < 0)
        {
            return "Error: Failed to read file"; //$NON-NLS-1$
        }

        // Read content
        String content = BslModuleUtils.readFileContent(file, startLine, endLine);
        if (content == null)
        {
            return "Error: Failed to read file content"; //$NON-NLS-1$
        }

        // Format output
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(modulePath).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("**Total lines:** ").append(totalLines); //$NON-NLS-1$

        if (startLine > 0 || endLine > 0)
        {
            int effectiveStart = Math.max(startLine, 1);
            int effectiveEnd = endLine > 0 ? Math.min(endLine, totalLines) : totalLines;
            sb.append(" | **Showing:** lines ").append(effectiveStart) //$NON-NLS-1$
                .append("-").append(effectiveEnd); //$NON-NLS-1$
        }
        sb.append("\n\n"); //$NON-NLS-1$

        sb.append("```bsl\n"); //$NON-NLS-1$
        sb.append(content);
        sb.append("```\n"); //$NON-NLS-1$

        return sb.toString();
    }

    /**
     * Parses an integer parameter string, handling both integer and double formats.
     */
    private int parseIntParam(String value, int defaultValue)
    {
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        try
        {
            return (int) Double.parseDouble(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
