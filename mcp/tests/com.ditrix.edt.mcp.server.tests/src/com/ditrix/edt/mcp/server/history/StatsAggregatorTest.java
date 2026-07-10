/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.history.StatsAggregator.StatsResult;
import com.ditrix.edt.mcp.server.protocol.McpConstants;

/**
 * Tests for {@link StatsAggregator} and {@link ToolStats}.
 *
 * <p>These build {@code McpCallRecord} instances (authored in a sibling slice)
 * through its all-args constructor
 * {@code (long timestampMillis, String method, String toolName, String requestJson,
 * String responseJson, long durationMs)}. All construction is funnelled through
 * {@link #toolCall} / {@link #method} so the cross-slice contract lives in one place.
 */
public class StatsAggregatorTest
{
    private static final String OK_RESULT =
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"structuredContent\":{\"success\":true}}}";

    private static McpCallRecord toolCall(long ts, String tool, String request, String response, long durationMs)
    {
        return new McpCallRecord(ts, McpConstants.METHOD_TOOLS_CALL, tool, request, response, durationMs);
    }

    private static McpCallRecord method(long ts, String method, String response)
    {
        return new McpCallRecord(ts, method, null, "{}", response, 1L);
    }

    private static ToolStats row(StatsResult result, String key)
    {
        for (ToolStats row : result.getRows())
        {
            if (key.equals(row.getToolName()))
            {
                return row;
            }
        }
        throw new AssertionError("No row for key: " + key); //$NON-NLS-1$
    }

    @Test
    public void testEmptyBufferAndNull()
    {
        for (StatsResult result : new StatsResult[] {
            StatsAggregator.aggregate(Collections.emptyList()), StatsAggregator.aggregate(null) })
        {
            assertTrue(result.getRows().isEmpty());
            assertTrue(result.getTop3().isEmpty());
            assertEquals(0L, result.getTotalCalls());
            assertEquals(0L, result.getTotalOutputChars());
            assertEquals(0L, result.getApproxTotalTokens());
            assertEquals(0L, result.getTotalErrors());
            assertEquals("~0", result.getApproxTotalTokensDisplay());
        }
    }

    @Test
    public void testSharePercentSumsToAboutHundred()
    {
        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(toolCall(1, "a", "{}", OK_RESULT, 5));
        snap.add(toolCall(2, "a", "{}", OK_RESULT, 5));
        snap.add(toolCall(3, "b", "{}", OK_RESULT, 5));
        snap.add(method(4, McpConstants.METHOD_INITIALIZE, OK_RESULT));

        StatsResult result = StatsAggregator.aggregate(snap);

        assertEquals(4L, result.getTotalCalls());
        double sum = 0;
        for (ToolStats stat : result.getRows())
        {
            sum += stat.getSharePercent();
        }
        assertEquals(100.0, sum, 0.0001);
        assertEquals(50.0, row(result, "a").getSharePercent(), 0.0001);
        assertEquals(25.0, row(result, "b").getSharePercent(), 0.0001);
    }

    @Test
    public void testContextWeightSortOrderAndTop3()
    {
        List<McpCallRecord> snap = new ArrayList<>();
        // Five distinct tools with strictly increasing response sizes.
        for (int i = 1; i <= 5; i++)
        {
            StringBuilder resp = new StringBuilder();
            for (int c = 0; c < i * 10; c++)
            {
                resp.append('x');
            }
            snap.add(toolCall(i, "tool" + i, "{}", resp.toString(), 1));
        }

        StatsResult result = StatsAggregator.aggregate(snap);

        // Heaviest first: tool5 > tool4 > ... > tool1.
        List<ToolStats> rows = result.getRows();
        for (int i = 0; i < rows.size() - 1; i++)
        {
            assertTrue("rows must be sorted by context weight desc",
                rows.get(i).getContextWeight() >= rows.get(i + 1).getContextWeight());
        }
        assertEquals("tool5", rows.get(0).getToolName());

        List<ToolStats> top = result.getTop3();
        assertEquals(3, top.size());
        assertEquals("tool5", top.get(0).getToolName());
        assertEquals("tool4", top.get(1).getToolName());
        assertEquals("tool3", top.get(2).getToolName());
    }

    @Test
    public void testAvgAndTotalDuration()
    {
        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(toolCall(1, "t", "{}", OK_RESULT, 10));
        snap.add(toolCall(2, "t", "{}", OK_RESULT, 30));

        ToolStats stat = row(StatsAggregator.aggregate(snap), "t");
        assertEquals(2L, stat.getCalls());
        assertEquals(40L, stat.getTotalDurationMs());
        assertEquals(20L, stat.getAvgDurationMs());
    }

    @Test
    public void testCharAndWordCountsAndContextWeight()
    {
        String request = "abcde"; // 5 chars, 1 word
        String response = "one two three"; // 13 chars, 3 words
        ToolStats stat =
            row(StatsAggregator.aggregate(List.of(toolCall(1, "t", request, response, 7))), "t");

        assertEquals(request.length(), stat.getRequestChars());
        assertEquals(1L, stat.getRequestWords());
        assertEquals(response.length(), stat.getResponseChars());
        assertEquals(3L, stat.getResponseWords());

        long expectedTokens = response.length() / 4; // 13/4 == 3
        assertEquals(expectedTokens, stat.getApproxTokens());
        assertEquals("~" + expectedTokens, stat.getApproxTokensDisplay());
        // context weight = response chars + approx tokens + response words
        assertEquals(response.length() + expectedTokens + 3, stat.getContextWeight());
    }

    @Test
    public void testErrorShapeTopLevelJsonRpcError()
    {
        String response = "{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        ToolStats stat = row(StatsAggregator.aggregate(List.of(toolCall(1, "t", "{}", response, 1))), "t");
        assertEquals(1L, stat.getErrorCount());
        assertTrue(StatsAggregator.isErrorResponse(response));
    }

    @Test
    public void testErrorShapeResultIsErrorAndSuccessFalse()
    {
        String isErrorShape = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true,"
            + "\"structuredContent\":{\"success\":false,\"error\":\"boom\"}}}";
        String successFalseOnly =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"structuredContent\":{\"success\":false}}}";

        assertTrue(StatsAggregator.isErrorResponse(isErrorShape));
        assertTrue(StatsAggregator.isErrorResponse(successFalseOnly));

        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(toolCall(1, "t", "{}", isErrorShape, 1));
        snap.add(toolCall(2, "t", "{}", successFalseOnly, 1));
        assertEquals(2L, row(StatsAggregator.aggregate(snap), "t").getErrorCount());
    }

    @Test
    public void testSuccessNotFlaggedByMereErrorKey()
    {
        // A SUCCESSFUL result that merely carries an "error" field (e.g. a
        // diagnostics list) must stay OK: classification is structural (success
        // boolean / isError boolean), not a substring match on the word "error".
        String okWithErrorField = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":false,"
            + "\"structuredContent\":{\"success\":true,\"error\":[],\"text\":\"an error occurred earlier\"}}}";

        assertFalse(StatsAggregator.isErrorResponse(okWithErrorField));
        assertFalse(StatsAggregator.isErrorResponse(OK_RESULT));
        assertFalse(StatsAggregator.isErrorResponse(null));
        assertFalse(StatsAggregator.isErrorResponse("not json"));

        ToolStats stat = row(StatsAggregator.aggregate(List.of(toolCall(1, "t", "{}", okWithErrorField, 1))), "t");
        assertEquals(0L, stat.getErrorCount());
    }

    @Test
    public void testNonToolsCallKeyedByMethod()
    {
        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(method(1, McpConstants.METHOD_INITIALIZE, OK_RESULT));
        snap.add(method(2, McpConstants.METHOD_TOOLS_LIST, OK_RESULT));
        snap.add(method(3, McpConstants.METHOD_INITIALIZED, OK_RESULT));
        // A tools/call with a blank tool name keys by its method (tools/call), not a tool.
        snap.add(toolCall(4, "  ", "{}", OK_RESULT, 1));
        snap.add(toolCall(5, "real_tool", "{}", OK_RESULT, 1));

        StatsResult result = StatsAggregator.aggregate(snap);

        // Each JSON-RPC method now gets its own row (so tools/list is visible on its own),
        // plus the one named tool — five distinct rows, not one lumped bucket.
        assertEquals(5, result.getRows().size());
        assertEquals(1L, row(result, McpConstants.METHOD_INITIALIZE).getCalls());
        assertEquals(1L, row(result, McpConstants.METHOD_TOOLS_LIST).getCalls());
        assertEquals(1L, row(result, McpConstants.METHOD_INITIALIZED).getCalls());
        assertEquals(1L, row(result, McpConstants.METHOD_TOOLS_CALL).getCalls());
        assertEquals(1L, row(result, "real_tool").getCalls());
    }

    @Test
    public void testTimeWindowSlice()
    {
        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(toolCall(100, "t100", "{}", OK_RESULT, 1));
        snap.add(toolCall(200, "t200", "{}", OK_RESULT, 1));
        snap.add(toolCall(300, "t300", "{}", OK_RESULT, 1));

        assertEquals(3L, StatsAggregator.aggregate(snap).getTotalCalls());

        // Half-open [from, to): 100 excluded (below), 300 excluded (== to).
        StatsResult mid = StatsAggregator.aggregate(snap, 150, 300);
        assertEquals(1L, mid.getTotalCalls());
        assertEquals("t200", mid.getRows().get(0).getToolName());

        // Inclusive lower bound: 100 and 200 in, 300 out.
        assertEquals(2L, StatsAggregator.aggregate(snap, 100, 300).getTotalCalls());
        // Widened upper bound includes 300.
        assertEquals(3L, StatsAggregator.aggregate(snap, 100, 301).getTotalCalls());
    }

    @Test
    public void testSummaryTotals()
    {
        String r1 = "aaaaaaaa"; // 8 chars
        String r2 = "bbbb"; // 4 chars
        List<McpCallRecord> snap = new ArrayList<>();
        snap.add(toolCall(1, "a", "{}", r1, 1));
        snap.add(toolCall(2, "b", "{}", r2, 1));

        StatsResult result = StatsAggregator.aggregate(snap);
        assertEquals(2L, result.getTotalCalls());
        assertEquals((long)(r1.length() + r2.length()), result.getTotalOutputChars());
        assertEquals((r1.length() + r2.length()) / 4L, result.getApproxTotalTokens());
        assertEquals("~" + result.getApproxTotalTokens(), result.getApproxTotalTokensDisplay());
    }
}
