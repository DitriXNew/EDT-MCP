package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;
import org.junit.*;
import com.ditrix.edt.mcp.server.*;
import com.ditrix.edt.mcp.server.history.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.protocol.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;

/** Real authenticated IPC frames with controlled tool completion; no EDT work is started. */
public class IpcInterruptTest extends StdioTestSupport
{
    private static final String TOOL = "ipc_interrupt_probe";

    @Test public void cancelAnswersWhileToolRunsAndDropsItsLateCompletion() throws Exception
    { assertEarlySignal(UserSignal.SignalType.CANCEL); }

    @Test public void backgroundAnswersWhileToolRunsAndDropsItsLateCompletion() throws Exception
    { assertEarlySignal(UserSignal.SignalType.BACKGROUND); }

    private void assertEarlySignal(UserSignal.SignalType type) throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage stage = f.stage("blocked"); p.call(71, stage); stage.awaitEntered();
            assertFalse("This fixture has no HTTP listener", f.server.isRunning());
            boolean menuEnabled = f.server.isToolExecuting();
            ActiveToolCall active = f.server.getActiveToolCall();
            assertTrue("The menu signal must be delivered before the tool completes", f.signal(type));
            assertTrue("Signal menu items use isToolExecuting even with HTTP off", menuEnabled);
            assertSignal(p.await(71)); assertEquals(1, stage.finished.getCount());
            assertFalse(stage.interrupted.get());
            var rows = f.history.snapshot().stream().filter(r -> TOOL.equals(r.getToolName())).toList();
            assertEquals(1, rows.size()); assertTrue(rows.get(0).isInterrupted());
            stage.release.countDown(); f.awaitWaiter(active); p.barrier(); p.assertIds(71, "barrier");
            assertFalse(stage.interrupted.get());
            assertEquals(1, f.history.snapshot().stream().filter(r -> TOOL.equals(r.getToolName())).count());
            assertEquals("success", f.history.snapshot().stream().filter(r -> TOOL.equals(r.getToolName()))
                .findFirst().orElseThrow().getBackgroundOutcome().getStatus());
        }
    }

    @Test public void normalCallReturnsExactlyOneResponse() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage stage = f.stage("normal"); p.call(72, stage); stage.awaitEntered();
            stage.release.countDown(); assertNormal(p.await(72), stage);
            p.barrier(); p.assertIds(72, "barrier");
        }
    }

    @Test public void completionBeforeInterruptKeepsItsOnlyNormalResponse() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage stage = f.stage("first"); p.call(73, stage); stage.awaitEntered();
            ActiveToolCall active = f.server.getActiveToolCall();
            stage.release.countDown(); assertNormal(p.await(73), stage);
            assertFalse(f.signal(UserSignal.SignalType.CANCEL));
            assertNotNull("The shared executor must register normal calls too", active);
            assertFalse(active.sendSignalResponse(new UserSignal(UserSignal.SignalType.CANCEL, "Late signal")));
            f.awaitWaiter(active); p.barrier(); p.assertIds(73, "barrier");
        }
    }

    @Test public void simultaneousInterruptAndCompletionWriteOneWholeFrame() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            for (int attempt = 0; attempt < 8; attempt++)
            {
                Stage stage = f.stage("race-" + attempt); int id = 80 + attempt;
                p.call(id, stage); stage.awaitEntered(); ActiveToolCall active = f.server.getActiveToolCall();
                assertNotNull(active);
                CyclicBarrier start = new CyclicBarrier(3); AtomicBoolean sent = new AtomicBoolean();
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread signal = race(start, failure, () -> sent.set(f.signal(UserSignal.SignalType.CANCEL)));
                Thread finish = race(start, failure, stage.release::countDown);
                start.await(3, TimeUnit.SECONDS); signal.join(3000); finish.join(3000);
                assertFalse(signal.isAlive()); assertFalse(finish.isAlive()); assertNull(failure.get());
                JsonObject response = p.await(id);
                if (sent.get()) { assertSignal(response); } else { assertNormal(response, stage); }
                f.awaitWaiter(active);
            }
            p.barrier(); p.assertIds(80, 81, 82, 83, 84, 85, 86, 87, "barrier");
        }
    }

    @Test public void pingIsReadableWhileAToolIsBlocked() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage stage = f.stage("ping"); p.call(91, stage); stage.awaitEntered();
            p.barrier(); assertEquals(1, stage.finished.getCount());
            stage.release.countDown(); assertNormal(p.await(91), stage); p.assertIds("barrier", 91);
        }
    }

    @Test public void disconnectDetachesTheWaiterWithoutCancellingOrRepeatingTheTool() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage stage = f.stage("disconnect"); p.call(92, stage); stage.awaitEntered();
            ActiveToolCall active = f.server.getActiveToolCall(); p.close();
            assertNotNull("EOF must detach a registered call", active); f.awaitWaiter(active);
            assertNull(f.server.getActiveToolCall()); assertFalse(stage.interrupted.get());
            assertEquals(1, stage.finished.getCount()); assertFalse(f.signal(UserSignal.SignalType.CANCEL));
            stage.release.countDown(); assertTrue(stage.finished.await(3, TimeUnit.SECONDS));
            p.assertIds(); assertEquals(1, stage.invocations.get());
            try (Peer next = f.peer())
            {
                Stage other = f.stage("reconnected"); other.release.countDown(); next.call(92, other);
                assertNormal(next.await(92), other); next.barrier(); next.assertIds(92, "barrier");
                assertEquals(1, stage.invocations.get());
            }
        }
    }

    @Test public void twoConnectionsWithTheSameIdKeepTheNewestCallInterruptible() throws Exception
    {
        try (Fixture f = new Fixture(); Peer first = f.peer(); Peer second = f.peer())
        {
            Stage a = f.stage("older"); first.call(93, a); a.awaitEntered();
            ActiveToolCall older = f.server.getActiveToolCall();
            Stage b = f.stage("newer"); second.call(93, b); b.awaitEntered();
            ActiveToolCall newer = f.server.getActiveToolCall(); assertNotNull(newer);
            a.release.countDown(); assertNormal(first.await(93), a); f.awaitWaiter(older);
            assertSame(newer, f.server.getActiveToolCall()); assertTrue(f.signal(UserSignal.SignalType.BACKGROUND));
            assertSignal(second.await(93)); b.release.countDown(); f.awaitWaiter(newer);
            first.barrier(); second.barrier(); first.assertIds(93, "barrier"); second.assertIds(93, "barrier");
        }
    }

    @Test public void newerCompletionDoesNotRestoreTheOlderInterruptTarget() throws Exception
    {
        try (Fixture f = new Fixture(); Peer first = f.peer(); Peer second = f.peer())
        {
            Stage a = f.stage("older"); first.call(94, a); a.awaitEntered();
            Stage b = f.stage("newer"); second.call(95, b); b.awaitEntered();
            assertNotNull(f.server.getActiveToolCall());
            b.release.countDown(); assertNormal(second.await(95), b);
            assertNull(f.server.getActiveToolCall()); assertFalse(f.signal(UserSignal.SignalType.CANCEL));
            a.release.countDown(); assertNormal(first.await(94), a);
            first.barrier(); second.barrier(); first.assertIds(94, "barrier"); second.assertIds(95, "barrier");
        }
    }

    @Test public void olderDisconnectKeepsTheNewerConnectionsInterruptTarget() throws Exception
    {
        try (Fixture f = new Fixture(); Peer first = f.peer(); Peer second = f.peer())
        {
            Stage a = f.stage("disconnected-older"); first.call(110, a); a.awaitEntered();
            ActiveToolCall older = f.server.getActiveToolCall();
            Stage b = f.stage("connected-newer"); second.call(111, b); b.awaitEntered();
            ActiveToolCall newer = f.server.getActiveToolCall(); assertNotNull(newer);
            first.close(); f.awaitWaiter(older);
            assertSame(newer, f.server.getActiveToolCall()); assertTrue(f.signal(UserSignal.SignalType.CANCEL));
            assertSignal(second.await(111)); a.release.countDown(); b.release.countDown(); f.awaitWaiter(newer);
            second.barrier(); first.assertIds(); second.assertIds(111, "barrier");
            assertFalse(a.interrupted.get()); assertFalse(b.interrupted.get());
        }
    }

    @Test public void overlappingRequestsOnOneConnectionMatchTheirOwnIds() throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage a = f.stage("slow"); p.call(96, a); a.awaitEntered();
            Stage b = f.stage("fast"); p.call("string-request", b); b.awaitEntered();
            b.release.countDown(); assertNormal(p.await("string-request"), b);
            a.release.countDown(); assertNormal(p.await(96), a); p.barrier(); p.assertIds(96, "string-request", "barrier");
        }
    }

    @Test public void olderHttpCompletionKeepsTheNewerIpcCallInterruptible() throws Exception
    { assertMixedOverlap(true); }

    @Test public void olderIpcCompletionKeepsTheNewerHttpCallInterruptible() throws Exception
    { assertMixedOverlap(false); }

    private void assertMixedOverlap(boolean httpFirst) throws Exception
    {
        try (Fixture f = new Fixture(); Peer p = f.peer())
        {
            Stage a = f.stage("http"); Stage b = f.stage("ipc");
            HttpCall http = new HttpCall(f.server, a, 97);
            if (httpFirst) { http.start(); a.awaitEntered(); p.call(98, b); b.awaitEntered(); }
            else { p.call(98, b); b.awaitEntered(); http.start(); a.awaitEntered(); }
            ActiveToolCall newest = f.server.getActiveToolCall(); assertNotNull(newest);
            if (httpFirst) { a.release.countDown(); http.await(); assertNormal(http.response(), a); }
            else { b.release.countDown(); assertNormal(p.await(98), b); }
            assertSame(newest, f.server.getActiveToolCall()); assertTrue(f.signal(UserSignal.SignalType.CANCEL));
            if (httpFirst) { assertSignal(p.await(98)); b.release.countDown(); f.awaitWaiter(newest); }
            else { http.await(); assertSignal(http.response()); a.release.countDown(); }
            p.barrier(); p.assertIds(98, "barrier");
            assertEquals(1, http.headersSent.get()); assertEquals(97, http.response().get("id").getAsInt());
        }
    }

    private static Thread race(CyclicBarrier start, AtomicReference<Throwable> failure, Runnable action)
    {
        Thread thread = new Thread(() -> {
            try { start.await(3, TimeUnit.SECONDS); action.run(); }
            catch (Throwable e) { failure.set(e); }
        }, "test-ipc-race"); thread.setDaemon(true); thread.start(); return thread;
    }

    private static void assertSignal(JsonObject response)
    { assertTrue(response.getAsJsonObject("result").get("isError").getAsBoolean()); }

    private static void assertNormal(JsonObject response, Stage stage)
    {
        JsonObject result = response.getAsJsonObject("result");
        assertFalse(result.has("isError") && result.get("isError").getAsBoolean());
        assertEquals("finished:" + stage.key, result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    private static String request(Object id, Stage stage)
    {
        return GsonProvider.toJson(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
            "params", Map.of("name", TOOL, "arguments", Map.of("key", stage.key))));
    }

    private static final class Stage
    {
        final String key; final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean(); final AtomicInteger invocations = new AtomicInteger();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        Stage(String key) { this.key = key; }
        void awaitEntered() throws Exception { assertTrue("Tool did not enter: " + key, entered.await(3, TimeUnit.SECONDS)); }
        String execute()
        {
            worker.set(Thread.currentThread()); invocations.incrementAndGet(); entered.countDown();
            try { assertTrue("Tool was not released", release.await(10, TimeUnit.SECONDS)); return "finished:" + key; }
            catch (InterruptedException e) { interrupted.set(true); Thread.currentThread().interrupt(); throw new AssertionError(e); }
            finally { finished.countDown(); }
        }
    }

    private static final class TrackingServer extends McpServer
    {
        final Map<ActiveToolCall, Thread> waiters = new ConcurrentHashMap<>();
        @Override public void setActiveToolCall(ActiveToolCall call)
        { waiters.put(call, Thread.currentThread()); super.setActiveToolCall(call); }
    }

    private final class Fixture implements AutoCloseable
    {
        final TrackingServer server = new TrackingServer(); final McpToolRegistry tools = McpToolRegistry.getInstance();
        final Collection<IMcpTool> saved = tools.getAllTools(); final Map<String, Stage> stages = new ConcurrentHashMap<>();
        final McpCallHistory history = McpCallHistory.getInstance(); final boolean recording = history.isRecordingEnabled();
        final IpcListener listener; final EndpointRegistry.Endpoint endpoint;
        Fixture() throws Exception
        {
            history.clear(); history.setRecordingEnabled(true);
            tools.replaceAll(List.of(new IMcpTool() {
                public String getName() { return TOOL; }
                public String getDescription() { return "Interrupt test"; }
                public String getInputSchema() { return "{\"type\":\"object\",\"properties\":{\"key\":{\"type\":\"string\"}}}"; }
                public ResponseType getResponseType() { return ResponseType.TEXT; }
                public String execute(Map<String, String> args) { return stages.get(args.get("key")).execute(); }
            }));
            EndpointRegistry registry = registry(); listener = new IpcListener(registry, "test", null, server); endpoint = registry.read();
        }
        Stage stage(String key) { Stage value = new Stage(key); stages.put(key, value); return value; }
        Peer peer() throws Exception { return new Peer(endpoint); }
        boolean signal(UserSignal.SignalType type) { return server.interruptToolCall(new UserSignal(type, "Return control")); }
        void awaitWaiter(ActiveToolCall call) throws Exception
        {
            assertNotNull(call); Thread waiter = server.waiters.get(call); assertNotNull(waiter);
            waiter.join(3000); assertFalse("Tool waiter must return", waiter.isAlive());
        }
        @Override public void close() throws Exception
        {
            stages.values().forEach(s -> s.release.countDown());
            try
            {
                listener.close();
                for (Stage stage : stages.values())
                {
                    if (stage.worker.get() != null) { stage.worker.get().join(3000); assertFalse(stage.worker.get().isAlive()); }
                }
                for (Thread waiter : server.waiters.values()) { waiter.join(3000); assertFalse(waiter.isAlive()); }
            }
            finally { tools.replaceAll(saved); history.clear(); history.setRecordingEnabled(recording); }
        }
    }

    private static final class Peer implements AutoCloseable
    {
        final JsonFrames.Socket socket = new JsonFrames.Socket(SocketChannel.open(StandardProtocolFamily.UNIX));
        final List<JsonObject> frames = new CopyOnWriteArrayList<>(); final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread reader; volatile boolean closing;
        Peer(EndpointRegistry.Endpoint endpoint) throws Exception
        {
            socket.connect(UnixDomainSocketAddress.of(endpoint.socket())); IpcHandshake.client(socket, endpoint);
            reader = new Thread(() -> {
                try
                {
                    String line;
                    while ((line = socket.read()) != null)
                    {
                        JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
                        assertEquals("2.0", frame.get("jsonrpc").getAsString()); assertTrue(frame.has("id"));
                        frames.add(frame);
                    }
                }
                catch (Throwable e) { if (!closing) { failure.set(e); } }
            }, "test-ipc-reader"); reader.setDaemon(true); reader.start();
            socket.write(init(0, false)); await(0); frames.clear(); socket.write(initialized());
        }
        void call(Object id, Stage stage) throws Exception { socket.write(request(id, stage)); }
        JsonObject await(Object id) throws Exception
        {
            JsonElement value = GsonProvider.get().toJsonTree(id); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline)
            {
                assertNull("Malformed or broken frame", failure.get());
                for (JsonObject frame : frames) { if (value.equals(frame.get("id"))) { return frame; } }
                Thread.sleep(5);
            }
            throw new AssertionError("No response for id=" + id + "; received=" + frames);
        }
        void barrier() throws Exception
        { socket.write("{\"jsonrpc\":\"2.0\",\"id\":\"barrier\",\"method\":\"ping\"}"); await("barrier"); }
        void assertIds(Object... ids)
        {
            assertNull(failure.get()); assertEquals(ids.length, frames.size());
            assertEquals(Arrays.stream(ids).map(GsonProvider::toJson)
                .collect(Collectors.groupingBy(v -> v, Collectors.counting())),
                frames.stream().map(frame -> frame.get("id").toString()).collect(Collectors.groupingBy(v -> v, Collectors.counting())));
        }
        @Override public void close() throws Exception
        { closing = true; socket.close(); reader.join(3000); assertFalse(reader.isAlive()); }
    }

    private static final class HttpCall
    {
        final HttpExchange exchange = mock(HttpExchange.class); final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final AtomicInteger headersSent = new AtomicInteger(); final AtomicReference<Throwable> failure = new AtomicReference<>(); final Thread request;
        HttpCall(McpServer server, Stage stage, int id) throws Exception
        {
            McpProtocolHandler protocol = new McpProtocolHandler();
            McpHttpHandler http = new McpHttpHandler(server, protocol, new InterruptibleToolExecutor(server, protocol), false);
            Headers headers = new Headers(); headers.add(McpConstants.HEADER_SESSION_ID, http.getSessions().create(ClientCapabilities.ABSENT));
            when(exchange.getRequestMethod()).thenReturn("POST"); when(exchange.getRequestHeaders()).thenReturn(headers);
            when(exchange.getResponseHeaders()).thenReturn(new Headers()); when(exchange.getResponseBody()).thenReturn(output);
            when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(request(id, stage).getBytes(StandardCharsets.UTF_8)));
            doAnswer(invocation -> { assertEquals(200, (int)invocation.getArgument(0)); headersSent.incrementAndGet(); return null; })
                .when(exchange).sendResponseHeaders(anyInt(), anyLong());
            request = new Thread(() -> { try { http.handle(exchange); } catch (Throwable e) { failure.set(e); } }, "test-http-overlap");
            request.setDaemon(true);
        }
        void start() { request.start(); }
        void await() throws Exception { request.join(3000); assertFalse(request.isAlive()); assertNull(failure.get()); }
        JsonObject response() { return JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject(); }
    }
}
