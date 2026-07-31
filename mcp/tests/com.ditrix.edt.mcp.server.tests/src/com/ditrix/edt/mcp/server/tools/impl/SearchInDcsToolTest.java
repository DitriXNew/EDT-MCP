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

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link SearchInDcsTool}: tool metadata, the input schema, and the argument-validation
 * branches (projectName, query, outputMode) that return BEFORE the first {@code ProjectContext.of(...)}
 * → workspace access (the EDT boundary). The regex-compile and the actual {@code .dcs} scan run after
 * that boundary and need a live project, so they are covered by the E2E suite, not here.
 */
public class SearchInDcsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("search_in_dcs", new SearchInDcsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(SearchInDcsTool.NAME, new SearchInDcsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new SearchInDcsTool().getResponseType());
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        String desc = new SearchInDcsTool().getDescription();
        assertNotNull(desc);
        assertTrue("description must point to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('search_in_dcs')")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new SearchInDcsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"query\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"outputMode\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresAllOptionalParameters()
    {
        String schema = new SearchInDcsTool().getInputSchema();
        assertTrue("schema must declare caseSensitive", schema.contains("\"caseSensitive\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare isRegex", schema.contains("\"isRegex\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare limit", schema.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare contextLines", schema.contains("\"contextLines\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare fileMask", schema.contains("\"fileMask\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaRequiresProjectNameAndQuery()
    {
        String schema = new SearchInDcsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("query must be required", tail.contains("\"query\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputModeEnumDeclaresAllModes()
    {
        String schema = new SearchInDcsTool().getInputSchema();
        assertTrue("outputMode enum must offer 'full'", schema.contains("\"full\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputMode enum must offer 'count'", schema.contains("\"count\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputMode enum must offer 'files'", schema.contains("\"files\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        String guide = new SearchInDcsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("outputMode")); //$NON-NLS-1$
        assertTrue(guide.contains(".dcs")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("query", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInDcsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingQuery()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInDcsTool().execute(params);
        assertTrue(result.contains("query is required")); //$NON-NLS-1$
    }

    @Test
    public void testInvalidOutputMode()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("query", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInDcsTool().execute(params);
        assertTrue(result.contains("outputMode must be")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameWinsOverInvalidOutputMode()
    {
        Map<String, String> params = new HashMap<>();
        params.put("query", "Amount"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInDcsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("argument validation must precede outputMode validation", //$NON-NLS-1$
            result.contains("outputMode must be")); //$NON-NLS-1$
    }

    @Test
    public void testNullParamsMissingProjectName()
    {
        String result = new SearchInDcsTool().execute(null);
        assertNotNull(result);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
