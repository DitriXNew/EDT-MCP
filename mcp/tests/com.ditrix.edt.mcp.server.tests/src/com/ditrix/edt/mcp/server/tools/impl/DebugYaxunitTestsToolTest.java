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

import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tests for {@link DebugYaxunitTestsTool}.
 *
 * Mirrors {@link RunYaxunitTestsToolTest} — verifies tool identity, response
 * type, schema, and parameter validation at the entry point. The actual
 * Eclipse launch path is out of scope (needs runtime).
 */
public class DebugYaxunitTestsToolTest
{
    @Test
    public void testToolName()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        assertEquals("debug_yaxunit_tests", tool.getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        DebugYaxunitTestsTool tool = new DebugYaxunitTestsTool();
        assertEquals(IMcpTool.ResponseType.JSON, tool.getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue("description should not be empty", desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresExpectedFields()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"applicationId\""));
        assertTrue(schema.contains("\"extensions\""));
        assertTrue(schema.contains("\"modules\""));
        assertTrue(schema.contains("\"tests\""));
        assertTrue("schema must include updateBeforeLaunch (auto-chain switch)",
            schema.contains("\"updateBeforeLaunch\""));
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "some-app-id");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue("must mention projectName", result.contains("projectName"));
        // JSON tool: error field is "error", not "**Error:**"
        assertTrue("must indicate error", result.contains("\"error\""));
    }

    @Test
    public void testExecuteMissingApplicationId()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue("must mention applicationId", result.contains("applicationId"));
        assertTrue("must indicate error", result.contains("\"error\""));
    }

    @Test
    public void testExecuteEmptyParams()
    {
        IMcpTool tool = new DebugYaxunitTestsTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue("must indicate error", result.contains("\"error\""));
    }
}
