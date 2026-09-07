/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.Arrays;

/**
 * Command-line entry point of the standalone MCP proxy (issue #253).
 *
 * <p>Allure-style subcommands, dispatched here and implemented in {@link CliCommands}:
 * <ul>
 * <li>{@code serve [options]} - starts the proxy in the foreground. This is also the implicit
 *     behaviour of a bare invocation (no subcommand, or options only), for backward
 *     compatibility with the pre-subcommand CLI;</li>
 * <li>{@code status [--port N]} - queries a RUNNING proxy and prints a human-readable table;</li>
 * <li>{@code stop [--port N]} - asks a RUNNING proxy to shut down.</li>
 * </ul>
 * {@code --help}/{@code -h} and {@code --version} are recognised anywhere in the arguments and
 * short-circuit dispatch entirely (see {@link CliCommands#usage()} / {@link CliCommands#versionLine()}).
 * An unrecognised subcommand prints the usage and exits {@code 2}.
 */
public final class Main
{
    private static final String CMD_SERVE = "serve"; //$NON-NLS-1$
    private static final String CMD_STATUS = "status"; //$NON-NLS-1$
    private static final String CMD_STOP = "stop"; //$NON-NLS-1$
    private static final String OPT_HELP_LONG = "--help"; //$NON-NLS-1$
    private static final String OPT_HELP_SHORT = "-h"; //$NON-NLS-1$
    private static final String OPT_VERSION = "--version"; //$NON-NLS-1$
    private static final String OPTION_PREFIX = "--"; //$NON-NLS-1$

    /**
     * Property the JDK's HTTP server reads (once, statically) for the number of connections it
     * will hold open before refusing new ones.
     */
    private static final String PROP_MAX_CONNECTIONS = "jdk.httpserver.maxConnections"; //$NON-NLS-1$

    /**
     * The ceiling this process sets when the operator has not. Far above any legitimate use of
     * a developer-fleet proxy - the handler sheds at 50 in flight long before this - so reaching
     * it means connections are being opened faster than they are being finished.
     */
    private static final String DEFAULT_MAX_CONNECTIONS = "1024"; //$NON-NLS-1$

    private Main()
    {
        // entry-point class
    }

    /**
     * Bounds the connections the proxy will hold open, BEFORE the HTTP server exists.
     * <p>
     * This is the one admission decision that does not need a worker. The handler's own
     * admission control ({@code McpProxyHandler.MAX_IN_FLIGHT_REQUESTS}) runs inside a worker
     * thread, so a burst arriving while every worker is blocked on a backend is queued, not
     * shed, and each queued exchange holds a socket and a file descriptor. Nothing in
     * {@code com.sun.net.httpserver} can answer a request without giving it a worker - the
     * {@code Executor} receives an opaque {@code Runnable}, not the exchange - so the queue can
     * either be bounded (and a burst dropped with NO response, by the executor's abort policy)
     * or unbounded (and always answered). It is unbounded, and this is what bounds the
     * retention instead: {@code ServerImpl}'s accept loop closes a connection outright once the
     * limit is reached, before any of it is queued.
     * <p>
     * Set only when the operator has not set it: an explicit {@code -Djdk.httpserver.maxConnections}
     * on the command line wins, including a {@code -1} that turns the limit off. It must be set
     * before the first {@code HttpServer} is created, because {@code ServerConfig} reads it in a
     * static initializer - hence the very first line of {@code main}. Package-visible so the
     * "only when unset" half is testable without launching a process.
     */
    static void capOpenConnections()
    {
        if (System.getProperty(PROP_MAX_CONNECTIONS) == null)
        {
            System.setProperty(PROP_MAX_CONNECTIONS, DEFAULT_MAX_CONNECTIONS);
        }
    }

    /**
     * Parses the subcommand (defaulting to {@code serve}) and dispatches to {@link CliCommands}.
     *
     * @param args CLI arguments; see {@link CliCommands#usage()}
     */
    public static void main(String[] args)
    {
        capOpenConnections();
        String[] safeArgs = args == null ? new String[0] : args;

        if (containsAny(safeArgs, OPT_HELP_LONG, OPT_HELP_SHORT))
        {
            System.out.println(CliCommands.usage());
            return;
        }
        if (containsAny(safeArgs, OPT_VERSION))
        {
            System.out.println(CliCommands.versionLine());
            return;
        }

        if (safeArgs.length == 0 || safeArgs[0].startsWith(OPTION_PREFIX))
        {
            // No subcommand, or the first token is already an option (e.g. "--port 9000") -
            // 'serve' is the implicit default, for backward compatibility with the CLI before
            // subcommands existed.
            CliCommands.serve(safeArgs);
            return;
        }

        String subcommand = safeArgs[0];
        String[] rest = Arrays.copyOfRange(safeArgs, 1, safeArgs.length);
        switch (subcommand)
        {
        case CMD_SERVE:
            CliCommands.serve(rest);
            break;
        case CMD_STATUS:
            System.exit(CliCommands.status(rest, System.out, System.err));
            break;
        case CMD_STOP:
            System.exit(CliCommands.stop(rest, System.out, System.err));
            break;
        default:
            System.err.println("edt-mcp-proxy: unknown subcommand '" + subcommand //$NON-NLS-1$
                + "'. Run with --help for usage."); //$NON-NLS-1$
            System.err.println();
            System.err.println(CliCommands.usage());
            System.exit(2);
        }
    }

    private static boolean containsAny(String[] args, String... needles)
    {
        for (String arg : args)
        {
            for (String needle : needles)
            {
                if (needle.equals(arg))
                {
                    return true;
                }
            }
        }
        return false;
    }
}
