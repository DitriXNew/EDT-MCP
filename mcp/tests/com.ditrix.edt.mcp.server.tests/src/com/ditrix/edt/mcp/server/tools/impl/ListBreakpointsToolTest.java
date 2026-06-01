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
 * Tests for {@link ListBreakpointsTool}.
 * <p>
 * This tool performs no argument validation: {@code projectName} is optional
 * and the first action in {@code execute()} is a live {@code DebugPlugin}
 * lookup. So the headless surface is limited to the static contract (name,
 * response type, schema); the listing behaviour is covered by the E2E suite.
 */
public class ListBreakpointsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_breakpoints", new ListBreakpointsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListBreakpointsTool.NAME, new ListBreakpointsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ListBreakpointsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListBreakpointsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListBreakpointsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }
}
