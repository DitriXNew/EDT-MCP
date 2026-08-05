/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BoundedJob.Outcome;

/**
 * Tests for {@link BoundedJob}: the deadline that keeps a wedged platform call from holding an
 * unattended MCP request open forever (issue #349).
 * <p>
 * The "wedged" work in these tests waits on a latch with its OWN finite ceiling, so an
 * implementation that lost the bound fails the elapsed-time assertion instead of hanging the
 * whole suite — a test for a timeout must itself terminate when the timeout is gone.
 */
public class BoundedJobTest
{
    /**
     * Deadline for the timeout tests. The deadline starts at schedule time, so it must stay well
     * above the job-start latency: a job that had not started yet when the deadline elapsed is
     * cancelled before it ever runs, and the start-dependent assertions below would have nothing
     * to observe. Jobs start in single-digit milliseconds here, so 2s is a large margin.
     */
    private static final long SHORT_TIMEOUT_MS = 2000;

    /** Ceiling on the wedged work, well above {@link #SHORT_TIMEOUT_MS} but still finite. */
    private static final long WEDGE_CEILING_MS = 60_000;

    /** Bound that comfortably exceeds any real scheduling delay yet stays far below the wedge ceiling. */
    private static final long SANE_RETURN_MS = 30_000;

    /** Deadline for work that is expected to finish on its own. */
    private static final long GENEROUS_TIMEOUT_MS = 60_000;

    @Test
    public void testWorkThatReturnsIsReportedAsSuccess()
    {
        AtomicBoolean ran = new AtomicBoolean(false);

        BoundedJob.Result result = BoundedJob.run("test: quick work", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> ran.set(true));

        assertTrue("the work must have run", ran.get()); //$NON-NLS-1$
        assertEquals(Outcome.COMPLETED, result.getOutcome());
        assertTrue("work that returned without raising is a success", result.isSuccess()); //$NON-NLS-1$
        assertNull("nothing was raised", result.getFailure()); //$NON-NLS-1$
    }

    @Test
    public void testWorkFailureIsCapturedNotPropagated()
    {
        BoundedJob.Result result = BoundedJob.run("test: failing work", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                throw new IllegalStateException("boom"); //$NON-NLS-1$
            });

        assertEquals("a raising work still returned before the deadline", //$NON-NLS-1$
            Outcome.COMPLETED, result.getOutcome());
        assertFalse("a raising work is not a success", result.isSuccess()); //$NON-NLS-1$
        assertEquals("boom", result.getFailure().getMessage()); //$NON-NLS-1$
    }

    @Test
    public void testWorkThatDoesNotReturnTimesOutInsteadOfWaitingForIt() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            BoundedJob.Result result = BoundedJob.run("test: wedged work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
                monitor -> {
                    started.countDown();
                    release.await(WEDGE_CEILING_MS, TimeUnit.MILLISECONDS);
                });

            assertTrue("the work must have started", started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS)); //$NON-NLS-1$
            assertEquals(Outcome.TIMED_OUT, result.getOutcome());
            assertFalse("a timed-out run is not a success", result.isSuccess()); //$NON-NLS-1$
            assertNull("the work never returned, so it never reported a failure", result.getFailure()); //$NON-NLS-1$
            // The real assertion: the caller stopped waiting. Without the bound this would be
            // the work's own ceiling (WEDGE_CEILING_MS), not a fraction of it.
            assertTrue("the call must return on its deadline, not on the work's ceiling (waited " //$NON-NLS-1$
                + result.getElapsedMs() + "ms)", result.getElapsedMs() < SANE_RETURN_MS); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testTimeoutCancelsTheMonitorHandedToTheWork() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observedCancel = new CountDownLatch(1);

        BoundedJob.Result result = BoundedJob.run("test: cancellable work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                started.countDown();
                long ceiling = System.currentTimeMillis() + WEDGE_CEILING_MS;
                while (System.currentTimeMillis() < ceiling)
                {
                    if (monitor.isCanceled())
                    {
                        observedCancel.countDown();
                        return;
                    }
                    Thread.sleep(20);
                }
            });

        assertEquals(Outcome.TIMED_OUT, result.getOutcome());
        // Asserted separately so a stalled scheduler reports as "the work never started" rather
        // than masquerading as "cancellation was not propagated".
        assertTrue("the work must have started for this test to say anything about cancellation", //$NON-NLS-1$
            started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        assertTrue("work polling its monitor must see the cancellation the deadline raises", //$NON-NLS-1$
            observedCancel.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * The NOT_RUN guard distinguishes "the job left the queue" from "the work ran". Its own branch
     * needs a job cancelled by a third party before it starts, which cannot be produced
     * deterministically from here; what IS pinned is that adding the guard did not make the normal
     * COMPLETED path unreachable — the failure mode a "did it really run?" flag invites.
     */
    @Test
    public void testTheDidItRunGuardLeavesTheNormalCompletedPathReachable()
    {
        BoundedJob.Result result = BoundedJob.run("test: ordinary work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                // Reached only if the job actually runs.
            });

        assertEquals(Outcome.COMPLETED, result.getOutcome());
        assertTrue("work that ran and returned is a success", result.isSuccess()); //$NON-NLS-1$
    }
}
