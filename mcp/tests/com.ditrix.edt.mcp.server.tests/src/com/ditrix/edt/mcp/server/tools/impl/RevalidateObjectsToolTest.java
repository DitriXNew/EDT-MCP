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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link RevalidateObjectsTool}.
 * <p>
 * {@code projectName} is optional in the schema, but the static
 * {@link RevalidateObjectsTool#revalidateObjects(String, List)} entry point guards a
 * null/blank {@code projectName} BEFORE any workspace or platform-services access, and
 * {@code execute()} reaches that same guard when {@code projectName} is missing/blank
 * (the only branch it skips for a blank name is the {@code ProjectStateChecker} call).
 * Everything past a non-empty name needs a live EDT workspace and is covered by the
 * E2E suite. These tests pin the metadata contract plus that headless guard.
 */
public class RevalidateObjectsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("revalidate_objects", new RevalidateObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RevalidateObjectsTool.NAME, new RevalidateObjectsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        // revalidate_objects is an action tool with no round-trip ID and no
        // machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new RevalidateObjectsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RevalidateObjectsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RevalidateObjectsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objects\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsTheOnlyRequiredParameter()
    {
        String schema = new RevalidateObjectsTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        // The objects filter is optional (empty/absent = full-project revalidation).
        assertFalse("objects must NOT be required", tail.contains("\"objects\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideNotEmpty()
    {
        String guide = new RevalidateObjectsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
    }

    // ==================== revalidateObjects static guard (returns before any EDT access) ====================

    @Test
    public void testStaticRevalidateNullProjectIsError()
    {
        // The first guard in revalidateObjects rejects a null projectName before
        // touching ProjectContext / the BM model — so this is headless-safe.
        String result = RevalidateObjectsTool.revalidateObjects(null, Collections.emptyList());
        assertTrue("null projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticRevalidateEmptyProjectIsError()
    {
        // A non-empty objects list must not change the outcome: a blank projectName
        // is rejected by the first guard before the FQNs are ever looked up.
        List<String> objects = new ArrayList<>();
        objects.add("Document.SalesOrder"); //$NON-NLS-1$
        String result = RevalidateObjectsTool.revalidateObjects("", objects); //$NON-NLS-1$
        assertTrue("empty projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticRevalidateNullObjectsListWithBlankProjectIsError()
    {
        // A null objects list is normalized to a full-project revalidation, but the
        // blank-projectName guard still fires first — no EDT access on this path.
        String result = RevalidateObjectsTool.revalidateObjects("", null); //$NON-NLS-1$
        assertTrue("blank projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteMissingProjectNameIsError()
    {
        // With no projectName, execute() skips the ProjectStateChecker block and
        // delegates to the static guard, which returns the error before any EDT access.
        Map<String, String> params = new HashMap<>();
        String result = new RevalidateObjectsTool().execute(params);
        assertTrue("missing projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testExecuteBlankProjectNameWithObjectsIsError()
    {
        // objects is parsed (a pure helper) before delegating; a blank projectName
        // still short-circuits at the static guard ahead of any workspace lookup.
        Map<String, String> params = new HashMap<>();
        params.put("projectName", ""); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("objects", "[\"Document.SalesOrder\"]"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new RevalidateObjectsTool().execute(params);
        assertTrue("blank projectName must produce 'projectName is required'", //$NON-NLS-1$
            result.contains("projectName is required")); //$NON-NLS-1$
    }

    @Test
    public void testStaticRevalidateErrorPayloadIsJson()
    {
        // The guard returns a ToolResult.error(...).toJson() payload (structured
        // error), not a MARKDOWN body — pin that the error travels as JSON.
        String result = RevalidateObjectsTool.revalidateObjects(null, new ArrayList<>());
        assertTrue("error payload must be JSON", result.trim().startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
