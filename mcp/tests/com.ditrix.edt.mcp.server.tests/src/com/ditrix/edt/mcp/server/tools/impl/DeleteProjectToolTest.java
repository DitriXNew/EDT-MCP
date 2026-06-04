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
 * Tests for {@link DeleteProjectTool}.
 * <p>
 * Covers tool metadata, the input/output schema (including the {@code confirm} gate and the
 * {@code deleteContent} flag), the guide, and the {@code projectName} required-argument guard,
 * which returns BEFORE any workspace access. The actual preview/delete behaviour needs a live
 * workspace project, so it is covered by the E2E round-trip (export -> import -> verify -> delete).
 */
public class DeleteProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("delete_project", new DeleteProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(DeleteProjectTool.NAME, new DeleteProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new DeleteProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndMentionsConfirmPreview()
    {
        String desc = new DeleteProjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must advertise the confirm-preview gate", //$NON-NLS-1$
            desc.toLowerCase().contains("confirm")); //$NON-NLS-1$
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('delete_project')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresParameters()
    {
        String schema = new DeleteProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"deleteContent\"")); //$NON-NLS-1$
        assertTrue("schema must declare the confirm gate", schema.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRequiredParameters()
    {
        String schema = new DeleteProjectTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        String tail = schema.substring(requiredIdx);
        assertTrue("projectName must be required", tail.contains("\"projectName\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("deleteContent must NOT be required", !tail.contains("\"deleteContent\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("confirm must NOT be required", !tail.contains("\"confirm\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutputSchemaDeclaresConfirmPreviewFields()
    {
        String schema = new DeleteProjectTool().getOutputSchema();
        assertNotNull(schema);
        assertTrue("outputSchema must declare action", schema.contains("\"action\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("outputSchema must declare confirmationRequired", //$NON-NLS-1$
            schema.contains("\"confirmationRequired\"")); //$NON-NLS-1$
        assertTrue("outputSchema must echo deleteContent", schema.contains("\"deleteContent\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsTwoPhaseAndDeleteContent()
    {
        String guide = new DeleteProjectTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document the preview phase", //$NON-NLS-1$
            guide.toLowerCase().contains("preview")); //$NON-NLS-1$
        assertTrue("guide must document the confirm parameter", guide.contains("confirm")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document deleteContent semantics", guide.contains("deleteContent")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== Argument validation (returns before any workspace access) ====================

    @Test
    public void testMissingProjectNameIsError()
    {
        Map<String, String> params = new HashMap<>();
        String result = new DeleteProjectTool().execute(params);
        assertTrue(result.contains("projectName is required")); //$NON-NLS-1$
        // The shared required-arg guard steers to list_projects.
        assertTrue(result.contains("list_projects")); //$NON-NLS-1$
    }
}
