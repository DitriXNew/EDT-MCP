/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.bridge;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * "Is anything still coming through the in-process bridge?" - the liveness signal a caller waiting
 * on an assistant turn needs in order to tell a working tool loop from one that stopped.
 *
 * <p>Two values, because either one alone lies:
 * <ul>
 *   <li>{@link #ticks()} counts bridge calls STARTED, monotonically and for the lifetime of the
 *       bundle. Counting starts rather than completions is what makes a call visible while it
 *       runs; monotonic and unbounded is what keeps the signal from standing still - a retained
 *       history is bounded and can be switched off, so its size stops changing while work goes
 *       on.</li>
 *   <li>{@link #inFlight()} is how many bridge calls are executing right now, so ONE tool that
 *       runs for minutes still reads as activity even though it ticks only once.</li>
 * </ul>
 *
 * <p>Only the in-process bridge is counted: another MCP client's HTTP traffic is not this
 * conversation's progress, and must not keep a dead turn alive.
 */
public final class BridgeActivity
{
    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private BridgeActivity()
    {
        // utility
    }

    /**
     * Marks the START of a bridge call. Every call must be paired with {@link #callFinished()}
     * in a {@code finally} block.
     */
    public static void callStarted()
    {
        TICKS.incrementAndGet();
        IN_FLIGHT.incrementAndGet();
    }

    /** Marks the end of a bridge call started by {@link #callStarted()}. */
    public static void callFinished()
    {
        IN_FLIGHT.decrementAndGet();
    }

    /**
     * @return how many bridge calls have been started since this bundle was loaded; never
     *         decreases and is never reset, so a caller can compare two readings
     */
    public static long ticks()
    {
        return TICKS.get();
    }

    /**
     * @return how many bridge calls are executing right now; a positive value means work is
     *         under way even when {@link #ticks()} has not moved
     */
    public static int inFlight()
    {
        return Math.max(0, IN_FLIGHT.get());
    }

    /** Test seam: forgets both readings. */
    static void resetForTest()
    {
        TICKS.set(0L);
        IN_FLIGHT.set(0);
    }
}
