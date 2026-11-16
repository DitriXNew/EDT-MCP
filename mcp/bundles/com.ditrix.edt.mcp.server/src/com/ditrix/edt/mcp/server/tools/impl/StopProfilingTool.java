/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.debug.core.model.IDebugTarget;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.ProfilingSupport;
import com.ditrix.edt.mcp.server.utils.ProfilingFiles;

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
        return "Stop BSL performance measurement and optionally name the newly collected measurement.";
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the running debug session (required)", true) //$NON-NLS-1$
            .stringProperty("name", "Optional measurement name; a new UUID must arrive within 5 seconds.")
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
            .stringProperty("measurementId", "New measurement UUID, only when observed after stopping")
            .stringProperty("name", "Observed measurement name, only when the new measurement arrived")
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
        String name = JsonUtils.extractStringArgument(params, "name");

        try
        {
            if (name != null) { ProfilingFiles.validateName(name); }
            IDebugTarget live = DebugSessionRegistry.findActiveTarget(applicationId);
            StartProfilingTool.reconcile(applicationId, live);
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

            IDebugTarget target = live;
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
            IProfilingService service = ProfilingSupport.service();
            Set<UUID> before = ProfilingSupport.ids(service);
            UUID sessionId = ProfilingSupport.sessionId(service, target); // read before the toggle drops it
            if (name != null && service.getResults().stream().anyMatch(r -> name.equalsIgnoreCase(r.getName())))
            {
                return ToolResult.error("Measurement name '" + name
                    + "' already exists. Choose a new name; existing measurements are never overwritten.").toJson();
            }
            String toggleError = ProfilingSupport.toggleProfiling(target);
            if (toggleError != null)
            {
                return ToolResult.error(toggleError).toJson();
            }

            StartProfilingTool.markInactive(applicationId);

            Activator.logInfo("Profiling stopped via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            ToolResult result = ToolResult.success()
                .put(KEY_ACTIVE, false)
                .put(KEY_STOPPED, true)
                .put(McpKeys.APPLICATION_ID, applicationId);
            return describeMeasurement(result, service, before, sessionId, name, 5000).toJson();
        }
        catch (IllegalArgumentException e)
        {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            Activator.logError("Error in stop_profiling", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /** Bounded worker-thread read-back; old results are never renamed. */
    public static ToolResult describeMeasurement(ToolResult response, IProfilingService service,
        Set<UUID> before, UUID sessionId, String name, long timeoutMillis) throws InterruptedException
    {
        IProfilingResult measurement = ProfilingSupport.awaitNewResult(service, before, sessionId, timeoutMillis);
        if (measurement != null && name != null && sessionId == null)
        {
            // Another stop (UI, session end, a parallel call) can deliver its measurement first.
            return ToolResult.error("Profiling stopped, but EDT does not expose which measurement belongs to this session,"
                + " so none was named. Find it with get_profiling_results mode=list.")
                .put(KEY_STOPPED, true).put(KEY_ACTIVE, false);
        }
        if (measurement == null)
        {
            return response.put(McpKeys.MESSAGE, "Profiling stopped, but no unambiguous new measurement arrived within "
                + timeoutMillis + " ms. No measurement was named. Use get_profiling_results mode=list to check later.");
        }
        String viewNote = "";
        if (name != null)
        {
            try
            {
                var attempted = new java.util.concurrent.atomic.AtomicBoolean();
                viewNote = ProfilingSupport.applyObserved(() -> {
                    // Acquire the service lock on the executing thread, never across a UI dispatch.
                    synchronized (service)
                    {
                        if (service.getResults().stream().anyMatch(r -> !r.getUuid().equals(measurement.getUuid()) && name.equalsIgnoreCase(r.getName())))
                        {
                            throw new IllegalArgumentException("The requested name now exists. Choose a unique name next time.");
                        }
                        attempted.set(true);
                        service.setResultName(measurement, name);
                    }
                }, () -> attempted.get() && service.getResults().stream().anyMatch(r -> r.getUuid().equals(measurement.getUuid())
                    && name.equals(r.getName())), "Rename to '" + name + "'");
            }
            catch (RuntimeException e)
            {
                return ToolResult.error("Profiling stopped, but " + e.getMessage())
                    .put(KEY_STOPPED, true).put(KEY_ACTIVE, false).put("measurementId", measurement.getUuid().toString());
            }
        }
        IProfilingResult observed = service.getResults().stream().filter(r -> r.getUuid().equals(measurement.getUuid()))
            .findFirst().orElseThrow(() -> new IllegalStateException("New measurement is no longer loaded. Use get_profiling_results mode=list."));
        return response.put("measurementId", observed.getUuid().toString()).put("name", observed.getName())
            .put(McpKeys.MESSAGE, "Profiling stopped. New measurement " + (name == null ? "observed" : "named '" + observed.getName() + "'")
                + "; use get_profiling_results with measurementId." + viewNote);
    }
}
