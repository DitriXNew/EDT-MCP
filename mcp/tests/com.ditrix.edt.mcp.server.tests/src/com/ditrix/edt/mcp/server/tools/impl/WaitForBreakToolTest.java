/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

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
}
