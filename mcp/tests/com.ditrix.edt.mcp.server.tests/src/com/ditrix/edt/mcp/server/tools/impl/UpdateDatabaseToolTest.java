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
 * Tests for {@link UpdateDatabaseTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName/applicationId
 * required-argument validation in the "no launchConfigurationName" branch, which
 * returns before any live launch-manager access. This is a destructive tool —
 * the tests only exercise the argument-validation sentinels (which return before
 * any database update); the actual update is covered by the E2E suite.
 */
public class UpdateDatabaseToolTest
{
    @Test
    public void testName()
    {
        assertEquals("update_database", new UpdateDatabaseTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(UpdateDatabaseTool.NAME, new UpdateDatabaseTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new UpdateDatabaseTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new UpdateDatabaseTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new UpdateDatabaseTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"launchConfigurationName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"applicationId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live launch manager needed) ====================

    @Test
    public void testMissingProjectName()
    {
        // No launchConfigurationName -> project+application mode -> projectName required.
        Map<String, String> params = new HashMap<>();
        String result = new UpdateDatabaseTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingApplicationId()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "MyProject"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new UpdateDatabaseTool().execute(params);
        assertTrue(result.contains("applicationId is required")); //$NON-NLS-1$
    }
}
