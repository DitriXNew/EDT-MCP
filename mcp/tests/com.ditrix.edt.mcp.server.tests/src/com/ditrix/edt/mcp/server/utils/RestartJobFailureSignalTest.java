/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com._1c.g5.v8.dt.core.lifecycle.ProjectRestartJob;

public class RestartJobFailureSignalTest
{
    private static Job job(IStatus status)
    {
        return new Job("marker")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return status;
            }
        };
    }

    @Test
    public void testADoneRestartJobWithAnErrorStatusRaisesTheSignalWithItsCause() throws Exception
    {
        Job job = job(new Status(IStatus.ERROR, "test", "restart failed",
            new IllegalStateException("INITIALIZATION: Extension", new IllegalArgumentException("PROJECT.PMF missing"))));
        try (RestartJobFailureSignal failure = new RestartJobFailureSignal(Job.getJobManager(), job::equals))
        {
            job.schedule();
            assertTrue(failure.await(5_000));
            job.join();
            assertTrue(failure.hasFailed());
            assertTrue(failure.getFailure().contains("restart failed"));
            assertTrue(failure.getFailure().contains("INITIALIZATION: Extension"));
            assertTrue(failure.getFailure().contains("PROJECT.PMF missing"));
        }
    }

    @Test
    public void testOkAndUnrelatedFailedJobsDoNotRaiseTheSignal() throws Exception
    {
        Job ok = job(Status.OK_STATUS);
        Job unrelated = job(new Status(IStatus.ERROR, "test", "unrelated failure"));
        try (RestartJobFailureSignal failure = new RestartJobFailureSignal(Job.getJobManager(), ok::equals))
        {
            ok.schedule();
            unrelated.schedule();
            ok.join();
            unrelated.join();
            assertFalse(failure.hasFailed());
            assertNull(failure.getFailure());
        }
    }

    @Test
    public void testTheFirstFailureWinsAndCleanupUnregistersExactlyOnce()
    {
        IJobManager manager = mock(IJobManager.class);
        RestartJobFailureSignal failure = new RestartJobFailureSignal(manager, job -> true);
        ArgumentCaptor<IJobChangeListener> listener = ArgumentCaptor.forClass(IJobChangeListener.class);
        verify(manager).addJobChangeListener(listener.capture());
        IJobChangeEvent event = mock(IJobChangeEvent.class);
        when(event.getResult()).thenReturn(new Status(IStatus.ERROR, "test", "first"),
            new Status(IStatus.CANCEL, "test", "second"));
        listener.getValue().done(event);
        listener.getValue().done(event);
        assertEquals("first", failure.getFailure());
        failure.close();
        failure.close();
        verify(manager, times(1)).removeJobChangeListener(listener.getValue());
    }

    @Test
    public void testProductionClassificationUsesTheRestartJobClass()
    {
        assertTrue(RestartJobFailureSignal.isRestartJob(mock(ProjectRestartJob.class)));
        assertFalse(RestartJobFailureSignal.isRestartJob(job(Status.OK_STATUS)));
    }
}
