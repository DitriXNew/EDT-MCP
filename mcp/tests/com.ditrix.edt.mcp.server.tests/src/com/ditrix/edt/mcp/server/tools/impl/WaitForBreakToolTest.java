/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

/**
 * Tests for {@link WaitForBreakTool}.
 * <p>
 * This tool has no argument-validation branch reachable before live EDT: after
 * reading params it immediately registers a debug listener (live
 * {@code DebugPlugin}), and the only "applicationId is required" return is
 * gated behind a live launch-manager enumeration, not the in-memory registry.
 * So the headless surface is limited to the static contract; the wait behaviour
 * is covered by the E2E suite.
 */
public class WaitForBreakToolTest
{
    @Test
    public void testName()
    {
        assertEquals("wait_for_break", new WaitForBreakTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(WaitForBreakTool.NAME, new WaitForBreakTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new WaitForBreakTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new WaitForBreakTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new WaitForBreakTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"timeout\"")); //$NON-NLS-1$
    }

    // ==================== Timeout clamping (pure, no live debug session) ====================

    @Test
    public void testClampTimeoutNormalValuePassesThrough()
    {
        assertEquals(45, WaitForBreakTool.clampTimeout(45));
    }

    @Test
    public void testClampTimeoutAboveMaxIsCapped()
    {
        // Unbounded value would block a worker thread for hours -> capped to 600s.
        assertEquals(600, WaitForBreakTool.clampTimeout(999999));
    }

    @Test
    public void testClampTimeoutAtMaxIsUnchanged()
    {
        assertEquals(600, WaitForBreakTool.clampTimeout(600));
    }

    @Test
    public void testClampTimeoutBelowOneIsRaisedToOne()
    {
        assertEquals(1, WaitForBreakTool.clampTimeout(0));
        assertEquals(1, WaitForBreakTool.clampTimeout(-5));
    }

    // ==================== Snapshot (hit) response: serverTarget flag ====================
    // buildSnapshotResponse is exercised headlessly: injectSuspend registers a
    // mocked thread in the in-memory registry, no live DebugPlugin involved.

    /** Keep the shared singleton registry clean between cases that mutate it. */
    @After
    public void clearRegistry()
    {
        DebugSessionRegistry.get().clear();
    }

    @Test
    public void testHitResponseCarriesServerTargetWhenResolvedViaServerPath() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        when(thread.getName()).thenReturn("server thread"); //$NON-NLS-1$
        when(thread.getStackFrames()).thenReturn(new IStackFrame[0]);
        String appId = "ServerApplication.TestApp"; //$NON-NLS-1$
        registry.injectSuspend(appId, thread);
        DebugSessionRegistry.SuspendSnapshot snapshot = registry.getSnapshot(appId);
        assertNotNull(snapshot);

        String json = WaitForBreakTool.buildSnapshotResponse(snapshot, registry, appId, false, true);

        assertTrue(json.contains("\"hit\":true")); //$NON-NLS-1$
        // serverTarget is declared in the output schema; a hit resolved via the
        // server-target path must report it, just like the timeout response does.
        assertTrue(json.contains("\"serverTarget\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"applicationId\":\"ServerApplication.TestApp\"")); //$NON-NLS-1$
    }

    @Test
    public void testHitResponseOmitsServerTargetForLaunchThread() throws Exception
    {
        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.clear();
        IThread thread = mock(IThread.class);
        when(thread.getName()).thenReturn("client thread"); //$NON-NLS-1$
        when(thread.getStackFrames()).thenReturn(new IStackFrame[0]);
        String appId = "launch:TestCfg"; //$NON-NLS-1$
        registry.injectSuspend(appId, thread);

        String json = WaitForBreakTool.buildSnapshotResponse(registry.getSnapshot(appId), registry,
            appId, false, false);

        assertTrue(json.contains("\"hit\":true")); //$NON-NLS-1$
        // Same emit-only-when-true convention as the timeout path.
        assertFalse(json.contains("serverTarget")); //$NON-NLS-1$
    }
}
