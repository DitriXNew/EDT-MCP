/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * End-to-end routing test: two {@link FakeBackend}s serving different projects behind one
 * in-process proxy. Verifies that {@code tools/call} is routed by {@code projectName}
 * (proven by the fake's {@code echo_port} tool), that {@code list_projects} fans out and
 * merges both backends in ascending port order, and that {@code tools/list} exposes the
 * backend tools plus the injected {@code router_*} tools.
 *
 * <p>This class also hosts the shared integration-test support ({@link McpTestClient},
 * {@link ProxyFixture} and the static helpers) reused by {@link HotplugFailoverIT} and
 * {@link ZeroBackendAndDupIT} — the IT slice has no separate util file by design.
 * {@link FakeBackend} contract relied on here (per the spec): {@code FakeBackend(int port,
 * List&lt;String&gt; projects)} with {@code start()}, {@code stop()} and {@code getPort()};
 * port {@code 0} means an ephemeral port.
 */
public class ProxyRoutingIT
{
    /** Project served by the backend on the lower scanned port. */
    static final String PROJECT_A = "ProjectA"; //$NON-NLS-1$
    /** Project served by the backend on the higher scanned port. */
    static final String PROJECT_B = "ProjectB"; //$NON-NLS-1$

    /** The fake's routing-proof tool: its result contains the owning backend's port. */
    static final String TOOL_ECHO_PORT = "echo_port"; //$NON-NLS-1$
    /** The fanned-out tool: merged across every live backend. */
    static final String TOOL_LIST_PROJECTS = "list_projects"; //$NON-NLS-1$
    /** Proxy-self tool: registry snapshot. */
    static final String TOOL_ROUTER_STATUS = "router_status"; //$NON-NLS-1$
    /** Proxy-self tool: forced registry rescan. */
    static final String TOOL_ROUTER_REFRESH = "router_refresh"; //$NON-NLS-1$

    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private FakeBackend backendA;
    private FakeBackend backendB;
    private ProxyFixture proxy;
    private McpTestClient client;
    private int portA;
    private int portB;

    /**
     * Starts backend A (lower port, {@link #PROJECT_A}) and backend B (higher port,
     * {@link #PROJECT_B}), then the proxy scanning exactly that port range, then performs
     * the client handshake. Everything is synchronous, so no readiness polling is needed.
     */
    @Before
    public void setUp() throws Exception
    {
        int[] ports = reserveFreePorts(2);
        portA = ports[0];
        portB = ports[1];
        backendA = new FakeBackend(portA, List.of(PROJECT_A));
        backendB = new FakeBackend(portB, List.of(PROJECT_B));
        backendA.start();
        backendB.start();
        proxy = new ProxyFixture(portA, portB);
        proxy.start();
        client = new McpTestClient(proxy.port());
        client.handshake();
    }

    /** Stops the proxy and both backends (null/double-stop safe). */
    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
        stopQuietly(backendA);
        stopQuietly(backendB);
    }

    /**
     * {@code tools/call echo_port} with {@code projectName} must reach exactly the owning
     * backend: the response carries that backend's port and not the other one's.
     */
    @Test
    public void testEchoPortRoutesByProjectName() throws Exception
    {
        JsonObject viaA = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_A));
        assertFalse("echo_port for " + PROJECT_A + " must succeed: " + viaA, isToolError(viaA)); //$NON-NLS-1$ //$NON-NLS-2$
        String bodyA = viaA.toString();
        assertTrue("call for " + PROJECT_A + " must reach backend :" + portA + ": " + bodyA, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            bodyA.contains(String.valueOf(portA)));
        assertFalse("call for " + PROJECT_A + " must NOT reach backend :" + portB + ": " + bodyA, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            bodyA.contains(String.valueOf(portB)));

        JsonObject viaB = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_B));
        assertFalse("echo_port for " + PROJECT_B + " must succeed: " + viaB, isToolError(viaB)); //$NON-NLS-1$ //$NON-NLS-2$
        String bodyB = viaB.toString();
        assertTrue("call for " + PROJECT_B + " must reach backend :" + portB + ": " + bodyB, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            bodyB.contains(String.valueOf(portB)));
        assertFalse("call for " + PROJECT_B + " must NOT reach backend :" + portA + ": " + bodyB, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            bodyB.contains(String.valueOf(portA)));
    }

    /**
     * A client sending {@code Accept: application/json} ONLY (no {@code text/event-stream})
     * must get back a PLAIN JSON response for a ROUTED {@code tools/call} - the backend must
     * be told the client's real {@code Accept} header instead of the proxy's own hardcoded
     * default, so the byte-for-byte relay produces the framing the client actually asked for
     * (the defect: the relay used to always ask the backend for SSE regardless of what the
     * client wanted, then relay that SSE body verbatim even to a strict-JSON client).
     */
    @Test
    public void testStrictJsonAcceptYieldsPlainJsonForARoutedToolCall() throws Exception
    {
        HttpResponse<String> response =
            client.callToolRaw(TOOL_ECHO_PORT, projectArgs(PROJECT_A), "application/json"); //$NON-NLS-1$

        assertEquals("routed tools/call must still succeed at the HTTP level", 200, response.statusCode()); //$NON-NLS-1$
        String contentType = response.headers().firstValue("Content-Type").orElse(""); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Accept: application/json must NOT get an SSE response: " + contentType, //$NON-NLS-1$
            contentType.contains("text/event-stream")); //$NON-NLS-1$
        assertTrue("expected a plain JSON content type: " + contentType, //$NON-NLS-1$
            contentType.contains("application/json")); //$NON-NLS-1$
        String body = response.body().trim();
        assertTrue("expected a bare JSON object body, not an SSE frame: " + body, body.startsWith("{")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject parsed = JsonParser.parseString(body).getAsJsonObject();
        assertFalse("echo_port must succeed: " + parsed, isToolError(parsed)); //$NON-NLS-1$
        assertTrue("the call must still reach backend :" + portA + ": " + body, //$NON-NLS-1$ //$NON-NLS-2$
            body.contains(String.valueOf(portA)));
    }

    /**
     * {@code list_projects} is fanned out to every live backend and merged; the projects
     * arrive in ascending backend port order, so the expected merge is [A, B].
     */
    @Test
    public void testListProjectsMergesBothBackends() throws Exception
    {
        // format=json is the MACHINE contract: the merged machine payload carries every backend's
        // projects. (The default format=md returns the human table instead - asserted below.)
        JsonObject jsonArgs = new JsonObject();
        jsonArgs.addProperty("format", "json"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject response = client.callTool(TOOL_LIST_PROJECTS, jsonArgs);
        assertFalse("merged list_projects must succeed: " + response, isToolError(response)); //$NON-NLS-1$
        JsonObject structured = structuredContent(response);
        assertTrue("merged result must keep the projects array: " + structured, //$NON-NLS-1$
            structured.has("projects") && structured.get("projects").isJsonArray()); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> names = new ArrayList<>();
        for (JsonElement project : structured.getAsJsonArray("projects")) //$NON-NLS-1$
        {
            names.add(project.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        assertEquals("projects must merge in ascending backend port order", //$NON-NLS-1$
            List.of(PROJECT_A, PROJECT_B), names);

        // The DEFAULT (format=md) shape mirrors a direct markdown call: the merged human table, and
        // no structuredContent - the proxy must not force the machine payload on a markdown caller.
        JsonObject mdResponse = client.callTool(TOOL_LIST_PROJECTS, new JsonObject());
        assertFalse("default list_projects must succeed: " + mdResponse, isToolError(mdResponse)); //$NON-NLS-1$
        assertFalse("the md default must not carry structuredContent: " + mdResponse, //$NON-NLS-1$
            mdResponse.getAsJsonObject("result").has("structuredContent")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * {@code tools/list} through the proxy = the first backend's tools (the fake serves two)
     * with the two {@code router_*} descriptors injected on top.
     */
    @Test
    public void testToolsListContainsFakeAndRouterTools() throws Exception
    {
        JsonObject response = client.request("tools/list", new JsonObject()); //$NON-NLS-1$
        Set<String> names = toolNames(response);
        assertTrue("tools/list must contain router_status: " + names, names.contains(TOOL_ROUTER_STATUS)); //$NON-NLS-1$
        assertTrue("tools/list must contain router_refresh: " + names, names.contains(TOOL_ROUTER_REFRESH)); //$NON-NLS-1$
        long fakeToolCount = names.stream().filter(name -> !name.startsWith("router_")).count(); //$NON-NLS-1$
        assertEquals("expected the FakeBackend's two tools besides the router ones: " + names, 2, fakeToolCount); //$NON-NLS-1$
        assertEquals("expected exactly 2 fake + 2 router tools: " + names, 4, names.size()); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------------------
    // Shared IT support (used by HotplugFailoverIT and ZeroBackendAndDupIT too)
    // ------------------------------------------------------------------------------------

    /**
     * Reserves {@code count} currently-free TCP ports by binding port-0 server sockets
     * simultaneously, then closing them all. This is the spec-sanctioned approach for
     * putting backends on a KNOWN scan range; the small race between closing the
     * reservation socket and the backend re-binding the port is accepted.
     *
     * @param count how many ports to reserve
     * @return the reserved ports, sorted ascending
     * @throws IOException when a reservation socket cannot be bound
     */
    static int[] reserveFreePorts(int count) throws IOException
    {
        ServerSocket[] sockets = new ServerSocket[count];
        int[] ports = new int[count];
        try
        {
            for (int i = 0; i < count; i++)
            {
                sockets[i] = new ServerSocket(0);
                sockets[i].setReuseAddress(true);
                ports[i] = sockets[i].getLocalPort();
            }
        }
        finally
        {
            for (ServerSocket socket : sockets)
            {
                if (socket != null)
                {
                    socket.close();
                }
            }
        }
        Arrays.sort(ports);
        return ports;
    }

    /**
     * Builds a {@code tools/call} arguments object holding only {@code projectName}.
     *
     * @param projectName the project to route by
     * @return the arguments object
     */
    static JsonObject projectArgs(String projectName)
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("projectName", projectName); //$NON-NLS-1$
        return arguments;
    }

    /**
     * Tells whether the response carries the plugin's ToolResult-error shape
     * ({@code result.isError == true}).
     *
     * @param response the parsed JSON-RPC response
     * @return {@code true} for a tool-level error response
     */
    static boolean isToolError(JsonObject response)
    {
        if (!response.has("result") || !response.get("result").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return false;
        }
        JsonObject result = response.getAsJsonObject("result"); //$NON-NLS-1$
        return result.has("isError") && result.get("isError").getAsBoolean(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Collects every human-readable error channel of a JSON-RPC response into one searchable
     * string: {@code result.content[*].text}, {@code result.structuredContent} and the
     * top-level {@code error} envelope. Message-content assertions run against this, so a
     * test stays valid whichever channel the proxy used for the given failure class.
     *
     * @param response the parsed JSON-RPC response
     * @return the concatenated error text (possibly empty, never {@code null})
     */
    static String errorText(JsonObject response)
    {
        StringBuilder text = new StringBuilder();
        if (response.has("result") && response.get("result").isJsonObject()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            JsonObject result = response.getAsJsonObject("result"); //$NON-NLS-1$
            if (result.has("content") && result.get("content").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
            {
                for (JsonElement item : result.getAsJsonArray("content")) //$NON-NLS-1$
                {
                    if (item.isJsonObject() && item.getAsJsonObject().has("text")) //$NON-NLS-1$
                    {
                        text.append(item.getAsJsonObject().get("text").getAsString()).append('\n'); //$NON-NLS-1$
                    }
                }
            }
            if (result.has("structuredContent")) //$NON-NLS-1$
            {
                text.append(result.get("structuredContent")).append('\n'); //$NON-NLS-1$
            }
        }
        if (response.has("error")) //$NON-NLS-1$
        {
            text.append(response.get("error")).append('\n'); //$NON-NLS-1$
        }
        return text.toString();
    }

    /**
     * Returns {@code result.structuredContent} of a {@code tools/call} response, failing the
     * test when it is absent or not an object.
     *
     * @param response the parsed JSON-RPC response
     * @return the structured content object
     */
    static JsonObject structuredContent(JsonObject response)
    {
        JsonObject result = response.getAsJsonObject("result"); //$NON-NLS-1$
        assertNotNull("response must carry a result: " + response, result); //$NON-NLS-1$
        JsonElement structured = result.get("structuredContent"); //$NON-NLS-1$
        assertNotNull("result must carry structuredContent: " + result, structured); //$NON-NLS-1$
        assertTrue("structuredContent must be a JSON object: " + structured, structured.isJsonObject()); //$NON-NLS-1$
        return structured.getAsJsonObject();
    }

    /**
     * Extracts the tool names of a {@code tools/list} JSON-RPC response.
     *
     * @param toolsListResponse the parsed JSON-RPC response
     * @return the tool names, in listing order
     */
    static Set<String> toolNames(JsonObject toolsListResponse)
    {
        JsonObject result = toolsListResponse.getAsJsonObject("result"); //$NON-NLS-1$
        assertNotNull("tools/list must carry a result: " + toolsListResponse, result); //$NON-NLS-1$
        assertTrue("tools/list result must carry a tools array: " + result, //$NON-NLS-1$
            result.has("tools") && result.get("tools").isJsonArray()); //$NON-NLS-1$ //$NON-NLS-2$
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement tool : result.getAsJsonArray("tools")) //$NON-NLS-1$
        {
            names.add(tool.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
    }

    /**
     * Polls {@code GET /health} on {@code port} until it stops answering (an {@link IOException}
     * on the connection attempt) or {@code timeoutMillis} elapses, whichever comes first. Used by
     * the {@code stop}/{@code POST /admin/shutdown} tests ({@code CliIT}, {@code AdminShutdownIT})
     * to observe the proxy actually stopping asynchronously (the response to the shutdown request
     * is sent before the server itself stops - see {@code ProxyServer#triggerShutdown}).
     *
     * @param port the port to poll
     * @param timeoutMillis how long to wait before giving up
     * @throws AssertionError when the port still answers after the timeout
     */
    static void awaitHealthUnreachable(int port, long timeoutMillis) throws InterruptedException
    {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://127.0.0.1:" + port + "/health"); //$NON-NLS-1$ //$NON-NLS-2$
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(1)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
                Thread.sleep(200);
            }
            catch (IOException expected)
            {
                return;
            }
        }
        throw new AssertionError("port :" + port + " kept answering /health past the " + timeoutMillis //$NON-NLS-1$ //$NON-NLS-2$
            + "ms timeout"); //$NON-NLS-1$
    }

    /**
     * Stops a backend, tolerating {@code null} and an already-stopped server (a test may
     * stop a backend mid-scenario; teardown must still be safe).
     *
     * @param backend the backend to stop, may be {@code null}
     */
    static void stopQuietly(FakeBackend backend)
    {
        if (backend == null)
        {
            return;
        }
        try
        {
            backend.stop();
        }
        catch (RuntimeException e)
        {
            // already stopped - nothing to clean up
        }
    }

    /**
     * The proxy under test, wired exactly like {@code Main} does it (config → registry →
     * handler → server) but WITHOUT the periodic refresh loop: the tests drive scans
     * explicitly ({@link #start()} performs one) or rely on the router's on-miss rescan,
     * which keeps every scenario deterministic.
     */
    static final class ProxyFixture
    {
        private final ProxyConfig config;
        private final BackendRegistry registry;
        private final ProxyServer server;

        /**
         * Builds the proxy bound to an ephemeral port, scanning exactly the given range.
         * The refresh interval is set huge on purpose — nothing may refresh behind the
         * test's back.
         *
         * @param scanFrom first scanned backend port (inclusive)
         * @param scanTo last scanned backend port (inclusive)
         */
        ProxyFixture(int scanFrom, int scanTo)
        {
            config = ProxyConfig.parse(new String[] {
                "--port", "0", //$NON-NLS-1$ //$NON-NLS-2$
                "--scan", scanFrom + "-" + scanTo, //$NON-NLS-1$ //$NON-NLS-2$
                "--refresh", "3600", //$NON-NLS-1$ //$NON-NLS-2$
                "--timeout", "15" }, //$NON-NLS-1$ //$NON-NLS-2$
                Map.of());
            registry = new BackendRegistry(config);
            server = new ProxyServer(config, registry, new McpProxyHandler(config, registry, new SessionManager()));
        }

        /** Starts the HTTP server and performs ONE synchronous backend scan. */
        void start()
        {
            server.start();
            registry.refresh();
        }

        /** Stops the HTTP server. */
        void stop()
        {
            server.stop();
        }

        /**
         * Returns the actual bound proxy port.
         *
         * @return the port the proxy listens on
         */
        int port()
        {
            return server.getPort();
        }
    }

    /**
     * Minimal MCP Streamable-HTTP client, mirroring what the e2e harness does over the wire:
     * {@code initialize} (capturing the {@code MCP-Session-Id} header) +
     * {@code notifications/initialized} (expects 202), then JSON-RPC requests whose SSE
     * responses ({@code data:} frames) are parsed back into JSON objects. The SSE framing
     * is ASSERTED, not tolerated — the proxy must answer exactly like the plugin.
     */
    static final class McpTestClient
    {
        /** MCP protocol version this client speaks (echoed back by initialize). */
        static final String PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$

        private static final String SESSION_HEADER = "MCP-Session-Id"; //$NON-NLS-1$
        private static final String ACCEPT_BOTH = "application/json, text/event-stream"; //$NON-NLS-1$

        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final URI mcpUri;
        private String sessionId;
        private int nextRequestId = 1;

        /**
         * Creates a client for the proxy on the given port.
         *
         * @param proxyPort the proxy's bound port
         */
        McpTestClient(int proxyPort)
        {
            mcpUri = URI.create("http://127.0.0.1:" + proxyPort + "/mcp"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        /**
         * Performs the full MCP handshake: {@code initialize} (must be 200 SSE and issue a
         * session id) followed by {@code notifications/initialized} (must be 202).
         *
         * @return the parsed initialize JSON-RPC response
         */
        JsonObject handshake() throws IOException, InterruptedException
        {
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "edt-mcp-proxy-it"); //$NON-NLS-1$ //$NON-NLS-2$
            clientInfo.addProperty("version", "0.0.1"); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", PROTOCOL_VERSION); //$NON-NLS-1$
            params.add("capabilities", new JsonObject()); //$NON-NLS-1$
            params.add("clientInfo", clientInfo); //$NON-NLS-1$
            HttpResponse<String> response = post(jsonRpcRequest("initialize", params).toString()); //$NON-NLS-1$
            assertEquals("initialize must succeed", 200, response.statusCode()); //$NON-NLS-1$
            sessionId = response.headers().firstValue(SESSION_HEADER).orElse(null);
            assertNotNull("initialize must issue an " + SESSION_HEADER + " header", sessionId); //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject initResponse = parseSseData(response);

            JsonObject initialized = new JsonObject();
            initialized.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
            initialized.addProperty("method", "notifications/initialized"); //$NON-NLS-1$ //$NON-NLS-2$
            HttpResponse<String> ack = post(initialized.toString());
            assertEquals("notifications/initialized must return 202", 202, ack.statusCode()); //$NON-NLS-1$
            return initResponse;
        }

        /**
         * Sends one JSON-RPC request and returns the inner response parsed from the SSE
         * {@code data:} frame.
         *
         * @param method the JSON-RPC method
         * @param params the params object, may be {@code null}
         * @return the parsed JSON-RPC response
         */
        JsonObject request(String method, JsonObject params) throws IOException, InterruptedException
        {
            HttpResponse<String> response = post(jsonRpcRequest(method, params).toString());
            assertEquals("HTTP status for " + method, 200, response.statusCode()); //$NON-NLS-1$
            return parseSseData(response);
        }

        /**
         * Calls {@code tools/call} for the given tool.
         *
         * @param toolName the tool to call
         * @param arguments the tool arguments, {@code null} for none
         * @return the parsed JSON-RPC response
         */
        JsonObject callTool(String toolName, JsonObject arguments) throws IOException, InterruptedException
        {
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName); //$NON-NLS-1$
            params.add("arguments", arguments == null ? new JsonObject() : arguments); //$NON-NLS-1$
            return request("tools/call", params); //$NON-NLS-1$
        }

        /**
         * Calls {@code tools/call} with a CALLER-CHOSEN {@code Accept} header (instead of this
         * client's default {@link #ACCEPT_BOTH}), returning the raw HTTP response so the
         * caller can assert on the framing (SSE vs plain JSON) rather than the parsed body.
         *
         * @param toolName the tool to call
         * @param arguments the tool arguments, {@code null} for none
         * @param acceptHeader the {@code Accept} header value to send
         * @return the raw HTTP response
         */
        HttpResponse<String> callToolRaw(String toolName, JsonObject arguments, String acceptHeader)
            throws IOException, InterruptedException
        {
            JsonObject params = new JsonObject();
            params.addProperty("name", toolName); //$NON-NLS-1$
            params.add("arguments", arguments == null ? new JsonObject() : arguments); //$NON-NLS-1$
            HttpResponse<String> response =
                post(jsonRpcRequest("tools/call", params).toString(), acceptHeader); //$NON-NLS-1$
            assertEquals("HTTP status for tools/call", 200, response.statusCode()); //$NON-NLS-1$
            return response;
        }

        private JsonObject jsonRpcRequest(String method, JsonObject params)
        {
            JsonObject request = new JsonObject();
            request.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
            request.addProperty("id", nextRequestId++); //$NON-NLS-1$
            request.addProperty("method", method); //$NON-NLS-1$
            if (params != null)
            {
                request.add("params", params); //$NON-NLS-1$
            }
            return request;
        }

        private HttpResponse<String> post(String body) throws IOException, InterruptedException
        {
            return post(body, ACCEPT_BOTH);
        }

        private HttpResponse<String> post(String body, String acceptHeader) throws IOException, InterruptedException
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(mcpUri)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
                .header("Accept", acceptHeader) //$NON-NLS-1$
                .header("MCP-Protocol-Version", PROTOCOL_VERSION); //$NON-NLS-1$
            if (sessionId != null)
            {
                builder.header(SESSION_HEADER, sessionId);
            }
            builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        /**
         * Asserts the SSE framing ({@code Content-Type: text/event-stream}) and parses the
         * first {@code data:} frame into a JSON object.
         */
        private static JsonObject parseSseData(HttpResponse<String> response)
        {
            String contentType = response.headers().firstValue("Content-Type").orElse(""); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue("expected an SSE response but Content-Type was '" + contentType + "'", //$NON-NLS-1$ //$NON-NLS-2$
                contentType.contains("text/event-stream")); //$NON-NLS-1$
            for (String line : response.body().split("\\r?\\n")) //$NON-NLS-1$
            {
                if (line.startsWith("data:")) //$NON-NLS-1$
                {
                    JsonElement parsed = JsonParser.parseString(line.substring("data:".length()).trim()); //$NON-NLS-1$
                    assertTrue("SSE data frame must be a JSON object: " + line, parsed.isJsonObject()); //$NON-NLS-1$
                    return parsed.getAsJsonObject();
                }
            }
            throw new AssertionError("no 'data:' frame in SSE body: " + response.body()); //$NON-NLS-1$
        }
    }
}
