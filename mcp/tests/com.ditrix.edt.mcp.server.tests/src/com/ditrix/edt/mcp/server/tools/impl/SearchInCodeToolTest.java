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
 * Tests for {@link SearchInCodeTool}.
 * <p>
 * Covers tool metadata, the input schema, the pure {@code getResultFileName}
 * sanitiser, and the argument-validation branches (projectName, query, outputMode)
 * that return before the first {@code ProjectContext.of(...)} →
 * {@code ResourcesPlugin.getWorkspace()} access (the EDT boundary). The regex-compile,
 * unknown-metadataType and actual file-scan paths run AFTER that boundary and need a
 * live project, so they are covered by the E2E suite, not here.
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
    public void testSchemaDeclaresAllOptionalParameters()
    {
        String schema = new SearchInCodeTool().getInputSchema();
        assertTrue("schema must declare caseSensitive", schema.contains("\"caseSensitive\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare isRegex", schema.contains("\"isRegex\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare limit", schema.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare the deprecated maxResults alias", //$NON-NLS-1$
            schema.contains("\"maxResults\"")); //$NON-NLS-1$
        assertTrue("schema must declare contextLines", schema.contains("\"contextLines\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare fileMask", schema.contains("\"fileMask\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare metadataType", schema.contains("\"metadataType\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaRequiresProjectNameAndQuery()
    {
        String schema = new SearchInCodeTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("query must be required", tail.contains("\"query\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputModeEnumDeclaresAllModes()
    {
        // The outputMode enum pins the vocabulary the validation branch accepts.
        String schema = new SearchInCodeTool().getInputSchema();
        assertTrue("outputMode enum must offer 'full'", schema.contains("\"full\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputMode enum must offer 'count'", schema.contains("\"count\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputMode enum must offer 'files'", schema.contains("\"files\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testDescriptionSteersToGuide()
    {
        String desc = new SearchInCodeTool().getDescription();
        assertTrue("description must point to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('search_in_code')")); //$NON-NLS-1$
    }

    // ==================== getResultFileName (pure sanitiser, no workspace) ====================

    @Test
    public void testResultFileNameDefaultsWhenQueryAbsent()
    {
        Map<String, String> params = new HashMap<>();
        assertEquals("search-results.md", new SearchInCodeTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameDefaultsWhenQueryEmpty()
    {
        Map<String, String> params = new HashMap<>();
        params.put("query", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("search-results.md", new SearchInCodeTool().getResultFileName(params)); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameSanitisesAndLowercases()
    {
        // Non-alphanumeric chars become '-', and the whole name is lowercased.
        Map<String, String> params = new HashMap<>();
        params.put("query", "Foo Bar.Baz()"); //$NON-NLS-1$ //$NON-NLS-2$
        String name = new SearchInCodeTool().getResultFileName(params);
        assertEquals("search-foo-bar-baz--.md", name); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameKeepsCyrillic()
    {
        // Cyrillic (Ѐ-ӿ) is preserved by the sanitiser; only the casing changes.
        Map<String, String> params = new HashMap<>();
        params.put("query", "Справочник"); // "Справочник" //$NON-NLS-1$ //$NON-NLS-2$
        String name = new SearchInCodeTool().getResultFileName(params);
        assertTrue("filename must keep the lowercased Cyrillic token", //$NON-NLS-1$
            name.contains("справочник")); // "справочник" //$NON-NLS-1$
        assertTrue(name.startsWith("search-")); //$NON-NLS-1$
        assertTrue(name.endsWith(".md")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameTruncatesLongQueryToForty()
    {
        // The sanitised middle is capped at 40 chars (before the search-/.md wrapping).
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < 60; i++)
        {
            q.append('a');
        }
        Map<String, String> params = new HashMap<>();
        params.put("query", q.toString()); //$NON-NLS-1$
        String name = new SearchInCodeTool().getResultFileName(params);
        // "search-" (7) + 40 sanitised chars + ".md" (3) == 50.
        assertEquals(50, name.length());
        assertTrue(name.startsWith("search-")); //$NON-NLS-1$
        assertTrue(name.endsWith(".md")); //$NON-NLS-1$
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

    @Test
    public void testInvalidOutputModeIsCaseInsensitiveStillRejected()
    {
        // The mode is lowercased before the membership check, so an uppercased
        // unknown value is rejected by the same branch (not silently accepted).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("query", "Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "SIDEWAYS"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("outputMode must be")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameWinsOverInvalidOutputMode()
    {
        // requireArguments runs before the outputMode validation: the missing
        // projectName error is reported even when outputMode is also invalid.
        Map<String, String> params = new HashMap<>();
        params.put("query", "Foo"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse("argument validation must precede outputMode validation", //$NON-NLS-1$
            result.contains("outputMode must be")); //$NON-NLS-1$
    }

    @Test
    public void testMissingQueryWinsOverInvalidOutputMode()
    {
        // projectName present, query missing, outputMode invalid: the query-required
        // guard fires first (it is checked before the outputMode membership test).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("outputMode", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new SearchInCodeTool().execute(params);
        assertTrue(result.contains("query is required")); //$NON-NLS-1$
        assertFalse(result.contains("outputMode must be")); //$NON-NLS-1$
    }

    @Test
    public void testNullParamsMissingProjectName()
    {
        // Null-safe extraction: a null map yields the projectName-required error,
        // never an NPE, and never reaches the workspace.
        String result = new SearchInCodeTool().execute(null);
        assertNotNull(result);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
