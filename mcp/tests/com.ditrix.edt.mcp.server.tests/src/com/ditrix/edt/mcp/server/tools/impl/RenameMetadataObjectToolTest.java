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
 * Tests for {@link RenameMetadataObjectTool}.
 * <p>
 * This is a cascade/destructive refactoring tool. The tests only exercise the
 * projectName/objectFqn/newName required-argument sentinels, which all return
 * (as bare {@code "Error: ..."} strings) before {@code PlatformUI.getWorkbench()}
 * and before any refactoring is computed or applied — so no rename ever runs.
 * The actual cascade is covered by the E2E suite (and must be run on a test
 * configuration).
 */
public class RenameMetadataObjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("rename_metadata_object", new RenameMetadataObjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RenameMetadataObjectTool.NAME, new RenameMetadataObjectTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new RenameMetadataObjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RenameMetadataObjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RenameMetadataObjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectFqn\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"newName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (returns before any rename) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingObjectFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("objectFqn is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingNewName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objectFqn", "Catalog.Products"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RenameMetadataObjectTool().execute(params);
        assertTrue(result.contains("newName is required")); //$NON-NLS-1$
    }
}
