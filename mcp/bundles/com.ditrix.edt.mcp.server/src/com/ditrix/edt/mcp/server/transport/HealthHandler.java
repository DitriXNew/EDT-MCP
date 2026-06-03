/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.impl.GetEdtVersionTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Server readiness/liveness handler for the {@code /health} endpoint.
 * <p>
 * Reports {@code status: "ok"} only when the MCP server is running AND the core
 * EDT model service-manager references are present (non-null); otherwise it
 * reports {@code "starting"} (server not up yet) or {@code "degraded"} (a core
 * service is missing) and names the missing services in {@code missingServices}.
 * A cheap always-true {@code live} ping is included so a simple liveness check
 * keeps working during the startup race.
 * <p>
 * IMPORTANT: this only null-checks the service-manager references obtained from
 * {@link Activator}; it never reads/queries the EDT model and opens no
 * transaction — the probe stays cheap.
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

        Activator activator = Activator.getDefault();

        // Liveness: whether the MCP server reports itself running. Null-safe for a
        // headless/early-startup context where the Activator or server is absent.
        McpServer server = activator != null ? activator.getMcpServer() : null;
        boolean running = server != null && server.isRunning();

        // Readiness: presence (non-null) of the core EDT model service-manager
        // references. This is a cheap reference check only — NO model read, NO
        // transaction. Order is preserved so missingServices reads predictably.
        Map<String, Object> coreServices = new LinkedHashMap<>();
        coreServices.put("v8ProjectManager", //$NON-NLS-1$
            activator != null ? activator.getV8ProjectManager() : null);
        coreServices.put("bmModelManager", //$NON-NLS-1$
            activator != null ? activator.getBmModelManager() : null);
        coreServices.put("servicesOrchestrator", //$NON-NLS-1$
            activator != null ? activator.getServicesOrchestrator() : null);

        String response =
            JsonUtils.buildHealthResponse(GetEdtVersionTool.getEdtVersion(), running, coreServices);
        exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        HttpTransport.sendResponse(exchange, 200, response);
    }
}
