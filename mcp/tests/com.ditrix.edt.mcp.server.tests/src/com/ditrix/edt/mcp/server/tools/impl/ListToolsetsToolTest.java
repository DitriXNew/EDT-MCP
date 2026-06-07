/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.ToolsetState;

/**
 * Tests for {@link ListToolsetsTool}. The tool is parameter-free and reads only the
 * static {@code Toolsets} catalogue + runtime {@code ToolsetState} + the disclosure
 * preference (off headless), so {@code execute()} runs without EDT services. The
 * live visible/enabled split is covered by the e2e suite.
 */
public class ListToolsetsToolTest
{
    @After
    public void tearDown()
    {
        ToolsetState.getInstance().reset();
    }

    @Test
    public void testNameConstant()
    {
        assertEquals("list_toolsets", new ListToolsetsTool().getName()); //$NON-NLS-1$
        assertEquals(ListToolsetsTool.NAME, new ListToolsetsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ListToolsetsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListToolsetsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testExecuteReturnsCatalogueJson()
    {
        String json = new ListToolsetsTool().execute(Collections.emptyMap());
        assertNotNull(json);
        assertTrue(json.contains("\"success\":true")); //$NON-NLS-1$
        assertTrue(json.contains("progressiveDisclosure")); //$NON-NLS-1$
        assertTrue(json.contains("\"toolsets\"")); //$NON-NLS-1$
        assertTrue(json.contains("\"count\"")); //$NON-NLS-1$
        // The core toolset and the meta-tools must be reported.
        assertTrue(json.contains("\"core\":true")); //$NON-NLS-1$
        assertTrue(json.contains("list_toolsets")); //$NON-NLS-1$
        assertTrue(json.contains("enable_toolset")); //$NON-NLS-1$
        // Headless: disclosure is off, so the note states so.
        assertTrue(json.contains("\"progressiveDisclosure\":false")); //$NON-NLS-1$
    }
}
