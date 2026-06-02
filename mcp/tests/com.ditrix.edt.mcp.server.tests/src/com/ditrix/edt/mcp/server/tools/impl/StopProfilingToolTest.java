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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link StopProfilingTool}.
 * <p>
 * Covers tool metadata, the input schema, the {@code applicationId}
 * required-argument validation, and the idempotent-when-not-active contract:
 * stopping a session that is not profiling returns a benign success (not an
 * error) and does <em>not</em> toggle profiling. The real toggle-off goes through
 * the live debug model and the EDT profiling bundles and is covered by the E2E suite.
 */
public class StopProfilingToolTest
{
    @Before
    public void resetState()
    {
        StartProfilingTool.clearStateForTests();
    }

    @After
    public void clearState()
    {
        StartProfilingTool.clearStateForTests();
    }

    @Test
    public void testName()
    {
        assertEquals("stop_profiling", new StopProfilingTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(StopProfilingTool.NAME, new StopProfilingTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new StopProfilingTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new StopProfilingTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new StopProfilingTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live debug session needed) ====================

    @Test
    public void testMissingApplicationId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new StopProfilingTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }

    // ==================== Idempotent stop when not active ====================

    @Test
    public void testStopWhenNotActiveReturnsBenignSuccess()
    {
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "never-started"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new StopProfilingTool().execute(params);

        // Benign: success, not an error, and explicitly reports not active / nothing to stop.
        assertTrue(result.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(result.contains("\"active\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"stopped\":false")); //$NON-NLS-1$
        assertTrue(result.contains("not active")); //$NON-NLS-1$
    }
}
