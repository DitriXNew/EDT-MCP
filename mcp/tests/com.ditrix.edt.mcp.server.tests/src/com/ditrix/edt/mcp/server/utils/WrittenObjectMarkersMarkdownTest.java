/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers.Report;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers.Row;

/**
 * The Markdown rendering of the shared written-object marker report, used by a tool that answers in
 * Markdown (the {@code dcs} write validation of #662), and the re-check addresses it names.
 */
public class WrittenObjectMarkersMarkdownTest
{
    private static final String PROJECT = "TestConfiguration"; //$NON-NLS-1$
    private static final String CONTENT = "Report.Sales.Template.Main.Template"; //$NON-NLS-1$

    private static final class Scripted implements WrittenObjectMarkers.Environment
    {
        ValidationState state = ValidationState.COMPLETE;
        final List<Row> rows = new ArrayList<>();

        @Override
        public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs)
        {
            Map<String, Long> ids = new LinkedHashMap<>();
            for (int i = 0; i < fqns.size(); i++)
            {
                ids.put(fqns.get(i), Long.valueOf(100 + i));
            }
            return ids;
        }

        @Override
        public ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds, long timeoutMs)
        {
            return state;
        }

        @Override
        public WrittenObjectMarkers.ProjectMarkers read(String projectName, Map<String, Long> topObjectIds,
            int maxRows, long timeoutMs)
        {
            return new WrittenObjectMarkers.ProjectMarkers(rows.size(), new ArrayList<>(rows));
        }

        @Override
        public boolean checksDisabled()
        {
            return false;
        }
    }

    private static Report collect(Scripted env)
    {
        Map<String, List<String>> slice = new LinkedHashMap<>();
        slice.put(PROJECT, List.of(CONTENT));
        return WrittenObjectMarkers.collect(env, slice, 10_000L);
    }

    @Test
    public void testACompleteReportRendersStateCountSentenceAndAnEscapedRowTable()
    {
        Scripted env = new Scripted();
        env.rows.add(new Row(CONTENT, MarkerSeverity.MINOR, "data-composition-variant-name-default", //$NON-NLS-1$
            "Name a|b variant", CONTENT, false)); //$NON-NLS-1$
        env.rows.add(new Row(CONTENT, MarkerSeverity.ERRORS, "dcs-ql-hub", "Query\nbroken", CONTENT, true)); //$NON-NLS-1$ //$NON-NLS-2$
        String md = WrittenObjectMarkers.markdown(collect(env));

        assertTrue(md, md.startsWith("**Validation:** `complete`\n\n**Markers:** `2`\n\n")); //$NON-NLS-1$
        assertTrue(md, md.contains("EDT reports 2 marker(s) on the written object(s)")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Severity | Check | Message | Location |")); //$NON-NLS-1$
        // Most severe first, and a cell can never break the table.
        assertTrue(md, md.indexOf("| ERRORS | dcs-ql-hub |") < md.indexOf("| MINOR |")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(md, md.contains("Name a\\|b variant")); //$NON-NLS-1$
        assertFalse(md, md.contains("Query\nbroken")); //$NON-NLS-1$
        assertFalse("the section ends without a dangling newline", md.endsWith("\n")); //$NON-NLS-1$
    }

    @Test
    public void testACleanReportSaysNoMarkersAndHasNoTable()
    {
        String md = WrittenObjectMarkers.markdown(collect(new Scripted()));
        assertEquals("**Validation:** `complete`\n\n**Markers:** `0`\n\n" //$NON-NLS-1$
            + "EDT validation of the written object(s) finished: no markers.", md); //$NON-NLS-1$
    }

    @Test
    public void testAPendingReportIsNeverCleanAndNamesTheRecheckAddressNotTheInternalTopObject()
    {
        Scripted env = new Scripted();
        env.state = ValidationState.PENDING;
        Report report = collect(env).withRecheckObjects(List.of("Report.Sales")); //$NON-NLS-1$
        String md = WrittenObjectMarkers.markdown(report);

        assertTrue(md, md.startsWith("**Validation:** `incomplete` (validationPending)")); //$NON-NLS-1$
        assertTrue(md, md.contains("do not treat it as clean")); //$NON-NLS-1$
        assertTrue(md, md.contains("get_project_errors objectFqns=[Report.Sales]")); //$NON-NLS-1$
        assertFalse(md, md.contains(CONTENT));
        assertFalse(md, md.contains("no markers")); //$NON-NLS-1$
        assertTrue("the copy keeps the verdict", report.incomplete());
        assertEquals(0, report.count());
    }
}
