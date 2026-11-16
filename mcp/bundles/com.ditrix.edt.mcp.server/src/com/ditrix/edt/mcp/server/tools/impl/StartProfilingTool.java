/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Starts 1C performance measurement on the active
 * debug target. Once enabled, every executed BSL line is tracked with call count
 * and timing. Call {@code stop_profiling} to stop, and
 * {@code get_profiling_results} after the test finishes to retrieve which code
 * was covered.
 *
 * <p>Uses reflection to access {@code IProfilingService} via
 * {@code ServiceAccess.get()} from the {@code com._1c.g5.wiring} bundle,
 * and {@code IProfileTarget.toggleProfiling()} on the debug target.
 *
 * <p><b>Determinism.</b> The underlying EDT API ({@code IProfilingService})
 * exposes only a single {@code toggleProfiling(target)} primitive and no public
 * "is profiling active" query, so the on/off state is tracked here — keyed by
 * {@code applicationId} — as the single source of truth shared with
 * {@code stop_profiling} and {@code get_profiling_results}. {@code start_profiling}
 * is start-only and idempotent: calling it while profiling is already active does
 * not toggle profiling off — it returns an "already profiling" result instead.
 */
public class StartProfilingTool implements IMcpTool
{
    public static final String NAME = "start_profiling"; //$NON-NLS-1$

    /** Output key: whether profiling is currently active for the application id. */
    private static final String KEY_ACTIVE = "active"; //$NON-NLS-1$

    /** Output key: whether this call started profiling (vs. already active). */
    private static final String KEY_STARTED = "started"; //$NON-NLS-1$

    /**
     * Shared on/off state, keyed by {@code applicationId}. The EDT profiling
     * service keeps its per-target state internally with no public getter, so this
     * set mirrors it for the application ids we drive via start/stop. It is the
     * single source of truth reused by {@link StopProfilingTool} and
     * {@link GetProfilingResultsTool} — do not add a parallel state holder.
     */
    private static final Map<String, Object> ACTIVE_APPLICATION_IDS = new ConcurrentHashMap<>();

    /** Owner of a flag set without a known target (tests, legacy callers). */
    private static final Object UNKNOWN_TARGET = new Object();

    /**
     * @param applicationId the application id of a debug session
     * @return {@code true} when profiling is currently marked active for the given id
     */
    public static boolean isProfilingActive(String applicationId)
    {
        return applicationId != null && ACTIVE_APPLICATION_IDS.containsKey(applicationId);
    }

    /**
     * Aligns the flag with the live target: EDT's own per-target state when readable, otherwise the flag is
     * dropped when it was set for an earlier session of the same applicationId (a relaunch keeps the id).
     */
    static void reconcile(String applicationId, IDebugTarget target)
    {
        if (applicationId == null) { return; }
        if (target == null)
        {
            // A lookup miss proves nothing by itself (a relaunch may not be registered yet): drop the flag
            // only when its own target has ended.
            if (ACTIVE_APPLICATION_IDS.get(applicationId) instanceof IDebugTarget owner && ended(owner))
            {
                // Only the owner checked: a concurrent start may have replaced it meanwhile.
                ACTIVE_APPLICATION_IDS.remove(applicationId, owner);
            }
            return;
        }
        Boolean enabled;
        try { enabled = ProfilingSupport.enabled(ProfilingSupport.service(), target); }
        catch (RuntimeException e) { enabled = null; }
        if (enabled != null)
        {
            if (enabled) { ACTIVE_APPLICATION_IDS.put(applicationId, target); }
            else { ACTIVE_APPLICATION_IDS.remove(applicationId); }
            return;
        }
        Object owner = ACTIVE_APPLICATION_IDS.get(applicationId);
        if (owner != null && owner != UNKNOWN_TARGET && owner != target)
        {
            ACTIVE_APPLICATION_IDS.remove(applicationId);
        }
    }

    /**
     * A target is ended when it or its launch reports so, or when its launch has left the launch manager AND its
     * client process has exited (a removed launch whose client still runs is kept live).
     */
    static boolean ended(IDebugTarget target)
    {
        org.eclipse.debug.core.DebugPlugin debug = org.eclipse.debug.core.DebugPlugin.getDefault();
        return ended(target, launch -> debug != null
            && java.util.Arrays.asList(debug.getLaunchManager().getLaunches()).contains(launch));
    }

    static boolean ended(IDebugTarget target, java.util.function.Predicate<org.eclipse.debug.core.ILaunch> registered)
    {
        if (target.isTerminated()) { return true; }
        org.eclipse.debug.core.ILaunch launch = target.getLaunch();
        return launch == null || launch.isTerminated()
            || !registered.test(launch) && com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.clientExited(launch);
    }

    /**
     * @return {@code true} when profiling is currently marked active for at least
     *         one application id (used to surface a global on/off hint when no
     *         specific id is supplied).
     */
    public static boolean isAnyProfilingActive()
    {
        return !ACTIVE_APPLICATION_IDS.isEmpty();
    }

    /** Marks profiling active for the given application id. Package-visible for the stop tool. */
    static void markActive(String applicationId)
    {
        markActive(applicationId, null);
    }

    static void markActive(String applicationId, IDebugTarget target)
    {
        if (applicationId != null)
        {
            ACTIVE_APPLICATION_IDS.put(applicationId, target == null ? UNKNOWN_TARGET : target);
        }
    }

    /** Marks profiling inactive for the given application id. Package-visible for the stop tool. */
    static void markInactive(String applicationId)
    {
        if (applicationId != null)
        {
            ACTIVE_APPLICATION_IDS.remove(applicationId);
        }
    }

    /** For tests only — drops all tracked profiling state. */
    static void clearStateForTests()
    {
        ACTIVE_APPLICATION_IDS.clear();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Measure execution time and coverage of BSL code in a debug session. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('start_profiling')."; //$NON-NLS-1$
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
            .booleanProperty(KEY_ACTIVE, "Whether profiling is now active for this applicationId") //$NON-NLS-1$
            .booleanProperty(KEY_STARTED, "True if this call started profiling, false if already active") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the profiled debug session") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status and next-step guidance") //$NON-NLS-1$
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
            IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
            reconcile(applicationId, target);
            // Start-only: if we already track profiling as active for this id, do
            // NOT toggle (toggling would silently switch profiling OFF). Return an
            // idempotent "already profiling" result instead.
            if (isProfilingActive(applicationId))
            {
                return ToolResult.success()
                    .put(KEY_ACTIVE, true)
                    .put(KEY_STARTED, false)
                    .put(McpKeys.APPLICATION_ID, applicationId)
                    .put(McpKeys.MESSAGE, "Profiling is already active for this applicationId. " //$NON-NLS-1$
                        + "Run your test, then call stop_profiling and get_profiling_results.") //$NON-NLS-1$
                    .toJson();
            }

            if (target == null)
            {
                return ToolResult.error("No active debug target for applicationId: " + applicationId //$NON-NLS-1$
                    + ". Start a debug session first (launch or debug_yaxunit_tests).").toJson(); //$NON-NLS-1$
            }

            // Resolve the profiling service + profile target and flip profiling on.
            // Gated above on our shared OFF state, so this toggle deterministically
            // switches profiling ON.
            String toggleError = ProfilingSupport.toggleProfiling(target);
            if (toggleError != null)
            {
                return ToolResult.error(toggleError).toJson();
            }

            markActive(applicationId, target);

            Activator.logInfo("Profiling started via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put(KEY_ACTIVE, true)
                .put(KEY_STARTED, true)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(McpKeys.MESSAGE, "Profiling started. Run your test, then call stop_profiling " //$NON-NLS-1$
                    + "and get_profiling_results.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in start_profiling", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
