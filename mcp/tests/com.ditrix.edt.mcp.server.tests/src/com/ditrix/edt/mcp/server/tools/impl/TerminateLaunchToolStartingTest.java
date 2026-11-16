/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IProcess;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.StartingLaunches;

/** terminate_launch over a launch that has no 1C client yet. */
public class TerminateLaunchToolStartingTest
{
    private static ILaunch emptyLaunch(ILaunchConfiguration config)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[0]);
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        return launch;
    }

    private static Job loadingJob(ILaunchConfiguration config, ILaunch launch, CountDownLatch running)
    {
        Job job = new Job("Launching test")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                running.countDown();
                try
                {
                    while (!monitor.isCanceled())
                    {
                        Thread.sleep(10);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                finally
                {
                    StartingLaunches.markDelegateReturned(launch);
                }
                return Status.CANCEL_STATUS;
            }

            @Override
            public boolean belongsTo(Object family)
            {
                return config.equals(family);
            }
        };
        job.schedule();
        return job;
    }

    /** Without force a start in progress is refused and keeps running; with force it is cancelled and removed. */
    @Test
    public void aStartInProgressIsAbortedOnlyWithForce() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getName()).thenReturn("IRP thin");
        ILaunch launch = emptyLaunch(config);
        CountDownLatch running = new CountDownLatch(1);
        Job job = loadingJob(config, launch, running);
        assertTrue(running.await(5, TimeUnit.SECONDS));
        try
        {
            TerminateLaunchTool.TerminationResult refused = new TerminateLaunchTool.TerminationResult(launch);
            assertTrue(TerminateLaunchTool.endEmptyLaunch(launch, 5, false, refused));
            assertEquals("still_starting", refused.code);
            assertTrue(refused.note, refused.note.contains("force=true") && refused.note.contains("state: starting"));
            assertTrue("a refusal must leave the start running", StartingLaunches.startJobActive(launch));

            TerminateLaunchTool.TerminationResult forced = new TerminateLaunchTool.TerminationResult(launch);
            assertTrue(TerminateLaunchTool.endEmptyLaunch(launch, 5, true, forced));
            assertEquals("terminated", forced.code);
            assertEquals(TerminateLaunchTool.START_CANCELLED_NOTE, forced.note);
            assertFalse(StartingLaunches.startJobActive(launch));
        }
        finally
        {
            job.cancel();
            job.join();
        }
        verify(launch, never()).terminate();
    }

    /** A launch whose start ended without a client is removed; Eclipse would never report it terminated. */
    @Test
    public void aLaunchWhoseStartEndedIsRemoved()
    {
        ILaunch launch = emptyLaunch(mock(ILaunchConfiguration.class));
        StartingLaunches.markDelegateReturned(launch);
        TerminateLaunchTool.TerminationResult result = new TerminateLaunchTool.TerminationResult(launch);
        assertTrue(TerminateLaunchTool.endEmptyLaunch(launch, 5, false, result));
        assertEquals("terminated", result.code);
        assertEquals(TerminateLaunchTool.ABANDONED_REMOVED_NOTE, result.note);
    }

    /** A launch with a client, or an empty one started from EDT's UI, takes the ordinary terminate path. */
    @Test
    public void otherLaunchesAreNotHandledHere()
    {
        ILaunch foreign = emptyLaunch(mock(ILaunchConfiguration.class));
        assertFalse(TerminateLaunchTool.endEmptyLaunch(foreign, 5, true,
            new TerminateLaunchTool.TerminationResult(foreign)));
        ILaunch running = mock(ILaunch.class);
        when(running.getProcesses()).thenReturn(new IProcess[] {mock(IProcess.class)});
        when(running.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        assertFalse(TerminateLaunchTool.endEmptyLaunch(running, 5, true,
            new TerminateLaunchTool.TerminationResult(running)));
    }
}
