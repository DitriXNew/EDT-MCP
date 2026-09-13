/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.ProgressMonitorWrapper;

/** Distinguishes a synchronous delegate cancellation from cancellation by another owner. */
public final class AttributableCancel extends ProgressMonitorWrapper
{
    private final boolean canceledOnEntry;
    private final Thread launchingThread;
    private final Set<Thread> cancellingThreads = ConcurrentHashMap.newKeySet();

    /** Wraps the supplied monitor and captures its state before the launch starts. */
    public AttributableCancel(IProgressMonitor monitor)
    {
        super(monitor == null ? new NullProgressMonitor() : monitor);
        canceledOnEntry = monitor != null && monitor.isCanceled();
        launchingThread = Thread.currentThread();
    }

    /** Records every thread that requests cancellation before forwarding the request. */
    @Override
    public void setCanceled(boolean value)
    {
        if (value)
        {
            cancellingThreads.add(Thread.currentThread());
        }
        super.setCanceled(value);
    }

    /** Whether the launch delegate cancelled synchronously on the launching thread. */
    public boolean wasCanceledByLaunchingThread()
    {
        return !canceledOnEntry && cancellingThreads.contains(launchingThread);
    }
}
