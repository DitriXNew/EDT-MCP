/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Plain Java entry point loaded by 1cedtc -startup. No Eclipse, OSGi, HTTP or TCP. */
public final class StdioBootstrap implements AutoCloseable
{
    static final long ATTACH_WAIT_MS = 2000;
    @FunctionalInterface public interface RegistryProvider { EndpointRegistry open() throws IOException; }
    private final RegistryProvider registries;
    private final StartupCoordinator.Starter starter;
    private final OutputStream output;
    private final JsonObject catalog;
    private final boolean headless;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "edt-mcp-attach"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean attaching = new AtomicBoolean();
    private record Pending(JsonElement id, boolean toolCall, JsonFrames.Socket owner) { }
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private volatile JsonFrames.Socket connection;
    private volatile String status = "EDT is connecting. Retry the same call in about 2 seconds. No tool ran.";
    private volatile String launchFailure;
    private CompletableFuture<String> preparation;
    private JsonObject initialize;
    private boolean initialized;
    private volatile boolean closed;

    public StdioBootstrap(EndpointRegistry registry, StartupCoordinator.Starter starter, OutputStream output,
        JsonObject catalog)
    { this(() -> registry, starter, output, catalog); }

    public StdioBootstrap(RegistryProvider registries, StartupCoordinator.Starter starter, OutputStream output,
        JsonObject catalog)
    { this(registries, starter, output, catalog, false); }

    public StdioBootstrap(RegistryProvider registries, StartupCoordinator.Starter starter, OutputStream output,
        JsonObject catalog, boolean headless)
    { this.registries = registries; this.starter = starter; this.output = output; this.catalog = catalog; this.headless = headless; }

    public static boolean headlessRequested(String[] args) { return java.util.Arrays.asList(args).contains("-headless"); }

    public static void main(String[] args)
    {
        try
        {
            Path install = explicitPath(args, "-install");
            String installFix = "Set -install to the EDT installation directory in .mcp.json.";
            try { install = install.toRealPath(); }
            catch (NoSuchFileException e)
            { throw new IOException(withAdvice("-install path does not exist: " + install, installFix), e); }
            catch (IOException e)
            { throw new IOException(withAdvice("Cannot access -install path " + install + ": " + e.getMessage(),
                "Check read access to -install and set it to the EDT installation directory in .mcp.json."), e); }
            if (!Files.isDirectory(install))
            { throw new IOException(withAdvice("-install is not a directory: " + install, installFix)); }
            Path workspace = explicitPath(args, "-data");
            try { WindowsEdtStarter.checkRuntime(install); }
            catch (WindowsEdtStarter.InstallationException e)
            { throw new IOException(withAdvice("Cannot use -install " + install + ": " + e.getMessage(), installFix), e); }
            Path configuration = optionalPath(args, "-configuration");
            JsonObject catalog;
            try (var resource = StdioBootstrap.class.getResourceAsStream("/bootstrap-catalog.json"))
            {
                if (resource == null) { throw new IOException("The plugin's generated bootstrap catalog is missing"); }
                catalog = JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
            catch (IOException | RuntimeException e)
            { throw new IOException(withAdvice("Cannot load the bootstrap catalog: " + e.getMessage(), "Reinstall the EDT MCP plugin."), e); }
            RegistryProvider registries = () -> {
                Files.createDirectories(workspace);
                return new EndpointRegistry(EndpointRegistry.privateDirectory(EndpointRegistry.userDirectory().resolve("ipc")), workspace);
            };
            try (var bootstrap = new StdioBootstrap(registries, new WindowsEdtStarter(install, workspace, configuration, headlessRequested(args)),
                System.out, catalog, headlessRequested(args)))
            {
                var input = new BufferedInputStream(System.in);
                String frame;
                try { while ((frame = JsonFrames.read(input)) != null) { bootstrap.accept(frame); } }
                catch (IOException e)
                { throw new IOException(withAdvice("MCP stdio transport failed: " + e.getMessage(),
                    "Check the client's stdio connection and newline-delimited UTF-8 JSON-RPC frames."), e); }
            }
        }
        catch (Exception e) { System.err.println("EDT MCP bootstrap: " + e.getMessage()); System.exit(1); }
    }

    private static String withAdvice(String message, String advice)
    {
        String text = message.stripTrailing();
        return text + (text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ? " " : ". ") + advice;
    }

    public static Path explicitPath(String[] args, String option) throws IOException
    {
        Path path = optionalPath(args, option);
        if (path == null) { throw new IOException("Explicit " + option + " is required in .mcp.json"); }
        return path;
    }

    private static Path optionalPath(String[] args, String option) throws IOException
    {
        for (int i = 0; i < args.length; i++)
        {
            if (args[i].equals(option))
            {
                if (i + 1 == args.length) { throw new IOException("Missing " + option + " value; set an absolute directory path in .mcp.json."); }
                Path path;
                try { path = Path.of(args[i + 1]); }
                catch (InvalidPathException e)
                { throw new IOException(withAdvice("Invalid " + option + " path: " + args[i + 1],
                    "Set a valid absolute directory path in .mcp.json."), e); }
                if (!path.isAbsolute())
                { throw new IOException(withAdvice(option + " must be an absolute path: " + path,
                    "Set an absolute directory path in .mcp.json.")); }
                return path;
            }
        }
        return null;
    }

    public void accept(String frame) throws IOException
    {
        JsonObject request;
        try
        {
            var value = JsonParser.parseString(frame);
            if (!value.isJsonObject()) { send(error(JsonNull.INSTANCE, -32600, "Expected a JSON-RPC object")); return; }
            request = value.getAsJsonObject();
        }
        catch (RuntimeException e) { send(error(JsonNull.INSTANCE, -32700, "Invalid JSON")); return; }
        JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        if (!isString(request.get("method")) || !isString(request.get("jsonrpc"))
            || !"2.0".equals(request.get("jsonrpc").getAsString())
            || (!id.isJsonNull() && (!id.isJsonPrimitive() || id.getAsJsonPrimitive().isBoolean())))
        { send(error(id, -32600, "Invalid JSON-RPC request")); return; }
        String method = request.get("method").getAsString();
        if (method.equals("initialize"))
        {
            if (initialize != null) { send(error(id, -32600, "Already initialized")); return; }
            initialize = request.deepCopy();
            JsonObject result = catalog.getAsJsonObject("initialize").deepCopy();
            String version = request.has("params") && request.get("params").isJsonObject()
                && isString(request.getAsJsonObject("params").get("protocolVersion"))
                ? request.getAsJsonObject("params").get("protocolVersion").getAsString() : "";
            for (JsonElement supported : catalog.getAsJsonArray("versions"))
            { if (supported.getAsString().equals(version)) { result.addProperty("protocolVersion", version); } }
            send(success(id, result)); return;
        }
        if (method.equals("notifications/initialized")) { initialized = initialize != null; return; }
        if (id.isJsonNull())
        {
            JsonFrames.Socket current = connection;
            if (current != null) { try { current.write(frame); } catch (IOException e) { disconnected(current); } }
            return;
        }
        if (method.equals("ping")) { send(success(id, new JsonObject())); return; }
        if (!initialized) { send(error(id, -32600, "Initialize this MCP connection first")); return; }
        JsonFrames.Socket current = connection;
        if (current != null) { forward(request, id, current); return; }
        if (method.equals("tools/list"))
        {
            JsonObject result = catalog.getAsJsonObject("tools").deepCopy();
            if (headless) {
                JsonArray allowed = new JsonArray();
                for (JsonElement tool : result.getAsJsonArray("tools")) {
                    String name = tool.getAsJsonObject().get("name").getAsString();
                    JsonObject hosts = catalog.getAsJsonObject("hostCapabilities");
                    if (hosts != null && hosts.has(name) && !"WINDOWED_ONLY".equals(hosts.get(name).getAsString())) { allowed.add(tool); }
                }
                result.add("tools", allowed);
            }
            if (structuredOptOut())
            { for (JsonElement tool : result.getAsJsonArray("tools")) { tool.getAsJsonObject().remove("outputSchema"); } }
            send(success(id, result)); return;
        }
        if (!method.equals("tools/call"))
        { send(error(id, -32002, "EDT is not attached. Call a tool to start or attach EDT.")); return; }
        JsonObject params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : null;
        if (params == null || !params.has("name") || !params.get("name").isJsonPrimitive()
            || !params.get("name").getAsJsonPrimitive().isString() || params.get("name").getAsString().isBlank())
        { send(error(id, -32602, "tools/call requires a nonblank name; use tools/list")); return; }
        String name = params.get("name").getAsString();
        boolean known = "debug_launch".equals(name);
        for (var tool : catalog.getAsJsonObject("tools").getAsJsonArray("tools"))
        { known |= tool.getAsJsonObject().get("name").getAsString().equals(name); }
        if (!known) { send(error(id, -32601, "Unknown tool; use tools/list")); return; }
        String refusal = status;
        try { refusal = prepareConnection().get(ATTACH_WAIT_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException e) { /* No invocation is retained by preparation. */ }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException e) { /* Preparation sets the actionable refusal. */ }
        current = connection;
        if (current != null) { forward(request, id, current); }
        else
        {
            String failure = launchFailure;
            send(toolError(id, failure != null ? failure : refusal));
        }
    }

    private void forward(JsonObject request, JsonElement id, JsonFrames.Socket current) throws IOException
    {
        if (pending.values().stream().anyMatch(call -> idKey(call.id()).equals(idKey(id))))
        { send(error(id, -32600, "Duplicate outstanding request id")); return; }
        if (pending.size() >= 128) { send(error(id, -32000, "Too many outstanding EDT requests")); return; }
        // IPC-owned string ids preserve external numeric ids beyond the dispatcher's Double precision.
        String key = "request-" + UUID.randomUUID();
        pending.put(key, new Pending(id.deepCopy(), "tools/call".equals(request.get("method").getAsString()), current));
        if (connection != current) { disconnected(current); return; }
        JsonObject forwarded = request.deepCopy(); forwarded.addProperty("id", key);
        try { current.write(forwarded.toString()); }
        catch (IOException e) { disconnected(current); }
    }

    private synchronized CompletableFuture<String> prepareConnection()
    {
        if (attaching.compareAndSet(false, true))
        {
            preparation = new CompletableFuture<>();
            CompletableFuture<String> ready = preparation;
            worker.execute(() -> attachOrStart(ready));
        }
        return preparation;
    }

    private boolean structuredOptOut()
    {
        try
        {
            var capability = initialize.getAsJsonObject("params").getAsJsonObject("capabilities");
            // Mirrors ClientCapabilities.allowsStructuredContent; catalog cannot promise an opted-out channel.
            JsonElement flag = capability.getAsJsonObject("experimental").get("structuredContent");
            return flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean() && !flag.getAsBoolean();
        }
        catch (RuntimeException e) { return false; }
    }

    private static boolean isString(JsonElement value)
    { return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(); }

    private void attachOrStart(CompletableFuture<String> prepared)
    {
        AtomicBoolean launching = new AtomicBoolean();
        try
        {
            var registry = registries.open();
            var endpoint = registry.read();
            if (endpoint == null || !EndpointRegistry.live(endpoint))
            {
                if (launchFailure != null) { status = launchFailure; return; }
                if (!starter.supportsStartup())
                { status = StartupCoordinator.OPEN_WORKSPACE; return; }
                status = new StartupCoordinator(registry, starter).ensureStarted(() -> {
                    launching.set(true);
                    status = StartupCoordinator.STARTING;
                    prepared.complete(status);
                });
                return;
            }
            if (!EndpointRegistry.workspaceKey(Path.of(endpoint.workspace())).equals(EndpointRegistry.workspaceKey(registry.workspace())))
            { throw new IOException("EDT endpoint workspace identity does not match"); }
            JsonFrames.Socket socket = new JsonFrames.Socket(SocketChannel.open(StandardProtocolFamily.UNIX));
            // A wedged listener must not hang the client's bootstrap or leak attach workers.
            var timeout = CompletableFuture.delayedExecutor(ATTACH_WAIT_MS, TimeUnit.MILLISECONDS);
            AtomicBoolean ready = new AtomicBoolean();
            timeout.execute(() -> { if (!ready.get()) { try { socket.close(); } catch (IOException ignored) { } } });
            try
            {
                // Connect on the channel before constructing any stream wrapper.
                connect(socket, endpoint);
                JsonObject replay = initialize.deepCopy();
                replay.addProperty("id", "bootstrap-" + UUID.randomUUID());
                socket.write(replay.toString());
                var response = JsonParser.parseString(socket.read()).getAsJsonObject();
                if (!response.has("result")) { throw new IOException("EDT rejected initialize"); }
                socket.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                ready.set(true);
                if (closed) { socket.close(); return; }
                connection = socket;
                launchFailure = null;
                Thread receiver = new Thread(() -> receive(socket), "edt-mcp-replies");
                receiver.setDaemon(true); receiver.start();
                send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
            }
            catch (Exception e) { socket.close(); throw e; }
        }
        catch (Exception e)
        {
            status = withAdvice("EDT connection/start failed: " + e.getMessage(), headless
                ? "Ask the user to inspect the EDT workspace log, then retry the same call. No tool ran."
                : "Ask the user to check the EDT window, then retry the same call. No tool ran.");
            if (launching.get()) { launchFailure = status; }
        }
        finally { prepared.complete(status); attaching.set(false); }
    }

    private void connect(JsonFrames.Socket socket, EndpointRegistry.Endpoint endpoint) throws IOException
    { socket.connect(UnixDomainSocketAddress.of(endpoint.socket())); IpcHandshake.client(socket, endpoint); }

    private void receive(JsonFrames.Socket socket)
    {
        try
        {
            String frame;
            while ((frame = socket.read()) != null)
            {
                JsonObject value = JsonParser.parseString(frame).getAsJsonObject();
                if (value.has("id"))
                {
                    Pending call = pending.remove(value.get("id").getAsString());
                    if (call == null || call.owner() != socket) { continue; }
                    value.add("id", call.id());
                }
                send(value.toString());
            }
        }
        catch (Exception e) { /* Disconnect reports the uncertain outcome once per outstanding request. */ }
        finally { disconnected(socket); }
    }

    private synchronized void disconnected(JsonFrames.Socket socket)
    {
        if (connection == socket)
        {
            connection = null;
            status = "EDT disconnected. Retry a read to reconnect. This call has not executed.";
        }
        try { socket.close(); } catch (IOException ignored) { }
        pending.forEach((key, call) -> {
            if (call.owner() == socket && pending.remove(key, call))
            {
                try
                {
                    String message = "EDT disconnected during the request. Outcome is unknown; a write may have committed. "
                        + "Read back the state before deciding whether to retry. The bootstrap has not replayed the call.";
                    send(call.toolCall() ? toolError(call.id(), message) : error(call.id(), -32002, "EDT disconnected; reconnect and retry this request."));
                }
                catch (IOException ignored) { }
            }
        });
    }

    private void send(String json) throws IOException { JsonFrames.write(output, json); }
    private static String idKey(JsonElement id)
    {
        return id.getAsJsonPrimitive().isNumber() ? "n:" + id.getAsBigDecimal().stripTrailingZeros().toString()
            : "s:" + id.getAsString();
    }
    public static String success(JsonElement id, JsonElement result)
    {
        JsonObject response = envelope(id); response.add("result", result); return response.toString();
    }
    private static JsonObject envelope(JsonElement id)
    { JsonObject response = new JsonObject(); response.addProperty("jsonrpc", "2.0"); response.add("id", id); return response; }
    public static String error(JsonElement id, int code, String message)
    {
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message);
        JsonObject response = envelope(id); response.add("error", error); return response.toString();
    }
    public static String toolError(JsonElement id, String message)
    {
        JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", ToolResult.error(message).toJson());
        JsonArray content = new JsonArray(); content.add(text);
        JsonObject result = new JsonObject(); result.addProperty("isError", true); result.add("content", content);
        return success(id, result);
    }
    @Override public void close()
    {
        closed = true;
        worker.shutdownNow();
        JsonFrames.Socket current = connection;
        if (current != null) { disconnected(current); }
    }
}
