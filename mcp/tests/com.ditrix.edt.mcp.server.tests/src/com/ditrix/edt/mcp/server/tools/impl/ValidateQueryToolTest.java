/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ValidateQueryTool}.
 */
public class ValidateQueryToolTest
{
    @Test
    public void testName()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        assertEquals("validate_query", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testResponseType()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        assertEquals(ResponseType.JSON, tool.getResponseType());
    }

    @Test
    public void testInputSchemaContainsRequiredParameters()
    {
        ValidateQueryTool tool = new ValidateQueryTool();
        String schema = tool.getInputSchema();

        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"queryText\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"dcsMode\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"required\":[\"projectName\",\"queryText\"]")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectName()
    {
        ValidateQueryTool tool = new ValidateQueryTool();

        Map<String, String> params = new HashMap<>();
        params.put("queryText", "SELECT 1"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingQueryText()
    {
        ValidateQueryTool tool = new ValidateQueryTool();

        Map<String, String> params = new HashMap<>();
        params.put("projectName", "AnyProject"); //$NON-NLS-1$ //$NON-NLS-2$

        String result = tool.execute(params);

        assertNotNull(result);
        assertTrue(result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result.contains("queryText is required")); //$NON-NLS-1$
    }

    // ==================== buildResult (migrated to ToolResult) ====================
    // The result JSON is built by the shared ToolResult/Gson path. Gson emits
    // compact ("key":value, no spaces) and escapes >, <, & as \\uXXXX, so the
    // assertions below use delimiter-free substrings.

    @Test
    public void testBuildResultValidWhenNoIssues()
    {
        String json = ValidateQueryTool.buildResult(List.of(), false);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"infoCount\":0")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"issues\":[]")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultReportsError()
    {
        ValidateQueryTool.QueryIssue err =
            new ValidateQueryTool.QueryIssue("ERROR", "Field 'Foo' not found", 1, 8, 7); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(err), true);
        assertTrue(json.contains("\"success\":true")); // tool ran; query is what's invalid //$NON-NLS-1$
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"errorCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"dcsMode\":true")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"ERROR\"")); //$NON-NLS-1$
        // Message text is present; Gson HTML-escapes the apostrophes ('->\\u0027),
        // so assert on delimiter-free fragments rather than the raw message.
        assertTrue(json.contains("Foo")); //$NON-NLS-1$
        assertTrue(json.contains("not found")); //$NON-NLS-1$
        assertTrue(json.contains("\"line\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"column\":8")); //$NON-NLS-1$
        assertTrue(json.contains("\"offset\":7")); //$NON-NLS-1$
    }

    @Test
    public void testBuildResultOmitsNonPositiveLocation()
    {
        // A non-located message (line/column <= 0, offset < 0) omits those keys,
        // but any issue at all still makes the query "not valid".
        ValidateQueryTool.QueryIssue warn =
            new ValidateQueryTool.QueryIssue("WARNING", "general warning", -1, -1, -1); //$NON-NLS-1$ //$NON-NLS-2$
        String json = ValidateQueryTool.buildResult(List.of(warn), false);
        assertTrue(json.contains("\"valid\":false")); //$NON-NLS-1$
        assertTrue(json.contains("\"warningCount\":1")); //$NON-NLS-1$
        assertTrue(json.contains("\"severity\":\"WARNING\"")); //$NON-NLS-1$
        assertFalse(json.contains("\"line\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"column\":")); //$NON-NLS-1$
        assertFalse(json.contains("\"offset\":")); //$NON-NLS-1$
    }
}
