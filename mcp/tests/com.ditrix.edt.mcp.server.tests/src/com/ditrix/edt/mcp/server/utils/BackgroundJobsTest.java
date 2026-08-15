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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.debug.core.ILaunch;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobWork;
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
            JobSnapshot admitted = startWhenAdmitted(jobs, 60_000L, 1, "start", //$NON-NLS-1$
                progress -> "third"); //$NON-NLS-1$
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
                    startWhenAdmitted(jobs, 60_000L, 2, "start", progress -> "third")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void testCommittedCancellationDoesNotBlockSameJobSnapshot() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            CancellationCapability capability = CancellationCapability.of("slow cancellation", () -> { //$NON-NLS-1$
                handlerEntered.countDown();
                releaseHandler.await();
                return BackgroundJobs.CommittedCancellation.stopped("stopped", "partial"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            JobSnapshot started = jobs.start("slow_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWork.await();
                    return "done"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            AtomicReference<CancellationResult> cancellation = new AtomicReference<>();
            Thread canceller = new Thread(() -> cancellation.set(jobs.cancel(started.getId())));
            canceller.setDaemon(true);
            canceller.start();
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));

            AtomicReference<JobSnapshot> snapshot = new AtomicReference<>();
            CountDownLatch snapshotReturned = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                snapshot.set(jobs.get(started.getId()));
                snapshotReturned.countDown();
            });
            reader.setDaemon(true);
            boolean returnedPromptly;
            try
            {
                reader.start();
                returnedPromptly = snapshotReturned.await(500, TimeUnit.MILLISECONDS);
                if (returnedPromptly)
                {
                    assertEquals(Status.RUNNING, snapshot.get().getStatus());
                    assertEquals("the handler must still be waiting when the snapshot returns", //$NON-NLS-1$
                        1L, releaseHandler.getCount());
                }
            }
            finally
            {
                releaseHandler.countDown();
                releaseWork.countDown();
                canceller.join(2_000L);
                reader.join(2_000L);
            }

            assertTrue("same-job snapshots must not wait for the owner cancellation handler", //$NON-NLS-1$
                returnedPromptly);
            assertEquals(CancellationOutcome.TERMINATED,
                cancellation.get().getOutcome());
        }
    }

    @Test
    public void testConcurrentSecondCancelDoesNotInvokeCommittedHandlerTwice() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            AtomicInteger handlerInvocations = new AtomicInteger();
            CancellationCapability capability = CancellationCapability.of("slow cancellation", () -> { //$NON-NLS-1$
                handlerInvocations.incrementAndGet();
                handlerEntered.countDown();
                releaseHandler.await();
                return BackgroundJobs.CommittedCancellation.stopped("stopped", "partial"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            JobSnapshot started = jobs.start("slow_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWork.await();
                    return "done"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            AtomicReference<CancellationResult> first = new AtomicReference<>();
            AtomicReference<CancellationResult> second = new AtomicReference<>();
            Thread firstCanceller = new Thread(() -> first.set(jobs.cancel(started.getId())));
            firstCanceller.setDaemon(true);
            firstCanceller.start();
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));

            CountDownLatch secondReturned = new CountDownLatch(1);
            Thread secondCanceller = new Thread(() -> {
                second.set(jobs.cancel(started.getId()));
                secondReturned.countDown();
            });
            secondCanceller.setDaemon(true);
            boolean returnedPromptly;
            try
            {
                secondCanceller.start();
                returnedPromptly = secondReturned.await(500, TimeUnit.MILLISECONDS);
                if (returnedPromptly)
                {
                    assertEquals(CancellationOutcome.ALREADY_COMMITTED,
                        second.get().getOutcome());
                    assertEquals(Status.RUNNING, second.get().getSnapshot().getStatus());
                    // It must say a cancellation is already running, NOT that the work cannot be
                    // recalled: the first cancellation is still in flight and may yet stop it, so
                    // the generic committed wording would be a false statement to the caller.
                    assertEquals("A cancellation of this job is already in progress and has not " //$NON-NLS-1$
                        + "finished, so this request did nothing and did not start a second one.", //$NON-NLS-1$
                        second.get().getDetail());
                }
                assertEquals(1, handlerInvocations.get());
            }
            finally
            {
                releaseHandler.countDown();
                releaseWork.countDown();
                firstCanceller.join(2_000L);
                secondCanceller.join(2_000L);
            }

            assertTrue("a concurrent second cancel must return without waiting for the handler", //$NON-NLS-1$
                returnedPromptly);
            assertEquals(1, handlerInvocations.get());
            assertEquals(CancellationOutcome.TERMINATED, first.get().getOutcome());
        }
    }

    @Test
    public void testCommittedCancellationWaitsForWorkerExitBeforePublishingAndReusingSlot()
        throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            CancellationCapability capability = CancellationCapability.of("stop parked work", //$NON-NLS-1$
                () -> BackgroundJobs.CommittedCancellation.stopped("launch stopped", "partial")); //$NON-NLS-1$ //$NON-NLS-2$
            JobSnapshot started = jobs.start("parked_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    while (releaseWork.getCount() > 0)
                    {
                        try
                        {
                            releaseWork.await();
                        }
                        catch (InterruptedException e)
                        {
                            // The cancelled external launch does not prove this callable exited.
                            Thread.interrupted();
                        }
                    }
                    return "worker returned"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            try
            {
                CancellationResult cancellation = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.TERMINATED, cancellation.getOutcome());
                assertEquals("the launch stop must not overstate the parked worker's lifecycle", //$NON-NLS-1$
                    Status.RUNNING, cancellation.getSnapshot().getStatus());
                assertEquals(Status.RUNNING, jobs.await(started.getId(), 100L).getStatus());
                assertNull("a replacement was admitted while the cancelled worker still ran", //$NON-NLS-1$
                    jobs.start(60_000L, 1, "replacement", progress -> "overlap")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            finally
            {
                releaseWork.countDown();
            }

            JobSnapshot cancelled = jobs.await(started.getId(), 2_000L);
            assertEquals(Status.CANCELLED, cancelled.getStatus());
            assertEquals("partial", cancelled.getResult()); //$NON-NLS-1$
            assertNotNull("the slot must return when the parked callable actually exits", //$NON-NLS-1$
                startWhenAdmitted(jobs, 60_000L, 1, "replacement", //$NON-NLS-1$
                    progress -> "after exit")); //$NON-NLS-1$
        }
    }

    @Test
    public void testHungCommittedCancellationHandlerKeepsClaimAndReleasesDeferredOutcome()
        throws Exception
    {
        // Production deliberately allows 30 seconds; this isolated registry uses the same path
        // with a short guard so the regression remains deterministic and fast.
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1, 100L))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            AtomicInteger handlerInvocations = new AtomicInteger();
            CancellationCapability capability = CancellationCapability.of("hung cancellation", () -> { //$NON-NLS-1$
                handlerInvocations.incrementAndGet();
                handlerEntered.countDown();
                while (releaseHandler.getCount() > 0)
                {
                    try
                    {
                        releaseHandler.await();
                    }
                    catch (InterruptedException e)
                    {
                        // Models a platform call such as ILaunch.terminate() ignoring interruption.
                        Thread.interrupted();
                    }
                }
                return BackgroundJobs.CommittedCancellation.stopped("too late", "wrong"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            JobSnapshot started = jobs.start("hung_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWork.await();
                    return "real worker outcome"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            AtomicReference<CancellationResult> result = new AtomicReference<>();
            Thread canceller = new Thread(() -> result.set(jobs.cancel(started.getId())));
            canceller.setDaemon(true);
            try
            {
                canceller.start();
                assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
                releaseWork.countDown();
                canceller.join(2_000L);

                assertNotNull("the outer handler guard must return the cancellation reply", //$NON-NLS-1$
                    result.get());
                assertEquals(CancellationOutcome.ALREADY_COMMITTED, result.get().getOutcome());
                assertTrue(result.get().getDetail().contains("did not complete within 100 ms")); //$NON-NLS-1$
                assertTrue(result.get().getDetail().contains("may still be running")); //$NON-NLS-1$
                assertEquals("the worker outcome deferred behind the claim must be published", //$NON-NLS-1$
                    Status.DONE, result.get().getSnapshot().getStatus());
                assertEquals("real worker outcome", result.get().getSnapshot().getResult()); //$NON-NLS-1$
                assertTrue("the handler thread still owns the job after its waiter times out", //$NON-NLS-1$
                    result.get().getSnapshot().isCancellationHandlerInFlight());

                CancellationResult second = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.ALREADY_COMMITTED, second.getOutcome());
                assertEquals("A cancellation of this job is already in progress and has not " //$NON-NLS-1$
                    + "finished, so this request did nothing and did not start a second one.", //$NON-NLS-1$
                    second.getDetail());
                assertEquals("the destructive handler must be invoked exactly once", //$NON-NLS-1$
                    1, handlerInvocations.get());
            }
            finally
            {
                releaseWork.countDown();
                releaseHandler.countDown();
                canceller.join(2_000L);
            }
            assertEquals(1, handlerInvocations.get());
        }
    }

    @Test
    public void testHandlerStoppingRightAfterTheGuardInterruptStillCancelsTheJob() throws Exception
    {
        // The guard's own interrupt is usually what ends an owner's verification wait, so the
        // stop it reports immediately afterwards is real. Dropping it would leave the job
        // uncancelled and let the worker publish a killed run as an ordinary success.
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1, 100L))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            CancellationCapability capability = CancellationCapability.of("late stop", () -> { //$NON-NLS-1$
                handlerEntered.countDown();
                try
                {
                    // Never released: only the guard's interrupt ends this verification wait.
                    new CountDownLatch(1).await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return BackgroundJobs.CommittedCancellation.stopped(
                    "the client really was killed", "partial report"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            JobSnapshot started = jobs.start("late_stop_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWork.await();
                    return "clean-looking result"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            CancellationResult result = jobs.cancel(started.getId());
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            assertEquals("a stop reported just after the guard interrupt is honoured, not dropped", //$NON-NLS-1$
                CancellationOutcome.TERMINATED, result.getOutcome());
            assertEquals("the client really was killed", result.getDetail()); //$NON-NLS-1$

            releaseWork.countDown();
            JobSnapshot cancelled = jobs.await(started.getId(), 2_000L);
            assertEquals("the worker's later normal return must not publish a killed run as DONE", //$NON-NLS-1$
                Status.CANCELLED, cancelled.getStatus());
            assertEquals("partial report", cancelled.getResult()); //$NON-NLS-1$
            assertTrue(cancelled.getProgress().stream().anyMatch(
                entry -> entry.getMessage().contains("overran the 100 ms guard"))); //$NON-NLS-1$
        }
    }

    @Test
    public void testLateHandlerOutcomeCannotMakeJobTerminalAfterItsWaiterTimedOut()
        throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1, 100L))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            CancellationCapability capability = CancellationCapability.of("late cancellation", () -> { //$NON-NLS-1$
                handlerEntered.countDown();
                while (releaseHandler.getCount() > 0)
                {
                    try
                    {
                        releaseHandler.await();
                    }
                    catch (InterruptedException e)
                    {
                        Thread.interrupted();
                    }
                }
                return BackgroundJobs.CommittedCancellation.stopped("stale stop", "stale result"); //$NON-NLS-1$ //$NON-NLS-2$
            });
            JobSnapshot started = jobs.start("late_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    while (releaseWork.getCount() > 0)
                    {
                        try
                        {
                            releaseWork.await();
                        }
                        catch (InterruptedException e)
                        {
                            Thread.interrupted();
                        }
                    }
                    return "worker outcome"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            AtomicReference<CancellationResult> cancellation = new AtomicReference<>();
            Thread canceller = new Thread(() -> cancellation.set(jobs.cancel(started.getId())));
            canceller.setDaemon(true);
            try
            {
                canceller.start();
                assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
                canceller.join(2_000L);
                assertEquals(CancellationOutcome.ALREADY_COMMITTED,
                    cancellation.get().getOutcome());
                assertEquals(Status.RUNNING, jobs.get(started.getId()).getStatus());

                releaseHandler.countDown();
                assertTrue("the handler did not actually exit", //$NON-NLS-1$
                    waitForHandlerExit(jobs, started.getId()));
                assertEquals("a stale handler result must not publish cancellation", //$NON-NLS-1$
                    Status.RUNNING, jobs.get(started.getId()).getStatus());
            }
            finally
            {
                releaseHandler.countDown();
                releaseWork.countDown();
                canceller.join(2_000L);
            }

            JobSnapshot done = jobs.await(started.getId(), 2_000L);
            assertEquals(Status.DONE, done.getStatus());
            assertEquals("worker outcome", done.getResult()); //$NON-NLS-1$
        }
    }

    @Test
    public void testInterruptedYaxunitVerificationPreservesIrreversibleTerminationRequest()
        throws Exception
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.canTerminate()).thenReturn(true);
        when(launch.isTerminated()).thenReturn(false);
        CountDownLatch terminateReturned = new CountDownLatch(1);
        doAnswer(invocation -> {
            terminateReturned.countDown();
            return null;
        }).when(launch).terminate();

        Path reportDir = Files.createTempDirectory("edt-mcp-yaxunit-interrupted-stop-test-"); //$NON-NLS-1$
        YaxunitJobCancellation cancellation = new YaxunitJobCancellation(null, 10);
        cancellation.track(launch, reportDir);
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1, 100L))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch releaseWork = new CountDownLatch(1);
            JobSnapshot started = jobs.start("interrupted_yaxunit", 60_000L, "start", //$NON-NLS-1$ //$NON-NLS-2$
                cancellation.capability(), progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    releaseWork.await();
                    return "clean-looking result"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));

            CancellationResult requested = jobs.cancel(started.getId());
            assertTrue(terminateReturned.await(2, TimeUnit.SECONDS));
            assertEquals(CancellationOutcome.TERMINATION_REQUESTED, requested.getOutcome());
            assertTrue(requested.getDetail().contains(
                "termination verification was interrupted")); //$NON-NLS-1$
            assertEquals(Status.RUNNING, requested.getSnapshot().getStatus());

            releaseWork.countDown();
            JobSnapshot cancelled = jobs.await(started.getId(), 2_000L);
            assertEquals(Status.CANCELLED, cancelled.getStatus());
            assertTrue(cancelled.getResult().toString().contains(
                "termination verification was interrupted")); //$NON-NLS-1$
            assertTrue(!cancelled.getResult().toString().contains("clean-looking result")); //$NON-NLS-1$
        }
        finally
        {
            verify(launch).terminate();
            Files.deleteIfExists(reportDir);
        }
    }

    @Test
    public void testThrowingCommittedCancellationHandlerLeavesJobRunning() throws Exception
    {
        String detail = "The owning tool's cancellation handler failed, so the committed " //$NON-NLS-1$
            + "work was NOT reported as stopped: owner failure"; //$NON-NLS-1$
        verifyRejectedCommittedCancellation(() -> {
            throw new IllegalStateException("owner failure"); //$NON-NLS-1$
        }, detail);
    }

    @Test
    public void testNullCommittedCancellationHandlerResultLeavesJobRunning() throws Exception
    {
        String detail = "The owning tool's cancellation handler returned no outcome, so " //$NON-NLS-1$
            + "the committed work was NOT reported as stopped."; //$NON-NLS-1$
        verifyRejectedCommittedCancellation(() -> null, detail);
    }

    @Test
    public void testCommittedJobWithoutCancellationCapabilityRemainsRunning() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot started = jobs.start("capability_free_owner", 60_000L, "start", //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    release.await();
                    return "done"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));
            try
            {
                CancellationResult cancellation = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.ALREADY_COMMITTED,
                    cancellation.getOutcome());
                assertEquals(Status.RUNNING, cancellation.getSnapshot().getStatus());
                assertTrue(cancellation.getSnapshot().getProgress().stream().anyMatch(entry ->
                    entry.getMessage().contains("cannot be recalled"))); //$NON-NLS-1$
            }
            finally
            {
                release.countDown();
            }
        }
    }

    /**
     * A start that is REFUSED stores nothing, so it must not pay for room it never uses. The
     * eviction that makes that room discards a completed job's result, and its owner is still
     * entitled to poll for it by id.
     */
    @Test
    public void testRefusedStartDoesNotDiscardARetainedResult() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(2, 2))
        {
            JobSnapshot done = jobs.start(60_000L, 5, "start", progress -> "keep me"); //$NON-NLS-1$ //$NON-NLS-2$
            assertNotNull(done);
            assertEquals(Status.DONE, jobs.await(done.getId(), 5_000L).getStatus());

            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch busy = new CountDownLatch(1);
            JobSnapshot running = startWhenAdmitted(jobs, 60_000L, 1, "start", progress -> { //$NON-NLS-1$
                busy.countDown();
                release.await();
                return "busy"; //$NON-NLS-1$
            });
            assertNotNull(running);
            assertTrue(busy.await(2, TimeUnit.SECONDS));

            try
            {
                // The registry is at capacity AND at its running limit, so this is refused.
                assertNull(jobs.start(60_000L, 1, "start", progress -> "no room")); //$NON-NLS-1$ //$NON-NLS-2$
                assertNotNull("the refused start evicted a result nobody replaced", //$NON-NLS-1$
                    jobs.get(done.getId()));
                assertEquals("keep me", jobs.get(done.getId()).getResult()); //$NON-NLS-1$
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

    /**
     * A terminal status is published before its admission slot is released, so a finished job
     * does not imply that its slot is back; tests must wait for the deliberate release ordering.
     */
    private static JobSnapshot startWhenAdmitted(BackgroundJobs jobs, long timeoutMs, int maxRunning,
        String initialProgress, JobWork work) throws InterruptedException
    {
        JobSnapshot admitted = null;
        long until = System.currentTimeMillis() + 5_000L;
        while (admitted == null && System.currentTimeMillis() < until)
        {
            admitted = jobs.start(timeoutMs, maxRunning, initialProgress, work);
            if (admitted == null)
            {
                Thread.sleep(20L);
            }
        }
        return admitted;
    }

    private static boolean waitForHandlerExit(BackgroundJobs jobs, String jobId)
        throws InterruptedException
    {
        long until = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < until)
        {
            JobSnapshot snapshot = jobs.get(jobId);
            if (snapshot != null && !snapshot.isCancellationHandlerInFlight())
            {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    private static JobSnapshot completed(BackgroundJobs jobs, String value)
    {
        JobSnapshot started = jobs.start(1000, value, progress -> value);
        JobSnapshot done = jobs.await(started.getId(), 1000);
        assertEquals(Status.DONE, done.getStatus());
        return done;
    }

    private static void verifyRejectedCommittedCancellation(
        BackgroundJobs.CommittedCancellationHandler handler, String expectedDetail)
        throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CancellationCapability capability = CancellationCapability.of(
                "test cancellation", handler); //$NON-NLS-1$
            JobSnapshot started = jobs.start("failure_owner", 60_000L, "start", capability, //$NON-NLS-1$ //$NON-NLS-2$
                progress -> {
                    assertTrue(progress.tryCommit());
                    committed.countDown();
                    release.await();
                    return "done"; //$NON-NLS-1$
                });
            assertTrue(committed.await(2, TimeUnit.SECONDS));
            try
            {
                CancellationResult cancellation = jobs.cancel(started.getId());
                assertEquals(CancellationOutcome.ALREADY_COMMITTED,
                    cancellation.getOutcome());
                assertEquals(expectedDetail, cancellation.getDetail());
                assertEquals(Status.RUNNING, cancellation.getSnapshot().getStatus());
                assertEquals(Status.RUNNING, jobs.get(started.getId()).getStatus());
                assertTrue(cancellation.getSnapshot().getProgress().stream().anyMatch(entry ->
                    expectedDetail.equals(entry.getMessage())));
            }
            finally
            {
                release.countDown();
            }
        }
    }
}
