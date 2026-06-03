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
 * Tests for {@link ListModulesTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName required-argument
 * validation that returns before the first {@code PlatformUI.getWorkbench()}
 * call. Enumerating modules needs a live project and is covered by the E2E
 * suite.
 */
public class ListModulesToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_modules", new ListModulesTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListModulesTool.NAME, new ListModulesTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ListModulesTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListModulesTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListModulesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new ListModulesTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the description/schema into the on-demand guide.
        assertTrue(guide.contains("FormModule")); //$NON-NLS-1$
        assertTrue(guide.contains("nameFilter")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ListModulesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
