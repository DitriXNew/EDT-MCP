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
 * Tests for {@link GetEdtVersionTool}.
 * <p>
 * Takes no parameters and reads the version from system properties / the OSGi
 * platform, so there is no argument-validation branch. The headless surface is
 * the static contract (the tool uses the {@code IMcpTool} default MARKDOWN
 * response type); the resolved version is environment-dependent and covered by
 * the E2E suite.
 */
public class GetEdtVersionToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_edt_version", new GetEdtVersionTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetEdtVersionTool.NAME, new GetEdtVersionTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetEdtVersionTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetEdtVersionTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaIsValidObject()
    {
        String schema = new GetEdtVersionTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("object")); //$NON-NLS-1$
    }
}
