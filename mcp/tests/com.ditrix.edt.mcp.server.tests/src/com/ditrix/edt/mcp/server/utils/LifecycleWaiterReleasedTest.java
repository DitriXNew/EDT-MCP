/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;

import com._1c.g5.v8.dt.core.lifecycle.ProjectContext;
import com._1c.g5.v8.dt.core.lifecycle.ProjectStopType;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.lifecycle.ServiceState;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectReleaseWaiter;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;

/** Release events, the real registry predicate, and restart failure interruption. */
public class LifecycleWaiterReleasedTest
{
    private static final String NAME = "Extension";

    private static final class Events
    {
        final IServicesOrchestrator services = mock(IServicesOrchestrator.class);
        final List<String> order = new ArrayList<>();
        IServiceContextLifecycleListener listener;

        Events()
        {
            doAnswer(call -> {
                order.add("add");
                listener = call.getArgument(0);
                return null;
            }).when(services).addListener(any());
        }

        void fire(String name, ProjectStopType stopType, ServiceState state)
        {
            IDtProject project = mock(IDtProject.class);
            when(project.getName()).thenReturn(name);
            ProjectContext context = new ProjectContext(project);
            context.setStopType(stopType);
            listener.contextStateChanged(context, state);
        }

        void removedOnce()
        {
            verify(services, times(1)).removeListener(listener);
        }
    }

    @Test
    public void testAStoppedEventWithStopTypeDeleteReleasesTheWait()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> false, 10);
        // The listener must be live BEFORE await, because delete itself can deliver the event.
        events.fire(NAME, ProjectStopType.DELETE, ServiceState.STOPPED);
        long start = System.nanoTime();
        assertTrue(waiter.await(30_000));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        waiter.cleanup();
        events.removedOnce();
    }

    @Test
    public void testAStoppedEventFromACleanRestartDoesNotCountAsRelease()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> false, 10);
        events.fire(NAME, ProjectStopType.CLEAN, ServiceState.STOPPED);
        events.fire("Other", ProjectStopType.DELETE, ServiceState.STOPPED);
        events.fire(NAME, ProjectStopType.DELETE, ServiceState.STARTED);
        assertFalse(waiter.await(60));
        waiter.cleanup();
        events.removedOnce();
    }

    @Test
    public void testAProjectEdtNeverKnewIsReleasedAtOnceAfterListenerRegistration()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> {
            events.order.add("probe");
            return true;
        }, 30_000);
        long start = System.nanoTime();
        assertTrue(waiter.await(30_000));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
        assertEquals(List.of("add", "probe"), events.order);
        waiter.cleanup();
        events.removedOnce();
    }

    @Test
    public void testThePredicateIsReprobedWhileWaiting()
    {
        Events events = new Events();
        AtomicInteger probes = new AtomicInteger();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME,
            () -> probes.incrementAndGet() >= 3, 10);
        assertTrue(waiter.await(300));
        assertTrue(probes.get() >= 3);
        events.removedOnce();
    }

    @Test
    public void testAThrowingPredicateCannotClaimRelease()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> {
            throw new IllegalStateException("not ready");
        }, 10);
        assertFalse(waiter.await(30));
        events.removedOnce();
    }

    @Test
    public void testTheReleasePredicateRequiresBothNoDtProjectAndAStoppedContext()
    {
        Events events = new Events();
        IProject project = mock(IProject.class);
        IDtProjectManager manager = mock(IDtProjectManager.class);
        IWorkspaceOrchestrator workspace = mock(IWorkspaceOrchestrator.class);
        when(manager.getDtProject(project)).thenReturn(mock(IDtProject.class));
        when(workspace.isStarted(project)).thenReturn(false);
        assertFalse(LifecycleWaiter.prepareForRelease(project, NAME, events.services, manager, workspace).await(0));
        when(manager.getDtProject(project)).thenReturn(null);
        when(workspace.isStarted(project)).thenReturn(true);
        assertFalse(LifecycleWaiter.prepareForRelease(project, NAME, events.services, manager, workspace).await(0));
        when(workspace.isStarted(project)).thenReturn(false);
        assertTrue(LifecycleWaiter.prepareForRelease(project, NAME, events.services, manager, workspace).await(0));
    }

    @Test
    public void testUnavailableServicesCannotVerifyRelease()
    {
        Events events = new Events();
        IProject project = mock(IProject.class);
        IDtProjectManager manager = mock(IDtProjectManager.class);
        IWorkspaceOrchestrator workspace = mock(IWorkspaceOrchestrator.class);
        assertNull(LifecycleWaiter.prepareForRelease(project, NAME, null, manager, workspace));
        assertNull(LifecycleWaiter.prepareForRelease(project, NAME, events.services, null, workspace));
        assertNull(LifecycleWaiter.prepareForRelease(project, NAME, events.services, manager, null));
        verify(events.services, never()).addListener(any());
    }

    @Test
    public void testAbandoningTheWaitAndCleaningUpTwiceRemovesTheListenerOnce()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> false, 10);
        waiter.cleanup();
        waiter.cleanup();
        events.removedOnce();
    }

    @Test
    public void testInterruptedReleaseWaitCleansUpAndPreservesInterruption()
    {
        Events events = new Events();
        ProjectReleaseWaiter waiter = new ProjectReleaseWaiter(events.services, NAME, () -> false, 10);
        Thread.currentThread().interrupt();
        try
        {
            assertFalse(waiter.await(300));
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally
        {
            Thread.interrupted();
            waiter.cleanup();
        }
        events.removedOnce();
    }

    @Test
    public void testAFailedRestartJobEndsTheWaitInsteadOfSpendingTheBudget() throws Exception
    {
        Events events = new Events();
        ProjectRestartWaiter waiter = new ProjectRestartWaiter(events.services, NAME);
        CountDownLatch run = new CountDownLatch(1);
        Job job = new Job("marker restart")
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    if (!run.await(5, TimeUnit.SECONDS))
                    {
                        return Status.CANCEL_STATUS;
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
                return new Status(IStatus.ERROR, "test", "restart failed");
            }
        };
        try (RestartJobFailureSignal failure = new RestartJobFailureSignal(Job.getJobManager(), job::equals))
        {
            events.fire(NAME, ProjectStopType.CLEAN, ServiceState.STOPPED);
            job.schedule(100);
            run.countDown();
            long start = System.nanoTime();
            assertFalse(waiter.await(60_000, failure));
            assertTrue("failed restarts must return in under 5 s",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 5_000);
            assertTrue(failure.hasFailed());
        }
        finally
        {
            run.countDown();
            job.join();
            waiter.cleanup();
        }
        events.removedOnce();
    }

    @Test
    public void testAStartedRestartWinsOverAForeignFailureSignal() throws Exception
    {
        Events events = new Events();
        ProjectRestartWaiter waiter = new ProjectRestartWaiter(events.services, NAME);
        RestartJobFailureSignal failure = mock(RestartJobFailureSignal.class);
        when(failure.hasFailed()).thenReturn(true);
        events.fire(NAME, ProjectStopType.CLEAN, ServiceState.STOPPED);
        events.fire(NAME, ProjectStopType.CLEAN, ServiceState.STARTED);
        assertTrue(waiter.await(0, failure));
        events.removedOnce();
    }
}
