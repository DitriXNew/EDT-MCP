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
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/** Focused lifecycle and capacity tests for {@link BackgroundJobs}. */
public class BackgroundJobsTest
{
    @Test
    public void testWorkRunsOnNamedDaemonWorker()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(2, 1))
        {
            AtomicReference<Thread> worker = new AtomicReference<>();
            JobSnapshot started = jobs.start(1000, "accepted", progress -> { //$NON-NLS-1$
                worker.set(Thread.currentThread());
                return "done"; //$NON-NLS-1$
            });
            JobSnapshot done = jobs.await(started.getId(), 1000);

            assertEquals(Status.DONE, done.getStatus());
            assertEquals("done", done.getResult()); //$NON-NLS-1$
            assertNotNull(worker.get());
            assertTrue(worker.get().isDaemon());
            assertTrue(worker.get().getName().startsWith("EDT-MCP background-job-worker-")); //$NON-NLS-1$
        }
    }

    @Test
    public void testOldestCompletedJobIsEvictedAtCapacity()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(2, 1))
        {
            JobSnapshot first = completed(jobs, "first"); //$NON-NLS-1$
            JobSnapshot second = completed(jobs, "second"); //$NON-NLS-1$
            JobSnapshot third = jobs.start(1000, "third", progress -> "third"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            assertNull(jobs.get(first.getId()));
            assertNotNull(jobs.get(second.getId()));
            assertNotNull(jobs.get(third.getId()));
        }
    }

    @Test
    public void testRegistryRejectsInsteadOfEvictingRunningJob() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BackgroundJobs jobs = new BackgroundJobs(1, 1))
        {
            jobs.start(5000, "running", progress -> { //$NON-NLS-1$
                entered.countDown();
                release.await();
                return null;
            });
            assertTrue(entered.await(1, TimeUnit.SECONDS));

            try
            {
                jobs.start(1000, "extra", progress -> null); //$NON-NLS-1$
                fail("Expected a full registry to reject another running job"); //$NON-NLS-1$
            }
            catch (RejectedExecutionException e)
            {
                assertTrue(e.getMessage().contains("full")); //$NON-NLS-1$
                assertTrue(e.getMessage().contains("1 running jobs")); //$NON-NLS-1$
            }
            finally
            {
                release.countDown();
            }
        }
    }

    private static JobSnapshot completed(BackgroundJobs jobs, String value)
    {
        JobSnapshot started = jobs.start(1000, value, progress -> value);
        JobSnapshot done = jobs.await(started.getId(), 1000);
        assertEquals(Status.DONE, done.getStatus());
        return done;
    }
}
