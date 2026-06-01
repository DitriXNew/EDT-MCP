/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
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

    // ========== sumDisplayedSeverities (pure) ==========

    @Test
    public void testSumDisplayedSeveritiesSumsSixColumns()
    {
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        counts.put(MarkerSeverity.ERRORS, 1);
        counts.put(MarkerSeverity.BLOCKER, 2);
        counts.put(MarkerSeverity.CRITICAL, 3);
        counts.put(MarkerSeverity.MAJOR, 4);
        counts.put(MarkerSeverity.MINOR, 5);
        counts.put(MarkerSeverity.TRIVIAL, 6);
        assertEquals(21, GetProblemSummaryTool.sumDisplayedSeverities(counts));
    }

    @Test
    public void testSumDisplayedSeveritiesExcludesNone()
    {
        // The per-project Total must equal the sum of the six displayed columns; NONE markers
        // (which have no column) must not inflate it. This is the A18 regression guard.
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        counts.put(MarkerSeverity.ERRORS, 2);
        counts.put(MarkerSeverity.MINOR, 3);
        counts.put(MarkerSeverity.NONE, 100);
        assertEquals(5, GetProblemSummaryTool.sumDisplayedSeverities(counts));
    }

    @Test
    public void testSumDisplayedSeveritiesMissingKeysAndNull()
    {
        assertEquals(0, GetProblemSummaryTool.sumDisplayedSeverities(new EnumMap<>(MarkerSeverity.class)));
        assertEquals(0, GetProblemSummaryTool.sumDisplayedSeverities(null));
    }
}
