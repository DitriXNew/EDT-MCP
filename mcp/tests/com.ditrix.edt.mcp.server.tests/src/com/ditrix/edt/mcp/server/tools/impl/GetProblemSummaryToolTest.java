/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool.GroupBy;
import com.ditrix.edt.mcp.server.tools.impl.GetProblemSummaryTool.SummaryRow;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        assertTrue("description must mention ERRORS", new GetProblemSummaryTool().getGuide().contains("ERRORS")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention BLOCKER", new GetProblemSummaryTool().getGuide().contains("BLOCKER")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must mention TRIVIAL", new GetProblemSummaryTool().getGuide().contains("TRIVIAL")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("description must steer to get_project_errors for detail", //$NON-NLS-1$
            new GetProblemSummaryTool().getGuide().contains("get_project_errors")); //$NON-NLS-1$
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

    // ========== groupBy / top / severity / checkId / modulePaths ==========

    @Test
    public void testSchemaDeclaresGroupByTopSeverityCheckIdAndModulePaths()
    {
        JsonObject props = JsonParser.parseString(new GetProblemSummaryTool().getInputSchema())
            .getAsJsonObject().getAsJsonObject("properties"); //$NON-NLS-1$
        JsonArray groupBy = props.getAsJsonObject("groupBy").getAsJsonArray("enum"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("[\"check\",\"module\",\"object\"]", groupBy.toString()); //$NON-NLS-1$
        assertEquals("integer", props.getAsJsonObject("top").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        JsonArray severity = props.getAsJsonObject("severity").getAsJsonArray("enum"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(GetProjectErrorsTool.SEVERITY_VALUES.size(), severity.size());
        assertEquals("string", props.getAsJsonObject("checkId").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("array", props.getAsJsonObject("modulePaths").get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testDescriptionSaysItGroupsByCheckModuleOrObject()
    {
        String desc = new GetProblemSummaryTool().getDescription();
        assertTrue(desc, desc.contains("check, module or object")); //$NON-NLS-1$
    }

    @Test
    public void testOnlyTheNewArgumentsSwitchToTheAggregatingPath()
    {
        // With none of them the legacy path (byte-identical output) must stay in charge.
        assertFalse(GetProblemSummaryTool.hasAggregationArguments(params()));
        assertFalse(GetProblemSummaryTool.hasAggregationArguments(params("projectName", "P"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GetProblemSummaryTool.hasAggregationArguments(params("severity", " ", "modulePaths", "[]"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(GetProblemSummaryTool.hasAggregationArguments(params("groupBy", "check"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProblemSummaryTool.hasAggregationArguments(params("severity", "MAJOR"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProblemSummaryTool.hasAggregationArguments(params("checkId", "ql-"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GetProblemSummaryTool.hasAggregationArguments(
            params("modulePaths", "[\"CommonModules/A/Module.bsl\"]"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTopWithoutGroupByIsRefused()
    {
        String result = new GetProblemSummaryTool().execute(params("top", "5")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue("the refusal names the missing parameter: " + result, result.contains("groupBy")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOutOfSetSeverityIsRefusedNotWidened()
    {
        String result = new GetProblemSummaryTool().execute(params("severity", "HUGE")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("HUGE")); //$NON-NLS-1$
        assertTrue(result, result.contains("ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL, NONE")); //$NON-NLS-1$
    }

    @Test
    public void testUnknownGroupByIsRefusedNamingTheValues()
    {
        String result = new GetProblemSummaryTool().execute(params("groupBy", "severity")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result, result.contains("\"success\":false")); //$NON-NLS-1$
        assertTrue(result, result.contains("check, module, object")); //$NON-NLS-1$
    }

    @Test
    public void testGroupByCheckCountsDescThenKeyAsc()
    {
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(2, "b-check", MarkerSeverity.MINOR, "CommonModules/A/Module.bsl", "CommonModule.A.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(2, "a-check", MarkerSeverity.MINOR, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(3, "c-check", MarkerSeverity.MAJOR, "CommonModules/B/Module.bsl", "CommonModule.B.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.CHECK, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.startsWith("## Problem Summary (by check)\n\n**Total:** 7 problems in 3 groups\n\n")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Check code | Severity | Count | Fix registered | Modules |")); //$NON-NLS-1$
        int c = md.indexOf("| `c-check` | MAJOR | 3 |  | 1 |"); //$NON-NLS-1$
        int a = md.indexOf("| `a-check` | MINOR | 2 |  | 0 |"); //$NON-NLS-1$
        int b = md.indexOf("| `b-check` | MINOR | 2 |  | 1 |"); //$NON-NLS-1$
        assertTrue(md, c > 0 && a > c && b > a);
        assertFalse("nothing was folded, so no other row: " + md, md.contains("other group")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTopFoldsTheRestIntoOneOtherRowAndTotalsStillAddUp()
    {
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(5, "x", MarkerSeverity.MAJOR, "M/A/Module.bsl", "CommonModule.A.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(3, "x", MarkerSeverity.MAJOR, "M/B/Module.bsl", "CommonModule.B.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(2, "x", MarkerSeverity.MAJOR, "M/C/Module.bsl", "CommonModule.C.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(1, "x", MarkerSeverity.MAJOR, "M/D/Module.bsl", "CommonModule.D.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.MODULE, 2, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("**Total:** 11 problems in 4 groups (showing top 2)")); //$NON-NLS-1$
        assertTrue(md, md.contains("| M/A/Module.bsl | 5 | x |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| M/B/Module.bsl | 3 | x |")); //$NON-NLS-1$
        assertFalse(md, md.contains("M/C/Module.bsl")); //$NON-NLS-1$
        // 2 + 1 folded: shown rows + other row add up to the total.
        assertTrue(md, md.contains("| *(2 other groups)* | 3 |  |")); //$NON-NLS-1$

        String all = GetProblemSummaryTool.renderGroups(rows, GroupBy.MODULE, 4, ""); //$NON-NLS-1$
        assertFalse(all, all.contains("other group")); //$NON-NLS-1$
        assertFalse(all, all.contains("showing top")); //$NON-NLS-1$
    }

    @Test
    public void testObjectKeyIsTheCanonicalEnglishTopObject()
    {
        String catalogRu = "Справочник"; //$NON-NLS-1$
        String formRu = "Форма"; //$NON-NLS-1$
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(1, "x", MarkerSeverity.MAJOR, null, catalogRu + ".X." + formRu + ".Y." + formRu)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        rows.addAll(rows(1, "y", MarkerSeverity.MAJOR, null, "Catalog.X.Form.Y.Form")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "x", MarkerSeverity.MAJOR, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "x", MarkerSeverity.MAJOR, null, "Configuration")); //$NON-NLS-1$ //$NON-NLS-2$
        SummaryRow unresolved = rows(1, "x", MarkerSeverity.MAJOR, null, "<unresolved: P>").get(0); //$NON-NLS-1$ //$NON-NLS-2$
        unresolved.presentationUnresolved = true;
        rows.add(unresolved);
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.OBJECT, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("| Object | Count | Checks |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Catalog.X | 3 | x, y |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| Configuration | 1 | x |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| <unresolved> | 1 | x |")); //$NON-NLS-1$
        assertTrue(md, md.contains("**Total:** 5 problems in 3 groups")); //$NON-NLS-1$
    }

    @Test
    public void testModuleGroupingPutsMetadataOnlyMarkersInOneDashRow()
    {
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(2, "md-check", MarkerSeverity.MAJOR, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "md-check", MarkerSeverity.MAJOR, null, "Catalog.Y")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "bsl", MarkerSeverity.MAJOR, "CommonModules/A/Module.bsl", "CommonModule.A.Module")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.MODULE, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("| - | 3 | md-check |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| CommonModules/A/Module.bsl | 1 | bsl |")); //$NON-NLS-1$
        assertTrue(md, md.contains("**Total:** 4 problems in 2 groups")); //$NON-NLS-1$
    }

    @Test
    public void testChecksCellListsTheTopThreeByCount()
    {
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(1, "d", MarkerSeverity.MAJOR, "M/A/Module.bsl", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(3, "c", MarkerSeverity.MAJOR, "M/A/Module.bsl", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(2, "b", MarkerSeverity.MAJOR, "M/A/Module.bsl", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        rows.addAll(rows(2, "a", MarkerSeverity.MAJOR, "M/A/Module.bsl", "x")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.MODULE, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("| M/A/Module.bsl | 8 | c, a, b, ... (1 more) |")); //$NON-NLS-1$
    }

    @Test
    public void testCheckSeverityIsTheMostSevereSeen()
    {
        List<SummaryRow> rows = new ArrayList<>();
        rows.addAll(rows(1, "k", MarkerSeverity.MINOR, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "k", MarkerSeverity.CRITICAL, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        rows.addAll(rows(1, "k", MarkerSeverity.MAJOR, null, "Catalog.X")); //$NON-NLS-1$ //$NON-NLS-2$
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.CHECK, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("| `k` | CRITICAL | 3 |")); //$NON-NLS-1$
    }

    @Test
    public void testFixRegisteredIsYesWhenTheCheckHasAFix()
    {
        List<SummaryRow> rows = rows(2, "fixable", MarkerSeverity.MINOR, null, "Catalog.X"); //$NON-NLS-1$ //$NON-NLS-2$
        rows.get(1).hasQuickFix = true;
        String md = GetProblemSummaryTool.renderGroups(rows, GroupBy.CHECK, 25, ""); //$NON-NLS-1$
        assertTrue(md, md.contains("| `fixable` | MINOR | 2 | yes | 0 |")); //$NON-NLS-1$
    }

    @Test
    public void testEmptyGroupingSaysNoProblemsFound()
    {
        String md = GetProblemSummaryTool.renderGroups(new ArrayList<>(), GroupBy.CHECK, 25, "Project: P"); //$NON-NLS-1$
        assertEquals("## Problem Summary (by check)\n\nProject: P\n\n**Total:** 0 problems in 0 groups\n\n" //$NON-NLS-1$
            + "*No problems found.*\n", md); //$NON-NLS-1$
    }

    @Test
    public void testSeverityFilterNarrowsTotalsWithoutGroupBy()
    {
        IProject project = project("P"); //$NON-NLS-1$
        Map<IProject, List<Marker>> markers = new LinkedHashMap<>();
        markers.put(project, Arrays.asList(marker(project, MarkerSeverity.MAJOR, "SU1"), //$NON-NLS-1$
            marker(project, MarkerSeverity.MINOR, "SU2"), marker(project, MarkerSeverity.MAJOR, "SU3"))); //$NON-NLS-1$ //$NON-NLS-2$
        List<SummaryRow> rows = GetProblemSummaryTool.collectRows(markers, null, MarkerSeverity.MAJOR, null,
            Collections.<String> emptySet(), null, null);
        assertEquals(2, rows.size());
        String md = GetProblemSummaryTool.renderTotals(rows, "Severity filter: MAJOR"); //$NON-NLS-1$
        assertTrue(md, md.startsWith("## Problem Summary\n\nSeverity filter: MAJOR\n\n### Overall Totals")); //$NON-NLS-1$
        assertTrue(md, md.contains("| MAJOR | 2 |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| MINOR | 0 |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| **TOTAL** | **2** |")); //$NON-NLS-1$
        assertTrue(md, md.contains("| P | 0 | 0 | 0 | 2 | 0 | 0 | 2 |")); //$NON-NLS-1$
    }

    @Test
    public void testCollectRowsKeepsTheSeverityAndFlagsAnUnresolvedPresentation()
    {
        IProject project = project("P"); //$NON-NLS-1$
        Marker broken = marker(project, null, "SU9"); //$NON-NLS-1$
        when(broken.getObjectPresentation()).thenThrow(new RuntimeException("stale")); //$NON-NLS-1$
        Map<IProject, List<Marker>> markers = new LinkedHashMap<>();
        markers.put(project, Arrays.asList(marker(project, MarkerSeverity.BLOCKER, "SU1"), broken)); //$NON-NLS-1$
        List<SummaryRow> rows = GetProblemSummaryTool.collectRows(markers, null, null, null,
            Collections.<String> emptySet(), null, null);
        assertEquals(MarkerSeverity.BLOCKER, rows.get(0).severity);
        assertFalse(rows.get(0).presentationUnresolved);
        assertEquals("a missing severity counts as NONE, as on the legacy path", //$NON-NLS-1$
            MarkerSeverity.NONE, rows.get(1).severity);
        assertTrue(rows.get(1).presentationUnresolved);
    }

    @Test
    public void testFilterLineEchoesEveryActiveFilter()
    {
        String line = GetProblemSummaryTool.filterLine("P", "major", "ql-", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            Arrays.asList("CommonModules/A/Module.bsl")); //$NON-NLS-1$
        assertEquals("Project: P · Severity filter: MAJOR · checkId filter: ql- · " //$NON-NLS-1$
            + "Module paths filter: CommonModules/A/Module.bsl", line); //$NON-NLS-1$
        assertEquals("", GetProblemSummaryTool.filterLine(null, null, null, Collections.<String> emptyList())); //$NON-NLS-1$
    }

    private static List<SummaryRow> rows(int count, String checkId, MarkerSeverity severity, String modulePath,
        String presentation)
    {
        List<SummaryRow> rows = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            SummaryRow row = new SummaryRow();
            row.project = "P"; //$NON-NLS-1$
            row.checkId = checkId;
            row.checkCode = "SU0"; //$NON-NLS-1$
            row.severity = severity;
            row.modulePath = modulePath;
            row.objectPresentation = presentation;
            rows.add(row);
        }
        return rows;
    }

    private static Marker marker(IProject project, MarkerSeverity severity, String checkCode)
    {
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(severity);
        when(marker.getCheckId()).thenReturn(checkCode);
        when(marker.getMessage()).thenReturn("msg"); //$NON-NLS-1$
        when(marker.getProject()).thenReturn(project);
        when(marker.getObjectPresentation()).thenReturn("Catalog.X"); //$NON-NLS-1$
        return marker;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        when(project.exists()).thenReturn(true);
        return project;
    }

    private static Map<String, String> params(String... keyValues)
    {
        Map<String, String> params = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2)
        {
            params.put(keyValues[i], keyValues[i + 1]);
        }
        return params;
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
