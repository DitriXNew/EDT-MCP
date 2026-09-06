/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.McpOriginValidator;
import com.sun.net.httpserver.HttpExchange;

/**
 * Shared HTTP transport helpers: CORS/Origin admission and raw response writing.
 * Used by both {@link McpHttpHandler} and {@link HealthHandler}. Pure transport
 * plumbing with no MCP-protocol knowledge.
 */
public final class HttpTransport
{
    /**
     * The largest request body this transport will buffer. The same cap the proxy applies
     * ({@code McpProxyHandler.MAX_BODY_BYTES}), so the two components answer the question the same
     * way; the plugin needs it at least as badly, because it buffers inside the developer's IDE
     * JVM and a body with no end would grow there while holding a worker.
     */
    public static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    private HttpTransport()
    {
        // utility
    }

    /**
     * Reads the request body, never buffering more than {@link #MAX_BODY_BYTES} plus one byte. A
     * declared {@code Content-Length} over the cap is refused without reading the stream at all
     * (the container drains it on {@link HttpExchange#close()}); an absent or understated
     * {@code Content-Length} (chunked transfer) is caught by the bounded read itself.
     *
     * @param exchange the HTTP exchange
     * @return the decoded UTF-8 body, or {@code null} when it exceeds {@link #MAX_BODY_BYTES} -
     *         the caller must answer {@code 413} and MUST NOT read the exchange further
     * @throws IOException if the client connection is lost while reading
     */
    public static String readBody(HttpExchange exchange) throws IOException
    {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length"); //$NON-NLS-1$
        if (contentLength != null)
        {
            try
            {
                if (Long.parseLong(contentLength.trim()) > MAX_BODY_BYTES)
                {
                    return null;
                }
            }
            catch (NumberFormatException malformed)
            {
                // Fall through to the bounded read, which enforces the cap regardless.
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
     * Adds CORS headers to the HTTP exchange if an Origin is present.
     * Validates the origin (via {@link McpOriginValidator}) and returns false
     * if it's not allowed.
     *
     * @param exchange the HTTP exchange
     * @return true if origin is allowed (or absent), false if origin is invalid
     */
    public static boolean addCorsHeaders(HttpExchange exchange)
    {
        String origin = exchange.getRequestHeaders().getFirst("Origin"); //$NON-NLS-1$
        if (origin != null)
        {
            if (!McpOriginValidator.isValidOrigin(origin))
            {
                return false;
            }
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin); //$NON-NLS-1$
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Accept"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // A missing Origin means a non-browser client (CLI / MCP client) — browsers
        // always send Origin, so the browser-CSRF allow-list does not apply here.
        // Real access control is the loopback bind + the optional auth token.
        return true;
    }

    /**
     * Checks the optional shared-token authorization. When no token is configured
     * ({@code PREF_AUTH_TOKEN} empty) authentication is disabled and every request
     * is authorized — the default, backward-compatible behavior. When a token IS
     * configured, the request must carry it in the {@code Authorization} header,
     * either as {@code "Bearer <token>"} or the raw token value.
     *
     * @param exchange the HTTP exchange
     * @return true if authorized (or auth disabled), false otherwise
     */
    public static boolean isAuthorized(HttpExchange exchange)
    {
        return isAuthorized(configuredAuthToken(), exchange.getRequestHeaders().getFirst("Authorization")); //$NON-NLS-1$
    }

    /**
     * The same decision as a pure function of the two strings, so it is unit-testable without a
     * preference store or an {@link HttpExchange}.
     *
     * @param configuredToken the configured {@code PREF_AUTH_TOKEN} (may be {@code null})
     * @param authorizationHeader the request's {@code Authorization} header (may be {@code null})
     * @return true if authorized (or auth disabled), false otherwise
     */
    static boolean isAuthorized(String configuredToken, String authorizationHeader)
    {
        String token = normalizeToken(configuredToken);
        if (token.isEmpty())
        {
            return true; // authentication disabled (default)
        }
        if (authorizationHeader == null)
        {
            return false;
        }
        // Accept "Bearer <token>" (scheme case-insensitive per RFC 6750) or the raw token.
        String trimmed = authorizationHeader.trim();
        String presented = trimmed.regionMatches(true, 0, "Bearer ", 0, 7) //$NON-NLS-1$
            ? trimmed.substring(7).trim()
            : trimmed;
        return constantTimeEquals(token, presented);
    }

    /**
     * The one definition of what the configured token IS, shared with the remote-bind refusal in
     * {@code McpServer} so the two cannot disagree. Surrounding whitespace is dropped because it
     * cannot survive the trip: the presented credential is trimmed out of the header, so an
     * untrimmed comparison would lock out every request while the server looked configured. By
     * the same rule a whitespace-only preference is NOT a token — it leaves auth disabled here,
     * and a remote bind with it is refused there.
     *
     * @param raw the stored preference value (may be {@code null})
     * @return the token as it is compared; empty when no token is configured
     */
    public static String normalizeToken(String raw)
    {
        return raw == null ? "" : raw.trim(); //$NON-NLS-1$
    }

    private static String configuredAuthToken()
    {
        Activator activator = Activator.getDefault();
        if (activator == null)
        {
            return null; // no preference store (e.g. headless tests) -> auth disabled
        }
        return activator.getPreferenceStore().getString(PreferenceConstants.PREF_AUTH_TOKEN);
    }

    /** Constant-time comparison to avoid leaking the token via response timing. */
    private static boolean constantTimeEquals(String expected, String presented)
    {
        if (expected == null || presented == null)
        {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = presented.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++)
        {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /**
     * Writes a complete HTTP response (status + body) and flushes.
     *
     * @param exchange the HTTP exchange
     * @param statusCode the HTTP status code
     * @param response the response body
     * @throws IOException if the client connection is lost while writing
     */
    public static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
            os.flush();
        }
        catch (IOException e)
        {
            Activator.logInfo("Connection lost while sending response: " + e.getMessage()); //$NON-NLS-1$
            throw e;
        }
    }
}
