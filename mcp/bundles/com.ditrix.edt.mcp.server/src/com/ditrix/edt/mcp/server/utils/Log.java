/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Plugin logging, extracted from {@code Activator} (which now delegates here for
 * backward compatibility). Resolves the bundle {@link org.eclipse.core.runtime.ILog}
 * via the standard {@link Platform#getLog(Bundle)} API, so it has no dependency on
 * the {@code Activator} singleton or its start/stop lifecycle — logging works the
 * same regardless of plugin activation state.
 * <p>
 * First step of the {@code slim-down-activator} decomposition (Log / EdtServices /
 * StartupOrchestrator). Behaviour is identical to the former {@code Activator.logXxx}
 * methods: same destination (the plugin {@code ILog}) and same {@code PLUGIN_ID}.
 */
public final class Log
{
    /** The owning bundle, resolved once. Non-null at runtime inside OSGi. */
    private static final Bundle BUNDLE = FrameworkUtil.getBundle(Log.class);

    /** Status plugin id = the bundle symbolic name (== Activator.PLUGIN_ID). */
    private static final String PLUGIN_ID =
        BUNDLE != null ? BUNDLE.getSymbolicName() : "com.ditrix.edt.mcp.server"; //$NON-NLS-1$

    /**
     * Standard Eclipse tracing option for verbose/debug output. Enabled by the
     * platform when {@code <pluginId>/debug=true} is set in a {@code .options}
     * file and Eclipse is launched with {@code -debug}. Off by default, so the
     * production INFO log stays terse.
     */
    private static final String DEBUG_OPTION = PLUGIN_ID + "/debug"; //$NON-NLS-1$

    /**
     * Default gate: consults the standard Eclipse debug-tracing flag via
     * {@link Platform#getDebugBoolean(String)}. Returns {@code false} unless the
     * tracing option is explicitly turned on, keeping debug output suppressed by
     * default.
     */
    private static final BooleanSupplier DEFAULT_DEBUG_GATE = () -> Platform.getDebugBoolean(DEBUG_OPTION);

    /**
     * The active gate controlling {@link #debug(String)}. Defaults to the Eclipse
     * tracing flag; overridable (e.g. from tests) via {@link #setDebugGate}.
     * Never {@code null}.
     */
    private static volatile BooleanSupplier debugGate = DEFAULT_DEBUG_GATE;

    private Log()
    {
        // Utility class
    }

    /**
     * Reports whether debug logging is currently enabled (i.e. whether a
     * subsequent {@link #debug(String)} call would emit anything).
     *
     * @return {@code true} when the debug gate is open
     */
    public static boolean isDebugEnabled()
    {
        return debugGate.getAsBoolean();
    }

    /**
     * Overrides the gate that controls {@link #debug(String)}. Intended for tests
     * that need to force debug output on or off deterministically. Pass
     * {@code null} to restore the production default (the Eclipse tracing flag).
     *
     * @param gate the new gate, or {@code null} to restore the default
     */
    static void setDebugGate(BooleanSupplier gate)
    {
        debugGate = gate != null ? gate : DEFAULT_DEBUG_GATE;
    }

    /**
     * Logs an info message.
     *
     * @param message the message
     */
    public static void info(String message)
    {
        log(IStatus.INFO, message, null);
    }

    /**
     * Logs a debug message, but only when debug logging is enabled (see
     * {@link #isDebugEnabled()}). Disabled by default, so the production INFO log
     * stays terse; enable it with the standard Eclipse {@code <pluginId>/debug}
     * tracing option (a {@code .options} file plus {@code -debug}).
     *
     * @param message the debug message
     */
    public static void debug(String message)
    {
        if (isDebugEnabled())
        {
            log(IStatus.INFO, "[DEBUG] " + message, null); //$NON-NLS-1$
        }
    }

    /**
     * Logs a warning message.
     *
     * @param message the warning message
     */
    public static void warning(String message)
    {
        log(IStatus.WARNING, message, null);
    }

    /**
     * Logs an error with an optional exception.
     *
     * @param message the message
     * @param e the exception (may be {@code null})
     */
    public static void error(String message, Throwable e)
    {
        log(IStatus.ERROR, message, e);
    }

    private static void log(int severity, String message, Throwable e)
    {
        if (BUNDLE != null)
        {
            Platform.getLog(BUNDLE).log(new Status(severity, PLUGIN_ID, message, e));
        }
    }
}
