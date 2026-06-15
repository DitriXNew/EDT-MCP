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
 * Tests for {@link ListModulesTool}.
 * <p>
 * Covers tool metadata, the input schema, the dynamic result file name, and the
 * projectName required-argument validation that returns before the first
 * {@code PlatformUI.getWorkbench()} call. Enumerating modules needs a live
 * project and is covered by the E2E suite.
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
    public void testDescriptionSteersToGuideAndStructure()
    {
        String desc = new ListModulesTool().getDescription();
        // The lean description must point at the on-demand guide and at the
        // companion per-module tool, not duplicate the full parameter list.
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('list_modules')")); //$NON-NLS-1$
        assertTrue("description must point at get_module_structure for in-module detail", //$NON-NLS-1$
            desc.contains("get_module_structure")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListModulesTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"metadataType\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"nameFilter\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"limit\"")); //$NON-NLS-1$
        // metadataType is now a declared enum (schema honours the closed value set).
        assertTrue("metadataType must declare an enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enum must list a known value", schema.contains("commonModules")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testSchemaEnumListsEveryHandledType()
    {
        // The closed metadataType value set must enumerate every type the execute()
        // switch handles (a mismatch would let a client send a value the tool rejects,
        // or omit one it accepts). "all" is the default whole-project scan.
        String schema = new ListModulesTool().getInputSchema();
        String[] types = { "all", "documents", "catalogs", "commonModules", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "informationRegisters", "accumulationRegisters", "reports", "dataProcessors", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "exchangePlans", "businessProcesses", "tasks", "constants", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "commonCommands", "commonForms", "webServices", "httpServices" }; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String type : types)
        {
            assertTrue("metadataType enum must list '" + type + "'", //$NON-NLS-1$ //$NON-NLS-2$
                schema.contains("\"" + type + "\"")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testOutputSchemaIsNullForMarkdownTool()
    {
        // A MARKDOWN tool returns content, not structuredContent, so it must leave
        // the output schema at the interface default of null.
        assertNull(new ListModulesTool().getOutputSchema());
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

    // ==================== Dynamic result file name (pure, no workbench) ====================

    @Test
    public void testResultFileNameUsesLowercasedProjectName()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("modules-myproject.md", //$NON-NLS-1$
            new ListModulesTool().getResultFileName(params));
    }

    @Test
    public void testResultFileNameFallsBackWhenProjectNameMissing()
    {
        // No projectName key → the static fallback file name.
        assertEquals("modules-list.md", //$NON-NLS-1$
            new ListModulesTool().getResultFileName(new HashMap<>()));
    }

    @Test
    public void testResultFileNameFallsBackOnBlankProjectName()
    {
        // An empty projectName is treated the same as a missing one.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("modules-list.md", //$NON-NLS-1$
            new ListModulesTool().getResultFileName(params));
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new ListModulesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingProjectNameCarriesDiscoveryHint()
    {
        // The required-argument error must steer the caller to list_projects.
        String result = new ListModulesTool().execute(new HashMap<>());
        assertTrue("error must point at list_projects for discovery", //$NON-NLS-1$
            result.contains("Use list_projects")); //$NON-NLS-1$
    }

    @Test
    public void testBlankProjectNameIsError()
    {
        // An empty projectName is treated as missing and short-circuits before any
        // workbench access (extractStringArgument is empty → requireArgument fails).
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ListModulesTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testNullParamsIsError()
    {
        // requireArgument tolerates a null params map and returns the error JSON
        // rather than throwing, well before any PlatformUI call.
        String result = new ListModulesTool().execute(null);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
