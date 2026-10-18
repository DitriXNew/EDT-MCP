/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * A test stand-in for a branch change of this server that outlives its caller's wait (#656): a scheduled
 * {@link Job} that runs until {@link #finish()} - like a hung checkout, it ignores a cancellation request, and only
 * records that it saw one.
 */
public final class BlockingJob extends Job
{
    private final CountDownLatch started = new CountDownLatch(1);

    private final CountDownLatch finish = new CountDownLatch(1);

    private volatile boolean sawCancel;

    private BlockingJob()
    {
        super("test: a branch change that outlives its wait"); //$NON-NLS-1$
        setSystem(true);
    }

    /** Schedules a blocking job and returns once it runs. */
    public static BlockingJob running() throws InterruptedException
    {
        BlockingJob job = new BlockingJob();
        job.schedule();
        assertTrue("the job must start", job.started.await(10, TimeUnit.SECONDS)); //$NON-NLS-1$
        return job;
    }

    @Override
    protected IStatus run(IProgressMonitor monitor)
    {
        started.countDown();
        try
        {
            finish.await(60, TimeUnit.SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        sawCancel = monitor.isCanceled();
        return Status.OK_STATUS;
    }

    /** Lets the job end and waits until it has. */
    public void finish() throws InterruptedException
    {
        finish.countDown();
        join(10_000, null);
    }

    /** @return whether a cancellation was requested while it ran (read after {@link #finish()}) */
    public boolean sawCancel()
    {
        return sawCancel;
    }

    /** Waits until no branch change is recorded as running for {@code project} any more, failing after 10 s. */
    public static void awaitNotInFlight(String project) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (BranchBindingLock.inFlightAmong(List.of(project)) != null && System.nanoTime() < deadline)
        {
            Thread.sleep(20);
        }
        assertNull("the ended job must no longer be recorded for '" + project + "'", //$NON-NLS-1$ //$NON-NLS-2$
            BranchBindingLock.inFlightAmong(List.of(project)));
    }

    /** Records {@code job} as running for {@code projects} the way this server does: under the lock, no wait. */
    public static void record(Job job, List<String> projects) throws InterruptedException
    {
        assertTrue(BranchBindingLock.tryLock());
        try
        {
            BranchBindingLock.awaitOrTrack(job, 0, () -> projects);
        }
        finally
        {
            BranchBindingLock.unlock();
        }
    }
}
