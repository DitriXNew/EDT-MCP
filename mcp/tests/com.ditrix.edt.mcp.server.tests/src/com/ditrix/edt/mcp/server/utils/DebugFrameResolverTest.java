/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Tests for {@link DebugFrameResolver} — the shared frame-resolution policy
 * extracted from {@code GetVariablesTool}. Each error branch must return the
 * EXACT same error JSON the tool produced before the extraction; the expected
 * value is rebuilt here through {@link ToolResult#error(String)} (the same
 * factory the resolver uses) so the assertion proves byte-identity, not merely a
 * substring.
 * <p>
 * The headless-reachable branches are exercised directly against the in-memory
 * {@link DebugSessionRegistry} (a plain map lookup returns a null frame/thread
 * with no live {@code DebugPlugin} access) and against mocked {@link IThread}s
 * injected into the registry. The auto-resolution fallback goes through the live
 * launch manager, which yields no session headless ({@code DebugTargetResolver
 * .resolve(null)} returns {@code null}), so it deterministically produces the
 * "no single suspended debug session" error here.
 * <p>
 * <b>Coverage boundary (e2e-only):</b> five of the six error strings are pinned
 * byte-identically below. The sixth — {@code "suspended thread has no stack
 * frames"} ({@code DebugFrameResolver.resolveByAutoSession}) — is intentionally
 * NOT pinned here because it is not headlessly reachable: it fires only when
 * auto-resolution finds a live suspended session whose thread reports zero stack
 * frames, but headless {@code DebugTargetResolver.resolve(null)} returns
 * {@code null} (no live launch manager), so the resolver short-circuits at the
 * "no single suspended debug session" branch ({@link #testNoAutoSessionReturnsExactError})
 * before that code can run. Reaching it would require a live launch manager and a
 * real suspended-with-empty-stack session, so this one branch is verified by the
 * e2e suite rather than this unit test — an inherent gap, not an omission.
 */
public class DebugFrameResolverTest
{
    /** Keep the shared singleton clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    // ==================== error branches (byte-identical JSON) ====================

    @Test
    public void testStaleFrameRefReturnsExactError() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // frameRef > 0 but no suspended session: the registry returns a null frame.
        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, 999999L, -1L, 0);
        assertNull("stale frameRef must not resolve a frame", res.frame); //$NON-NLS-1$
        assertEquals(ToolResult.error("stale frameRef — call wait_for_break again").toJson(), //$NON-NLS-1$
            res.error);
    }

    @Test
    public void testStaleThreadIdReturnsExactError() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // threadId > 0 (no frameRef): the registry returns a null thread.
        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, -1L, 999999L, 0);
        assertNull("stale threadId must not resolve a frame", res.frame); //$NON-NLS-1$
        assertEquals(ToolResult.error("stale threadId — call wait_for_break again").toJson(), //$NON-NLS-1$
            res.error);
    }

    @Test
    public void testFrameIndexOutOfRangeReturnsExactError() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        when(thread.getStackFrames())
            .thenReturn(new IStackFrame[] {mock(IStackFrame.class), mock(IStackFrame.class)});
        registry.injectSuspend("launch:Cfg", thread); //$NON-NLS-1$
        long threadId = registry.getSnapshot("launch:Cfg").threadId; //$NON-NLS-1$

        // frameIndex 5 with a 2-frame thread → out of range (0..1).
        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, -1L, threadId, 5);
        assertNull("out-of-range index must not resolve a frame", res.frame); //$NON-NLS-1$
        assertEquals(ToolResult.error("frameIndex out of range (0..1)").toJson(), res.error); //$NON-NLS-1$
    }

    @Test
    public void testNegativeFrameIndexReturnsExactError() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        when(thread.getStackFrames())
            .thenReturn(new IStackFrame[] {mock(IStackFrame.class), mock(IStackFrame.class)});
        registry.injectSuspend("launch:Cfg", thread); //$NON-NLS-1$
        long threadId = registry.getSnapshot("launch:Cfg").threadId; //$NON-NLS-1$

        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, -1L, threadId, -1);
        assertNull("a negative index must not resolve a frame", res.frame); //$NON-NLS-1$
        assertEquals(ToolResult.error("frameIndex out of range (0..1)").toJson(), res.error); //$NON-NLS-1$
    }

    @Test
    public void testNoAutoSessionReturnsExactError() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        // Neither frameRef nor threadId: auto-resolution finds no live session
        // headless (DebugTargetResolver.resolve(null) is null).
        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, -1L, -1L, 0);
        assertNull("no auto session must not resolve a frame", res.frame); //$NON-NLS-1$
        assertEquals(
            ToolResult.error("Provide frameRef or threadId — no single suspended debug " //$NON-NLS-1$
                + "session available for auto-resolution. Call wait_for_break first.").toJson(), //$NON-NLS-1$
            res.error);
    }

    // ==================== success branches ====================

    @Test
    public void testResolvesRegisteredFrameByRef() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IStackFrame frame = mock(IStackFrame.class);
        long frameRef = registry.registerFrame(frame);

        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, frameRef, -1L, 0);
        assertNull("a successful resolution carries no error", res.error); //$NON-NLS-1$
        assertSame("the frame registered under frameRef must be returned", frame, res.frame); //$NON-NLS-1$
    }

    @Test
    public void testResolvesFrameByThreadIdAndIndex() throws DebugException
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IStackFrame frame0 = mock(IStackFrame.class);
        IStackFrame frame1 = mock(IStackFrame.class);
        IThread thread = mock(IThread.class);
        when(thread.getStackFrames()).thenReturn(new IStackFrame[] {frame0, frame1});
        registry.injectSuspend("launch:Cfg", thread); //$NON-NLS-1$
        long threadId = registry.getSnapshot("launch:Cfg").threadId; //$NON-NLS-1$

        DebugFrameResolver.Resolution res = DebugFrameResolver.resolve(registry, -1L, threadId, 1);
        assertNull("a successful resolution carries no error", res.error); //$NON-NLS-1$
        assertSame("the frameIndex-th frame of the thread must be returned", frame1, res.frame); //$NON-NLS-1$
    }
}
