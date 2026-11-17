/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;
import com.ditrix.edt.mcp.server.Activator;

/** Bundle-owned listener cleanup, in reverse registration order. */
public final class ListenerDisposables
{
    private static ListenerDisposables shared = new ListenerDisposables();
    private final Deque<Runnable> disposables = new ArrayDeque<>();
    private boolean stopped;
    private final Activator bundle = Activator.getDefault();

    public static synchronized Runnable register(Runnable cleanup)
    {
        Activator current = Activator.getDefault();
        if (shared.stopped && current != null && current != shared.bundle)
        {
            shared = new ListenerDisposables();
        }
        return shared.add(cleanup);
    }

    public static synchronized void disposeAll() { shared.dispose(); }

    synchronized Runnable add(Runnable cleanup)
    {
        AtomicBoolean closed = new AtomicBoolean();
        Runnable disposable = () -> {
            if (closed.compareAndSet(false, true)) { cleanup.run(); }
        };
        if (stopped) { disposable.run(); }
        else { disposables.push(disposable); }
        return disposable;
    }

    synchronized void dispose()
    {
        stopped = true;
        while (!disposables.isEmpty())
        {
            try { disposables.pop().run(); }
            catch (RuntimeException e) { Activator.logWarning("Listener disposal failed: " + e); }
        }
    }
}
