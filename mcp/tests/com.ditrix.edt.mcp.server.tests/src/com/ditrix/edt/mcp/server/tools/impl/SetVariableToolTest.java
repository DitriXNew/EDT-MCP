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
 * Tests for {@link SetVariableTool}.
 * <p>
 * Covers tool metadata, the input schema, and the early-validation branches that
 * are reachable headlessly (without a live suspended debug session): the required
 * {@code variableName}/{@code value} guards, and the shared frame resolver's
 * "stale frameRef" branch — with a positive {@code frameRef} but no suspended
 * session the in-memory {@code DebugSessionRegistry} returns a null frame, so the
 * tool reports the stale reference before any live {@code DebugPlugin} access. The
 * actual value modification (supportsValueModification / verifyValue / setValue)
 * needs a real suspended frame and is covered by the E2E suite.
 */
public class SetVariableToolTest
{
    @Test
    public void testName()
    {
        assertEquals("set_variable", new SetVariableTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SetVariableTool.NAME, new SetVariableTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new SetVariableTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SetVariableTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SetVariableTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"frameRef\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"threadId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"frameIndex\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"variableName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"value\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingVariableNameIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("value", "42"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("variableName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingValueIsRejected()
    {
        Map<String, String> params = new HashMap<>();
        params.put("variableName", "МояПеременная"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("value is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaleFrameRefWhenNoLiveSession()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("variableName", "МояПеременная"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("value", "42"); //$NON-NLS-1$ //$NON-NLS-2$
        // frameRef > 0 but no suspended session: the shared DebugFrameResolver reads a
        // null frame from the in-memory registry and reports a stale frameRef before
        // any live access.
        String result = new SetVariableTool().execute(params);
        assertTrue(result.contains("stale frameRef")); //$NON-NLS-1$
    }
}
