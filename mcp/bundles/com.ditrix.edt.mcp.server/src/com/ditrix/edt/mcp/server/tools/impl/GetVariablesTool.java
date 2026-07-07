/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IVariable;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugFrameResolver;
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
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("variables", "Variables in the frame or expanded node, with name/value/type") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of variables returned") //$NON-NLS-1$ //$NON-NLS-2$
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
        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        String expandPath = JsonUtils.extractStringArgument(params, "expandPath"); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();

        try
        {
            DebugFrameResolver.Resolution fr = DebugFrameResolver.resolve(registry, frameRef, threadId, frameIndex);
            if (fr.error != null)
            {
                return fr.error;
            }
            return serializeVariables(registry, fr.frame, expandPath);
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_variables", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Serializes either the children of the variable at {@code expandPath} (when
     * non-empty) or the whole frame, returning the success JSON — or the exact
     * "expandPath not found" error the inline branch produced.
     */
    private static String serializeVariables(DebugSessionRegistry registry, IStackFrame frame, String expandPath)
        throws Exception
    {
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

}
