/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
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
    private HttpTransport()
    {
        // utility
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
        String token = configuredAuthToken();
        if (token == null || token.isEmpty())
        {
            return true; // authentication disabled (default)
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization"); //$NON-NLS-1$
        if (header == null)
        {
            return false;
        }
        // Accept "Bearer <token>" (scheme case-insensitive per RFC 6750) or the raw token.
        String trimmed = header.trim();
        String presented = trimmed.regionMatches(true, 0, "Bearer ", 0, 7) //$NON-NLS-1$
            ? trimmed.substring(7).trim()
            : trimmed;
        return constantTimeEquals(token, presented);
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
