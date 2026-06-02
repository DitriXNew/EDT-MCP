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
import com.ditrix.edt.mcp.server.tools.symbol.SymbolInfoService;

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
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$ deprecated alias
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
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
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
        // Error is now a ToolResult.error JSON; Gson HTML-escapes ">=", so assert
        // on a delimiter-free substring (see project_gson_escapes_html_chars).
        assertTrue(result.contains("Line and column must be")); //$NON-NLS-1$
    }

    // ==================== Table-cell escaping (CLAUDE.md don't #9) ====================
    //
    // The EObject info table is built from dynamic, externally-derived values
    // (token text, symbol names, signatures, grammar/EMF type names) which may
    // contain a '|' or a newline. Those would break the Markdown table unless
    // escaped. The actual table builders need a live EDT model and run on the
    // UI thread, so the escaping is funnelled through the pure cell()/codeCell()
    // helpers, which are unit-tested here; end-to-end rendering is covered by the
    // E2E suite against a live workbench.

    @Test
    public void testCellEscapesPipe()
    {
        String row = SymbolInfoService.cell("Kind", "Map<String|Int>"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("| **Kind** | Map<String\\|Int> |\n", row); //$NON-NLS-1$
    }

    @Test
    public void testCellEscapesNewline()
    {
        String row = SymbolInfoService.cell("Parameters", "a\nb"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("newline must not leak into a table cell", row.contains("\n a")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(row.endsWith(" |\n")); //$NON-NLS-1$
        assertFalse("only the terminating newline is allowed", //$NON-NLS-1$
            row.substring(0, row.length() - 1).contains("\n")); //$NON-NLS-1$
    }

    @Test
    public void testCellPlainValue()
    {
        assertEquals("| **EMF type** | Module |\n", //$NON-NLS-1$
            SymbolInfoService.cell("EMF type", "Module")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCellNullValueRendersEmpty()
    {
        assertEquals("| **Symbol** |  |\n", SymbolInfoService.cell("Symbol", null)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCodeCellEscapesPipeInsideBackticks()
    {
        // A '|' inside a code span still splits a Markdown table cell, so it
        // must be escaped even though it is wrapped in backticks.
        String row = SymbolInfoService.codeCell("Signature", "F(a | b)"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("| **Signature** | `F(a \\| b)` |\n", row); //$NON-NLS-1$
    }

    @Test
    public void testCodeCellWrapsValueInBackticks()
    {
        assertEquals("| **Symbol** | `MyProc` |\n", //$NON-NLS-1$
            SymbolInfoService.codeCell("Symbol", "MyProc")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testCodeCellEscapesNewline()
    {
        String row = SymbolInfoService.codeCell("Token", "x\ny"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(row.endsWith(" |\n")); //$NON-NLS-1$
        assertFalse("only the terminating newline is allowed", //$NON-NLS-1$
            row.substring(0, row.length() - 1).contains("\n")); //$NON-NLS-1$
    }
}
