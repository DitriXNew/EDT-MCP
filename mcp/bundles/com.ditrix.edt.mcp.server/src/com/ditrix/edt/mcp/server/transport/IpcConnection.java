/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.StdioBootstrap;
import com.ditrix.edt.mcp.server.protocol.ClientCapabilities;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;
import com.google.gson.JsonElement;

/** One authenticated UDS peer with private capabilities, response waiters and notification sink. */
public final class IpcConnection implements AutoCloseable
{
    private final McpProtocolHandler dispatcher;
    private final Consumer<String> sink;
    private final InterruptibleToolExecutor executor;
    private final Set<Thread> requests = new HashSet<>();
    private volatile boolean closed;
    private ClientCapabilities capabilities = ClientCapabilities.ABSENT;
    private volatile boolean initialized;
    private boolean initializeReceived;

    public IpcConnection(McpServer server, McpProtocolHandler dispatcher, Consumer<String> sink)
    { this.dispatcher = dispatcher; this.sink = sink; this.executor = new InterruptibleToolExecutor(server, dispatcher); }

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
            capabilities = McpProtocolHandler.parseClientCapabilities(request);
            initializeReceived = true;
        }
        else if ("notifications/initialized".equals(request.getMethod()))
        { initialized = initializeReceived; return; }
        else if (!initialized && !"ping".equals(request.getMethod()))
        {
            if (!id.isJsonNull()) { send(StdioBootstrap.error(id, -32600, "Initialize this MCP connection first")); }
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
