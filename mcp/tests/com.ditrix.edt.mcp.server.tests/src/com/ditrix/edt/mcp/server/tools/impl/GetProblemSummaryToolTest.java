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
 * Tests for {@link GetProblemSummaryTool}.
 * <p>
 * {@code projectName} is optional (absent = all projects) and {@code execute()}
 * goes straight to the live {@code IMarkerManager} service, so there is no
 * argument-validation branch reachable before live access. The headless surface
 * is the static contract (note: the tool uses the {@code IMcpTool} default
 * MARKDOWN response type); the summary is covered by the E2E suite.
 */
public class GetProblemSummaryToolTest
{
    @Test
    public void testName()
    {
        assertEquals("get_problem_summary", new GetProblemSummaryTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetProblemSummaryTool.NAME, new GetProblemSummaryTool().getName());
    }

    @Test
    public void testResponseTypeMarkdown()
    {
        assertEquals(ResponseType.MARKDOWN, new GetProblemSummaryTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmpty()
    {
        String desc = new GetProblemSummaryTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetProblemSummaryTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }
}
