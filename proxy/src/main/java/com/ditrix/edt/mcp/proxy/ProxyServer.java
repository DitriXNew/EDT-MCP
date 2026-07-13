/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Owns the proxy's {@link HttpServer}: binds the configured port and wires the two contexts.
 *
 * <ul>
 * <li>{@code GET /health} - liveness probe answered here:
 *     {@code {"status":"ok","role":"proxy","backends":N}} where {@code N} is the current number
 *     of live backends in the registry.</li>
 * <li>{@code /mcp} - the MCP Streamable HTTP endpoint, delegated entirely to
 *     {@link McpProxyHandler}.</li>
 * </ul>
 */
public final class ProxyServer
{
    private static final Logger LOG = Logger.getLogger(ProxyServer.class.getName());

    private static final String CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$
    private static final String APPLICATION_JSON = "application/json"; //$NON-NLS-1$

    private final ProxyConfig cfg;
    private final BackendRegistry registry;
    private final McpProxyHandler handler;

    private HttpServer httpServer;
    private ExecutorService executor;

    /**
     * Creates the server (does not bind yet - call {@link #start()}).
     *
     * @param cfg the proxy configuration, not {@code null}
     * @param registry the backend registry queried by {@code /health}, not {@code null}
     * @param handler the MCP request handler serving {@code /mcp}, not {@code null}
     */
    public ProxyServer(ProxyConfig cfg, BackendRegistry registry, McpProxyHandler handler)
    {
        this.cfg = cfg;
        this.registry = registry;
        this.handler = handler;
    }

    /**
     * Binds {@code cfg.port} (0 = ephemeral), registers the {@code /health} and {@code /mcp}
     * contexts and starts serving requests on a cached thread pool.
     *
     * @throws UncheckedIOException when the port cannot be bound (e.g. already in use)
     * @throws IllegalStateException when the server is already started
     */
    public synchronized void start()
    {
        if (httpServer != null)
        {
            throw new IllegalStateException("Proxy server is already started"); //$NON-NLS-1$
        }
        try
        {
            httpServer = HttpServer.create(new InetSocketAddress(cfg.port), 0);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(
                "Failed to bind proxy port " + cfg.port + " (already in use?): " + e.getMessage(), e); //$NON-NLS-1$ //$NON-NLS-2$
        }
        executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "edt-mcp-proxy-worker"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executor);
        httpServer.createContext("/health", this::handleHealth); //$NON-NLS-1$
        httpServer.createContext("/mcp", handler); //$NON-NLS-1$
        httpServer.start();
        LOG.info("Proxy HTTP server started on port " + getPortUnsafe()); //$NON-NLS-1$
    }

    /**
     * Stops the HTTP server (if started) and shuts down its worker pool. Idempotent.
     */
    public synchronized void stop()
    {
        if (httpServer != null)
        {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null)
        {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Returns the actual listening port.
     *
     * @return the bound port once started (meaningful when configured with port 0),
     *         otherwise the configured port
     */
    public synchronized int getPort()
    {
        return getPortUnsafe();
    }

    private int getPortUnsafe()
    {
        return httpServer != null ? httpServer.getAddress().getPort() : cfg.port;
    }

    /**
     * Serves {@code GET /health} with the proxy liveness payload.
     */
    private void handleHealth(HttpExchange exchange) throws IOException
    {
        try
        {
            if (!"GET".equals(exchange.getRequestMethod())) //$NON-NLS-1$
            {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}"); //$NON-NLS-1$
                return;
            }
            JsonObject body = new JsonObject();
            body.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
            body.addProperty("role", "proxy"); //$NON-NLS-1$ //$NON-NLS-2$
            body.addProperty("backends", registry.live().size()); //$NON-NLS-1$
            sendJson(exchange, 200, Json.compact(body));
        }
        finally
        {
            exchange.close();
        }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException
    {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(CONTENT_TYPE, APPLICATION_JSON);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody())
        {
            os.write(bytes);
        }
    }
}
