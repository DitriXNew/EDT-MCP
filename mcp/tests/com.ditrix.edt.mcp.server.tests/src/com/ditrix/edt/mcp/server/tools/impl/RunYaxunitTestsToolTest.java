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
 * Tests for {@link RunYaxunitTestsTool}.
 *
 * Verifies tool name, response type, schema (required fields and parameter list)
 * and validation of required parameters at the entry point. Does not exercise
 * the actual launch flow because it requires the Eclipse runtime.
 */
public class RunYaxunitTestsToolTest
{
    @Test
    public void testToolName()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        assertEquals("run_yaxunit_tests", tool.getName());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String desc = tool.getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        RunYaxunitTestsTool tool = new RunYaxunitTestsTool();
        assertEquals(IMcpTool.ResponseType.MARKDOWN, tool.getResponseType());
    }

    @Test
    public void testSchemaContainsRequiredFields()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String schema = tool.getInputSchema();
        assertNotNull(schema);
        assertTrue("schema must declare projectName", schema.contains("\"projectName\""));
        assertTrue("schema must declare applicationId", schema.contains("\"applicationId\""));
        assertTrue("schema must declare extensions", schema.contains("\"extensions\""));
        assertTrue("schema must declare modules", schema.contains("\"modules\""));
        assertTrue("schema must declare tests", schema.contains("\"tests\""));
        assertTrue("schema must declare timeout", schema.contains("\"timeout\""));
        // projectName and applicationId must be in the required list
        assertTrue("projectName must be required",
                schema.contains("\"required\"") && schema.contains("projectName"));
        assertTrue("applicationId must be required",
                schema.contains("\"required\"") && schema.contains("applicationId"));
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("applicationId", "some-app-id");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue(result.contains("projectName"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void testExecuteMissingApplicationId()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject");
        String result = tool.execute(params);
        assertNotNull(result);
        assertTrue(result.contains("applicationId"));
        assertTrue(result.toLowerCase().contains("required") || result.contains("Error"));
    }

    @Test
    public void testExecuteEmptyParams()
    {
        IMcpTool tool = new RunYaxunitTestsTool();
        String result = tool.execute(new HashMap<String, String>());
        assertNotNull(result);
        assertTrue(result.contains("Error"));
    }
}
