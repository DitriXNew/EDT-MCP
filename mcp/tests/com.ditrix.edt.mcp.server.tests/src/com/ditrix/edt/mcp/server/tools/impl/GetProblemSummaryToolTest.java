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
 * goes straight to {@code Activator.getDefault().getMarkerManager()} (the EDT
 * boundary), so there is no argument-validation branch reachable before live
 * access — {@code execute()} is intentionally NOT called here. The headless
 * surface is the static contract (note: the tool uses the {@code IMcpTool}
 * default MARKDOWN response type) plus the pure {@code sumDisplayedSeverities}
 * grouping helper; the rendered summary is covered by the E2E suite.
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
    public void testDescriptionNamesSeveritiesAndSteersToDetailTool()
    {
        // The description advertises the severity vocabulary it groups by, and steers callers
        // wanting per-marker detail to get_project_errors (this tool is totals-only).
        String desc = new GetProblemSummaryTool().getDescription();
        assertTrue("description must mention ERRORS", desc.contains("ERRORS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention BLOCKER", desc.contains("BLOCKER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention TRIVIAL", desc.contains("TRIVIAL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must steer to get_project_errors for detail", //$NON-NLS-1$
            desc.contains("get_project_errors")); //$NON-NLS-1$
    }

    @Test
    public void testSchemaDeclaresParameters()
    {
        String schema = new GetProblemSummaryTool().getInputSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"projectName\"")); //$NON-NLS-1$
    }

    @Test
    public void testProjectNameIsOptionalNotRequired()
    {
        // projectName is optional (absent = all projects); it must NOT appear in the required array.
        String schema = new GetProblemSummaryTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        if (requiredIdx >= 0)
        {
            int open = schema.indexOf('[', requiredIdx);
            int close = schema.indexOf(']', open);
            if (open >= 0 && close > open)
            {
                String requiredBlock = schema.substring(open, close + 1);
                assertTrue("projectName must NOT be required", //$NON-NLS-1$
                    !requiredBlock.contains("\"projectName\"")); //$NON-NLS-1$
            }
        }
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

    @Test
    public void testSumDisplayedSeveritiesEachColumnContributesIndependently()
    {
        // Each of the six displayed severities must be counted; a per-column probe guards
        // against a future edit dropping one column from the sum.
        MarkerSeverity[] displayed = {
            MarkerSeverity.ERRORS, MarkerSeverity.BLOCKER, MarkerSeverity.CRITICAL,
            MarkerSeverity.MAJOR, MarkerSeverity.MINOR, MarkerSeverity.TRIVIAL };
        for (MarkerSeverity sev : displayed)
        {
            Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
            counts.put(sev, 7);
            assertEquals("column " + sev.name() + " must contribute to the total", //$NON-NLS-1$ //$NON-NLS-2$
                7, GetProblemSummaryTool.sumDisplayedSeverities(counts));
        }
    }

    @Test
    public void testSumDisplayedSeveritiesIgnoresOnlyNoneEntry()
    {
        // A map carrying ONLY a NONE count (no displayed column) sums to zero: NONE has no column.
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        counts.put(MarkerSeverity.NONE, 42);
        assertEquals(0, GetProblemSummaryTool.sumDisplayedSeverities(counts));
    }

    @Test
    public void testSumDisplayedSeveritiesIsAdditiveOverDisplayedColumns()
    {
        // Mixed: some displayed columns present, some absent (default 0), plus a NONE that
        // must be excluded. Total = 10 + 20 + 30 = 60 (NONE's 99 is ignored).
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        counts.put(MarkerSeverity.ERRORS, 10);
        counts.put(MarkerSeverity.CRITICAL, 20);
        counts.put(MarkerSeverity.TRIVIAL, 30);
        counts.put(MarkerSeverity.NONE, 99);
        assertEquals(60, GetProblemSummaryTool.sumDisplayedSeverities(counts));
    }
}
