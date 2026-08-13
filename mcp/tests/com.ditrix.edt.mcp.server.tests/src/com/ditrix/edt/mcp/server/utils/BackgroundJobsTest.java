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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/** Focused lifecycle and capacity tests for {@link BackgroundJobs}. */
public class BackgroundJobsTest
{
    /**
     * Admission has to happen INSIDE the same lock as insertion. A caller that counts first
     * and starts afterwards has a window where several concurrent starts all see room, which
     * is exactly what a running limit exists to prevent.
     */
    @Test
    public void testConcurrentStartsCannotExceedTheRunningLimit() throws Exception
    {
        final int limit = 2;
        final int racers = 8;
        try (BackgroundJobs jobs = new BackgroundJobs(50, 4))
        {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();

            for (int i = 0; i < racers; i++)
            {
                Thread racer = new Thread(() -> {
                    ready.countDown();
                    try
                    {
                        go.await();
                        JobSnapshot started = jobs.start(60_000L, limit, "start", progress -> { //$NON-NLS-1$
                            release.await();
                            return "done"; //$NON-NLS-1$
                        });
                        if (started != null)
                        {
                            admitted.incrementAndGet();
                        }
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                });
                racer.setDaemon(true);
                racer.start();
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            Thread.sleep(200);
            release.countDown();

            assertEquals("no more than the limit may ever be admitted", //$NON-NLS-1$
                limit, admitted.get());
        }
    }

    /**
     * Work that has handed its request over cannot take it back, so the deadline must stop
     * manufacturing a failure for it: the published "timed out, start a new job" would be
     * answered with a retry that performs the SAME action twice.
     */
    @Test
    public void testDeadlineDoesNotFailWorkThatCanNoLongerBeAbandoned() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot started = jobs.start(50L, "start", progress -> { //$NON-NLS-1$
                assertTrue(progress.tryCommit());
                committed.countDown();
                release.await();
                return "handed over"; //$NON-NLS-1$
            });
            assertNotNull(started);
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            // Well past the 50 ms budget: an uncommitted job would be FAILED by now.
            JobSnapshot afterDeadline = jobs.await(started.getId(), 500L);
            assertEquals(Status.RUNNING, afterDeadline.getStatus());
            assertTrue(afterDeadline.getProgress().stream().anyMatch(
                entry -> entry.getMessage().contains("already handed over"))); //$NON-NLS-1$

            release.countDown();
            JobSnapshot finished = jobs.await(started.getId(), 5_000L);
            assertEquals(Status.DONE, finished.getStatus());
            assertEquals("handed over", finished.getResult()); //$NON-NLS-1$
        }
    }

    /**
     * The other half of the same race: when the deadline got there FIRST, the work must be
     * told so and skip the step it was about to take - otherwise the caller is handed a
     * failure while the action happens anyway.
     */
    @Test
    public void testWorkArrivingAfterTheDeadlineIsRefusedTheCommit() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            CountDownLatch decided = new CountDownLatch(1);
            AtomicBoolean allowed = new AtomicBoolean(true);
            JobSnapshot started = jobs.start(50L, "start", progress -> { //$NON-NLS-1$
                try
                {
                    // The deadline interrupts the worker, which is how this returns early.
                    Thread.sleep(10_000L);
                }
                catch (InterruptedException e)
                {
                    Thread.interrupted();
                }
                allowed.set(progress.tryCommit());
                decided.countDown();
                return "not asked"; //$NON-NLS-1$
            });
            assertNotNull(started);

            assertEquals(Status.FAILED, jobs.await(started.getId(), 5_000L).getStatus());
            assertTrue(decided.await(5, TimeUnit.SECONDS));
            assertTrue("a job the deadline already failed must refuse the commit", //$NON-NLS-1$
                !allowed.get());
        }
    }

    /**
     * The mirror of the queued-cancellation case: work that ignores interruption still OWNS its
     * worker thread after the job is failed. Handing the admission slot to a replacement then
     * promises a thread that does not exist, and the replacement waits in the queue instead of
     * running - which is the starvation the limit exists to prevent.
     */
    @Test
    public void testTimedOutWorkKeepsItsSlotUntilTheCallableActuallyExits() throws Exception
    {
        // ONE worker thread, so "admitted" and "actually running" cannot be confused.
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot started = jobs.start(50L, 1, "start", progress -> { //$NON-NLS-1$
                entered.countDown();
                while (release.getCount() > 0)
                {
                    try
                    {
                        release.await();
                    }
                    catch (InterruptedException e)
                    {
                        // Deliberately ignores the interrupt: the thread stays occupied.
                        Thread.interrupted();
                    }
                }
                return "eventually"; //$NON-NLS-1$
            });
            assertNotNull(started);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(Status.FAILED, jobs.await(started.getId(), 5_000L).getStatus());

            assertNull("a slot was handed out while its worker thread was still busy", //$NON-NLS-1$
                jobs.start(60_000L, 1, "start", progress -> "second")); //$NON-NLS-1$ //$NON-NLS-2$

            release.countDown();
            JobSnapshot admitted = null;
            long until = System.currentTimeMillis() + 5_000L;
            while (admitted == null && System.currentTimeMillis() < until)
            {
                admitted = jobs.start(60_000L, 1, "start", progress -> "third"); //$NON-NLS-1$ //$NON-NLS-2$
                if (admitted == null)
                {
                    Thread.sleep(20L);
                }
            }
            assertNotNull("the slot never came back after the work unwound", admitted); //$NON-NLS-1$
        }
    }

    /**
     * A job can be cancelled while its task is still QUEUED - the timeout fires before a
     * worker picks it up. {@code FutureTask.run()} then returns without ever running the
     * callable, so a slot released from inside the callable would never come back and the
     * admission limit would shrink permanently.
     */
    @Test
    public void testAdmissionSlotComesBackWhenTheTaskIsCancelledBeforeItRuns() throws Exception
    {
        // ONE worker thread, so the second job cannot start until the first one lets go.
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot running = jobs.start(60_000L, 5, "start", progress -> { //$NON-NLS-1$
                occupied.countDown();
                release.await();
                return "first"; //$NON-NLS-1$
            });
            assertNotNull(running);
            assertTrue(occupied.await(2, TimeUnit.SECONDS));

            JobSnapshot queued = jobs.start(50L, 5, "start", progress -> "never runs"); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(queued);
            assertEquals(Status.FAILED, jobs.await(queued.getId(), 5_000L).getStatus());

            try
            {
                // Two jobs were admitted and one of them is provably over, so a limit of two
                // must still have room. Without the slot coming back this returns null.
                assertNotNull("the cancelled job kept its admission slot", //$NON-NLS-1$
                    jobs.start(60_000L, 2, "start", progress -> "third")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                release.countDown();
            }
        }
    }

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
