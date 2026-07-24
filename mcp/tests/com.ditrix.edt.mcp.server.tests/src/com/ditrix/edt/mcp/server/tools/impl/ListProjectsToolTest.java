/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
    public void testConnectsToInfobaseIsFalse()
    {
        // #270: list_projects is a pure workspace-metadata read — it must NOT arm the
        // auth-dialog suppressor's activity window, so constant polling by this tool
        // no longer keeps a human's manual credentials dialog fought back.
        assertFalse(new ListProjectsTool().connectsToInfobase());
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

    // ==================== the 'format' parameter (issue #302) ====================

    @Test
    public void testSchemaDeclaresFormatParameter()
    {
        // Schema/execute parity: execute() reads 'format', so tools/list must declare it (with both
        // allowed values) or a schema-driven client cannot discover the machine format.
        String schema = new ListProjectsTool().getInputSchema();
        assertTrue(schema.contains("\"format\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"md\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"json\"")); //$NON-NLS-1$
    }

    @Test
    public void testResponseTypeIsMarkdownByDefault()
    {
        // No format argument -> the human Markdown table (the no-regression default).
        assertEquals(ResponseType.MARKDOWN,
            new ListProjectsTool().getResponseType(new HashMap<>()));
    }

    @Test
    public void testResponseTypeIsJsonWhenFormatJson()
    {
        HashMap<String, String> params = new HashMap<>();
        params.put("format", "json"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResponseType.JSON, new ListProjectsTool().getResponseType(params));
    }

    @Test
    public void testResponseTypeIsMarkdownForExplicitMdAndUnknownValues()
    {
        ListProjectsTool tool = new ListProjectsTool();
        HashMap<String, String> md = new HashMap<>();
        md.put("format", "md"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType(md));

        // Anything that is not 'json' keeps the human default rather than failing the call.
        HashMap<String, String> other = new HashMap<>();
        other.put("format", "xml"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType(other));
    }

    @Test
    public void testFormatJsonIsCaseInsensitiveAndTrimmed()
    {
        ListProjectsTool tool = new ListProjectsTool();
        HashMap<String, String> params = new HashMap<>();
        params.put("format", " JSON "); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ResponseType.JSON, tool.getResponseType(params));
    }
}
