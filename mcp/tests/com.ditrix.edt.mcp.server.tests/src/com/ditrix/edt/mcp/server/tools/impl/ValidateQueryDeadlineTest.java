/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;
import com.google.gson.JsonParser;

public class ValidateQueryDeadlineTest
{
    private static final long GUARD_MS = 5_000L;

    @Test
    public void normalValidationReturnsOriginalPayloadAndDoesNotPublishItInTheJob()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            String original = "{\"success\":true,\"validationState\":\"VALID\"}"; //$NON-NLS-1$
            var jobId = new AtomicReference<String>();
            assertEquals(original, ValidateQueryTool.validateWithDeadline(jobs, GUARD_MS, "Demo", cancel -> {
                assertFalse(cancel.isCanceled());
                jobId.set(jobs.running().get(0).getId());
                return original;
            }));
            assertTrue(jobs.running().isEmpty());
            var finished = jobs.get(jobId.get());
            assertEquals(Status.DONE, finished.getStatus());
            assertNull(finished.getResult());
        }
    }

    @Test
    public void timeoutIsAnErrorAndDoesNotPretendTheNativeCallbackHasExited() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        CountDownLatch signalled = new CountDownLatch(1);
        AtomicBoolean cooperativeSignal = new AtomicBoolean();
        var caller = Executors.newSingleThreadExecutor();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 2))
        {
            try
            {
                var response = caller.submit(() -> ValidateQueryTool.validateWithDeadline(jobs, 1_500L, "Demo",
                    cancel -> {
                        entered.countDown();
                        try
                        {
                            allowCleanup.await();
                        }
                        catch (InterruptedException e)
                        {
                            // A native call can consume the interrupt and still be unwinding.
                            cooperativeSignal.set(cancel.isCanceled());
                            signalled.countDown();
                            awaitCleanup(allowCleanup);
                        }
                        return "{\"success\":true}"; //$NON-NLS-1$
                    }));
                assertTrue(entered.await(GUARD_MS, TimeUnit.MILLISECONDS));
                var body = JsonParser.parseString(response.get(GUARD_MS, TimeUnit.MILLISECONDS)).getAsJsonObject();
                assertFalse(body.get("success").getAsBoolean());
                assertTrue(body.get("error").getAsString().contains("may still hold a read lock"));
                assertTrue(signalled.await(GUARD_MS, TimeUnit.MILLISECONDS));
                assertTrue("the cancellation token persists even after an interrupt was consumed",
                    cooperativeSignal.get());
                assertEquals(1, jobs.running().size());
                var pending = jobs.running().get(0);
                assertEquals(ValidateQueryTool.NAME, pending.getOwningTool());
                assertTrue(body.get("error").getAsString().contains(pending.getId()));
                assertNull(pending.getResult());
                assertTrue(ValidateQueryTool.activeValidationHint(jobs, "Demo").contains(pending.getId()));
                assertEquals("", ValidateQueryTool.activeValidationHint(jobs, "OtherProject"));

                String cancelReply = new CancelJobTool(jobs).execute(
                    Map.of("jobId", pending.getId(), "confirm", "true"));
                assertTrue(cancelReply.contains("worker has not exited"));
                String refused = ValidateQueryTool.validateWithDeadline(jobs, GUARD_MS, "Demo",
                    ignored -> failIfRun());
                assertTrue(refused.contains("No query validation slot is available"));

                allowCleanup.countDown();
                var terminal = jobs.await(pending.getId(), GUARD_MS);
                assertEquals(Status.CANCELLED, terminal.getStatus());
                assertNull(terminal.getResult());
                assertTrue(jobs.running().isEmpty());
                assertEquals("", ValidateQueryTool.activeValidationHint(jobs, "Demo"));
            }
            finally
            {
                allowCleanup.countDown();
            }
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    @Test
    public void cooperativeWorkerCanReturnAfterExplicitJobCancellation() throws Exception
    {
        var caller = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        var validationThread = new AtomicReference<Thread>();
        try (BackgroundJobs jobs = new BackgroundJobs(10, 1))
        {
            var response = caller.submit(() -> ValidateQueryTool.validateWithDeadline(jobs, GUARD_MS, "Demo",
                cancel -> {
                    validationThread.set(Thread.currentThread());
                    entered.countDown();
                    while (!cancel.isCanceled())
                    {
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    }
                    return "{\"success\":true}"; //$NON-NLS-1$
                }));
            assertTrue(entered.await(GUARD_MS, TimeUnit.MILLISECONDS));
            var current = jobs.running().get(0);
            // Queue unrelated work on the same single-threaded pool before cancellation.
            // The next task must not inherit the previous job's interrupt or cancellation token.
            CountDownLatch nextEntered = new CountDownLatch(1);
            CountDownLatch releaseNext = new CountDownLatch(1);
            var after = jobs.start("next_tool", GUARD_MS, "Next", progress -> {
                assertSame(validationThread.get(), Thread.currentThread());
                assertFalse(Thread.currentThread().isInterrupted());
                nextEntered.countDown();
                assertTrue(releaseNext.await(GUARD_MS, TimeUnit.MILLISECONDS));
                assertFalse(progress.isCancellationRequested());
                return "finished";
            });
            try
            {
                jobs.cancel(current.getId());
                assertTrue(response.get(GUARD_MS, TimeUnit.MILLISECONDS).contains("\"success\":false"));
                assertEquals(Status.CANCELLED, jobs.await(current.getId(), GUARD_MS).getStatus());
                assertTrue(nextEntered.await(GUARD_MS, TimeUnit.MILLISECONDS));
                // Replay the cancellation while we know the different task owns the same thread.
                assertEquals(BackgroundJobs.CancellationOutcome.ALREADY_TERMINAL,
                    jobs.cancel(current.getId()).getOutcome());
            }
            finally
            {
                releaseNext.countDown();
            }
            assertEquals(Status.DONE, jobs.await(after.getId(), GUARD_MS).getStatus());
        }
        finally
        {
            caller.shutdownNow();
        }
    }

    private static String failIfRun()
    {
        fail("Admission must not start a second worker while the first still owns the slot");
        return null;
    }

    private static void awaitCleanup(CountDownLatch release)
    {
        while (release.getCount() > 0)
        {
            try
            {
                release.await();
            }
            catch (InterruptedException e)
            {
                // Deliberately model a cancellation-unaware platform callback.
            }
        }
    }
}
