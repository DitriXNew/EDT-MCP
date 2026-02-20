/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link GetEdtVersionTool}.
 */
public class GetEdtVersionToolTest
{
    @Test
    public void testName()
    {
        GetEdtVersionTool tool = new GetEdtVersionTool();
        assertEquals("get_edt_version", tool.getName()); //$NON-NLS-1$
    }

    @Test
    public void testGetEdtVersionFromBuildId()
    {
        String previousBuildId = System.getProperty("eclipse.buildId"); //$NON-NLS-1$
        try
        {
            System.setProperty("eclipse.buildId", "2024.2.6"); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("2024.2.6", GetEdtVersionTool.getEdtVersion()); //$NON-NLS-1$
        }
        finally
        {
            if (previousBuildId == null)
                System.clearProperty("eclipse.buildId"); //$NON-NLS-1$
            else
                System.setProperty("eclipse.buildId", previousBuildId); //$NON-NLS-1$
        }
    }
}
