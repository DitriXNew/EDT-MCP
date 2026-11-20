/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/** {@link BackgroundJobs#running()}: the listing {@code get_server_status.runningJobs} reads (#993). */
public class BackgroundJobsRunningTest
{
    private static final long WAIT_MS = 10_000L;

    @Test
    public void testOnlyRunningJobsAreListedOldestFirst() throws Exception
    {
        try (BackgroundJobs jobs = new BackgroundJobs(10, 3))
        {
            CountDownLatch release = new CountDownLatch(1);
            JobSnapshot finished = jobs.start("finished_tool", 60_000L, "start", progress -> "done"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertEquals(Status.DONE, jobs.await(finished.getId(), WAIT_MS).getStatus());
            JobSnapshot older = jobs.start("older_tool", 60_000L, "start", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return "done"; //$NON-NLS-1$
            });
            JobSnapshot newer = jobs.start("newer_tool", 60_000L, "start", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
                release.await(WAIT_MS, TimeUnit.MILLISECONDS);
                return "done"; //$NON-NLS-1$
            });
            try
            {
                List<JobSnapshot> running = jobs.running();
                assertEquals("a finished job is not running; the two blocked ones are", 2, running.size());
                assertEquals(older.getId(), running.get(0).getId());
                assertEquals(newer.getId(), running.get(1).getId());
                assertEquals("older_tool", running.get(0).getOwningTool()); //$NON-NLS-1$
                assertTrue(running.stream().allMatch(s -> s.getStatus() == Status.RUNNING));
            }
            finally
            {
                release.countDown();
            }
            jobs.await(older.getId(), WAIT_MS);
            jobs.await(newer.getId(), WAIT_MS);
            assertTrue("nothing runs once every job has finished", jobs.running().isEmpty());
        }
    }

    @Test
    public void testAnEmptyRegistryListsNothing()
    {
        try (BackgroundJobs jobs = new BackgroundJobs(10, 1))
        {
            assertTrue(jobs.running().isEmpty());
        }
    }
}
