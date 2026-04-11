/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IFile;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;

/**
 * Removes a previously set line breakpoint, either by its marker id or by
 * (project + module + line) coordinates.
 */
public class RemoveBreakpointTool implements IMcpTool
{
    public static final String NAME = "remove_breakpoint"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove a 1C BSL line breakpoint. " //$NON-NLS-1$
            + "Either pass breakpointId (returned from set_breakpoint) " //$NON-NLS-1$
            + "or projectName+module+lineNumber to look it up by coordinates."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("breakpointId", "Marker id returned by set_breakpoint") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("projectName", "EDT project name (when looking up by coordinates)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("module", "EDT module path or absolute path (when looking up by coordinates)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("lineNumber", "1-based line number (when looking up by coordinates)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        long breakpointId = JsonUtils.extractLongArgument(params, "breakpointId", -1L); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String module = JsonUtils.extractStringArgument(params, "module"); //$NON-NLS-1$
        int lineNumber = JsonUtils.extractIntArgument(params, "lineNumber", -1); //$NON-NLS-1$

        try
        {
            boolean removed;
            if (breakpointId > 0)
            {
                removed = BreakpointUtils.removeBreakpointById(breakpointId);
            }
            else
            {
                if (module == null || module.isEmpty() || lineNumber < 1)
                {
                    return ToolResult.error("Provide either breakpointId or module+lineNumber").toJson(); //$NON-NLS-1$
                }
                IFile file = BreakpointUtils.resolveModuleFile(projectName, module);
                if (file == null || !file.exists())
                {
                    return ToolResult.error("Module file not found: " + module).toJson(); //$NON-NLS-1$
                }
                removed = BreakpointUtils.removeBreakpointAt(file, lineNumber);
            }
            return ToolResult.success().put("removed", removed).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to remove breakpoint", e); //$NON-NLS-1$
            return ToolResult.error("Failed to remove breakpoint: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
