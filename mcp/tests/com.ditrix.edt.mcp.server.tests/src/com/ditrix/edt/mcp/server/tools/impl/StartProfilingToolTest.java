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
 * Tests for {@link StartProfilingTool}.
 * <p>
 * Covers tool metadata, the input schema, and the pure {@code applicationId}
 * required-argument validation that returns before any live access. Resolving
 * the active debug target and toggling profiling go through the live debug
 * model and the EDT profiling bundles and are covered by the E2E suite.
 */
public class StartProfilingToolTest
{
    @Test
    public void testName()
    {
        assertEquals("start_profiling", new StartProfilingTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(StartProfilingTool.NAME, new StartProfilingTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new StartProfilingTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new StartProfilingTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new StartProfilingTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingApplicationId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new StartProfilingTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }
}
