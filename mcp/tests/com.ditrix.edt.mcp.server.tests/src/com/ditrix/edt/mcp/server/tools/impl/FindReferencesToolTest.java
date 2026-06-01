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
 * Tests for {@link FindReferencesTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation
 * sentinels that return before any Eclipse/EDT access. Reference search runs
 * on the UI thread via {@code PlatformUI.getWorkbench()} and needs a live
 * workbench, so it is covered by the E2E suite, not here.
 * <p>
 * Bilingual note: the Russian-to-English FQN normalization lives in the shared
 * {@code MetadataTypeUtils.normalizeFqn} and is exhaustively tested in
 * {@code MetadataTypeUtilsTest}. This test only asserts that a Russian-dialect
 * FQN passes argument validation.
 */
public class FindReferencesToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        assertEquals("find_references", new FindReferencesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(FindReferencesTool.NAME, new FindReferencesTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new FindReferencesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new FindReferencesTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        String schema = new FindReferencesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
    }

    /**
     * The {@code limit} parameter caps the OVERALL number of collected references
     * (at limit*10), not a per-category count. The schema description must say so,
     * matching the actual runtime behavior. Asserts a delimiter-free ASCII substring.
     */
    @Test
    public void testLimitSchemaDescribesOverallCap()
    {
        String schema = new FindReferencesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("overall")); //$NON-NLS-1$
        assertTrue(schema.contains("not per category")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new FindReferencesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new FindReferencesTool().execute(params);
        assertTrue(result.contains("objectFqn is required")); //$NON-NLS-1$
    }

    /**
     * A Russian-dialect FQN (Справочник.Номенклатура) must pass argument validation —
     * it is a valid FQN, so the tool must NOT reject it as missing.
     */
    @Test
    public void testRussianFqnPassesValidation()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); // blank: projectName is checked first //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new FindReferencesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        assertFalse(result.contains("objectFqn is required")); //$NON-NLS-1$
    }
}
