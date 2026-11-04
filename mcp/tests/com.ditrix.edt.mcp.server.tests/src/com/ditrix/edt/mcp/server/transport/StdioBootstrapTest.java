package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.*;

public class StdioBootstrapTest extends StdioTestSupport
{
    private static final String OCCUPIED = "This workspace is already open, but its EDT MCP IPC listener is unavailable. "
        + "Ask the user to inspect Window > Preferences > MCP Server. This call has not executed.";
    private static final String BLOCKING_DIALOG = "EDT is running but IPC is not ready. "
        + "Ask the user to inspect the EDT window for a blocking dialog.";

    @Test public void occupiedWorkspaceRefusesFirstCallAndRetry() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        var metadata = Files.createDirectories(registry.workspace().resolve(".metadata"));
        try (var channel = FileChannel.open(metadata.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock();
             var bootstrap = new StdioBootstrap(registry, () -> { fail("Occupied workspace must not launch"); return null; }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            bootstrap.accept(call(2, "read")); assertRefusal(output, 2, OCCUPIED);
            bootstrap.accept(call(3, "read")); assertRefusal(output, 3, OCCUPIED);
            assertFalse(Files.exists(registry.claimPath()));
        }
    }

    @Test public void liveExpiredClaimReportsBlockingDialogOnFirstCallAndRetry() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        var process = new StartupCoordinator.Started(ProcessHandle.current().pid(),
            ProcessHandle.current().info().startInstant().orElseThrow().toString());
        EndpointRegistry.atomicWrite(registry.claimPath(), new Gson().toJson(
            Map.of("requested", "2000-01-01T00:00:00Z", "process", process)));
        try (var bootstrap = new StdioBootstrap(registry, () -> { fail("Live claim must not launch"); return null; }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            bootstrap.accept(call(2, "read")); assertRefusal(output, 2, BLOCKING_DIALOG);
            bootstrap.accept(call(3, "read")); assertRefusal(output, 3, BLOCKING_DIALOG);
        }
    }

    @Test public void endpointPublishedDuringPreparationReportsConnecting() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        var starter = new StartupCoordinator.Starter() {
            private boolean published;
            public StartupCoordinator.Started start() { fail("Live endpoint must not launch"); return null; }
            public boolean supportsStartup()
            {
                try
                {
                    if (!published) { registry.publish(endpoint(registry, "ready", ProcessHandle.current().pid())); published = true; }
                }
                catch (IOException e) { throw new UncheckedIOException(e); }
                return true;
            }
        };
        try (var bootstrap = new StdioBootstrap(registry, starter, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "read"));
            assertRefusal(output, 2, "EDT is connecting. Retry the same call in about 2 seconds. No tool ran.");
        }
    }

    @Test public void failedLaunchRemainsActionableAndNeverLaunchesAgainEvenAfterClaimExpires() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        AtomicInteger starts = new AtomicInteger(), opens = new AtomicInteger();
        CountDownLatch thrown = new CountDownLatch(1);
        try (var bootstrap = new StdioBootstrap(() -> { opens.incrementAndGet(); return registry; }, () -> {
            starts.incrementAndGet(); thrown.countDown(); throw new IOException("Launch provider refused EDT.");
        }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "read"));
            assertTrue(thrown.await(3, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); int id = 3;
            while (opens.get() < 2 && System.nanoTime() < deadline) { bootstrap.accept(call(id++, "read")); Thread.yield(); }
            assertTrue("A later attach attempt must finish after the throwing attempt", opens.get() >= 2);
            String failure = "EDT connection/start failed: Launch provider refused EDT. "
                + "Ask the user to check the EDT window, then retry the same call. No tool ran.";
            bootstrap.accept(call(id, "read")); assertRefusal(output, id++, failure);
            assertTrue("The uncertain launch claim must be kept", Files.exists(registry.claimPath()));
            EndpointRegistry.atomicWrite(registry.claimPath(), "{\"requested\":\"2000-01-01T00:00:00Z\"}");
            bootstrap.accept(call(id, "read")); assertRefusal(output, id, failure);
            assertEquals(1, starts.get());
        }
    }

    @Test public void concurrentCallsShareTheOccupiedVerdict() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        var metadata = Files.createDirectories(registry.workspace().resolve(".metadata"));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (var channel = FileChannel.open(metadata.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock();
             var bootstrap = new StdioBootstrap(() -> {
                 entered.countDown();
                 try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                 catch (InterruptedException e) { throw new IOException(e); }
                 return registry;
             }, () -> { fail("Occupied workspace must not launch"); return null; }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            Future<?> first = executor.submit(() -> { bootstrap.accept(call(2, "read")); return null; });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> other = executor.submit(() -> { second.countDown(); bootstrap.accept(call(3, "read")); return null; });
            assertTrue(second.await(3, TimeUnit.SECONDS)); release.countDown();
            first.get(3, TimeUnit.SECONDS); other.get(3, TimeUnit.SECONDS);
            assertRefusal(output, 2, OCCUPIED); assertRefusal(output, 3, OCCUPIED);
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void nonWindowsColdStartRefusesWithoutInvokingStarterOrCreatingClaim() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream();
        var starter = new StartupCoordinator.Starter() {
            public StartupCoordinator.Started start() { fail("Unsupported startup must not run"); return null; }
            public boolean supportsStartup() { return false; }
        };
        try (var bootstrap = new StdioBootstrap(registry, starter, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "read"));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("Ask the user to open this workspace in EDT"));
            assertFalse(Files.exists(registry.claimPath()));
        }
    }

    @Test public void stalledAttachIsBoundedAndNeverQueuesTheInvocation() throws Exception
    {
        var registry = new EndpointRegistry(temporary.getRoot().toPath(), temporary.newFolder().toPath());
        var endpoint = endpoint(registry, "stalled", ProcessHandle.current().pid());
        var output = new ByteArrayOutputStream();
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
             var bootstrap = new StdioBootstrap(registry, () -> { fail("A live endpoint must not start another EDT"); return null; }, output, catalog()))
        {
            server.bind(UnixDomainSocketAddress.of(endpoint.socket())); registry.publish(endpoint);
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            long start = System.nanoTime(); bootstrap.accept(call(2, "read"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3500);
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("No tool ran"));
            assertFalse(Files.exists(registry.claimPath()));
        }
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
            assertRefusal(output, 3, StartupCoordinator.STARTING);
            assertRefusal(output, 4, StartupCoordinator.STARTING);
            var frames = new ByteArrayInputStream(output.toByteArray());
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            var result = JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject();
            assertEquals(3, result.get("id").getAsInt()); assertTrue(result.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(result.toString().contains("EDT is starting. Retry the same call in about 15 seconds. No tool ran."));
        }
        finally { release.countDown(); }
    }

    @Test public void concurrentColdCallsAnswerBeforeTheStarterReturns() throws Exception
    {
        var registry = registry(); var output = new ByteArrayOutputStream(); AtomicInteger starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (var bootstrap = new StdioBootstrap(registry, () -> {
            starts.incrementAndGet(); entered.countDown(); release.await(); return null;
        }, output, catalog()))
        {
            bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
            Future<?> first = executor.submit(() -> { bootstrap.accept(call(2, "read")); return null; });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Future<?> other = executor.submit(() -> { bootstrap.accept(call(3, "read")); return null; });
            first.get(1, TimeUnit.SECONDS); other.get(1, TimeUnit.SECONDS);
            assertEquals(1, release.getCount()); assertEquals(1, starts.get());
            assertRefusal(output, 2, StartupCoordinator.STARTING); assertRefusal(output, 3, StartupCoordinator.STARTING);
        }
        finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test public void explicitPathsNeverGuessCwd() throws Exception
    {
        assertThrows(IOException.class, () -> StdioBootstrap.explicitPath(new String[0], "-data"));
        assertThrows(IOException.class, () -> StdioBootstrap.explicitPath(new String[]{"-data", "."}, "-data"));
        assertEquals(temporary.getRoot().toPath(), StdioBootstrap.explicitPath(new String[]{"-data", temporary.getRoot().toString()}, "-data"));
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
}
