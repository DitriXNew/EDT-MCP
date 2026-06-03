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
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetModuleStructureTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/modulePath
 * required-argument validation that returns before the first
 * {@code PlatformUI.getWorkbench()} call. Parsing the module structure needs a
 * live workbench and is covered by the E2E suite.
 */
public class GetModuleStructureToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_module_structure", new GetModuleStructureTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetModuleStructureTool.NAME, new GetModuleStructureTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetModuleStructureTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetModuleStructureTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetModuleStructureTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"modulePath\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresResponseFormatEnum()
    {
        // responseFormat must be declared in the schema (schema<->execute parity)
        // and must expose exactly the concise/detailed enum values.
        String schema = new GetModuleStructureTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"responseFormat\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"concise\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"detailed\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"enum\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetModuleStructureTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingModulePath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetModuleStructureTool().execute(params);
        assertTrue(result.contains("modulePath is required")); //$NON-NLS-1$
    }

    // ==================== Region end-line matching (pure logic) ====================

    // Russian region markers, built from code points so this source file stays
    // pure ASCII (#Область / #КонецОбласти).
    private static final String RU_REGION =
        "#" + new String(new char[] {0x041E, 0x0431, 0x043B, 0x0430, 0x0441, 0x0442, 0x044C}); //$NON-NLS-1$
    private static final String RU_END_REGION =
        "#" + new String(new char[] //$NON-NLS-1$
            {0x041A, 0x043E, 0x043D, 0x0435, 0x0446, 0x041E, 0x0431, 0x043B, 0x0430, 0x0441, 0x0442, 0x0438});

    @Test
    public void testComputeRegionEndLineSiblingRegions()
    {
        // Two sibling regions with code between them. Each region must end at its
        // own #EndRegion — NOT at the end of the file (the bug this fixes).
        List<String> lines = List.of(
            "#Region Service",              // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure",   // 2 //$NON-NLS-1$
            "#EndRegion",                   // 3 //$NON-NLS-1$
            "#Region Accounts",             // 4 //$NON-NLS-1$
            "Procedure B() EndProcedure",   // 5 //$NON-NLS-1$
            "#EndRegion",                   // 6 //$NON-NLS-1$
            "Procedure Orphan() EndProcedure"); // 7 (outside any region) //$NON-NLS-1$
        assertEquals(3, GetModuleStructureTool.computeRegionEndLine(lines, 1));
        assertEquals(6, GetModuleStructureTool.computeRegionEndLine(lines, 4));
    }

    @Test
    public void testComputeRegionEndLineNested()
    {
        List<String> lines = List.of(
            "#Region Outer",                // 1 //$NON-NLS-1$
            "#Region Inner",                // 2 //$NON-NLS-1$
            "Procedure A() EndProcedure",   // 3 //$NON-NLS-1$
            "#EndRegion",                   // 4 (Inner) //$NON-NLS-1$
            "#EndRegion");                  // 5 (Outer) //$NON-NLS-1$
        assertEquals(5, GetModuleStructureTool.computeRegionEndLine(lines, 1));
        assertEquals(4, GetModuleStructureTool.computeRegionEndLine(lines, 2));
    }

    @Test
    public void testComputeRegionEndLineRussianMarkers()
    {
        List<String> lines = List.of(
            RU_REGION + " Service",         // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure",   // 2 //$NON-NLS-1$
            RU_END_REGION);                 // 3
        assertEquals(3, GetModuleStructureTool.computeRegionEndLine(lines, 1));
    }

    @Test
    public void testComputeRegionEndLineDegenerate()
    {
        // null source → returns the start line unchanged.
        assertEquals(5, GetModuleStructureTool.computeRegionEndLine(null, 5));
        // start line beyond EOF → returns the start line unchanged.
        assertEquals(3, GetModuleStructureTool.computeRegionEndLine(List.of("a", "b"), 3)); //$NON-NLS-1$ //$NON-NLS-2$
        // unterminated region → returns the start line unchanged.
        List<String> noEnd = List.of("#Region X", "Procedure A() EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, GetModuleStructureTool.computeRegionEndLine(noEnd, 1));
    }

    @Test
    public void testIsRegionStartAndEnd()
    {
        assertTrue(GetModuleStructureTool.isRegionStart("#Region Foo")); //$NON-NLS-1$
        assertTrue(GetModuleStructureTool.isRegionStart("#region foo")); // case-insensitive //$NON-NLS-1$
        assertTrue(GetModuleStructureTool.isRegionStart(RU_REGION + " X")); //$NON-NLS-1$
        assertFalse(GetModuleStructureTool.isRegionStart("#EndRegion")); //$NON-NLS-1$
        assertFalse(GetModuleStructureTool.isRegionStart("// #Region in a comment")); //$NON-NLS-1$

        assertTrue(GetModuleStructureTool.isRegionEnd("#EndRegion")); //$NON-NLS-1$
        assertTrue(GetModuleStructureTool.isRegionEnd("#endregion")); //$NON-NLS-1$
        assertTrue(GetModuleStructureTool.isRegionEnd(RU_END_REGION));
        assertFalse(GetModuleStructureTool.isRegionEnd("#Region Foo")); //$NON-NLS-1$
    }
}
