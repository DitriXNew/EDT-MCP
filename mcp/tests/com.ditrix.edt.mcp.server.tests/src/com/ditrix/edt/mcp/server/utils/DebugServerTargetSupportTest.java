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
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;
import org.junit.Test;

import com._1c.g5.v8.dt.debug.core.model.IRuntimeDebugTargetThread;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;

/**
 * Headless tests for {@link DebugServerTargetSupport} — the bridge that surfaces
 * 1C debug-server targets (server-side breakpoint suspends from
 * {@code debug_yaxunit_tests}, and EDT-UI-started "Debug As" sessions) as standard
 * Eclipse debug-model elements.
 *
 * <p>Only the null/empty-safe contract is exercised here: in the Tycho headless
 * runtime the {@code IRuntimeDebugClientTargetManager} OSGi service is not
 * registered, so enumeration yields an empty list and resolution yields
 * {@code null}. The live suspend/resume/step path needs a real running 1C session
 * and is verified E2E.
 */
public class DebugServerTargetSupportTest
{
    @Test
    public void testServerAppIdPrefix()
    {
        assertEquals("ServerApplication.", DebugServerTargetSupport.SERVER_APP_ID_PREFIX); //$NON-NLS-1$
    }

    @Test
    public void testIsServerApplicationIdMatchesServerPrefix()
    {
        // The server-application gate: the literal "ServerApplication." prefix marks a
        // standalone-server application that must never be DB-updated out-of-band.
        assertTrue(DebugServerTargetSupport.isServerApplicationId("ServerApplication.MyServer")); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.isServerApplicationId(
            DebugServerTargetSupport.SERVER_APP_ID_PREFIX + "x")); //$NON-NLS-1$
    }

    @Test
    public void testIsServerApplicationIdRejectsOtherIds()
    {
        // Null/empty, the other synthetic prefixes and a real infobase-application id
        // are all NOT server applications — their programmatic pre-update keeps running.
        assertFalse(DebugServerTargetSupport.isServerApplicationId(null));
        assertFalse(DebugServerTargetSupport.isServerApplicationId("")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.isServerApplicationId("launch:MyConfig")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.isServerApplicationId("attach:MyConfig")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.isServerApplicationId(
            "0461b6bb-39f8-4b2b-9268-0d4bbc9e3df9")); //$NON-NLS-1$
        // The gate is the literal prefix — never case-insensitive, never a bare name.
        assertFalse(DebugServerTargetSupport.isServerApplicationId("serverapplication.x")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.isServerApplicationId("ServerApplication")); //$NON-NLS-1$
    }

    @Test
    public void testListServerTargetsNeverNull()
    {
        // Headless: no debug-core manager → empty, never null, never throws.
        assertNotNull(DebugServerTargetSupport.listServerTargets());
        assertTrue(DebugServerTargetSupport.listServerTargets().isEmpty());
    }

    @Test
    public void testResolveNullReturnsNull()
    {
        assertNull(DebugServerTargetSupport.resolve(null));
    }

    @Test
    public void testResolveEmptyReturnsNull()
    {
        assertNull(DebugServerTargetSupport.resolve("")); //$NON-NLS-1$
    }

    @Test
    public void testResolveUnknownReturnsNull()
    {
        // No targets registered headless, so any id resolves to null.
        assertNull(DebugServerTargetSupport.resolve("ServerApplication.Nope")); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.resolve("Nope")); //$NON-NLS-1$
    }

    @Test
    public void testFindLoneServerTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findLoneServerTarget());
    }

    @Test
    public void testFindSuspendedThreadNullTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findSuspendedThread(null));
    }

    @Test
    public void testIsAnyThreadSuspendedNullTargetIsFalse()
    {
        assertFalse(DebugServerTargetSupport.isAnyThreadSuspended(null));
    }

    @Test
    public void testPollForSuspendedThreadNullTargetReturnsNull() throws InterruptedException
    {
        // A null/terminated target must return immediately (not block for the timeout).
        long start = System.currentTimeMillis();
        assertNull(DebugServerTargetSupport.pollForSuspendedThread(null, 2000L, 50L));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("must not block for the full timeout on a null target", elapsed < 1500L); //$NON-NLS-1$
    }

    // === findRuntimeClientDebugTarget — the delegate-criterion duplicate guard ===

    @Test
    public void testFindRuntimeClientDebugTargetNullArgsReturnNull()
    {
        // Both keys are required — a null/empty project or app id can never identify the
        // delegate's session, so the guard must never match (and never throw) on them.
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(null, "app")); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", null)); //$NON-NLS-1$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("", "app")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFindRuntimeClientDebugTargetHeadlessReturnsNull()
    {
        // Headless: no IRuntimeDebugClientTargetManager OSGi service is registered, so
        // listDebugTargets() yields nothing and the guard resolves to null (never throws).
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget("Proj", "app")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === findFirstLiveThread — the live-thread discriminator ===
    //
    // findRuntimeClientDebugTarget itself can't be exercised with a mock target headless
    // (it enumerates the IRuntimeDebugClientTargetManager OSGi service, absent here), but
    // findFirstLiveThread IS the discriminator it now requires: a thin-CLIENT debug
    // session has ≥1 non-terminated thread; a standalone-SERVER / profiling target has 0.
    // These mock-target tests pin exactly that "server target no longer matches, client
    // target still matches" behavior the regression fix hinges on.

    @Test
    public void testFindFirstLiveThreadNullTargetReturnsNull()
    {
        assertNull(DebugServerTargetSupport.findFirstLiveThread(null));
    }

    @Test
    public void testFindFirstLiveThreadTerminatedTargetReturnsNull()
    {
        // A terminated target is never a duplicate, even if it still reports threads.
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(true);
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadServerTargetWithNoThreadsReturnsNull() throws Exception
    {
        // THE SERVER CASE: a standalone-server / profiling target shares
        // the client's app id + project but has ZERO threads. It must NOT be treated as a
        // live client session — so findRuntimeClientDebugTarget will return null for it and
        // the client launch proceeds instead of being wrongly short-circuited.
        IDebugTarget server = mock(IDebugTarget.class);
        when(server.isTerminated()).thenReturn(false);
        when(server.getThreads()).thenReturn(new IThread[0]);
        assertNull(DebugServerTargetSupport.findFirstLiveThread(server));
    }

    @Test
    public void testFindFirstLiveThreadServerTargetWithOnlyTerminatedThreadsReturnsNull() throws Exception
    {
        // A target whose every thread is terminated is equally NOT a live session —
        // mirrors the delegate's filter(!isTerminated) over the thread list.
        IThread dead = mock(IThread.class);
        when(dead.isTerminated()).thenReturn(true);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {dead});
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadClientTargetReturnsLiveThread() throws Exception
    {
        // THE CLIENT CASE: a thin-client debug session has ≥1 non-terminated thread; that
        // thread IS returned, so findRuntimeClientDebugTarget would match it (a real
        // already-running client correctly short-circuits / is restarted).
        IThread live = mock(IThread.class);
        when(live.isTerminated()).thenReturn(false);
        IDebugTarget client = mock(IDebugTarget.class);
        when(client.isTerminated()).thenReturn(false);
        when(client.getThreads()).thenReturn(new IThread[] {live});
        assertSame(live, DebugServerTargetSupport.findFirstLiveThread(client));
    }

    @Test
    public void testFindFirstLiveThreadSkipsTerminatedAndReturnsFirstLive() throws Exception
    {
        // A live thread among terminated ones (e.g. a worker that already exited) still
        // makes the target a live session — the first non-terminated thread is returned.
        IThread dead = mock(IThread.class);
        when(dead.isTerminated()).thenReturn(true);
        IThread live = mock(IThread.class);
        when(live.isTerminated()).thenReturn(false);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {dead, live});
        assertSame(live, DebugServerTargetSupport.findFirstLiveThread(target));
    }

    @Test
    public void testFindFirstLiveThreadDoesNotRequireSuspension() throws Exception
    {
        // Unlike findSuspendedThread, liveness — NOT suspension — is the discriminator:
        // a running (not suspended) client thread still counts as a live session.
        IThread running = mock(IThread.class);
        when(running.isTerminated()).thenReturn(false);
        when(running.isSuspended()).thenReturn(false);
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(new IThread[] {running});
        assertSame(running, DebugServerTargetSupport.findFirstLiveThread(target));
        // ...and the suspension-requiring finder returns null for the same target.
        assertNull(DebugServerTargetSupport.findSuspendedThread(target));
    }

    @Test
    public void testFindFirstLiveThreadGetThreadsThrowsReturnsNull() throws Exception
    {
        // Best-effort: a target whose getThreads() throws (model mid-teardown) yields
        // null, never an exception onto the caller.
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenThrow(new org.eclipse.debug.core.DebugException(
            new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                "test", "threads unavailable"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findFirstLiveThread(target));
    }

    // === isClientThread / findFirstLiveClientThread — the TYPE-aware discriminator ===
    //
    // Bare thread liveness is NOT a client/server discriminator: a standalone server
    // launched in DEBUG mode carries a LIVE IRuntimeDebugTargetThread typed SERVER
    // (presented as «Сервер»), which the previous liveness-only test mis-read as a
    // client session — restartIfRunning then terminated the SERVER session and hung
    // on its restart. These tests pin the type matrix: a live thread counts as a
    // CLIENT thread unless its 1C type POSITIVELY classifies as server-side
    // (DebugTargetTypeUtil.isServer); unknown / non-1C / unreadable types stay
    // conservatively client so the detection is never weakened by a model hiccup.

    private static IRuntimeDebugTargetThread liveTypedThread(DebugTargetType type)
    {
        IRuntimeDebugTargetThread t = mock(IRuntimeDebugTargetThread.class);
        when(t.isTerminated()).thenReturn(false);
        when(t.getType()).thenReturn(type);
        return t;
    }

    private static IDebugTarget liveTargetWithThreads(IThread... threads) throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }

    @Test
    public void testIsClientThreadServerTypedThreadIsNotClient()
    {
        // THE SERVER-THREAD CASE: a live thread typed SERVER (debug-mode standalone server,
        // «Сервер») positively classifies as server-side — NOT a client thread.
        assertFalse(DebugServerTargetSupport.isClientThread(liveTypedThread(DebugTargetType.SERVER)));
    }

    @Test
    public void testIsClientThreadAllServerSideTypesAreNotClient()
    {
        // Every type DebugTargetTypeUtil.isServer covers is server-side.
        assertFalse(DebugServerTargetSupport.isClientThread(
            liveTypedThread(DebugTargetType.SERVER_EMULATION)));
        assertFalse(DebugServerTargetSupport.isClientThread(
            liveTypedThread(DebugTargetType.MOBILE_SERVER)));
        assertFalse(DebugServerTargetSupport.isClientThread(
            liveTypedThread(DebugTargetType.MOBILE_MANAGED_SERVER)));
    }

    @Test
    public void testIsClientThreadClientTypedThreadsAreClient()
    {
        // CLIENT (thick) and MANAGED_CLIENT (thin) are the canonical client types.
        assertTrue(DebugServerTargetSupport.isClientThread(liveTypedThread(DebugTargetType.CLIENT)));
        assertTrue(DebugServerTargetSupport.isClientThread(
            liveTypedThread(DebugTargetType.MANAGED_CLIENT)));
        assertTrue(DebugServerTargetSupport.isClientThread(
            liveTypedThread(DebugTargetType.WEB_CLIENT)));
    }

    @Test
    public void testIsClientThreadUnknownTypeIsConservativelyClient()
    {
        // UNKNOWN type → counts as client (behavior changes ONLY where the type
        // positively says server-side).
        assertTrue(DebugServerTargetSupport.isClientThread(liveTypedThread(DebugTargetType.UNKNOWN)));
    }

    @Test
    public void testIsClientThreadNullTypeIsConservativelyClient()
    {
        // A null getType() must not be fed into DebugTargetTypeUtil (it throws NPE on
        // null) and must stay conservatively client.
        assertTrue(DebugServerTargetSupport.isClientThread(liveTypedThread(null)));
    }

    @Test
    public void testIsClientThreadNonRuntimeThreadIsConservativelyClient()
    {
        // A plain (non-1C) IThread carries no type — conservatively client.
        assertTrue(DebugServerTargetSupport.isClientThread(mock(IThread.class)));
    }

    @Test
    public void testIsClientThreadGetTypeThrowsIsConservativelyClient()
    {
        // Best-effort: a getType() failure (model mid-teardown) must not reclassify
        // the thread as server-side, and must never throw.
        IRuntimeDebugTargetThread t = mock(IRuntimeDebugTargetThread.class);
        when(t.getType()).thenThrow(new IllegalStateException("model gone")); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.isClientThread(t));
    }

    @Test
    public void testIsClientThreadNullThreadIsNotClient()
    {
        assertFalse(DebugServerTargetSupport.isClientThread(null));
    }

    @Test
    public void testFindFirstLiveClientThreadServerTypedLiveThreadReturnsNull() throws Exception
    {
        // THE SERVER-TARGET CASE: a debug-mode standalone server target with a LIVE
        // SERVER-typed thread is NOT a client session — no client thread is found, so
        // findRuntimeClientDebugTarget never matches it (no short-circuit, no
        // terminate), while the liveness-only finder still sees the thread.
        IRuntimeDebugTargetThread serverThread = liveTypedThread(DebugTargetType.SERVER);
        IDebugTarget serverTarget = liveTargetWithThreads(serverThread);
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(serverTarget));
        assertSame(serverThread, DebugServerTargetSupport.findFirstLiveThread(serverTarget));
    }

    @Test
    public void testFindFirstLiveClientThreadClientTypedLiveThreadReturnsIt() throws Exception
    {
        // A live MANAGED_CLIENT (thin client) thread IS the client discriminator.
        IRuntimeDebugTargetThread clientThread = liveTypedThread(DebugTargetType.MANAGED_CLIENT);
        IDebugTarget clientTarget = liveTargetWithThreads(clientThread);
        assertSame(clientThread, DebugServerTargetSupport.findFirstLiveClientThread(clientTarget));
    }

    @Test
    public void testFindFirstLiveClientThreadMixedThreadsReturnsTheClientOne() throws Exception
    {
        // MIXED (server + client threads on one target): the client thread is present,
        // so the session IS a client session — the server-typed thread is skipped.
        IRuntimeDebugTargetThread serverThread = liveTypedThread(DebugTargetType.SERVER);
        IRuntimeDebugTargetThread clientThread = liveTypedThread(DebugTargetType.CLIENT);
        IDebugTarget target = liveTargetWithThreads(serverThread, clientThread);
        assertSame(clientThread, DebugServerTargetSupport.findFirstLiveClientThread(target));
    }

    @Test
    public void testFindFirstLiveClientThreadUnknownTypedLiveThreadCounts() throws Exception
    {
        // Conservative: an UNKNOWN-typed live thread keeps counting as a client
        // session (exactly the previous behavior for everything not positively server).
        IRuntimeDebugTargetThread unknownThread = liveTypedThread(DebugTargetType.UNKNOWN);
        IDebugTarget target = liveTargetWithThreads(unknownThread);
        assertSame(unknownThread, DebugServerTargetSupport.findFirstLiveClientThread(target));
    }

    @Test
    public void testFindFirstLiveClientThreadPlainEclipseThreadCounts() throws Exception
    {
        // A non-1C live IThread (no getType at all) conservatively counts as client.
        IThread plain = mock(IThread.class);
        when(plain.isTerminated()).thenReturn(false);
        IDebugTarget target = liveTargetWithThreads(plain);
        assertSame(plain, DebugServerTargetSupport.findFirstLiveClientThread(target));
    }

    @Test
    public void testFindFirstLiveClientThreadDeadClientLiveServerReturnsNull() throws Exception
    {
        // A terminated client thread plus a live SERVER-typed thread: no LIVE client
        // thread exists — not a client session.
        IRuntimeDebugTargetThread deadClient = mock(IRuntimeDebugTargetThread.class);
        when(deadClient.isTerminated()).thenReturn(true);
        when(deadClient.getType()).thenReturn(DebugTargetType.MANAGED_CLIENT);
        IRuntimeDebugTargetThread liveServer = liveTypedThread(DebugTargetType.SERVER);
        IDebugTarget target = liveTargetWithThreads(deadClient, liveServer);
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(target));
    }

    @Test
    public void testFindFirstLiveClientThreadNullAndTerminatedTargetReturnNull()
    {
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(null));
        IDebugTarget terminated = mock(IDebugTarget.class);
        when(terminated.isTerminated()).thenReturn(true);
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(terminated));
    }

    @Test
    public void testFindFirstLiveClientThreadNoThreadsReturnsNull() throws Exception
    {
        // The legacy server shape (profiling / idle server target, zero threads)
        // stays a non-session.
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(liveTargetWithThreads()));
    }

    @Test
    public void testFindFirstLiveClientThreadGetThreadsThrowsReturnsNull() throws Exception
    {
        IDebugTarget target = mock(IDebugTarget.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getThreads()).thenThrow(new org.eclipse.debug.core.DebugException(
            new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                "test", "threads unavailable"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findFirstLiveClientThread(target));
    }

    // === findFirstLiveServerThread — the positive server-side signal ===

    @Test
    public void testFindFirstLiveServerThreadFindsLiveServerTyped() throws Exception
    {
        IRuntimeDebugTargetThread serverThread = liveTypedThread(DebugTargetType.SERVER);
        IDebugTarget target =
            liveTargetWithThreads(liveTypedThread(DebugTargetType.CLIENT), serverThread);
        assertSame(serverThread, DebugServerTargetSupport.findFirstLiveServerThread(target));
    }

    @Test
    public void testFindFirstLiveServerThreadIgnoresClientUnknownAndPlainThreads() throws Exception
    {
        // Only a POSITIVELY server-typed live thread counts: client, unknown and
        // non-1C threads all classify client-side, so a thin client can never be
        // reclassified server-side by a model hiccup.
        IThread plain = mock(IThread.class); // non-1C, live (isTerminated() false)
        IDebugTarget target = liveTargetWithThreads(
            liveTypedThread(DebugTargetType.MANAGED_CLIENT),
            liveTypedThread(DebugTargetType.UNKNOWN), plain);
        assertNull(DebugServerTargetSupport.findFirstLiveServerThread(target));
    }

    @Test
    public void testFindFirstLiveServerThreadIgnoresTerminatedServerThread() throws Exception
    {
        // The thread must be LIVE: a dead server thread is not a server signal.
        IRuntimeDebugTargetThread dead = mock(IRuntimeDebugTargetThread.class);
        when(dead.isTerminated()).thenReturn(true);
        when(dead.getType()).thenReturn(DebugTargetType.SERVER);
        assertNull(DebugServerTargetSupport.findFirstLiveServerThread(liveTargetWithThreads(dead)));
    }

    @Test
    public void testFindFirstLiveServerThreadNullAndTerminatedTargetReturnNull()
    {
        assertNull(DebugServerTargetSupport.findFirstLiveServerThread(null));
        IDebugTarget terminated = mock(IDebugTarget.class);
        when(terminated.isTerminated()).thenReturn(true);
        assertNull(DebugServerTargetSupport.findFirstLiveServerThread(terminated));
    }

    // === isServerTarget — review item 8: launch-owned client sessions are NOT server targets ===
    //
    // The target manager creates an IRuntimeDebugClientTarget for EVERY debug session
    // (thin clients included), so the raw listDebugTargets() view overcaptured: plain
    // client sessions took the 100ms poll loop instead of the event-driven wait,
    // reported serverTarget:true on timeouts, and were listed twice in debug_status
    // under a synthesized ServerApplication.<app> id. These tests pin the narrowed
    // classification: a target is a server target only when NO live registered launch
    // resolves to it (its events never key into the launch-based registry — polling is
    // the only wait that works) OR it carries a live SERVER-typed thread.

    /** Mocks an EDT runtime-client launch configuration with the given name. */
    private static ILaunchConfiguration edtConfig(String name) throws Exception
    {
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getType()).thenReturn(type);
        when(config.getName()).thenReturn(name);
        return config;
    }

    /** Mocks a live launch with the given configuration and debug targets. */
    private static ILaunch liveLaunch(ILaunchConfiguration config, IDebugTarget... targets)
    {
        ILaunch launch = mock(ILaunch.class);
        when(launch.isTerminated()).thenReturn(false);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        when(launch.getDebugTargets()).thenReturn(targets);
        return launch;
    }

    @Test
    public void testIsServerTargetLaunchOwnedClientSessionIsNotServerTarget() throws Exception
    {
        // THE OVERCAPTURE CASE: a thin-client session owned by a live REGISTERED EDT
        // launch, with a live CLIENT-typed thread. Its suspend events key into the
        // launch-based registry, so it is NOT a server target — not listed, no poll
        // loop, no serverTarget:true, no duplicate ServerApplication.<app> entry.
        IDebugTarget client = liveTargetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch launch = liveLaunch(edtConfig("Cfg"), client); //$NON-NLS-1$
        when(client.getLaunch()).thenReturn(launch);
        assertFalse(DebugServerTargetSupport.isServerTarget(client, new ILaunch[] {launch}));
        // ...while the duplicate-session discriminator still sees its live client
        // thread — the detect scans the UNFILTERED enumeration (pinned below).
        assertNotNull(DebugServerTargetSupport.findFirstLiveClientThread(client));
    }

    @Test
    public void testIsServerTargetNoOwningLaunchIsServerTarget() throws Exception
    {
        // A target with NO owning Eclipse launch (e.g. an rphost debuggee) needs the
        // bridge: nothing keys its suspend events into the launch-based registry.
        IDebugTarget orphan = liveTargetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        when(orphan.getLaunch()).thenReturn(null);
        assertTrue(DebugServerTargetSupport.isServerTarget(orphan, new ILaunch[0]));
    }

    @Test
    public void testIsServerTargetUnregisteredOwningLaunchIsServerTarget() throws Exception
    {
        // A UI-started "Debug As" session: its ILaunch never surfaces in the launch
        // manager, so the launch-based machinery can't track it — the bridge (and
        // its poll-based wait) is the only mechanism that works. MUST stay a server
        // target even though target.getLaunch() answers a live EDT launch.
        IDebugTarget uiSession = liveTargetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch unregistered = liveLaunch(edtConfig("UiCfg"), uiSession); //$NON-NLS-1$
        when(uiSession.getLaunch()).thenReturn(unregistered);
        assertTrue(DebugServerTargetSupport.isServerTarget(uiSession, new ILaunch[0]));
    }

    @Test
    public void testIsServerTargetTerminatedOwningLaunchIsServerTarget() throws Exception
    {
        // A registered but TERMINATED owning launch no longer tracks the session.
        IDebugTarget target = liveTargetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch dead = mock(ILaunch.class);
        when(dead.isTerminated()).thenReturn(true);
        when(target.getLaunch()).thenReturn(dead);
        assertTrue(DebugServerTargetSupport.isServerTarget(target, new ILaunch[] {dead}));
    }

    @Test
    public void testIsServerTargetServerTypedThreadWinsOverLiveOwningLaunch() throws Exception
    {
        // A debug-mode standalone server launched through an Eclipse launch config:
        // live SERVER-typed thread → still a server target (its suspends are
        // observed by the poll bridge, not the launch-based registry).
        IDebugTarget server = liveTargetWithThreads(liveTypedThread(DebugTargetType.SERVER));
        ILaunch launch = liveLaunch(edtConfig("Srv"), server); //$NON-NLS-1$
        when(server.getLaunch()).thenReturn(launch);
        assertTrue(DebugServerTargetSupport.isServerTarget(server, new ILaunch[] {launch}));
    }

    @Test
    public void testIsServerTargetOwnershipViaGetDebugTargetsContainment() throws Exception
    {
        // The owning launch may resolve to the target through launch.getDebugTargets()
        // even when target.getLaunch() answers null — still a launch-owned client.
        IDebugTarget client = liveTargetWithThreads(liveTypedThread(DebugTargetType.CLIENT));
        when(client.getLaunch()).thenReturn(null);
        ILaunch launch = liveLaunch(edtConfig("Cfg"), client); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.isServerTarget(client, new ILaunch[] {launch}));
    }

    @Test
    public void testIsServerTargetNonEdtOwningLaunchIsServerTarget() throws Exception
    {
        // A registered live launch OUTSIDE the 1C/EDT namespace yields no registry
        // key (getApplicationIdFor == null): its events can't be keyed — keep the
        // bridge for such a target.
        ILaunchConfigurationType type = mock(ILaunchConfigurationType.class);
        when(type.getIdentifier()).thenReturn("org.eclipse.jdt.launching.localJavaApplication"); //$NON-NLS-1$
        ILaunchConfiguration config = mock(ILaunchConfiguration.class);
        when(config.getType()).thenReturn(type);
        when(config.getName()).thenReturn("JavaApp"); //$NON-NLS-1$
        IDebugTarget target = liveTargetWithThreads(liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch launch = liveLaunch(config, target);
        when(target.getLaunch()).thenReturn(launch);
        assertTrue(DebugServerTargetSupport.isServerTarget(target, new ILaunch[] {launch}));
    }

    @Test
    public void testIsServerTargetNullTargetIsFalse()
    {
        assertFalse(DebugServerTargetSupport.isServerTarget(null, new ILaunch[0]));
        assertFalse(DebugServerTargetSupport.isServerTarget(null));
    }

    @Test
    public void testIsServerTargetHeadlessNeverThrows()
    {
        // Public overload against the live launch manager (empty/absent headless):
        // a launch-less target classifies as a server target, never throws.
        IDebugTarget orphan = mock(IDebugTarget.class);
        when(orphan.isTerminated()).thenReturn(false);
        when(orphan.getLaunch()).thenReturn(null);
        assertTrue(DebugServerTargetSupport.isServerTarget(orphan));
    }

    // === findRuntimeClientDebugTarget over the UNFILTERED enumeration — D5/D7b no-regress ===
    //
    // Narrowing listServerTargets() must NOT narrow the duplicate-session detect: a
    // launch-owned client session is excluded from the server-target view by design,
    // yet it is exactly what the delegate's code-1003 "Debug session already exists"
    // check matches. The detect therefore scans the unfiltered manager enumeration;
    // these tests pin its matching half against explicit candidate lists.

    /** Test surface mirroring the 1C target's getApplication() for the reflective scan. */
    public interface TargetWithApplication extends IDebugTarget
    {
        Object getApplication();
    }

    /** Test surface mirroring IApplication (getId/getProject) for the reflective scan. */
    public interface ApplicationStub
    {
        String getId();

        ProjectStub getProject();
    }

    /** Test surface mirroring IProject#getName() for the reflective scan. */
    public interface ProjectStub
    {
        String getName();
    }

    private static TargetWithApplication targetBoundTo(String projectName, String appId,
        IThread... threads) throws Exception
    {
        ProjectStub project = mock(ProjectStub.class);
        when(project.getName()).thenReturn(projectName);
        ApplicationStub app = mock(ApplicationStub.class);
        when(app.getId()).thenReturn(appId);
        when(app.getProject()).thenReturn(project);
        TargetWithApplication target = mock(TargetWithApplication.class);
        when(target.isTerminated()).thenReturn(false);
        when(target.getApplication()).thenReturn(app);
        when(target.getThreads()).thenReturn(threads);
        return target;
    }

    private static DebugServerTargetSupport.ServerTarget asServerTarget(IDebugTarget target)
    {
        return new DebugServerTargetSupport.ServerTarget(target,
            "ServerApplication.App", "App", null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDuplicateDetectStillMatchesLaunchOwnedClientSession() throws Exception
    {
        // THE NO-REGRESS PIN (review item 8): a client session OWNED by a live
        // registered launch is NOT a server target (the filter excludes it) — but
        // the duplicate-session detect, fed the unfiltered enumeration, must still
        // match it on (project, delegate app id) + live CLIENT-typed thread.
        TargetWithApplication client = targetBoundTo("Proj", "app-id", //$NON-NLS-1$ //$NON-NLS-2$
            liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        ILaunch launch = liveLaunch(edtConfig("Cfg"), client); //$NON-NLS-1$
        when(client.getLaunch()).thenReturn(launch);
        assertFalse("the session the filter excludes...", //$NON-NLS-1$
            DebugServerTargetSupport.isServerTarget(client, new ILaunch[] {launch}));
        assertSame("...must still be found by the duplicate detect", client, //$NON-NLS-1$
            DebugServerTargetSupport.findRuntimeClientDebugTarget(
                Arrays.asList(asServerTarget(client)), "Proj", "app-id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDuplicateDetectStillSkipsServerSession() throws Exception
    {
        // Counterpart (D7b semantics unchanged): a debug-mode standalone server —
        // same project + app id, live SERVER-typed thread — keeps NOT matching, so
        // it is never short-circuited or terminated by restartIfRunning.
        TargetWithApplication server = targetBoundTo("Proj", "app-id", //$NON-NLS-1$ //$NON-NLS-2$
            liveTypedThread(DebugTargetType.SERVER));
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(
            Arrays.asList(asServerTarget(server)), "Proj", "app-id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDuplicateDetectRequiresProjectAndAppIdMatch() throws Exception
    {
        // The delegate's criterion is BOTH keys: same project AND same app id.
        TargetWithApplication client = targetBoundTo("Proj", "app-id", //$NON-NLS-1$ //$NON-NLS-2$
            liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        List<DebugServerTargetSupport.ServerTarget> list = Arrays.asList(asServerTarget(client));
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(
            list, "OtherProj", "app-id")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(
            list, "Proj", "other-app")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDuplicateDetectSkipsTerminatedTarget() throws Exception
    {
        TargetWithApplication client = targetBoundTo("Proj", "app-id", //$NON-NLS-1$ //$NON-NLS-2$
            liveTypedThread(DebugTargetType.MANAGED_CLIENT));
        when(client.isTerminated()).thenReturn(true);
        assertNull(DebugServerTargetSupport.findRuntimeClientDebugTarget(
            Arrays.asList(asServerTarget(client)), "Proj", "app-id")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // === matchesServerTargetId — the ONE id predicate shared by resolve() and debug_status ===

    @Test
    public void testMatchesServerTargetIdAllForms()
    {
        IDebugTarget target = mock(IDebugTarget.class); // getLaunch() null → no launch id
        DebugServerTargetSupport.ServerTarget st = new DebugServerTargetSupport.ServerTarget(
            target, "ServerApplication.App", "App", "http://srv:1550"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st, "ServerApplication.App")); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st, "App")); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st, "http://srv:1550")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(st, "Other")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(st, null));
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(st, "")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(null, "App")); //$NON-NLS-1$
    }

    @Test
    public void testMatchesServerTargetIdBareFormOfMintedIdWithoutApplicationName()
    {
        // Minted id keyed from the target NAME (no application, no URL): the bare
        // form must still match — debug_status's filter historically accepted it
        // (PREFIX + filterAppId) and the shared predicate keeps that.
        DebugServerTargetSupport.ServerTarget st = new DebugServerTargetSupport.ServerTarget(
            mock(IDebugTarget.class), "ServerApplication.TargetName", null, null); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st, "TargetName")); //$NON-NLS-1$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st, "ServerApplication.TargetName")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(st, "Other")); //$NON-NLS-1$
    }

    @Test
    public void testMatchesServerTargetIdOwningLaunchId() throws Exception
    {
        // The owning Eclipse launch's id (launch:<name> here) addresses the target
        // too — after unification debug_status filters by it exactly like resolve().
        IDebugTarget target = mock(IDebugTarget.class);
        ILaunchConfiguration config = edtConfig("MyCfg"); //$NON-NLS-1$
        ILaunch launch = mock(ILaunch.class);
        when(launch.getLaunchConfiguration()).thenReturn(config);
        when(target.getLaunch()).thenReturn(launch);
        DebugServerTargetSupport.ServerTarget st = new DebugServerTargetSupport.ServerTarget(
            target, "ServerApplication.App", "App", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(DebugServerTargetSupport.matchesServerTargetId(st,
            LaunchConfigUtils.LAUNCH_APP_ID_PREFIX + "MyCfg")); //$NON-NLS-1$
        assertFalse(DebugServerTargetSupport.matchesServerTargetId(st,
            LaunchConfigUtils.LAUNCH_APP_ID_PREFIX + "OtherCfg")); //$NON-NLS-1$
    }
}
