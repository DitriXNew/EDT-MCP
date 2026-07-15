/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Implementations of the proxy's three CLI subcommands ({@code serve}, {@code status},
 * {@code stop}) plus the {@code --help}/{@code --version} text. {@link Main} only parses the
 * subcommand name and dispatches here; {@code status}/{@code stop} take an explicit
 * {@link PrintStream} pair so a test can capture their output without touching
 * {@link System#out}/{@link System#err} or forking a process.
 */
public final class CliCommands
{
    private static final Logger LOG = Logger.getLogger(CliCommands.class.getName());

    private static final String OPT_PORT = "--port"; //$NON-NLS-1$

    private static final String HEADER_SESSION_ID = "Mcp-Session-Id"; //$NON-NLS-1$
    private static final String HEADER_ACCEPT = "Accept"; //$NON-NLS-1$
    private static final String HEADER_CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$
    private static final String CONTENT_TYPE_JSON = "application/json"; //$NON-NLS-1$

    private static final String CLIENT_NAME = "edt-mcp-proxy-cli"; //$NON-NLS-1$
    private static final String CLIENT_VERSION = "1"; //$NON-NLS-1$

    private static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$
    private static final String METHOD_INITIALIZED_NOTIFICATION = "notifications/initialized"; //$NON-NLS-1$
    private static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$
    private static final String TOOL_ROUTER_STATUS = "router_status"; //$NON-NLS-1$

    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$
    private static final String KEY_ID = "id"; //$NON-NLS-1$
    private static final String KEY_METHOD = "method"; //$NON-NLS-1$
    private static final String KEY_PARAMS = "params"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_VERSION = "version"; //$NON-NLS-1$
    private static final String KEY_ARGUMENTS = "arguments"; //$NON-NLS-1$
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion"; //$NON-NLS-1$
    private static final String KEY_CAPABILITIES = "capabilities"; //$NON-NLS-1$
    private static final String KEY_CLIENT_INFO = "clientInfo"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_PORT = "port"; //$NON-NLS-1$
    private static final String KEY_PROJECTS = "projects"; //$NON-NLS-1$
    private static final String KEY_BACKENDS = "backends"; //$NON-NLS-1$
    private static final String KEY_DUPLICATES = "duplicates"; //$NON-NLS-1$
    private static final String KEY_SCAN_RANGE = "scanRange"; //$NON-NLS-1$
    private static final String KEY_LAST_REFRESH_MS = "lastRefreshMs"; //$NON-NLS-1$

    private static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    /** Connect/response timeout for every status/stop client HTTP call. */
    private static final int CLIENT_TIMEOUT_SECONDS = 5;

    private static final String NONE = "(none)"; //$NON-NLS-1$

    private CliCommands()
    {
        // static entry-point helpers only
    }

    /**
     * Implements the {@code serve} subcommand (and the implicit behaviour of a bare invocation):
     * parses {@link ProxyConfig}, wires the registry/handler/server, performs the initial
     * synchronous discovery scan, starts the periodic refresh, wires the {@code POST
     * /admin/shutdown} cleanup callback to the SAME {@link Runnable} installed as the JVM
     * shutdown hook, and prints the one-line startup banner. Never returns while the proxy is
     * running - the process stays alive on the HTTP server's non-daemon dispatcher thread.
     *
     * @param args the {@code serve} CLI arguments, see {@link ProxyConfig#usage()}
     */
    static void serve(String[] args)
    {
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

        Runnable shutdown = () -> {
            LOG.info("Shutting down edt-mcp-proxy"); //$NON-NLS-1$
            scheduler.shutdownNow();
            server.stop();
        };
        // POST /admin/shutdown (the 'stop' subcommand's target) and a Ctrl+C/SIGTERM shutdown
        // hook must clean up identically; both invocations are idempotent, so it is harmless if
        // the hook also fires after an admin-triggered stop already ran it.
        server.setShutdownCallback(shutdown);
        Runtime.getRuntime().addShutdownHook(new Thread(shutdown, "edt-mcp-proxy-shutdown")); //$NON-NLS-1$

        // The single startup line goes to stdout by contract (scripts grep for it).
        System.out.println("edt-mcp-proxy listening on :" + server.getPort() //$NON-NLS-1$
            + ", scanning " + cfg.scanFrom + "-" + cfg.scanTo); //$NON-NLS-1$ //$NON-NLS-2$
        LOG.info("Live backends after initial discovery: " + registry.live().size()); //$NON-NLS-1$
        // The JVM stays alive on the HttpServer's non-daemon dispatcher thread.
    }

    /**
     * Implements the {@code status} subcommand: probes a RUNNING proxy's {@code GET /health}
     * and, when alive, performs a minimal MCP handshake ({@code initialize} +
     * {@code notifications/initialized}) followed by {@code tools/call router_status}, then
     * prints a human-readable table of the live backends, their projects, duplicate projects,
     * the scan range and the last refresh time.
     *
     * @param args the {@code status} CLI arguments (only {@code --port N} is supported)
     * @param out the stream the status table is printed to (normally {@link System#out})
     * @param err the stream an actionable failure line is printed to (normally {@link System#err})
     * @return {@code 0} when the proxy answered, {@code 1} when it could not be reached or its
     *         router status could not be retrieved, {@code 2} on a bad {@code --port} argument
     */
    static int status(String[] args, PrintStream out, PrintStream err)
    {
        int port;
        try
        {
            port = parsePort(args);
        }
        catch (IllegalArgumentException e)
        {
            err.println("edt-mcp-proxy: " + e.getMessage()); //$NON-NLS-1$
            return 2;
        }

        if (!isHealthy(port))
        {
            err.println(unreachableMessage(port));
            return 1;
        }

        try
        {
            JsonObject structured = fetchRouterStatus(port);
            if (structured == null)
            {
                err.println("edt-mcp-proxy: proxy on :" + port //$NON-NLS-1$
                    + " is alive but router_status could not be retrieved."); //$NON-NLS-1$
                return 1;
            }
            printStatusTable(out, port, structured);
            return 0;
        }
        catch (IOException e)
        {
            err.println(unreachableMessage(port) + " (" + e.getMessage() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return 1;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            err.println(unreachableMessage(port));
            return 1;
        }
    }

    /**
     * Implements the {@code stop} subcommand: {@code POST}s the running proxy's
     * {@code /admin/shutdown} endpoint (loopback only - see {@link ProxyServer}; a CLI client
     * connecting to {@code 127.0.0.1} always qualifies) and reports the result.
     *
     * @param args the {@code stop} CLI arguments (only {@code --port N} is supported)
     * @param out the stream the confirmation is printed to
     * @param err the stream an actionable failure line is printed to
     * @return {@code 0} when the shutdown request was accepted, {@code 1} when the proxy could
     *         not be reached or refused the request, {@code 2} on a bad {@code --port} argument
     */
    static int stop(String[] args, PrintStream out, PrintStream err)
    {
        int port;
        try
        {
            port = parsePort(args);
        }
        catch (IllegalArgumentException e)
        {
            err.println("edt-mcp-proxy: " + e.getMessage()); //$NON-NLS-1$
            return 2;
        }

        HttpRequest request = HttpRequest.newBuilder(adminShutdownUri(port))
            .timeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
        try
        {
            HttpResponse<String> response = newClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200)
            {
                out.println("edt-mcp-proxy: proxy on :" + port + " is stopping."); //$NON-NLS-1$ //$NON-NLS-2$
                return 0;
            }
            err.println("edt-mcp-proxy: proxy on :" + port + " refused the stop request (HTTP " //$NON-NLS-1$ //$NON-NLS-2$
                + response.statusCode() + "): " + response.body()); //$NON-NLS-1$
            return 1;
        }
        catch (IOException e)
        {
            err.println(unreachableMessage(port) + " (" + e.getMessage() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return 1;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            err.println(unreachableMessage(port));
            return 1;
        }
    }

    /**
     * Returns the full {@code --help} usage text: the subcommand overview plus the complete
     * {@code serve} option/env-variable reference ({@link ProxyConfig#usage()}), so one flag
     * shows everything a user needs (spec section 2: "usage covering all subcommands + options
     * + env vars").
     *
     * @return the multi-line usage text, never {@code null}
     */
    static String usage()
    {
        String subcommandOverview = String.join(System.lineSeparator(),
            "edt-mcp-proxy - standalone MCP router for multiple 1C:EDT instances", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Usage:", //$NON-NLS-1$
            "  java -jar edt-mcp-proxy.jar [serve] [options]  Start the proxy (default subcommand)", //$NON-NLS-1$
            "  java -jar edt-mcp-proxy.jar status [--port N]  Query a running proxy's router status", //$NON-NLS-1$
            "  java -jar edt-mcp-proxy.jar stop [--port N]    Stop a running proxy", //$NON-NLS-1$
            "  java -jar edt-mcp-proxy.jar --help             Print this help and exit", //$NON-NLS-1$
            "  java -jar edt-mcp-proxy.jar --version          Print the proxy version and exit", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "A bare invocation with no subcommand (options only, or none at all) is an alias for", //$NON-NLS-1$
            "'serve', for backward compatibility with the CLI before subcommands existed.", //$NON-NLS-1$
            "'status' and 'stop' accept only --port N (default " + ProxyConfig.defaultPort() + ")."); //$NON-NLS-1$ //$NON-NLS-2$
        return String.join(System.lineSeparator() + System.lineSeparator(), subcommandOverview, ProxyConfig.usage());
    }

    /**
     * Returns the {@code --version} output line.
     *
     * @return {@code "edt-mcp-proxy "} followed by {@link ProxyVersion#current()}
     */
    static String versionLine()
    {
        return "edt-mcp-proxy " + ProxyVersion.current(); //$NON-NLS-1$
    }

    // ------------------------------------------------------------------------------------
    // status / stop plumbing
    // ------------------------------------------------------------------------------------

    private static int parsePort(String[] args)
    {
        int port = ProxyConfig.defaultPort();
        if (args != null)
        {
            for (int i = 0; i < args.length; i++)
            {
                if (OPT_PORT.equals(args[i]))
                {
                    if (++i >= args.length)
                    {
                        throw new IllegalArgumentException(
                            "Option '" + OPT_PORT + "' requires a value. Run with --help for usage."); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    port = parsePortValue(args[i]);
                }
                else
                {
                    throw new IllegalArgumentException("Unknown option '" + args[i] //$NON-NLS-1$
                        + "'. Only " + OPT_PORT + " is supported here."); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        return port;
    }

    private static int parsePortValue(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Invalid value for " + OPT_PORT + ": '" + value //$NON-NLS-1$ //$NON-NLS-2$
                + "' - expected an integer."); //$NON-NLS-1$
        }
    }

    private static boolean isHealthy(int port)
    {
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")) //$NON-NLS-1$ //$NON-NLS-2$
                .timeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
                .GET()
                .build();
            HttpResponse<String> response = newClient().send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200)
            {
                return false;
            }
            JsonObject body = Json.parseObject(response.body());
            return body != null && "ok".equals(Json.str(body, "status")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            return false;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Performs the minimal MCP handshake against the proxy on {@code port} and calls
     * {@code router_status}, returning its {@code structuredContent} payload.
     *
     * @return the {@code structuredContent} object, or {@code null} when the handshake or the
     *         call did not yield a usable response
     */
    private static JsonObject fetchRouterStatus(int port) throws IOException, InterruptedException
    {
        HttpClient client = newClient();
        URI mcpUri = URI.create("http://127.0.0.1:" + port + "/mcp"); //$NON-NLS-1$ //$NON-NLS-2$

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty(KEY_NAME, CLIENT_NAME);
        clientInfo.addProperty(KEY_VERSION, CLIENT_VERSION);
        JsonObject initParams = new JsonObject();
        initParams.addProperty(KEY_PROTOCOL_VERSION, Backend.PROTOCOL_VERSION);
        initParams.add(KEY_CAPABILITIES, new JsonObject());
        initParams.add(KEY_CLIENT_INFO, clientInfo);

        HttpResponse<String> initResponse =
            post(client, mcpUri, null, jsonRpcRequest(1, METHOD_INITIALIZE, initParams));
        if (initResponse.statusCode() != 200)
        {
            return null;
        }
        String sessionId = initResponse.headers().firstValue(HEADER_SESSION_ID).orElse(null);
        if (sessionId == null)
        {
            return null;
        }

        JsonObject initializedNotification = new JsonObject();
        initializedNotification.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        initializedNotification.addProperty(KEY_METHOD, METHOD_INITIALIZED_NOTIFICATION);
        post(client, mcpUri, sessionId, initializedNotification);

        JsonObject callParams = new JsonObject();
        callParams.addProperty(KEY_NAME, TOOL_ROUTER_STATUS);
        callParams.add(KEY_ARGUMENTS, new JsonObject());
        HttpResponse<String> statusResponse =
            post(client, mcpUri, sessionId, jsonRpcRequest(2, METHOD_TOOLS_CALL, callParams));
        if (statusResponse.statusCode() != 200)
        {
            return null;
        }
        JsonObject envelope = Json.parseObject(statusResponse.body());
        JsonObject result = envelope == null ? null : Json.obj(envelope, KEY_RESULT);
        return result == null ? null : Json.obj(result, KEY_STRUCTURED_CONTENT);
    }

    private static JsonObject jsonRpcRequest(int id, String method, JsonObject params)
    {
        JsonObject request = new JsonObject();
        request.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        request.addProperty(KEY_ID, id);
        request.addProperty(KEY_METHOD, method);
        request.add(KEY_PARAMS, params);
        return request;
    }

    /**
     * Posts one JSON-RPC message with {@code Accept: application/json} (so the proxy answers
     * plain JSON rather than SSE - see {@code McpProxyHandler#sendMcpResponse}), avoiding any
     * SSE-framing parsing in this small CLI client.
     */
    private static HttpResponse<String> post(HttpClient client, URI uri, String sessionId, JsonObject body)
        throws IOException, InterruptedException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS))
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
            .header(HEADER_ACCEPT, CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(Json.compact(body), StandardCharsets.UTF_8));
        if (sessionId != null)
        {
            builder.header(HEADER_SESSION_ID, sessionId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void printStatusTable(PrintStream out, int queriedPort, JsonObject structured)
    {
        JsonArray backends = asArray(structured, KEY_BACKENDS);
        out.println("edt-mcp-proxy status: reachable on :" + queriedPort //$NON-NLS-1$
            + " (" + backends.size() + " live backend(s))"); //$NON-NLS-1$ //$NON-NLS-2$
        out.println();
        if (backends.isEmpty())
        {
            out.println("  (no live EDT-MCP backends)"); //$NON-NLS-1$
        }
        else
        {
            out.println("  PORT     PROJECTS"); //$NON-NLS-1$
            for (JsonElement element : backends)
            {
                JsonObject backend = element.getAsJsonObject();
                int backendPort = backend.get(KEY_PORT).getAsInt();
                out.println("  " + padRight(String.valueOf(backendPort), 8) + " " //$NON-NLS-1$ //$NON-NLS-2$
                    + joinOrNone(asArray(backend, KEY_PROJECTS)));
            }
        }
        out.println();
        out.println(formatDuplicates(asObject(structured, KEY_DUPLICATES)));
        out.println("Scan range: " + stringOrEmpty(structured, KEY_SCAN_RANGE)); //$NON-NLS-1$
        out.println("Last refresh: " + formatLastRefresh(longOrZero(structured, KEY_LAST_REFRESH_MS))); //$NON-NLS-1$
    }

    private static String formatDuplicates(JsonObject duplicates)
    {
        if (duplicates == null || duplicates.entrySet().isEmpty())
        {
            return "Duplicates: none"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder("Duplicates: "); //$NON-NLS-1$
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : duplicates.entrySet())
        {
            if (!first)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            first = false;
            sb.append(entry.getKey()).append(" -> ").append(joinPorts(entry.getValue().getAsJsonArray())); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private static String joinPorts(JsonArray ports)
    {
        List<String> values = new ArrayList<>();
        for (JsonElement port : ports)
        {
            values.add(port.getAsString());
        }
        return String.join(", ", values); //$NON-NLS-1$
    }

    private static String joinOrNone(JsonArray projects)
    {
        if (projects.isEmpty())
        {
            return NONE;
        }
        List<String> names = new ArrayList<>();
        for (JsonElement project : projects)
        {
            names.add(project.getAsString());
        }
        return String.join(", ", names); //$NON-NLS-1$
    }

    private static String formatLastRefresh(long epochMillis)
    {
        if (epochMillis <= 0)
        {
            return "never"; //$NON-NLS-1$
        }
        long ageSeconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        return ageSeconds + "s ago"; //$NON-NLS-1$
    }

    private static String padRight(String value, int width)
    {
        StringBuilder sb = new StringBuilder(value);
        while (sb.length() < width)
        {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static JsonArray asArray(JsonObject parent, String key)
    {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
            ? parent.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject asObject(JsonObject parent, String key)
    {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
            ? parent.getAsJsonObject(key) : null;
    }

    private static String stringOrEmpty(JsonObject parent, String key)
    {
        String value = parent == null ? null : Json.str(parent, key);
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static long longOrZero(JsonObject parent, String key)
    {
        return parent != null && parent.has(key) && parent.get(key).isJsonPrimitive()
            ? parent.get(key).getAsLong() : 0L;
    }

    private static String unreachableMessage(int port)
    {
        return "edt-mcp-proxy: no proxy reachable on :" + port //$NON-NLS-1$
            + ". Is it running? Start it with: java -jar edt-mcp-proxy.jar serve --port " + port; //$NON-NLS-1$
    }

    private static URI adminShutdownUri(int port)
    {
        return URI.create("http://127.0.0.1:" + port + "/admin/shutdown"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static HttpClient newClient()
    {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CLIENT_TIMEOUT_SECONDS)).build();
    }
}
