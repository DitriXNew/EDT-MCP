/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationEvent;
import com.e1c.g5.dt.applications.IApplicationEvent.ApplicationEventType;
import com.e1c.g5.dt.applications.IApplicationListener;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Mock-driven tests for the event-driven await helper
 * {@link LaunchLifecycleUtils#awaitUpdateState}.
 *
 * <p>Instead of busy-polling the lagging cached {@code getUpdateState}, the helper
 * registers an {@link IApplicationListener} and wakes on EDT's
 * {@link ApplicationEventType#UPDATE_STATE_CHANGED} event — the same signal the
 * "Applications" view's decorator uses to flip its out-of-sync "*" star. These
 * tests verify:
 * <ul>
 *   <li>immediate return when the entry {@code getUpdateState} already satisfies
 *       the {@code done} predicate (no blocking);</li>
 *   <li>waking on a delivered {@code UPDATE_STATE_CHANGED} event for the target
 *       application, and ignoring events for OTHER applications / other event types;</li>
 *   <li>null-safety (null manager/application/predicate → UNKNOWN);</li>
 *   <li>the listener is always removed (registered once, removed once);</li>
 *   <li>timeout returns the last observed state when no satisfying event arrives.</li>
 * </ul>
 *
 * <p>The sync timing windows are shrunk in {@link #shrinkTimings} so timeout paths
 * resolve quickly.
 */
public class LaunchLifecycleUtilsAwaitTest
{
    @Before
    public void shrinkTimings()
    {
        // settleWindow unused here; applyTimeout small; poll interval small so the
        // safety-net re-read fires quickly when used.
        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 200L, 5L);
    }

    @After
    public void restoreTimings()
    {
        LaunchLifecycleUtils.resetSyncTimingsForTest();
    }

    /** Builds a mock manager that captures the registered listener for manual firing. */
    private static IApplicationManager mockManagerCapturingListener(IApplication app,
            ApplicationUpdateState initialState, AtomicReference<IApplicationListener> captured,
            int[] addCount, int[] removeCount)
        throws ApplicationException
    {
        IApplicationManager mgr = mock(IApplicationManager.class);
        when(mgr.getUpdateState(app)).thenReturn(initialState);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            addCount[0]++;
            return null;
        }).when(mgr).addAppllicationListener(any(IApplicationListener.class));
        doAnswer(inv -> {
            removeCount[0]++;
            return null;
        }).when(mgr).removeAppllicationListener(any(IApplicationListener.class));
        return mgr;
    }

    private static IApplicationEvent event(IApplication app, ApplicationEventType type,
            ApplicationUpdateState state)
    {
        IApplicationEvent e = mock(IApplicationEvent.class);
        when(e.getApplication()).thenReturn(app);
        when(e.getEventType()).thenReturn(type);
        when(e.getUpdateState()).thenReturn(state);
        return e;
    }

    @Test
    public void testNullArgumentsReturnUnknown()
    {
        IApplication app = mock(IApplication.class);
        IApplicationManager mgr = mock(IApplicationManager.class);
        assertEquals(ApplicationUpdateState.UNKNOWN,
            LaunchLifecycleUtils.awaitUpdateState(null, app, s -> true, 100L));
        assertEquals(ApplicationUpdateState.UNKNOWN,
            LaunchLifecycleUtils.awaitUpdateState(mgr, null, s -> true, 100L));
        assertEquals(ApplicationUpdateState.UNKNOWN,
            LaunchLifecycleUtils.awaitUpdateState(mgr, app, null, 100L));
    }

    @Test
    public void testImmediateReturnWhenEntryStateAlreadySatisfies() throws ApplicationException
    {
        // The entry getUpdateState already satisfies `done` (an event may already have
        // fired). Must return immediately without blocking for the timeout.
        IApplication app = mock(IApplication.class);
        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mockManagerCapturingListener(app,
            ApplicationUpdateState.UPDATED, captured, addCount, removeCount);

        long start = System.currentTimeMillis();
        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 5000L);
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(ApplicationUpdateState.UPDATED, result);
        assertTrue("must return immediately, not block on the timeout", elapsed < 1000L);
        assertEquals("listener must be registered exactly once", 1, addCount[0]);
        assertEquals("listener must be removed exactly once", 1, removeCount[0]);
    }

    @Test
    public void testWakesOnUpdateStateChangedEvent() throws ApplicationException, InterruptedException
    {
        // Entry state does NOT satisfy `done`; a background thread later delivers an
        // UPDATE_STATE_CHANGED event carrying UPDATED. The waiter must wake on the
        // event (well before the timeout) and return UPDATED.
        IApplication app = mock(IApplication.class);
        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mockManagerCapturingListener(app,
            ApplicationUpdateState.BEING_UPDATED, captured, addCount, removeCount);

        Thread firer = new Thread(() -> {
            // Spin until the listener is registered, then fire the event.
            IApplicationListener l;
            while ((l = captured.get()) == null)
            {
                Thread.yield();
            }
            l.applicationChanged(event(app, ApplicationEventType.UPDATE_STATE_CHANGED,
                ApplicationUpdateState.UPDATED));
        });
        firer.setDaemon(true);

        // Use a long timeout so a pass proves the EVENT woke us, not the timeout.
        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 5000L, 1000L);
        firer.start();
        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 5000L);
        firer.join(2000L);

        assertEquals(ApplicationUpdateState.UPDATED, result);
        assertEquals(1, addCount[0]);
        assertEquals(1, removeCount[0]);
    }

    @Test
    public void testIgnoresEventsForOtherApplicationAndOtherTypes()
        throws ApplicationException, InterruptedException
    {
        // Events for a DIFFERENT application, and non-UPDATE_STATE_CHANGED events for
        // OUR application, must be ignored; only the matching event ends the wait.
        IApplication app = mock(IApplication.class);
        IApplication other = mock(IApplication.class);
        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mockManagerCapturingListener(app,
            ApplicationUpdateState.BEING_UPDATED, captured, addCount, removeCount);

        Thread firer = new Thread(() -> {
            IApplicationListener l;
            while ((l = captured.get()) == null)
            {
                Thread.yield();
            }
            // Noise: other application, and wrong event type for our application.
            l.applicationChanged(event(other, ApplicationEventType.UPDATE_STATE_CHANGED,
                ApplicationUpdateState.UPDATED));
            l.applicationChanged(event(app, ApplicationEventType.LIFECYCLE_STATE_CHANGED,
                ApplicationUpdateState.UPDATED));
            // Finally the real one.
            l.applicationChanged(event(app, ApplicationEventType.UPDATE_STATE_CHANGED,
                ApplicationUpdateState.UPDATED));
        });
        firer.setDaemon(true);

        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 5000L, 1000L);
        firer.start();
        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 5000L);
        firer.join(2000L);

        assertEquals("only the matching UPDATE_STATE_CHANGED for our app must satisfy done",
            ApplicationUpdateState.UPDATED, result);
    }

    @Test
    public void testWakesOnEventFromRematerializedApplicationInstance()
        throws ApplicationException, InterruptedException
    {
        // The event may carry a DIFFERENT IApplication instance that
        // represents the SAME application (EDT re-materializes instances; concrete
        // implementations carry value semantics). A reference-identity filter would
        // silently drop ALL such events and the wait would stall on the lagging
        // BEING_UPDATED cache until the timeout — so a prompt UPDATED return proves
        // the value-based (id) match.
        IApplication app = mock(IApplication.class);
        when(app.getId()).thenReturn("app-42");
        IApplication rematerialized = mock(IApplication.class);
        when(rematerialized.getId()).thenReturn("app-42");

        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mockManagerCapturingListener(app,
            ApplicationUpdateState.BEING_UPDATED, captured, addCount, removeCount);

        Thread firer = new Thread(() -> {
            IApplicationListener l;
            while ((l = captured.get()) == null)
            {
                Thread.yield();
            }
            l.applicationChanged(event(rematerialized,
                ApplicationEventType.UPDATE_STATE_CHANGED, ApplicationUpdateState.UPDATED));
        });
        firer.setDaemon(true);

        // Long poll interval so the safety-net re-read (which keeps reading
        // BEING_UPDATED) cannot mask a dropped event: only the event itself can
        // deliver UPDATED before the timeout.
        LaunchLifecycleUtils.setSyncTimingsForTest(40L, 5000L, 1000L);
        firer.start();
        long start = System.currentTimeMillis();
        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 5000L);
        long elapsed = System.currentTimeMillis() - start;
        firer.join(2000L);

        assertEquals("an event from a re-materialized instance of the same application "
            + "must satisfy the wait", ApplicationUpdateState.UPDATED, result);
        assertTrue("must wake on the event, not the timeout, took " + elapsed + "ms",
            elapsed < 4000L);
    }

    @Test
    public void testIsSameApplicationMatchesByIdAcrossInstances()
    {
        IApplication a = mock(IApplication.class);
        IApplication b = mock(IApplication.class);
        IApplication c = mock(IApplication.class);
        when(a.getId()).thenReturn("app-42");
        when(b.getId()).thenReturn("app-42");
        when(c.getId()).thenReturn("other");

        assertTrue("same id on different instances must match",
            LaunchLifecycleUtils.isSameApplication(a, b));
        assertFalse("different ids must not match",
            LaunchLifecycleUtils.isSameApplication(a, c));
    }

    @Test
    public void testIsSameApplicationIdentityAndNullSafety()
    {
        IApplication a = mock(IApplication.class);
        assertTrue("identity must match without consulting getId",
            LaunchLifecycleUtils.isSameApplication(a, a));
        assertFalse(LaunchLifecycleUtils.isSameApplication(a, null));
        assertFalse(LaunchLifecycleUtils.isSameApplication(null, a));
    }

    @Test
    public void testIsSameApplicationFallsBackToEqualsWhenIdUnavailable()
    {
        // getId() returns null on both (Mockito default) → falls back to equals();
        // mock equals is identity, so two distinct instances must not match.
        IApplication a = mock(IApplication.class);
        IApplication b = mock(IApplication.class);
        assertFalse(LaunchLifecycleUtils.isSameApplication(a, b));
    }

    @Test
    public void testIsSameApplicationSurvivesThrowingGetId()
    {
        IApplication a = mock(IApplication.class);
        IApplication b = mock(IApplication.class);
        when(a.getId()).thenThrow(new RuntimeException("boom"));
        when(b.getId()).thenThrow(new RuntimeException("boom"));
        assertFalse("a throwing getId must fall back to equals, not propagate",
            LaunchLifecycleUtils.isSameApplication(a, b));
    }

    @Test
    public void testTimeoutReturnsLastObservedState() throws ApplicationException
    {
        // No satisfying event ever arrives; getUpdateState stays BEING_UPDATED.
        // The wait must time out and return the last observed state.
        IApplication app = mock(IApplication.class);
        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mockManagerCapturingListener(app,
            ApplicationUpdateState.BEING_UPDATED, captured, addCount, removeCount);

        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 120L);
        assertEquals(ApplicationUpdateState.BEING_UPDATED, result);
        assertEquals("listener must be removed even on timeout", 1, removeCount[0]);
    }

    @Test
    public void testPollSafetyNetCatchesMissedEvent() throws ApplicationException
    {
        // Simulate a MISSED event: no listener notification is ever fired, but the
        // cached getUpdateState flips to UPDATED on the second read. The periodic
        // safety-net re-read must catch it and end the wait.
        IApplication app = mock(IApplication.class);
        AtomicReference<IApplicationListener> captured = new AtomicReference<>();
        int[] addCount = {0};
        int[] removeCount = {0};
        IApplicationManager mgr = mock(IApplicationManager.class);
        // First read (entry) = BEING_UPDATED; subsequent reads = UPDATED.
        when(mgr.getUpdateState(app))
            .thenReturn(ApplicationUpdateState.BEING_UPDATED)
            .thenReturn(ApplicationUpdateState.UPDATED);
        doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            addCount[0]++;
            return null;
        }).when(mgr).addAppllicationListener(any(IApplicationListener.class));
        doAnswer(inv -> {
            removeCount[0]++;
            return null;
        }).when(mgr).removeAppllicationListener(any(IApplicationListener.class));

        ApplicationUpdateState result = LaunchLifecycleUtils.awaitUpdateState(mgr, app,
            s -> s == ApplicationUpdateState.UPDATED, 2000L);
        assertEquals(ApplicationUpdateState.UPDATED, result);
        assertEquals(1, removeCount[0]);
    }
}
