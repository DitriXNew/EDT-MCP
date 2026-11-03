/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.ActiveToolCall;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.UserSignal.SignalType;
import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallRecord;
import com.ditrix.edt.mcp.server.history.StatsAggregator;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.ClientCapabilities;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.tools.impl.GetMcpHistoryTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/** Real protocol recorder and registered tool, with latches instead of timing-dependent races. */
public class InterruptibleToolHistoryTest
{
    private static final String NAME = "history_interrupt_probe";
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":577,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"" + NAME + "\",\"arguments\":{}}}";
    private final McpCallHistory history = McpCallHistory.getInstance();
    private final McpToolRegistry registry = McpToolRegistry.getInstance();

    @Before
    public void setUp()
    {
        history.clear();
        history.setRecordingEnabled(true);
        registry.clear();
    }

    @After
    public void tearDown()
    {
        registry.clear();
        history.clear();
    }

    @Test
    public void signalIsRecordedWhileOperationIsStillRunning() throws Exception
    {
        try (Call call = new Call(false))
        {
            call.start();
            assertTrue(call.signal());
            call.awaitRequest();
            McpCallRecord row = assertSignal(call);
            assertNull(row.getBackgroundOutcome());
            assertTrue(call.worker.get().isAlive());
            assertEquals(1, StatsAggregator.aggregate(history.snapshot()).getTotalErrors());
        }
    }

    @Test
    public void backgroundSuccessUpdatesSignalRowAndKeepsStats() throws Exception
    {
        assertCompletionAfterSignal(false);
    }

    @Test
    public void backgroundErrorUpdatesSignalRowAndKeepsStats() throws Exception
    {
        assertCompletionAfterSignal(true);
    }

    private void assertCompletionAfterSignal(boolean error) throws Exception
    {
        try (Call call = new Call(error))
        {
            call.start();
            assertTrue(call.signal());
            call.awaitRequest();
            McpCallRecord before = assertSignal(call);
            call.finishWork();
            McpCallRecord after = assertSignal(call);
            assertEquals(error ? "error" : "success", after.getBackgroundOutcome().getStatus());
            assertFalse(after.getBackgroundOutcome().getMessage().isEmpty());
            assertEquals(before.getTimestampMs(), after.getTimestampMs());
            assertEquals(before.getDurationMs(), after.getDurationMs());
            assertEquals(before.getOriginalResponseChars(), after.getOriginalResponseChars());
            assertNull("Snapshots remain immutable", before.getBackgroundOutcome());
            assertEquals(1, StatsAggregator.aggregate(history.snapshot()).getTotalCalls());
            assertEquals(1, StatsAggregator.aggregate(history.snapshot()).getTotalErrors());
            assertEquals(before.getOriginalResponseChars(),
                StatsAggregator.aggregate(history.snapshot()).getTotalOutputChars());
            assertEquals(1, call.appended);
            assertEquals(1, call.updated);

            JsonObject page = JsonParser.parseString(new GetMcpHistoryTool().execute(
                Map.of("tool", NAME, "status", "error", "includeStats", "true", "includeBodies", "true")))
                .getAsJsonObject();
            assertEquals(1, page.get("matched").getAsInt());
            JsonObject exposed = page.getAsJsonArray("records").get(0).getAsJsonObject();
            assertTrue(exposed.get("interrupted").getAsBoolean());
            assertEquals(after.getResponseJson(), exposed.get("responseJson").getAsString());
            assertEquals(error ? "error" : "success",
                exposed.getAsJsonObject("backgroundOutcome").get("status").getAsString());
        }
    }

    @Test
    public void backgroundFinishesBeforeSignalIsRecorded() throws Exception
    {
        try (Call call = new Call(false))
        {
            CountDownLatch writingSignal = new CountDownLatch(1);
            CountDownLatch releaseSignal = new CountDownLatch(1);
            doAnswer(invocation -> {
                writingSignal.countDown();
                assertTrue(releaseSignal.await(5, TimeUnit.SECONDS));
                return null;
            }).when(call.exchange).sendResponseHeaders(anyInt(), anyLong());
            call.start();
            AtomicReference<Boolean> sent = new AtomicReference<>();
            Thread signal = new Thread(() -> sent.set(call.signal()), "test-signal-writer");
            signal.start();
            try
            {
                assertTrue(writingSignal.await(5, TimeUnit.SECONDS));
                call.finishWork();
                assertEquals("Undelivered completion must not append", 0, history.size());
                verify(call.exchange, never()).close();
            }
            finally
            {
                releaseSignal.countDown();
                signal.join(5000);
            }
            assertFalse(signal.isAlive());
            assertEquals(Boolean.TRUE, sent.get());
            call.awaitRequest();
            assertEquals("success", assertSignal(call).getBackgroundOutcome().getStatus());
            assertEquals(1, call.appended);
            assertEquals(0, call.updated);
        }
    }

    @Test
    public void normalResponseWinsAgainstLateSignal() throws Exception
    {
        try (Call call = new Call(false))
        {
            call.start();
            ActiveToolCall active = call.server.getActiveToolCall();
            call.finishWork();
            call.awaitRequest();
            assertFalse(active.sendSignalResponse(new UserSignal(SignalType.CANCEL, "Too late")));
            assertEquals(1, history.size());
            McpCallRecord row = history.snapshot().get(0);
            assertFalse(row.isInterrupted());
            assertNull(row.getBackgroundOutcome());
            assertEquals(call.response.get(), row.getResponseJson());
            assertEquals(0, StatsAggregator.aggregate(history.snapshot()).getTotalErrors());
        }
    }

    @Test
    public void clearedSignalRowDoesNotReappearOnCompletion() throws Exception
    {
        try (Call call = new Call(false))
        {
            call.start();
            assertTrue(call.signal());
            call.awaitRequest();
            assertSignal(call);
            history.clear();
            call.finishWork();
            assertEquals(0, history.size());
        }
    }

    @Test
    public void truncatedSignalRemainsAnErrorInHistoryAndStats() throws Exception
    {
        try (Call call = new Call(false))
        {
            call.start();
            assertTrue(call.server.interruptToolCall(new UserSignal(SignalType.CUSTOM, "x".repeat(25000))));
            call.awaitRequest();
            assertEquals(1, history.size());
            McpCallRecord row = history.snapshot().get(0);
            assertTrue(row.isInterrupted());
            assertTrue(row.getOriginalResponseChars() > row.getResponseJson().length());
            assertEquals(1, StatsAggregator.aggregate(history.snapshot()).getTotalErrors());
            JsonObject page = JsonParser.parseString(new GetMcpHistoryTool().execute(
                Map.of("tool", NAME, "status", "error"))).getAsJsonObject();
            assertEquals(1, page.get("matched").getAsInt());
            assertEquals("error", page.getAsJsonArray("records").get(0).getAsJsonObject()
                .get("status").getAsString());
        }
    }

    private McpCallRecord assertSignal(Call call)
    {
        assertEquals(1, history.size());
        McpCallRecord row = history.snapshot().get(0);
        assertEquals(BODY, row.getRequestJson());
        assertEquals(call.output.toString(java.nio.charset.StandardCharsets.UTF_8), row.getResponseJson());
        assertTrue(row.isInterrupted());
        assertTrue(JsonParser.parseString(row.getResponseJson()).getAsJsonObject()
            .getAsJsonObject("result").get("isError").getAsBoolean());
        assertTrue(StatsAggregator.isErrorResponse(row.getResponseJson()));
        return row;
    }

    private final class Call implements AutoCloseable, McpCallHistory.HistoryListener
    {
        final McpServer server = new McpServer();
        final HttpExchange exchange = mock(HttpExchange.class);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final AtomicReference<String> response = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread request;
        int appended;
        int updated;

        Call(boolean error) throws Exception
        {
            when(exchange.getResponseHeaders()).thenReturn(new Headers());
            when(exchange.getResponseBody()).thenReturn(output);
            IMcpTool tool = mock(IMcpTool.class);
            when(tool.getName()).thenReturn(NAME);
            when(tool.getResponseType(anyMap())).thenReturn(IMcpTool.ResponseType.MARKDOWN);
            when(tool.execute(anyMap())).thenAnswer(invocation -> {
                worker.set(Thread.currentThread());
                entered.countDown();
                assertTrue("Work was not released", release.await(10, TimeUnit.SECONDS));
                return error ? ToolResult.error("Probe failed; retry the operation.").toJson() : "Completed probe";
            });
            registry.register(tool);
            history.addListener(this);
            McpProtocolHandler protocol = new McpProtocolHandler();
            McpHttpHandler http = new McpHttpHandler(server, protocol,
                new InterruptibleToolExecutor(server, protocol), false);
            Headers headers = new Headers();
            headers.add(McpConstants.HEADER_SESSION_ID, http.getSessions().create(ClientCapabilities.ABSENT));
            when(exchange.getRequestMethod()).thenReturn("POST");
            when(exchange.getRequestHeaders()).thenReturn(headers);
            when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
            request = new Thread(() -> {
                try
                {
                    http.handle(exchange);
                    response.set(output.toString(StandardCharsets.UTF_8));
                }
                catch (Throwable e)
                {
                    failure.set(e);
                }
            }, "test-history-request");
        }

        void start() throws Exception
        {
            request.start();
            assertTrue("Tool did not start", entered.await(5, TimeUnit.SECONDS));
        }

        boolean signal()
        {
            return server.interruptToolCall(new UserSignal(SignalType.CANCEL, "Stop waiting"));
        }

        void awaitRequest() throws Exception
        {
            request.join(5000);
            assertFalse("Request did not finish", request.isAlive());
            assertNull(failure.get());
        }

        void finishWork() throws Exception
        {
            release.countDown();
            worker.get().join(5000);
            assertFalse("Worker did not finish", worker.get().isAlive());
        }

        @Override
        public void onRecord(McpCallRecord record)
        {
            appended++;
        }

        @Override
        public void onUpdate(McpCallRecord record)
        {
            updated++;
        }

        @Override
        public void close() throws Exception
        {
            release.countDown();
            request.join(5000);
            if (worker.get() != null)
            {
                worker.get().join(5000);
            }
            history.removeListener(this);
        }
    }
}
