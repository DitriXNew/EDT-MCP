/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.ProfilingSupport;

/**
 * Stops 1C performance measurement on the active
 * debug target. Counterpart to {@code start_profiling}: it deterministically
 * switches profiling OFF for the given {@code applicationId}.
 *
 * <p><b>Idempotent.</b> If profiling is not currently active for the
 * application id (never started, or already stopped), this returns a benign
 * "not active" success result rather than an error. After stopping, call
 * {@code get_profiling_results} to retrieve the collected coverage.
 *
 * <p>The on/off state is shared with {@link StartProfilingTool} and keyed by
 * {@code applicationId} — see {@link StartProfilingTool#isProfilingActive(String)}.
 * The underlying EDT API ({@code IProfilingService.toggleProfiling}) is a single
 * toggle primitive with no public stop or "is active" query, so this tool only
 * toggles when the shared state says profiling is currently ON.
 */
public class StopProfilingTool implements IMcpTool
{
    public static final String NAME = "stop_profiling"; //$NON-NLS-1$

    /** Output key: whether profiling is still active after the call. */
    private static final String KEY_ACTIVE = "active"; //$NON-NLS-1$

    /** Output key: whether profiling was actually toggled off by this call. */
    private static final String KEY_STOPPED = "stopped"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Stop performance measurement on the active debug target. " //$NON-NLS-1$
            + "Counterpart to start_profiling: deterministically switches profiling off. " //$NON-NLS-1$
            + "Idempotent: if profiling is not active for this applicationId it returns a benign result, not an error. " //$NON-NLS-1$
            + "Call get_profiling_results afterwards to retrieve the collected coverage."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the running debug session (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_ACTIVE, "Whether profiling is still active after the call") //$NON-NLS-1$
            .booleanProperty(KEY_STOPPED, "Whether profiling was actually toggled off") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id the result refers to") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable summary of the outcome") //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, McpKeys.APPLICATION_ID);
        if (err != null)
        {
            return err;
        }
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);

        try
        {
            // Idempotent: nothing to do if profiling was never started (or already
            // stopped) for this id. Return a benign success, NOT an error — toggling
            // here would silently switch profiling ON.
            if (!StartProfilingTool.isProfilingActive(applicationId))
            {
                return ToolResult.success()
                    .put(KEY_ACTIVE, false)
                    .put(KEY_STOPPED, false)
                    .put(McpKeys.APPLICATION_ID, applicationId)
                    .put(McpKeys.MESSAGE, "Profiling was not active for this applicationId; nothing to stop.") //$NON-NLS-1$
                    .toJson();
            }

            // Find active debug target
            IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
            if (target == null)
            {
                // The session is gone, so profiling cannot still be running on the
                // debug server. Clear our state and report it benignly.
                StartProfilingTool.markInactive(applicationId);
                return ToolResult.success()
                    .put(KEY_ACTIVE, false)
                    .put(KEY_STOPPED, true)
                    .put(McpKeys.APPLICATION_ID, applicationId)
                    .put(McpKeys.MESSAGE, "No active debug target for applicationId: " + applicationId //$NON-NLS-1$
                        + "; the debug session has ended. Profiling state cleared.") //$NON-NLS-1$
                    .toJson();
            }

            // Resolve the profiling service + profile target and flip profiling off.
            // Gated above on our shared ON state, so this toggle deterministically
            // switches profiling OFF.
            String toggleError = ProfilingSupport.toggleProfiling(target);
            if (toggleError != null)
            {
                return ToolResult.error(toggleError).toJson();
            }

            StartProfilingTool.markInactive(applicationId);

            Activator.logInfo("Profiling stopped via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put(KEY_ACTIVE, false)
                .put(KEY_STOPPED, true)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(McpKeys.MESSAGE, "Profiling stopped. Call get_profiling_results to retrieve the collected coverage.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in stop_profiling", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
