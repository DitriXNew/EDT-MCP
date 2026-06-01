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
 * Tests for {@link GetSymbolInfoTool}.
 * <p>
 * Covers tool metadata, the input schema, and the argument-validation
 * sentinels that return before any Eclipse/EDT access (missing projectName /
 * filePath, non-numeric line/column, out-of-range line/column). Symbol
 * resolution at a position runs on the UI thread and needs a live workbench,
 * so it is covered by the E2E suite, not here.
 * <p>
 * This tool is position-addressed (filePath + line/column), not identifier-
 * addressed, so there is no Russian-identifier case to assert; the bilingual
 * resolution shared by the navigation tools is covered in MetadataTypeUtilsTest.
 */
public class GetSymbolInfoToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        assertEquals("get_symbol_info", new GetSymbolInfoTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetSymbolInfoTool.NAME, new GetSymbolInfoTool().getName());
    }

    @Test
    public void testResponseType()
    {
        assertEquals(ResponseType.MARKDOWN, new GetSymbolInfoTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetSymbolInfoTool().getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
    }

    @Test
    public void testInputSchemaContainsParameters()
    {
        String schema = new GetSymbolInfoTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"line\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"column\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("filePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSymbolInfoTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFilePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSymbolInfoTool().execute(params);
        assertTrue(result.contains("filePath is required")); //$NON-NLS-1$
    }

    @Test
    public void testNonNumericLineColumn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "abc"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSymbolInfoTool().execute(params);
        assertTrue(result.contains("Invalid line or column number")); //$NON-NLS-1$
    }

    @Test
    public void testOutOfRangeLineColumn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("filePath", "CommonModules/MyModule/Module.bsl"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("line", "0"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("column", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSymbolInfoTool().execute(params);
        assertTrue(result.contains("Line and column must be >= 1")); //$NON-NLS-1$
    }
}
