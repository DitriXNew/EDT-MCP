/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link ElapsedTime}, the one answer this bundle's comparison feature gives to
 * "how much time has passed".
 *
 * <p>Every test here scripts the time source, so none of them sleeps and none of them depends on the
 * machine underneath. Between them they pin the three properties the class exists for: a step
 * backwards spends nothing and refunds nothing, an arbitrary origin cannot overflow the reading, and
 * a fresh instance reads zero - which is the NEWEST reading there is, not the oldest.</p>
 */
public class ElapsedTimeTest
{
    /** One millisecond, in the nanoseconds the ticker speaks. */
    private static final long MS = 1_000_000L;

    /**
     * A scripted source: the first reading is the origin, and each later one applies the next step -
     * the LAST step repeating for every reading after it. Steps rather than absolute readings, so
     * that a test says what the machine DID instead of restating the arithmetic under test.
     */
    private static final class SteppingTicker
        implements ElapsedTime.Ticker
    {
        private final long[] steps;

        private long current;

        private int readings;

        SteppingTicker(long origin, long... steps)
        {
            this.current = origin;
            this.steps = steps;
        }

        @Override
        public long nanoTime()
        {
            if (readings > 0)
            {
                current += steps[Math.min(readings - 1, steps.length - 1)];
            }
            readings++;
            return current;
        }
    }

    /**
     * The seed. With epoch milliseconds an unset reading is zero, zero reads as 1970 and every age
     * computed from it is enormous - which is why an unset timestamp field used to be survivable by
     * accident. Here zero means "just now", and code that keeps a reading has to keep it beside the
     * thing it dates.
     */
    @Test
    public void testAFreshReadingIsZeroHoweverFarAlongTheSourceIs()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(Long.MAX_VALUE - 4 * MS, 0L));

        assertEquals(0L, elapsed.nanos());
    }

    @Test
    public void testForwardProgressIsCharged()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, 250 * MS));

        elapsed.nanos();

        assertEquals(500 * MS, elapsed.nanos());
    }

    /**
     * The defect this class was extracted for, in its smallest form: a reading that goes BACKWARDS
     * spends nothing. It cannot refund what was already charged either, so no behaviour of the
     * machine can push the end of a wait further away than the budget asked for.
     */
    @Test
    public void testAStepBackwardsSpendsNothing()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, 600 * MS, -3_000 * MS, 600 * MS));

        elapsed.nanos();

        assertEquals("600ms forward, then three seconds back: the back step buys the caller nothing", //$NON-NLS-1$
            600 * MS, elapsed.nanos());
    }

    /** And the other half of the same reading: it does not REFUND what was already charged. */
    @Test
    public void testAStepBackwardsDoesNotRefundWhatWasAlreadyCharged()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, 600 * MS, -3_000 * MS, 600 * MS));

        elapsed.nanos();
        elapsed.nanos();

        assertEquals("the reading after the back step adds its own 600ms to the 600 already spent", //$NON-NLS-1$
            1_200 * MS, elapsed.nanos());
    }

    /**
     * The origin of {@code System.nanoTime()} is arbitrary and may sit anywhere in the {@code long}
     * range, so a deadline computed as "the origin plus the budget" can WRAP. Readings taken from
     * this class start at zero and grow only by time observed, so a deadline computed from one
     * cannot.
     */
    @Test
    public void testAnOriginAtTheEndOfLongStillReadsAsPlainElapsedTime()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(Long.MAX_VALUE - 300 * MS, 200 * MS));

        elapsed.nanos();

        assertEquals("the second step crosses the end of long, and the difference is still right", //$NON-NLS-1$
            400 * MS, elapsed.nanos());
    }

    /**
     * One enormous step must not wrap the total back to "no time has passed" - the failure the
     * saturation exists to make unreachable.
     */
    @Test
    public void testAnEnormousStepSaturatesRatherThanWrapping()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, Long.MAX_VALUE / 2));

        elapsed.nanos();
        elapsed.nanos();

        assertTrue("three half-ranges must not come back as a small or negative reading", //$NON-NLS-1$
            elapsed.nanos() > 0L);
    }

    @Test
    public void testMillisIsTheSameReadingScaled()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, 1_500 * MS));

        assertEquals(1_500L, elapsed.millis());
    }

    /** A reading is charged from the previous READING, not from the previous call to millis(). */
    @Test
    public void testMillisAndNanosShareOneAccumulator()
    {
        ElapsedTime elapsed = new ElapsedTime(new SteppingTicker(0L, 700 * MS));

        elapsed.millis();

        assertEquals(1_400 * MS, elapsed.nanos());
    }

    /**
     * A SHARED instance charges every tick exactly once, however many threads read it.
     * <p>
     * This is the shape the registry actually uses: ONE {@code ElapsedTime} read both from
     * synchronized registry methods and from its waiting loops, which deliberately do not hold the
     * registry monitor. {@link ElapsedTime#nanos()} is a read-modify-write over two fields, so
     * without a lock two readers can take the same previous reading and both charge the same step.
     * The total then runs FAST - and a total that runs fast expires a standing claim before its
     * budget and reclaims a session before its idle TTL, which is exactly what the monotonic source
     * was introduced to prevent.
     * <p>
     * The ticker advances by one nanosecond per call, so the answer is not approximate: after
     * {@code THREADS * READS} calls the total must be exactly that many nanoseconds. A lost update
     * makes it smaller, a double charge makes it larger; only charging each step once lands on it.
     * <p>
     * It is a STRESS pin, not a forced interleaving - it cannot fail on a correct implementation,
     * and on the unsynchronized one the window is hit within the first few thousand reads.
     */
    @Test
    public void testASharedInstanceChargesEveryTickExactlyOnceUnderConcurrentReaders() throws Exception
    {
        final int threads = 4;
        final int reads = 50_000;
        AtomicLong ticks = new AtomicLong();
        // The construction itself takes a reading, so the ticks it consumes are not charged.
        ElapsedTime shared = new ElapsedTime(ticks::incrementAndGet);
        long consumedByConstruction = ticks.get();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> running = new ArrayList<>();
        for (int t = 0; t < threads; t++)
        {
            running.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < reads; i++)
                {
                    shared.nanos();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : running)
        {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        long charged = shared.nanos();
        long ticksTaken = ticks.get();
        assertEquals("every tick the readers observed must be charged exactly once",
            ticksTaken - consumedByConstruction, charged);
    }
}
