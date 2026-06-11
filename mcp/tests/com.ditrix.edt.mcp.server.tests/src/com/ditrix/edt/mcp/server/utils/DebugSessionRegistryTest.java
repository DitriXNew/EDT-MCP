/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

/**
 * Tests for the launch-mode predicate that decides whether a launch counts as an
 * active DEBUG session ({@link DebugSessionRegistry#isActiveDebugLaunch}), the
 * forget/purge cleanup paths, and the reverse purge-by-event-source that kills
 * snapshots living under MINTED {@code ServerApplication.<app>} keys on real
 * RESUME/TERMINATE debug events.
 * <p>
 * A RUN-mode launch must be excluded (audit A13): it has no debug target and never
 * suspends, so auto-resolving a debug operation onto it could never succeed.
 * The launch-manager-walking methods around it call {@code DebugPlugin.getDefault()}
 * (a live workbench) and are covered E2E; the pure predicate is unit-tested here.
 */
public class DebugSessionRegistryTest
{
    @Test
    public void testActiveDebugLaunchIsTrueForRunningDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.DEBUG_MODE);
        assertTrue(DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForRunMode()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchMode()).thenReturn(ILaunchManager.RUN_MODE);
        assertFalse("a RUN-mode launch must not count as an active debug session", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForTerminatedDebugLaunch()
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(true);
        assertFalse("a terminated launch must not count as active", //$NON-NLS-1$
            DebugSessionRegistry.isActiveDebugLaunch(launch));
    }

    @Test
    public void testActiveDebugLaunchIsFalseForNull()
    {
        assertFalse(DebugSessionRegistry.isActiveDebugLaunch(null));
    }

    // === forgetApplication ===

    /** Keep the shared singleton clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    @Test
    public void testForgetApplicationDropsSnapshot()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        String appId = "ServerApplication.SomeApp"; //$NON-NLS-1$
        IThread thread = mock(IThread.class);
        // injectSuspend takes the appId directly, so this needs no EDT runtime.
        registry.injectSuspend(appId, thread);
        assertTrue("precondition: snapshot present after injectSuspend", //$NON-NLS-1$
            registry.hasSnapshot(appId));

        registry.forgetApplication(appId);

        assertFalse("snapshot must be gone after forgetApplication", //$NON-NLS-1$
            registry.hasSnapshot(appId));
        assertNull("getSnapshot must return null after forgetApplication", //$NON-NLS-1$
            registry.getSnapshot(appId));
    }

    @Test
    public void testForgetApplicationOnlyTargetsGivenApp()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        String victim = "launch:Victim"; //$NON-NLS-1$
        String survivor = "launch:Survivor"; //$NON-NLS-1$
        IThread victimThread = mock(IThread.class);
        IThread survivorThread = mock(IThread.class);
        registry.injectSuspend(victim, victimThread);
        registry.injectSuspend(survivor, survivorThread);

        registry.forgetApplication(victim);

        assertFalse("targeted app must be forgotten", registry.hasSnapshot(victim)); //$NON-NLS-1$
        assertTrue("unrelated app must be untouched", registry.hasSnapshot(survivor)); //$NON-NLS-1$
        // The survivor's thread reference must still resolve via its stable id.
        long survivorThreadId = registry.getSnapshot(survivor).threadId;
        assertSame("survivor's live thread reference must remain", //$NON-NLS-1$
            survivorThread, registry.getThread(survivorThreadId));
    }

    @Test
    public void testForgetApplicationNullIsNoOp()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // Must not throw and must leave state empty.
        registry.forgetApplication(null);
        assertFalse(registry.hasSnapshot("anything")); //$NON-NLS-1$
    }

    // === purge-by-event-source: minted-key snapshots must die on real events ===

    private static final String MINTED_APP = "ServerApplication.App"; //$NON-NLS-1$

    @Test
    public void testResumeEventPurgesMintedKeySnapshotByThreadReverseLookup()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // A 1C server thread: its launch carries no EDT id, so the launch-attribute
        // lookup (findApplicationIdFor) can never produce the minted key the snapshot
        // lives under — only the reverse thread lookup can purge it.
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenReturn(false); // really resumed
        registry.injectSuspend(MINTED_APP, thread);
        long threadId = registry.getSnapshot(MINTED_APP).threadId;

        registry.handleEvent(new DebugEvent(thread, DebugEvent.RESUME));

        assertFalse("minted-key snapshot must die on a real RESUME event", //$NON-NLS-1$
            registry.hasSnapshot(MINTED_APP));
        assertNull("the purged app's thread reference must be dropped too", //$NON-NLS-1$
            registry.getThread(threadId));
    }

    @Test
    public void testLateResumeEventKeepsFreshSnapshotOfReSuspendedThread()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // Debug events dispatch asynchronously: a LATE resume event can arrive after
        // a poll-based tool (step) already observed the NEXT suspend and injected a
        // fresh snapshot for the same thread. The thread reports suspended again —
        // the stale resume must NOT destroy the fresh snapshot.
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenReturn(true); // already re-suspended
        registry.injectSuspend(MINTED_APP, thread);

        registry.handleEvent(new DebugEvent(thread, DebugEvent.RESUME));

        assertTrue("a late RESUME must not purge the fresh post-step snapshot", //$NON-NLS-1$
            registry.hasSnapshot(MINTED_APP));
    }

    @Test
    public void testEvaluationResumeDoesNotPurgeMintedKeySnapshot()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // An (implicit) evaluation resume returns to the very same suspend location;
        // for a minted key no follow-up SUSPEND event would re-create the snapshot,
        // so the purge must skip evaluation resumes entirely.
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenReturn(false);
        registry.injectSuspend(MINTED_APP, thread);

        registry.handleEvent(
            new DebugEvent(thread, DebugEvent.RESUME, DebugEvent.EVALUATION_IMPLICIT));

        assertTrue("an evaluation resume must not purge the session snapshot", //$NON-NLS-1$
            registry.hasSnapshot(MINTED_APP));
    }

    @Test
    public void testTerminateEventPurgesMintedKeySnapshotByDebugTarget()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IDebugTarget target = mock(IDebugTarget.class);
        IThread thread = mock(IThread.class);
        when(thread.getDebugTarget()).thenReturn(target);
        // Even a thread that still reports suspended dies with its target —
        // TERMINATE ignores the late-event guard.
        when(thread.isSuspended()).thenReturn(true);
        registry.injectSuspend(MINTED_APP, thread);

        registry.handleEvent(new DebugEvent(target, DebugEvent.TERMINATE));

        assertFalse("minted-key snapshot must die when its debug target terminates", //$NON-NLS-1$
            registry.hasSnapshot(MINTED_APP));
    }

    @Test
    public void testResumeEventPurgeLeavesOtherApplicationsUntouched()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread resumedThread = mock(IThread.class);
        when(resumedThread.isSuspended()).thenReturn(false);
        IThread otherThread = mock(IThread.class);
        when(otherThread.isSuspended()).thenReturn(true);
        registry.injectSuspend(MINTED_APP, resumedThread);
        registry.injectSuspend("ServerApplication.Other", otherThread); //$NON-NLS-1$

        registry.handleEvent(new DebugEvent(resumedThread, DebugEvent.RESUME));

        assertFalse(registry.hasSnapshot(MINTED_APP));
        assertTrue("an unrelated session's snapshot must survive", //$NON-NLS-1$
            registry.hasSnapshot("ServerApplication.Other")); //$NON-NLS-1$
    }

    // === thread → applicationId mapping (canonical key for step/resume) ===

    @Test
    public void testGetThreadApplicationIdReturnsRegistrationKey()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        registry.injectSuspend("launch:Cfg", thread); //$NON-NLS-1$
        long threadId = registry.getSnapshot("launch:Cfg").threadId; //$NON-NLS-1$

        assertEquals("launch:Cfg", registry.getThreadApplicationId(threadId)); //$NON-NLS-1$
    }

    @Test
    public void testGetThreadApplicationIdUnknownIdIsNull()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        assertNull(registry.getThreadApplicationId(999_999L));
    }
}
