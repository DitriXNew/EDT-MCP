/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;

import com.ditrix.edt.mcp.server.Activator;

/** Captures the last relevant EDT error logged while one launch is running. */
public final class LaunchAbortReason implements AutoCloseable
{
    /** EDT plug-in families whose errors can explain a launch delegate cancellation. */
    private static final List<String> PLUGIN_PREFIXES = Arrays.asList(
        "com._1c.g5.v8.dt.launching", //$NON-NLS-1$
        "com.e1c.g5.dt.applications", //$NON-NLS-1$
        "com.e1c.g5.v8.dt.platform.standaloneserver", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug"); //$NON-NLS-1$

    private final AtomicReference<IStatus> lastError = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ILogListener listener = this::logged;

    private LaunchAbortReason()
    {
    }

    /** Opens a per-launch log window. */
    public static LaunchAbortReason open()
    {
        LaunchAbortReason window = new LaunchAbortReason();
        Platform.addLogListener(window.listener);
        return window;
    }

    /** Keeps the last allowlisted ERROR while the window is open. */
    private void logged(IStatus status, String sourcePlugin)
    {
        if (closed.get() || status == null || status.getSeverity() != IStatus.ERROR)
        {
            return;
        }
        String pluginId = status.getPlugin();
        if (Activator.PLUGIN_ID.equals(sourcePlugin) || Activator.PLUGIN_ID.equals(pluginId)
            || !isAllowlisted(pluginId))
        {
            return;
        }
        lastError.set(status);
    }

    /** Whether the status plug-in belongs to a launch-related EDT family. */
    private static boolean isAllowlisted(String pluginId)
    {
        if (pluginId == null)
        {
            return false;
        }
        for (String prefix : PLUGIN_PREFIXES)
        {
            if (pluginId.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /** Describes the last captured error and its deepest distinct cause, or returns {@code null}. */
    public String reason()
    {
        IStatus status = lastError.get();
        if (status == null)
        {
            return null;
        }
        String message = status.getMessage();
        if (message == null || message.trim().isEmpty())
        {
            message = "EDT logged an error without a message"; //$NON-NLS-1$
        }
        String rootCause = PlatformFailures.rootCause(new CoreException(status));
        if (rootCause == null || rootCause.isEmpty() || message.equals(rootCause))
        {
            return message;
        }
        return message + " Caused by: " + rootCause; //$NON-NLS-1$
    }

    /** Removes this window's listener. */
    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            Platform.removeLogListener(listener);
        }
    }
}
