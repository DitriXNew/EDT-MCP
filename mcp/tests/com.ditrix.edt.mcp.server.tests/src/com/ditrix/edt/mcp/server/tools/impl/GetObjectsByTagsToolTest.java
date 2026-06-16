/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link GetObjectsByTagsTool}.
 * <p>
 * Covers tool metadata, the input schema, and every {@code execute()}
 * early-validation branch reachable headlessly: the missing-projectName guard,
 * the empty-tags guard, and the project-not-found sentinel. These all return
 * BEFORE the {@code TagService.getTagStorage(project)} call (the EDT boundary,
 * which needs a live project's tag store). The transient BUILDING pre-flight
 * ({@code ResourcesPlugin}-backed) is traversed safely for a non-existent
 * project name (it resolves to NOT_AVAILABLE, never BUILDING, so it does not
 * short-circuit). Resolving tagged objects is covered by the E2E suite.
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

    @Test
    public void testSchemaRequiresProjectNameAndTags()
    {
        // projectName and tags are both required; limit is the only optional input.
        String schema = new GetObjectsByTagsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertTrue("projectName must be required", requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("tags must be required", requiredBlock.contains("\"tags\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("limit must NOT be required", requiredBlock.contains("\"limit\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (no live tag store needed) ====================

    @Test
    public void testMissingProjectName()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testMissingTagsIsError()
    {
        // projectName present but no tags: the building pre-flight resolves a
        // non-existent project to NOT_AVAILABLE (not BUILDING) and falls through,
        // so the tags-required guard is reached before any tag-store access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_GetObjectsByTags"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue("missing tags must produce the tags-required error", //$NON-NLS-1$
            result.contains("Tags array is required")); //$NON-NLS-1$
    }

    @Test
    public void testBlankTagsIsError()
    {
        // A blank/whitespace tags value parses to an empty list -> tags-required error.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_GetObjectsByTags"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue("blank tags must produce the tags-required error", //$NON-NLS-1$
            result.contains("Tags array is required")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyJsonArrayTagsIsError()
    {
        // An explicit empty JSON array is still "no tags" -> tags-required error.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_GetObjectsByTags"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "[]"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue("empty tags array must produce the tags-required error", //$NON-NLS-1$
            result.contains("Tags array is required")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownProjectWithTagsIsNotFound()
    {
        // projectName + tags present, but the project does not exist in the
        // (empty) test workspace: resolution fails and the tool returns the
        // actionable "Project not found" sentinel before the tag-store access.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "NoSuchProject_GetObjectsByTags"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("tags", "[\"Important\", \"NeedsReview\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetObjectsByTagsTool().execute(params);
        assertTrue("an unknown project must produce 'Project not found'", //$NON-NLS-1$
            result.contains("Project not found")); //$NON-NLS-1$
    }
}
