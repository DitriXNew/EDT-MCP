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

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;
import com.ditrix.edt.mcp.server.utils.QueryValidationJobs;

public class ValidateQueryDeadlineTest
{
    private static final long GUARD_MS = 5_000L;
    private static final String ORIGINAL = "{\"success\":true,\"validationState\":\"VALID\"}";

    @Test
    public void ordinaryJobsCannotBlockFastValidationsAndFastValidationsDoNotEvictThem() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(2, 1);
            QueryValidationJobs validations = new QueryValidationJobs(1, 2))
        {
            var prior = jobs.start("retained", GUARD_MS, "start", progress -> "historical result");
            assertEquals(Status.DONE, jobs.await(prior.getId(), GUARD_MS).getStatus());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var busy = jobs.start("occupied", GUARD_MS, "start", progress -> {
                entered.countDown();
                release.await();
                return "finished";
            });
            try
            {
                assertTrue(entered.await(GUARD_MS, TimeUnit.MILLISECONDS));
                for (int i = 0; i < 100; i++)
                {
                    assertEquals(ORIGINAL, ValidateQueryTool.validateWithDeadline(validations, GUARD_MS,
                        "Demo", () -> false, cancel -> ORIGINAL));
                }
                assertEquals("historical result", jobs.get(prior.getId()).getResult());
                assertEquals(Status.RUNNING, jobs.get(busy.getId()).getStatus());
                assertTrue(validations.running().isEmpty());
            }
            finally
            {
                release.countDown();
            }
        }
    }

    @Test
    public void detachedValidationIsCancellableAndDoesNotStarveOrdinaryJobs() throws Exception
    {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var signalled = new CountDownLatch(1);
        var stickySignal = new AtomicBoolean();
        var caller = Executors.newSingleThreadExecutor();
        try (BackgroundJobs ordinary = new BackgroundJobs(2, 1);
            QueryValidationJobs validations = new QueryValidationJobs(1, 2))
        {
            try
            {
                var response = caller.submit(() -> ValidateQueryTool.validateWithDeadline(validations,
                    1500L, "Demo", () -> false, cancel -> {
                        entered.countDown();
                        try
                        {
                            release.await();
                        }
                        catch (InterruptedException e)
                        {
                            stickySignal.set(cancel.isCanceled());
                            signalled.countDown();
                            awaitCleanup(release);
                        }
                        return ORIGINAL;
                    }));
                assertTrue(entered.await(GUARD_MS, TimeUnit.MILLISECONDS));
                assertTrue("fast attached work is not a polling job", validations.running().isEmpty());
                String error = response.get(GUARD_MS, TimeUnit.MILLISECONDS);
                assertTrue(error.contains("may still hold a read lock"));
                assertTrue(signalled.await(GUARD_MS, TimeUnit.MILLISECONDS));
                assertTrue(stickySignal.get());
                var pending = validations.running().get(0);
                String id = pending.getId();
                assertTrue(error.contains(id));
                assertNull(ordinary.get(id));
                assertNull(pending.getResult());
                assertTrue(ValidateQueryTool.activeValidationHint(validations, "Demo").contains(id));
                assertEquals("", ValidateQueryTool.activeValidationHint(validations, "Other"));
                assertTrue(new GetJobStatusTool(ordinary, validations).execute(
                    Map.of("jobId", id, "waitSeconds", "0")).contains("| status | running |"));
                assertTrue(new CancelJobTool(ordinary, validations).execute(
                    Map.of("jobId", id)).contains("preview"));
                assertTrue(new CancelJobTool(ordinary, validations).execute(
                    Map.of("jobId", id, "confirm", "true")).contains("worker has not exited"));
                assertTrue(ValidateQueryTool.validateWithDeadline(validations, GUARD_MS, "Demo", () -> false,
                    ignored -> { fail("No slot may be released until native exit"); return null; })
                    .contains("No query validation slot is available"));

                var next = ordinary.start("unrelated", GUARD_MS, "start", progress -> "independent");
                assertEquals("independent", ordinary.await(next.getId(), GUARD_MS).getResult());
                release.countDown();
                var terminal = validations.await(id, GUARD_MS);
                assertEquals(id, terminal.getId());
                assertEquals(Status.CANCELLED, terminal.getStatus());
                assertNull(terminal.getResult());
                assertTrue(validations.running().isEmpty());
                assertEquals("", ValidateQueryTool.activeValidationHint(validations, "Demo"));
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
                // Deliberately model cancellation-unaware platform work.
            }
        }
    }
}
