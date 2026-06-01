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
 * Tests for {@link GetFormLayoutSnapshotTool}.
 * <p>
 * Covers tool metadata, the TEXT response type, the input schema, and the
 * "projectName is required when formPath is specified" validation that returns
 * before any {@code Display} access. Capturing the WYSIWYG layout needs a live
 * workbench and is covered by the E2E suite.
 */
public class GetFormLayoutSnapshotToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_form_layout_snapshot", new GetFormLayoutSnapshotTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetFormLayoutSnapshotTool.NAME, new GetFormLayoutSnapshotTool().getName());
    }

    @Test
    public void testResponseTypeText()
    {
        assertEquals(ResponseType.TEXT, new GetFormLayoutSnapshotTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetFormLayoutSnapshotTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetFormLayoutSnapshotTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"formPath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"mode\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testFormPathWithoutProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("formPath", "Catalog.Products.Forms.ItemForm"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetFormLayoutSnapshotTool().execute(params);
        assertTrue(result.contains("projectName is required when formPath is specified")); //$NON-NLS-1$
    }
}
