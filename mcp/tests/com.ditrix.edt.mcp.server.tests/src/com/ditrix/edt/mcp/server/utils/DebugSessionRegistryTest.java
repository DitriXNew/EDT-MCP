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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
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

    // === SUSPEND events vs a stop a poller already registered ===

    private static final String LAUNCH_APP = "launch:Cfg"; //$NON-NLS-1$

    @Test
    public void testSuspendEventRegistersASnapshot() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = launchThread(LAUNCH_APP);

        registry.handleEvent(new DebugEvent(thread, DebugEvent.SUSPEND));

        assertSame(thread, registry.getSnapshot(LAUNCH_APP).thread);
        assertSame(thread, registry.getThread(registry.getSnapshot(LAUNCH_APP).threadId));
    }

    @Test
    public void testALateSuspendEventKeepsTheHandedOutThreadIdValid() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = launchThread(LAUNCH_APP);
        // A poller (debug_pause / wait_for_break) saw the stop before Eclipse dispatched its event.
        registry.injectSuspend(LAUNCH_APP, thread);
        long handedOut = registry.getSnapshot(LAUNCH_APP).threadId;

        registry.handleEvent(new DebugEvent(thread, DebugEvent.SUSPEND));

        // The event records its own stop; the id already handed out still addresses the thread.
        DebugSessionRegistry.SuspendSnapshot current = registry.getSnapshot(LAUNCH_APP);
        assertSame(thread, current.thread);
        assertFalse("the event cannot tell this stop from a later one, so it mints its own id", //$NON-NLS-1$
            handedOut == current.threadId);
        assertSame(thread, registry.getThread(handedOut));
        assertEquals(LAUNCH_APP, registry.getThreadApplicationId(handedOut));
    }

    @Test
    public void testASecondStopOfTheSameThreadAfterAStepIsANewSnapshot() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = launchThread(LAUNCH_APP);
        registry.handleEvent(new DebugEvent(thread, DebugEvent.SUSPEND));
        long firstStop = registry.getSnapshot(LAUNCH_APP).threadId;

        // A 1C step fires its RESUME on the debug target, never on the thread.
        registry.handleEvent(new DebugEvent(thread.getDebugTarget(), DebugEvent.RESUME, DebugEvent.STEP_OVER));
        registry.handleEvent(new DebugEvent(thread, DebugEvent.SUSPEND));

        assertFalse("the stop after the step must not be taken for the first one", //$NON-NLS-1$
            firstStop == registry.getSnapshot(LAUNCH_APP).threadId);
    }

    @Test
    public void testAnEvaluationResumeKeepsMintedKeyFrames()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = mock(IThread.class);
        // The generic Eclipse model runs the thread while an expression evaluates.
        when(thread.isSuspended()).thenReturn(false);
        IStackFrame frame = mock(IStackFrame.class);
        when(frame.getThread()).thenReturn(thread);
        registry.injectSuspend(MINTED_APP, thread);
        long frameRef = registry.registerFrame(frame, MINTED_APP);

        registry.handleEvent(new DebugEvent(thread, DebugEvent.RESUME, DebugEvent.EVALUATION_IMPLICIT));

        assertSame("an evaluation returns to the same stop, so its frameRef must survive", //$NON-NLS-1$
            frame, registry.getFrame(frameRef));
    }

    @Test
    public void testSuspendEventOfAnotherThreadReplacesTheSnapshot() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread first = launchThread(LAUNCH_APP);
        IThread second = launchThread(LAUNCH_APP);
        registry.injectSuspend(LAUNCH_APP, first);
        long firstId = registry.getSnapshot(LAUNCH_APP).threadId;

        registry.handleEvent(new DebugEvent(second, DebugEvent.SUSPEND));

        DebugSessionRegistry.SuspendSnapshot current = registry.getSnapshot(LAUNCH_APP);
        assertSame(second, current.thread);
        assertFalse(firstId == current.threadId);
    }

    @Test
    public void testFramesRegisteredUnderAMintedKeyDieWithTheSession()
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        // A launchless 1C server thread: no launch attribute can yield the minted key.
        IThread thread = mock(IThread.class);
        when(thread.isSuspended()).thenReturn(false);
        IStackFrame frame = mock(IStackFrame.class);
        when(frame.getThread()).thenReturn(thread);
        registry.injectSuspend(MINTED_APP, thread);
        long frameRef = registry.registerFrame(frame, MINTED_APP);

        registry.handleEvent(new DebugEvent(thread, DebugEvent.RESUME));

        assertNull("a resumed session's frameRef must stop resolving", registry.getFrame(frameRef)); //$NON-NLS-1$
    }

    // === RESUME on the debug target: a step started outside the step tool ===

    private static final String OTHER_APP = "ServerApplication.Other"; //$NON-NLS-1$

    @Test
    public void testATargetStepIntoForgetsAMintedSession() throws Exception
    {
        assertATargetStepForgetsAMintedSession(DebugEvent.STEP_INTO);
    }

    @Test
    public void testATargetStepOverForgetsAMintedSession() throws Exception
    {
        assertATargetStepForgetsAMintedSession(DebugEvent.STEP_OVER);
    }

    @Test
    public void testATargetStepReturnForgetsAMintedSession() throws Exception
    {
        assertATargetStepForgetsAMintedSession(DebugEvent.STEP_RETURN);
    }

    @Test
    public void testATargetStepForgetsALaunchKeySession() throws Exception
    {
        IThread thread = launchThread(LAUNCH_APP);
        when(thread.isSuspended()).thenReturn(false);
        Stop stop = stopOf(LAUNCH_APP, thread);

        DebugSessionRegistry.get().handleEvent(
            new DebugEvent(thread.getDebugTarget(), DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertForgotten(stop);
    }

    @Test
    public void testALateTargetStepKeepsAMintedSession() throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        Stop stop = stopOf(MINTED_APP, threadOf(target, true));

        DebugSessionRegistry.get().handleEvent(new DebugEvent(target, DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertKept(stop);
    }

    @Test
    public void testALateTargetStepKeepsALaunchKeySession() throws Exception
    {
        // launchThread reports suspended: the thread already stopped again.
        IThread thread = launchThread(LAUNCH_APP);
        Stop stop = stopOf(LAUNCH_APP, thread);

        DebugSessionRegistry.get().handleEvent(
            new DebugEvent(thread.getDebugTarget(), DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertKept(stop);
    }

    @Test
    public void testAClientRequestResumeOnTheTargetIsANoOp() throws Exception
    {
        assertATargetResumeIsANoOp(DebugEvent.CLIENT_REQUEST);
    }

    @Test
    public void testAnUnspecifiedResumeOnTheTargetIsANoOp() throws Exception
    {
        assertATargetResumeIsANoOp(DebugEvent.UNSPECIFIED);
    }

    @Test
    public void testAnEvaluationResumeOnTheTargetIsANoOp() throws Exception
    {
        assertATargetResumeIsANoOp(DebugEvent.EVALUATION);
    }

    @Test
    public void testAnImplicitEvaluationResumeOnTheTargetIsANoOp() throws Exception
    {
        assertATargetResumeIsANoOp(DebugEvent.EVALUATION_IMPLICIT);
    }

    @Test
    public void testAStepEndResumeOnTheTargetIsANoOp() throws Exception
    {
        assertATargetResumeIsANoOp(DebugEvent.STEP_END);
    }

    @Test
    public void testATargetStepLeavesASuspendedSiblingUnderAnotherKey() throws Exception
    {
        // The purge walks a hash map keyed by ids that clear() never resets, so which of the two
        // threads it meets first depends on the ids earlier tests used. A pass takes four ids and
        // only the last two residues of the table put the sibling second: two passes cannot both
        // land there, so at least one meets the suspended sibling first.
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        for (int pass = 0; pass < 2; pass++)
        {
            registry.clear();
            IDebugTarget target = mock(IDebugTarget.class);
            Stop sibling = stopOf(OTHER_APP, threadOf(target, true));
            Stop stepped = stopOf(MINTED_APP, threadOf(target, false));

            registry.handleEvent(new DebugEvent(target, DebugEvent.RESUME, DebugEvent.STEP_OVER));

            assertForgotten(stepped);
            assertKept(sibling);
        }
    }

    @Test
    public void testATargetStepForgetsAKeyWhoseSnapshotThreadIsStillSuspended() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IDebugTarget target = mock(IDebugTarget.class);
        IThread running = threadOf(target, false);
        IThread suspended = threadOf(target, true);
        registry.injectSuspend(MINTED_APP, running);
        long runningId = registry.getSnapshot(MINTED_APP).threadId;
        registry.clearSnapshot(MINTED_APP);
        registry.injectSuspend(MINTED_APP, suspended);
        long suspendedId = registry.getSnapshot(MINTED_APP).threadId;

        registry.handleEvent(new DebugEvent(target, DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertFalse("one running thread is enough to forget the whole key", //$NON-NLS-1$
            registry.hasSnapshot(MINTED_APP));
        assertNull(registry.getThread(runningId));
        assertNull(registry.getThread(suspendedId));
    }

    @Test
    public void testAStepOnAnotherTargetPurgesNothing() throws Exception
    {
        IThread thread = threadOf(mock(IDebugTarget.class), false);
        Stop stop = stopOf(MINTED_APP, thread);

        DebugSessionRegistry.get().handleEvent(
            new DebugEvent(mock(IDebugTarget.class), DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertKept(stop);
        verify(thread, never()).isSuspended();
    }

    @Test
    public void testATargetStepForgetsASessionWhoseThreadStateIsUnknown() throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        IThread thread = threadOf(target, false);
        when(thread.isSuspended()).thenThrow(new IllegalStateException("disconnected")); //$NON-NLS-1$
        Stop stop = stopOf(MINTED_APP, thread);

        DebugSessionRegistry.get().handleEvent(new DebugEvent(target, DebugEvent.RESUME, DebugEvent.STEP_OVER));

        assertForgotten(stop);
    }

    @Test
    public void testASuspendAfterAForgottenStepIsANewStopAndTheOldIdsStayDead() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = launchThread(LAUNCH_APP);
        when(thread.isSuspended()).thenReturn(false);
        Stop before = stopOf(LAUNCH_APP, thread);
        registry.handleEvent(new DebugEvent(thread.getDebugTarget(), DebugEvent.RESUME, DebugEvent.STEP_OVER));

        when(thread.isSuspended()).thenReturn(true);
        registry.handleEvent(new DebugEvent(thread, DebugEvent.SUSPEND));

        long after = registry.getSnapshot(LAUNCH_APP).threadId;
        assertFalse("the stop after the step must get its own id", before.threadId == after); //$NON-NLS-1$
        assertSame(thread, registry.getThread(after));
        assertNull("the id handed out before the step must not come back", //$NON-NLS-1$
            registry.getThread(before.threadId));
        assertNull("the frameRef handed out before the step must not come back", //$NON-NLS-1$
            registry.getFrame(before.frameRef));
    }

    @Test
    public void testAPlainResumeSequenceLeavesASuspendedSiblingUnderAnotherKey() throws Exception
    {
        // EDT's plain resume passes the literal detail 1 to the thread and then to its target,
        // and 1 is also the value of STEP_INTO: the target's half looks like a step.
        int plainResumeDetail = 1;
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IDebugTarget target = mock(IDebugTarget.class);
        Stop sibling = stopOf(OTHER_APP, threadOf(target, true));
        IThread resumed = threadOf(target, false);
        Stop stop = stopOf(MINTED_APP, resumed);

        registry.handleEvent(new DebugEvent(resumed, DebugEvent.RESUME, plainResumeDetail));
        registry.handleEvent(new DebugEvent(target, DebugEvent.RESUME, plainResumeDetail));

        assertForgotten(stop);
        assertKept(sibling);
    }

    private static void assertATargetStepForgetsAMintedSession(int detail) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        Stop stop = stopOf(MINTED_APP, threadOf(target, false));

        DebugSessionRegistry.get().handleEvent(new DebugEvent(target, DebugEvent.RESUME, detail));

        assertForgotten(stop);
    }

    private static void assertATargetResumeIsANoOp(int detail) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        Stop stop = stopOf(MINTED_APP, threadOf(target, false));

        DebugSessionRegistry.get().handleEvent(new DebugEvent(target, DebugEvent.RESUME, detail));

        assertKept(stop);
    }

    /** A thread of the target; no launch attribute can yield a minted key for it. */
    private static IThread threadOf(IDebugTarget target, boolean suspended) throws Exception
    {
        IThread thread = mock(IThread.class);
        when(thread.getDebugTarget()).thenReturn(target);
        when(thread.isSuspended()).thenReturn(suspended);
        return thread;
    }

    /** A stop as a tool reply hands it out: the thread's stable id and one frameRef. */
    private static Stop stopOf(String appId, IThread thread)
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.injectSuspend(appId, thread);
        IStackFrame frame = mock(IStackFrame.class);
        long threadId = registry.getSnapshot(appId).threadId;
        return new Stop(appId, thread, threadId, frame, registry.registerFrame(frame, appId));
    }

    private static void assertForgotten(Stop stop) throws InterruptedException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        assertFalse("the snapshot must be forgotten", registry.hasSnapshot(stop.appId)); //$NON-NLS-1$
        assertNull("the threadId must stop resolving", registry.getThread(stop.threadId)); //$NON-NLS-1$
        assertNull("the frameRef must stop resolving", registry.getFrame(stop.frameRef)); //$NON-NLS-1$
        assertNull("a waiter must not get the old stop", registry.waitForSuspend(stop.appId, 0)); //$NON-NLS-1$
    }

    private static void assertKept(Stop stop) throws InterruptedException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        assertSame("the snapshot must be kept", stop.thread, registry.waitForSuspend(stop.appId, 0).thread); //$NON-NLS-1$
        assertSame("the threadId must keep resolving", stop.thread, registry.getThread(stop.threadId)); //$NON-NLS-1$
        assertSame("the frameRef must keep resolving", stop.frame, registry.getFrame(stop.frameRef)); //$NON-NLS-1$
    }

    /** What a stop handed out: its session key, thread, stable thread id, frame and frameRef. */
    private static final class Stop
    {
        final String appId;
        final IThread thread;
        final long threadId;
        final IStackFrame frame;
        final long frameRef;

        Stop(String appId, IThread thread, long threadId, IStackFrame frame, long frameRef)
        {
            this.appId = appId;
            this.thread = thread;
            this.threadId = threadId;
            this.frame = frame;
            this.frameRef = frameRef;
        }
    }

    /** A thread whose launch configuration carries {@code appId} as its application id. */
    private static IThread launchThread(String appId) throws Exception
    {
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, (String) null)).thenReturn(appId);
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.getLaunch()).thenReturn(launch);
        IThread thread = mock(IThread.class);
        when(thread.getDebugTarget()).thenReturn(target);
        when(thread.isSuspended()).thenReturn(true);
        return thread;
    }
}
