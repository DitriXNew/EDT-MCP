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
 * Tests for {@link DebugLaunchTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two headless-reachable
 * required-argument validations in the project+application launch mode
 * (projectName, then applicationId), which return before the first
 * {@code ProjectStateChecker}/launch-manager access. NOTE: these checks are
 * only reachable when {@code launchConfigurationName} is absent — supplying it
 * enters the by-name launch mode whose first statement touches the live launch
 * manager. Actual launching is covered by the E2E suite.
 */
public class DebugLaunchToolTest
{
    @Test
    public void testName()
    {
        assertEquals("debug_launch", new DebugLaunchTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DebugLaunchTool.NAME, new DebugLaunchTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DebugLaunchTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new DebugLaunchTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new DebugLaunchTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        // The exhaustive detail (Attach mode, the alreadyRunning short-circuit and
        // updateBeforeLaunch nuances) moved out of the slimmed description/schema
        // into getGuide(); assert it survived there rather than vanishing.
        String guide = new DebugLaunchTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue(guide.contains("Attach")); //$NON-NLS-1$
        assertTrue(guide.contains("alreadyRunning")); //$NON-NLS-1$
        assertTrue(guide.contains("updateBeforeLaunch")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        // projectName present, no launchConfigurationName, applicationId omitted.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new DebugLaunchTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }
}
