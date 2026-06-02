/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

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

    private Log()
    {
        // Utility class
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
     * Logs a debug message. Disabled by default (kept for parity with the former
     * {@code Activator.logDebug}); enable the body below for troubleshooting.
     *
     * @param message the debug message
     */
    public static void debug(String message)
    {
        // Disabled by default - uncomment to troubleshoot:
        // log(IStatus.INFO, "[DEBUG] " + message, null);
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
