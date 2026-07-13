/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

/**
 * Command-line entry point of the standalone MCP proxy (issue #253).
 *
 * <p>Wires the components together: parses {@link ProxyConfig} ({@code --help} prints usage and
 * exits 0), builds the {@link BackendRegistry}, {@link SessionManager}, {@link McpProxyHandler}
 * and {@link ProxyServer}, performs an initial backend discovery, starts the periodic registry
 * refresh loop, installs a shutdown hook, and prints one startup line to stdout.
 *
 * <p>The proxy stays alive with zero backends: discovery is periodic and also happens on a
 * routing miss, so backends may come and go while the proxy is running.
 */
public final class Main
{
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main()
    {
        // entry-point class
    }

    /**
     * Starts the proxy.
     *
     * @param args CLI arguments, see {@link ProxyConfig#usage()}
     */
    public static void main(String[] args)
    {
        if (args != null)
        {
            for (String arg : args)
            {
                if ("--help".equals(arg) || "-h".equals(arg)) //$NON-NLS-1$ //$NON-NLS-2$
                {
                    System.out.println(ProxyConfig.usage());
                    return;
                }
            }
        }

        ProxyConfig cfg;
        try
        {
            cfg = ProxyConfig.parse(args, System.getenv());
        }
        catch (IllegalArgumentException e)
        {
            System.err.println("edt-mcp-proxy: " + e.getMessage()); //$NON-NLS-1$
            System.err.println();
            System.err.println(ProxyConfig.usage());
            System.exit(2);
            return;
        }

        BackendRegistry registry = new BackendRegistry(cfg);
        SessionManager sessions = new SessionManager();
        McpProxyHandler handler = new McpProxyHandler(cfg, registry, sessions);
        ProxyServer server = new ProxyServer(cfg, registry, handler);

        // Initial discovery so /health and the first requests see the backends immediately.
        registry.refresh();
        server.start();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "edt-mcp-proxy-refresh"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        registry.startPeriodicRefresh(scheduler);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down edt-mcp-proxy"); //$NON-NLS-1$
            scheduler.shutdownNow();
            server.stop();
        }, "edt-mcp-proxy-shutdown")); //$NON-NLS-1$

        // The single startup line goes to stdout by contract (scripts grep for it).
        System.out.println("edt-mcp-proxy listening on :" + server.getPort() //$NON-NLS-1$
            + ", scanning " + cfg.scanFrom + "-" + cfg.scanTo); //$NON-NLS-1$ //$NON-NLS-2$
        LOG.info("Live backends after initial discovery: " + registry.live().size()); //$NON-NLS-1$
        // The JVM stays alive on the HttpServer's non-daemon dispatcher thread.
    }
}
