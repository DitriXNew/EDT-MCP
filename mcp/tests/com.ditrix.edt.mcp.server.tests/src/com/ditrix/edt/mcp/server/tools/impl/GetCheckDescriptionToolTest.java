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
 * Tests for {@link GetCheckDescriptionTool}.
 * <p>
 * Covers tool metadata, the input schema, and the checkId required-argument
 * validation that returns before the configured-folder / preference-store
 * access. Reading the actual check document needs the configured docs folder and
 * is covered by the E2E suite. (Uses the {@code IMcpTool} default MARKDOWN
 * response type.)
 */
public class GetCheckDescriptionToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_check_description", new GetCheckDescriptionTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetCheckDescriptionTool.NAME, new GetCheckDescriptionTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetCheckDescriptionTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetCheckDescriptionTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetCheckDescriptionTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"checkId\"")); //$NON-NLS-1$
    }

    // ==================== Argument validation (no configured folder needed) ====================

    @Test
    public void testMissingCheckId()
    {
        Map<String, String> params = new HashMap<>();
        String result = new GetCheckDescriptionTool().execute(params);
        assertTrue(result.contains("checkId parameter is required")); //$NON-NLS-1$
    }
}
