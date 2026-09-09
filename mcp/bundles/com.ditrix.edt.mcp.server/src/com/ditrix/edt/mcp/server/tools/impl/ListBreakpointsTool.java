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
import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IBreakpointManager;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.ILineBreakpoint;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BreakpointUtils;

/**
 * Lists registered line and workspace-wide exception breakpoints.
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
        return "Review configured BSL line breakpoints and workspace-wide break-on-error state. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('list_breakpoints')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Optional project filter") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("breakpoints", "Configured line and workspace-wide exception breakpoints") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of breakpoints returned") //$NON-NLS-1$ //$NON-NLS-2$
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

        for (IBreakpoint bp : bpManager.getBreakpoints()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            // GUARDED, because this lookup can throw: the platform recognises a breakpoint by
            // interface, and its marker may be stale by the time the listing runs. Unguarded,
            // one such entry threw out of the loop and the whole call failed - the caller lost
            // every OTHER breakpoint over one it could not have named anyway.
            IMarker m;
            try
            {
                m = bp.getMarker();
            }
            catch (Exception unreadable) // NOSONAR: an entry this tool cannot name is skipped
            {
                continue;
            }
            if (m == null || m.getResource() == null)
            {
                continue;
            }
            if (BreakpointUtils.isExceptionBreakpoint(bp))
            {
                out.add(createExceptionBreakpointDto(bp, m));
                continue;
            }
            // This tool advertises BSL breakpoints. Another Eclipse debug model may contribute a
            // method, watch or custom breakpoint on a project resource; labelling one of those
            // 'line' would hand the caller something that is not a BSL source line at all.
            if (!BreakpointUtils.isBslLineBreakpoint(bp))
            {
                continue;
            }
            // IResource.getProject() is null for a marker on the workspace root —
            // non-exception root markers are outside this tool's BSL breakpoint surface.
            IProject project = m.getResource().getProject();
            if (project == null)
            {
                continue;
            }
            if (projectFilter != null && !projectFilter.isEmpty()
                && !projectFilter.equals(project.getName()))
            {
                continue;
            }
            out.add(createLineBreakpointDto(bp, m, project));
        }
        return ToolResult.success().put("breakpoints", out).put("count", out.size()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    static Map<String, Object> createLineBreakpointDto(IBreakpoint bp, IMarker marker,
        IProject project)
    {
        Map<String, Object> dto = baseDto(marker);
        dto.put("kind", "line"); //$NON-NLS-1$ //$NON-NLS-2$
        dto.put("project", project.getName()); //$NON-NLS-1$
        dto.put("file", marker.getResource().getFullPath().toString()); //$NON-NLS-1$
        try
        {
            if (bp instanceof ILineBreakpoint)
            {
                dto.put("lineNumber", ((ILineBreakpoint)bp).getLineNumber()); //$NON-NLS-1$
                dto.putAll(BreakpointUtils.readLineBreakpointConfiguration(bp, marker));
            }
            addCommonState(dto, bp);
        }
        catch (Exception ex)
        {
            // An exception with a NO message would store null, which the shared Gson drops - the entry
                                // would then look ordinary while its state was never read. Fall back to the type.
                dto.put("error", ex.getMessage() != null && !ex.getMessage().isEmpty() //$NON-NLS-1$
                                    ? ex.getMessage()
                                    : ex.getClass().getSimpleName());
        }
        return dto;
    }

    static Map<String, Object> createExceptionBreakpointDto(IBreakpoint bp, IMarker marker)
    {
        Map<String, Object> dto = baseDto(marker);
        dto.put("kind", "exception"); //$NON-NLS-1$ //$NON-NLS-2$
        dto.put("workspaceWide", true); //$NON-NLS-1$
        try
        {
            dto.putAll(BreakpointUtils.readExceptionBreakpointConfiguration(marker));
            addCommonState(dto, bp);
        }
        catch (Exception ex)
        {
            // An exception with a NO message would store null, which the shared Gson drops - the entry
                                // would then look ordinary while its state was never read. Fall back to the type.
                dto.put("error", ex.getMessage() != null && !ex.getMessage().isEmpty() //$NON-NLS-1$
                                    ? ex.getMessage()
                                    : ex.getClass().getSimpleName());
        }
        return dto;
    }

    private static Map<String, Object> baseDto(IMarker marker)
    {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("breakpointId", marker.getId()); //$NON-NLS-1$
        return dto;
    }

    private static void addCommonState(Map<String, Object> dto, IBreakpoint bp) throws Exception
    {
        dto.put("enabled", bp.isEnabled()); //$NON-NLS-1$
        dto.put("modelId", bp.getModelIdentifier()); //$NON-NLS-1$
    }
}
