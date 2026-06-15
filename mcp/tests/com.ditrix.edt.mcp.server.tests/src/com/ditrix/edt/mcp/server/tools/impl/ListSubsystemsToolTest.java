/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
    public void testSchemaDeclaresAllParameters()
    {
        String schema = new ListSubsystemsTool().getInputSchema();
        assertTrue("schema must declare recursive", schema.contains("\"recursive\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare limit", schema.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("schema must declare language", schema.contains("\"language\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testProjectNameIsRequiredInSchema()
    {
        String schema = new ListSubsystemsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testNoOutputSchemaForMarkdownTool()
    {
        // MARKDOWN content tool: no structured output schema (IMcpTool default).
        assertNull(new ListSubsystemsTool().getOutputSchema());
    }

    // ==================== getResultFileName (pure, no live workbench) ====================

    @Test
    public void testResultFileNameUsesLowercasedProject()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyConfig"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystems-myconfig.md", //$NON-NLS-1$
            new ListSubsystemsTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallsBackWhenProjectMissing()
    {
        // No projectName: the generic file name (no per-project suffix).
        assertEquals("subsystems.md", //$NON-NLS-1$
            new ListSubsystemsTool().getResultFileName(new HashMap<>()));
    }

    @Test
    public void testResultFileNameFallsBackWhenProjectBlank()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("subsystems.md", //$NON-NLS-1$
            new ListSubsystemsTool().getResultFileName(params));
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
        // Structured error envelope, returned before the first PlatformUI access.
        assertTrue("must be a structured error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        // The required-arg guard appends the canonical discovery hint for projectName.
        assertTrue("must steer to list_projects", result.contains("list_projects")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testBlankProjectName()
    {
        // A blank value is treated as missing by the required-arg guard, still
        // returning before the live workbench is touched.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ListSubsystemsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
