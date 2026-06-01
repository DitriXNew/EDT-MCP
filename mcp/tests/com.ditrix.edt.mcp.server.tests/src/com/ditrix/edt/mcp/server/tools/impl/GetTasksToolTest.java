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
 * Tests for {@link GetTasksTool}.
 * <p>
 * All parameters are optional and {@code execute()} goes straight to the live
 * {@code ResourcesPlugin} workspace (task markers), so there is no
 * argument-validation branch reachable before live access. The headless surface
 * is the static contract (the tool uses the {@code IMcpTool} default MARKDOWN
 * response type); the task listing is covered by the E2E suite.
 */
public class GetTasksToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_tasks", new GetTasksTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetTasksTool.NAME, new GetTasksTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetTasksTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetTasksTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetTasksTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"filePath\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"priority\"")); //$NON-NLS-1$
    }
}
