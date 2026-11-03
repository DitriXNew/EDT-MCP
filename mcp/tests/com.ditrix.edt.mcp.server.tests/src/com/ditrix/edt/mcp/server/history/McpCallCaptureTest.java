/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.*;

import org.junit.Test;

/** Retention, enablement, and identity rules for late background updates. */
public class McpCallCaptureTest
{
    private static final String SIGNAL = "{\"result\":{\"isError\":true}}";
    private static final String OK = "{\"result\":{}}";

    @Test
    public void capturesKeepTheirOwnOutcomeWhenTheyCompleteInReverseOrder()
    {
        McpCallHistory history = new McpCallHistory();
        McpCallHistory.CallCapture first = history.beginCall("tools/call", "first", "{}", System.nanoTime());
        McpCallHistory.CallCapture second = history.beginCall("tools/call", "second", "{}", System.nanoTime());
        first.recordResponse(SIGNAL, true);
        second.recordResponse(SIGNAL, true);
        second.completed("{\"error\":{\"message\":\"Failed\"}}");
        first.completed(OK);
        first.recordResponse(OK, false);
        first.completed("{\"error\":{}}");
        assertEquals(2, history.size());
        assertEquals("first", history.snapshot().get(0).getToolName());
        assertEquals("success", history.snapshot().get(0).getBackgroundOutcome().getStatus());
        assertEquals("second", history.snapshot().get(1).getToolName());
        assertEquals("error", history.snapshot().get(1).getBackgroundOutcome().getStatus());
        assertEquals(SIGNAL, history.snapshot().get(0).getResponseJson());
    }

    @Test
    public void evictedCallIsNotRestoredByCompletion()
    {
        McpCallHistory history = new McpCallHistory();
        history.setBufferSize(HistoryConfig.MIN_BUFFER_SIZE);
        McpCallHistory.CallCapture call = history.beginCall("tools/call", "old", "{}", System.nanoTime());
        call.recordResponse(SIGNAL, true);
        for (int i = 0; i < HistoryConfig.MIN_BUFFER_SIZE; i++)
        {
            history.record("tools/call", "new", "{}", OK, 1);
        }
        call.completed(OK);
        assertEquals(HistoryConfig.MIN_BUFFER_SIZE, history.size());
        assertTrue(history.snapshot().stream().allMatch(row -> "new".equals(row.getToolName())));
    }

    @Test
    public void disabledSignalIsNotRecordedWhenRecordingIsEnabledLater()
    {
        McpCallHistory history = new McpCallHistory();
        history.setRecordingEnabled(false);
        McpCallHistory.CallCapture call = history.beginCall("tools/call", "off", "{}", System.nanoTime());
        call.recordResponse(SIGNAL, true);
        history.setRecordingEnabled(true);
        call.completed(OK);
        call.recordResponse(OK, false);
        assertEquals(0, history.size());
    }

    @Test
    public void signalKeepsOriginalPayloadSizesAndParseDuration()
    {
        McpCallHistory history = new McpCallHistory();
        String request = "x".repeat(McpCallHistory.MAX_PAYLOAD_CHARS + 100);
        McpCallHistory.CallCapture call = history.beginCall("tools/call", "large", request,
            System.nanoTime() - 2_000_000_000L);
        call.completed(null);
        call.recordResponse(SIGNAL, true);
        McpCallRecord row = history.snapshot().get(0);
        assertEquals(request.length(), row.getOriginalRequestChars());
        assertTrue(row.getRequestJson().contains(McpCallHistory.TRUNCATION_MARKER));
        assertEquals(SIGNAL.length(), row.getOriginalResponseChars());
        assertTrue(row.getDurationMs() >= 2000);
        assertEquals("error", row.getBackgroundOutcome().getStatus());
    }
}
