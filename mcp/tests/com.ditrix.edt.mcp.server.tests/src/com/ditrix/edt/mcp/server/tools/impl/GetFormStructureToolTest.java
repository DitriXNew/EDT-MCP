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
 * Tests for {@link GetFormStructureTool}.
 * <p>
 * Covers the headless tool metadata (name, MARKDOWN response type, schema
 * declaring {@code projectName} + {@code formPath}, non-empty description) and the
 * argument-validation branches that return before any model access. The shared
 * FQN-parsing resolver + EMF-reflection rendering helpers (extracted to
 * {@code FormStructureReader}) are covered by {@code FormStructureReaderTest}; the
 * deep read of a real form model is covered by the e2e suite against a live EDT.
 */
public class GetFormStructureToolTest
{
    // ==================== Tool metadata ====================

    @Test
    public void testName()
    {
        assertEquals("get_form_structure", new GetFormStructureTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetFormStructureTool.NAME, new GetFormStructureTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetFormStructureTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetFormStructureTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        // The slimmed description points at the on-demand guide channel.
        assertTrue(desc.contains("get_tool_guide('get_form_structure')")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHoldsMigratedDetail()
    {
        // The exhaustive detail moved OUT of the description/schema and INTO getGuide():
        // it must be non-empty and still carry the key migrated facts (FQN shapes, the
        // managed-vs-rendered distinction, the bilingual TYPE-token note).
        String guide = new GetFormStructureTool().getGuide();
        assertNotNull(guide);
        assertFalse(guide.isEmpty());
        assertTrue(guide.contains("## Parameters")); //$NON-NLS-1$
        assertTrue(guide.contains("CommonForm.FormName")); //$NON-NLS-1$
        assertTrue(guide.contains("get_form_layout_snapshot")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetFormStructureTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"language\"")); //$NON-NLS-1$
    }

    @Test
    public void testResultFileNameUsesFormName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("form-structure-ItemForm.md", new GetFormStructureTool().getResultFileName(params)); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testFormPathWithoutProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormStructureTool().execute(params);
        assertTrue(result.contains("projectName is required when formPath is specified")); //$NON-NLS-1$
    }

    @Test
    public void testMissingFormPath()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormStructureTool().execute(params);
        assertTrue(result.contains("formPath is required")); //$NON-NLS-1$
    }
}
