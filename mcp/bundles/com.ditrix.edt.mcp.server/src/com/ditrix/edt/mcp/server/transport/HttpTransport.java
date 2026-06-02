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
        return true;
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
