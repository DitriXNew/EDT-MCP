/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.StdioBootstrap;
import com.ditrix.edt.mcp.server.protocol.ClientCapabilities;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import com.google.gson.JsonElement;

/** One authenticated UDS peer with private capabilities, response waiters and notification sink. */
public final class IpcConnection implements AutoCloseable
{
    private final McpServer server;
    private final McpProtocolHandler dispatcher;
    private final Consumer<String> sink;
    /** Closes the peer; used when a bounded send cannot make room. */
    private final Runnable detach;
    private final InterruptibleToolExecutor executor;
    private final Boolean plainTextMode;
    /** How long a client gets to take the shutdown receipt before its socket is closed. */
    private static final long RECEIPT_TIMEOUT_MILLIS = 5000;

    /** A shutdown notification frame; {@code params} is null for the plain accepted/refused ones. */
    private static String receipt(String method, String params)
    {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\""
            + (params == null ? "" : ",\"params\":{" + params + "}") + "}";
    }
    private final Set<Thread> requests = new HashSet<>();
    private volatile boolean closed;
    private ClientCapabilities capabilities = ClientCapabilities.ABSENT;
    private volatile boolean initialized;
    private boolean initializeReceived;

    public IpcConnection(McpServer server, McpProtocolHandler dispatcher, Consumer<String> sink)
    { this(server, dispatcher, sink, null); }

    public IpcConnection(McpServer server, McpProtocolHandler dispatcher, Consumer<String> sink, Boolean plainTextMode)
    { this(server, dispatcher, sink, plainTextMode, () -> { }); }

    /**
     * @param detach closes the peer, so a send that cannot make room releases the socket instead
     *            of waiting for a client that stopped reading
     */
    public IpcConnection(McpServer server, McpProtocolHandler dispatcher, Consumer<String> sink,
        Boolean plainTextMode, Runnable detach)
    {
        this.server = server;
        this.dispatcher = dispatcher; this.sink = sink; this.detach = detach;
        this.executor = new InterruptibleToolExecutor(server, dispatcher);
        this.plainTextMode = plainTextMode;
    }

    public void accept(String frame) throws IOException
    {
        if (closed) { return; }
        long start = System.nanoTime();
        JsonRpcRequest request;
        try { request = GsonProvider.get().fromJson(frame, JsonRpcRequest.class); }
        catch (RuntimeException e)
        { send(StdioBootstrap.error(JsonNull.INSTANCE, -32700, "Invalid JSON")); return; }
        if (request == null || !"2.0".equals(request.getJsonrpc()) || request.getMethod() == null)
        { send(StdioBootstrap.error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request")); return; }
        var tree = JsonParser.parseString(frame).getAsJsonObject();
        var id = tree.has("id") ? tree.get("id") : JsonNull.INSTANCE;
        if ("initialize".equals(request.getMethod()))
        {
            if (initializeReceived)
            { send(StdioBootstrap.error(id, -32600, "Already initialized")); return; }
            capabilities = McpProtocolHandler.parseClientCapabilities(request).withPlainTextMode(plainTextMode);
            initializeReceived = true;
        }
        else if ("notifications/initialized".equals(request.getMethod()))
        { initialized = initializeReceived; return; }
        else if (!initialized && !"ping".equals(request.getMethod()))
        {
            if (!id.isJsonNull()) { send(StdioBootstrap.error(id, -32600, "Initialize this MCP connection first")); }
            return;
        }
        else if (McpConstants.METHOD_HOST_SHUTDOWN.equals(request.getMethod()))
        {
            // The connection's handshake secret plus this gate are the whole authorization. The
            // answer is immediate: "accepted" means this host is going down and the caller must
            // wait for the exit, "refused" means it keeps running and must NOT be killed.
            if (!tree.has("id")) {
                // Answer before starting: the accepted receipt must reach the client before the
                // host begins closing, and the refused one tells it the process keeps running.
                boolean accepted = server.hostShutdownArmed();
                sendBounded(receipt(accepted ? McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED
                    : McpConstants.METHOD_HOST_SHUTDOWN_REFUSED, null));
                if (accepted) { server.requestHostShutdown(success -> sendBounded(receipt(
                    McpConstants.METHOD_HOST_SHUTDOWN_COMPLETE, "\"success\":" + success))); }
            }
            else
            {
                // A request with an id expects a response, and an unanswered id leaves the
                // client waiting forever. Answer it, without starting anything.
                send(StdioBootstrap.error(id, -32600,
                    McpConstants.METHOD_HOST_SHUTDOWN + " is a notification; send it without an id."));
            }
            return;
        }
        if ("tools/call".equals(request.getMethod()))
        { startToolCall(frame, request, start, id); return; }
        String response = dispatcher.processRequest(frame, request, start, capabilities);
        if (response != null && !id.isJsonNull()) { send(response); }
    }

    private void startToolCall(String frame, JsonRpcRequest request, long start, JsonElement id)
    {
        ClientCapabilities client = capabilities;
        Thread waiter = new Thread(() -> {
            try
            {
                ToolCallResponder responder = new ToolCallResponder.Ipc(response -> { if (!id.isJsonNull()) { send(response); } });
                String response;
                try { response = executor.execute(responder, frame, request, start, client); }
                catch (Exception e)
                { com.ditrix.edt.mcp.server.Activator.logError("IPC tool execution failed", e);
                  response = StdioBootstrap.error(id, -32603, "EDT tool execution failed; its outcome may be unknown. Inspect EDT before retrying."); }
                if (response != null) { responder.send(response); }
            }
            catch (IOException | RuntimeException e) { close(); }
            finally { synchronized (requests) { requests.remove(Thread.currentThread()); } }
        }, "edt-mcp-ipc-request");
        waiter.setDaemon(true);
        synchronized (requests)
        { if (!closed) { requests.add(waiter); waiter.start(); } }
    }

    private void send(String response)
    { synchronized (sink) { if (!closed) { sink.accept(response); } } }

    /**
     * Sends the shutdown receipt without letting a client that stopped reading hold the shutdown
     * open. The receipt is written off the shutdown thread and the wait is bounded; when the
     * socket cannot take it in time, the peer is closed so the write cannot block the close
     * sequence that follows.
     *
     * @param response the receipt frame
     */
    private void sendBounded(String response)
    {
        var writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "edt-mcp-shutdown-receipt");
            thread.setDaemon(true);
            return thread;
        });
        try
        {
            writer.submit(() -> send(response)).get(RECEIPT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e)
        {
            // Nobody is reading: drop the peer so the socket write cannot pin the shutdown.
            try { detach.run(); } catch (RuntimeException ignored) { /* the peer is going away anyway */ }
            Activator.logInfo("The client did not take the shutdown receipt within "
                + RECEIPT_TIMEOUT_MILLIS + " ms; its connection was closed and the shutdown continues.");
        }
        catch (ExecutionException e)
        {
            Activator.logError("Cannot deliver the shutdown receipt", e.getCause());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally { writer.shutdownNow(); }
    }

    public void toolsChanged()
    {
        if (initialized) { send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"); }
    }

    @Override public void close()
    {
        // Detach response waiters; the shared executor leaves EDT operations running.
        synchronized (requests) { closed = true; requests.forEach(Thread::interrupt); }
    }
}
