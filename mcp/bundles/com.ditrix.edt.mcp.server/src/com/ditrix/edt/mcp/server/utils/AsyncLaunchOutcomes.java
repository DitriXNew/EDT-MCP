/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The few most recent failures of a FIRE-AND-FORGET launch, so a caller can still learn about
 * them.
 *
 * <p>{@code launch} dispatches the launch as a background {@code Job} and returns
 * {@code status: "launching"} immediately. Anything that goes wrong after that — a launch
 * exception, or an external-changes conflict dialog that the call's
 * {@link ExternalInfobaseChangesPolicy} declined to resolve while EDT performed the DB update
 * inside its own launch delegate (the standalone-server path) — happens when the MCP call is long
 * over. Without this registry the outcome exists only in the workspace log: the client sees a
 * successful dispatch and then simply no session, with no reason anywhere on the wire.
 *
 * <p>{@code debug_status} reports what is stored here, so the follow-up call an agent already makes
 * to check the session tells it what actually happened.
 *
 * <p>Bounded by construction: {@link #MAX_ENTRIES} newest entries, each dropped after
 * {@link #TTL_MILLIS}. Thread-safe; every method is a no-op-safe read/write of one small deque.
 */
public final class AsyncLaunchOutcomes
{
    /**
     * How many outcomes are kept. The TTL is applied first, so this cap only bites when MORE than
     * this many launches fail within the same hour — then the OLDEST are dropped, including, in
     * that extreme, one another application was about to ask about. Deliberately a small bounded
     * window rather than a log: the registry exists to explain the last few launches.
     */
    static final int MAX_ENTRIES = 100;

    /** How long an outcome stays reportable; older entries are pruned on the next access. */
    static final long TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private static final Deque<Outcome> RECENT = new ArrayDeque<>();

    private AsyncLaunchOutcomes()
    {
        // Utility class
    }

    /**
     * Records a failure of an asynchronous launch.
     *
     * @param launchConfiguration the launch configuration name (may be {@code null})
     * @param applicationId the application the launch targets (may be {@code null}), so a
     *            {@code debug_status} filtered by application does not report someone else's failure
     * @param message the actionable message for the caller, never {@code null}
     */
    public static void record(String launchConfiguration, String applicationId, String message)
    {
        if (message == null || message.isEmpty())
        {
            return;
        }
        long now = System.currentTimeMillis();
        Outcome outcome = new Outcome(launchConfiguration, applicationId, message, now);
        synchronized (RECENT)
        {
            // Expired first: an entry nobody can report any more must not be what pushes a live one
            // out of the window.
            pruneExpired(now - TTL_MILLIS);
            RECENT.addLast(outcome);
            while (RECENT.size() > MAX_ENTRIES)
            {
                RECENT.removeFirst();
            }
        }
    }

    /**
     * Returns the outcomes recorded within the TTL, oldest first, pruning what expired.
     *
     * @return a snapshot list, never {@code null}
     */
    public static List<Outcome> recent()
    {
        long cutoff = System.currentTimeMillis() - TTL_MILLIS;
        synchronized (RECENT)
        {
            pruneExpired(cutoff);
            return new ArrayList<>(RECENT);
        }
    }

    /** Drops everything older than {@code cutoff}. Caller holds the {@code RECENT} monitor. */
    private static void pruneExpired(long cutoff)
    {
        for (Iterator<Outcome> it = RECENT.iterator(); it.hasNext();)
        {
            if (it.next().timestampMillis < cutoff)
            {
                it.remove();
            }
        }
    }

    /** Test seam: drops everything recorded so far. */
    static void clearForTest()
    {
        synchronized (RECENT)
        {
            RECENT.clear();
        }
    }

    /** One recorded failure of an asynchronous launch. */
    public static final class Outcome
    {
        private final String launchConfiguration;
        private final String applicationId;
        private final String message;
        private final long timestampMillis;

        Outcome(String launchConfiguration, String applicationId, String message, long timestampMillis)
        {
            this.launchConfiguration = launchConfiguration;
            this.applicationId = applicationId;
            this.message = message;
            this.timestampMillis = timestampMillis;
        }

        /**
         * @return the application the failed launch targeted, or {@code null} when it could not be
         *         resolved — a {@code debug_status} filtered by application skips such an entry
         *         (it cannot be shown to belong to the caller); an unfiltered call reports it
         */
        public String applicationId()
        {
            return applicationId;
        }

        /**
         * @return the launch configuration the failure belongs to, or {@code null}
         */
        public String launchConfiguration()
        {
            return launchConfiguration;
        }

        /**
         * @return the actionable message, never {@code null}
         */
        public String message()
        {
            return message;
        }

        /**
         * @return how many seconds ago it was recorded
         */
        public long ageSeconds()
        {
            return Math.max(0L, (System.currentTimeMillis() - timestampMillis) / 1000L);
        }
    }
}
