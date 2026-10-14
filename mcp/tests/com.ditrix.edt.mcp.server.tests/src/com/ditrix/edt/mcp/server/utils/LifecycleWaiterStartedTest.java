/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.eclipse.core.runtime.IProgressMonitor;
import org.junit.Test;

import com._1c.g5.v8.dt.core.lifecycle.ProjectContext;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.lifecycle.ILifecycleContext;
import com._1c.g5.v8.dt.lifecycle.IServiceContextLifecycleListener;
import com._1c.g5.v8.dt.lifecycle.IServicesOrchestrator;
import com._1c.g5.v8.dt.lifecycle.ServiceState;

/**
 * Pins {@link LifecycleWaiter#waitForProjectStarted}: EDT starts a new project's context INSIDE its
 * project manager's {@code create()}, so the STARTED event has already fired when the caller begins to
 * wait. A waiter that only listens therefore waited out its whole timeout on every create - 60 s, and
 * then reported the project as merely 'created' (issue #706). The waiter now registers its listener and
 * THEN asks EDT whether the project is already started, and re-asks while it waits.
 */
public class LifecycleWaiterStartedTest
{
    private static final String PROJECT = "Demo"; //$NON-NLS-1$

    /** Records the order of what reached it and hands out the registered listener. */
    private static final class RecordingOrchestrator implements IServicesOrchestrator
    {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger removed = new AtomicInteger();
        final CountDownLatch registered = new CountDownLatch(1);
        volatile IServiceContextLifecycleListener listener;

        @Override
        public void addListener(IServiceContextLifecycleListener added)
        {
            events.add("add"); //$NON-NLS-1$
            listener = added;
            registered.countDown();
        }

        @Override
        public void removeListener(IServiceContextLifecycleListener gone)
        {
            events.add("remove"); //$NON-NLS-1$
            removed.incrementAndGet();
        }

        @Override
        public void startServices(ILifecycleContext context, IProgressMonitor monitor)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stopServices(ILifecycleContext context, IProgressMonitor monitor)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isInfrastructureReady()
        {
            return true;
        }

        /** Fires a lifecycle event for a project named {@code name}, as EDT's orchestrator would. */
        void fire(String name, ServiceState state)
        {
            IDtProject project = mock(IDtProject.class);
            when(project.getName()).thenReturn(name);
            listener.contextStateChanged(new ProjectContext(project), state);
        }
    }

    /** A probe that records each time it was asked, into the orchestrator's event list. */
    private static BooleanSupplier probe(RecordingOrchestrator orchestrator, boolean answer)
    {
        return () -> {
            orchestrator.events.add("probe"); //$NON-NLS-1$
            return answer;
        };
    }

    // ==================== U10, U11: already started ====================

    @Test
    public void testAProjectAlreadyStartedIsReportedAtOnceWithoutAnyEvent()
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        long start = System.nanoTime();

        // The re-probe interval is the whole timeout: only the FIRST question can answer quickly.
        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, probe(orchestrator, true), PROJECT,
            30_000L, 30_000L);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(started);
        assertTrue("an already started project must not wait for an event, took " + elapsedMs + " ms", //$NON-NLS-1$ //$NON-NLS-2$
            elapsedMs < 1_000L);
        assertEquals(1, orchestrator.removed.get());
    }

    @Test
    public void testTheStateIsAskedOnlyAfterTheListenerIsRegistered()
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();

        LifecycleWaiter.waitForProjectStarted(orchestrator, probe(orchestrator, true), PROJECT, 30_000L, 1_000L);

        // Asked first, a start completing between the question and the registration would be seen by
        // neither - the very window that cost every create its full timeout.
        assertEquals("add", orchestrator.events.get(0)); //$NON-NLS-1$
        assertEquals("probe", orchestrator.events.get(1)); //$NON-NLS-1$
        assertEquals("remove", orchestrator.events.get(orchestrator.events.size() - 1)); //$NON-NLS-1$
    }

    // ==================== U12: not yet started, then the event ====================

    @Test
    public void testAProjectThatStartsLaterIsReportedOnItsEvent() throws InterruptedException
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        Thread starter = new Thread(() -> {
            try
            {
                if (orchestrator.registered.await(5, TimeUnit.SECONDS))
                {
                    Thread.sleep(100L);
                    orchestrator.fire("Other", ServiceState.STARTED); //$NON-NLS-1$
                    orchestrator.fire(PROJECT, ServiceState.STARTED);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        starter.start();
        long start = System.nanoTime();

        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, probe(orchestrator, false), PROJECT,
            30_000L, 60_000L);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        starter.join(5_000L);
        assertTrue(started);
        assertTrue("the event must end the wait, took " + elapsedMs + " ms", elapsedMs < 10_000L); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, orchestrator.removed.get());
    }

    @Test
    public void testAnotherProjectsEventDoesNotEndTheWait()
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        Thread starter = new Thread(() -> {
            try
            {
                if (orchestrator.registered.await(5, TimeUnit.SECONDS))
                {
                    orchestrator.fire("Other", ServiceState.STARTED); //$NON-NLS-1$
                    orchestrator.fire(PROJECT, ServiceState.STOPPED);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        starter.start();

        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, probe(orchestrator, false), PROJECT,
            400L, 50L);

        assertFalse(started);
    }

    // ==================== U13: never started ====================

    @Test
    public void testAProjectThatNeverStartsTimesOutAndTheListenerIsRemoved()
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        long start = System.nanoTime();

        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, probe(orchestrator, false), PROJECT,
            300L, 50L);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertFalse(started);
        assertTrue("the wait must last its timeout, took " + elapsedMs + " ms", elapsedMs >= 250L); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("and no more than a little past it, took " + elapsedMs + " ms", elapsedMs < 5_000L); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, orchestrator.removed.get());
    }

    // ==================== the re-probe ====================

    @Test
    public void testAStartWhoseEventWasMissedIsSeenByTheReprobe()
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        AtomicInteger asked = new AtomicInteger();
        // Not started at the first question, started from the third on - and no event ever fires.
        BooleanSupplier laterStarted = () -> asked.incrementAndGet() >= 3;
        long start = System.nanoTime();

        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, laterStarted, PROJECT, 30_000L, 50L);

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(started);
        assertTrue("re-asked well before the timeout, took " + elapsedMs + " ms", elapsedMs < 10_000L); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(asked.get() >= 3);
        assertEquals(1, orchestrator.removed.get());
    }

    @Test
    public void testAProbeThatThrowsCountsAsNotStartedAndTheEventStillEndsTheWait() throws InterruptedException
    {
        RecordingOrchestrator orchestrator = new RecordingOrchestrator();
        Thread starter = new Thread(() -> {
            try
            {
                if (orchestrator.registered.await(5, TimeUnit.SECONDS))
                {
                    Thread.sleep(100L);
                    orchestrator.fire(PROJECT, ServiceState.STARTED);
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        starter.start();

        boolean started = LifecycleWaiter.waitForProjectStarted(orchestrator, () -> {
            throw new IllegalStateException("workspace orchestrator not ready"); //$NON-NLS-1$
        }, PROJECT, 30_000L, 50L);

        starter.join(5_000L);
        assertTrue(started);
        assertNotNull(orchestrator.listener);
        assertEquals(1, orchestrator.removed.get());
    }
}
