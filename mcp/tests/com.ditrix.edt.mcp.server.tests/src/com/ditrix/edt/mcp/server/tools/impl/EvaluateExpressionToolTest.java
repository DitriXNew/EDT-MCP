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
 * Tests for {@link EvaluateExpressionTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * that return before any live DebugPlugin access. The "stale frameRef" branch
 * is reachable headlessly: {@code DebugSessionRegistry} is an in-memory
 * singleton, so an empty registry yields a null frame and the tool returns the
 * stale-frame error before touching the debug expression manager. Actual
 * evaluation needs a suspended debug session and is covered by the E2E suite.
 */
public class EvaluateExpressionToolTest
{
    @Test
    public void testName()
    {
        assertEquals("evaluate_expression", new EvaluateExpressionTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(EvaluateExpressionTool.NAME, new EvaluateExpressionTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new EvaluateExpressionTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new EvaluateExpressionTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new EvaluateExpressionTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"frameRef\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"expression\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingFrameRef()
    {
        Map<String, String> params = new HashMap<>();
        params.put("expression", "1 + 1"); //$NON-NLS-1$ //$NON-NLS-2$
        // frameRef omitted -> defaults to -1 -> "frameRef is required"
        String result = new EvaluateExpressionTool().execute(params);
        assertTrue(result.contains("frameRef is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingExpression()
    {
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new EvaluateExpressionTool().execute(params);
        assertTrue(result.contains("expression is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaleFrameRefWhenNoLiveSession()
    {
        // A positive frameRef + valid expression, but no suspended debug session
        // is registered: the in-memory registry returns a null frame, so the tool
        // returns the stale-frame error before any DebugPlugin access.
        Map<String, String> params = new HashMap<>();
        params.put("frameRef", "999999"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("expression", "1 + 1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new EvaluateExpressionTool().execute(params);
        assertTrue(result.contains("stale frameRef")); //$NON-NLS-1$
    }
}
