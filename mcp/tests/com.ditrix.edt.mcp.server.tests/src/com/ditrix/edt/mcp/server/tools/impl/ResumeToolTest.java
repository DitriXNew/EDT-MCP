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
 * Tests for {@link ResumeTool}.
 * <p>
 * Covers tool metadata, the input schema, and the single headless-reachable
 * validation branch: with a positive threadId but no suspended session, the
 * in-memory {@code DebugSessionRegistry} returns a null thread and the tool
 * reports a stale threadId before any live DebugPlugin access (the listener
 * registration it performs first is null-safe headlessly). The
 * applicationId-based resume paths query the live launch manager and are
 * covered by the E2E suite.
 */
public class ResumeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("resume", new ResumeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ResumeTool.NAME, new ResumeTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ResumeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ResumeTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ResumeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ResumeTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }
}
