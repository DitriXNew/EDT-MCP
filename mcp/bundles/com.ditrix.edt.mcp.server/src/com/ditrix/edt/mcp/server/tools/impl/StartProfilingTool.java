/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.Map;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IDebugTarget;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Toggles 1C performance measurement (замер производительности) on the active
 * debug target. Once enabled, every executed BSL line is tracked with call count
 * and timing. Call {@code get_profiling_results} after the test finishes to
 * retrieve which code was covered.
 *
 * <p>Uses reflection to access {@code IProfilingService} via
 * {@code ServiceAccess.get()} from the {@code com._1c.g5.wiring} bundle,
 * and {@code IProfileTarget.toggleProfiling()} on the debug target.
 */
public class StartProfilingTool implements IMcpTool
{
    public static final String NAME = "start_profiling"; //$NON-NLS-1$

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
        return "Toggle performance measurement (замер производительности) on the active debug target. " //$NON-NLS-1$
            + "Enables line-level profiling: call counts and timing for every executed BSL line. " //$NON-NLS-1$
            + "Call get_profiling_results after the test finishes to see which code was covered. " //$NON-NLS-1$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required").toJson(); //$NON-NLS-1$
        }

        try
        {
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
            // internally, registers it in targets map, sends to debug server.
            Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
            toggleProfiling.invoke(profilingService, profileTarget);

            Activator.logInfo("Profiling toggled via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put("toggled", true) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("message", "Profiling toggled (on↔off). This is a toggle — calling again " //$NON-NLS-1$ //$NON-NLS-2$
                    + "will switch profiling off. Run your test, then call get_profiling_results.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in start_profiling", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
