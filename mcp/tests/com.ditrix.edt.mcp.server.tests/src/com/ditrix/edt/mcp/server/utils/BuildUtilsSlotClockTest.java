/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the clock of BuildUtils' per-project slots (#743 review): the one limiter behind the export wait, the validation
 * wait, the marker read and the state-line read measures how long an unreturned claim holds on a MONOTONIC clock, not on
 * the wall clock. The wall clock does not measure elapsed time - NTP corrects it, an operator sets it, a virtual machine
 * resumed from a snapshot reads earlier than before: a step forward would lapse a claim while its job is still out, so
 * jobs could pile up again, and a step back would hold a claim past the length its holder states. The limiter's clock is
 * replaced here by one the test steps, so the wall clock can move while the limiter's does not, and the other way round.
 */
public class BuildUtilsSlotClockTest
{
    private static final String PROJECT = "SlotClockProject"; //$NON-NLS-1$

    /** An ordinary reading of the stepped clock when a test starts it. */
    private static final long ORIGIN_NANOS = 1_000_000_000L;

    /** One of the four claims the limiter hands out. */
    @FunctionalInterface
    private interface Claim
    {
        AtomicBoolean begin(String projectName, long timeoutMs);
    }

    /** Every slot of the limiter, each on a key of its own. */
    private static final List<Claim> EVERY_SLOT = Arrays.asList(BuildUtils::beginExportWait,
        BuildUtils::beginValidationWait, BuildUtils::beginMarkerRead, BuildUtils::beginStateLineRead);

    private static final List<String> SLOT_NAMES = Arrays.asList("the export wait", "the validation wait", //$NON-NLS-1$ //$NON-NLS-2$
        "the marker read", "the state-line read"); //$NON-NLS-1$ //$NON-NLS-2$

    /** The clock the limiter was on before a test stepped it; put back after every test. */
    private LongSupplier original;

    @Before
    public void reset()
    {
        BuildUtils.forgetExportWaits();
    }

    @After
    public void putTheLimitersClockBack()
    {
        if (original != null)
        {
            BuildUtils.useSlotClock(original);
            original = null;
        }
        BuildUtils.forgetExportWaits();
    }

    /** A monotonic clock only the test moves, reading nanoseconds from {@code origin}. */
    private static final class SteppedClock implements LongSupplier
    {
        private final AtomicLong nanos;

        SteppedClock(long origin)
        {
            nanos = new AtomicLong(origin);
        }

        @Override
        public long getAsLong()
        {
            return nanos.get();
        }

        void advanceNanos(long step)
        {
            assertTrue("a monotonic clock does not step back", step >= 0); //$NON-NLS-1$
            nanos.addAndGet(step);
        }
    }

    /** Puts the limiter on a clock the test steps, starting at {@code origin}. */
    private SteppedClock stepTheLimitersClock(long origin)
    {
        SteppedClock clock = new SteppedClock(origin);
        LongSupplier was = BuildUtils.useSlotClock(clock);
        if (original == null)
        {
            original = was;
        }
        return clock;
    }

    /**
     * Each of the four slots lapses on the limiter's clock and on nothing else: while the wall clock runs far past a
     * claim's hold and the limiter's clock stands still, the claim holds; once the hold has elapsed on the limiter's
     * clock - with no wall-clock time passing at all - it lapses, to the nanosecond. One limiter, one clock: a slot that
     * kept a clock of its own would fail here.
     */
    @Test
    public void testEverySlotLapsesOnTheLimitersClockNotOnTheWallClock() throws Exception
    {
        SteppedClock clock = stepTheLimitersClock(ORIGIN_NANOS);
        long holdNanos = TimeUnit.MILLISECONDS.toNanos(3); // three times a 1ms timeout
        for (int i = 0; i < EVERY_SLOT.size(); i++)
        {
            Claim claim = EVERY_SLOT.get(i);
            String slot = SLOT_NAMES.get(i);
            BuildUtils.forgetExportWaits();
            assertNotNull(slot + ": the first claim is granted", claim.begin(PROJECT, 1L)); //$NON-NLS-1$

            Thread.sleep(25); // the wall clock runs far past the 3ms hold; the limiter's clock does not move
            assertNull(slot + ": a claim must not lapse on wall-clock time", claim.begin(PROJECT, 1L)); //$NON-NLS-1$

            clock.advanceNanos(holdNanos - 1);
            assertNull(slot + ": still held a nanosecond before its hold has elapsed", claim.begin(PROJECT, 1L)); //$NON-NLS-1$

            clock.advanceNanos(1);
            assertNotNull(slot + ": lapsed once its hold has elapsed on the limiter's clock, with no wall-clock time " //$NON-NLS-1$
                + "passing", claim.begin(PROJECT, 1L)); //$NON-NLS-1$
        }
    }

    /**
     * How much of a claim's hold is left is counted on the same clock: exactly three times the timeout while the
     * limiter's clock stands still, however much wall-clock time passes, and exactly what elapsed on the limiter's clock
     * less. Less than a millisecond left still holds, and reads as one.
     */
    @Test
    public void testTheHoldLeftIsCountedOnTheLimitersClock() throws Exception
    {
        SteppedClock clock = stepTheLimitersClock(ORIGIN_NANOS);
        assertNotNull(BuildUtils.beginStateLineRead(PROJECT, 60_000L));
        assertEquals("three times the timeout, on a clock that has not moved", 180_000L, //$NON-NLS-1$
            BuildUtils.stateLineReadHoldLeftMs(PROJECT));

        Thread.sleep(25);
        assertEquals("wall-clock time does not count", 180_000L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(100));
        assertEquals("time elapsed on the limiter's clock does", 80_000L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$

        clock.advanceNanos(TimeUnit.SECONDS.toNanos(80) - 1);
        assertEquals("a nanosecond left still holds, and reads as a millisecond", 1L, //$NON-NLS-1$
            BuildUtils.stateLineReadHoldLeftMs(PROJECT));
        assertNull("and still refuses", BuildUtils.beginStateLineRead(PROJECT, 60_000L)); //$NON-NLS-1$

        clock.advanceNanos(1);
        assertEquals("lapsed: nothing left", 0L, BuildUtils.stateLineReadHoldLeftMs(PROJECT)); //$NON-NLS-1$
        assertNotNull("and granted again", BuildUtils.beginStateLineRead(PROJECT, 60_000L)); //$NON-NLS-1$
    }

    /**
     * A monotonic reading has an arbitrary origin and may wrap past the end of a long: a claim taken a millisecond
     * before the reading wraps holds for its whole hold all the same, and lapses when it has elapsed. A limiter that
     * compared readings instead of their difference would let it lapse at once.
     */
    @Test
    public void testAClaimTakenJustBeforeTheClockWrapsHoldsForItsWholeHold()
    {
        SteppedClock clock = stepTheLimitersClock(Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(1));
        assertNotNull(BuildUtils.beginStateLineRead(PROJECT, 60_000L));
        assertNull("a claim taken just before the clock's reading wraps holds", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, 60_000L));
        assertEquals(180_000L, BuildUtils.stateLineReadHoldLeftMs(PROJECT));

        clock.advanceNanos(TimeUnit.MILLISECONDS.toNanos(180_000L) - 1); // the reading wraps on the way
        assertNull("still held past the wrap, a nanosecond before its hold has elapsed", //$NON-NLS-1$
            BuildUtils.beginStateLineRead(PROJECT, 60_000L));
        clock.advanceNanos(1);
        assertNotNull("lapsed once its hold has elapsed", BuildUtils.beginStateLineRead(PROJECT, 60_000L)); //$NON-NLS-1$
    }

    /** The limiter's own clock - the one production runs on - is the JVM's monotonic clock, not the wall clock. */
    @Test
    public void testTheLimitersOwnClockIsTheJvmsMonotonicClock()
    {
        LongSupplier production = BuildUtils.useSlotClock(() -> 0L);
        BuildUtils.useSlotClock(production);
        long before = System.nanoTime();
        long reading = production.getAsLong();
        long after = System.nanoTime();
        assertTrue("the limiter reads System.nanoTime(): " + reading + " is not within [" + before + ", " + after + "]", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            reading - before >= 0 && after - reading >= 0);
    }
}
