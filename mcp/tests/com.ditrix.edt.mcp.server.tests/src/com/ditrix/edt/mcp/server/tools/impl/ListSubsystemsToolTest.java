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
 * Tests for {@link ListSubsystemsTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName required-argument
 * validation that returns before the first {@code PlatformUI.getWorkbench()}
 * call. Walking the subsystem tree needs a live workbench and is covered by the
 * E2E suite.
 */
public class ListSubsystemsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_subsystems", new ListSubsystemsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListSubsystemsTool.NAME, new ListSubsystemsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ListSubsystemsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListSubsystemsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListSubsystemsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"nameFilter\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new ListSubsystemsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the slimmed description/schema must live in the guide.
        assertTrue(guide.contains("recursive")); //$NON-NLS-1$
        assertTrue(guide.contains("get_subsystem_content")); //$NON-NLS-1$
        assertTrue(guide.contains("FQN")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ListSubsystemsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
