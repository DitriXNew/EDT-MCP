/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * The proxy's MCP Streamable HTTP transport: the single {@code /mcp} request handler.
 * <p>
 * Mirrors the plugin's {@code McpHttpHandler} wire behaviour 1:1 (session issuance on
 * {@code initialize}, the SSE-vs-plain-JSON framing decided by the client's {@code Accept}
 * header, {@code 202} for notifications) but terminates the client-facing MCP session layer
 * itself (via {@link SessionManager}) and answers {@code initialize} / {@code ping} /
 * {@code router_status} / {@code router_refresh} locally instead of forwarding them.
 * <p>
 * {@code tools/list} is forwarded to the first live backend and has the two
 * {@code router_*} tool descriptors injected before being cached and returned; a
 * {@code tools/call} (and any other method) is routed via {@link ProjectRouter} to one
 * backend (forwarded WITH the client's own {@code Accept} header - see
 * {@link #forwardToBackend} - and streamed back byte-for-byte, so the relayed framing is the
 * one the client actually asked for), fanned out across every live backend
 * ({@code list_projects}), answered by the proxy itself (the router tools), or refused with
 * an actionable error. A backend {@link IOException} triggers a registry refresh and an
 * actionable error naming the dead port; no exception ever escapes {@link #handle(HttpExchange)}.
 */
public final class McpProxyHandler implements HttpHandler
{
    private static final Logger LOG = Logger.getLogger(McpProxyHandler.class.getName());

    // ------------------------------------------------------------------------------------
    // HTTP wire constants (mirrors com.ditrix.edt.mcp.server.transport.McpHttpHandler /
    // com.ditrix.edt.mcp.server.protocol.McpConstants — the proxy module has no compile
    // dependency on the plugin, so the literal values are duplicated here).
    // ------------------------------------------------------------------------------------

    /** {@code list_projects} argument selecting the output format. */
    private static final String ARG_FORMAT = "format"; //$NON-NLS-1$

    /** {@link #ARG_FORMAT} value asking a backend for the machine-readable project list. */
    private static final String FORMAT_JSON = "json"; //$NON-NLS-1$

    private static final String HEADER_SESSION_ID = "Mcp-Session-Id"; //$NON-NLS-1$
    private static final String HEADER_ACCEPT = "Accept"; //$NON-NLS-1$
    private static final String HEADER_CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$
    private static final String HEADER_CACHE_CONTROL = "Cache-Control"; //$NON-NLS-1$
    private static final String HEADER_CONNECTION = "Connection"; //$NON-NLS-1$
    private static final String HEADER_CONTENT_LENGTH = "Content-Length"; //$NON-NLS-1$

    private static final String VALUE_NO_CACHE = "no-cache"; //$NON-NLS-1$
    private static final String VALUE_KEEP_ALIVE = "keep-alive"; //$NON-NLS-1$
    private static final String VALUE_APPLICATION_JSON = "application/json"; //$NON-NLS-1$
    private static final String VALUE_TEXT_EVENT_STREAM = "text/event-stream"; //$NON-NLS-1$

    private static final String HTTP_METHOD_POST = "POST"; //$NON-NLS-1$
    private static final String HTTP_METHOD_DELETE = "DELETE"; //$NON-NLS-1$

    private static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$
    private static final String METHOD_PING = "ping"; //$NON-NLS-1$
    private static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$
    private static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$
    private static final String NOTIFICATION_PREFIX = "notifications/"; //$NON-NLS-1$

    private static final String SERVER_NAME = "edt-mcp-proxy"; //$NON-NLS-1$

    private static final int ERROR_INVALID_REQUEST = -32600;
    private static final int ERROR_INTERNAL = -32603;
    private static final int ERROR_BACKEND_UNREACHABLE = -32000;

    /**
     * Hard cap on a request body's size (issue #253 hardening): a body at or under this size is
     * read in full; a bigger one is rejected with {@code 413} - either up front (from a declared
     * {@code Content-Length}) or as soon as the bounded read exceeds the cap - without ever
     * buffering more than {@code MAX_BODY_BYTES + 1} bytes, so an oversized or unbounded request
     * body cannot exhaust heap.
     */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private static final String KEY_METHOD = "method"; //$NON-NLS-1$
    private static final String KEY_PARAMS = "params"; //$NON-NLS-1$
    private static final String KEY_ARGUMENTS = "arguments"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_ID = "id"; //$NON-NLS-1$
    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion"; //$NON-NLS-1$
    private static final String KEY_CAPABILITIES = "capabilities"; //$NON-NLS-1$
    private static final String KEY_TOOLS = "tools"; //$NON-NLS-1$
    private static final String KEY_SERVER_INFO = "serverInfo"; //$NON-NLS-1$
    private static final String KEY_VERSION = "version"; //$NON-NLS-1$
    private static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    private final BackendRegistry registry;
    private final SessionManager sessions;
    private final ProjectRouter router;

    /** Event ID counter for the SSE frames this handler emits itself (mirrors the plugin). */
    private final AtomicLong eventIdCounter = new AtomicLong(0);

    /**
     * Creates the handler.
     *
     * @param cfg the proxy configuration, used to configure {@link RouterTools}' status fields
     * @param registry the backend registry queried and refreshed by every request path
     * @param sessions the proxy's own client-facing session tracker
     */
    public McpProxyHandler(ProxyConfig cfg, BackendRegistry registry, SessionManager sessions)
    {
        this.registry = registry;
        this.sessions = sessions;
        this.router = new ProjectRouter(registry);
        RouterTools.configure(cfg);
    }

    /**
     * Handles one {@code /mcp} HTTP exchange. {@code POST} dispatches the MCP JSON-RPC
     * message, {@code DELETE} closes the caller's session, every other HTTP method is
     * rejected with {@code 405}. Never lets an exception escape: any failure not already
     * handled by a narrower catch is reported as a JSON-RPC internal-error {@code 500}.
     *
     * @param exchange the HTTP exchange to serve
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        try
        {
            String method = exchange.getRequestMethod();
            if (HTTP_METHOD_DELETE.equals(method))
            {
                handleDelete(exchange);
            }
            else if (HTTP_METHOD_POST.equals(method))
            {
                handlePost(exchange);
            }
            else
            {
                sendPlain(exchange, 405, buildSimpleError("Method not allowed")); //$NON-NLS-1$
            }
        }
        catch (Exception e) // NOSONAR: the transport choke point must never let an exception escape
        {
            LOG.log(Level.SEVERE, "Unexpected error handling proxy MCP request", e); //$NON-NLS-1$
            try
            {
                sendPlain(exchange, 500,
                    buildJsonRpcError(ERROR_INTERNAL, "Internal proxy error: " + e.getMessage(), null)); //$NON-NLS-1$
            }
            catch (IOException ignored)
            {
                // client already gone
            }
        }
        finally
        {
            exchange.close();
        }
    }

    /**
     * Closes the caller's proxy-issued session (idempotent - an unknown or absent session
     * id is simply ignored) and answers {@code 200}. Backend sessions are untouched.
     */
    private void handleDelete(HttpExchange exchange) throws IOException
    {
        String sessionId = exchange.getRequestHeaders().getFirst(HEADER_SESSION_ID);
        sessions.close(sessionId);
        sendPlain(exchange, 200, ""); //$NON-NLS-1$
    }

    /**
     * Dispatches one {@code POST /mcp} JSON-RPC message: {@code initialize} is answered by
     * the proxy itself and needs no prior session; every other method requires a valid
     * proxy-issued {@code Mcp-Session-Id}. {@code notifications/*} get an empty {@code 202};
     * {@code ping}, {@code tools/list} and {@code tools/call} (plus any other method) are
     * delegated to their dedicated handlers. A body over {@link #MAX_BODY_BYTES} is rejected
     * with {@code 413} before any further processing (see {@link #readBody}).
     */
    private void handlePost(HttpExchange exchange) throws IOException
    {
        String rawBody = readBody(exchange);
        if (rawBody == null)
        {
            sendPlain(exchange, 413, buildJsonRpcError(ERROR_INVALID_REQUEST,
                "Request body exceeds the " + MAX_BODY_BYTES + "-byte limit", null)); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        JsonObject requestJson = Json.parseObject(rawBody);
        Object requestId = extractRequestId(requestJson);

        if (requestJson == null)
        {
            sendMcpResponse(exchange, 200,
                buildJsonRpcError(ERROR_INVALID_REQUEST, "Malformed or unparseable JSON-RPC request body", //$NON-NLS-1$
                    requestId),
                null);
            return;
        }

        String jsonRpcMethod = Json.str(requestJson, KEY_METHOD);
        boolean isInitialize = METHOD_INITIALIZE.equals(jsonRpcMethod);
        if (!isInitialize && !requireValidSession(exchange, requestId))
        {
            return;
        }

        if (isInitialize)
        {
            handleInitialize(exchange, requestJson, requestId);
            return;
        }
        if (jsonRpcMethod != null && jsonRpcMethod.startsWith(NOTIFICATION_PREFIX))
        {
            exchange.sendResponseHeaders(202, -1);
            return;
        }
        if (METHOD_PING.equals(jsonRpcMethod))
        {
            handlePing(exchange, requestId);
            return;
        }
        if (METHOD_TOOLS_LIST.equals(jsonRpcMethod))
        {
            handleToolsList(exchange, rawBody, requestId);
            return;
        }
        handleRouted(exchange, jsonRpcMethod, rawBody, requestJson, requestId);
    }

    /**
     * Validates the {@code Mcp-Session-Id} of a non-{@code initialize} request: a missing
     * header answers {@code 400}, an unknown/expired session answers {@code 404} — both as a
     * JSON-RPC error body, mirroring the plugin's session-header handling. On failure the
     * response has already been sent to {@code exchange}.
     *
     * @return {@code true} when the session is valid and dispatch should continue
     */
    private boolean requireValidSession(HttpExchange exchange, Object requestId) throws IOException
    {
        String sessionId = exchange.getRequestHeaders().getFirst(HEADER_SESSION_ID);
        if (sessionId == null || sessionId.isBlank())
        {
            sendPlain(exchange, 400, buildJsonRpcError(ERROR_INVALID_REQUEST,
                "Missing " + HEADER_SESSION_ID + " header - call initialize first.", requestId)); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        if (!sessions.isValid(sessionId))
        {
            sendPlain(exchange, 404, buildJsonRpcError(ERROR_INVALID_REQUEST,
                "Unknown or expired session '" + sessionId + "' - call initialize again.", requestId)); //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return true;
    }

    /**
     * Answers {@code initialize} itself: echoes the client's {@code protocolVersion} (falling
     * back to the version the proxy speaks to its backends when absent), advertises
     * {@code capabilities: {"tools":{}}}, identifies as {@value #SERVER_NAME}, and issues a
     * fresh {@code Mcp-Session-Id} - unless the proxy's {@link SessionManager} is at its session
     * cap, in which case {@link SessionManager#create()} returns {@code null} and this answers
     * a JSON-RPC error instead of a session (no {@code Mcp-Session-Id} header is issued).
     */
    private void handleInitialize(HttpExchange exchange, JsonObject requestJson, Object requestId) throws IOException
    {
        String clientProtocolVersion = Json.str(Json.obj(requestJson, KEY_PARAMS), KEY_PROTOCOL_VERSION);
        String protocolVersion = clientProtocolVersion != null ? clientProtocolVersion : Backend.PROTOCOL_VERSION;

        JsonObject capabilities = new JsonObject();
        capabilities.add(KEY_TOOLS, new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty(KEY_NAME, SERVER_NAME);
        serverInfo.addProperty(KEY_VERSION, proxyVersion());

        JsonObject result = new JsonObject();
        result.addProperty(KEY_PROTOCOL_VERSION, protocolVersion);
        result.add(KEY_CAPABILITIES, capabilities);
        result.add(KEY_SERVER_INFO, serverInfo);

        String sessionId = sessions.create();
        if (sessionId == null)
        {
            sendMcpResponse(exchange, 200, buildJsonRpcError(ERROR_INTERNAL,
                "Session limit reached (" + SessionManager.MAX_SESSIONS //$NON-NLS-1$
                    + "); close idle sessions and retry.", //$NON-NLS-1$
                requestId), null);
            return;
        }
        sendMcpResponse(exchange, 200, wrapResult(result, requestId), sessionId);
    }

    /** Answers {@code ping} itself with an empty result object, per the MCP basic utilities. */
    private void handlePing(HttpExchange exchange, Object requestId) throws IOException
    {
        sendMcpResponse(exchange, 200, wrapResult(new JsonObject(), requestId), null);
    }

    /**
     * Serves {@code tools/list}: forwards the raw request to the first live backend, injects
     * the two {@code router_*} descriptors, and caches the injected response. With zero live
     * backends, serves the cache (re-stamped with the caller's request id) when one exists,
     * or a minimal list containing ONLY the router tools otherwise.
     */
    private void handleToolsList(HttpExchange exchange, String rawBody, Object requestId) throws IOException
    {
        List<Backend> live = registry.live();
        if (live.isEmpty())
        {
            String cached = registry.cachedToolsListResponse();
            String body = cached != null ? rewriteId(cached, requestId) : minimalToolsListResponse(requestId);
            sendMcpResponse(exchange, 200, body, null);
            return;
        }

        Backend backend = live.get(0);
        try
        {
            HttpResponse<InputStream> response = backend.forward(rawBody);
            String raw;
            try (InputStream in = response.body())
            {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            String injected = RouterTools.injectIntoToolsList(Backend.stripSseFraming(raw));
            registry.cacheToolsListResponse(injected);
            sendMcpResponse(exchange, 200, rewriteId(injected, requestId), null);
        }
        catch (IOException e)
        {
            registry.refresh();
            sendMcpResponse(exchange, 200,
                buildJsonRpcError(ERROR_BACKEND_UNREACHABLE, deadBackendMessage(backend.getPort()), requestId), null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            sendPlain(exchange, 500,
                buildJsonRpcError(ERROR_INTERNAL, "Interrupted while forwarding tools/list to a backend", requestId)); //$NON-NLS-1$
        }
    }

    /**
     * Handles {@code tools/call} and any other JSON-RPC method through {@link ProjectRouter}:
     * a {@code BACKEND} decision is forwarded and streamed back byte-for-byte, {@code
     * FAN_OUT_LIST_PROJECTS} is merged across every live backend, {@code PROXY_SELF} answers
     * the router tools, and {@code ERROR} is reported in the tool-call error shape for a
     * {@code tools/call} (so MCP clients see {@code isError:true}) or as a plain JSON-RPC
     * error for any other method.
     */
    private void handleRouted(HttpExchange exchange, String jsonRpcMethod, String rawBody, JsonObject requestJson,
        Object requestId) throws IOException
    {
        boolean isToolCall = METHOD_TOOLS_CALL.equals(jsonRpcMethod);
        ProjectRouter.RouteResult route = router.route(jsonRpcMethod, requestJson);
        switch (route.kind)
        {
            case BACKEND:
                forwardToBackend(exchange, route.backend, rawBody, isToolCall, requestId);
                break;
            case FAN_OUT_LIST_PROJECTS:
                handleFanOut(exchange, requestId, wantsJsonFormat(requestJson));
                break;
            case PROXY_SELF:
                handleProxySelf(exchange, requestJson, requestId);
                break;
            case ERROR:
            default:
                String body = isToolCall
                    ? RouterTools.toolCallError(route.errorMessage, requestId)
                    : buildJsonRpcError(ERROR_BACKEND_UNREACHABLE, route.errorMessage, requestId);
                sendMcpResponse(exchange, 200, body, null);
                break;
        }
    }

    /**
     * Forwards {@code rawBody} to {@code backend} - WITH the client's own {@code Accept}
     * header (see {@link Backend#forward(String, String)}), so the backend answers in the
     * framing that client actually asked for - and streams its response back byte-for-byte
     * (status, {@code Content-Type}, chunked body copy). On an {@link IOException} the
     * registry is refreshed and an actionable error naming the dead port is returned instead
     * (in the tool-call error shape for a {@code tools/call}, a plain JSON-RPC error otherwise).
     */
    private void forwardToBackend(HttpExchange exchange, Backend backend, String rawBody, boolean isToolCall,
        Object requestId) throws IOException
    {
        try
        {
            String clientAccept = exchange.getRequestHeaders().getFirst(HEADER_ACCEPT);
            HttpResponse<InputStream> response = backend.forward(rawBody, clientAccept);
            streamBackendResponse(exchange, response);
        }
        catch (IOException e)
        {
            registry.refresh();
            String message = deadBackendMessage(backend.getPort());
            String body = isToolCall
                ? RouterTools.toolCallError(message, requestId)
                : buildJsonRpcError(ERROR_BACKEND_UNREACHABLE, message, requestId);
            sendMcpResponse(exchange, 200, body, null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            sendPlain(exchange, 500,
                buildJsonRpcError(ERROR_INTERNAL, "Interrupted while forwarding to a backend", requestId)); //$NON-NLS-1$
        }
    }

    /**
     * Whether the CLIENT asked {@code list_projects} for {@code format=json}. The fan-out always
     * queries the backends with {@code format=json} (the merge needs the machine list), but the
     * merged response must be shaped like a direct call of the format the client actually requested:
     * the human table by default, the machine payload only when asked for.
     *
     * @param requestJson the parsed JSON-RPC request
     * @return {@code true} when the caller passed {@code format=json}
     */
    private static boolean wantsJsonFormat(JsonObject requestJson)
    {
        JsonObject arguments = Json.obj(Json.obj(requestJson, KEY_PARAMS), KEY_ARGUMENTS);
        String format = Json.str(arguments, ARG_FORMAT);
        return format != null && FORMAT_JSON.equalsIgnoreCase(format.trim());
    }

    /**
     * Calls {@code list_projects} on every live backend and merges the results via
     * {@link FanOut#mergeListProjects}. A backend that fails the call simply contributes no
     * response (mirroring the registry's own defensive {@code list_projects} handling); the
     * registry is refreshed once when at least one backend failed.
     */
    private void handleFanOut(HttpExchange exchange, Object requestId, boolean jsonFormat)
        throws IOException
    {
        List<Backend> live = registry.live();
        List<String> responses = new ArrayList<>(live.size());
        boolean anyBackendFailed = false;
        for (Backend backend : live)
        {
            try
            {
                // Always ask for the MACHINE payload: the merge reads structuredContent.projects, and
                // the human table of the merged response is re-rendered from it. A backend whose
                // plugin ignores the parameter answers without structuredContent and contributes
                // nothing - the registry reports it as an unsupported plugin version.
                JsonObject arguments = new JsonObject();
                arguments.addProperty(ARG_FORMAT, FORMAT_JSON);
                responses.add(backend.callToolBlocking(ProjectRouter.TOOL_LIST_PROJECTS, arguments));
            }
            catch (IOException e)
            {
                anyBackendFailed = true;
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                anyBackendFailed = true;
                break;
            }
        }
        if (anyBackendFailed)
        {
            registry.refresh();
        }
        sendMcpResponse(exchange, 200,
            FanOut.mergeListProjects(responses, requestId, jsonFormat), null);
    }

    /** Answers {@code router_status} / {@code router_refresh} itself via {@link RouterTools}. */
    private void handleProxySelf(HttpExchange exchange, JsonObject requestJson, Object requestId) throws IOException
    {
        String toolName = Json.str(Json.obj(requestJson, KEY_PARAMS), KEY_NAME);
        String body = ProjectRouter.TOOL_ROUTER_REFRESH.equals(toolName)
            ? RouterTools.routerRefresh(registry, requestId)
            : RouterTools.routerStatus(registry, requestId);
        sendMcpResponse(exchange, 200, body, null);
    }

    /**
     * Streams a backend's HTTP response back to the client byte-for-byte: same status code,
     * same {@code Content-Type} (when present), and a chunked {@link InputStream}-to-
     * {@link OutputStream} copy with no full buffering.
     */
    private static void streamBackendResponse(HttpExchange exchange, HttpResponse<InputStream> response)
        throws IOException
    {
        response.headers().firstValue(HEADER_CONTENT_TYPE)
            .ifPresent(contentType -> exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, contentType));
        try (InputStream in = response.body())
        {
            exchange.sendResponseHeaders(response.statusCode(), 0);
            try (OutputStream out = exchange.getResponseBody())
            {
                in.transferTo(out);
            }
        }
    }

    /**
     * Sends a genuine MCP JSON-RPC response, choosing SSE or plain-JSON framing from the
     * request's {@code Accept} header exactly like the plugin's transport: SSE
     * ({@code event: message} / {@code id: N} / {@code data: <body>}) when {@code Accept}
     * contains {@code text/event-stream}, plain JSON otherwise. Adds the
     * {@code Mcp-Session-Id} response header when {@code sessionIdOrNull} is not {@code null}
     * (the {@code initialize} response only).
     */
    private void sendMcpResponse(HttpExchange exchange, int status, String body, String sessionIdOrNull)
        throws IOException
    {
        if (sessionIdOrNull != null)
        {
            exchange.getResponseHeaders().add(HEADER_SESSION_ID, sessionIdOrNull);
        }
        String acceptHeader = exchange.getRequestHeaders().getFirst(HEADER_ACCEPT);
        boolean acceptsSse = acceptHeader != null && acceptHeader.contains(VALUE_TEXT_EVENT_STREAM);
        exchange.getResponseHeaders().add(HEADER_CONNECTION, VALUE_KEEP_ALIVE);
        if (acceptsSse)
        {
            exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, VALUE_TEXT_EVENT_STREAM);
            exchange.getResponseHeaders().add(HEADER_CACHE_CONTROL, VALUE_NO_CACHE);
            long eventId = eventIdCounter.incrementAndGet();
            String frame = "event: message\n" + "id: " + eventId + "\n" + "data: " + body + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            writeBytes(exchange, status, frame.getBytes(StandardCharsets.UTF_8));
        }
        else
        {
            exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, VALUE_APPLICATION_JSON);
            writeBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Sends a transport-level (non-MCP-framed) plain JSON response, no SSE consideration. */
    private static void sendPlain(HttpExchange exchange, int status, String body) throws IOException
    {
        exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, VALUE_APPLICATION_JSON);
        writeBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException
    {
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }

    /**
     * Reads the request body up to {@link #MAX_BODY_BYTES}, never buffering more than that plus
     * one byte. A declared {@code Content-Length} over the cap is rejected without reading the
     * stream at all (the container drains it when {@link HttpExchange#close()} runs); absent or
     * understated {@code Content-Length} (e.g. chunked transfer) is caught by the bounded read
     * itself.
     *
     * @return the decoded UTF-8 body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES}
     *         (the caller must answer {@code 413} and MUST NOT read the exchange further)
     */
    private static String readBody(HttpExchange exchange) throws IOException
    {
        String contentLengthHeader = exchange.getRequestHeaders().getFirst(HEADER_CONTENT_LENGTH);
        if (contentLengthHeader != null)
        {
            try
            {
                if (Long.parseLong(contentLengthHeader.trim()) > MAX_BODY_BYTES)
                {
                    return null;
                }
            }
            catch (NumberFormatException ignored)
            {
                // Malformed header - fall through to the bounded read, which enforces the cap anyway.
            }
        }
        try (InputStream in = exchange.getRequestBody())
        {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES)
            {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts a JSON-RPC {@code id} as an {@link Object} (String, Number, or {@code null})
     * so it can be handed to {@link FanOut#writeId} / {@link RouterTools}.
     */
    private static Object extractRequestId(JsonObject requestJson)
    {
        if (requestJson == null)
        {
            return null;
        }
        com.google.gson.JsonElement id = requestJson.get(KEY_ID);
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive())
        {
            return null;
        }
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        return primitive.isNumber() ? primitive.getAsNumber() : primitive.getAsString();
    }

    /** Wraps a {@code result} payload into a full JSON-RPC success envelope. */
    private static String wrapResult(JsonObject result, Object requestId)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        FanOut.writeId(envelope, requestId);
        envelope.add(KEY_RESULT, result);
        return Json.compact(envelope);
    }

    /** Re-stamps the {@code id} of a cached raw JSON-RPC response with the caller's request id. */
    private static String rewriteId(String rawEnvelope, Object requestId)
    {
        JsonObject envelope = Json.parseObject(rawEnvelope);
        if (envelope == null)
        {
            return rawEnvelope;
        }
        FanOut.writeId(envelope, requestId);
        return Json.compact(envelope);
    }

    /** Builds the zero-backend, zero-cache {@code tools/list} response: ONLY the router tools. */
    private static String minimalToolsListResponse(Object requestId)
    {
        JsonObject result = new JsonObject();
        result.add(KEY_TOOLS, RouterTools.descriptorsArray());
        return wrapResult(result, requestId);
    }

    /** The actionable message used whenever a backend {@link IOException}s mid-call. */
    private static String deadBackendMessage(int port)
    {
        return "Backend at :" + port + " stopped responding; refreshed registry; retry."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** The proxy's own version; see {@link ProxyVersion} for how it is resolved. */
    private static String proxyVersion()
    {
        return ProxyVersion.current();
    }

    private static String buildSimpleError(String message)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("error", message); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    /** Mirrors the plugin's {@code JsonUtils.buildJsonRpcError} envelope shape. */
    private static String buildJsonRpcError(int code, String message, Object requestId)
    {
        return FanOut.jsonRpcError(code, message, requestId);
    }
}
