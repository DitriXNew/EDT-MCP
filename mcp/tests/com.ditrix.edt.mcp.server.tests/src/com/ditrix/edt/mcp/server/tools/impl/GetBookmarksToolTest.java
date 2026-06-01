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
 * Tests for {@link GetBookmarksTool}.
 * <p>
 * All parameters are optional and {@code execute()} goes straight to the live
 * {@code ResourcesPlugin} workspace, so there is no argument-validation branch
 * reachable before live access. The headless surface is the static contract
 * (the tool uses the {@code IMcpTool} default MARKDOWN response type); the
 * bookmark listing is covered by the E2E suite.
 */
public class GetBookmarksToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_bookmarks", new GetBookmarksTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetBookmarksTool.NAME, new GetBookmarksTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetBookmarksTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetBookmarksTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetBookmarksTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
    }
}
