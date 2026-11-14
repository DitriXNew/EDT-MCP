/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The projects whose model must still take in a checkout THIS server performed (#922).
 * <p>
 * A checkout rewrites the files of every project in the work tree, and EDT loads them into the model only later:
 * Eclipse's automatic refresh takes the files up, EDT registers their import inside that refresh, and imports them
 * in the {@code SYNC} stage of the derived-data pipeline. A metadata write admitted before that lands in the model
 * the import is about to replace. The signals that show this window - a queued automatic refresh, queued work at
 * {@code STARTING}/{@code SYNC} - are not specific to a disk import: they are workspace-wide or shared with ordinary
 * derived-data work, so the write gate must not read them for every write. EDT's own per-project import state
 * ({@code SynchronizationManager}) is internal and not exported. This registry is what makes the signals specific:
 * the write gate reads them only for a project registered here, that is, only right after this server checked out
 * a branch in its work tree.
 * <p>
 * A registration ends when the project has been seen idle for {@link #STABLE_MS} without interruption - a single
 * idle sample proves nothing, the import may not have started yet - or after {@link #EXPIRY_MS} at the latest, so a
 * reload that never shows its end cannot hold writes back indefinitely.
 */
public final class CheckoutReloads
{
    /** How long a registered project must be seen idle, without interruption, before its reload counts as done. */
    public static final long STABLE_MS = 2_000L;

    /** The longest a registration lasts. */
    public static final long EXPIRY_MS = TimeUnit.MINUTES.toMillis(10);

    /** What a registered project's reload looks like at one observation. */
    public enum State
    {
        /** The reload is visibly running: refuse writes. */
        LOADING,
        /** Idle, but not yet for {@link #STABLE_MS}: still refuse writes. */
        SETTLING,
        /** Idle for {@link #STABLE_MS}, or not registered at all: the registration is gone. */
        SETTLED
    }

    private static final class Entry
    {
        final long expiresAtNanos;
        long idleSinceNanos;

        Entry(long expiresAtNanos)
        {
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    private static final Map<String, Entry> PENDING = new ConcurrentHashMap<>();

    private CheckoutReloads()
    {
        // Utility class
    }

    /**
     * Registers projects whose files a checkout just rewrote. A new registration replaces an older one, so a second
     * checkout starts the projects' wait afresh.
     *
     * @param projectNames the projects of the checked-out work tree
     */
    public static void register(Collection<String> projectNames)
    {
        register(projectNames, System.nanoTime());
    }

    static void register(Collection<String> projectNames, long nowNanos)
    {
        long expires = nowNanos + TimeUnit.MILLISECONDS.toNanos(EXPIRY_MS);
        for (String name : projectNames)
        {
            if (name != null)
            {
                PENDING.put(name, new Entry(expires));
            }
        }
    }

    /**
     * Whether a checkout's reload is still registered for {@code projectName}.
     *
     * @param projectName the project
     * @return {@code true} while registered and not expired
     */
    public static boolean isPending(String projectName)
    {
        return isPending(projectName, System.nanoTime());
    }

    static boolean isPending(String projectName, long nowNanos)
    {
        if (projectName == null)
        {
            return false;
        }
        Entry entry = PENDING.get(projectName);
        if (entry == null)
        {
            return false;
        }
        if (nowNanos - entry.expiresAtNanos >= 0)
        {
            PENDING.remove(projectName, entry);
            return false;
        }
        return true;
    }

    /**
     * Records one observation of a registered project and answers its state; a project idle for {@link #STABLE_MS}
     * without interruption is unregistered. An unregistered (or expired) project is {@link State#SETTLED}.
     *
     * @param projectName the project
     * @param loadingNow whether the reload is visibly running at this observation
     * @return the state after this observation
     */
    public static State observe(String projectName, boolean loadingNow)
    {
        return observe(projectName, loadingNow, System.nanoTime());
    }

    static State observe(String projectName, boolean loadingNow, long nowNanos)
    {
        if (!isPending(projectName, nowNanos))
        {
            return State.SETTLED;
        }
        Entry entry = PENDING.get(projectName);
        if (entry == null)
        {
            return State.SETTLED;
        }
        synchronized (entry)
        {
            if (loadingNow)
            {
                entry.idleSinceNanos = 0L;
                return State.LOADING;
            }
            if (entry.idleSinceNanos == 0L)
            {
                entry.idleSinceNanos = nowNanos == 0L ? 1L : nowNanos;
            }
            if (nowNanos - entry.idleSinceNanos < TimeUnit.MILLISECONDS.toNanos(STABLE_MS))
            {
                return State.SETTLING;
            }
        }
        PENDING.remove(projectName, entry);
        return State.SETTLED;
    }

    /** Test hook: forget every registration. */
    static void clear()
    {
        PENDING.clear();
    }
}
