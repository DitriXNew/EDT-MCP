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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/** Regression tests for Error publication and propagation from background work (#679). */
public class BackgroundJobsErrorTest
{
    private static final long JOB_TIMEOUT_MS = 60_000L;
    private static final long AWAIT_MS = 2_000L;

    @Test
    public void testCommittedAssertionErrorFailsImmediatelyAndIsRethrown() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            AssertionError failure = new AssertionError("boom"); //$NON-NLS-1$
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                assertTrue("the work must cross the commit handshake", progress.tryCommit()); //$NON-NLS-1$
                throw failure;
            });

            assertFailed(jobs, started, "boom"); //$NON-NLS-1$
            assertRethrown(jobs, started, failure);
        }
    }

    @Test
    public void testUncommittedAssertionErrorFailsWithoutWaitingForDeadline()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                throw new AssertionError("boom"); //$NON-NLS-1$
            });

            assertFailed(jobs, started, "boom"); //$NON-NLS-1$
        }
    }

    @Test
    public void testErrorWithNullMessageUsesSimpleClassName()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                throw new Error();
            });

            assertFailed(jobs, started, "Error"); //$NON-NLS-1$
        }
    }

    @Test
    public void testErrorWithBlankMessageUsesSimpleClassName()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                throw new AssertionError(" \t "); //$NON-NLS-1$
            });

            assertFailed(jobs, started, "AssertionError"); //$NON-NLS-1$
        }
    }

    @Test
    public void testErrorFailureReleasesOnlySlotAndWorkerStaysUsable() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(1, 1))
        {
            AssertionError failure = new AssertionError("boom"); //$NON-NLS-1$
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, 1, "start", progress -> { //$NON-NLS-1$
                assertTrue(progress.tryCommit());
                throw failure;
            });

            assertFailed(jobs, started, "boom"); //$NON-NLS-1$
            // Future completion follows the slot-release finally, unlike terminal publication.
            assertRethrown(jobs, started, failure);
            assertFalse("the failed worker must release its claim", //$NON-NLS-1$
                jobs.get(started.getId()).isClaimed());

            JobSnapshot replacement = jobs.start(JOB_TIMEOUT_MS, 1, "replacement", //$NON-NLS-1$
                progress -> "done"); //$NON-NLS-1$
            assertNotNull("the only admission slot must be reusable", replacement); //$NON-NLS-1$
            JobSnapshot completed = jobs.await(replacement.getId(), AWAIT_MS);
            assertEquals(Status.DONE, completed.getStatus());
            assertEquals("done", completed.getResult()); //$NON-NLS-1$
            assertNull(completed.getErrorMessage());
        }
    }

    @Test
    public void testLinkageErrorIsFailedAndRethrown() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            LinkageError failure = new LinkageError("linkage boom"); //$NON-NLS-1$
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                throw failure;
            });

            assertFailed(jobs, started, "linkage boom"); //$NON-NLS-1$
            assertRethrown(jobs, started, failure);
        }
    }

    /** Compatibility control: ordinary exceptions keep their existing failed snapshot. */
    @Test
    public void testExceptionKeepsFailureShape()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(20, 1))
        {
            JobSnapshot started = jobs.start(JOB_TIMEOUT_MS, "start", progress -> { //$NON-NLS-1$
                throw new IllegalStateException("exception boom"); //$NON-NLS-1$
            });

            assertFailed(jobs, started, "exception boom"); //$NON-NLS-1$
        }
    }

    private static void assertFailed(BackgroundJobs jobs, JobSnapshot started, String message)
    {
        JobSnapshot failed = jobs.await(started.getId(), AWAIT_MS);
        assertNotNull(failed);
        assertEquals("the failure must be published before the job deadline", //$NON-NLS-1$
            Status.FAILED, failed.getStatus());
        assertEquals(message, failed.getErrorMessage());
        assertNull(failed.getResult());
        assertNull(failed.getErrorMarker());
        assertTrue("a failed job must have a completion timestamp", failed.getCompletedAtMs() > 0); //$NON-NLS-1$
        assertTrue("the original failure must be retained in progress", //$NON-NLS-1$
            failed.getProgress().stream().anyMatch(entry ->
                ("Failed: " + message).equals(entry.getMessage()))); //$NON-NLS-1$
    }

    private static void assertRethrown(BackgroundJobs jobs, JobSnapshot started, Error failure)
        throws Exception
    {
        // The registry intentionally hides its Future; inspect it only to prove Error propagation
        // and to wait for the callable's finally without adding a production test hook.
        Field recordsField = BackgroundJobs.class.getDeclaredField("jobs"); //$NON-NLS-1$
        recordsField.setAccessible(true);
        Map<?, ?> records = (Map<?, ?>)recordsField.get(jobs);
        Object record = records.get(started.getId());
        Field futureField = record.getClass().getDeclaredField("workFuture"); //$NON-NLS-1$
        futureField.setAccessible(true);
        Future<?> future = (Future<?>)futureField.get(record);
        try
        {
            future.get(AWAIT_MS, TimeUnit.MILLISECONDS);
            fail("the worker Future must retain the rethrown Error"); //$NON-NLS-1$
        }
        catch (ExecutionException e)
        {
            assertSame("the original Error must escape runWork", failure, e.getCause()); //$NON-NLS-1$
        }
    }
}
