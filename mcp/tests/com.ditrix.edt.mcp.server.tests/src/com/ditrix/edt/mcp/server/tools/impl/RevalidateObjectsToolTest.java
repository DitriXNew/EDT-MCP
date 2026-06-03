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
 * Tests for {@link RevalidateObjectsTool}.
 * <p>
 * {@code projectName} is optional (an empty/absent objects array means full-project
 * revalidation) and both branches go through {@code ProjectStateChecker}/the live
 * validation manager, so there is no argument-validation branch reachable before
 * live access. The headless surface is the static contract; revalidation is
 * covered by the E2E suite.
 */
public class RevalidateObjectsToolTest
{
    @Test
    public void testName()
    {
        assertEquals("revalidate_objects", new RevalidateObjectsTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(RevalidateObjectsTool.NAME, new RevalidateObjectsTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        // revalidate_objects is an action tool with no round-trip ID and no
        // machine-structured payload, so it returns MARKDOWN (see the
        // "Response format policy" in edt-mcp-tool-conventions).
        assertEquals(ResponseType.MARKDOWN, new RevalidateObjectsTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new RevalidateObjectsTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new RevalidateObjectsTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"objects\"")); //$NON-NLS-1$
    }
}
