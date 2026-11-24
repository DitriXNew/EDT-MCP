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
    @Test public void aNewRequestOnAnOpenPeerDoesNotExtendTheDrain() throws Exception
    {
        var frames = new CopyOnWriteArrayList<String>();
        var drained = new CountDownLatch(1);
        try (var lifetime = new com.ditrix.edt.mcp.server.HeadlessLifetime(java.time.Duration.ZERO, () -> { });
             var running = lifetime.tool();
             var connection = new IpcConnection(new com.ditrix.edt.mcp.server.McpServer(),
                 new McpProtocolHandler(), frames::add, null, () -> { }, lifetime))
        {
            connection.accept(init(1, false)); connection.accept(initialized());
            lifetime.requestShutdown(drained::countDown);
            connection.accept(call(2, "test_shutdown"));
            assertEquals(2, frames.size());
            var reply = JsonParser.parseString(frames.get(1)).getAsJsonObject();
            assertEquals(2, reply.get("id").getAsInt());
            assertEquals("Headless EDT is shutting down", reply.getAsJsonObject("error").get("message").getAsString());
            running.close();
            assertEquals(0, drained.getCount());
        }
    }

    @Test public void aStalledResponseReleasesTheDrainLeaseAfterDetaching() throws Exception
    {
        var deadlines = Executors.newSingleThreadScheduledExecutor();
        var detached = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        try (var lifetime = new com.ditrix.edt.mcp.server.HeadlessLifetime(java.time.Duration.ZERO, () -> { });
             var lease = lifetime.tool())
        {
            lifetime.requestShutdown(drained::countDown);
            IpcListener.withWriteDeadline(deadlines, 100, detached::countDown, () -> {
                assertEquals(1, drained.getCount());
                try { assertTrue(detached.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
            lease.close();
            assertEquals(0, drained.getCount());
        }
        finally { deadlines.shutdownNow(); }
    }

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
                awaitFrames(frames, 2);
                assertEquals("An id is answered rather than left waiting", 2, frames.size());
                var refusal = JsonParser.parseString(frames.get(1)).getAsJsonObject();
                assertEquals(7, refusal.get("id").getAsInt());
                assertTrue(refusal.has("error"));
                connection.accept(SHUTDOWN);
                awaitFrames(frames, 3);
                assertEquals("The decision is the first thing the host sends", 3, frames.size());
                assertEquals(McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED,
                    JsonParser.parseString(frames.get(2)).getAsJsonObject().get("method").getAsString());
                assertEquals(1, shutdowns.get());
                // A repeat while the host is already stopping must not start a second cycle; it is
                // still answered as accepted, because the process really is going down.
                connection.accept(SHUTDOWN);
                awaitFrames(frames, 4);
                assertEquals("A repeat is not a second closing cycle", 1, shutdowns.get());
                assertEquals(McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED,
                    JsonParser.parseString(frames.get(3)).getAsJsonObject().get("method").getAsString());
                receipt.get().accept(true);
                awaitFrames(frames, 5);
                assertEquals(5, frames.size());
                var message = JsonParser.parseString(frames.get(4)).getAsJsonObject();
                assertFalse(message.has("id"));
                assertEquals("notifications/host_shutdown_complete", message.get("method").getAsString());
                assertTrue(message.getAsJsonObject("params").get("success").getAsBoolean());
            }
        }
        finally { registry.replaceAll(saved); }
    }

    /**
     * A client that stopped reading must not hold the shutdown open: the receipt goes through a
     * bounded path, and a sink that cannot take it detaches the peer instead of blocking the close.
     */
    @Test public void aBlockedSinkDetachesThePeerInsteadOfBlockingTheShutdown() throws Exception
    {
        var registry = McpToolRegistry.getInstance(); var saved = registry.getAllTools();
        try
        {
            registry.replaceAll(List.of(sentinel("test_shutdown", "{\"value\":\"alive\"}")));
            var server = new com.ditrix.edt.mcp.server.McpServer();
            var receipt = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Boolean>>();
            server.setHostShutdown(completion -> receipt.set(completion));
            var detach = new java.util.concurrent.atomic.AtomicInteger();
            var sends = new java.util.concurrent.atomic.AtomicInteger();
            var held = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            // The first frame (the initialize reply) passes; everything after it is stuck, which
            // is what a client that stopped reading looks like from the host side.
            // Frame 1 is the initialize reply, frame 2 the accepted decision; everything after
            // that is stuck, which is what a client that stopped reading looks like from the host.
            java.util.function.Consumer<String> sink = json -> {
                if (sends.incrementAndGet() > 2)
                {
                    held.countDown();
                    try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            };
            try (var connection = new IpcConnection(server, new McpProtocolHandler(), sink, false,
                detach::incrementAndGet))
            {
                connection.accept(init(1, false));
                connection.accept(initialized());
                connection.accept(SHUTDOWN);
                var shutdownThread = new Thread(() -> receipt.get().accept(true));
                shutdownThread.setDaemon(true);
                shutdownThread.start();
                try
                {
                    assertTrue("the receipt must reach the blocked sink", held.await(5, TimeUnit.SECONDS));
                    // The sink stays blocked for the whole wait: the shutdown path must give up
                    // on its own, not because the client was released.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                    while (detach.get() == 0 && System.nanoTime() < deadline) { Thread.sleep(10); }
                    assertEquals("a blocked receipt must detach the peer", 1, detach.get());
                    shutdownThread.join(5000);
                    assertFalse("the shutdown must return while the client is still stuck",
                        shutdownThread.isAlive());
                }
                finally { release.countDown(); shutdownThread.join(5000); }
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
                awaitFrames(frames, 2);
                assertEquals("A refused shutdown is answered", 2, frames.size());
                assertEquals(McpConstants.METHOD_HOST_SHUTDOWN_REFUSED,
                    JsonParser.parseString(frames.get(1)).getAsJsonObject().get("method").getAsString());
                connection.accept(call(2, "test_shutdown"));
                awaitFrames(frames, 3);
                assertEquals(3, frames.size());
                var result = JsonParser.parseString(frames.get(2)).getAsJsonObject().getAsJsonObject("result");
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
                awaitFrames(frames, 2);
                assertEquals(McpConstants.METHOD_HOST_SHUTDOWN_ACCEPTED,
                    JsonParser.parseString(frames.get(1)).getAsJsonObject().get("method").getAsString());
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
