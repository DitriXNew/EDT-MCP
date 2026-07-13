/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

/**
 * One discovered EDT-MCP instance, addressed by its port on localhost.
 * <p>
 * Owns the MCP client-side session against that instance: the session is established
 * lazily on first use ({@link #ensureSession()}), cached, and transparently re-established
 * once when the backend reports it stale (HTTP 404 on {@link #forward(String)}).
 * All requests mirror the wire behaviour of the e2e harness client: JSON body and the
 * {@code Mcp-Session-Id} header echoed back verbatim once the backend issues one. The
 * {@code Accept} header defaults to {@code application/json, text/event-stream} for every
 * internal caller (the session handshake, {@link #callToolBlocking}, the fan-out path) since
 * they all parse the response themselves and tolerate either framing; the ROUTED pass-through
 * ({@link #forward(String, String)}) instead forwards the end client's own {@code Accept}
 * header, so the backend answers in the framing that client actually asked for and the
 * byte-for-byte relay stays correct.
 */
public final class Backend
{
    /** MCP protocol version this proxy speaks to its backends. */
    static final String PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$

    private static final String HEADER_SESSION_ID = "Mcp-Session-Id"; //$NON-NLS-1$
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"; //$NON-NLS-1$
    private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8"; //$NON-NLS-1$
    private static final String ACCEPT_JSON_AND_SSE = "application/json, text/event-stream"; //$NON-NLS-1$

    /** Health probes must stay cheap: a hung port must not stall a whole registry scan. */
    private static final int HEALTH_TIMEOUT_SECONDS = 2;

    /** The MCP handshake is two tiny requests; it never needs the long per-call budget. */
    private static final int HANDSHAKE_TIMEOUT_SECONDS = 10;

    private final int port;
    private final HttpClient client;
    private final int timeoutSeconds;
    private final URI mcpUri;
    private final URI healthUri;
    private final AtomicLong requestId = new AtomicLong(0);

    /** Guarded by {@code this}: whether the lazy MCP handshake has been performed. */
    private boolean handshakeDone;

    /** Guarded by {@code this}: the session id the backend issued, or {@code null} for a session-less backend. */
    private String sessionId;

    /**
     * Creates a backend handle for an EDT-MCP instance on {@code 127.0.0.1:<port>}.
     *
     * @param port the backend's HTTP port
     * @param client the shared HTTP client to send requests through
     * @param timeoutSeconds the per-forwarded-call timeout in seconds
     */
    public Backend(int port, HttpClient client, int timeoutSeconds)
    {
        this.port = port;
        this.client = client;
        this.timeoutSeconds = timeoutSeconds;
        this.mcpUri = URI.create("http://127.0.0.1:" + port + "/mcp"); //$NON-NLS-1$ //$NON-NLS-2$
        this.healthUri = URI.create("http://127.0.0.1:" + port + "/health"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Returns the backend's port.
     *
     * @return the port this backend listens on
     */
    public int getPort()
    {
        return port;
    }

    /**
     * Probes the backend's {@code /health} endpoint.
     * <p>
     * The plugin's health handler always answers HTTP 200 and distinguishes readiness in
     * the {@code status} field ({@code ok} / {@code starting} / {@code degraded}), so the
     * probe requires BOTH a 200 status AND {@code "status":"ok"} in the JSON body.
     *
     * @return {@code true} when the backend answered 200 with {@code status == "ok"}
     */
    public boolean probeHealth()
    {
        HttpRequest request = HttpRequest.newBuilder(healthUri)
            .timeout(Duration.ofSeconds(HEALTH_TIMEOUT_SECONDS))
            .GET()
            .build();
        try
        {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
            {
                return false;
            }
            JsonObject body = Json.parseObject(response.body());
            return body != null && "ok".equals(Json.str(body, "status")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Ensures an MCP session against the backend, performing the lazy handshake on first
     * use: {@code initialize} (capturing the {@code Mcp-Session-Id} response header) followed
     * by {@code notifications/initialized}. The result is cached until
     * {@link #invalidateSession()}; a session-less backend (no header issued) is cached too,
     * so the handshake runs at most once per (in)validation cycle.
     *
     * @return the backend-issued session id, or {@code null} when the backend is session-less
     * @throws IOException when the handshake request fails or the backend rejects it
     * @throws InterruptedException when the calling thread is interrupted mid-handshake
     */
    public synchronized String ensureSession() throws IOException, InterruptedException
    {
        if (handshakeDone)
        {
            return sessionId;
        }

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "edt-mcp-proxy"); //$NON-NLS-1$ //$NON-NLS-2$
        clientInfo.addProperty("version", "1"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION); //$NON-NLS-1$
        params.add("capabilities", new JsonObject()); //$NON-NLS-1$
        params.add("clientInfo", clientInfo); //$NON-NLS-1$

        HttpRequest initialize =
            newPostRequest(buildJsonRpcRequest("initialize", params), null, HANDSHAKE_TIMEOUT_SECONDS); //$NON-NLS-1$
        HttpResponse<String> response = client.send(initialize, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
        {
            throw new IOException("initialize against backend :" + port + " failed with HTTP " //$NON-NLS-1$ //$NON-NLS-2$
                + response.statusCode());
        }
        String issued = response.headers().firstValue(HEADER_SESSION_ID).orElse(null);

        String initialized = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"; //$NON-NLS-1$
        client.send(newPostRequest(initialized, issued, HANDSHAKE_TIMEOUT_SECONDS),
            HttpResponse.BodyHandlers.discarding());

        sessionId = issued;
        handshakeDone = true;
        return sessionId;
    }

    /**
     * Drops the cached session so the next {@link #ensureSession()} re-handshakes.
     */
    public synchronized void invalidateSession()
    {
        handshakeDone = false;
        sessionId = null;
    }

    /**
     * Drops the cached session ONLY if it is still exactly the session that was just
     * presented and rejected with a stale-session {@code 404} - a compare-and-invalidate.
     * Without this guard, two callers racing a stale session both retry: the first
     * re-handshakes and caches a fresh session, and the second (slower to reach this point)
     * would unconditionally wipe that fresh session out from under the first, forcing a
     * needless third handshake.
     *
     * @param staleSession the session id that was presented and rejected with 404
     */
    private synchronized void invalidateSessionIfCurrent(String staleSession)
    {
        if (handshakeDone && Objects.equals(sessionId, staleSession))
        {
            handshakeDone = false;
            sessionId = null;
        }
    }

    /**
     * Forwards a raw JSON-RPC request body to the backend's {@code /mcp} endpoint using the
     * cached session and the default {@code Accept} header (see the class javadoc). Equivalent
     * to {@code forward(rawBody, null)}.
     *
     * @param rawBody the raw JSON-RPC request body to forward unchanged
     * @return the backend's HTTP response with a streaming body
     * @throws IOException when the request (or the re-handshake) fails at the HTTP level
     * @throws InterruptedException when the calling thread is interrupted mid-call
     */
    public HttpResponse<InputStream> forward(String rawBody) throws IOException, InterruptedException
    {
        return forward(rawBody, null);
    }

    /**
     * Forwards a raw JSON-RPC request body to the backend's {@code /mcp} endpoint using the
     * cached session and the given {@code Accept} header. On HTTP 404 (the backend no longer
     * knows the session, e.g. it was restarted) the session is re-established ONCE (via
     * {@link #invalidateSessionIfCurrent}, a compare-and-invalidate so a concurrent caller's
     * freshly re-established session is not wiped out) and the request retried; a second 404
     * is returned to the caller as-is.
     * <p>
     * The response body is an {@link InputStream} so the caller can stream it back to the
     * client byte-for-byte without buffering; the caller owns closing it. This is the ROUTED
     * pass-through's entry point: {@code acceptHeader} should be the end client's own
     * {@code Accept} header so the backend answers in the framing that client actually asked
     * for, and the byte-for-byte relay stays correct.
     *
     * @param rawBody the raw JSON-RPC request body to forward unchanged
     * @param acceptHeader the {@code Accept} header to send; {@code null} or blank falls back
     *            to the default {@code application/json, text/event-stream}
     * @return the backend's HTTP response with a streaming body
     * @throws IOException when the request (or the re-handshake) fails at the HTTP level
     * @throws InterruptedException when the calling thread is interrupted mid-call
     */
    public HttpResponse<InputStream> forward(String rawBody, String acceptHeader)
        throws IOException, InterruptedException
    {
        return sendWithStaleSessionRetry(rawBody, acceptHeader, timeoutSeconds);
    }

    private HttpResponse<InputStream> sendWithStaleSessionRetry(String rawBody, String acceptHeader,
        int requestTimeoutSeconds) throws IOException, InterruptedException
    {
        String accept = acceptHeader == null || acceptHeader.isBlank() ? ACCEPT_JSON_AND_SSE : acceptHeader;
        String session = ensureSession();
        HttpResponse<InputStream> response = client.send(
            newPostRequest(rawBody, session, requestTimeoutSeconds, accept), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 404)
        {
            closeQuietly(response.body());
            invalidateSessionIfCurrent(session);
            session = ensureSession();
            response = client.send(newPostRequest(rawBody, session, requestTimeoutSeconds, accept),
                HttpResponse.BodyHandlers.ofInputStream());
        }
        return response;
    }

    /**
     * Calls one tool on the backend and returns the inner JSON-RPC response string, using the
     * backend's own configured per-call timeout. Equivalent to
     * {@code callToolBlocking(toolName, arguments, -1)}.
     *
     * @param toolName the tool to call
     * @param arguments the tool arguments; {@code null} means no arguments
     * @return the JSON-RPC response as a compact JSON string
     * @throws IOException when the HTTP call fails
     * @throws InterruptedException when the calling thread is interrupted mid-call
     */
    public String callToolBlocking(String toolName, JsonObject arguments) throws IOException, InterruptedException
    {
        return callToolBlocking(toolName, arguments, -1);
    }

    /**
     * Calls one tool on the backend and returns the inner JSON-RPC response string.
     * <p>
     * Builds a {@code tools/call} JSON-RPC request, forwards it (default {@code Accept}, see
     * the class javadoc) bounded by {@code timeoutSecondsOverride} instead of the backend's own
     * configured timeout, reads the whole response body, and strips the SSE framing (the
     * {@code "data: "} payload of the message event) so the caller gets the plain JSON-RPC
     * response. A plain-JSON (non-SSE) body is returned unchanged. Used by the registry
     * ({@code list_projects} during a scan, bounded by the SHORT discovery timeout so one
     * hung backend cannot stall a whole scan) and by the fan-out path (the normal end-user
     * timeout, no override).
     *
     * @param toolName the tool to call
     * @param arguments the tool arguments; {@code null} means no arguments
     * @param timeoutSecondsOverride the per-call timeout in seconds; {@code <= 0} means "use
     *            this backend's own configured timeout" (no override)
     * @return the JSON-RPC response as a compact JSON string
     * @throws IOException when the HTTP call fails
     * @throws InterruptedException when the calling thread is interrupted mid-call
     */
    public String callToolBlocking(String toolName, JsonObject arguments, int timeoutSecondsOverride)
        throws IOException, InterruptedException
    {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName); //$NON-NLS-1$
        params.add("arguments", arguments == null ? new JsonObject() : arguments); //$NON-NLS-1$

        int effectiveTimeoutSeconds = timeoutSecondsOverride > 0 ? timeoutSecondsOverride : timeoutSeconds;
        HttpResponse<InputStream> response =
            sendWithStaleSessionRetry(buildJsonRpcRequest("tools/call", params), null, effectiveTimeoutSeconds); //$NON-NLS-1$
        String body;
        try (InputStream in = response.body())
        {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return stripSseFraming(body);
    }

    /**
     * Extracts the JSON-RPC payload from a Streamable-HTTP response body: a bare JSON
     * object is returned trimmed; an SSE body ({@code event:}/{@code id:}/{@code data:}
     * lines) yields the data payload of the LAST event, mirroring the e2e harness client.
     * Package-private for direct unit testing.
     *
     * @param body the raw HTTP response body
     * @return the inner JSON-RPC message, or the trimmed body when no SSE frame is present
     */
    static String stripSseFraming(String body)
    {
        String trimmed = body == null ? "" : body.trim(); //$NON-NLS-1$
        if (trimmed.startsWith("{")) //$NON-NLS-1$
        {
            return trimmed;
        }
        List<String> events = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : trimmed.split("\r?\n", -1)) //$NON-NLS-1$
        {
            if (line.startsWith("data:")) //$NON-NLS-1$
            {
                if (current.length() > 0)
                {
                    current.append('\n');
                }
                current.append(line.substring(5).stripLeading());
            }
            else if (line.isBlank() && current.length() > 0)
            {
                events.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0)
        {
            events.add(current.toString());
        }
        if (events.isEmpty())
        {
            return trimmed;
        }
        return events.get(events.size() - 1);
    }

    private String buildJsonRpcRequest(String method, JsonObject params)
    {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        request.addProperty("id", requestId.incrementAndGet()); //$NON-NLS-1$
        request.addProperty("method", method); //$NON-NLS-1$
        request.add("params", params); //$NON-NLS-1$
        return Json.compact(request);
    }

    /** Builds a POST request with the default {@code Accept} header (the session handshake). */
    private HttpRequest newPostRequest(String body, String session, int requestTimeoutSeconds)
    {
        return newPostRequest(body, session, requestTimeoutSeconds, ACCEPT_JSON_AND_SSE);
    }

    /** Builds a POST request with an explicit {@code Accept} header (the routed pass-through). */
    private HttpRequest newPostRequest(String body, String session, int requestTimeoutSeconds, String acceptHeader)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(mcpUri)
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Content-Type", CONTENT_TYPE_JSON) //$NON-NLS-1$
            .header("Accept", acceptHeader) //$NON-NLS-1$
            .header(HEADER_PROTOCOL_VERSION, PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (session != null)
        {
            builder.header(HEADER_SESSION_ID, session);
        }
        return builder.build();
    }

    private static void closeQuietly(InputStream in)
    {
        try
        {
            in.close();
        }
        catch (IOException ignored)
        {
            // the stale-session body is irrelevant; only the retry matters
        }
    }
}
