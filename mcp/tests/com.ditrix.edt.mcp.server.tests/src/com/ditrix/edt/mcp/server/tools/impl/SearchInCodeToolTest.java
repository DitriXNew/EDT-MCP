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
 * Tests for {@link SearchInCodeTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation branches
 * (projectName, query, outputMode) that return before the first
 * {@code ResourcesPlugin}/project access. The actual search needs a live project
 * and is covered by the E2E suite.
 */
public class SearchInCodeToolTest
{
    @Test
    public void testName()
    {
        assertEquals("search_in_code", new SearchInCodeTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SearchInCodeTool.NAME, new SearchInCodeTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new SearchInCodeTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new SearchInCodeTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SearchInCodeTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"query\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"outputMode\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        String guide = new SearchInCodeTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Exhaustive detail moved out of description/schema lives here now.
        assertTrue(guide.contains("metadataType")); //$NON-NLS-1$
        assertTrue(guide.contains("outputMode")); //$NON-NLS-1$
        assertTrue(guide.contains("dialect")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("query", "Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingQuery()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("query is required")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidOutputMode()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("query", "Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("outputMode must be")); //$NON-NLS-1$
    }
}
