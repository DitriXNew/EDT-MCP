/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Captures the last allowlisted EDT error logged during a launch window. With no other tracked
 * window, correlation is temporal only and the error may belong to another operation.
 */
public final class LaunchAbortReason implements AutoCloseable
{
    /** EDT plug-in families whose errors can explain a launch delegate cancellation. */
    private static final List<String> PLUGIN_PREFIXES = Arrays.asList(
        "com._1c.g5.v8.dt.launching", //$NON-NLS-1$
        "com.e1c.g5.dt.applications", //$NON-NLS-1$
        "com.e1c.g5.v8.dt.platform.standaloneserver", //$NON-NLS-1$
        "com._1c.g5.v8.dt.debug"); //$NON-NLS-1$

    private static final Object WINDOW_LOCK = new Object();
    private static final List<LaunchAbortReason> OPEN_WINDOWS = new ArrayList<>();

    private final AtomicReference<IStatus> lastError = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean sawConcurrentWindow = new AtomicBoolean(false);
    private final ILogListener listener = this::logged;
    private final String expectedName;

    private LaunchAbortReason(String expectedName)
    {
        this.expectedName = trimToNull(expectedName);
    }

    /** Opens a per-launch log window. */
    public static LaunchAbortReason open()
    {
        return open(null);
    }

    /** Opens a log window associated with the application or infobase this launch targets. */
    public static LaunchAbortReason open(String expectedName)
    {
        LaunchAbortReason window = new LaunchAbortReason(expectedName);
        synchronized (WINDOW_LOCK)
        {
            OPEN_WINDOWS.add(window);
            markConcurrentWindows();
        }
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
        synchronized (WINDOW_LOCK)
        {
            markConcurrentWindows();
        }
        if (sawConcurrentWindow.get() && !namesExpectedLaunch(status))
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

    /** Whether the captured status message names the application this window owns. */
    private boolean namesExpectedLaunch(IStatus status)
    {
        String message = status == null ? null : status.getMessage();
        if (expectedName == null || message == null || !containsWholeName(message, expectedName))
        {
            return false;
        }
        synchronized (WINDOW_LOCK)
        {
            // A short name such as "Base" has ordinary character boundaries inside "Base Copy".
            // When both launches have open windows, give the message to the longer complete name
            // instead of letting the prefix window claim the same failure.
            for (LaunchAbortReason window : OPEN_WINDOWS)
            {
                String candidate = window.expectedName;
                if (candidate != null && candidate.length() > expectedName.length()
                    && containsWholeName(candidate, expectedName)
                    && containsWholeName(message, candidate))
                {
                    return false;
                }
            }
        }
        return true;
    }

    /** Case-insensitive name match with identifier boundaries on both sides. */
    private static boolean containsWholeName(String text, String name)
    {
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = name.toLowerCase(Locale.ROOT);
        int from = 0;
        while (from <= haystack.length() - needle.length())
        {
            int match = haystack.indexOf(needle, from);
            if (match < 0)
            {
                return false;
            }
            int after = match + needle.length();
            boolean leftBoundary = match == 0 || !isNameCharacter(haystack.charAt(match - 1));
            boolean rightBoundary = after == haystack.length()
                || !isNameCharacter(haystack.charAt(after));
            if (leftBoundary && rightBoundary)
            {
                return true;
            }
            from = match + 1;
        }
        return false;
    }

    /** Characters that cannot delimit an application/configuration name token. */
    private static boolean isNameCharacter(char value)
    {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    /** Normalizes an optional ownership name. */
    private static String trimToNull(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return null;
        }
        return value.trim();
    }

    /** Makes concurrency sticky for every window that currently observes another open window. */
    private static void markConcurrentWindows()
    {
        if (OPEN_WINDOWS.size() > 1)
        {
            for (LaunchAbortReason window : OPEN_WINDOWS)
            {
                window.sawConcurrentWindow.set(true);
            }
        }
    }

    /** Removes this window's listener. */
    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            synchronized (WINDOW_LOCK)
            {
                markConcurrentWindows();
                OPEN_WINDOWS.remove(this);
            }
            Platform.removeLogListener(listener);
        }
    }

}
