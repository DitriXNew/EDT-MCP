/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

public class QueryValidationJobsTest
{
    private static final long WAIT_MS = 5_000;

    @Test
    public void lateSuccessCannotCrossDeadlineAndCompletedFastWorkHasNoJob() throws Exception
    {
        AtomicLong now = new AtomicLong();
        try (var jobs = new QueryValidationJobs(2, 1, now::get))
        {
            var fast = jobs.run("Demo", 10, () -> false, cancel -> "fast");
            assertEquals("fast", fast.response());
            assertNull(fast.jobId());

            var late = jobs.run("Demo", 10, () -> false, cancel -> {
                now.set(TimeUnit.MILLISECONDS.toNanos(10));
                return "must not escape";
            });
            assertNull(late.response());
            if (late.jobId() != null)
            {
                // If detachment won the callback-exit race, that id is still valid after exit.
                var terminal = jobs.await(late.jobId(), 0);
                assertNotNull(terminal);
                assertNull(terminal.getResult());
            }
        }
    }

    @Test
    public void deliveryBeforeSubmissionDoesNotStartAWorker()
    {
        try (var jobs = new QueryValidationJobs(1, 1))
        {
            var result = jobs.run("Demo", WAIT_MS, () -> true, cancel -> {
                fail("An already-interrupted call must not invoke validation");
                return null;
            });
            assertNull(result.response());
            assertNull(result.jobId());
            assertTrue(jobs.running().isEmpty());
        }
    }

    @Test
    public void waitingThreadInterruptPromotesOnceAndRestoresItsInterruptBit() throws Exception
    {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerInterrupted = new CountDownLatch(1);
        var waiter = new AtomicReference<Thread>();
        var result = new AtomicReference<QueryValidationJobs.Result>();
        var preserved = new AtomicBoolean();
        try (var jobs = new QueryValidationJobs(1, 1))
        {
            Thread caller = new Thread(() -> {
                waiter.set(Thread.currentThread());
                result.set(jobs.run("Demo", WAIT_MS, () -> false, cancel -> {
                    entered.countDown();
                    try
                    {
                        release.await();
                    }
                    catch (InterruptedException e)
                    {
                        if (cancel.isCanceled())
                        {
                            workerInterrupted.countDown();
                        }
                        awaitRelease(release);
                    }
                    return "late";
                }));
                preserved.set(Thread.currentThread().isInterrupted());
            });
            caller.setDaemon(true);
            caller.start();
            try
            {
                assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
                waiter.get().interrupt();
                caller.join(WAIT_MS);
                assertFalse(caller.isAlive());
                assertTrue(preserved.get());
                assertTrue(workerInterrupted.await(WAIT_MS, TimeUnit.MILLISECONDS));
                String id = result.get().jobId();
                assertNotNull(id);
                assertEquals(id, jobs.running().get(0).getId());
                assertNull(result.get().response());
                jobs.close();
                assertThrows(RejectedExecutionException.class,
                    () -> jobs.run("Demo", WAIT_MS, () -> false, cancel -> "no"));
                release.countDown();
                // A closed pool is not a claim that the native call already finished.
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void completedDetachedRetentionIsBoundedAndFastCallsDoNotEvictIt() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        try (var jobs = new QueryValidationJobs(2, 1))
        {
            String old = detachAndFinish(jobs, caller);
            assertNotNull(jobs.await(old, 0));
            assertEquals("normal", jobs.run("Demo", WAIT_MS, () -> false, cancel -> "normal").response());
            assertEquals(Status.CANCELLED, jobs.await(old, 0).getStatus());

            String fresh = detachAndFinish(jobs, caller);
            assertNull(jobs.await(old, 0));
            assertEquals(Status.CANCELLED, jobs.await(fresh, 0).getStatus());
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void staleCancellationDoesNotInterruptTheReusedWorker() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        var firstThread = new AtomicReference<Thread>();
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        try (var jobs = new QueryValidationJobs(1, 2))
        {
            String old = detachAndFinish(jobs, caller, firstThread);
            var next = caller.submit(() -> jobs.run("Demo", WAIT_MS, () -> false, cancel -> {
                assertSame(firstThread.get(), Thread.currentThread());
                assertFalse(Thread.currentThread().isInterrupted());
                secondEntered.countDown();
                try
                {
                    releaseSecond.await();
                }
                catch (InterruptedException e)
                {
                    fail("Stale cancellation reached a different invocation");
                }
                assertFalse(cancel.isCanceled());
                return "new response";
            }));
            try
            {
                assertTrue(secondEntered.await(WAIT_MS, TimeUnit.MILLISECONDS));
                assertEquals(BackgroundJobs.CancellationOutcome.ALREADY_TERMINAL, jobs.cancel(old).getOutcome());
                releaseSecond.countDown();
                assertEquals("new response", next.get(WAIT_MS, TimeUnit.MILLISECONDS).response());
            }
            finally
            {
                releaseSecond.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void lateAccessAfterSharedStopCannotReopenLaneUntilExplicitStartup()
    {
        QueryValidationJobs original = QueryValidationJobs.shared();
        try
        {
            QueryValidationJobs.shutdownShared();
            assertSame(original, QueryValidationJobs.shared());
            assertThrows(RejectedExecutionException.class, () -> QueryValidationJobs.shared()
                .run("Demo", WAIT_MS, () -> false, cancel -> "must not start"));
            QueryValidationJobs.startShared();
            assertSame(original, QueryValidationJobs.shared());
            assertEquals("after restart", QueryValidationJobs.shared()
                .run("Demo", WAIT_MS, () -> false, cancel -> "after restart").response());
        }
        finally
        {
            QueryValidationJobs.startShared();
        }
    }

    @Test
    public void restartKeepsTheSameDetachedIdAndCapacityUntilRealExit() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new AtomicBoolean();
        try (var jobs = new QueryValidationJobs(1, 2))
        {
            var response = caller.submit(() -> jobs.run("Demo", WAIT_MS, delivered::get, cancel -> {
                entered.countDown();
                awaitRelease(release);
                return "discarded";
            }));
            try
            {
                assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
                delivered.set(true);
                String id = response.get(WAIT_MS, TimeUnit.MILLISECONDS).jobId();
                assertNotNull(id);
                jobs.pause();
                jobs.resume();

                assertEquals(id, jobs.running().get(0).getId());
                assertEquals(Status.RUNNING, jobs.await(id, 0).getStatus());
                assertEquals(id, jobs.cancel(id).getSnapshot().getId());
                assertThrows(RejectedExecutionException.class,
                    () -> jobs.run("NewProject", WAIT_MS, () -> false, cancel -> "over capacity"));
                release.countDown();
                assertEquals(Status.CANCELLED, jobs.await(id, WAIT_MS).getStatus());
                assertEquals("new", jobs.run("NewProject", WAIT_MS, () -> false, cancel -> "new").response());
            }
            finally
            {
                release.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void stopDetachesAnAttachedCallerWithoutWaitingAndStickyCancelSurvivesRestart() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelledInsideWorker = new AtomicBoolean();
        try (var jobs = new QueryValidationJobs(1, 2))
        {
            var response = caller.submit(() -> jobs.run("Demo", WAIT_MS, () -> false, cancel -> {
                entered.countDown();
                awaitRelease(release);
                cancelledInsideWorker.set(cancel.isCanceled());
                return "discarded";
            }));
            try
            {
                assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
                assertTrue(jobs.running().isEmpty());
                jobs.pause();
                jobs.resume();
                var stopped = response.get(WAIT_MS, TimeUnit.MILLISECONDS);
                assertNull(stopped.response());
                assertNotNull(stopped.jobId());
                assertEquals(stopped.jobId(), jobs.runningForProject("Demo").getId());
                release.countDown();
                assertEquals(Status.CANCELLED, jobs.await(stopped.jobId(), WAIT_MS).getStatus());
                assertTrue(cancelledInsideWorker.get());
            }
            finally
            {
                release.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void stoppedBeforeNativeEntryDoesNotPublishOrRunAfterResume() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        var workerCreated = new CountDownLatch(1);
        var letWorkerStart = new CountDownLatch(1);
        var enteredNative = new AtomicBoolean();
        try (var jobs = new QueryValidationJobs(1, 2, System::nanoTime, runnable -> {
            Thread thread = new Thread(() -> {
                workerCreated.countDown();
                awaitRelease(letWorkerStart);
                runnable.run();
            });
            thread.setDaemon(true);
            return thread;
        }))
        {
            var result = caller.submit(() -> jobs.run("Demo", WAIT_MS, () -> false, cancel -> {
                enteredNative.set(true);
                return "unexpected";
            }));
            try
            {
                assertTrue(workerCreated.await(WAIT_MS, TimeUnit.MILLISECONDS));
                jobs.pause();
                jobs.resume();
                assertNull(result.get(WAIT_MS, TimeUnit.MILLISECONDS).jobId());
                assertTrue(jobs.running().isEmpty());
                letWorkerStart.countDown();
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
                while (true)
                {
                    try
                    {
                        assertEquals("fresh", jobs.run("Demo", WAIT_MS, () -> false, cancel -> "fresh").response());
                        break;
                    }
                    catch (RejectedExecutionException handoff)
                    {
                        assertTrue(System.nanoTime() - deadline < 0);
                        Thread.yield();
                    }
                }
                assertFalse(enteredNative.get());
                assertTrue(jobs.running().isEmpty());
            }
            finally
            {
                letWorkerStart.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void leaseAcquiredAcrossRestartIsReleasedAndNeverAdmitted() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        var oldLeaseReached = new CountDownLatch(1);
        var returnOldLease = new CountDownLatch(1);
        var oldLeaseReleases = new AtomicInteger();
        var enteredNative = new AtomicBoolean();
        try (var jobs = new QueryValidationJobs(1, 2))
        {
            var stale = caller.submit(() -> jobs.run("Demo", WAIT_MS, () -> false, cancel -> {
                enteredNative.set(true);
                return "unexpected";
            }, () -> {
                oldLeaseReached.countDown();
                awaitRelease(returnOldLease);
                return () -> oldLeaseReleases.incrementAndGet();
            }));
            try
            {
                assertTrue(oldLeaseReached.await(WAIT_MS, TimeUnit.MILLISECONDS));
                jobs.pause();
                jobs.resume();
                returnOldLease.countDown();
                var rejected = assertThrows(ExecutionException.class,
                    () -> stale.get(WAIT_MS, TimeUnit.MILLISECONDS));
                assertTrue(rejected.getCause() instanceof RejectedExecutionException);
                assertFalse(enteredNative.get());
                assertEquals("discarded lease must close exactly once", 1, oldLeaseReleases.get());
                assertTrue(jobs.running().isEmpty());
                assertEquals("fresh", jobs.run("Demo", WAIT_MS, () -> false, cancel -> "fresh").response());
            }
            finally
            {
                returnOldLease.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    private static String detachAndFinish(QueryValidationJobs jobs, java.util.concurrent.ExecutorService caller)
        throws Exception
    {
        return detachAndFinish(jobs, caller, new AtomicReference<>());
    }

    private static String detachAndFinish(QueryValidationJobs jobs, java.util.concurrent.ExecutorService caller,
        AtomicReference<Thread> worker) throws Exception
    {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delivered = new AtomicBoolean();
        var result = caller.submit(() -> jobs.run("Demo", WAIT_MS, delivered::get, cancel -> {
            worker.set(Thread.currentThread());
            entered.countDown();
            awaitRelease(release);
            return "late";
        }));
        try
        {
            assertTrue(entered.await(WAIT_MS, TimeUnit.MILLISECONDS));
            delivered.set(true);
            String id = result.get(WAIT_MS, TimeUnit.MILLISECONDS).jobId();
            assertNotNull(id);
            assertEquals(Status.RUNNING, jobs.await(id, 0).getStatus());
            release.countDown();
            assertEquals(Status.CANCELLED, jobs.await(id, WAIT_MS).getStatus());
            return id;
        }
        finally
        {
            release.countDown();
        }
    }

    private static void awaitRelease(CountDownLatch release)
    {
        while (release.getCount() > 0)
        {
            try
            {
                release.await();
            }
            catch (InterruptedException e)
            {
                // Model a native callback that consumes the interruption but does not exit yet.
            }
        }
    }
}
