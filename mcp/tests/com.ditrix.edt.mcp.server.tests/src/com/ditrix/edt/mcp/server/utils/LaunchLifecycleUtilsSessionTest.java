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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.Test;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ExistingClientSession;

/**
 * Tests for the existing-CLIENT-session layer of {@link LaunchLifecycleUtils}
 * — moved here alongside the helpers, which moved
 * from {@code DebugLaunchTool} so {@code debug_launch} AND the YAXUnit debug path
 * share one detect+terminate policy.
 * <p>
 * These headless tests exercise the unified decision point directly: the
 * CLIENT-typed-thread discriminator ({@code firstLiveClientThreadTarget} over
 * {@link DebugServerTargetSupport#findFirstLiveClientThread}) that keeps a
 * standalone-SERVER session — including a debug-mode one whose live thread is
 * typed SERVER — from ever short-circuiting or being terminated, and the
 * terminate halves. The static {@code ILaunchManager} scan inside
 * {@code resolveExistingClientSession} and the target-manager enumeration inside
 * {@code ensureNoExistingClientSession} need a live workbench and are covered
 * E2E; only their null/empty-safe contracts are asserted here.
 */
public class LaunchLifecycleUtilsSessionTest
{
    private static IThread liveThread()
    {
        IThread t = mock(IThread.class);
        when(t.isTerminated()).thenReturn(false);
        return t;
    }

    private static IThread deadThread()
    {
        IThread t = mock(IThread.class);
        when(t.isTerminated()).thenReturn(true);
        return t;
    }

    /** A live 1C thread carrying the given debug-target type. */
    private static IRuntimeDebugTargetThread liveTypedThread(DebugTargetType type)
    {
        IRuntimeDebugTargetThread t = mock(IRuntimeDebugTargetThread.class);
        when(t.isTerminated()).thenReturn(false);
        when(t.getType()).thenReturn(type);
        return t;
    }

    private static IDebugTarget targetWithThreads(IThread... threads) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }

    private static ILaunch launchWithTargets(IDebugTarget... targets)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getDebugTargets()).thenReturn(targets);
        return launch;
    }

    // ==================== firstLiveClientThreadTarget ====================

    @Test
    public void testFirstLiveClientThreadTargetNullLaunchReturnsNull()
    {
        assertNull(LaunchLifecycleUtils.firstLiveClientThreadTarget(null));
    }

    @Test
    public void testFirstLiveClientThreadTargetNoTargetsReturnsNull()
    {
        // A RUN-mode launch carries no debug target — firstLiveClientThreadTarget finds
        // no client DEBUG target (resolveExistingClientSession then treats it as the
        // genuine RUN-mode running client via its zero-targets branch).
        ILaunch launch = launchWithTargets();
        assertNull(LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetThreadlessServerTargetReturnsNull() throws Exception
    {
        // THE THREAD-LESS SERVER CASE: a DEBUG launch whose only target is a
        // thread-less standalone-server / profiling target is NOT a client.
        IDebugTarget server = targetWithThreads(); // zero threads
        ILaunch launch = launchWithTargets(server);
        assertNull(LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetServerTypedLiveThreadReturnsNull() throws Exception
    {
        // THE LIVE-SERVER-THREAD CASE: a debug-mode standalone server target
        // carries a LIVE thread typed SERVER («Сервер») — bare liveness mis-read it as
        // a client; the TYPE discriminator must not.
        IDebugTarget server = targetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        ILaunch launch = launchWithTargets(server);
        assertNull(LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetOnlyTerminatedThreadsReturnsNull() throws Exception
    {
        // A target whose every thread is terminated is equally not a live client.
        IDebugTarget target = targetWithThreads(deadThread());
        ILaunch launch = launchWithTargets(target);
        assertNull(LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetClientTargetReturnsIt() throws Exception
    {
        // THE CLIENT CASE: a thin-client DEBUG target has a live (untyped, hence
        // conservatively client) thread, so it IS the resolved client session target.
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch launch = launchWithTargets(client);
        assertSame(client, LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetClientTypedThreadReturnsIt() throws Exception
    {
        // Same with an explicitly CLIENT-typed (MANAGED_CLIENT = thin client) 1C thread.
        IDebugTarget client = targetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch launch = launchWithTargets(client);
        assertSame(client, LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetSkipsTerminatedTargets() throws Exception
    {
        // A terminated target is skipped even if it still reports a live thread; the
        // next, non-terminated, live-client-thread target is the match.
        IDebugTarget terminated = mock(IDebugTarget.class);
        when(terminated.isTerminated()).thenReturn(true);
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch launch = launchWithTargets(terminated, client);
        assertSame(client, LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    @Test
    public void testFirstLiveClientThreadTargetServerThenClientReturnsClient() throws Exception
    {
        // A launch holding BOTH a live-SERVER-thread server target and a live client
        // target resolves to the client one — the server target never blocks
        // discrimination.
        IDebugTarget server = targetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        IDebugTarget client = targetWithThreads(liveTypedThread(DebugTargetType.CLIENT));
        ILaunch launch = launchWithTargets(server, client);
        assertSame(client, LaunchLifecycleUtils.firstLiveClientThreadTarget(launch));
    }

    // ==================== decideExistingClientSession: the pure decision ====================

    @Test
    public void testDecideNoSessionsReturnsNull()
    {
        assertNull(LaunchLifecycleUtils.decideExistingClientSession(null, null));
    }

    @Test
    public void testDecideLiveClientDebugTargetIsSession() throws Exception
    {
        // findActiveTarget returned a DEBUG target with a live client thread → a real
        // client debug session, carrying the matched target so restartIfRunning /
        // ensureNoExistingClientSession can terminate it.
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.getLaunch()).thenReturn(launch);
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(client, null);
        assertNotNull(session);
        assertSame(client, session.liveTarget);
        assertEquals("debug", session.mode); //$NON-NLS-1$
    }

    @Test
    public void testDecideThreadlessServerTargetIsNotASession() throws Exception
    {
        // THE THREAD-LESS SERVER CASE via findActiveTarget: a thread-less standalone-server
        // target is NOT a client — the decision falls through to activeLaunch (here
        // null) → null, so the client proceeds instead of being short-circuited.
        IDebugTarget server = targetWithThreads(); // zero threads
        assertNull(LaunchLifecycleUtils.decideExistingClientSession(server, null));
    }

    @Test
    public void testDecideServerTypedLiveThreadTargetIsNotASession() throws Exception
    {
        // THE LIVE-SERVER-THREAD CASE via findActiveTarget: a debug-mode
        // standalone server target carries a LIVE thread typed SERVER. It must NOT be
        // a client session — no short-circuit, and (critically) restartIfRunning /
        // the YAXUnit fresh-run sweep never terminate it.
        IDebugTarget server = targetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        assertNull(LaunchLifecycleUtils.decideExistingClientSession(server, null));
    }

    @Test
    public void testDecideMixedServerAndClientThreadsIsSession() throws Exception
    {
        // MIXED: one target carrying both a live SERVER-typed and a live CLIENT-typed
        // thread — a client thread is present, so it IS a client session.
        IDebugTarget mixed = targetWithThreads(
            liveTypedThread(DebugTargetType.SERVER), liveTypedThread(DebugTargetType.CLIENT));
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        when(mixed.getLaunch()).thenReturn(launch);
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(mixed, null);
        assertNotNull(session);
        assertSame(mixed, session.liveTarget);
    }

    @Test
    public void testDecideUnknownTypedLiveThreadTargetIsSession() throws Exception
    {
        // Conservative: an UNKNOWN-typed live thread keeps counting as a client
        // session — behavior changes only where the type positively says server-side.
        IDebugTarget target = targetWithThreads(liveTypedThread(DebugTargetType.UNKNOWN));
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        when(target.getLaunch()).thenReturn(launch);
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(target, null);
        assertNotNull(session);
        assertSame(target, session.liveTarget);
    }

    @Test
    public void testDecideRunModeLaunchIsSessionWithNoTarget()
    {
        // findActiveLaunch returned a RUN-mode launch (no debug target): a genuine
        // running client — the already-running guard. Returned as a session with a null target
        // (terminated via the launch on restartIfRunning).
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(runLaunch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(null, runLaunch);
        assertNotNull(session);
        assertNull(session.liveTarget);
        assertSame(runLaunch, session.launch);
        assertEquals("run", session.mode); //$NON-NLS-1$
    }

    @Test
    public void testDecideDebugLaunchWithOnlyThreadlessTargetsIsNotASession() throws Exception
    {
        // THE SERVER CASE via findActiveLaunch: a DEBUG launch whose every debug target
        // is thread-less is a standalone-server / profiling session, NOT a client.
        IDebugTarget server = targetWithThreads(); // zero threads
        ILaunch debugLaunch = launchWithTargets(server);
        assertNull(LaunchLifecycleUtils.decideExistingClientSession(null, debugLaunch));
    }

    @Test
    public void testDecideDebugLaunchWithOnlyServerTypedThreadsIsNotASession() throws Exception
    {
        // THE LIVE-SERVER-THREAD CASE via findActiveLaunch: a DEBUG launch whose only target's
        // live threads are all SERVER-typed (debug-mode standalone server) must NOT
        // short-circuit the client at either restartIfRunning value.
        IDebugTarget server = targetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        ILaunch debugLaunch = launchWithTargets(server);
        assertNull(LaunchLifecycleUtils.decideExistingClientSession(null, debugLaunch));
    }

    @Test
    public void testDecideDebugLaunchWithLiveClientThreadTargetIsSession() throws Exception
    {
        // A DEBUG launch the target scan missed but which DOES own a live-client-thread
        // target is a client session.
        IDebugTarget client = targetWithThreads(liveThread());
        ILaunch debugLaunch = launchWithTargets(client);
        when(debugLaunch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(null, debugLaunch);
        assertNotNull(session);
        assertSame(client, session.liveTarget);
    }

    @Test
    public void testDecideServerTargetButLiveRunLaunchPrefersRunClient() throws Exception
    {
        // A server target (live SERVER-typed thread) AND a separate RUN-mode client
        // launch for the same app id: the server target is rejected, and the RUN-mode
        // launch is the session — the server never suppresses a genuine client.
        IDebugTarget server = targetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(runLaunch.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        ExistingClientSession session =
            LaunchLifecycleUtils.decideExistingClientSession(server, runLaunch);
        assertNotNull(session);
        assertSame(runLaunch, session.launch);
        assertNull(session.liveTarget);
    }

    // ==================== resolveExistingClientSession (headless contract) ====================

    @Test
    public void testResolveExistingClientSessionNullEmptyAppIdReturnsNull()
    {
        // Null/empty app id can never identify a session — never matches, never throws.
        assertNull(LaunchLifecycleUtils.resolveExistingClientSession(null));
        assertNull(LaunchLifecycleUtils.resolveExistingClientSession("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveExistingClientSessionHeadlessUnknownAppIdReturnsNull()
    {
        // Headless: the ILaunchManager carries no launch for this app id, so the unified
        // detector resolves to null (the live scan is covered E2E).
        assertNull(LaunchLifecycleUtils.resolveExistingClientSession("no-such-app-zzz")); //$NON-NLS-1$
    }

    // ==================== terminate halves ====================

    @Test
    public void testTerminateExistingSessionAndWaitTerminatesAndReturns() throws Exception
    {
        // The shared terminate-half debug_launch's restartIfRunning AND the YAXUnit
        // fresh-run sweep both use: terminate + wait for death (here immediate).
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.canTerminate()).thenReturn(true);
        when(client.isTerminated()).thenReturn(false, true); // dies right after terminate()
        LaunchLifecycleUtils.terminateExistingSessionAndWait(client, "app-x"); //$NON-NLS-1$
        verify(client, times(1)).terminate();
    }

    @Test
    public void testTerminateExistingSessionAndWaitNullTargetIsSafe()
    {
        // Null target: nothing to terminate; the registry forget is still performed
        // (and the call never throws).
        LaunchLifecycleUtils.terminateExistingSessionAndWait(null, "app-x"); //$NON-NLS-1$
        LaunchLifecycleUtils.terminateExistingSessionAndWait(null, null);
    }

    @Test
    public void testTerminateExistingSessionAndWaitTerminateThrowsIsSwallowed() throws Exception
    {
        // Best-effort: a terminate() failure is logged, never thrown — the caller
        // proceeds to launch and the armed confirmer is the race net.
        IDebugTarget target = targetWithThreads(liveThread());
        when(target.canTerminate()).thenReturn(true);
        when(target.isTerminated()).thenReturn(true);
        org.mockito.Mockito.doThrow(new org.eclipse.debug.core.DebugException(
            new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                "test", "refuse"))).when(target).terminate(); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchLifecycleUtils.terminateExistingSessionAndWait(target, "app-x"); //$NON-NLS-1$
    }

    @Test
    public void testTerminateExistingLaunchAndWaitTerminatesAndReturns() throws Exception
    {
        // The launch half handles the RUN-mode / no-debug-target client case.
        ILaunch runLaunch = mock(ILaunch.class);
        when(runLaunch.canTerminate()).thenReturn(true);
        when(runLaunch.isTerminated()).thenReturn(false, true);
        LaunchLifecycleUtils.terminateExistingLaunchAndWait(runLaunch, "app-y"); //$NON-NLS-1$
        verify(runLaunch, times(1)).terminate();
    }

    @Test
    public void testTerminateExistingLaunchAndWaitNullLaunchIsSafe()
    {
        LaunchLifecycleUtils.terminateExistingLaunchAndWait(null, "app-y"); //$NON-NLS-1$
        LaunchLifecycleUtils.terminateExistingLaunchAndWait(null, null);
    }

    @Test
    public void testTerminateExistingLaunchAndWaitCannotTerminateSkipsTerminate() throws Exception
    {
        // canTerminate=false: terminate() is never invoked (no exception path), the
        // wait sees isTerminated and the call returns.
        ILaunch launch = mock(ILaunch.class);
        when(launch.canTerminate()).thenReturn(false);
        when(launch.isTerminated()).thenReturn(true);
        LaunchLifecycleUtils.terminateExistingLaunchAndWait(launch, "app-z"); //$NON-NLS-1$
        verify(launch, never()).terminate();
    }

    // ==================== ensureNoExistingClientSession (headless contract) ====================

    @Test
    public void testEnsureNoExistingClientSessionNullEmptyAppIdIsNoOp()
    {
        // The YAXUnit fresh-run sweep must be a guarded no-op without a delegate app id
        // (no resolvable application): nothing to key the detect on, nothing terminated.
        IProject project = mock(IProject.class);
        assertFalse(LaunchLifecycleUtils.ensureNoExistingClientSession(project, null));
        assertFalse(LaunchLifecycleUtils.ensureNoExistingClientSession(project, "")); //$NON-NLS-1$
    }

    @Test
    public void testEnsureNoExistingClientSessionNullProjectSkipsTargetManagerSource()
    {
        // Null project: the target-manager source is skipped; headless the
        // ILaunchManager source finds nothing either → false, never throws.
        assertFalse(LaunchLifecycleUtils.ensureNoExistingClientSession(null, "some-app")); //$NON-NLS-1$
    }

    @Test
    public void testEnsureNoExistingClientSessionHeadlessNoSessionsReturnsFalse()
    {
        // Headless: no launches, no target manager — nothing detected, nothing
        // terminated, clean false.
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn("MyProject"); //$NON-NLS-1$
        assertFalse(LaunchLifecycleUtils.ensureNoExistingClientSession(project, "some-app")); //$NON-NLS-1$
    }

    // ============ fresh-launch sweep vs MCP-owned launches ============
    // The ILaunchManager-sourced branch of ensureNoExistingClientSession, exercised
    // through its extracted seam sweepLaunchManagerSession (the live launch-manager
    // scan needs a workbench and is covered E2E).

    @Test
    public void testSweepNullSessionIsNoOp()
    {
        assertFalse(LaunchLifecycleUtils.sweepLaunchManagerSession(null, "app-none")); //$NON-NLS-1$
    }

    @Test
    public void testSweepSkipsOwnedRunLaunch() throws Exception
    {
        // THE FINDING: with updateBeforeLaunch=false the fresh-launch sweep is the
        // only guard (prepareForFreshLaunch's hard-fail on owned launches did not
        // run), and it must NOT silently terminate a concurrent MCP-OWNED RUN test
        // launch of the same application — that launch is managed by its own tool.
        ILaunch owned = mock(ILaunch.class);
        when(owned.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(owned.getDebugTargets()).thenReturn(new IDebugTarget[0]);
        LaunchLifecycleUtils.registerOwnedLaunch(owned);
        try
        {
            ExistingClientSession session = new ExistingClientSession(owned, null, "run"); //$NON-NLS-1$
            assertFalse(LaunchLifecycleUtils.sweepLaunchManagerSession(session, "app-owned")); //$NON-NLS-1$
            verify(owned, never()).terminate();
        }
        finally
        {
            LaunchLifecycleUtils.unregisterOwnedLaunch(owned);
        }
    }

    @Test
    public void testSweepSkipsOwnedDebugSession() throws Exception
    {
        // The exemption equally covers an owned DEBUG session resolved with a live
        // client target — its owning launch is in the registry, so neither the
        // target nor the launch is terminated.
        ILaunch ownedLaunch = mock(ILaunch.class);
        when(ownedLaunch.getLaunchMode()).thenReturn("debug"); //$NON-NLS-1$
        IDebugTarget client = targetWithThreads(liveThread());
        when(client.getLaunch()).thenReturn(ownedLaunch);
        LaunchLifecycleUtils.registerOwnedLaunch(ownedLaunch);
        try
        {
            ExistingClientSession session =
                new ExistingClientSession(ownedLaunch, client, "debug"); //$NON-NLS-1$
            assertFalse(LaunchLifecycleUtils.sweepLaunchManagerSession(session, "app-owned-dbg")); //$NON-NLS-1$
            verify(client, never()).terminate();
            verify(ownedLaunch, never()).terminate();
        }
        finally
        {
            LaunchLifecycleUtils.unregisterOwnedLaunch(ownedLaunch);
        }
    }

    @Test
    public void testSweepTerminatesForeignRunLaunch() throws Exception
    {
        // A FOREIGN (not MCP-owned) RUN-mode client launch of the application IS
        // still terminated — the fresh-run guarantee itself is unchanged.
        ILaunch foreign = mock(ILaunch.class);
        when(foreign.getLaunchMode()).thenReturn("run"); //$NON-NLS-1$
        when(foreign.canTerminate()).thenReturn(true);
        when(foreign.isTerminated()).thenReturn(false, true);
        ExistingClientSession session = new ExistingClientSession(foreign, null, "run"); //$NON-NLS-1$
        assertTrue(LaunchLifecycleUtils.sweepLaunchManagerSession(session, "app-foreign")); //$NON-NLS-1$
        verify(foreign, times(1)).terminate();
    }
}
