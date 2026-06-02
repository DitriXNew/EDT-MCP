/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;

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
 * Stops 1C performance measurement (замер производительности) on the active
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

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Stop performance measurement (замер производительности) on the active debug target. " //$NON-NLS-1$
            + "Counterpart to start_profiling: deterministically switches profiling off. " //$NON-NLS-1$
            + "Idempotent: if profiling is not active for this applicationId it returns a benign result, not an error. " //$NON-NLS-1$
            + "Call get_profiling_results afterwards to retrieve the collected coverage."; //$NON-NLS-1$
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
            // Idempotent: nothing to do if profiling was never started (or already
            // stopped) for this id. Return a benign success, NOT an error — toggling
            // here would silently switch profiling ON.
            if (!StartProfilingTool.isProfilingActive(applicationId))
            {
                return ToolResult.success()
                    .put("active", false) //$NON-NLS-1$
                    .put("stopped", false) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("message", "Profiling was not active for this applicationId; nothing to stop.") //$NON-NLS-1$ //$NON-NLS-2$
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
                    .put("active", false) //$NON-NLS-1$
                    .put("stopped", true) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("message", "No active debug target for applicationId: " + applicationId //$NON-NLS-1$ //$NON-NLS-2$
                        + "; the debug session has ended. Profiling state cleared.") //$NON-NLS-1$
                    .toJson();
            }

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
                profileTarget = target.getAdapter(profileTargetClass);
            }

            if (profileTarget == null)
            {
                return ToolResult.error("Debug target does not support profiling. " //$NON-NLS-1$
                    + "Target class: " + target.getClass().getName()).toJson(); //$NON-NLS-1$
            }

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

            // IProfilingService.toggleProfiling(IProfileTarget). Because our shared
            // state says profiling is currently ON for this target, this toggle
            // deterministically switches it OFF.
            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);

            StartProfilingTool.markInactive(applicationId);

            Activator.logInfo("Profiling stopped via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put("active", false) //$NON-NLS-1$
                .put("stopped", true) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("message", "Profiling stopped. Call get_profiling_results to retrieve the collected coverage.") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in stop_profiling", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
