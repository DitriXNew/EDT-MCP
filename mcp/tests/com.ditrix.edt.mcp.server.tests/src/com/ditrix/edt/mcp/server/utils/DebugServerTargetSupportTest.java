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
}
