/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Method;

import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.model.IDebugTarget;
import org.osgi.framework.Bundle;

/**
 * Shared reflection plumbing for the line-level profiling tools
 * ({@code start_profiling} / {@code stop_profiling}). Reaches the EDT
 * {@code IProfilingService} via {@code ServiceAccess.get()} from the
 * {@code com._1c.g5.wiring} bundle and toggles profiling on a debug target
 * adapted to {@code com._1c.g5.v8.dt.profiling.core.IProfileTarget}.
 *
 * <p>The underlying EDT API exposes only a single {@code toggleProfiling(target)}
 * flip with no on/off argument and no public "is active" query, so callers MUST
 * gate the call on their own tracked on/off state (kept in
 * {@code StartProfilingTool}); a single call deterministically flips the state.
 */
public final class ProfilingSupport
{
    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String DEBUG_CORE_BUNDLE = "com._1c.g5.v8.dt.debug.core"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    private ProfilingSupport()
    {
    }

    /**
     * Resolves the EDT {@code IProfilingService} and the {@code IProfileTarget} for
     * the given debug target and invokes the single {@code toggleProfiling} primitive.
     * Because the toggle is a flip with no on/off argument, the caller MUST gate this
     * on its own tracked on/off state — calling it once deterministically flips the
     * current state.
     *
     * @param target the active, non-{@code null} debug target to toggle profiling on
     * @return {@code null} on success, or an actionable error message (a required
     *         bundle/service is missing, or the target does not support profiling)
     *         that the caller should surface via {@code ToolResult.error}
     * @throws ReflectiveOperationException if the EDT profiling API shape differs from
     *         what this reflection expects (unexpected; the caller surfaces it as a
     *         generic error, mirroring the previous inline behaviour)
     */
    public static String toggleProfiling(IDebugTarget target) throws ReflectiveOperationException
    {
        Bundle debugBundle = Platform.getBundle(DEBUG_CORE_BUNDLE);
        if (debugBundle == null)
        {
            return "Debug core bundle not found"; //$NON-NLS-1$
        }

        Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
        if (profilingBundle == null)
        {
            return "Profiling core bundle not found"; //$NON-NLS-1$
        }

        Class<?> profileTargetClass = profilingBundle.loadClass(
            "com._1c.g5.v8.dt.profiling.core.IProfileTarget"); //$NON-NLS-1$

        // Adapt the debug target to IProfileTarget (directly or via Eclipse adapters).
        Object profileTarget = profileTargetClass.isInstance(target)
            ? target
            : target.getAdapter(profileTargetClass);
        if (profileTarget == null)
        {
            return "Debug target does not support profiling. " //$NON-NLS-1$
                + "Target class: " + target.getClass().getName(); //$NON-NLS-1$
        }

        // Get IProfilingService via ServiceAccess.get() — it manages the
        // UUID↔target mapping needed for module resolution in results.
        Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
        if (wiringBundle == null)
        {
            return "Wiring bundle not found"; //$NON-NLS-1$
        }

        Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
        Class<?> profilingServiceClass = profilingBundle.loadClass(
            "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$
        Method getService = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
        Object profilingService = getService.invoke(null, profilingServiceClass);
        if (profilingService == null)
        {
            return "IProfilingService not available"; //$NON-NLS-1$
        }

        // IProfilingService.toggleProfiling(IProfileTarget) — generates a UUID
        // internally, registers it in the targets map, sends it to the debug server.
        Method toggleProfiling = profilingServiceClass.getMethod("toggleProfiling", profileTargetClass); //$NON-NLS-1$
        toggleProfiling.invoke(profilingService, profileTarget);
        return null;
    }
}
