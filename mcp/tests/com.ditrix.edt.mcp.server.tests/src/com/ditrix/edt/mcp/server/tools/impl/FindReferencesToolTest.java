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
     * The {@code limit} parameter is an OVERALL bound on the search, not a per-category count
     * (issue #931: it used to be a {@code limit*10} cap inside a walk that always ran to the end).
     */
    @Test
    public void testLimitSchemaDescribesOverallBoundOnTheSearch()
    {
        String schema = new FindReferencesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("overall")); //$NON-NLS-1$
        assertTrue(schema.contains("not per category")); //$NON-NLS-1$
        assertTrue(schema.contains("the search itself stops one past it")); //$NON-NLS-1$
        assertFalse(schema.contains("limit*10")); //$NON-NLS-1$
    }

    /**
     * The exhaustive detail (the bound and its report, result categories, ru/en type token,
     * synonym-vs-Name resolution) lives in the on-demand guide. The guide must name the two count
     * lines and the stop paragraph an agent reads, and no longer promise the old {@code limit*10} cap.
     */
    @Test
    public void testGuideNotEmptyAndCarriesMigratedDetail()
    {
        String guide = new FindReferencesTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        assertTrue(guide.contains("synonym")); //$NON-NLS-1$
        assertTrue(guide.contains("**References shown:** N")); //$NON-NLS-1$
        assertTrue(guide.contains("**Search stopped early:**")); //$NON-NLS-1$
        assertTrue(guide.contains("45 s time budget")); //$NON-NLS-1$
        assertFalse(guide.contains("limit*10")); //$NON-NLS-1$
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
