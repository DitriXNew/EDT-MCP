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

import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link GetMcpHistoryTool}.
 * <p>
 * Covers the tool metadata + schema contract (headless), and drives the real
 * filter/format path against the shared in-memory {@link McpCallHistory} ring — the
 * tool is read-only (a snapshot; no model / EDT), so the whole execute() path is
 * unit-testable without a live workbench. Each test resets the singleton ring so the
 * assertions are deterministic.
 */
public class GetMcpHistoryToolTest
{
    /** A non-error JSON-RPC response envelope (classified OK by StatsAggregator). */
    private static final String OK_RESPONSE = "{\"result\":{\"content\":[]}}"; //$NON-NLS-1$

    /** A JSON-RPC top-level error envelope (classified as an error outcome). */
    private static final String ERROR_RESPONSE = "{\"error\":{\"code\":-32000,\"message\":\"boom\"}}"; //$NON-NLS-1$

    private static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    private McpCallHistory history;

    @Before
    public void setUp()
    {
        history = McpCallHistory.getInstance();
        history.setRecordingEnabled(true);
        history.setBufferSize(500);
        history.clear();
    }

    @After
    public void tearDown()
    {
        history.clear();
    }

    // ==================== metadata / schema contract ====================

    @Test
    public void testName()
    {
        assertEquals("get_mcp_history", new GetMcpHistoryTool().getName()); //$NON-NLS-1$
    }

    @Test
    public void testNameConstant()
    {
        assertEquals(GetMcpHistoryTool.NAME, new GetMcpHistoryTool().getName());
    }

    @Test
    public void testResponseTypeJson()
    {
        // JSON so the records[] / stats land in structuredContent (BuiltInToolOutputSchemaTest).
        assertEquals(ResponseType.JSON, new GetMcpHistoryTool().getResponseType());
    }

    @Test
    public void testDescriptionNotEmptyAndSteersToGuide()
    {
        String desc = new GetMcpHistoryTool().getDescription();
        assertNotNull(desc);
        assertTrue(desc.length() > 0);
        assertTrue("description must steer to the on-demand guide", //$NON-NLS-1$
            desc.contains("get_tool_guide('get_mcp_history')")); //$NON-NLS-1$
    }

    @Test
    public void testInputSchemaDeclaresAllParametersLowerCamelCase()
    {
        String schema = new GetMcpHistoryTool().getInputSchema();
        assertNotNull(schema);
        // Every param read in execute() must be declared (CLAUDE.md don't #6), lowerCamelCase.
        for (String param : new String[] {"tool", "status", "minDurationMs", "sinceMs", "untilMs", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "limit", "includeBodies", "includeStats"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            assertTrue("schema must declare " + param, schema.contains("\"" + param + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testStatusIsAClosedEnumInSchema()
    {
        String schema = new GetMcpHistoryTool().getInputSchema();
        assertTrue("status must expose its accepted values as an enum", schema.contains("\"enum\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("status enum must list all", schema.contains("\"all\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("status enum must list error", schema.contains("\"error\"")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("status enum must list ok", schema.contains("\"ok\"")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testNoParameterIsRequired()
    {
        // Every filter is optional (a bare call returns the recent page), so required must be empty.
        String schema = new GetMcpHistoryTool().getInputSchema();
        int requiredIdx = schema.indexOf("\"required\""); //$NON-NLS-1$
        assertTrue("schema must declare a required array", requiredIdx >= 0); //$NON-NLS-1$
        int open = schema.indexOf('[', requiredIdx);
        int close = schema.indexOf(']', open);
        assertTrue("required array must be well-formed", open >= 0 && close > open); //$NON-NLS-1$
        String requiredBlock = schema.substring(open, close + 1);
        assertEquals("no parameter must be required", "[]", requiredBlock.replaceAll("\\s", "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void testOutputSchemaDeclaresTheResultEnvelope()
    {
        String schema = new GetMcpHistoryTool().getOutputSchema();
        assertNotNull(schema);
        for (String field : new String[] {"success", "matched", "returned", "limit", "totalRecorded", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "recording", "bufferSize", "records", "stats"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertTrue("outputSchema must declare " + field, schema.contains("\"" + field + "\"")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    @Test
    public void testGuideDocumentsBodiesStatsAndPii()
    {
        String guide = new GetMcpHistoryTool().getGuide();
        assertNotNull(guide);
        assertTrue(guide.length() > 0);
        assertTrue("guide must document includeStats", guide.contains("includeStats")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must document includeBodies", guide.contains("includeBodies")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("guide must flag the PII / personal-data nature", //$NON-NLS-1$
            guide.toUpperCase().contains("PII") || guide.toLowerCase().contains("personal data")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== bad enum (returns before touching the ring) ====================

    @Test
    public void testInvalidStatusIsRejectedActionably()
    {
        Map<String, String> params = new HashMap<>();
        params.put("status", "sideways"); //$NON-NLS-1$ //$NON-NLS-2$
        String result = new GetMcpHistoryTool().execute(params);
        assertTrue("must reject with an error", result.contains("\"success\":false") //$NON-NLS-1$ //$NON-NLS-2$
            || result.contains("\"error\"")); //$NON-NLS-1$
        assertTrue("invalid status must echo the bad value", result.contains("sideways")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid status must list all", result.contains("all")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid status must list error", result.contains("error")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("invalid status must list ok", result.contains("ok")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ==================== execute over the ring ====================

    @Test
    public void testEmptyHistoryReturnsEmptyPage()
    {
        JsonObject out = execute(new HashMap<>());
        assertTrue("empty history is still a success envelope", out.get("success").getAsBoolean()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, out.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals(0, out.get("returned").getAsInt()); //$NON-NLS-1$
        assertEquals(0, out.getAsJsonArray("records").size()); //$NON-NLS-1$
        assertFalse("stats must be absent unless includeStats is set", out.has("stats")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testHappyPathReturnsMetadataOnlyByDefault()
    {
        history.record(METHOD_TOOLS_CALL, "list_projects", "{\"a\":1}", OK_RESPONSE, 12L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "get_markers", "{\"b\":2}", OK_RESPONSE, 34L); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject out = execute(new HashMap<>());
        assertEquals(2, out.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals(2, out.get("returned").getAsInt()); //$NON-NLS-1$

        JsonObject rec = out.getAsJsonArray("records").get(0).getAsJsonObject(); //$NON-NLS-1$
        // Metadata is present ...
        for (String field : new String[] {"timestampMs", "method", "tool", "durationMs", "status", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "requestChars", "responseChars"}) //$NON-NLS-1$ //$NON-NLS-2$
        {
            assertTrue("record must carry metadata field " + field, rec.has(field)); //$NON-NLS-1$
        }
        // ... but the raw bodies are NOT included by default.
        assertFalse("requestJson must be omitted by default", rec.has("requestJson")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("responseJson must be omitted by default", rec.has("responseJson")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIncludeBodiesAttachesRawPayloads()
    {
        history.record(METHOD_TOOLS_CALL, "list_projects", "{\"req\":true}", OK_RESPONSE, 5L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("includeBodies", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        JsonObject rec = out.getAsJsonArray("records").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("{\"req\":true}", rec.get("requestJson").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(OK_RESPONSE, rec.get("responseJson").getAsString()); //$NON-NLS-1$
    }

    @Test
    public void testToolSubstringFilter()
    {
        history.record(METHOD_TOOLS_CALL, "get_markers", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "list_projects", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("tool", "MARK"); // case-insensitive substring over the key //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        assertEquals(1, out.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals("get_markers", //$NON-NLS-1$
            out.getAsJsonArray("records").get(0).getAsJsonObject().get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testFilterKeyedByMethodForNonToolCall()
    {
        // A non-tools/call exchange is keyed by its method (StatsAggregator.keyOf), so the tool
        // filter matches the method name.
        history.record(METHOD_TOOLS_CALL, "get_markers", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record("tools/list", null, "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("tool", "tools/list"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        assertEquals(1, out.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals("tools/list", //$NON-NLS-1$
            out.getAsJsonArray("records").get(0).getAsJsonObject().get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testStatusFilterUsesTheSharedClassifier()
    {
        history.record(METHOD_TOOLS_CALL, "ok_tool", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "bad_tool", "{}", ERROR_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> errParams = new HashMap<>();
        errParams.put("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject errOut = execute(errParams);
        assertEquals(1, errOut.get("matched").getAsInt()); //$NON-NLS-1$
        JsonObject errRec = errOut.getAsJsonArray("records").get(0).getAsJsonObject(); //$NON-NLS-1$
        assertEquals("bad_tool", errRec.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("error", errRec.get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> okParams = new HashMap<>();
        okParams.put("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject okOut = execute(okParams);
        assertEquals(1, okOut.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals("ok", //$NON-NLS-1$
            okOut.getAsJsonArray("records").get(0).getAsJsonObject().get("status").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testMinDurationFilter()
    {
        history.record(METHOD_TOOLS_CALL, "fast", "{}", OK_RESPONSE, 10L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "slow", "{}", OK_RESPONSE, 5000L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("minDurationMs", "1000"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        assertEquals(1, out.get("matched").getAsInt()); //$NON-NLS-1$
        assertEquals("slow", //$NON-NLS-1$
            out.getAsJsonArray("records").get(0).getAsJsonObject().get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testTimeWindowIsHalfOpen()
    {
        history.record(METHOD_TOOLS_CALL, "only", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        // Read the real timestamp back so the window bounds are exact.
        long t = execute(new HashMap<>()).getAsJsonArray("records").get(0).getAsJsonObject() //$NON-NLS-1$
            .get("timestampMs").getAsLong(); //$NON-NLS-1$

        assertEquals("sinceMs == t is inclusive", 1, matchedFor("sinceMs", t)); //$NON-NLS-1$
        assertEquals("sinceMs == t+1 excludes t", 0, matchedFor("sinceMs", t + 1)); //$NON-NLS-1$
        assertEquals("untilMs == t is exclusive", 0, matchedFor("untilMs", t)); //$NON-NLS-1$
        assertEquals("untilMs == t+1 includes t", 1, matchedFor("untilMs", t + 1)); //$NON-NLS-1$
    }

    @Test
    public void testLimitCapsPageNewestFirst()
    {
        history.record(METHOD_TOOLS_CALL, "call_1", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "call_2", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "call_3", "{}", OK_RESPONSE, 1L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("limit", "2"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        assertEquals("matched counts every match, not just the page", 3, out.get("matched").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray records = out.getAsJsonArray("records"); //$NON-NLS-1$
        assertEquals(2, records.size());
        // Newest first: the last recorded call heads the page.
        assertEquals("call_3", records.get(0).getAsJsonObject().get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("call_2", records.get(1).getAsJsonObject().get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testIncludeStatsAppendsAggregatedBlock()
    {
        history.record(METHOD_TOOLS_CALL, "list_projects", "{}", OK_RESPONSE, 10L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "list_projects", "{}", OK_RESPONSE, 20L); //$NON-NLS-1$ //$NON-NLS-2$
        history.record(METHOD_TOOLS_CALL, "get_markers", "{}", ERROR_RESPONSE, 30L); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, String> params = new HashMap<>();
        params.put("includeStats", "true"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject out = execute(params);

        assertTrue("stats block must be present with includeStats", out.has("stats")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject stats = out.getAsJsonObject("stats"); //$NON-NLS-1$
        assertTrue(stats.has("summary")); //$NON-NLS-1$
        assertTrue(stats.has("rows")); //$NON-NLS-1$
        assertTrue(stats.has("top3")); //$NON-NLS-1$

        JsonObject summary = stats.getAsJsonObject("summary"); //$NON-NLS-1$
        // Aggregated over the filtered set == everything here (3 calls, 1 error).
        assertEquals(3L, summary.get("totalCalls").getAsLong()); //$NON-NLS-1$
        assertEquals(1L, summary.get("totalErrors").getAsLong()); //$NON-NLS-1$
        assertEquals(out.get("matched").getAsInt(), summary.get("totalCalls").getAsInt()); //$NON-NLS-1$ //$NON-NLS-2$
        // Two distinct keys -> two rows.
        assertEquals(2, stats.getAsJsonArray("rows").size()); //$NON-NLS-1$
    }

    // ==================== helpers ====================

    private JsonObject execute(Map<String, String> params)
    {
        String json = new GetMcpHistoryTool().execute(params);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private int matchedFor(String key, long value)
    {
        Map<String, String> params = new HashMap<>();
        params.put(key, String.valueOf(value));
        return execute(params).get("matched").getAsInt(); //$NON-NLS-1$
    }
}
