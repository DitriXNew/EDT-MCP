/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.util.function.Consumer;
import com.ditrix.edt.mcp.server.ipc.StdioBootstrap;
import com.ditrix.edt.mcp.server.protocol.ClientCapabilities;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.google.gson.JsonParser;
import com.google.gson.JsonNull;

/** One authenticated UDS peer. No client state is stored on McpServer or shared with HTTP. */
public final class IpcConnection
{
    private final McpProtocolHandler dispatcher;
    private final Consumer<String> sink;
    private ClientCapabilities capabilities = ClientCapabilities.ABSENT;
    private volatile boolean initialized;
    private boolean initializeReceived;

    public IpcConnection(McpProtocolHandler dispatcher, Consumer<String> sink)
    { this.dispatcher = dispatcher; this.sink = sink; }

    public void accept(String frame) throws IOException
    {
        long start = System.nanoTime();
        JsonRpcRequest request;
        try { request = GsonProvider.get().fromJson(frame, JsonRpcRequest.class); }
        catch (RuntimeException e)
        { sink.accept(StdioBootstrap.error(JsonNull.INSTANCE, -32700, "Invalid JSON")); return; }
        if (request == null || !"2.0".equals(request.getJsonrpc()) || request.getMethod() == null)
        { sink.accept(StdioBootstrap.error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request")); return; }
        var tree = JsonParser.parseString(frame).getAsJsonObject();
        var id = tree.has("id") ? tree.get("id") : JsonNull.INSTANCE;
        if ("initialize".equals(request.getMethod()))
        {
            if (initializeReceived)
            { sink.accept(StdioBootstrap.error(id, -32600, "Already initialized")); return; }
            capabilities = McpProtocolHandler.parseClientCapabilities(request);
            initializeReceived = true;
        }
        else if ("notifications/initialized".equals(request.getMethod()))
        { initialized = initializeReceived; return; }
        else if (!initialized && !"ping".equals(request.getMethod()))
        {
            if (!id.isJsonNull()) { sink.accept(StdioBootstrap.error(id, -32600, "Initialize this MCP connection first")); }
            return;
        }
        String response = dispatcher.processRequest(frame, request, start, capabilities);
        if (response != null && !id.isJsonNull()) { sink.accept(response); }
    }

    public void toolsChanged()
    {
        if (initialized) { sink.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}"); }
    }
}
