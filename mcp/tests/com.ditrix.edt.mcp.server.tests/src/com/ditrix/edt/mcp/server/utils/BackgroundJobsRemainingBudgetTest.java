/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/**
 * Tests for {@link BackgroundJobs.ProgressReporter#remainingMillis()} - the budget a job's work
 * must bound ITSELF by.
 *
 * <p>The registry measures {@code timeoutSeconds} from SUBMISSION and its worker pool can queue
 * the work first. Work that restarts the caller's timeout when it happens to begin would grant
 * itself the queue delay on top of the total the caller was promised - and for ask_workmate that
 * surplus buys further conversation turns with tool side effects (review of #440).
 */
public class BackgroundJobsRemainingBudgetTest
{
    @Test
    public void testRemainingBudgetIsBelowTheTotalAndCountsFromSubmission() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 2))
        {
            AtomicLong seen = new AtomicLong(-1);
            JobSnapshot started = jobs.start(TimeUnit.SECONDS.toMillis(30), "accepted", progress -> {
                Thread.sleep(60);
                seen.set(progress.remainingMillis());
                return "done";
            });
            assertTrue("the job must start", started != null);
            jobs.await(started.getId(), 5_000L);
            long remaining = seen.get();
            assertTrue("the work is told what is LEFT, not the whole budget: " + remaining,
                remaining > 0 && remaining < TimeUnit.SECONDS.toMillis(30));
            assertTrue("the time already spent is deducted: " + remaining,
                remaining <= TimeUnit.SECONDS.toMillis(30) - 50);
        }
    }

    @Test
    public void testTheDefaultReporterIsUnbounded()
    {
        // A reporter that is not registry-backed (a test seam, a direct caller) must not make
        // work believe its budget is exhausted.
        BackgroundJobs.ProgressReporter plain = message -> {
            // no-op
        };
        assertEquals(Long.MAX_VALUE, plain.remainingMillis());
    }
}
