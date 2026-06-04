/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;

/**
 * Tests for {@link ListConfigurationsTool}.
 * <p>
 * Both parameters are optional. The out-of-enum {@code type} guard runs BEFORE the
 * live launch-manager access, so it is the one argument-validation branch reachable
 * headlessly; the configuration list itself is covered by the E2E suite.
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
    public void testInvalidTypeRejected()
    {
        // The out-of-enum 'type' guard runs before any live launch-manager access,
        // so it is headless-reachable: a genuinely-unknown value is rejected (not
        // silently treated as 'all'), naming the value and the valid set.
        Map<String, String> params = new HashMap<>();
        params.put("type", "bogus_enum_value"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new ListConfigurationsTool().execute(params);
        assertTrue("must be a structured error", result.contains("\"success\":false")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must name the failing param value", result.contains("Invalid type")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("must echo the rejected value", result.contains("bogus_enum_value")); //$NON-NLS-1$ //$NON-NLS-2$
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
