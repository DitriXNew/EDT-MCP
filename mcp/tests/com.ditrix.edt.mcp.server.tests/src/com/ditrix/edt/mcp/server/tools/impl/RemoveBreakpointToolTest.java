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
 * Tests for {@link RemoveBreakpointTool}.
 * <p>
 * Covers tool metadata, the input schema, and the one argument-validation
 * branch that returns before any live workspace/debug access: when neither a
 * breakpointId nor module+lineNumber coordinates are provided. Actual marker/
 * breakpoint removal needs a live workspace and is covered by the E2E suite.
 */
public class RemoveBreakpointToolTest
{
    @Test
    public void testName()
    {
        assertEquals("remove_breakpoint", new RemoveBreakpointTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RemoveBreakpointTool.NAME, new RemoveBreakpointTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new RemoveBreakpointTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RemoveBreakpointTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RemoveBreakpointTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"breakpointId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"module\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"lineNumber\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingAllCoordinates()
    {
        // no breakpointId (defaults to -1), no module/lineNumber coordinates
        Map<String, String> params = new HashMap<>();
        String result = new RemoveBreakpointTool().execute(params);
        assertTrue(result.contains("Provide either breakpointId or module+lineNumber")); //$NON-NLS-1$
    }
}
