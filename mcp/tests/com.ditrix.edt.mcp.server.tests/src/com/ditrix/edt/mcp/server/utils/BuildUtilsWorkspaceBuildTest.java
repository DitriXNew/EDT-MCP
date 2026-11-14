/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for {@link BuildUtils#awaitWorkspaceBuild(long)} and its seam
 * {@link BuildUtils#awaitJobFamilies}: the BOUNDED wait for the workspace build that a rename runs
 * before it collects references, so a module written just before is in the BSL index (#935).
 * <p>
 * The jobs here belong to a family of their own, so nothing in the test runtime's real workspace
 * build can make them pass or fail. Every job's work waits on a latch with a finite ceiling, so a
 * lost bound fails an elapsed-time assertion instead of hanging the suite.
 */
public class BuildUtilsWorkspaceBuildTest
{
    private static final long CEILING_MS = 30_000;

    private final Object family = new Object();
    private final CountDownLatch release = new CountDownLatch(1);

    @After
    public void tearDown()
    {
        release.countDown();
        Job.getJobManager().cancel(family);
    }

    private Job familyJob(long runMs)
    {
        Job job = new Job("test #935 build stand-in") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    if (runMs >= 0)
                    {
                        Thread.sleep(runMs);
                    }
                    else
                    {
                        release.await(CEILING_MS, TimeUnit.MILLISECONDS);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return Status.OK_STATUS;
            }

            @Override
            public boolean belongsTo(Object candidate)
            {
                return candidate == family;
            }
        };
        job.setSystem(true);
        return job;
    }

    @Test
    public void testTheWorkspaceBuildFamiliesAreTheAutoAndManualBuild()
    {
        assertEquals(List.of(ResourcesPlugin.FAMILY_AUTO_BUILD, ResourcesPlugin.FAMILY_MANUAL_BUILD),
            BuildUtils.WORKSPACE_BUILD_FAMILIES);
    }

    @Test
    public void testNoPendingJobReturnsAtOnce()
    {
        long start = System.nanoTime();
        assertTrue(BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), 5_000));
        assertTrue("nothing pending must not wait", elapsedMs(start) < 1_000); //$NON-NLS-1$
    }

    @Test
    public void testASleepingBuildIsWaitedForUntilItHasRun()
    {
        // The auto-build is scheduled with a delay: it is SLEEPING when the write that caused it
        // returns. A wait that skips sleeping jobs is the #935 race all over again.
        Job job = familyJob(300);
        job.schedule(700);
        long start = System.nanoTime();

        boolean drained = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS);

        assertTrue("the build finished well inside the deadline", drained); //$NON-NLS-1$
        assertNotNull("the wait must not return before the scheduled build has run", job.getResult()); //$NON-NLS-1$
        assertTrue("the wait covered the delay and the run", elapsedMs(start) >= 900); //$NON-NLS-1$
    }

    @Test
    public void testABuildOutlivingTheDeadlineIsReportedNotWaitedOut()
    {
        Job job = familyJob(-1);
        job.schedule();
        long start = System.nanoTime();

        boolean drained = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), 800);

        long elapsed = elapsedMs(start);
        assertFalse("a build still running at the deadline is not a drained build", drained); //$NON-NLS-1$
        assertNull("the build is still running", job.getResult()); //$NON-NLS-1$
        assertTrue("the wait honoured its deadline (" + elapsed + " ms)", //$NON-NLS-1$ //$NON-NLS-2$
            elapsed >= 700 && elapsed < 10_000);
    }

    @Test
    public void testOneDeadlineCoversEveryFamily()
    {
        Object second = new Object();
        Job first = familyJob(-1);
        first.schedule();
        long start = System.nanoTime();

        // The first family never drains; the second must not get a fresh deadline of its own.
        boolean drained = BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family, second), 600);

        assertFalse(drained);
        assertTrue(elapsedMs(start) < 10_000);
    }

    @Test
    public void testAnInterruptedCallerDoesNotWait()
    {
        Job job = familyJob(-1);
        job.schedule();
        Thread.currentThread().interrupt();
        try
        {
            long start = System.nanoTime();
            assertFalse(BuildUtils.awaitJobFamilies(Job.getJobManager(), List.of(family), CEILING_MS));
            assertTrue(elapsedMs(start) < 5_000);
            assertTrue("the interrupt must be kept for the caller", Thread.currentThread().isInterrupted()); //$NON-NLS-1$
        }
        finally
        {
            Thread.interrupted();
        }
    }

    private static long elapsedMs(long start)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
