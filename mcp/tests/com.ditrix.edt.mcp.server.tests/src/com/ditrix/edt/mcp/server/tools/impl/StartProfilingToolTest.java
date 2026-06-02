/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link StartProfilingTool}.
 * <p>
 * Covers tool metadata, the input schema, the {@code applicationId} required-argument
 * validation, and the start-only / idempotent contract: when profiling is already
 * marked active for an applicationId, {@code start_profiling} returns an
 * "already profiling" result <em>without</em> toggling (which would otherwise switch
 * profiling off). Resolving the active debug target and the real toggle go through the
 * live debug model and the EDT profiling bundles and are covered by the E2E suite.
 */
public class StartProfilingToolTest
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
    public void testDescriptionMentionsStop()
    {
        // start_profiling must point the client at the deterministic stop tool.
        assertTrue(new StartProfilingTool().getDescription().contains("stop_profiling")); //$NON-NLS-1$
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

    // ==================== Start-only / idempotent state contract ====================

    @Test
    public void testAlreadyActiveReturnsIdempotentResultWithoutToggling()
    {
        String appId = "app-1"; //$NON-NLS-1$
        StartProfilingTool.markActive(appId);

        Map<String, String> params = new HashMap<>();
        params.put("applicationId", appId); //$NON-NLS-1$
        String result = new StartProfilingTool().execute(params);

        // Benign idempotent result, not an error, and profiling stays active.
        assertTrue(result.contains("already active")); //$NON-NLS-1$
        assertTrue(result.contains("\"started\":false")); //$NON-NLS-1$
        assertTrue(result.contains("\"active\":true")); //$NON-NLS-1$
        assertTrue(StartProfilingTool.isProfilingActive(appId));
    }

    @Test
    public void testStateHelpersTrackPerApplicationId()
    {
        assertFalse(StartProfilingTool.isProfilingActive("x")); //$NON-NLS-1$
        assertFalse(StartProfilingTool.isAnyProfilingActive());

        StartProfilingTool.markActive("x"); //$NON-NLS-1$
        assertTrue(StartProfilingTool.isProfilingActive("x")); //$NON-NLS-1$
        assertTrue(StartProfilingTool.isAnyProfilingActive());
        assertFalse(StartProfilingTool.isProfilingActive("y")); //$NON-NLS-1$

        StartProfilingTool.markInactive("x"); //$NON-NLS-1$
        assertFalse(StartProfilingTool.isProfilingActive("x")); //$NON-NLS-1$
        assertFalse(StartProfilingTool.isAnyProfilingActive());
    }
}
