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
 * Tests for {@link GetTagsTool}.
 * <p>
 * Covers tool metadata, the input schema, and the two {@code execute()}
 * early-validation branches reachable headlessly: the missing-projectName guard
 * and the project-not-found sentinel. Both return BEFORE the
 * {@code TagService.getTagStorage(project)} call (the EDT boundary, which needs a
 * live project's tag store). The transient BUILDING pre-flight
 * ({@code ResourcesPlugin}-backed) is traversed safely for a non-existent project
 * name (it resolves to NOT_AVAILABLE, never BUILDING). Reading the tag store is
 * covered by the E2E suite.
 */
public class GetTagsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_tags", new GetTagsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetTagsTool.NAME, new GetTagsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetTagsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetTagsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetTagsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaRequiresProjectName()
    {
        // projectName is the sole input and it is required.
        String schema = new GetTagsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        assertTrue("projectName must be required", //$NON-NLS-1$
            schema.substring(requiredIdx).contains("\"projectName\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no live tag store needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetTagsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownProjectIsNotFound()
    {
        // projectName present but the project does not exist in the (empty) test
        // workspace: the building pre-flight resolves it to NOT_AVAILABLE (never
        // BUILDING) and falls through to the actionable "Project not found"
        // sentinel, which is returned before any tag-store access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_GetTags"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetTagsTool().execute(params);
        assertTrue("an unknown project must produce 'Project not found'", //$NON-NLS-1$
            result.contains("Project not found")); //$NON-NLS-1$
    }
}
