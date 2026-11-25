package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.*;
import com.ditrix.edt.mcp.server.ActiveToolCall;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HeadlessLifetime;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class IpcConnectionTest extends StdioTestSupport
{
    @Test public void anAdmittedRequestRunsWhenShutdownBeginsBeforeProtocolDispatch() throws Exception
    {
        try (var fixture = new DrainFixture())
        {
            fixture.startCall();
            fixture.lifetime.requestShutdown(fixture.drained::countDown);
            assertEquals("the admitted request is still pending", 1, fixture.drained.getCount());

            fixture.finishTool.countDown();
            fixture.dispatch.countDown();
            String response = fixture.frames.poll(5, TimeUnit.SECONDS);
            assertNotNull("the admitted request must answer", response);
            var reply = JsonParser.parseString(response).getAsJsonObject();
            assertEquals(2, reply.get("id").getAsInt());
            assertTrue(response, reply.has("result"));
            assertEquals("ran", reply.getAsJsonObject("result").getAsJsonObject("structuredContent")
                .get("value").getAsString());
            assertTrue("shutdown can finish after delivery", fixture.drained.await(5, TimeUnit.SECONDS));
        }
    }

    @Test public void aDetachedWaiterDoesNotReleaseThePendingExecution() throws Exception
    {
        try (var fixture = new DrainFixture())
        {
            fixture.startCall();
            fixture.lifetime.requestShutdown(fixture.drained::countDown);
            fixture.connection.close();
            DrainFixture.join(fixture.waiter.get());
            assertEquals("shutdown must still wait for the worker", 1, fixture.drained.getCount());

            fixture.dispatch.countDown();
            assertTrue("the detached call still runs", fixture.executed.await(5, TimeUnit.SECONDS));
            assertEquals("shutdown must still wait inside the tool", 1, fixture.drained.getCount());
            fixture.finishTool.countDown();
            assertTrue("the worker releases the final lease", fixture.drained.await(5, TimeUnit.SECONDS));
            assertTrue("a detached peer gets no tool result", fixture.frames.isEmpty());
        }
    }

    @Test public void directProtocolDispatchStillRequiresItsOwnAdmission() throws Exception
    {
        try (var fixture = new DrainFixture())
        {
            fixture.startCall();
            fixture.lifetime.requestShutdown(fixture.drained::countDown);
            // Another request cannot borrow the lease held by the paused IPC worker.
            fixture.finishTool.countDown();
            String response = fixture.dispatcher.processRequest(call(3, DrainFixture.TOOL));
            // Assert the machine-readable payload, not prose that depends on the JVM locale.
            var refused = JsonParser.parseString(response).getAsJsonObject();
            assertNotNull(response, refused.get("result"));
            assertTrue(response, refused.getAsJsonObject("result").get("isError").getAsBoolean());
            assertEquals("unadmitted tool must not run", 1, fixture.executed.getCount());
        }
    }

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
            assertTrue(reply.toString(), reply.has("result"));
            var result = reply.getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            assertTrue(result.toString(), result.toString().contains("reconnect and retry a read"));
            running.close();
            assertEquals(0, drained.getCount());
        }
    }

    @Test public void ordinaryBackpressureIsNotTimedOut() throws Exception
    {
        var deadlines = Executors.newSingleThreadScheduledExecutor();
        var detached = new CountDownLatch(1);
        try
        {
            IpcListener.withWriteDeadline(deadlines, 50, () -> false, detached::countDown, () -> {
                try { assertFalse("ordinary backpressure must survive the drain budget",
                    detached.await(200, TimeUnit.MILLISECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            });
        }
        finally { deadlines.shutdownNow(); }
    }

    @Test public void aStalledResponseReleasesTheDrainLeaseAfterDetaching() throws Exception
    {
        var deadlines = Executors.newSingleThreadScheduledExecutor();
        var detached = new CountDownLatch(1);
        var drained = new CountDownLatch(1);
        try (var lifetime = new com.ditrix.edt.mcp.server.HeadlessLifetime(java.time.Duration.ZERO, () -> { });
             var lease = lifetime.tool())
        {
            IpcListener.withWriteDeadline(deadlines, 100, lifetime::isStopping, detached::countDown, () -> {
                assertEquals(1, drained.getCount());
                try {
                    assertFalse("write already blocked before draining", detached.await(250, TimeUnit.MILLISECONDS));
                    lifetime.requestShutdown(drained::countDown);
                    assertTrue(detached.await(3, TimeUnit.SECONDS));
                }
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

    private static final class DrainFixture implements AutoCloseable
    {
        private static final String TOOL = "get_server_status";
        private final String savedApplication = System.getProperty("eclipse.application");
        private final Field plugin = Activator.class.getDeclaredField("plugin");
        private final Object savedPlugin;
        private final McpToolRegistry registry = McpToolRegistry.getInstance();
        private final Collection<IMcpTool> savedTools = registry.getAllTools();
        final HeadlessLifetime lifetime = new HeadlessLifetime(Duration.ZERO, () -> { });
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final CountDownLatch beforeDispatch = new CountDownLatch(1);
        final CountDownLatch dispatch = new CountDownLatch(1);
        final CountDownLatch executed = new CountDownLatch(1);
        final CountDownLatch finishTool = new CountDownLatch(1);
        final CountDownLatch drained = new CountDownLatch(1);
        final AtomicReference<Thread> waiter = new AtomicReference<>();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final McpProtocolHandler dispatcher = new McpProtocolHandler();
        final IpcConnection connection;

        DrainFixture() throws Exception
        {
            plugin.setAccessible(true);
            savedPlugin = plugin.get(null);
            var server = new McpServer() {
                @Override public void setActiveToolCall(ActiveToolCall call) {
                    super.setActiveToolCall(call);
                    waiter.set(Thread.currentThread());
                }
                @Override public void bindToolCall(ActiveToolCall call) {
                    super.bindToolCall(call);
                    worker.set(Thread.currentThread());
                    beforeDispatch.countDown();
                    try { assertTrue("test must release dispatch", dispatch.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
            };
            var activator = mock(Activator.class);
            when(activator.getHeadlessLifetime()).thenReturn(lifetime);
            when(activator.getPreferenceStore()).thenReturn(new PreferenceStore());
            when(activator.getMcpServer()).thenReturn(server);
            connection = new IpcConnection(server, dispatcher, frames::add, false, () -> { }, lifetime);
            System.setProperty("eclipse.application", HostKind.APPLICATION_ID);
            plugin.set(null, activator);
            registry.replaceAll(List.of(new IMcpTool() {
                @Override public String getName() { return TOOL; }
                @Override public String getDescription() { return "Shutdown test probe"; }
                @Override public String getInputSchema() { return "{\"type\":\"object\"}"; }
                @Override public ResponseType getResponseType() { return ResponseType.JSON; }
                @Override public String execute(Map<String, String> arguments) {
                    executed.countDown();
                    try { assertTrue("test must release tool", finishTool.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    return "{\"value\":\"ran\"}";
                }
            }));
        }

        void startCall() throws Exception
        {
            connection.accept(init(1, false));
            assertNotNull("initialize must answer", frames.poll());
            connection.accept(initialized());
            connection.accept(call(2, TOOL));
            assertTrue("worker must reach the gate before dispatch", beforeDispatch.await(5, TimeUnit.SECONDS));
        }

        static void join(Thread thread) throws InterruptedException
        {
            if (thread != null)
            {
                thread.join(5000);
                assertFalse("test thread must exit: " + thread.getName(), thread.isAlive());
            }
        }

        @Override public void close() throws Exception
        {
            connection.close();
            dispatch.countDown();
            finishTool.countDown();
            try { join(waiter.get()); join(worker.get()); }
            finally
            {
                lifetime.close();
                plugin.set(null, savedPlugin);
                registry.replaceAll(savedTools);
                if (savedApplication == null) { System.clearProperty("eclipse.application"); }
                else { System.setProperty("eclipse.application", savedApplication); }
            }
        }
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
            var refusals = new java.util.concurrent.atomic.AtomicInteger();
            var refusingServer = new McpServer() {
                @Override public boolean requestHostShutdown(java.util.function.Consumer<Boolean> completion) {
                    refusals.incrementAndGet(); return super.requestHostShutdown(completion);
                }
            };
            try (var connection = new IpcConnection(refusingServer, new McpProtocolHandler(), frames::add))
            {
                connection.accept(init(1, false)); connection.accept(initialized());
                connection.accept(SHUTDOWN);
                assertEquals("refusal must reach the server's logging path", 1, refusals.get());
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
