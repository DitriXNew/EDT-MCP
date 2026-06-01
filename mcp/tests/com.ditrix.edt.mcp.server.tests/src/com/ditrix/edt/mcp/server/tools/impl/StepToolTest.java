/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link StepTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live debug-model access. threadId/kind validation is
 * pure; the "stale threadId" branch is reachable headlessly because
 * {@code DebugSessionRegistry} is an in-memory singleton whose
 * {@code getThread(id)} is a map lookup returning {@code null} on an empty
 * registry. Actual stepping needs a suspended debug session and is covered by
 * the E2E suite.
 */
public class StepToolTest
{
    @Test
    public void testName()
    {
        assertEquals("step", new StepTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(StepTool.NAME, new StepTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new StepTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new StepTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new StepTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"kind\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingThreadId()
    {
        Map<String, String> params = new HashMap<>();
        params.put("kind", "over"); //$NON-NLS-1$ //$NON-NLS-2$
        // threadId omitted -> defaults to -1 -> "threadId is required"
        String result = new StepTool().execute(params);
        assertTrue(result.contains("threadId is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingKind()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new StepTool().execute(params);
        assertTrue(result.contains("kind is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("kind", "over"); //$NON-NLS-1$ //$NON-NLS-2$
        // No suspended session registered -> the in-memory registry returns a null
        // thread -> stale-frame/thread error before any live DebugPlugin access.
        String result = new StepTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }

    // ==================== Timeout clamping (pure, no live debug session) ====================

    @Test
    public void testClampTimeoutNormalValuePassesThrough()
    {
        assertEquals(20, StepTool.clampTimeout(20));
    }

    @Test
    public void testClampTimeoutAboveMaxIsCapped()
    {
        // Unbounded value would block a worker thread for hours -> capped to 600s.
        assertEquals(600, StepTool.clampTimeout(999999));
    }

    @Test
    public void testClampTimeoutAtMaxIsUnchanged()
    {
        assertEquals(600, StepTool.clampTimeout(600));
    }

    @Test
    public void testClampTimeoutBelowOneIsRaisedToOne()
    {
        assertEquals(1, StepTool.clampTimeout(0));
        assertEquals(1, StepTool.clampTimeout(-5));
    }
}
