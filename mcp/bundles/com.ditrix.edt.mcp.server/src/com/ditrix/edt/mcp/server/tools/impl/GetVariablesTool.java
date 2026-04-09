/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.VariableSerializer;

/**
 * Returns variables visible in a stack frame of a suspended thread. Frames can
 * be referenced either by {@code frameRef} (preferred — returned from
 * {@code wait_for_break}) or by {@code threadId + frameIndex} (re-resolved
 * against the live IThread).
 *
 * <p>If {@code expandPath} is supplied, walks the dot-separated path from frame
 * variables and returns its children instead — used to drill into Структуры,
 * Соответствия, Массивы, etc., without exploding the response on the first call.
 */
public class GetVariablesTool implements IMcpTool
{
    public static final String NAME = "get_variables"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read variables from a stack frame of a suspended debug thread. " //$NON-NLS-1$
            + "Pass frameRef from wait_for_break (preferred) or threadId+frameIndex. " //$NON-NLS-1$
            + "Use expandPath to drill into nested structures (dot-separated)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("frameRef", "Stable frame reference returned from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Thread id (alternative to frameRef)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("frameIndex", "0-based frame index when using threadId") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expandPath", "Dot-separated path to expand a nested variable") //$NON-NLS-1$ //$NON-NLS-2$
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
        long frameRef = parseLong(params.get("frameRef")); //$NON-NLS-1$
        long threadId = parseLong(params.get("threadId")); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        String expandPath = JsonUtils.extractStringArgument(params, "expandPath"); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();

        try
        {
            IStackFrame frame = null;
            if (frameRef > 0)
            {
                frame = registry.getFrame(frameRef);
                if (frame == null)
                {
                    return ToolResult.error("stale frameRef — call wait_for_break again").toJson(); //$NON-NLS-1$
                }
            }
            else if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("stale threadId — call wait_for_break again").toJson(); //$NON-NLS-1$
                }
                IStackFrame[] frames = thread.getStackFrames();
                if (frameIndex < 0 || frameIndex >= frames.length)
                {
                    return ToolResult.error("frameIndex out of range (0.." //$NON-NLS-1$
                            + (frames.length - 1) + ")").toJson(); //$NON-NLS-1$
                }
                frame = frames[frameIndex];
            }
            else
            {
                return ToolResult.error("Provide either frameRef or threadId").toJson(); //$NON-NLS-1$
            }

            List<Map<String, Object>> vars;
            if (expandPath != null && !expandPath.isEmpty())
            {
                IVariable resolved = VariableSerializer.resolvePath(frame, expandPath);
                if (resolved == null)
                {
                    return ToolResult.error("expandPath not found: " + expandPath).toJson(); //$NON-NLS-1$
                }
                vars = VariableSerializer.serializeChildren(resolved, registry);
            }
            else
            {
                vars = VariableSerializer.serializeFrame(frame, registry);
            }
            return ToolResult.success()
                .put("variables", vars) //$NON-NLS-1$
                .put("count", vars.size()) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_variables", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static long parseLong(String s)
    {
        if (s == null || s.isEmpty()) return -1L;
        try {
            double d = Double.parseDouble(s.trim());
            if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return -1L;
            return (long) d;
        } catch (NumberFormatException nfe) { return -1L; }
    }
}
