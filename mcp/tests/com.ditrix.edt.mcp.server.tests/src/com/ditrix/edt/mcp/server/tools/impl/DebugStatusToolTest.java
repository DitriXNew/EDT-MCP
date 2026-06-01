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
 * Tests for {@link DebugStatusTool}.
 * <p>
 * This tool performs no argument validation: the single optional
 * {@code applicationId} param is treated as "no filter" when absent, and the
 * first action in {@code execute()} is a live {@code DebugPlugin} lookup. So
 * the headless surface is limited to the static contract; the status payload is
 * covered by the E2E suite.
 */
public class DebugStatusToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_status", new DebugStatusTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugStatusTool.NAME, new DebugStatusTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugStatusTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugStatusTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugStatusTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }
}
