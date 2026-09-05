/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

/**
 * How much time has passed since this object was made, measured so that no correction of the
 * machine's clocks can move the answer backwards or make it jump.
 *
 * <h2>Why not the system clock</h2>
 * Every deadline in the comparison feature - the budget of one {@code get_comparison_node} call,
 * the five minutes a launch may hold EDT's single comparison slot, the thirty minutes a session may
 * sit untouched, the ten seconds a hand-back gives the platform to begin - is a promise about
 * ELAPSED time. {@code System.currentTimeMillis()} does not measure elapsed time: NTP corrects it,
 * an operator sets it, and a virtual machine resumed from a snapshot hands the JVM a reading from
 * before the wait started. Both directions break a promise, and they break it in opposite ways:
 * <ul>
 * <li>a reading that STEPS BACK keeps an age below its budget for as long as the step was, so a
 * dead launch worker's claim outlives {@code CLAIM_BUDGET_MILLIS} and every later comparison is
 * refused on its account, and a call's {@code waitSeconds} stops being an upper bound;</li>
 * <li>a reading that JUMPS FORWARD ages everything by the size of the jump at once, so the next
 * sweep reclaims a comparison that was touched a moment ago - out from under the caller reading
 * it.</li>
 * </ul>
 *
 * <h2>Differences, not an absolute instant</h2>
 * The reading is accumulated: each call charges {@code now - last} against a monotonic
 * {@link Ticker} and adds it to a running total, which starts at zero. Three properties follow, and
 * all three are the reason this is not the one-liner {@code start + budget}:
 * <ul>
 * <li><b>a step BACKWARDS spends nothing.</b> It cannot refund what was already charged either, so
 * no behaviour of the machine can push the end of a wait further away than the budget asked
 * for;</li>
 * <li><b>the arbitrary origin is gone.</b> {@code System.nanoTime()}'s origin may sit anywhere in
 * the {@code long} range, so {@code ticker.nanoTime() + budget} can overflow and {@code now <
 * deadline} is then simply the wrong question. Readings from this class start at zero and grow only
 * by time actually observed, so an absolute deadline computed from one is safe;</li>
 * <li><b>an unset timestamp field cannot be mistaken for "long ago".</b> With epoch milliseconds a
 * zero reads as 1970 and every age computed from it is enormous; here a zero is the moment this
 * object was made, which is the newest reading there is. Code that keeps a reading must therefore
 * keep it beside the thing it dates - which is what {@code ComparisonSessionRegistry} does with its
 * standing claim - rather than in a field with a default.</li>
 * </ul>
 *
 * <h2>What it is not</h2>
 * It is not a timestamp. It cannot be rendered, it means nothing outside the object that produced
 * it, and two readings taken from two instances must never be compared with each other.
 *
 * <p>Not thread-safe on its own: it is a small mutable accumulator, and every user in this bundle
 * either owns it for the length of one call ({@code GetComparisonNodeTool.Budget}) or reads it
 * under a monitor it already holds ({@code ComparisonSessionRegistry}).</p>
 */
public final class ElapsedTime
{
    /** How many nanoseconds make a millisecond. */
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /**
     * The monotonic source a reading is taken from - {@code System::nanoTime} in production, a
     * scripted one in a test.
     */
    @FunctionalInterface
    public interface Ticker
    {
        /**
         * @return a monotonically advancing reading in nanoseconds, whose origin is arbitrary
         */
        long nanoTime();
    }

    private final Ticker ticker;

    private long lastReading;

    private long elapsedNanos;

    /**
     * @param ticker the monotonic source; the reading it gives now is the zero of this object
     */
    public ElapsedTime(Ticker ticker)
    {
        this.ticker = ticker;
        this.lastReading = ticker.nanoTime();
    }

    /**
     * @return an instance measuring against the JVM's monotonic source
     */
    public static ElapsedTime system()
    {
        return new ElapsedTime(System::nanoTime);
    }

    /**
     * Charges the time since the previous reading and answers the total.
     * <p>
     * <b>Synchronized because one instance is SHARED.</b> A registry keeps a single
     * {@code ElapsedTime} and reads it from threads that do not hold the registry's own monitor -
     * its waiting loops must not, or they would block every other call while they sleep. This
     * method is a read-modify-write over two fields, so without a lock two callers can read the
     * same previous reading and both charge the same step: the total then runs FAST, which expires
     * a standing claim before its budget and reclaims a session before its idle TTL - the very
     * failures a monotonic source was introduced to prevent, reached by another route. The wall
     * clock this replaced was stateless and so had no such race; carrying state is what makes the
     * lock part of the design rather than an afterthought.
     * <p>
     * The lock is a leaf: nothing inside calls back into a caller, so registry-then-elapsed is the
     * only ordering that exists and no deadlock is reachable through it.
     *
     * @return nanoseconds observed since this object was made; never decreases
     */
    public synchronized long nanos()
    {
        long now = ticker.nanoTime();
        long step = now - lastReading;
        lastReading = now;
        if (step > 0L)
        {
            // Saturating, so that one enormous step cannot wrap the total back to "no time has
            // passed" - the failure this whole class exists to make unreachable.
            elapsedNanos = elapsedNanos > Long.MAX_VALUE - step ? Long.MAX_VALUE : elapsedNanos + step;
        }
        return elapsedNanos;
    }

    /**
     * The same reading in milliseconds, which is the scale the registry's budgets are stated in.
     *
     * @return milliseconds observed since this object was made; never decreases
     */
    public long millis()
    {
        return nanos() / NANOS_PER_MILLI;
    }
}
