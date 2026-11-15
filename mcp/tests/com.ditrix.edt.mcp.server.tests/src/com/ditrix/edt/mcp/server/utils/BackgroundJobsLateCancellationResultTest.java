/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CommittedCancellation;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/**
 * {@link BackgroundJobs.ProgressReporter#cancellationResult}: the work's own report fills a committed
 * cancellation that settled without one - and nothing else (#944, review round 2).
 */
public class BackgroundJobsLateCancellationResultTest
{
    private BackgroundJobs jobs;

    @Before
    public void setUp()
    {
        jobs = new BackgroundJobs(10, 2);
    }

    @After
    public void tearDown()
    {
        jobs.close();
    }

    @Test(timeout = 30_000)
    public void testALateReportFillsACancellationThatSettledWithoutOne() throws Exception
    {
        JobSnapshot snapshot = cancelAndReport(CommittedCancellation.stopInitiated("gave up waiting", null), "report", true); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Status.CANCELLED, snapshot.getStatus());
        assertEquals("report", snapshot.getResult()); //$NON-NLS-1$
    }

    @Test(timeout = 30_000)
    public void testALateReportDoesNotReplaceTheHandlersOwnResult() throws Exception
    {
        JobSnapshot snapshot = cancelAndReport(CommittedCancellation.stopped("stopped", "handler"), "worker", true); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(Status.CANCELLED, snapshot.getStatus());
        assertEquals("handler", snapshot.getResult()); //$NON-NLS-1$
    }

    @Test(timeout = 30_000)
    public void testALateReportDoesNothingForAnUncommittedCancel() throws Exception
    {
        JobSnapshot snapshot = cancelAndReport(null, "worker", false); //$NON-NLS-1$
        assertEquals(Status.CANCELLED, snapshot.getStatus());
        assertNull("an ordinary cancel keeps its null result", snapshot.getResult()); //$NON-NLS-1$
    }

    /**
     * Starts a job that commits (or not), cancels it with {@code handlerOutcome}, then lets the work hand
     * in {@code late} through the reporter, and returns the terminal snapshot.
     */
    private JobSnapshot cancelAndReport(CommittedCancellation handlerOutcome, String late, boolean commit)
        throws Exception
    {
        CountDownLatch inWork = new CountDownLatch(1);
        CountDownLatch decided = new CountDownLatch(1);
        CancellationCapability capability = CancellationCapability.of("stops it", () -> handlerOutcome); //$NON-NLS-1$
        JobSnapshot started = jobs.start("late_report_test", 20_000, Integer.MAX_VALUE, "queued", //$NON-NLS-1$ //$NON-NLS-2$
            capability, progress -> {
                if (commit)
                {
                    progress.tryCommit();
                }
                inWork.countDown();
                while (true)
                {
                    try
                    {
                        if (decided.await(10, TimeUnit.SECONDS))
                        {
                            break;
                        }
                    }
                    catch (InterruptedException e)
                    {
                        // a cancel may interrupt this thread; the work still hands in its report
                    }
                }
                progress.cancellationResult(late);
                throw new IllegalStateException("stopped"); //$NON-NLS-1$
            });
        assertTrue(inWork.await(10, TimeUnit.SECONDS));
        CancellationOutcome outcome = jobs.cancel(started.getId()).getOutcome();
        assertTrue(String.valueOf(outcome), outcome != CancellationOutcome.ALREADY_TERMINAL);
        decided.countDown();
        return jobs.await(started.getId(), 10_000);
    }
}
