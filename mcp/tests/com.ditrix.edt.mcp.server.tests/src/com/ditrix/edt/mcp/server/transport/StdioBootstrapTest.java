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
            var frames = new ByteArrayInputStream(output.toByteArray());
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            assertNotNull(JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject().get("result"));
            var result = JsonParser.parseString(JsonFrames.read(frames)).getAsJsonObject();
            assertEquals(3, result.get("id").getAsInt()); assertTrue(result.getAsJsonObject("result").get("isError").getAsBoolean());
            assertTrue(result.toString().contains("EDT is starting. Retry the same call in about 15 seconds. No tool ran."));
        }
        finally { release.countDown(); }
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
