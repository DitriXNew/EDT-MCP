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
 * composition of the four filters (tools/call toggle, method/tool key,
 * minimum duration, inclusive time interval) and the {@code statKey} grouping
 * that must mirror {@code StatsAggregator.keyOf}. The filter UI itself (SWT
 * widgets) is verified live.
 */
public class McpHistoryViewFilterTest
{
    private static final long TS = 1_000_000L;

    private static McpCallRecord record(String method, String tool, long timestampMs, long durationMs)
    {
        return new McpCallRecord(timestampMs, method, tool, "{}", "{}", durationMs); //$NON-NLS-1$ //$NON-NLS-2$
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
        // "(unknown)" bucket so the filter combo lines up 1:1 with the stats rows.
        assertEquals(StatsAggregator.NON_TOOL_METHODS_KEY,
            McpHistoryView.statKey(record(null, null, TS, 5)));
    }

    // ------------------------------------------------------------ matchesFilters

    @Test
    public void testNullRecordNeverMatches()
    {
        assertFalse(McpHistoryView.matchesFilters(null, false, null, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testNoFiltersMatchesEverything()
    {
        McpCallRecord r = record("initialize", null, TS, 0); //$NON-NLS-1$
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testToolsCallOnlyExcludesOtherMethods()
    {
        McpCallRecord other = record("tools/list", null, TS, 0); //$NON-NLS-1$
        McpCallRecord call = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 0); //$NON-NLS-1$
        assertFalse(McpHistoryView.matchesFilters(other, true, null, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(call, true, null, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testSelectedKeyMatchesOnlyThatKey()
    {
        McpCallRecord meta = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", TS, 0); //$NON-NLS-1$
        McpCallRecord list = record("tools/list", null, TS, 0); //$NON-NLS-1$
        assertTrue(McpHistoryView.matchesFilters(meta, false, "get_metadata", 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        assertFalse(McpHistoryView.matchesFilters(list, false, "get_metadata", 0, false, //$NON-NLS-1$
            Long.MIN_VALUE, Long.MAX_VALUE));
        // A null selected key means "(all)" -> no key filter.
        assertTrue(McpHistoryView.matchesFilters(list, false, null, 0, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testMinDurationIsInclusiveLowerBound()
    {
        McpCallRecord r = record("tools/list", null, TS, 100); //$NON-NLS-1$
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 100, false, Long.MIN_VALUE, Long.MAX_VALUE));
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 99, false, Long.MIN_VALUE, Long.MAX_VALUE));
        assertFalse(McpHistoryView.matchesFilters(r, false, null, 101, false, Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void testIntervalBoundsAreInclusiveAndAppliedOnlyWhenOn()
    {
        McpCallRecord r = record("tools/list", null, 5_000L, 0); //$NON-NLS-1$
        // Inside [4000, 6000].
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 0, true, 4_000L, 6_000L));
        // On the inclusive bounds.
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 0, true, 5_000L, 5_000L));
        // Below the lower bound.
        assertFalse(McpHistoryView.matchesFilters(r, false, null, 0, true, 6_000L, 7_000L));
        // Above the upper bound.
        assertFalse(McpHistoryView.matchesFilters(r, false, null, 0, true, 1_000L, 4_000L));
        // intervalOn == false -> the window is ignored even though it would exclude.
        assertTrue(McpHistoryView.matchesFilters(r, false, null, 0, false, 6_000L, 7_000L));
    }

    @Test
    public void testFiltersComposeWithAnd()
    {
        McpCallRecord r = record(McpConstants.METHOD_TOOLS_CALL, "get_metadata", 5_000L, 120); //$NON-NLS-1$
        // All four filters satisfied.
        assertTrue(McpHistoryView.matchesFilters(r, true, "get_metadata", 100, true, 4_000L, 6_000L)); //$NON-NLS-1$
        // One failing filter (duration) fails the whole predicate.
        assertFalse(McpHistoryView.matchesFilters(r, true, "get_metadata", 200, true, 4_000L, 6_000L)); //$NON-NLS-1$
    }
}
