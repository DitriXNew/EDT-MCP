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
 * Tests for {@link GetProfilingResultsTool}.
 * <p>
 * This tool performs no argument validation; its only pre-EDT error returns are
 * OSGi bundle-presence guards ({@code Platform.getBundle(...)==null}) whose
 * outcome depends on which 1C profiling bundles the test runtime resolves — so
 * asserting on them would be environment-dependent. The headless surface is
 * therefore limited to the static contract; the profiling readout is covered by
 * the E2E suite.
 */
public class GetProfilingResultsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_profiling_results", new GetProfilingResultsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetProfilingResultsTool.NAME, new GetProfilingResultsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetProfilingResultsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetProfilingResultsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetProfilingResultsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"moduleFilter\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"minFrequency\"")); //$NON-NLS-1$
        // applicationId surfaces the on/off profiling state for a specific session.
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    @Test
    public void testDescriptionMentionsActiveState()
    {
        // The tool surfaces whether profiling is currently active so a client can
        // tell a stop is pending; the description must advertise it.
        assertTrue(new GetProfilingResultsTool().getDescription().contains("active")); //$NON-NLS-1$
    }
}
