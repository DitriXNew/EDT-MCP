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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.Toolsets;
import com.ditrix.edt.mcp.server.tools.ToolsetState;

/**
 * Tests for {@link EnableToolsetTool}. The mutation targets only the in-memory
 * {@code ToolsetState} + the static catalogue, so {@code execute()} runs without
 * EDT services. {@code ToolsetState} is a singleton, so it is reset around each test.
 */
public class EnableToolsetToolTest
{
    @Before
    @After
    public void reset()
    {
        ToolsetState.getInstance().reset();
    }

    @Test
    public void testNameConstant()
    {
        assertEquals("enable_toolset", new EnableToolsetTool().getName()); //$NON-NLS-1$
        assertEquals(EnableToolsetTool.NAME, new EnableToolsetTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new EnableToolsetTool().getResponseType());
    }

    @Test
    public void testSchemaDeclaresToolsetsRequired()
    {
        String schema = new EnableToolsetTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("toolsets")); //$NON-NLS-1$
        assertTrue(schema.contains("disable")); //$NON-NLS-1$
        // toolsets is the required parameter.
        assertTrue(schema.contains("\"required\":[\"toolsets\"]")); //$NON-NLS-1$
    }

    @Test
    public void testMissingToolsetsErrorsActionably()
    {
        String json = new EnableToolsetTool().execute(Collections.emptyMap());
        assertNotNull(json);
        assertTrue(json.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(json.contains("toolsets is required")); //$NON-NLS-1$
        assertTrue(json.contains("list_toolsets")); //$NON-NLS-1$
    }

    @Test
    public void testAllInvalidErrorsAndNamesValuesAndDiscoveryTool()
    {
        Map<String, String> params = new HashMap<>();
        params.put("toolsets", "no_such_toolset_zzz"); //$NON-NLS-1$ //$NON-NLS-2$
        String json = new EnableToolsetTool().execute(params);
        assertTrue(json.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(json.contains("no_such_toolset_zzz")); //$NON-NLS-1$
        assertTrue(json.contains("list_toolsets")); //$NON-NLS-1$
    }

    @Test
    public void testEnableValidToolsetRecordsAndUpdatesState()
    {
        Map<String, String> params = new HashMap<>();
        params.put("toolsets", Toolsets.CODE); //$NON-NLS-1$
        String json = new EnableToolsetTool().execute(params);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"action\":\"enabled\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"applied\"")); //$NON-NLS-1$
        assertTrue(json.contains(Toolsets.CODE));
        // The runtime state changed regardless of the disclosure preference.
        assertTrue(ToolsetState.getInstance().isVisible(Toolsets.CODE));
    }

    @Test
    public void testCoreIsIgnoredNotApplied()
    {
        Map<String, String> params = new HashMap<>();
        params.put("toolsets", Toolsets.CORE); //$NON-NLS-1$
        String json = new EnableToolsetTool().execute(params);
        // Toggling core is meaningless but not an error.
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"ignored\"")); //$NON-NLS-1$
        assertTrue(json.contains(Toolsets.CORE));
    }

    @Test
    public void testDisableFlagHidesToolset()
    {
        ToolsetState.getInstance().enable(Toolsets.DEBUG);
        Map<String, String> params = new HashMap<>();
        params.put("toolsets", Toolsets.DEBUG); //$NON-NLS-1$
        params.put("disable", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        String json = new EnableToolsetTool().execute(params);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"action\":\"disabled\"")); //$NON-NLS-1$
        assertFalse(ToolsetState.getInstance().isVisible(Toolsets.DEBUG));
    }
}
