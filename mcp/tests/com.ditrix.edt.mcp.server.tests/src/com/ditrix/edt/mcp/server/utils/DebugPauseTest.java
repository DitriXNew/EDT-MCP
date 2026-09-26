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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

/**
 * Headless tests for {@link DebugPause}: the target and its threads are mocks whose state flips the
 * way EDT's 1C model flips it, so every outcome is reached without a live session.
 */
public class DebugPauseTest
{
    private static final String APP_ID = "launch:PauseCfg"; //$NON-NLS-1$

    private final DebugSessionRegistry registry = DebugSessionRegistry.get();

    @After
    public void clearRegistry()
    {
        registry.clear();
    }

    @Test
    public void testClampTimeoutSeconds()
    {
        assertEquals(1, DebugPause.clampTimeoutSeconds(0));
        assertEquals(1, DebugPause.clampTimeoutSeconds(-5));
        assertEquals(10, DebugPause.clampTimeoutSeconds(10));
        assertEquals(600, DebugPause.clampTimeoutSeconds(600));
        assertEquals(600, DebugPause.clampTimeoutSeconds(999999));
    }

    @Test
    public void testAlreadySuspendedSessionIsReturnedWithoutARequest() throws Exception
    {
        IThread thread = thread(new AtomicBoolean(true));
        IDebugTarget target = target(thread);

        long started = System.currentTimeMillis();
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.SUSPENDED, outcome.state);
        assertTrue(outcome.alreadySuspended);
        assertSame(thread, outcome.snapshot.thread);
        assertSame("the snapshot must be the session's registered one", outcome.snapshot,
            registry.getSnapshot(APP_ID));
        assertSame(thread, registry.getThread(outcome.snapshot.threadId));
        assertTrue("an already paused session must not wait out the window",
            System.currentTimeMillis() - started < 5_000);
        verify(target, never()).suspend();
    }

    @Test
    public void testAlreadySuspendedKeepsTheThreadIdWaitForBreakHandedOut() throws Exception
    {
        IThread thread = thread(new AtomicBoolean(true));
        IDebugTarget target = target(thread);
        registry.injectSuspend(APP_ID, thread);
        long handedOut = registry.getSnapshot(APP_ID).threadId;

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertTrue(outcome.alreadySuspended);
        assertEquals(handedOut, outcome.snapshot.threadId);
        verify(target, never()).suspend();
    }

    @Test
    public void testTargetLevelSuspendReturnsTheObservedThread() throws Exception
    {
        AtomicBoolean suspended = new AtomicBoolean(false);
        IThread thread = thread(suspended);
        IDebugTarget target = target(thread);
        when(target.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            suspended.set(true);
            return null;
        }).when(target).suspend();

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.SUSPENDED, outcome.state);
        assertFalse(outcome.alreadySuspended);
        assertSame(thread, outcome.snapshot.thread);
        assertSame(outcome.snapshot, registry.getSnapshot(APP_ID));
        verify(target).suspend();
    }

    @Test
    public void testASuspendThatLandsBeforeTheWaitIsNotLost() throws Exception
    {
        // The suspend event can be recorded by the registry listener before the wait begins.
        AtomicBoolean suspended = new AtomicBoolean(false);
        IThread thread = thread(suspended);
        IDebugTarget target = target(thread);
        when(target.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            suspended.set(true);
            registry.injectSuspend(APP_ID, thread);
            return null;
        }).when(target).suspend();

        long started = System.currentTimeMillis();
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.SUSPENDED, outcome.state);
        assertSame(registry.getSnapshot(APP_ID), outcome.snapshot);
        assertTrue(System.currentTimeMillis() - started < 5_000);
    }

    @Test
    public void testAStaleSnapshotIsNotHandedOutAsThePause() throws Exception
    {
        IThread released = thread(new AtomicBoolean(false));
        registry.injectSuspend(APP_ID, released);
        long staleThreadId = registry.getSnapshot(APP_ID).threadId;
        long staleFrameRef = registry.registerFrame(mock(IStackFrame.class), APP_ID);
        AtomicBoolean suspended = new AtomicBoolean(false);
        IThread running = thread(suspended);
        IDebugTarget target = target(running);
        when(target.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            suspended.set(true);
            return null;
        }).when(target).suspend();

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertSame(running, outcome.snapshot.thread);
        assertSame(running, registry.getSnapshot(APP_ID).thread);
        assertNull("the released stop's threadId must stop resolving", registry.getThread(staleThreadId));
        assertNull("the released stop's frameRef must stop resolving", registry.getFrame(staleFrameRef));
    }

    @Test
    public void testAReleasedStopReplacedByAnotherSuspendedThreadDropsItsReferences() throws Exception
    {
        IThread released = thread(new AtomicBoolean(false));
        registry.injectSuspend(APP_ID, released);
        long staleThreadId = registry.getSnapshot(APP_ID).threadId;
        long staleFrameRef = registry.registerFrame(mock(IStackFrame.class), APP_ID);
        IThread stopped = thread(new AtomicBoolean(true));
        IDebugTarget target = target(released, stopped);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.SUSPENDED, outcome.state);
        assertTrue(outcome.alreadySuspended);
        assertSame(stopped, outcome.snapshot.thread);
        assertNull(registry.getThread(staleThreadId));
        assertNull(registry.getFrame(staleFrameRef));
        assertSame(stopped, registry.getThread(outcome.snapshot.threadId));
        verify(target, never()).suspend();
    }

    @Test
    public void testALeftStopIsForgottenEvenWhenNothingStopsInTheWindow() throws Exception
    {
        IThread released = thread(new AtomicBoolean(false));
        registry.injectSuspend(APP_ID, released);
        long staleThreadId = registry.getSnapshot(APP_ID).threadId;
        long staleFrameRef = registry.registerFrame(mock(IStackFrame.class), APP_ID);
        IDebugTarget target = target(released);
        when(target.canSuspend()).thenReturn(true);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 50, 10);

        assertEquals(DebugPause.State.RUNNING, outcome.state);
        assertNull("wait_for_break must not return the left stop as the pause", registry.getSnapshot(APP_ID));
        assertNull(registry.getThread(staleThreadId));
        assertNull(registry.getFrame(staleFrameRef));
    }

    @Test
    public void testALiveStopKeepsTheReferencesAlreadyHandedOut() throws Exception
    {
        // Also the mid-evaluation case: EDT's 1C thread stays SUSPENDED while an expression evaluates.
        IThread thread = thread(new AtomicBoolean(true));
        registry.injectSuspend(APP_ID, thread);
        long threadId = registry.getSnapshot(APP_ID).threadId;
        long frameRef = registry.registerFrame(mock(IStackFrame.class), APP_ID);

        DebugPause.Outcome outcome = DebugPause.pause(target(thread), APP_ID, registry, 10_000, 10);

        assertEquals(threadId, outcome.snapshot.threadId);
        assertSame(thread, registry.getThread(threadId));
        assertNotNull("a stop that is still live must keep its frameRefs", registry.getFrame(frameRef));
    }

    @Test
    public void testThreadFallbackWhenTheTargetRejectsTheRequest() throws Exception
    {
        AtomicBoolean suspended = new AtomicBoolean(false);
        IThread thread = thread(suspended);
        when(thread.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            suspended.set(true);
            return null;
        }).when(thread).suspend();
        IDebugTarget target = target(thread);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.SUSPENDED, outcome.state);
        verify(thread).suspend();
        verify(target, never()).suspend();
    }

    @Test
    public void testNoPauseWithinTheWindowReportsRunning() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.canSuspend()).thenReturn(true);

        long started = System.currentTimeMillis();
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 200, 20);
        long elapsed = System.currentTimeMillis() - started;

        assertEquals(DebugPause.State.RUNNING, outcome.state);
        assertNull(outcome.snapshot);
        assertFalse(outcome.alreadySuspended);
        assertNull("a timeout must not leave a snapshot behind", registry.getSnapshot(APP_ID));
        assertTrue("must wait the whole window, waited " + elapsed, elapsed >= 200);
        assertTrue("must end with the window, waited " + elapsed, elapsed < 5_000);
        verify(target).suspend();
    }

    @Test
    public void testAPendingRequestIsWaitedOnNotResent() throws Exception
    {
        // EDT's target reports isSuspended() from the moment a pause is requested, before any
        // thread stops, and then refuses canSuspend(): an earlier request is still armed.
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.canSuspend()).thenReturn(false);
        when(target.isSuspended()).thenReturn(true);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 100, 20);

        assertEquals("a suspended TARGET with no suspended thread is not a pause",
            DebugPause.State.RUNNING, outcome.state);
        verify(target, never()).suspend();
    }

    @Test
    public void testTerminationDuringTheWaitReportsTerminated() throws Exception
    {
        AtomicBoolean terminated = new AtomicBoolean(false);
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.isTerminated()).thenAnswer(invocation -> terminated.get());
        when(target.canSuspend()).thenReturn(true);
        doAnswer(invocation -> {
            terminated.set(true);
            return null;
        }).when(target).suspend();

        long started = System.currentTimeMillis();
        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.TERMINATED, outcome.state);
        assertNull(outcome.snapshot);
        assertTrue("termination must end the wait early", System.currentTimeMillis() - started < 5_000);
    }

    @Test
    public void testATerminatedTargetIsNotAskedToSuspend() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.isTerminated()).thenReturn(true);
        when(target.canSuspend()).thenReturn(true);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.TERMINATED, outcome.state);
        verify(target, never()).suspend();
    }

    @Test
    public void testATargetEndingBeforeTheRequestIsTerminatedNotRefused() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        // Live at the first check, gone by the time the request would go out.
        when(target.isTerminated()).thenReturn(false, true);

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.TERMINATED, outcome.state);
        verify(target, never()).suspend();
    }

    @Test
    public void testARequestFailingBecauseTheTargetEndedIsTerminated() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.isTerminated()).thenReturn(false, true);
        when(target.canSuspend()).thenReturn(true);
        doThrow(new DebugException(new Status(IStatus.ERROR, "test", "target gone"))) //$NON-NLS-1$ //$NON-NLS-2$
            .when(target).suspend();

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.TERMINATED, outcome.state);
        assertNull(outcome.snapshot);
    }

    @Test
    public void testNothingAcceptingTheRequestIsRefused() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));

        DebugPause.Outcome outcome = DebugPause.pause(target, APP_ID, registry, 10_000, 10);

        assertEquals(DebugPause.State.REFUSED, outcome.state);
        verify(target, never()).suspend();
    }

    @Test
    public void testAFailedRequestPropagates() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.canSuspend()).thenReturn(true);
        doThrow(new DebugException(new Status(IStatus.ERROR, "test", "debug server gone"))) //$NON-NLS-1$ //$NON-NLS-2$
            .when(target).suspend();

        try
        {
            DebugPause.pause(target, APP_ID, registry, 10_000, 10);
            fail("a failed suspend request must not read as an outcome");
        }
        catch (DebugException e)
        {
            assertEquals("debug server gone", e.getMessage()); //$NON-NLS-1$
        }
    }

    @Test
    public void testAnInterruptEndsTheWait() throws Exception
    {
        IDebugTarget target = target(thread(new AtomicBoolean(false)));
        when(target.canSuspend()).thenReturn(true);
        Thread.currentThread().interrupt();
        try
        {
            DebugPause.pause(target, APP_ID, registry, 10_000, 10);
            fail("an interrupted wait must not read as a timeout");
        }
        catch (InterruptedException e)
        {
            assertNotNull(e);
        }
        finally
        {
            Thread.interrupted();
        }
        verify(target).suspend();
    }

    private static IThread thread(AtomicBoolean suspended)
    {
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenAnswer(invocation -> suspended.get());
        return thread;
    }

    private static IDebugTarget target(IThread... threads) throws DebugException
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }
}
