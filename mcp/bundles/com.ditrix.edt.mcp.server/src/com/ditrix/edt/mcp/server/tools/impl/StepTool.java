/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Steps a suspended thread (over / into / out) and waits for the next SUSPEND
 * event, returning a fresh snapshot via the same JSON shape as
 * {@link WaitForBreakTool}.
 */
public class StepTool implements IMcpTool
{
    public static final String NAME = "step"; //$NON-NLS-1$
    private static final int DEFAULT_TIMEOUT = 30;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Step a suspended debug thread. kind ∈ {over, into, out}. " //$NON-NLS-1$
            + "Blocks until the next SUSPEND event (or timeout) and returns the new frame snapshot."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("threadId", "Thread id from wait_for_break (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("kind", "Step kind: over, into, out (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("timeout", "Wait window in seconds (default: 30)") //$NON-NLS-1$ //$NON-NLS-2$
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
        long threadId = parseLong(params.get("threadId")); //$NON-NLS-1$
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        int timeout = JsonUtils.extractIntArgument(params, "timeout", DEFAULT_TIMEOUT); //$NON-NLS-1$
        if (timeout < 1) timeout = 1;

        if (threadId <= 0)
        {
            return ToolResult.error("threadId is required").toJson(); //$NON-NLS-1$
        }
        if (kind == null || kind.isEmpty())
        {
            return ToolResult.error("kind is required (over/into/out)").toJson(); //$NON-NLS-1$
        }

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = registry.getThread(threadId);
        if (thread == null)
        {
            return ToolResult.error("stale threadId — call wait_for_break again").toJson(); //$NON-NLS-1$
        }
        if (!(thread instanceof IStep))
        {
            return ToolResult.error("thread does not support stepping").toJson(); //$NON-NLS-1$
        }
        IStep stepper = (IStep) thread;

        String appId = DebugSessionRegistry.findApplicationIdFor(thread);
        if (appId == null)
        {
            return ToolResult.error("could not determine applicationId for thread").toJson(); //$NON-NLS-1$
        }

        try
        {
            switch (kind.toLowerCase())
            {
                case "over": //$NON-NLS-1$
                    if (!stepper.canStepOver())
                    {
                        return ToolResult.error("cannot step over").toJson(); //$NON-NLS-1$
                    }
                    stepper.stepOver();
                    break;
                case "into": //$NON-NLS-1$
                    if (!stepper.canStepInto())
                    {
                        return ToolResult.error("cannot step into").toJson(); //$NON-NLS-1$
                    }
                    stepper.stepInto();
                    break;
                case "out": //$NON-NLS-1$
                case "return": //$NON-NLS-1$
                    if (!stepper.canStepReturn())
                    {
                        return ToolResult.error("cannot step out").toJson(); //$NON-NLS-1$
                    }
                    stepper.stepReturn();
                    break;
                default:
                    return ToolResult.error("unknown kind: " + kind).toJson(); //$NON-NLS-1$
            }

            DebugSessionRegistry.SuspendSnapshot snapshot =
                registry.waitForSuspend(appId, timeout * 1000L);
            if (snapshot == null)
            {
                return ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put("reason", "timeout") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            return WaitForBreakTool.buildSnapshotResponse(snapshot, registry);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in step", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static long parseLong(String s)
    {
        if (s == null || s.isEmpty()) return -1L;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException nfe) { return -1L; }
    }
}
