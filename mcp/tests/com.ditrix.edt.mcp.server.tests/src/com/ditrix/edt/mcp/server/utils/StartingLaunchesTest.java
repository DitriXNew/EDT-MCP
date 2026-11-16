/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

import com.ditrix.edt.mcp.server.utils.StartingLaunches.CancelOutcome;
import com.ditrix.edt.mcp.server.utils.StartingLaunches.Phase;

/** Tests for {@link StartingLaunches}: the phase of a launch with no client yet, and cancelling its start. */
public class StartingLaunchesTest
{
    private static ILaunch emptyLaunch(ILaunchConfiguration config)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[0]);
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        return launch;
    }

    /** A job of the configuration's family that runs until cancelled, as the launch job does during a load. */
    private static Job startJob(ILaunchConfiguration config, CountDownLatch running, ILaunch delegateLaunch)
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
                    StartingLaunches.markDelegateReturned(delegateLaunch);
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

    @Test
    public void aLaunchWithAClientIsRunning()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.getProcesses()).thenReturn(new IProcess[] {mock(IProcess.class)});
        when(launch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        assertEquals(Phase.RUNNING, StartingLaunches.phase(launch, l -> true));
        assertNull(StartingLaunches.note(Phase.RUNNING));
    }

    @Test
    public void anEmptyLaunchIsStartingOrSynchronizingUntilItsDelegateReturns()
    {
        ILaunch launch = emptyLaunch(mock(ILaunchConfiguration.class));
        assertEquals(Phase.STARTING, StartingLaunches.phase(launch, l -> false));
        assertEquals(Phase.SYNCHRONIZING, StartingLaunches.phase(launch, l -> true));
        assertTrue(Phase.SYNCHRONIZING.starting());
        assertEquals("synchronizing", Phase.SYNCHRONIZING.token());
        String note = StartingLaunches.note(Phase.SYNCHRONIZING);
        assertTrue(note, note.contains("Do not terminate_launch") && note.contains("restartIfRunning"));

        StartingLaunches.markDelegateReturned(launch);
        assertEquals(Phase.ABANDONED, StartingLaunches.phase(launch, l -> true));
        assertFalse(Phase.ABANDONED.starting());
        assertNotNull(StartingLaunches.note(Phase.ABANDONED));
    }

    @Test
    public void aRunningStartJobKeepsTheLaunchStartingAndIsCancelledOnRequest() throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        ILaunch launch = emptyLaunch(config);
        CountDownLatch running = new CountDownLatch(1);
        Job job = startJob(config, running, launch);
        assertTrue(running.await(5, TimeUnit.SECONDS));
        assertTrue(StartingLaunches.startJobActive(launch));
        assertEquals(Phase.STARTING, StartingLaunches.phase(launch, l -> false));

        assertEquals(CancelOutcome.REMOVED, StartingLaunches.cancel(launch, null, 5000));
        assertTrue(job.join(5000, null));
        assertFalse(StartingLaunches.startJobActive(launch));
        verify(launch, never()).terminate();
    }

    @Test
    public void anEmptyLaunchNotStartedByThisPluginIsLeftAlone() throws Exception
    {
        ILaunch foreign = emptyLaunch(mock(ILaunchConfiguration.class));
        assertFalse(StartingLaunches.startJobActive(foreign));
        assertEquals(CancelOutcome.FOREIGN, StartingLaunches.cancel(foreign, null, 10));
        verify(foreign, never()).terminate();
    }
}
