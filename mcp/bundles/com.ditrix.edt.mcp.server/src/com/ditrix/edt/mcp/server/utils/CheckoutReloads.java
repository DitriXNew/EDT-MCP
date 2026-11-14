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
import java.util.function.BooleanSupplier;

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
 * reload that never shows its end cannot hold writes back indefinitely. While this server's own post-checkout work on
 * the projects (the explicit refresh) still runs, the registration counts as loading whatever the signals say.
 * <p>
 * No transition drops a hold it does not own: a registration takes one verdict ({@link Registration}); a newer checkout
 * of the same project keeps an older registration that still holds it as its predecessor, and the project goes back
 * to that predecessor - never to "settled" - when the newer one is withdrawn or expires first.
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
        /** Answers {@code true} while work that will still rewrite the model (the post-checkout refresh) runs. */
        final BooleanSupplier busy;
        /**
         * The registration this one replaced while that one still held the project (its own work still running, or
         * pinned): a newer checkout of the same project must not end the older one's hold before the older one ends.
         */
        final Entry previous;
        long idleSinceNanos;

        Entry(long expiresAtNanos, BooleanSupplier busy, Entry previous)
        {
            this.expiresAtNanos = expiresAtNanos;
            this.busy = busy;
            this.previous = previous;
        }

        /** Whether this registration, or one it replaced that has not expired, still holds the project. */
        boolean holds(long nowNanos)
        {
            for (Entry e = this; e != null; e = e.previous)
            {
                if (nowNanos - e.expiresAtNanos < 0 && e.busy.getAsBoolean())
                {
                    return true;
                }
            }
            return false;
        }

        /** {@code candidate} when it still holds the project at {@code nowNanos}, otherwise {@code null}. */
        static Entry ifHolding(Entry candidate, long nowNanos)
        {
            return candidate != null && candidate.holds(nowNanos) ? candidate : null;
        }
    }

    private static final Map<String, Entry> PENDING = new ConcurrentHashMap<>();

    /** Test hook run just before an idle registration is removed; {@code null} in production. */
    static volatile Runnable beforeSettle;

    /** Test hook run just before an expired registration is removed; {@code null} in production. */
    static volatile Runnable beforeExpire;

    private CheckoutReloads()
    {
        // Utility class
    }

    /** {@link #register(Collection, BooleanSupplier, long)} now; production registers through {@link #begin}. */
    static void register(Collection<String> projectNames, BooleanSupplier busy)
    {
        register(projectNames, busy, System.nanoTime());
    }

    /**
     * Registers projects whose files a checkout just rewrote. A new registration replaces an older one, so a second
     * checkout starts the projects' idle wait afresh; an older one that still holds the project (its own work still
     * running, or pinned) keeps holding it through the newer one until it ends or expires.
     *
     * @param projectNames the projects of the checked-out work tree
     * @param busy answers {@code true} while this server's own post-checkout work on those projects (the explicit
     *     refresh) is still running; the registration cannot end before it answers {@code false} (or expires)
     * @param nowNanos the current {@link System#nanoTime()}
     * @return the entries put, by project name
     */
    static Map<String, Object> register(Collection<String> projectNames, BooleanSupplier busy, long nowNanos)
    {
        purgeExpired(nowNanos);
        long expires = nowNanos + TimeUnit.MILLISECONDS.toNanos(EXPIRY_MS);
        BooleanSupplier running = busy == null ? () -> false : busy;
        Map<String, Object> put = new java.util.LinkedHashMap<>();
        for (String name : projectNames)
        {
            if (name != null)
            {
                Entry entry = PENDING.compute(name,
                    (key, current) -> new Entry(expires, running, Entry.ifHolding(current, nowNanos)));
                put.put(name, entry);
            }
        }
        return put;
    }

    /**
     * Ends {@code entry}'s own hold on {@code name}: the registration it replaced takes the project back when that
     * one still holds it, otherwise the project is unregistered. An entry a newer checkout has replaced meanwhile is
     * left alone - the newer one carries it as its {@code previous}, and asks it whether it still holds.
     */
    private static void withdraw(String name, Entry entry, long nowNanos)
    {
        PENDING.computeIfPresent(name, (key, current) -> current != entry ? current
            : Entry.ifHolding(entry.previous, nowNanos));
    }

    /**
     * Registers the projects a checkout is ABOUT to rewrite, before the checkout starts, so no write can slip in
     * between the checkout's end and the registration (#922). The caller then reports how the checkout went: the
     * registration is held (loading) until {@link Registration#done()}, kept until it expires after
     * {@link Registration#pin()}, and dropped by {@link Registration#release()} when nothing was rewritten.
     *
     * @param projectNames the projects of the work tree
     * @return the registration
     */
    public static Registration begin(Collection<String> projectNames)
    {
        return begin(projectNames, System.nanoTime());
    }

    static Registration begin(Collection<String> projectNames, long nowNanos)
    {
        Registration registration = new Registration();
        registration.entries.putAll(register(projectNames, registration::busy, nowNanos));
        return registration;
    }

    /**
     * A checkout's own registration, from before the checkout to the end of its post-checkout refresh.
     * <p>
     * It starts {@code RUNNING} (holding the projects) and takes exactly one verdict. {@link #pin()} is the strongest
     * and wins over any other: once pinned, the projects stay held until the expiry, whatever is reported next.
     * {@link #done()} and {@link #release()} take effect only from {@code RUNNING}, so neither can end a pinned hold,
     * and a late {@code done} cannot revive a released one.
     */
    public static final class Registration
    {
        private enum Phase
        {
            /** The checkout or its refresh still runs: the projects are held. */
            RUNNING,
            /** The checkout and its refresh ended cleanly: the projects settle once seen idle. */
            DONE,
            /** The effect is not known to have completed: the projects are held until the expiry. */
            PINNED,
            /** The checkout rewrote nothing: the registration is withdrawn. */
            RELEASED
        }

        private final java.util.concurrent.atomic.AtomicReference<Phase> phase =
            new java.util.concurrent.atomic.AtomicReference<>(Phase.RUNNING);

        private final Map<String, Object> entries = new ConcurrentHashMap<>();

        private Registration()
        {
        }

        private boolean busy()
        {
            Phase now = phase.get();
            return now == Phase.RUNNING || now == Phase.PINNED;
        }

        /** The checkout and its refresh ended cleanly: the projects settle once they are seen idle. */
        public void done()
        {
            phase.compareAndSet(Phase.RUNNING, Phase.DONE);
        }

        /**
         * The checkout's effect on disk, or the refresh that tells EDT about it, is not known to have completed (a
         * timed-out or failed checkout, a failed refresh): the projects stay registered until the expiry. Wins over an
         * earlier verdict; after a {@link #release()} it puts the withdrawn registration back.
         */
        public void pin()
        {
            pin(System.nanoTime());
        }

        void pin(long nowNanos)
        {
            Phase before = phase.getAndSet(Phase.PINNED);
            if (before == Phase.RELEASED)
            {
                for (Map.Entry<String, Object> e : entries.entrySet())
                {
                    Entry own = (Entry) e.getValue();
                    Entry restored = PENDING.compute(e.getKey(), (key, current) -> current == null ? own
                        : new Entry(own.expiresAtNanos, own.busy, Entry.ifHolding(current, nowNanos)));
                    e.setValue(restored);
                }
            }
        }

        /**
         * The checkout rewrote nothing (it did not run, or JGit left the tree as it was): withdraw this registration.
         * Has no effect once the registration is pinned or done - neither verdict may be undone by a later one.
         */
        public void release()
        {
            release(System.nanoTime());
        }

        void release(long nowNanos)
        {
            if (!phase.compareAndSet(Phase.RUNNING, Phase.RELEASED))
            {
                return;
            }
            for (Map.Entry<String, Object> e : entries.entrySet())
            {
                withdraw(e.getKey(), (Entry) e.getValue(), nowNanos);
            }
        }
    }

    /**
     * Drops every expired registration. Run on each registration, so names that are never asked about again (a
     * non-EDT project of the work tree, a project deleted since) cannot accumulate past {@link #EXPIRY_MS}.
     */
    private static void purgeExpired(long nowNanos)
    {
        for (String name : PENDING.keySet())
        {
            PENDING.computeIfPresent(name, (key, current) -> nowNanos - current.expiresAtNanos < 0 ? current
                : Entry.ifHolding(current.previous, nowNanos));
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
        while (true)
        {
            Entry entry = PENDING.get(projectName);
            if (entry == null)
            {
                return false;
            }
            if (nowNanos - entry.expiresAtNanos < 0)
            {
                return true;
            }
            Runnable hook = beforeExpire;
            if (hook != null)
            {
                hook.run();
            }
            // An expired registration hands the project back to one it replaced that still holds it (#922).
            Entry successor = Entry.ifHolding(entry.previous, nowNanos);
            if (successor == null ? PENDING.remove(projectName, entry)
                : PENDING.replace(projectName, entry, successor))
            {
                if (successor == null)
                {
                    return false;
                }
                continue;
            }
            // A newer checkout replaced the expired registration meanwhile: ask about that one instead.
        }
    }

    /**
     * Records one observation of a registered project and answers its state; a project idle for {@link #STABLE_MS}
     * without interruption is unregistered. An unregistered (or expired) project is {@link State#SETTLED}. The
     * registration's own work ({@code busy}) still running counts as loading. A registration replaced by a newer
     * checkout while this observation ran is never reported settled: the observation is made again on the newer one.
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
        while (true)
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
                if (loadingNow || entry.holds(nowNanos))
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
            Runnable hook = beforeSettle;
            if (hook != null)
            {
                hook.run();
            }
            if (PENDING.remove(projectName, entry))
            {
                return State.SETTLED;
            }
            // A newer checkout replaced the registration meanwhile: observe that one instead.
        }
    }

    /** Test hook: how many registrations are held. */
    static int size()
    {
        return PENDING.size();
    }

    /** Test hook: forget every registration. */
    static void clear()
    {
        PENDING.clear();
        beforeSettle = null;
        beforeExpire = null;
    }
}
