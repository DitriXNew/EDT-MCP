/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Server health-check handler for the {@code /health} endpoint.
 */
public class HealthHandler implements HttpHandler
{
    @Override
    public void handle(HttpExchange exchange) throws IOException
    {
        // Add CORS headers for health check
        HttpTransport.addCorsHeaders(exchange);

        // Handle OPTIONS preflight
        if ("OPTIONS".equals(exchange.getRequestMethod())) //$NON-NLS-1$
        {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String response = JsonUtils.buildHealthResponse(GetEdtVersionTool.getEdtVersion());
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        HttpTransport.sendResponse(exchange, 200, response);
    }
}
