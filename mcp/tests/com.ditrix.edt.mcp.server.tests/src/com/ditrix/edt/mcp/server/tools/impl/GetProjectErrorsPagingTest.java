/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.junit.Test;

import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.settings.CheckUid;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.ditrix.edt.mcp.server.tools.impl.GetProjectErrorsTool.ErrorInfo;
import com.ditrix.edt.mcp.server.utils.MarkerRows;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Paging and severity honesty of {@code get_project_errors} (issues #917, #918): the total is counted past
 * the limit, {@code offset} pages through the matches, a page is never cut mid-list by the output size
 * cap, and a severity filter that leaves nothing names what the other levels hold.
 */
public class GetProjectErrorsPagingTest
{
    // ========== #917: the total is counted past the page, offset pages through the matches ==========

    @Test
    public void testTheTotalCountsEveryMatchPastTheLimit()
    {
        GetProjectErrorsTool.CollectContext context = context(null, 2, 0);
        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(byProject(majors(5)), null, context);

        assertEquals("the page holds limit rows", 2, rows.size()); //$NON-NLS-1$
        assertEquals("but every match is counted", 5, context.total()); //$NON-NLS-1$
        assertEquals(Arrays.asList("SU1", "SU2"), codes(rows)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOffsetSkipsTheFirstMatches()
    {
        GetProjectErrorsTool.CollectContext context = context(null, 2, 2);
        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(byProject(majors(5)), null, context);

        assertEquals(Arrays.asList("SU3", "SU4"), codes(rows)); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(5, context.total());
    }

    @Test
    public void testOffsetCountsMatchesNotMarkers()
    {
        // The offset skips MATCHING problems: a filtered-out marker between them is not a page position.
        List<Marker> markers = Arrays.asList(marker(MarkerSeverity.MAJOR, "SU1"), //$NON-NLS-1$
            marker(MarkerSeverity.MINOR, "SU2"), marker(MarkerSeverity.MAJOR, "SU3"), //$NON-NLS-1$ //$NON-NLS-2$
            marker(MarkerSeverity.MAJOR, "SU4")); //$NON-NLS-1$
        GetProjectErrorsTool.CollectContext context = context(MarkerSeverity.MAJOR, 1, 1);
        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(byProject(markers), null, context);

        assertEquals(Collections.singletonList("SU3"), codes(rows)); //$NON-NLS-1$
        assertEquals("only the MAJOR markers count", 3, context.total()); //$NON-NLS-1$
    }

    @Test
    public void testTheTotalSpansEveryProject()
    {
        // The old collector stopped visiting projects once the limit was reached; the total must not.
        Map<IProject, List<Marker>> markers = new LinkedHashMap<>();
        markers.put(project("A"), majors(2)); //$NON-NLS-1$
        markers.put(project("B"), majors(3)); //$NON-NLS-1$
        GetProjectErrorsTool.CollectContext context = context(null, 1, 0);
        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(markers, null, context);

        assertEquals(1, rows.size());
        assertEquals(5, context.total());
    }

    @Test
    public void testAnOffsetPastTheEndKeepsTheTotal()
    {
        GetProjectErrorsTool.CollectContext context = context(null, 10, 10);
        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(byProject(majors(5)), null, context);

        assertTrue(rows.isEmpty());
        assertEquals(5, context.total());
    }

    // ========== #917: the page header names the total and the way on ==========

    @Test
    public void testPageNoticeNamesTheWindowAndTheNextOffset()
    {
        assertEquals(" (showing 1-100 of 245; next page: offset=100)", Pagination.pageNotice(0, 100, 245)); //$NON-NLS-1$
        assertEquals(" (showing 101-200 of 245; next page: offset=200)", Pagination.pageNotice(100, 100, 245)); //$NON-NLS-1$
        assertEquals("the last page names no next one", //$NON-NLS-1$
            " (showing 201-245 of 245)", Pagination.pageNotice(200, 45, 245)); //$NON-NLS-1$
        assertEquals("a page that is the whole result needs no notice", "", Pagination.pageNotice(0, 5, 5)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testAPartialPageStatesTheTotalAndPointsToTheOverview()
    {
        StringBuilder md = new StringBuilder();
        int shown = GetProjectErrorsTool.appendProblemsPage(md, rows(2, "msg"), 0, 5, false, 100_000); //$NON-NLS-1$
        String out = md.toString();

        assertEquals(2, shown);
        assertTrue(out, out.contains("**Found:** 5 (showing 1-2 of 5; next page: offset=2)")); //$NON-NLS-1$
        assertTrue("the way on is named after the table too: " + out, out.contains("call again with offset=2")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the overview tool is named: " + out, out.contains("get_problem_summary")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one table line per shown row plus header and separator", 4, tableLines(out).size()); //$NON-NLS-1$
    }

    @Test
    public void testAWholeResultPageCarriesNoPagingNotes()
    {
        StringBuilder md = new StringBuilder();
        GetProjectErrorsTool.appendProblemsPage(md, rows(3, "msg"), 0, 3, false, 100_000); //$NON-NLS-1$
        String out = md.toString();

        assertTrue(out, out.contains("**Found:** 3\n\n")); //$NON-NLS-1$
        assertFalse(out, out.contains("offset")); //$NON-NLS-1$
        assertFalse(out, out.contains("get_problem_summary")); //$NON-NLS-1$
    }

    @Test
    public void testThePageStopsAtARowBoundaryUnderTheSizeBudget()
    {
        // A row of ~1000 characters and a budget of ~3 rows: the table must stop on a whole row and say where
        // the rest begins, instead of letting the output cap cut it somewhere in the middle.
        char[] longText = new char[1000];
        Arrays.fill(longText, 'x');
        List<ErrorInfo> page = rows(10, new String(longText));
        StringBuilder md = new StringBuilder();
        int shown = GetProjectErrorsTool.appendProblemsPage(md, page, 20, 100, false, 3_500);
        String out = md.toString();

        assertTrue("some rows must be cut: " + shown, shown > 0 && shown < page.size()); //$NON-NLS-1$
        assertEquals("every rendered row is whole", shown + 2, tableLines(out).size()); //$NON-NLS-1$
        for (String line : tableLines(out))
        {
            assertTrue("a table line must end its row: " + line, line.endsWith("|")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        int next = 20 + shown;
        assertTrue(out, out.contains("(showing 21-" + next + " of 100; next page: offset=" + next + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue("the size cut is explained: " + out, //$NON-NLS-1$
            out.contains("fit the response size limit") && out.contains("(offset=" + next + ")")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void testOneRowIsRenderedEvenOverTheBudget()
    {
        StringBuilder md = new StringBuilder();
        assertEquals(1, GetProjectErrorsTool.appendProblemsPage(md, rows(3, "msg"), 0, 3, false, 1)); //$NON-NLS-1$
    }

    @Test
    public void testAnOffsetPastTheLastProblemDoesNotReadAsClean()
    {
        StringBuilder md = new StringBuilder();
        int shown = GetProjectErrorsTool.appendProblemsPage(md, Collections.<ErrorInfo> emptyList(), 10, 5, false,
            100_000);
        String out = md.toString();

        assertEquals(0, shown);
        assertFalse(out, out.contains("No Errors Found")); //$NON-NLS-1$
        assertTrue(out, out.contains("**Found:** 5 (offset 10 is past the last problem")); //$NON-NLS-1$
    }

    @Test
    public void testTheAddressPayloadCarriesTheTotalAndTheNextOffset()
    {
        JsonObject payload = JsonParser.parseString(GetProjectErrorsTool.addressPayload("report", rows(2, "m"), //$NON-NLS-1$ //$NON-NLS-2$
            new GetProjectErrorsTool.AddressResolution(), 7, 3)).getAsJsonObject();

        assertEquals(2, payload.get(GetProjectErrorsTool.KEY_PROBLEMS_FOUND).getAsInt());
        assertEquals(7, payload.get(GetProjectErrorsTool.KEY_PROBLEMS_TOTAL).getAsInt());
        assertTrue(payload.get(GetProjectErrorsTool.KEY_HAS_MORE).getAsBoolean());
        assertEquals(5, payload.get(GetProjectErrorsTool.KEY_NEXT_OFFSET).getAsInt());

        JsonObject whole = JsonParser.parseString(GetProjectErrorsTool.addressPayload("report", rows(2, "m"), //$NON-NLS-1$ //$NON-NLS-2$
            new GetProjectErrorsTool.AddressResolution())).getAsJsonObject();
        assertEquals(2, whole.get(GetProjectErrorsTool.KEY_PROBLEMS_TOTAL).getAsInt());
        assertFalse(whole.get(GetProjectErrorsTool.KEY_HAS_MORE).getAsBoolean());
    }

    @Test
    public void testSchemaDeclaresOffsetAsAnInteger()
    {
        JsonObject offset = JsonParser.parseString(new GetProjectErrorsTool().getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties").getAsJsonObject(GetProjectErrorsTool.PARAM_OFFSET); //$NON-NLS-1$
        assertEquals("integer", offset.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testGuideDocumentsPagingAndTheNoneLevel()
    {
        String guide = new GetProjectErrorsTool().getGuide();
        assertTrue(guide.contains("`offset`")); //$NON-NLS-1$
        assertTrue(guide.contains("next page: offset=")); //$NON-NLS-1$
        assertTrue(guide.contains("NOT \"all levels\"")); //$NON-NLS-1$
    }

    // ========== #918: a severity filter that leaves nothing names the other levels ==========

    @Test
    public void testOtherSeveritiesAreCountedWithTheOtherFilters()
    {
        List<Marker> markers = Arrays.asList(marker(MarkerSeverity.MINOR, "SU1"), //$NON-NLS-1$
            marker(MarkerSeverity.MINOR, "SU2"), marker(MarkerSeverity.TRIVIAL, "SU3"), //$NON-NLS-1$ //$NON-NLS-2$
            marker(MarkerSeverity.MAJOR, "XX9")); //$NON-NLS-1$
        GetProjectErrorsTool.CollectContext context = new GetProjectErrorsTool.CollectContext(MarkerSeverity.NONE,
            "SU", Collections.<String> emptySet(), null, null, 100, new int[1], new int[1], false, null, //$NON-NLS-1$
            Collections.<String> emptySet(), 0);

        assertTrue(GetProjectErrorsTool.collectErrors(byProject(markers), null, context).isEmpty());
        assertEquals(0, context.total());

        Map<MarkerSeverity, Integer> counts =
            GetProjectErrorsTool.countOtherSeverities(byProject(markers), null, context);
        Map<MarkerSeverity, Integer> expected = new EnumMap<>(MarkerSeverity.class);
        expected.put(MarkerSeverity.MINOR, 2);
        expected.put(MarkerSeverity.TRIVIAL, 1);
        assertEquals("the checkId filter still applies; only severity is lifted", expected, counts); //$NON-NLS-1$
    }

    @Test
    public void testNoOtherSeveritiesWithoutASeverityFilter()
    {
        GetProjectErrorsTool.CollectContext context = context(null, 100, 0);
        assertTrue(GetProjectErrorsTool.countOtherSeverities(byProject(majors(2)), null, context).isEmpty());
    }

    @Test
    public void testTheOtherSeveritiesNoteNamesEachLevelAndTheRemedy()
    {
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        counts.put(MarkerSeverity.TRIVIAL, 1);
        counts.put(MarkerSeverity.MINOR, 2);
        StringBuilder md = new StringBuilder();
        MarkerRows.appendOtherSeveritiesNote(md, MarkerSeverity.NONE, counts);
        String out = md.toString();

        assertTrue(out, out.contains("Nothing has severity NONE, but 3 problem(s)")); //$NON-NLS-1$
        assertTrue("levels in severity order: " + out, out.contains("MINOR 2, TRIVIAL 1")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(out, out.contains("not 'all levels'")); //$NON-NLS-1$
        assertTrue(out, out.contains("omit severity")); //$NON-NLS-1$

        StringBuilder clean = new StringBuilder("x"); //$NON-NLS-1$
        MarkerRows.appendOtherSeveritiesNote(clean, MarkerSeverity.NONE, new EnumMap<>(MarkerSeverity.class));
        assertEquals("a scope with nothing at any level stays clean", "x", clean.toString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTheSummaryCountsTheOtherSeveritiesLeavingOutTheFilteredOne()
    {
        List<GetProblemSummaryTool.SummaryRow> rows = new ArrayList<>();
        for (MarkerSeverity severity : new MarkerSeverity[] {MarkerSeverity.MINOR, MarkerSeverity.MINOR,
            MarkerSeverity.TRIVIAL, MarkerSeverity.NONE})
        {
            GetProblemSummaryTool.SummaryRow row = new GetProblemSummaryTool.SummaryRow();
            row.severity = severity;
            rows.add(row);
        }
        Map<MarkerSeverity, Integer> expected = new EnumMap<>(MarkerSeverity.class);
        expected.put(MarkerSeverity.MINOR, 2);
        expected.put(MarkerSeverity.TRIVIAL, 1);
        assertEquals(expected, GetProblemSummaryTool.severityCounts(rows, MarkerSeverity.NONE));
    }

    @Test
    public void testMatchesMakesTheSameDecisionAsTheRowBuilder()
    {
        Marker minor = marker(MarkerSeverity.MINOR, "SU1"); //$NON-NLS-1$
        assertFalse(MarkerRows.matches(minor, MarkerSeverity.MAJOR, null, Collections.<String> emptySet(),
            Collections.<String> emptySet(), null, new int[1], false));
        assertTrue(MarkerRows.matches(minor, MarkerSeverity.MINOR, "su", Collections.<String> emptySet(), //$NON-NLS-1$
            Collections.<String> emptySet(), null, new int[1], false));
        assertFalse(MarkerRows.matches(minor, null, "nope", Collections.<String> emptySet(), //$NON-NLS-1$
            Collections.<String> emptySet(), null, new int[1], false));
    }

    // ========== review of #924: a counted marker keeps its page position; one row stays bounded ==========

    @Test
    public void testAPresentationThatFailsOnASecondReadDoesNotLoseTheCountedMarker()
    {
        // The objects filter read the location once and counted the marker at index 0. Building its row must
        // reuse that decision: a second read can fail on its own, and a rerun of the filters would then drop
        // the counted marker and let the next one take its place (and appear again on the next page).
        Marker flaky = marker(MarkerSeverity.MAJOR, "SU1"); //$NON-NLS-1$
        when(flaky.getObjectPresentation()).thenReturn("CommonModule.X.Module") //$NON-NLS-1$
            .thenThrow(new IllegalStateException("model gone")); //$NON-NLS-1$
        Marker steady = marker(MarkerSeverity.MAJOR, "SU2"); //$NON-NLS-1$
        GetProjectErrorsTool.CollectContext context = new GetProjectErrorsTool.CollectContext(null, null,
            GetProjectErrorsTool.scanFilterVariants(Collections.singletonList("CommonModule.X"), false), null, null, //$NON-NLS-1$
            1, new int[1], new int[1], false, null, Collections.<String> emptySet(), 0);

        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(byProject(Arrays.asList(flaky, steady)), null,
            context);

        assertEquals("the marker counted at index 0 is the one on the page", //$NON-NLS-1$
            Collections.singletonList("SU1"), codes(rows)); //$NON-NLS-1$
        assertEquals(2, context.total());
        assertEquals("CommonModule.X.Module", rows.get(0).objectPresentation); //$NON-NLS-1$
    }

    @Test
    public void testACheckLookupThatFailsOnASecondCallDoesNotLoseTheCountedMarker()
    {
        // The checkId filter matched the SYMBOLIC id through the repository; a second lookup may fail on its own.
        CheckUid uid = mock(CheckUid.class);
        when(uid.getCheckId()).thenReturn("my-check"); //$NON-NLS-1$
        ICheckRepository repository = mock(ICheckRepository.class);
        when(repository.getUidForShortUid(eq("SU1"), any(IProject.class))).thenReturn(uid) //$NON-NLS-1$
            .thenThrow(new IllegalStateException("repository gone")); //$NON-NLS-1$
        when(repository.getUidForShortUid(eq("SU2"), any(IProject.class))).thenReturn(uid); //$NON-NLS-1$
        GetProjectErrorsTool.CollectContext context = new GetProjectErrorsTool.CollectContext(null, "my-check", //$NON-NLS-1$
            Collections.<String> emptySet(), repository, null, 1, new int[1], new int[1], false, null,
            Collections.<String> emptySet(), 0);

        List<ErrorInfo> rows = GetProjectErrorsTool.collectErrors(
            byProject(Arrays.asList(marker(MarkerSeverity.MAJOR, "SU1"), marker(MarkerSeverity.MAJOR, "SU2"))), //$NON-NLS-1$ //$NON-NLS-2$
            null, context);

        assertEquals(Collections.singletonList("SU1"), codes(rows)); //$NON-NLS-1$
        assertEquals("my-check", rows.get(0).checkId); //$NON-NLS-1$
        assertEquals(2, context.total());
    }

    @Test
    public void testOneHugeMessageCannotPushTheFirstRowPastTheBudget()
    {
        // The first row is always rendered; a message of 200,000 characters must not carry it past the budget
        // (the output cap would then cut the row, and the same row is first again on every continuation page).
        char[] huge = new char[200_000];
        Arrays.fill(huge, 'x');
        List<ErrorInfo> page = rows(2, new String(huge));
        StringBuilder md = new StringBuilder();
        int shown = GetProjectErrorsTool.appendProblemsPage(md, page, 0, 2, false,
            GetProjectErrorsTool.REPORT_TABLE_BUDGET_CHARS);
        String out = md.toString();

        assertEquals("both bounded rows fit", 2, shown); //$NON-NLS-1$
        assertTrue("the page stays below the budget: " + out.length(), //$NON-NLS-1$
            out.length() < GetProjectErrorsTool.REPORT_TABLE_BUDGET_CHARS);
        assertTrue("the cut is visible", out.contains("... [cut: 2000 of 200000 characters shown]")); //$NON-NLS-1$ //$NON-NLS-2$
        for (String line : tableLines(out))
        {
            assertTrue("every row stays whole: " + line.length(), line.endsWith("|")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void testBoundedCellKeepsShortTextAndNeverSplitsASurrogatePair()
    {
        assertEquals("short", GetProjectErrorsTool.boundedCell("short")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(null, GetProjectErrorsTool.boundedCell(null));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < GetProjectErrorsTool.MAX_CELL_CHARS - 1; i++)
        {
            text.append('a');
        }
        text.append("😀tail"); //$NON-NLS-1$ (a pair straddling the bound)
        String cut = GetProjectErrorsTool.boundedCell(text.toString());
        assertTrue(cut, cut.startsWith(text.substring(0, GetProjectErrorsTool.MAX_CELL_CHARS - 1) + " ... [cut: ")); //$NON-NLS-1$
        assertFalse("no lone high surrogate", cut.contains("\uD83D ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testOnlyRenderedPlaceholderRowsAreCountedAsShown()
    {
        // Five markers whose location cannot be resolved, each with a long message: the page holds all five,
        // the size budget renders only the first few. The "shown with a placeholder location" warning must
        // count the rows really shown, not the ones moved to the next page.
        char[] text = new char[1500];
        Arrays.fill(text, 'x');
        List<Marker> markers = new ArrayList<>();
        for (int i = 1; i <= 5; i++)
        {
            Marker marker = marker(MarkerSeverity.MAJOR, "SU" + i); //$NON-NLS-1$
            when(marker.getMessage()).thenReturn(new String(text));
            when(marker.getObjectPresentation()).thenThrow(new IllegalStateException("unresolvable")); //$NON-NLS-1$
            markers.add(marker);
        }
        GetProjectErrorsTool.CollectContext context = context(null, 5, 0);
        List<ErrorInfo> page = GetProjectErrorsTool.collectErrors(byProject(markers), null, context);
        assertEquals(5, page.size());

        int shown = GetProjectErrorsTool.appendProblemsPage(new StringBuilder(), page, 0, 5, false, 3_500,
            context.unresolvedShown);

        assertTrue("the budget must cut the page: " + shown, shown > 0 && shown < 5); //$NON-NLS-1$
        assertEquals("only the rendered placeholder rows count", shown, context.unresolvedShown[0]); //$NON-NLS-1$
    }

    @Test
    public void testThePlaceholderFlagStaysOutOfTheStructuredPayload()
    {
        List<ErrorInfo> rows = rows(1, "m"); //$NON-NLS-1$
        rows.get(0).presentationUnresolved = true;
        String json = GetProjectErrorsTool.addressPayload("report", rows, new GetProjectErrorsTool.AddressResolution()); //$NON-NLS-1$
        assertFalse(json, json.contains("presentationUnresolved")); //$NON-NLS-1$
    }

    // ========== helpers ==========

    private static GetProjectErrorsTool.CollectContext context(MarkerSeverity severity, int limit, int offset)
    {
        return new GetProjectErrorsTool.CollectContext(severity, null, Collections.<String> emptySet(), null, null,
            limit, new int[1], new int[1], false, null, Collections.<String> emptySet(), offset);
    }

    private static Map<IProject, List<Marker>> byProject(List<Marker> markers)
    {
        Map<IProject, List<Marker>> byProject = new LinkedHashMap<>();
        byProject.put(project("P"), markers); //$NON-NLS-1$
        return byProject;
    }

    private static List<Marker> majors(int count)
    {
        List<Marker> markers = new ArrayList<>();
        for (int i = 1; i <= count; i++)
        {
            markers.add(marker(MarkerSeverity.MAJOR, "SU" + i)); //$NON-NLS-1$
        }
        return markers;
    }

    private static Marker marker(MarkerSeverity severity, String checkCode)
    {
        IProject project = project("P"); //$NON-NLS-1$
        Marker marker = mock(Marker.class);
        when(marker.getSeverity()).thenReturn(severity);
        when(marker.getCheckId()).thenReturn(checkCode);
        when(marker.getMessage()).thenReturn("msg"); //$NON-NLS-1$
        when(marker.getProject()).thenReturn(project);
        when(marker.getObjectPresentation()).thenReturn("CommonModule.X.Module"); //$NON-NLS-1$
        return marker;
    }

    private static IProject project(String name)
    {
        IProject project = mock(IProject.class);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static List<ErrorInfo> rows(int count, String message)
    {
        List<ErrorInfo> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++)
        {
            ErrorInfo row = new ErrorInfo();
            row.checkCode = "SU" + i; //$NON-NLS-1$
            row.message = message;
            row.objectPresentation = "CommonModule.X.Module"; //$NON-NLS-1$
            rows.add(row);
        }
        return rows;
    }

    private static List<String> codes(List<ErrorInfo> rows)
    {
        List<String> codes = new ArrayList<>();
        for (ErrorInfo row : rows)
        {
            codes.add(row.checkCode);
        }
        return codes;
    }

    private static List<String> tableLines(String markdown)
    {
        List<String> lines = new ArrayList<>();
        for (String line : markdown.split("\n")) //$NON-NLS-1$
        {
            if (line.startsWith("|")) //$NON-NLS-1$
            {
                lines.add(line);
            }
        }
        return lines;
    }
}
