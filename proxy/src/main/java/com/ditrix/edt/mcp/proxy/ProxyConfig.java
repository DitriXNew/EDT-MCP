/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.Map;

/**
 * Immutable configuration for the standalone MCP proxy.
 *
 * <p>Values are resolved with the precedence: CLI argument &gt; environment variable &gt; built-in default.
 * Supported CLI options: {@code --port N}, {@code --scan FROM-TO}, {@code --refresh N}, {@code --timeout N},
 * {@code --bind HOST}.
 * Supported environment variables: {@code EDT_MCP_PROXY_PORT}, {@code EDT_MCP_PROXY_SCAN} (e.g.
 * {@code "8765-8774"}), {@code EDT_MCP_PROXY_REFRESH}, {@code EDT_MCP_PROXY_TIMEOUT},
 * {@code EDT_MCP_PROXY_HOST}.
 *
 * <p>Any invalid value fails fast with an {@link IllegalArgumentException} carrying an actionable
 * message. The {@code --help} flag is handled by {@link Main} before parsing, not here.
 *
 * <p>An inverted scan range ({@code FROM > TO}) is accepted and means an <b>empty</b> scan range:
 * no backend ports are probed and the proxy starts (and stays alive) with zero backends.
 *
 * <p><b>Bind address (security).</b> By default the proxy binds loopback only ({@code 127.0.0.1}),
 * mirroring the EDT-MCP plugin's own default: the proxy forwards {@code tools/call} to EDT-MCP
 * backends whose tool surface includes arbitrary-BSL execution and destructive operations, so it
 * must not be reachable from the network unless explicitly opted in. Setting {@code --bind HOST}
 * (or {@value #ENV_HOST}) - e.g. {@code --bind 0.0.0.0} - opts into binding {@code HOST} instead
 * and sets {@link #allowRemote}. <b>The proxy has no authentication in v1</b> - exposing it beyond
 * loopback means any host that can reach the port can invoke every routed tool, including
 * arbitrary BSL, on every backend the proxy discovers. Only do this on a trusted network.
 */
public final class ProxyConfig
{
    /** Environment variable overriding the proxy listen port. */
    public static final String ENV_PORT = "EDT_MCP_PROXY_PORT"; //$NON-NLS-1$

    /** Environment variable overriding the backend scan range, formatted {@code FROM-TO}. */
    public static final String ENV_SCAN = "EDT_MCP_PROXY_SCAN"; //$NON-NLS-1$

    /** Environment variable overriding the periodic refresh interval in seconds. */
    public static final String ENV_REFRESH = "EDT_MCP_PROXY_REFRESH"; //$NON-NLS-1$

    /** Environment variable overriding the per-forwarded-call backend timeout in seconds. */
    public static final String ENV_TIMEOUT = "EDT_MCP_PROXY_TIMEOUT"; //$NON-NLS-1$

    /**
     * Environment variable requesting a non-loopback bind host (e.g. {@code "0.0.0.0"}).
     * Setting it, like {@code --bind}, opts into {@link #allowRemote}.
     */
    public static final String ENV_HOST = "EDT_MCP_PROXY_HOST"; //$NON-NLS-1$

    private static final String OPT_PORT = "--port"; //$NON-NLS-1$
    private static final String OPT_SCAN = "--scan"; //$NON-NLS-1$
    private static final String OPT_REFRESH = "--refresh"; //$NON-NLS-1$
    private static final String OPT_TIMEOUT = "--timeout"; //$NON-NLS-1$
    private static final String OPT_BIND = "--bind"; //$NON-NLS-1$

    private static final int DEFAULT_PORT = 8764;
    private static final int DEFAULT_SCAN_FROM = 8765;
    private static final int DEFAULT_SCAN_TO = 8774;
    private static final int DEFAULT_REFRESH_SECONDS = 20;
    private static final int DEFAULT_BACKEND_TIMEOUT_SECONDS = 300;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** HTTP port the proxy listens on. Default {@code 8764}; {@code 0} = pick an ephemeral port. */
    public final int port;

    /** First backend port of the discovery scan range, inclusive. Default {@code 8765}. */
    public final int scanFrom;

    /** Last backend port of the discovery scan range, inclusive. Default {@code 8774}. */
    public final int scanTo;

    /** Interval of the periodic backend registry refresh, in seconds. Default {@code 20}. */
    public final int refreshSeconds;

    /** Timeout for a single forwarded backend call, in seconds. Default {@code 300}. */
    public final int backendTimeoutSeconds;

    /**
     * {@code true} when the proxy is configured to bind beyond loopback (via {@code --bind} /
     * {@value #ENV_HOST}). Default {@code false} - loopback only, no authentication needed
     * because nothing off-box can reach the port.
     */
    public final boolean allowRemote;

    /**
     * The host to bind when {@link #allowRemote} is {@code true} (e.g. {@code "0.0.0.0"} for all
     * interfaces, or a specific interface address). {@code null} when {@link #allowRemote} is
     * {@code false} - the loopback address is used instead.
     */
    public final String bindHost;

    private ProxyConfig(int port, int scanFrom, int scanTo, int refreshSeconds, int backendTimeoutSeconds,
        boolean allowRemote, String bindHost)
    {
        this.port = port;
        this.scanFrom = scanFrom;
        this.scanTo = scanTo;
        this.refreshSeconds = refreshSeconds;
        this.backendTimeoutSeconds = backendTimeoutSeconds;
        this.allowRemote = allowRemote;
        this.bindHost = bindHost;
    }

    /**
     * Parses the configuration from CLI arguments and environment variables.
     *
     * @param args CLI arguments as passed to {@code main}, may be {@code null} or empty
     * @param env environment variables (normally {@code System.getenv()}), may be {@code null}
     * @return the resolved configuration, never {@code null}
     * @throws IllegalArgumentException on any unknown option or invalid value, with an actionable message
     */
    public static ProxyConfig parse(String[] args, Map<String, String> env)
    {
        int port = DEFAULT_PORT;
        int scanFrom = DEFAULT_SCAN_FROM;
        int scanTo = DEFAULT_SCAN_TO;
        int refreshSeconds = DEFAULT_REFRESH_SECONDS;
        int backendTimeoutSeconds = DEFAULT_BACKEND_TIMEOUT_SECONDS;
        boolean allowRemote = false;
        String bindHost = null;

        if (env != null)
        {
            String value = trimmedOrNull(env.get(ENV_PORT));
            if (value != null)
            {
                port = parseListenPort(ENV_PORT, value);
            }
            value = trimmedOrNull(env.get(ENV_SCAN));
            if (value != null)
            {
                int[] range = parseScanRange(ENV_SCAN, value);
                scanFrom = range[0];
                scanTo = range[1];
            }
            value = trimmedOrNull(env.get(ENV_REFRESH));
            if (value != null)
            {
                refreshSeconds = parsePositive(ENV_REFRESH, value);
            }
            value = trimmedOrNull(env.get(ENV_TIMEOUT));
            if (value != null)
            {
                backendTimeoutSeconds = parsePositive(ENV_TIMEOUT, value);
            }
            value = trimmedOrNull(env.get(ENV_HOST));
            if (value != null)
            {
                bindHost = parseHost(ENV_HOST, value);
                allowRemote = true;
            }
        }

        if (args != null)
        {
            for (int i = 0; i < args.length; i++)
            {
                String option = args[i];
                switch (option)
                {
                case OPT_PORT:
                    port = parseListenPort(OPT_PORT, optionValue(OPT_PORT, args, ++i));
                    break;
                case OPT_SCAN:
                    int[] range = parseScanRange(OPT_SCAN, optionValue(OPT_SCAN, args, ++i));
                    scanFrom = range[0];
                    scanTo = range[1];
                    break;
                case OPT_REFRESH:
                    refreshSeconds = parsePositive(OPT_REFRESH, optionValue(OPT_REFRESH, args, ++i));
                    break;
                case OPT_TIMEOUT:
                    backendTimeoutSeconds = parsePositive(OPT_TIMEOUT, optionValue(OPT_TIMEOUT, args, ++i));
                    break;
                case OPT_BIND:
                    bindHost = parseHost(OPT_BIND, optionValue(OPT_BIND, args, ++i));
                    allowRemote = true;
                    break;
                default:
                    throw new IllegalArgumentException(
                        "Unknown option '" + option + "'. Run with --help for the supported options."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        return new ProxyConfig(port, scanFrom, scanTo, refreshSeconds, backendTimeoutSeconds, allowRemote, bindHost);
    }

    /**
     * Returns the CLI usage text printed by {@code --help} and on invalid arguments.
     *
     * @return the multi-line usage text, never {@code null}
     */
    public static String usage()
    {
        return String.join(System.lineSeparator(),
            "edt-mcp-proxy - standalone MCP router for multiple 1C:EDT instances", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Usage: java -jar edt-mcp-proxy.jar [options]", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Options:", //$NON-NLS-1$
            "  --port N        HTTP port to listen on (default 8764, 0 = ephemeral)", //$NON-NLS-1$
            "  --scan FROM-TO  Backend discovery port range, inclusive (default 8765-8774)", //$NON-NLS-1$
            "  --refresh N     Periodic backend rediscovery interval in seconds (default 20)", //$NON-NLS-1$
            "  --timeout N     Per-forwarded-call backend timeout in seconds (default 300)", //$NON-NLS-1$
            "  --bind HOST     Bind HOST instead of loopback only (e.g. 0.0.0.0 for all interfaces).", //$NON-NLS-1$
            "                  WARNING: v1 has no authentication - this exposes every routed EDT", //$NON-NLS-1$
            "                  tool, including arbitrary BSL, to anyone who can reach the port.", //$NON-NLS-1$
            "  --help          Print this help and exit", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Environment variables (CLI options take precedence):", //$NON-NLS-1$
            "  EDT_MCP_PROXY_PORT, EDT_MCP_PROXY_SCAN (FROM-TO),", //$NON-NLS-1$
            "  EDT_MCP_PROXY_REFRESH, EDT_MCP_PROXY_TIMEOUT, EDT_MCP_PROXY_HOST"); //$NON-NLS-1$
    }

    private static String optionValue(String option, String[] args, int index)
    {
        if (index >= args.length)
        {
            throw new IllegalArgumentException(
                "Option '" + option + "' requires a value. Run with --help for usage."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return args[index];
    }

    private static String trimmedOrNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int parseListenPort(String source, String value)
    {
        int parsed = parseInt(source, value);
        if (parsed < 0 || parsed > MAX_PORT)
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected a port number 0-65535 (0 = ephemeral)."); //$NON-NLS-1$
        }
        return parsed;
    }

    private static int[] parseScanRange(String source, String value)
    {
        int dash = value.indexOf('-');
        if (dash <= 0 || dash >= value.length() - 1)
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected FROM-TO, e.g. 8765-8774."); //$NON-NLS-1$
        }
        int from = parseScanPort(source, value.substring(0, dash).trim());
        int to = parseScanPort(source, value.substring(dash + 1).trim());
        return new int[] { from, to };
    }

    private static int parseScanPort(String source, String value)
    {
        int parsed = parseInt(source, value);
        if (parsed < MIN_PORT || parsed > MAX_PORT)
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - scan ports must be 1-65535."); //$NON-NLS-1$
        }
        return parsed;
    }

    private static int parsePositive(String source, String value)
    {
        int parsed = parseInt(source, value);
        if (parsed < 1)
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected a positive integer (seconds)."); //$NON-NLS-1$
        }
        return parsed;
    }

    private static String parseHost(String source, String value)
    {
        String trimmed = value == null ? null : value.trim();
        if (trimmed == null || trimmed.isEmpty())
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected a non-empty host, e.g. 0.0.0.0."); //$NON-NLS-1$
        }
        return trimmed;
    }

    private static int parseInt(String source, String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid value for " + source + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected an integer."); //$NON-NLS-1$
        }
    }
}
