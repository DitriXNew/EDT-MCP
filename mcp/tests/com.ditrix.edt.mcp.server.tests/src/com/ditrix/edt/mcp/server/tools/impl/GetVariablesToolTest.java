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
 * Tests for {@link GetVariablesTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * "stale" branches: with a positive {@code frameRef} (or {@code threadId}) but
 * no suspended session, the in-memory {@code DebugSessionRegistry} returns a
 * null frame/thread via a plain map lookup, so the tool reports the stale
 * reference before any live {@code DebugPlugin} access. The auto-resolution
 * fallback (neither frameRef nor threadId) goes through the live launch manager
 * and, together with actual variable reads, is covered by the E2E suite.
 */
public class GetVariablesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_variables", new GetVariablesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetVariablesTool.NAME, new GetVariablesTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetVariablesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetVariablesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetVariablesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"frameRef\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"frameIndex\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expandPath\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testStaleFrameRefWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        // frameRef > 0 but no suspended session: the in-memory registry returns a
        // null frame, so the tool reports a stale frameRef before any live access.
        String result = new GetVariablesTool().execute(params);
        assertTrue(result.contains("stale frameRef")); //$NON-NLS-1$
    }

    @Test
    public void testStaleThreadIdWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("threadId", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        // threadId > 0 (no frameRef): the in-memory registry returns a null thread,
        // so the tool reports a stale threadId before any live access.
        String result = new GetVariablesTool().execute(params);
        assertTrue(result.contains("stale threadId")); //$NON-NLS-1$
    }
}
