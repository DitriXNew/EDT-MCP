/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Owns the proxy's {@link HttpServer}: binds the configured port and wires the three contexts.
 *
 * <ul>
 * <li>{@code GET /health} - liveness probe answered here:
 *     {@code {"status":"ok","role":"proxy","backends":N}} where {@code N} is the current number
 *     of live backends in the registry.</li>
 * <li>{@code /mcp} - the MCP Streamable HTTP endpoint, delegated entirely to
 *     {@link McpProxyHandler}.</li>
 * <li>{@code POST /admin/shutdown} - the {@code stop} CLI subcommand's target (issue #253
 *     follow-up, spec section 7): gracefully stops the proxy. Accepted ONLY from a loopback
 *     remote address, REGARDLESS of {@code cfg.allowRemote}/{@code --bind} - the admin surface
 *     is never exposed remotely even when the MCP traffic itself is. See
 *     {@link #handleAdminShutdown} and {@link #setShutdownCallback}.</li>
 * </ul>
 *
 * <p><b>Bind address.</b> {@link #start()} binds loopback only unless {@code cfg.allowRemote}
 * opts into a caller-chosen {@code cfg.bindHost} (see {@link ProxyConfig}) - the proxy forwards
 * to EDT-MCP backends with an arbitrary-BSL tool surface and has no authentication of its own,
 * so loopback is the security boundary by default.
 */
public final class ProxyServer
{
    private static final Logger LOG = Logger.getLogger(ProxyServer.class.getName());

    private static final String CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$
    private static final String APPLICATION_JSON = "application/json"; //$NON-NLS-1$
    private static final String HTTP_METHOD_POST = "POST"; //$NON-NLS-1$
    private static final String CONTEXT_ADMIN_SHUTDOWN = "/admin/shutdown"; //$NON-NLS-1$

    private final ProxyConfig cfg;
    private final BackendRegistry registry;
    private final McpProxyHandler handler;

    private HttpServer httpServer;
    private ExecutorService executor;

    /**
     * Cleanup run once {@code POST /admin/shutdown} has been accepted and its response flushed;
     * wired by {@code Main}/{@code CliCommands} to the exact same cleanup a Ctrl+C/SIGTERM
     * shutdown hook runs (stop the periodic-refresh scheduler, then this server). {@code null}
     * (the default, e.g. in a test that never calls {@link #setShutdownCallback}) falls back to
     * calling {@link #stop()} directly, so the endpoint is still self-sufficient without a caller
     * that owns extra resources to clean up.
     */
    private volatile Runnable shutdownCallback;

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
     * <p>Binds loopback only ({@link InetAddress#getLoopbackAddress()}) unless
     * {@code cfg.allowRemote} is set, in which case it binds {@code cfg.bindHost} instead -
     * mirroring the EDT-MCP plugin's own loopback-by-default policy (see
     * {@code com.ditrix.edt.mcp.server.McpServer#start}). The proxy has no authentication in
     * v1, so a remote bind is logged as a security warning.
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
        InetSocketAddress bindAddress = cfg.allowRemote
            ? new InetSocketAddress(cfg.bindHost, cfg.port)
            : new InetSocketAddress(InetAddress.getLoopbackAddress(), cfg.port);
        try
        {
            httpServer = HttpServer.create(bindAddress, 0);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Failed to bind proxy " //$NON-NLS-1$
                + (cfg.allowRemote ? cfg.bindHost : "loopback") + ":" + cfg.port //$NON-NLS-1$ //$NON-NLS-2$
                + " (already in use?): " + e.getMessage(), e); //$NON-NLS-1$
        }
        executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "edt-mcp-proxy-worker"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(executor);
        httpServer.createContext("/health", this::handleHealth); //$NON-NLS-1$
        httpServer.createContext("/mcp", handler); //$NON-NLS-1$
        httpServer.createContext(CONTEXT_ADMIN_SHUTDOWN, this::handleAdminShutdown);
        httpServer.start();
        LOG.info("Proxy HTTP server binding to " //$NON-NLS-1$
            + (cfg.allowRemote ? "remote (" + cfg.bindHost + ")" : "loopback only") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " on port " + getPortUnsafe()); //$NON-NLS-1$
        if (cfg.allowRemote)
        {
            LOG.warning("SECURITY: proxy is bound to " + cfg.bindHost //$NON-NLS-1$
                + " with NO authentication. Any host that can reach this port can invoke every " //$NON-NLS-1$
                + "routed tool on every discovered EDT backend, including arbitrary BSL. " //$NON-NLS-1$
                + "Only expose this on a trusted network."); //$NON-NLS-1$
        }
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
     * Installs the cleanup {@code POST /admin/shutdown} runs once its response has been flushed
     * to the client, instead of the plain {@link #stop()} fallback. The caller (normally
     * {@code CliCommands.serve}) should pass the SAME {@link Runnable} it installs as its JVM
     * shutdown hook, so a Ctrl+C/SIGTERM and an admin-triggered stop clean up identically
     * (stop the periodic-refresh scheduler, then this server); both paths are idempotent, so it
     * is harmless if the shutdown hook also fires afterwards.
     *
     * @param callback the shutdown cleanup to run; {@code null} restores the {@link #stop()} fallback
     */
    public void setShutdownCallback(Runnable callback)
    {
        this.shutdownCallback = callback;
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

    /**
     * Serves {@code POST /admin/shutdown} (issue #253 follow-up, spec section 7/8): the {@code
     * stop} CLI subcommand's target. Accepted ONLY when {@link #isLoopbackRequest} is {@code
     * true} for the exchange's remote address - REGARDLESS of {@code cfg.allowRemote}/{@code
     * --bind} - answering {@code 403} otherwise; any HTTP method other than {@code POST}
     * answers {@code 405}. On acceptance the {@code 200 {"status":"stopping"}} response is sent
     * and the exchange closed FIRST, so it reaches the client, and only THEN is the actual stop
     * triggered on a separate thread (see {@link #triggerShutdown()}) - stopping this server
     * from within one of its own dispatcher threads before the response is flushed would race
     * the client seeing a reply at all.
     */
    private void handleAdminShutdown(HttpExchange exchange) throws IOException
    {
        boolean accepted = false;
        try
        {
            if (!HTTP_METHOD_POST.equals(exchange.getRequestMethod()))
            {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}"); //$NON-NLS-1$
                return;
            }
            if (!isLoopbackRequest(exchange.getRemoteAddress()))
            {
                LOG.warning("Rejected " + CONTEXT_ADMIN_SHUTDOWN + " from non-loopback address " //$NON-NLS-1$ //$NON-NLS-2$
                    + exchange.getRemoteAddress());
                sendJson(exchange, 403,
                    "{\"error\":\"Forbidden - " + CONTEXT_ADMIN_SHUTDOWN + " accepts loopback callers only\"}"); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            LOG.info("Accepted " + CONTEXT_ADMIN_SHUTDOWN + " from " + exchange.getRemoteAddress()); //$NON-NLS-1$ //$NON-NLS-2$
            sendJson(exchange, 200, "{\"status\":\"stopping\"}"); //$NON-NLS-1$
            accepted = true;
        }
        finally
        {
            exchange.close();
        }
        if (accepted)
        {
            triggerShutdown();
        }
    }

    /**
     * Tells whether an HTTP exchange's remote address is loopback - the ONLY address class
     * {@code POST /admin/shutdown} accepts, regardless of {@code cfg.allowRemote}/{@code --bind}.
     * Package-private and static (no exchange/socket needed) so a unit test can exercise both
     * branches directly: faking a genuinely non-loopback remote peer in-process is impractical,
     * so the 403 branch is proven on this helper instead of over a real socket.
     *
     * @param remote the exchange's remote socket address; {@code null} is treated as non-loopback
     * @return {@code true} only when the remote address is present and loopback
     */
    static boolean isLoopbackRequest(InetSocketAddress remote)
    {
        return remote != null && remote.getAddress() != null && remote.getAddress().isLoopbackAddress();
    }

    /**
     * Runs the shutdown cleanup on a fresh daemon thread, off the HTTP dispatcher thread that
     * just answered {@code POST /admin/shutdown}, so stopping this server's {@link HttpServer}
     * never races the response it just sent. Uses {@link #shutdownCallback} when set (installed
     * by the caller that also owns e.g. the periodic-refresh scheduler); falls back to
     * {@link #stop()} so the endpoint is self-sufficient even without one.
     */
    private void triggerShutdown()
    {
        Thread shutdownThread = new Thread(() -> {
            Runnable callback = shutdownCallback;
            if (callback != null)
            {
                callback.run();
            }
            else
            {
                stop();
            }
        }, "edt-mcp-proxy-admin-shutdown"); //$NON-NLS-1$
        shutdownThread.setDaemon(true);
        shutdownThread.start();
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
