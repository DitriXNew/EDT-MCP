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
 * Tests for {@link ListConfigurationsTool}.
 * <p>
 * Both parameters are optional and {@code execute()} goes straight to the live
 * launch manager, so there is no argument-validation branch reachable before
 * live access. The headless surface is the static contract; the configuration
 * list is covered by the E2E suite.
 */
public class ListConfigurationsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("list_configurations", new ListConfigurationsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(ListConfigurationsTool.NAME, new ListConfigurationsTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        assertEquals(ResponseType.JSON, new ListConfigurationsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new ListConfigurationsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new ListConfigurationsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"type\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testGuideHasMigratedDetail()
    {
        String guide = new ListConfigurationsTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        // Detail moved out of the slimmed description/schema lives in the guide.
        assertTrue(guide.contains("launchConfigurationName")); //$NON-NLS-1$
        assertTrue(guide.contains("suspended")); //$NON-NLS-1$
    }
}
