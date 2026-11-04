/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Transport adapters subscribe without retaining a mutable last-client sink. */
public final class ToolsChangedListeners
{
    private static final Set<Runnable> LISTENERS = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ThreadPoolExecutor SENDERS = new java.util.concurrent.ThreadPoolExecutor(
        0, 4, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "edt-mcp-tools-changed"); t.setDaemon(true); return t;
        }, new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
    private ToolsChangedListeners() { }
    public static void add(Runnable listener) { LISTENERS.add(listener); }
    public static void remove(Runnable listener) { LISTENERS.remove(listener); }
    public static void notifyChanged()
    {
        for (Runnable listener : LISTENERS)
        {
            SENDERS.execute(() -> {
                try { listener.run(); } catch (RuntimeException e) { LISTENERS.remove(listener); }
            });
        }
    }
}
