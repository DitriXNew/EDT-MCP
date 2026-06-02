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
 * Tests for {@link GetObjectsByTagsTool}.
 * <p>
 * Covers tool metadata, the input schema, and the projectName required-argument
 * validation that returns before the first {@code ProjectStateChecker}/
 * {@code ResourcesPlugin} access. The tags-required check runs after the live
 * project-readiness check, so only the projectName sentinel is reachable
 * headlessly; resolving tagged objects is covered by the E2E suite.
 */
public class GetObjectsByTagsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_objects_by_tags", new GetObjectsByTagsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetObjectsByTagsTool.NAME, new GetObjectsByTagsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetObjectsByTagsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetObjectsByTagsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetObjectsByTagsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"tags\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live workspace needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }
}
