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
 * Tests for {@link CleanProjectTool}.
 * <p>
 * {@code projectName} is optional (absent = clean all EDT projects) and both
 * branches go through {@code ProjectStateChecker} / the live clean-build
 * lifecycle, so there is no argument-validation branch reachable before live
 * access. This is a destructive tool — the tests assert the static contract only
 * and never invoke {@code execute()}; cleaning is covered by the E2E suite.
 */
public class CleanProjectToolTest
{
    @Test
    public void testName()
    {
        assertEquals("clean_project", new CleanProjectTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(CleanProjectTool.NAME, new CleanProjectTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new CleanProjectTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new CleanProjectTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new CleanProjectTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }
}
