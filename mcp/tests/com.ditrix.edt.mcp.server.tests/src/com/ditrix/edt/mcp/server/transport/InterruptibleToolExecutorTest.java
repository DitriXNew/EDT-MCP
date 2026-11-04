/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.Test;

import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.UserSignal.SignalType;
import com.ditrix.edt.mcp.server.protocol.ClientCapabilities;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

/** Transport isolation at the protocol dispatch boundary; no live platform operation is started. */
public class InterruptibleToolExecutorTest
{
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"update_database\",\"arguments\":{\"confirm\":true}}}";
    private static final String RESULT = "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}";

    @Test
    public void brokenJsonResponseDoesNotCancelCompletedOperation() throws Exception
    {
        brokenResponseDoesNotCancelOperation(false);
    }

    @Test
    public void brokenSseResponseDoesNotCancelCompletedOperation() throws Exception
    {
        brokenResponseDoesNotCancelOperation(true);
    }

    private void brokenResponseDoesNotCancelOperation(boolean sse) throws Exception
    {
        try (Call call = new Call())
        {
            if (sse)
            {
                call.exchange.getRequestHeaders().add("Accept", "text/event-stream");
            }
            doThrow(new IOException("Broken pipe")).when(call.exchange).sendResponseHeaders(anyInt(), anyLong());
            call.start();
            // The peer closes while dispatch is still running. Response I/O is attempted only later.
            call.exchange.close();
            assertFalse(call.monitor.isCanceled());
            assertTrue(call.operationThread.get().isAlive());
            call.release.countDown();
            call.awaitRequest();
            assertTrue(call.completed.get());
            assertFalse(call.interrupted.get());
            assertFalse(call.monitor.isCanceled());
            verify(call.exchange).sendResponseHeaders(eq(200), anyLong());
        }
    }

    @Test
    public void interruptedHttpWaiterLeavesOperationRunningWithUnknownOutcome() throws Exception
    {
        try (Call call = new Call())
        {
            call.start();
            call.requestThread.interrupt();
            call.awaitRequest();
            assertFalse(call.completed.get());
            assertTrue(call.operationThread.get().isAlive());
            assertFalse(call.operationThread.get().isInterrupted());
            assertFalse(call.monitor.isCanceled());
            verify(call.exchange, never()).sendResponseHeaders(anyInt(), anyLong());
            call.release.countDown();
            call.awaitOperation();
            assertTrue(call.completed.get());
            assertFalse(call.interrupted.get());
        }
    }

    @Test
    public void sessionDeletionDoesNotCancelAnAlreadyDispatchedOperation() throws Exception
    {
        try (Call call = new Call())
        {
            call.start();
            HttpExchange deletion = mock(HttpExchange.class);
            when(deletion.getRequestMethod()).thenReturn("DELETE");
            Headers headers = new Headers();
            headers.add(McpConstants.HEADER_SESSION_ID, call.sessionId);
            when(deletion.getRequestHeaders()).thenReturn(headers);
            when(deletion.getResponseBody()).thenReturn(new ByteArrayOutputStream());
            call.http.handle(deletion);
            assertFalse(call.http.getSessions().isValid(call.sessionId));
            assertFalse(call.monitor.isCanceled());
            assertTrue(call.operationThread.get().isAlive());
            call.release.countDown();
            call.awaitRequest();
            assertTrue(call.completed.get());
            assertFalse(call.interrupted.get());
        }
    }

    @Test
    public void explicitUiSignalStillReturnsBeforeOperationCompletes() throws Exception
    {
        try (Call call = new Call())
        {
            call.start();
            assertTrue(call.server.interruptToolCall(new UserSignal(SignalType.CANCEL, "Stop waiting")));
            call.awaitRequest();
            assertFalse(call.completed.get());
            assertFalse(call.monitor.isCanceled());
            assertTrue(call.output.toString(StandardCharsets.UTF_8).contains("may still be running"));
            call.release.countDown();
            call.awaitOperation();
            assertTrue(call.completed.get());
            assertFalse(call.interrupted.get());
        }
    }

    @Test public void normalSseCompletionKeepsItsStatusHeadersAndSingleEvent() throws Exception
    {
        try (Call call = new Call())
        {
            call.exchange.getRequestHeaders().add("Accept", "text/event-stream");
            call.start(); call.release.countDown(); call.awaitRequest();
            assertEquals("text/event-stream", call.exchange.getResponseHeaders().getFirst("Content-Type"));
            assertEquals("event: message\nid: 1\ndata: " + RESULT + "\n\n", call.output.toString(StandardCharsets.UTF_8));
            verify(call.exchange).sendResponseHeaders(eq(200), anyLong());
        }
    }

    @Test public void interruptedSseRequestKeepsItsImmediateJsonResponse() throws Exception
    {
        try (Call call = new Call())
        {
            call.exchange.getRequestHeaders().add("Accept", "text/event-stream"); call.start();
            assertTrue(call.server.interruptToolCall(new UserSignal(SignalType.BACKGROUND, "Return control")));
            call.awaitRequest();
            assertEquals("application/json", call.exchange.getResponseHeaders().getFirst("Content-Type"));
            var response = com.google.gson.JsonParser.parseString(call.output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(7, response.get("id").getAsInt()); assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean());
            call.release.countDown(); call.awaitOperation();
            verify(call.exchange).sendResponseHeaders(eq(200), anyLong()); verify(call.exchange, times(2)).close();
        }
    }

    private static final class Call implements AutoCloseable
    {
        final McpServer server = new McpServer();
        final HttpExchange exchange = mock(HttpExchange.class);
        final NullProgressMonitor monitor = new NullProgressMonitor();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Thread> operationThread = new AtomicReference<>();
        final AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final McpHttpHandler http;
        final String sessionId;
        final Thread requestThread;

        Call() throws Exception
        {
            McpProtocolHandler protocol = mock(McpProtocolHandler.class);
            when(protocol.parse(BODY)).thenReturn(new McpProtocolHandler().parse(BODY));
            when(protocol.processRequest(eq(BODY), any(), anyLong(), any(), any())).thenAnswer(invocation -> {
                operationThread.set(Thread.currentThread());
                entered.countDown();
                try
                {
                    if (!release.await(10, TimeUnit.SECONDS))
                    {
                        throw new AssertionError("Operation was not released");
                    }
                    interrupted.set(Thread.currentThread().isInterrupted());
                    completed.set(true);
                    return RESULT;
                }
                catch (InterruptedException e)
                {
                    interrupted.set(true);
                    throw e;
                }
            });
            http = new McpHttpHandler(server, protocol, new InterruptibleToolExecutor(server, protocol), false);
            sessionId = http.getSessions().create(ClientCapabilities.ABSENT);
            Headers headers = new Headers();
            headers.add(McpConstants.HEADER_SESSION_ID, sessionId);
            when(exchange.getRequestMethod()).thenReturn("POST");
            when(exchange.getRequestHeaders()).thenReturn(headers);
            when(exchange.getResponseHeaders()).thenReturn(new Headers());
            when(exchange.getRequestBody()).thenReturn(
                new ByteArrayInputStream(BODY.getBytes(StandardCharsets.UTF_8)));
            when(exchange.getResponseBody()).thenReturn(output);
            requestThread = new Thread(() -> {
                try
                {
                    http.handle(exchange);
                }
                catch (Throwable e)
                {
                    requestFailure.set(e);
                }
            }, "test-http-waiter");
        }

        void start() throws Exception
        {
            requestThread.start();
            assertTrue("Dispatch did not start", entered.await(5, TimeUnit.SECONDS));
        }

        void awaitRequest() throws Exception
        {
            requestThread.join(5000);
            assertFalse("HTTP request did not finish", requestThread.isAlive());
            assertNull(requestFailure.get());
        }

        void awaitOperation() throws Exception
        {
            operationThread.get().join(5000);
            assertFalse("Dispatch did not finish", operationThread.get().isAlive());
        }

        @Override
        public void close() throws Exception
        {
            release.countDown();
            requestThread.join(5000);
            if (operationThread.get() != null)
            {
                awaitOperation();
            }
        }
    }
}
