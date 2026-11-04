package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class StdioPilotTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private EndpointRegistry registry() throws Exception
    { return new EndpointRegistry(temporary.newFolder().toPath(), temporary.newFolder().toPath()); }
    private EndpointRegistry.Endpoint endpoint(EndpointRegistry registry, String generation, long pid)
    {
        return new EndpointRegistry.Endpoint(registry.workspace().toString(), generation, pid,
            ProcessHandle.current().info().startInstant().orElseThrow().toString(), "1.0.0", 1,
            registry.directory().resolve("socket").toString(), "private-secret");
    }

    @Test public void framesPreserveCyrillicAndCoalescedMessages() throws Exception
    {
        String data = "{\"text\":\"Справочник.Номенклатура Ёж\"}";
        var bytes = new ByteArrayOutputStream(); JsonFrames.write(bytes, data); JsonFrames.write(bytes, "{}");
        var input = new ByteArrayInputStream(bytes.toByteArray());
        assertEquals(data, JsonFrames.read(input)); assertEquals("{}", JsonFrames.read(input)); assertNull(JsonFrames.read(input));
        assertEquals(data, JsonFrames.read(new ByteArrayInputStream((data + "\r\n").getBytes(StandardCharsets.UTF_8))));
    }

    @Test public void rejectsTruncatedMalformedAndOversizedFrames() throws Exception
    {
        assertThrows(EOFException.class, () -> JsonFrames.read(new ByteArrayInputStream(new byte[]{'{'})));
        assertThrows(IOException.class, () -> JsonFrames.read(new ByteArrayInputStream(new byte[]{(byte) 0xff, 10})));
        assertThrows(IOException.class, () -> JsonFrames.write(new ByteArrayOutputStream(), "{}\n{}"));
        byte[] oversized = new byte[JsonFrames.MAX_BYTES + 1]; Arrays.fill(oversized, (byte)'a');
        assertThrows(IOException.class, () -> JsonFrames.read(new ByteArrayInputStream(oversized)));
    }

    @Test public void registryChecksPidAndStartAndNeverDeletesReplacement() throws Exception
    {
        var registry = registry();
        var stale = endpoint(registry, "old", Long.MAX_VALUE); registry.publish(stale);
        assertFalse(EndpointRegistry.live(registry.read()));
        EndpointRegistry.atomicWrite(registry.claimPath(), "{\"requested\":\"2026-11-04T00:00:00Z\"}");
        var replacement = endpoint(registry, "new", ProcessHandle.current().pid()); registry.publish(replacement);
        assertFalse("A ready listener clears the startup lease so a later crash can restart promptly", Files.exists(registry.claimPath()));
        assertTrue(EndpointRegistry.live(registry.read()));
        assertFalse(registry.remove("old")); assertEquals(replacement, registry.read());
        assertThrows(IOException.class, () -> registry.publish(endpoint(registry, "third", Long.MAX_VALUE)));
        assertFalse(EndpointRegistry.sameProcess(ProcessHandle.current().pid(), Instant.EPOCH.toString()));
        assertTrue(registry.remove("new")); assertNull(registry.read());
        try (var files = Files.list(registry.directory())) { assertFalse(files.anyMatch(p -> p.toString().endsWith(".tmp"))); }
    }

    @Test public void handshakeRejectsWrongSecretGenerationWorkspaceAndVersion() throws Exception
    {
        var registry = registry(); var expected = endpoint(registry, "generation", ProcessHandle.current().pid());
        assertTrue(IpcHandshake.accepts(IpcHandshake.hello(expected), expected));
        for (String field : List.of("secret", "generation", "workspace", "pluginVersion", "startTime", "ipcVersion", "pid"))
        {
            var wrong = JsonParser.parseString(IpcHandshake.hello(expected)).getAsJsonObject();
            if (field.equals("ipcVersion") || field.equals("pid")) { wrong.addProperty(field, -1); }
            else { wrong.addProperty(field, "wrong"); }
            assertFalse(field, IpcHandshake.accepts(wrong.toString(), expected));
        }
        assertFalse(IpcHandshake.accepts("{}", expected)); assertFalse(IpcHandshake.accepts("not-json", expected));
    }

    @Test public void startupMutexAllowsOneStarterAcrossRacingClients() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); entered.countDown(); release.await(); return null; });
        var second = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<String> result = executor.submit(first::ensureStarted); assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(second.ensureStarted().contains("starting")); release.countDown();
            assertTrue(result.get(3, TimeUnit.SECONDS).contains("starting"));
            assertTrue(second.ensureStarted().contains("starting")); assertEquals(1, starts.get());
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void expiredClaimAndDeadRecordCanStartButLiveOwnerCannot() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var coordinator = new StartupCoordinator(registry, () -> { starts.incrementAndGet(); return null; });
        registry.publish(endpoint(registry, "crashed", Long.MAX_VALUE));
        EndpointRegistry.atomicWrite(registry.claimPath(), "{\"requested\":\"2000-01-01T00:00:00Z\"}");
        assertTrue(coordinator.ensureStarted().contains("starting")); assertEquals(1, starts.get());
        registry.publish(endpoint(registry, "live", ProcessHandle.current().pid()));
        coordinator.ensureStarted(); assertEquals(1, starts.get());
    }

    @Test public void bootstrapInitializeAndListDoNotStartEdtAndFirstCallIsPrompt() throws Exception
    {
        var registry = registry(); AtomicInteger starts = new AtomicInteger();
        var output = new ByteArrayOutputStream();
        CountDownLatch release = new CountDownLatch(1), started = new CountDownLatch(1);
        try (var bootstrap = new StdioBootstrap(registry, () -> {
            starts.incrementAndGet(); started.countDown(); release.await(); return null;
        }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            bootstrap.accept("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            assertEquals(0, starts.get());
            long begin = System.nanoTime(); bootstrap.accept(call(3, "read"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 1000);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            bootstrap.accept(call(4, "read")); assertEquals(1, starts.get());
            var frames = new ByteArrayInputStream(output.toByteArray());
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            var result = JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject();
            assertEquals(3, result.get("id").getAsInt()); assertTrue(result.getAsJsonObject("result").get("isError").getAsBoolean());
        }
        finally { release.countDown(); }
    }

    @Test public void perConnectionCapabilitiesIdsAndNotificationSinksAreIsolated() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "pilot_read"; }
            public String getDescription() { return "Pilot read"; }
            public String getInputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public String getOutputSchema() { return "{\"type\":\"object\"}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String,String> arguments) { return "{\"value\":\"Привет\"}"; }
        };
        try
        {
            registry.replaceAll(List.of(tool));
            List<String> a = new ArrayList<>(), b = new ArrayList<>();
            // Also exercise the explicit-capabilities overload against ONE shared dispatcher.
            var shared = new McpProtocolHandler();
            var first = new IpcConnection(shared, a::add); var second = new IpcConnection(shared, b::add);
            first.accept(init(10, true)); second.accept(init(20, false));
            first.accept(initialized()); second.accept(initialized());
            first.accept(call(11, "pilot_read")); second.accept(call(21, "pilot_read"));
            var ar = JsonParser.parseString(a.get(1)).getAsJsonObject(); var br = JsonParser.parseString(b.get(1)).getAsJsonObject();
            assertEquals(11, ar.get("id").getAsInt()); assertEquals(21, br.get("id").getAsInt());
            assertFalse(ar.getAsJsonObject("result").has("structuredContent"));
            assertTrue(br.getAsJsonObject("result").has("structuredContent"));
            first.toolsChanged(); assertEquals(3, a.size()); assertEquals(2, b.size());
            assertEquals("notifications/tools/list_changed", JsonParser.parseString(a.get(2)).getAsJsonObject().get("method").getAsString());
        }
        finally { registry.replaceAll(saved); }
    }

    @Test public void explicitPathsNeverGuessCwd() throws Exception
    {
        assertThrows(IOException.class, () -> StdioBootstrap.explicitPath(new String[0], "-data"));
        assertThrows(IOException.class, () -> StdioBootstrap.explicitPath(new String[]{"-data", "."}, "-data"));
        assertEquals(temporary.getRoot().toPath(), StdioBootstrap.explicitPath(new String[]{"-data", temporary.getRoot().toString()}, "-data"));
    }

    @Test public void actualUdsCarriesHandshakeRequestsAndPushedNotifications() throws Exception
    {
        var registry = new EndpointRegistry(temporary.getRoot().toPath(), temporary.newFolder().toPath());
        try (var listener = new IpcListener(registry, "pilot");
             var socket = new JsonFrames.Socket(SocketChannel.open(StandardProtocolFamily.UNIX)))
        {
            var endpoint = registry.read(); socket.connect(UnixDomainSocketAddress.of(endpoint.socket()));
            IpcHandshake.client(socket, endpoint);
            socket.write(init(101, false));
            assertEquals(101, JsonParser.parseString(socket.read()).getAsJsonObject().get("id").getAsInt());
            socket.write(initialized());
            socket.write("{\"jsonrpc\":\"2.0\",\"id\":102,\"method\":\"tools/list\"}");
            assertEquals(102, JsonParser.parseString(socket.read()).getAsJsonObject().get("id").getAsInt());
            ToolsChangedListeners.notifyChanged();
            ExecutorService reader = Executors.newSingleThreadExecutor();
            try
            {
                String notification = reader.submit(socket::read).get(3, TimeUnit.SECONDS);
                assertEquals("notifications/tools/list_changed", JsonParser.parseString(notification).getAsJsonObject().get("method").getAsString());
            }
            finally { reader.shutdownNow(); }
        }
        assertNull(registry.read());
    }

    @Test public void embeddedJarContainsGeneratedSchemasAndNoPlatformClasses() throws Exception
    {
        var saved = McpToolRegistry.getInstance().getAllTools();
        try
        {
            BuiltInToolRegistrar.registerAll(McpToolRegistry.getInstance());
            JsonObject generated = null;
            try (var input = StdioBootstrap.class.getResourceAsStream("/bootstrap/edt-mcp-runtime.jar"))
            {
                assertNotNull("The plugin must ship the bootstrap inside the bundle", input);
                try (var jar = new java.util.jar.JarInputStream(input))
                {
                    java.util.jar.JarEntry entry;
                    while ((entry = jar.getNextJarEntry()) != null)
                    {
                        assertFalse(entry.getName(), entry.getName().startsWith("org/eclipse/"));
                        assertFalse(entry.getName(), entry.getName().startsWith("org/osgi/"));
                        if (entry.getName().equals("bootstrap-catalog.json"))
                        { generated = JsonParser.parseString(new String(jar.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(); }
                        if (entry.getName().startsWith("com/ditrix/") && entry.getName().endsWith(".class"))
                        {
                            byte[] bytes = jar.readAllBytes();
                            assertEquals("Bootstrap classes must target Java 17", 61, ((bytes[6] & 255) << 8) | (bytes[7] & 255));
                        }
                    }
                }
            }
            assertNotNull(generated);
            assertEquals(GsonProvider.get().toJsonTree(McpProtocolHandler.toolCatalog(
                McpToolRegistry.getInstance().getAllTools(), ClientCapabilities.ABSENT)), generated.get("tools"));
        }
        finally { McpToolRegistry.getInstance().replaceAll(saved); }
    }

    @Test public void bootstrapRelaysAndNeverReplaysADroppedWrite() throws Exception
    {
        var tools = McpToolRegistry.getInstance(); var saved = tools.getAllTools();
        AtomicInteger writes = new AtomicInteger(), starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "pilot_write"; }
            public String getDescription() { return "Test write"; }
            public String getInputSchema() { return "{\"type\":\"object\"}"; }
            public String execute(Map<String,String> arguments)
            {
                writes.incrementAndGet(); entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "committed";
            }
        };
        var registry = new EndpointRegistry(temporary.getRoot().toPath(), temporary.newFolder().toPath());
        var output = new ByteArrayOutputStream(); JsonObject catalog = catalog();
        catalog.getAsJsonObject("tools").getAsJsonArray("tools").get(0).getAsJsonObject().addProperty("name", "pilot_write");
        try
        {
            tools.replaceAll(List.of(tool));
            try (var listener = new IpcListener(registry, "pilot");
                 var bootstrap = new StdioBootstrap(registry, () -> { starts.incrementAndGet(); return null; }, output, catalog))
            {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
                bootstrap.accept(call(2, "pilot_write"));
                awaitOutput(output, "notifications/tools/list_changed");
                assertEquals("The first call only attached; it must not execute later", 0, writes.get());
                bootstrap.accept(call(3, "pilot_write")); assertTrue(entered.await(3, TimeUnit.SECONDS));
                listener.close();
                awaitOutput(output, "Outcome is unknown");
                release.countDown();
                assertEquals(1, writes.get()); assertEquals("Attach does not start a second EDT", 0, starts.get());
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("has not replayed"));
            }
        }
        finally { release.countDown(); tools.replaceAll(saved); }
    }

    @Test public void malformedBootstrapRequestsProduceErrorsWithoutStarting() throws Exception
    {
        AtomicInteger starts = new AtomicInteger(); var output = new ByteArrayOutputStream();
        try (var bootstrap = new StdioBootstrap(registry(), () -> { starts.incrementAndGet(); return null; }, output, catalog()))
        {
            for (String frame : List.of("[1]", "{", "{\"jsonrpc\":null,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"method\":{},\"id\":1}")) { bootstrap.accept(frame); }
            var input = new ByteArrayInputStream(output.toByteArray());
            for (int i = 0; i < 4; i++) { assertTrue(JsonParser.parseString(JsonFrames.read(input)).getAsJsonObject().has("error")); }
            assertEquals(0, starts.get());
        }
    }

    @Test public void relayPreservesLargeAndStringRequestIdsAndCyrillic() throws Exception
    {
        var tools = McpToolRegistry.getInstance(); var saved = tools.getAllTools();
        var registry = new EndpointRegistry(temporary.getRoot().toPath(), temporary.newFolder().toPath());
        var output = new ByteArrayOutputStream();
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "read"; }
            public String getDescription() { return "Test read"; }
            public String getInputSchema() { return "{\"type\":\"object\"}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String,String> arguments) { return "{\"text\":\"Справочник.Ёж\"}"; }
        };
        try
        {
            tools.replaceAll(List.of(tool));
            try (var listener = new IpcListener(registry, "pilot");
                 var bootstrap = new StdioBootstrap(registry, () -> { fail("Should attach"); return null; }, output, catalog()))
            {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "read"));
                awaitOutput(output, "notifications/tools/list_changed");
                bootstrap.accept(call(3, "read").replace("\"id\":3", "\"id\":9007199254740993"));
                awaitOutput(output, "9007199254740993");
                bootstrap.accept(call(4, "read").replace("\"id\":4", "\"id\":\"client-Ё\""));
                awaitOutput(output, "client-Ё");
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("Справочник.Ёж"));
            }
        }
        finally { tools.replaceAll(saved); }
    }

    private static void awaitOutput(ByteArrayOutputStream output, String text) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline)
        { if (output.toString(StandardCharsets.UTF_8).contains(text)) { return; } Thread.sleep(10); }
        fail("Missing output: " + text + " in " + output.toString(StandardCharsets.UTF_8));
    }

    private static JsonObject catalog()
    {
        return JsonParser.parseString("{\"versions\":[\"2025-11-25\"],\"initialize\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"tools\":{\"tools\":[{\"name\":\"read\",\"inputSchema\":{\"type\":\"object\"}}]}}").getAsJsonObject();
    }
    private static String init(int id, boolean optOut)
    { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{\"capabilities\":{\"experimental\":{\"structuredContent\":" + !optOut + "}}}}"; }
    private static String initialized() { return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"; }
    private static String call(int id, String name)
    { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}"; }
}
