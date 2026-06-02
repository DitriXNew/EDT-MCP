/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IDebugTarget;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Starts 1C performance measurement (замер производительности) on the active
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

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    /**
     * Shared on/off state, keyed by {@code applicationId}. The EDT profiling
     * service keeps its per-target state internally with no public getter, so this
     * set mirrors it for the application ids we drive via start/stop. It is the
     * single source of truth reused by {@link StopProfilingTool} and
     * {@link GetProfilingResultsTool} — do not add a parallel state holder.
     */
    private static final Set<String> ACTIVE_APPLICATION_IDS = ConcurrentHashMap.newKeySet();

    /**
     * @param applicationId the application id of a debug session
     * @return {@code true} when profiling is currently marked active for the given id
     */
    public static boolean isProfilingActive(String applicationId)
    {
        return applicationId != null && ACTIVE_APPLICATION_IDS.contains(applicationId);
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
        if (applicationId != null)
        {
            ACTIVE_APPLICATION_IDS.add(applicationId);
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
        return "Start performance measurement (замер производительности) on the active debug target. " //$NON-NLS-1$
            + "Enables line-level profiling: call counts and timing for every executed BSL line. " //$NON-NLS-1$
            + "Start-only and idempotent: if profiling is already active for this applicationId it stays on. " //$NON-NLS-1$
            + "Call stop_profiling to stop, then get_profiling_results to see which code was covered. " //$NON-NLS-1$
            + "Requires an active debug session (debug_launch or debug_yaxunit_tests)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("applicationId", "Application id of the running debug session (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
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
        String err = JsonUtils.requireArgument(params, "applicationId"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        try
        {
            // Start-only: if we already track profiling as active for this id, do
            // NOT toggle (toggling would silently switch profiling OFF). Return an
            // idempotent "already profiling" result instead.
            if (isProfilingActive(applicationId))
            {
                return ToolResult.success()
                    .put("active", true) //$NON-NLS-1$
                    .put("started", false) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("message", "Profiling is already active for this applicationId. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Run your test, then call stop_profiling and get_profiling_results.") //$NON-NLS-1$
                    .toJson();
            }

            // Find active debug target
            IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
            if (target == null)
            {
                return ToolResult.error("No active debug target for applicationId: " + applicationId //$NON-NLS-1$
                    + ". Start a debug session first (debug_launch or debug_yaxunit_tests).").toJson(); //$NON-NLS-1$
            }

            // Check if target implements IProfileTarget (via adapter or directly)
            Bundle debugBundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
            if (debugBundle == null)
            {
                return ToolResult.error("Debug core bundle not found").toJson(); //$NON-NLS-1$
            }

            Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
            if (profilingBundle == null)
            {
                return ToolResult.error("Profiling core bundle not found").toJson(); //$NON-NLS-1$
            }

            Class<?> profileTargetClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfileTarget"); //$NON-NLS-1$

            // Try to adapt the debug target to IProfileTarget
            Object profileTarget = null;
            if (profileTargetClass.isInstance(target))
            {
                profileTarget = target;
            }
            else
            {
                // Try Eclipse adapter mechanism
                profileTarget = target.getAdapter(profileTargetClass);
            }

            if (profileTarget == null)
            {
                return ToolResult.error("Debug target does not support profiling. " //$NON-NLS-1$
                    + "Target class: " + target.getClass().getName()).toJson(); //$NON-NLS-1$
            }

            // Get IProfilingService via ServiceAccess.get() — it manages the
            // UUID↔target mapping needed for module resolution in results.
            Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
            if (wiringBundle == null)
            {
                return ToolResult.error("Wiring bundle not found").toJson(); //$NON-NLS-1$
            }

            Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
            Class<?> profilingServiceClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
            Method getService = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
            Object profilingService = getService.invoke(null, profilingServiceClass);
            if (profilingService == null)
            {
                return ToolResult.error("IProfilingService not available").toJson(); //$NON-NLS-1$
            }

            // IProfilingService.toggleProfiling(IProfileTarget) — generates UUID
            // internally, registers it in targets map, sends to debug server. Since
            // we only call this when our state says profiling is OFF, this toggle
            // deterministically switches it ON.
            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);

            markActive(applicationId);

            Activator.logInfo("Profiling started via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put("active", true) //$NON-NLS-1$
                .put("started", true) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("message", "Profiling started. Run your test, then call stop_profiling " //$NON-NLS-1$ //$NON-NLS-2$
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
