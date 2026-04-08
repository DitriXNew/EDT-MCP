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

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Blocks until a SUSPEND event is observed for the given application id, then
 * returns a snapshot of the suspended thread (top frame info + stack).
 *
 * <p>If the application is already suspended at the time of the call, returns
 * immediately. If the timeout expires without a suspend, returns
 * {@code {hit:false, reason:"timeout"}} — the launch is NOT terminated.
 */
public class WaitForBreakTool implements IMcpTool
{
    public static final String NAME = "wait_for_break"; //$NON-NLS-1$
    private static final int DEFAULT_TIMEOUT = 60;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Wait for a debug suspend event (e.g. breakpoint hit) on the given " //$NON-NLS-1$
            + "application. Returns the suspended thread/frame snapshot, or {hit:false} on timeout. " //$NON-NLS-1$
            + "Does NOT terminate the launch on timeout — call again to keep waiting."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("applicationId", "Application id of the running debug session (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Wait window in seconds (default: 60)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        if (timeout < 1)
        {
            timeout = 1;
        }
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required").toJson(); //$NON-NLS-1$
        }

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        try
        {
            DebugSessionRegistry.SuspendSnapshot snapshot =
                registry.waitForSuspend(applicationId, timeout * 1000L);
            if (snapshot == null)
            {
                return ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put("reason", "timeout") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            return buildSnapshotResponse(snapshot, registry);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for break").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in wait_for_break", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Builds the JSON response for a suspend snapshot. Walks the thread stack
     * and registers each frame with a stable id so that follow-up tools
     * (get_variables, evaluate_expression, step) can refer back to it.
     */
    static String buildSnapshotResponse(DebugSessionRegistry.SuspendSnapshot snapshot,
            DebugSessionRegistry registry) throws Exception
    {
        IThread thread = snapshot.thread;
        List<Map<String, Object>> frames = new ArrayList<>();
        IStackFrame[] stackFrames = thread.getStackFrames();
        for (int i = 0; i < stackFrames.length; i++)
        {
            IStackFrame f = stackFrames[i];
            long frameRef = registry.registerFrame(f);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("frameIndex", i); //$NON-NLS-1$
            dto.put("frameRef", frameRef); //$NON-NLS-1$
            dto.put("name", f.getName()); //$NON-NLS-1$
            try
            {
                dto.put("line", f.getLineNumber()); //$NON-NLS-1$
            }
            catch (Exception ex)
            {
                // ignore
            }
            frames.add(dto);
        }
        ToolResult result = ToolResult.success()
            .put("hit", true) //$NON-NLS-1$
            .put("threadId", snapshot.threadId) //$NON-NLS-1$
            .put("threadName", thread.getName()) //$NON-NLS-1$
            .put("frames", frames); //$NON-NLS-1$
        if (!frames.isEmpty())
        {
            result.put("topFrameRef", frames.get(0).get("frameRef")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toJson();
    }
}
