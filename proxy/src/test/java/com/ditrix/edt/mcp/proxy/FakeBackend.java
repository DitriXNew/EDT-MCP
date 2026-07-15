/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * In-process fake EDT-MCP backend for the proxy tests (unit and integration).
 * <p>
 * Mirrors the wire surface of the plugin's transport that the proxy relies on:
 * <ul>
 * <li>{@code GET /health} — {@code 200 {"status":"ok"}};</li>
 * <li>{@code POST /mcp initialize} — issues its own {@code Mcp-Session-Id} header
 *     ({@code fake-<port>}) and answers framed per the request's {@code Accept} header (see
 *     below);</li>
 * <li>{@code notifications/*} — {@code 202} with an empty body;</li>
 * <li>ANY other call without the issued session id — {@code 404} (a stale/absent session);</li>
 * <li>{@code tools/list} — two fake tools;</li>
 * <li>{@code tools/call list_projects} — a result whose {@code structuredContent.projects}
 *     holds the CONFIGURABLE project list, optionally delayed by
 *     {@link #setListProjectsDelayMillis(long)};</li>
 * <li>{@code tools/call echo_port} — a result containing this fake's port, so a routing
 *     test can prove WHICH backend served the call.</li>
 * </ul>
 * <p>
 * <b>Framing.</b> Every response mirrors the plugin's real transport: SSE
 * ({@code event:}/{@code id:}/{@code data:} lines) when the request's {@code Accept} header
 * contains {@code text/event-stream}, plain JSON otherwise. The tests' default
 * {@code Accept: application/json, text/event-stream} client always gets SSE (the pre-existing
 * behaviour); a client sending {@code Accept: application/json} ONLY gets a plain JSON body.
 * <p>
 * Binds port 0 by default (the OS picks a free port; read it via {@link #getPort()}), or a
 * caller-chosen port so a registry scan range can cover it.
 */
public final class FakeBackend
{
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String VALUE_TEXT_EVENT_STREAM = "text/event-stream";
    private static final String VALUE_APPLICATION_JSON = "application/json";

    private final int requestedPort;
    private volatile List<String> projects;
    private volatile boolean brokenListProjects;
    private volatile long listProjectsDelayMillis;

    private HttpServer server;
    private ExecutorService executor;

    private final AtomicInteger sessionGeneration = new AtomicInteger(0);
    private final AtomicInteger initializeCount = new AtomicInteger(0);
    private volatile String currentSessionId;

    /**
     * Creates a fake backend on an OS-chosen free port (port 0).
     *
     * @param projects the project names {@code list_projects} reports
     */
    public FakeBackend(String... projects)
    {
        this(0, projects);
    }

    /**
     * Creates a fake backend bound to a specific port (so a scan range can cover it).
     *
     * @param port the port to bind, or {@code 0} for an OS-chosen free port
     * @param projects the project names {@code list_projects} reports
     */
    public FakeBackend(int port, String... projects)
    {
        this.requestedPort = port;
        this.projects = new ArrayList<>(Arrays.asList(projects));
    }

    /**
     * Creates a fake backend bound to a specific port, taking the project names as a list
     * (the integration tests' preferred call shape).
     *
     * @param port the port to bind, or {@code 0} for an OS-chosen free port
     * @param projects the project names {@code list_projects} reports
     */
    public FakeBackend(int port, java.util.List<String> projects)
    {
        this.requestedPort = port;
        this.projects = new ArrayList<>(projects);
    }

    /**
     * Starts the HTTP server.
     *
     * @throws IOException when the port cannot be bound
     */
    public synchronized void start() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/health", this::handleHealth);
        server.createContext("/mcp", this::handleMcp);
        server.start();
    }

    /**
     * Stops the HTTP server and its executor. Safe to call twice.
     */
    public synchronized void stop()
    {
        if (server != null)
        {
            server.stop(0);
            server = null;
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Returns the actual bound port.
     *
     * @return the port the fake listens on
     */
    public synchronized int getPort()
    {
        if (server != null)
        {
            return server.getAddress().getPort();
        }
        if (requestedPort == 0)
        {
            throw new IllegalStateException("FakeBackend not started yet - the OS port is unknown");
        }
        return requestedPort;
    }

    /**
     * Replaces the project list {@code list_projects} reports (hot-reconfiguration).
     *
     * @param newProjects the new project names
     */
    public void setProjects(String... newProjects)
    {
        this.projects = new ArrayList<>(Arrays.asList(newProjects));
    }

    /**
     * Makes {@code tools/call list_projects} return an unparseable body (or restores the
     * normal behaviour), for testing the registry's defensive parse.
     *
     * @param broken whether list_projects should answer garbage
     */
    public void setBrokenListProjects(boolean broken)
    {
        this.brokenListProjects = broken;
    }

    /**
     * Delays every {@code tools/call list_projects} response by the given number of
     * milliseconds (0 = no delay, the default) before answering - simulates a backend that
     * answers {@code /health} promptly but hangs on a tool call, for testing the registry's
     * discovery timeout and its {@code refresh()} concurrency coalescing.
     *
     * @param delayMillis how long to sleep before answering {@code list_projects}
     */
    public void setListProjectsDelayMillis(long delayMillis)
    {
        this.listProjectsDelayMillis = delayMillis;
    }

    /**
     * Invalidates every session issued so far: the next non-initialize call answers 404
     * until the client re-handshakes. Exercises the proxy's stale-session retry.
     */
    public void invalidateSessions()
    {
        sessionGeneration.incrementAndGet();
        currentSessionId = null;
    }

    /**
     * Returns how many {@code initialize} requests this fake has served — proves whether
     * a client re-handshook or reused its cached session.
     *
     * @return the initialize request count
     */
    public int getInitializeCount()
    {
        return initializeCount.get();
    }

    private void handleHealth(HttpExchange exchange) throws IOException
    {
        sendPlain(exchange, 200, "application/json", "{\"status\":\"ok\",\"edt_version\":\"fake\"}");
    }

    private void handleMcp(HttpExchange exchange) throws IOException
    {
        try
        {
            if (!"POST".equals(exchange.getRequestMethod()))
            {
                sendPlain(exchange, 405, "text/plain", "method not allowed");
                return;
            }
            String acceptHeader = exchange.getRequestHeaders().getFirst(HEADER_ACCEPT);
            boolean acceptsSse = acceptHeader != null && acceptHeader.contains(VALUE_TEXT_EVENT_STREAM);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject request = parseObject(body);
            String method = request != null && request.get("method") != null
                && request.get("method").isJsonPrimitive() ? request.get("method").getAsString() : "";
            JsonElement id = request == null ? null : request.get("id");

            if ("initialize".equals(method))
            {
                initializeCount.incrementAndGet();
                String issued = issueSessionId();
                exchange.getResponseHeaders().add(HEADER_SESSION_ID, issued);
                sendFramed(exchange, jsonRpcResponse(id, initializeResult()), acceptsSse);
                return;
            }

            String presented = exchange.getRequestHeaders().getFirst(HEADER_SESSION_ID);
            String issued = currentSessionId;
            if (issued == null || !issued.equals(presented))
            {
                // Mirrors the plugin: a call outside a known session is 404.
                sendPlain(exchange, 404, "text/plain", "session not found");
                return;
            }

            if (method.startsWith("notifications/"))
            {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if ("tools/list".equals(method))
            {
                sendFramed(exchange, jsonRpcResponse(id, toolsListResult()), acceptsSse);
                return;
            }
            if ("tools/call".equals(method))
            {
                handleToolCall(exchange, request, id, acceptsSse);
                return;
            }
            // ping and anything else: an empty result
            sendFramed(exchange, jsonRpcResponse(id, new JsonObject()), acceptsSse);
        }
        finally
        {
            exchange.close();
        }
    }

    private void handleToolCall(HttpExchange exchange, JsonObject request, JsonElement id, boolean acceptsSse)
        throws IOException
    {
        JsonObject params = request.get("params") != null && request.get("params").isJsonObject()
            ? request.getAsJsonObject("params") : new JsonObject();
        String toolName = params.get("name") != null && params.get("name").isJsonPrimitive()
            ? params.get("name").getAsString() : "";

        if ("list_projects".equals(toolName))
        {
            sleepIfDelayConfigured();
            if (brokenListProjects)
            {
                sendSseRaw(exchange, "this-is-not-json");
                return;
            }
            sendFramed(exchange, jsonRpcResponse(id, listProjectsResult()), acceptsSse);
            return;
        }
        if ("echo_port".equals(toolName))
        {
            sendFramed(exchange, jsonRpcResponse(id, echoPortResult()), acceptsSse);
            return;
        }
        sendFramed(exchange, jsonRpcResponse(id, textResult("fake tool '" + toolName + "' executed")), acceptsSse);
    }

    private void sleepIfDelayConfigured()
    {
        long delay = listProjectsDelayMillis;
        if (delay > 0)
        {
            try
            {
                Thread.sleep(delay);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String issueSessionId()
    {
        int generation = sessionGeneration.get();
        String issued = generation == 0 ? "fake-" + getPort() : "fake-" + getPort() + "-g" + generation;
        currentSessionId = issued;
        return issued;
    }

    private static JsonObject initializeResult()
    {
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "fake-backend");
        serverInfo.addProperty("version", "0");
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2025-11-25");
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        return result;
    }

    private static JsonObject toolsListResult()
    {
        JsonArray tools = new JsonArray();
        tools.add(toolDescriptor("fake_tool_one", "First fake tool"));
        tools.add(toolDescriptor("echo_port", "Echoes the port of the backend that served the call"));
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private static JsonObject toolDescriptor(String name, String description)
    {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("inputSchema", schema);
        return tool;
    }

    private JsonObject listProjectsResult()
    {
        JsonArray array = new JsonArray();
        for (String project : projects)
        {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", project);
            entry.addProperty("state", "ready");
            array.add(entry);
        }
        JsonObject structured = new JsonObject();
        structured.addProperty("success", true);
        structured.add("projects", array);
        JsonObject result = textResult("projects listed");
        result.add("structuredContent", structured);
        return result;
    }

    private JsonObject echoPortResult()
    {
        int port = getPort();
        JsonObject structured = new JsonObject();
        structured.addProperty("success", true);
        structured.addProperty("port", port);
        JsonObject result = textResult("port " + port);
        result.add("structuredContent", structured);
        return result;
    }

    private static JsonObject textResult(String text)
    {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", false);
        return result;
    }

    private static String jsonRpcResponse(JsonElement id, JsonObject result)
    {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response.toString();
    }

    private static JsonObject parseObject(String body)
    {
        try
        {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Sends one JSON-RPC response framed per the request's {@code Accept} header: SSE when
     * {@code sse} is {@code true}, a bare plain-JSON body (status 200,
     * {@code Content-Type: application/json}) otherwise.
     */
    private static void sendFramed(HttpExchange exchange, String json, boolean sse) throws IOException
    {
        if (sse)
        {
            sendSseRaw(exchange, json);
        }
        else
        {
            sendPlain(exchange, 200, VALUE_APPLICATION_JSON, json);
        }
    }

    private static void sendSseRaw(HttpExchange exchange, String data) throws IOException
    {
        // Mirror the plugin's SSE framing: event + id + data lines, blank-line terminated.
        byte[] bytes = ("event: message\nid: 1\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }

    private static void sendPlain(HttpExchange exchange, int status, String contentType, String body)
        throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }
}
