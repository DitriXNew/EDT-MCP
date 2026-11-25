/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.progress.IProgressConstants;
import org.junit.Test;

/**
 * Tests for {@link McpJobs}: a job this server schedules on an MCP caller's behalf must never
 * raise Eclipse's modal "job failed" dialog in the workbench of the human sitting in front of EDT.
 */
public class McpJobsTest
{
    @Test public void scheduledFollowUpKeepsTheDrainOpen() throws Exception
    {
        var field = com.ditrix.edt.mcp.server.Activator.class.getDeclaredField("plugin");
        field.setAccessible(true); var previous = field.get(null);
        var lifetime = new com.ditrix.edt.mcp.server.HeadlessLifetime(java.time.Duration.ZERO, () -> { });
        var activator = org.mockito.Mockito.mock(com.ditrix.edt.mcp.server.Activator.class);
        org.mockito.Mockito.when(activator.getHeadlessLifetime()).thenReturn(lifetime);
        var running = lifetime.tool();
        var drained = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Job job = new Job("Drain follow-up") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                entered.countDown();
                try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return Status.OK_STATUS;
            }
        };
        try {
            field.set(null, activator);
            lifetime.requestShutdown(drained::countDown);
            McpJobs.schedule(job);
            org.junit.Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            running.close();
            assertEquals(1, drained.getCount());
            release.countDown(); job.join();
            org.junit.Assert.assertTrue(drained.await(5, java.util.concurrent.TimeUnit.SECONDS));
            org.junit.Assert.assertThrows(IllegalStateException.class, () -> McpJobs.schedule(failingJob()));
        }
        finally { release.countDown(); job.join(); running.close(); lifetime.close(); field.set(null, previous); }
    }

    private static Job failingJob()
    {
        return new Job("test") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return new Status(IStatus.ERROR, "com.ditrix.edt.mcp.server", "boom");
            }
        };
    }

    @Test
    public void testMarkedJobReportsItsErrorInTheProgressViewInsteadOfADialog()
    {
        Job job = failingJob();
        assertEquals(Boolean.TRUE,
            McpJobs.markNoErrorDialog(job).getProperty(
                IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY));
    }

    @Test
    public void testMarkReturnsTheSameJobSoItCanBeChained()
    {
        Job job = failingJob();
        assertSame(job, McpJobs.markNoErrorDialog(job));
    }

    @Test
    public void testAnUnmarkedJobIsTheOneEclipseWouldPromptFor()
    {
        // Pins WHY the helper exists: without the property Eclipse's progress manager hands an
        // ERROR result to the status manager with SHOW, which opens the modal error dialog.
        assertEquals(null,
            failingJob().getProperty(IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY));
    }
}
