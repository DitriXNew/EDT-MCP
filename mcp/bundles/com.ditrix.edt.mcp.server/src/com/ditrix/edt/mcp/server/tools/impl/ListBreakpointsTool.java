/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Lists currently registered line breakpoints, optionally filtered by project.
 */
public class ListBreakpointsTool implements IMcpTool
{
    public static final String NAME = "list_breakpoints"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List active line breakpoints. Optionally filter by projectName."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Optional project filter") //$NON-NLS-1$ //$NON-NLS-2$
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
        String projectFilter = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            return ToolResult.error("DebugPlugin not available").toJson(); //$NON-NLS-1$
        }
        IBreakpointManager bpManager = debugPlugin.getBreakpointManager();
        List<Map<String, Object>> out = new ArrayList<>();

        for (IBreakpoint bp : bpManager.getBreakpoints())
        {
            IMarker m = bp.getMarker();
            if (m == null || m.getResource() == null)
            {
                continue;
            }
            if (projectFilter != null && !projectFilter.isEmpty()
                && !projectFilter.equals(m.getResource().getProject().getName()))
            {
                continue;
            }
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("breakpointId", m.getId()); //$NON-NLS-1$
            dto.put("project", m.getResource().getProject().getName()); //$NON-NLS-1$
            dto.put("file", m.getResource().getFullPath().toString()); //$NON-NLS-1$
            try
            {
                if (bp instanceof ILineBreakpoint)
                {
                    dto.put("lineNumber", ((ILineBreakpoint) bp).getLineNumber()); //$NON-NLS-1$
                }
                dto.put("enabled", bp.isEnabled()); //$NON-NLS-1$
                dto.put("modelId", bp.getModelIdentifier()); //$NON-NLS-1$
            }
            catch (Exception ex)
            {
                dto.put("error", ex.getMessage()); //$NON-NLS-1$
            }
            out.add(dto);
        }
        return ToolResult.success().put("breakpoints", out).put("count", out.size()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
