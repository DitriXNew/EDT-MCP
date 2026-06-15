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

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListProjectsTool}.
 * <p>
 * Takes no parameters and {@code execute()} goes straight to the live
 * {@code ResourcesPlugin.getWorkspace()} workspace (the EDT boundary), so there
 * is no argument-validation branch and no pure static helper that can run
 * headlessly: both {@code listProjects()} and {@code readEdtStatusAndNatures}
 * read the live workspace / construct against {@code IProject}. The
 * unit-testable surface is therefore the static metadata contract (the tool uses
 * the {@code IMcpTool} default MARKDOWN response type and, being a content tool,
 * declares no structured output schema); the project list is covered by the E2E
 * suite — execute() is deliberately NOT called here.
 */
public class ListProjectsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_projects", new ListProjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListProjectsTool.NAME, new ListProjectsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new ListProjectsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListProjectsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaIsValidObject()
    {
        String schema = new ListProjectsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
    }

    @Test
    public void testNoOutputSchemaForContentTool()
    {
        // MARKDOWN content tools carry their data in content, not structuredContent,
        // so they leave the output schema null (IMcpTool default).
        assertNull(new ListProjectsTool().getOutputSchema());
    }

    @Test
    public void testGuideNeverNull()
    {
        // GuideLoader returns "" when no bundled guide exists; never null.
        assertNotNull(new ListProjectsTool().getGuide());
    }

    @Test
    public void testResultFileNameIsMarkdownDefault()
    {
        // No override: the IMcpTool default derives the file name from the tool name.
        assertEquals("list_projects.md", //$NON-NLS-1$
            new ListProjectsTool().getResultFileName(new HashMap<>()));
    }
}
