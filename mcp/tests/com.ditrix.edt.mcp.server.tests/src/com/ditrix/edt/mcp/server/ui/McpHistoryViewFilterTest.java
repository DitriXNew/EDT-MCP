/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.history.McpCallRecord;
import com.ditrix.edt.mcp.server.history.StatsAggregator;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Unit tests for the pure, SWT-free filter predicate extracted from
 * {@link McpHistoryView} (the History-view filter bar). These lock the AND
 * composition of the five filters (tools/call toggle, case-insensitive
 * method/tool substring, errors-only toggle, minimum duration, inclusive time
 * interval), the {@code isError} record classifier shared with the Status
 * column, and the {@code statKey} grouping that must mirror
 * {@code StatsAggregator.keyOf}. The filter UI itself (SWT widgets) is verified
 * live.
 */
public class McpHistoryViewFilterTest
{
    private static final long TS = 1_000_000L;

    /** A JSON-RPC top-level error envelope; classified as an error outcome. */
    private static final String RPC_ERROR = "{\"error\":{\"code\":-32000,\"message\":\"boom\"}}"; //$NON-NLS-1$

    /** A tools/call result carrying isError:true; classified as an error outcome. */
    private static final String TOOL_ERROR = "{\"result\":{\"isError\":true}}"; //$NON-NLS-1$

    /** An OK response (no error markers). */
    private static final String OK = "{}"; //$NON-NLS-1$

    private static McpCallRecord record(String method, String tool, long timestampMs, long durationMs)
    {
        return new McpCallRecord(timestampMs, method, tool, "{}", OK, durationMs); //$NON-NLS-1$
    }

    private static McpCallRecord record(String method, String tool, long timestampMs, long durationMs,
        String responseJson)
    {
        return new McpCallRecord(timestampMs, method, tool, "{}", responseJson, durationMs); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------ statKey

    @Test
    public void testStatKeyUsesToolNameForToolsCall()
    {
        assertEquals("get_metadata", //$NON-NLS-1$
            McpHistoryView.statKey(record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 5))); //$NON-NLS-1$
    }

    @Test
    public void testStatKeyFallsBackToMethodWhenToolBlank()
    {
        assertEquals(McpConstants.METHOD_TOOLS_CALL,
            McpHistoryView.statKey(record(McpConstants.METHOD_TOOLS_CALL, "   ", TS, 5))); //$NON-NLS-1$
    }

    @Test
    public void testStatKeyUsesMethodForNonToolsCall()
    {
        assertEquals("tools/list", McpHistoryView.statKey(record("tools/list", null, TS, 5))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStatKeyUnknownForNoMethod()
    {
        // Mirror StatsAggregator.keyOf: a record with no method falls into the
        // "(unknown)" bucket so the substring filter lines up with the stats rows.
        assertEquals(StatsAggregator.NON_TOOL_METHODS_KEY,
            McpHistoryView.statKey(record(null, null, TS, 5)));
    }

    // -------------------------------------------------------------------- isError

    @Test
    public void testIsErrorClassifiesResponse()
    {
        assertFalse(McpHistoryView.isError(null));
        assertFalse(McpHistoryView.isError(record("tools/list", null, TS, 0, OK))); //$NON-NLS-1$
        assertTrue(McpHistoryView.isError(record("tools/list", null, TS, 0, RPC_ERROR))); //$NON-NLS-1$
        assertTrue(McpHistoryView.isError(record(McpConstants.METHOD_TOOLS_CALL, "x", TS, 0, TOOL_ERROR))); //$NON-NLS-1$
    }

    // -------------------------------------------------------- renderEscapedNewlines

    @Test
    public void testRenderEscapedNewlinesUnescapesGenuineBreaks()
    {
        // A JSON-encoded \n / \r\n newline becomes a real line break in the detail pane.
        assertEquals("a\nb", McpHistoryView.renderEscapedNewlines("a\\nb")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a\nb", McpHistoryView.renderEscapedNewlines("a\\r\\nb")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testRenderEscapedNewlinesKeepsEscapedBackslash()
    {
        // A JSON-encoded escaped backslash followed by 'n' (a Windows path such as
        // "C:\\node") must NOT be turned into a spurious line break.
        assertEquals("C:\\\\node", McpHistoryView.renderEscapedNewlines("C:\\\\node")); //$NON-NLS-1$ //$NON-NLS-2$
        // An escaped backslash immediately followed by a genuine newline escape keeps
        // the backslash pair and still breaks the line at the newline.
        assertEquals("x\\\\\ny", McpHistoryView.renderEscapedNewlines("x\\\\\\ny")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ------------------------------------------------------------ matchesFilters

    @Test
    public void testNullRecordNeverMatches()
    {
        assertFalse(McpHistoryView.matchesFilters(null, false, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testNoFiltersMatchesEverything()
    {
        McpCallRecord r = record("initialize", null, TS, 0); //$NON-NLS-1$
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testToolsCallOnlyExcludesOtherMethods()
    {
        McpCallRecord other = record("tools/list", null, TS, 0); //$NON-NLS-1$
        McpCallRecord call = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 0); //$NON-NLS-1$
        assertFalse(McpHistoryView.matchesFilters(other, true, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(call, true, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testTextFilterMatchesSubstringCaseInsensitively()
    {
        McpCallRecord meta = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 0); //$NON-NLS-1$
        McpCallRecord list = record("tools/list", null, TS, 0); //$NON-NLS-1$
        // A substring of the tool key matches (tools/call keys on the tool name).
        assertTrue(McpHistoryView.matchesFilters(meta, false, "metadata", false, 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        // Case-insensitive on both sides.
        assertTrue(McpHistoryView.matchesFilters(meta, false, "META", false, 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        // A non-matching substring excludes the row.
        assertFalse(McpHistoryView.matchesFilters(list, false, "metadata", false, 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        // For a non-tools/call the key is the method, so the substring is matched there.
        assertTrue(McpHistoryView.matchesFilters(list, false, "LIST", false, 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        // Null/blank means "no text filter" -> keep everything.
        assertTrue(McpHistoryView.matchesFilters(list, false, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(list, false, "   ", false, 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testErrorsOnlyKeepsOnlyErrorOutcomes()
    {
        McpCallRecord ok = record("tools/list", null, TS, 0, OK); //$NON-NLS-1$
        McpCallRecord rpcError = record("tools/list", null, TS, 0, RPC_ERROR); //$NON-NLS-1$
        McpCallRecord toolError = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 0, TOOL_ERROR); //$NON-NLS-1$
        // errorsOnly off -> outcome is ignored.
        assertTrue(McpHistoryView.matchesFilters(ok, false, null, false, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        // errorsOnly on -> OK excluded, errors kept.
        assertFalse(McpHistoryView.matchesFilters(ok, false, null, true, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(rpcError, false, null, true, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(toolError, false, null, true, 0, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testMinDurationIsInclusiveLowerBound()
    {
        McpCallRecord r = record("tools/list", null, TS, 100); //$NON-NLS-1$
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 100, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 99, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertFalse(McpHistoryView.matchesFilters(r, false, null, false, 101, false,
            Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testIntervalBoundsAreInclusiveAndAppliedOnlyWhenOn()
    {
        McpCallRecord r = record("tools/list", null, 5_000L, 0); //$NON-NLS-1$
        // Inside [4000, 6000].
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 0, true, 4_000L, 6_000L));
        // On the inclusive bounds.
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 0, true, 5_000L, 5_000L));
        // Below the lower bound.
        assertFalse(McpHistoryView.matchesFilters(r, false, null, false, 0, true, 6_000L, 7_000L));
        // Above the upper bound.
        assertFalse(McpHistoryView.matchesFilters(r, false, null, false, 0, true, 1_000L, 4_000L));
        // intervalOn == false -> the window is ignored even though it would exclude.
        assertTrue(McpHistoryView.matchesFilters(r, false, null, false, 0, false, 6_000L, 7_000L));
    }

    @Test
    public void testFiltersComposeWithAnd()
    {
        McpCallRecord r =
            record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", 5_000L, 120, TOOL_ERROR); //$NON-NLS-1$
        // All five filters satisfied.
        assertTrue(McpHistoryView.matchesFilters(r, true, "meta", true, 100, true, 4_000L, 6_000L)); //$NON-NLS-1$
        // One failing filter (duration) fails the whole predicate.
        assertFalse(McpHistoryView.matchesFilters(r, true, "meta", true, 200, true, 4_000L, 6_000L)); //$NON-NLS-1$
        // The errors-only filter fails the whole predicate on an OK record.
        McpCallRecord ok =
            record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", 5_000L, 120, OK); //$NON-NLS-1$
        assertFalse(McpHistoryView.matchesFilters(ok, true, "meta", true, 100, true, 4_000L, 6_000L)); //$NON-NLS-1$
    }
}
