/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolGroup;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;

/** Integration pins are executable against the base's existing registry and policy classes. */
public class ProjectChecksPolicyTest
{
    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void testTheProductionCatalogueRegistersProjectChecks()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);
        assertNotNull("project_checks is a built-in tool", registry.getTool("project_checks"));
    }

    @Test
    public void testProjectChecksBelongsToProblemsAndTheProjectToolset()
    {
        assertTrue(ToolGroup.PROBLEMS.getToolNames().contains("project_checks"));
        assertEquals(Toolsets.PROJECT, Toolsets.toolsetOf("project_checks"));
        assertTrue(Toolsets.isExplicitlyMapped("project_checks"));
    }

    @Test
    public void testTheConsentPreferencesIncludeProjectChecks()
    {
        assertTrue("per-tool consent policy must expose project_checks",
            DestructiveConsentGate.GATED_TOOLS.contains("project_checks"));
    }
}
