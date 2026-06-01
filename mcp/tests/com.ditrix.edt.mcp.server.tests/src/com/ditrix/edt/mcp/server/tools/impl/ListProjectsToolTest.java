/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListProjectsTool}.
 * <p>
 * Takes no parameters and {@code execute()} goes straight to the live
 * {@code ResourcesPlugin} workspace, so there is no argument-validation branch.
 * The headless surface is the static contract (the tool uses the
 * {@code IMcpTool} default MARKDOWN response type); the project list is covered
 * by the E2E suite.
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
}
