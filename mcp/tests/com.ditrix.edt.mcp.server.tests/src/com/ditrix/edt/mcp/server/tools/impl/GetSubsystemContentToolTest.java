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
 * Tests for {@link GetSubsystemContentTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/subsystemFqn
 * required-argument validation that returns before the first
 * {@code PlatformUI.getWorkbench()} call. Resolving the subsystem needs a live
 * configuration and is covered by the E2E suite.
 */
public class GetSubsystemContentToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_subsystem_content", new GetSubsystemContentTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetSubsystemContentTool.NAME, new GetSubsystemContentTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetSubsystemContentTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetSubsystemContentTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetSubsystemContentTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"subsystemFqn\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new GetSubsystemContentTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the slimmed description/schema into the on-demand guide.
        assertTrue(guide.contains("recursive")); //$NON-NLS-1$
        assertTrue(guide.contains("FQN format")); //$NON-NLS-1$
        assertTrue(guide.contains("deduplicated")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingSubsystemFqn()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetSubsystemContentTool().execute(params);
        assertTrue(result.contains("subsystemFqn is required")); //$NON-NLS-1$
    }
}
