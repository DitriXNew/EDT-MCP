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

    private Main()
    {
        // entry-point class
    }

    /**
     * Parses the subcommand (defaulting to {@code serve}) and dispatches to {@link CliCommands}.
     *
     * @param args CLI arguments; see {@link CliCommands#usage()}
     */
    public static void main(String[] args)
    {
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
