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

import org.eclipse.debug.core.model.IDebugTarget;
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
 *
 * <p>{@code applicationId} may be a real id from {@code get_applications} or the
 * synthetic {@code attach:<configName>} id reported by {@code debug_status} for
 * Attach launches. If omitted and exactly one EDT debug launch is active, that
 * launch is auto-resolved.
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
        return "Wait for a debug suspend event (e.g. breakpoint hit) on the given application. " //$NON-NLS-1$
            + "Returns the suspended thread/frame snapshot, or {hit:false} on timeout. " //$NON-NLS-1$
            + "applicationId may be real or synthetic 'attach:<configName>'. " //$NON-NLS-1$
            + "If omitted and exactly one EDT debug launch is active, that launch is used. " //$NON-NLS-1$
            + "Does NOT terminate the launch on timeout — call again to keep waiting."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id of the running debug session (real or 'attach:<configName>'). " //$NON-NLS-1$
                    + "Optional if exactly one debug launch is active.") //$NON-NLS-1$
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

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        boolean autoResolved = false;
        if (applicationId == null || applicationId.isEmpty())
        {
            applicationId = DebugSessionRegistry.findLoneActiveApplicationId();
            if (applicationId == null)
            {
                return ToolResult.error("applicationId is required — no single active debug launch " //$NON-NLS-1$
                    + "available for auto-resolution. Use debug_status to list active launches.").toJson(); //$NON-NLS-1$
            }
            autoResolved = true;
        }

        // Proactively scan live targets for threads already suspended before the
        // listener was registered (e.g. manual breakpoint hit in EDT, or suspend
        // that happened between debug_launch and this call).
        scanForAlreadySuspended(registry, applicationId);

        try
        {
            DebugSessionRegistry.SuspendSnapshot snapshot =
                registry.waitForSuspend(applicationId, timeout * 1000L);
            if (snapshot == null)
            {
                ToolResult r = ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put("reason", "timeout") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("applicationId", applicationId); //$NON-NLS-1$
                if (autoResolved)
                {
                    r.put("autoResolved", true); //$NON-NLS-1$
                }
                return r.toJson();
            }
            return buildSnapshotResponse(snapshot, registry, applicationId, autoResolved);
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
     * Scans the active debug target for threads that are already suspended but
     * were missed by the registry listener (e.g. suspend happened before the
     * listener was installed). If a suspended thread is found and the registry
     * has no snapshot for this appId, injects a synthetic snapshot.
     */
    private static void scanForAlreadySuspended(DebugSessionRegistry registry, String applicationId)
    {
        try
        {
            if (registry.hasSnapshot(applicationId))
            {
                return; // already tracked
            }
            IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
            if (target == null || target.isTerminated())
            {
                return;
            }
            for (IThread thread : target.getThreads())
            {
                if (thread.isSuspended())
                {
                    registry.injectSuspend(applicationId, thread);
                    return;
                }
            }
        }
        catch (Exception ex)
        {
            // best effort — fall through to normal wait
        }
    }

    /**
     * Builds the JSON response for a suspend snapshot. Walks the thread stack
     * and registers each frame with a stable id so that follow-up tools
     * (get_variables, evaluate_expression, step) can refer back to it.
     */
    static String buildSnapshotResponse(DebugSessionRegistry.SuspendSnapshot snapshot,
            DebugSessionRegistry registry, String applicationId, boolean autoResolved) throws Exception
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
            .put("applicationId", applicationId) //$NON-NLS-1$
            .put("frames", frames); //$NON-NLS-1$
        if (autoResolved)
        {
            result.put("autoResolved", true); //$NON-NLS-1$
        }
        if (!frames.isEmpty())
        {
            result.put("topFrameRef", frames.get(0).get("frameRef")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result.toJson();
    }
}
