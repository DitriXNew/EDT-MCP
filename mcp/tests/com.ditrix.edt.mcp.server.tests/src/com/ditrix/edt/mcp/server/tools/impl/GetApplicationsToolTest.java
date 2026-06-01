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
 * Tests for {@link GetApplicationsTool}.
 * <p>
 * Covers tool metadata, the input schema, and the pure {@code projectName}
 * required-argument validation that returns before the first
 * {@code ProjectStateChecker}/{@code ResourcesPlugin} access. Enumerating
 * applications needs a live project model and the application manager service
 * and is covered by the E2E suite.
 */
public class GetApplicationsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_applications", new GetApplicationsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetApplicationsTool.NAME, new GetApplicationsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new GetApplicationsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetApplicationsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetApplicationsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workbench needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetApplicationsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
