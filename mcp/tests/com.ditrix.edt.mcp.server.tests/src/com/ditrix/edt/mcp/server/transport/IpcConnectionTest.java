package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class IpcConnectionTest extends StdioTestSupport
{
    @Test public void concurrentBridgesDeliverTheirOwnModes() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        var preferences = com.ditrix.edt.mcp.server.Activator.getDefault().getPreferenceStore();
        String key = com.ditrix.edt.mcp.server.preferences.PreferenceConstants.PREF_PLAIN_TEXT_MODE;
        boolean previous = preferences.getBoolean(key);
        var entered = new CountDownLatch(2);
        String payload = "{\"value\":\"" + "complete result ".repeat(100) + "\"}";
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "test_delivery"; }
            public String getDescription() { return "Test delivery"; }
            public String getInputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String, String> arguments) {
                entered.countDown();
                try { assertTrue(entered.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return payload;
            }
        };
        try
        {
            preferences.setValue(key, true);
            registry.replaceAll(List.of(tool));
            List<String> a = new CopyOnWriteArrayList<>(), b = new CopyOnWriteArrayList<>();
            var server = new com.ditrix.edt.mcp.server.McpServer();
            var shared = new McpProtocolHandler();
            try (var plain = new IpcConnection(server, shared, a::add, true);
                 var structured = new IpcConnection(server, shared, b::add, false))
            {
                plain.accept(init(1, false)); structured.accept(init(2, false));
                plain.accept(initialized()); structured.accept(initialized());
                plain.accept(call(3, tool.getName())); structured.accept(call(4, tool.getName()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((a.size() < 2 || b.size() < 2) && System.nanoTime() < deadline) { Thread.sleep(5); }
                assertEquals(2, a.size()); assertEquals(2, b.size());
                var text = JsonParser.parseString(a.get(1)).getAsJsonObject().getAsJsonObject("result");
                var json = JsonParser.parseString(b.get(1)).getAsJsonObject().getAsJsonObject("result");
                var expected = JsonParser.parseString(payload);
                assertEquals(expected, text.get("structuredContent"));
                assertEquals(expected, json.get("structuredContent"));
                String full = text.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                assertEquals(expected, JsonParser.parseString(full));
                String digest = json.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
                assertTrue(digest.length() <= 500); assertNotEquals(full, digest);
            }
        }
        finally { registry.replaceAll(saved); preferences.setValue(key, previous); }
    }

    @Test public void perConnectionCapabilitiesIdsAndNotificationSinksAreIsolated() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "test_read"; }
            public String getDescription() { return "Test read"; }
            public String getInputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public String getOutputSchema() { return "{\"type\":\"object\"}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String,String> arguments) { return "{\"value\":\"Привет\"}"; }
        };
        try
        {
            registry.replaceAll(List.of(tool));
            List<String> a = new CopyOnWriteArrayList<>(), b = new CopyOnWriteArrayList<>();
            // Also exercise the explicit-capabilities overload against ONE shared dispatcher.
            var shared = new McpProtocolHandler();
            try (var first = new IpcConnection(new com.ditrix.edt.mcp.server.McpServer(), shared, a::add);
                 var second = new IpcConnection(new com.ditrix.edt.mcp.server.McpServer(), shared, b::add))
            {
                first.accept(init(10, true)); second.accept(init(20, false));
                first.accept(initialized()); second.accept(initialized());
                first.accept(call(11, "test_read")); second.accept(call(21, "test_read"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while ((a.size() < 2 || b.size() < 2) && System.nanoTime() < deadline) { Thread.sleep(5); }
                assertEquals(2, a.size()); assertEquals(2, b.size());
                var ar = JsonParser.parseString(a.get(1)).getAsJsonObject(); var br = JsonParser.parseString(b.get(1)).getAsJsonObject();
                assertEquals(11, ar.get("id").getAsInt()); assertEquals(21, br.get("id").getAsInt());
                assertFalse(ar.getAsJsonObject("result").has("structuredContent"));
                assertTrue(br.getAsJsonObject("result").has("structuredContent"));
                first.toolsChanged(); assertEquals(3, a.size()); assertEquals(2, b.size());
                assertEquals("notifications/tools/list_changed", JsonParser.parseString(a.get(2)).getAsJsonObject().get("method").getAsString());
            }
        }
        finally { registry.replaceAll(saved); }
    }

    private static final String SHUTDOWN = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown\"}";

    private static IMcpTool sentinel(String name, String payload)
    {
        return new IMcpTool() {
            public String getName() { return name; }
            public String getDescription() { return "Shutdown sentinel"; }
            public String getInputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            public ResponseType getResponseType() { return ResponseType.JSON; }
            public String execute(Map<String, String> arguments) { return payload; }
        };
    }

    private static void awaitFrames(List<String> frames, int expected) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (frames.size() < expected && System.nanoTime() < deadline) { Thread.sleep(5); }
    }

    @Test public void hostShutdownNotificationRunsItsChannelOnceAndNeverReplies() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        try
        {
            registry.replaceAll(List.of(sentinel("test_shutdown", "{\"value\":\"alive\"}")));
            List<String> frames = new CopyOnWriteArrayList<>();
            var server = new com.ditrix.edt.mcp.server.McpServer();
            java.util.concurrent.atomic.AtomicInteger shutdowns = new java.util.concurrent.atomic.AtomicInteger();
            var receipt = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Boolean>>();
            server.setHostShutdown(completion -> { shutdowns.incrementAndGet(); receipt.set(completion); });
            try (var connection = new IpcConnection(server, new McpProtocolHandler(), frames::add))
            {
                connection.accept(init(1, false)); connection.accept(initialized());
                assertEquals(1, frames.size());
                connection.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown\",\"id\":7}");
                assertEquals("A request with an id must not stop the host", 0, shutdowns.get());
                connection.accept(SHUTDOWN);
                assertEquals("No reply before cleanup", 1, frames.size());
                assertEquals(1, shutdowns.get());
                // A repeat while the host is already stopping must not start a second cycle, and an
                // id on it must not be answered either: the method is a notification, not a request.
                connection.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/host_shutdown\",\"id\":7}");
                assertEquals("A repeat is a no-op, not a second closing cycle", 1, shutdowns.get());
                assertEquals("An id on it must not produce a reply", 1, frames.size());
                receipt.get().accept(true);
                assertEquals(2, frames.size());
                var message = JsonParser.parseString(frames.get(1)).getAsJsonObject();
                assertFalse(message.has("id"));
                assertEquals("notifications/host_shutdown_complete", message.get("method").getAsString());
                assertTrue(message.getAsJsonObject("params").get("success").getAsBoolean());
            }
        }
        finally { registry.replaceAll(saved); }
    }

    @Test public void hostShutdownNotificationIsRefusedByAHostWithoutTheChannel() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        try
        {
            registry.replaceAll(List.of(sentinel("test_shutdown", "{\"value\":\"alive\"}")));
            List<String> frames = new CopyOnWriteArrayList<>();
            // The windowed IDE: no channel armed, so the request is refused in the log - and the
            // session is untouched, which is the half that matters to the user.
            try (var connection = new IpcConnection(new com.ditrix.edt.mcp.server.McpServer(), new McpProtocolHandler(), frames::add))
            {
                connection.accept(init(1, false)); connection.accept(initialized());
                connection.accept(SHUTDOWN);
                assertEquals("A refused shutdown changes nothing on the wire", 1, frames.size());
                connection.accept(call(2, "test_shutdown"));
                awaitFrames(frames, 2);
                assertEquals(2, frames.size());
                var result = JsonParser.parseString(frames.get(1)).getAsJsonObject().getAsJsonObject("result");
                assertEquals("{\"value\":\"alive\"}",
                    result.getAsJsonObject("structuredContent").toString());
            }
        }
        finally { registry.replaceAll(saved); }
    }

    @Test public void hostShutdownWaitsForInitializeLikeEveryOtherNotification() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        try
        {
            registry.replaceAll(List.of());
            List<String> frames = new CopyOnWriteArrayList<>();
            var server = new com.ditrix.edt.mcp.server.McpServer();
            java.util.concurrent.atomic.AtomicInteger shutdowns = new java.util.concurrent.atomic.AtomicInteger();
            server.setHostShutdown(completion -> shutdowns.incrementAndGet());
            try (var connection = new IpcConnection(server, new McpProtocolHandler(), frames::add))
            {
                connection.accept("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                connection.accept(SHUTDOWN);
                assertEquals("An uninitialized peer cannot stop the host", 0, shutdowns.get());
                connection.accept(init(1, false)); connection.accept(initialized());
                connection.accept(SHUTDOWN);
                assertEquals(1, shutdowns.get());
            }
        }
        finally { registry.replaceAll(saved); }
    }

    @Test public void actualUdsCarriesHandshakeRequestsAndPushedNotifications() throws Exception
    {
        var registry = new EndpointRegistry(temporary.getRoot().toPath(), temporary.newFolder().toPath());
        try (var listener = new IpcListener(registry, "test");
             var socket = new JsonFrames.Socket(SocketChannel.open(StandardProtocolFamily.UNIX)))
        {
            var endpoint = registry.read(); socket.connect(UnixDomainSocketAddress.of(endpoint.socket()));
            if (PrivatePaths.posix(Path.of(endpoint.socket())))
            { assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(Path.of(endpoint.socket()))); }
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
}
