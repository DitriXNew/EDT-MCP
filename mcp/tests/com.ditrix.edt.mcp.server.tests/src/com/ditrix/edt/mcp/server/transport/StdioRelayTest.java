package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import com.ditrix.edt.mcp.server.ipc.*;
import com.ditrix.edt.mcp.server.tools.*;
import com.google.gson.*;

public class StdioRelayTest extends StdioTestSupport
{
    @Test public void bootstrapRelaysAndNeverReplaysADroppedWrite() throws Exception
    {
        var tools = McpToolRegistry.getInstance(); var saved = tools.getAllTools();
        AtomicInteger writes = new AtomicInteger(), starts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch reconnect = new CountDownLatch(1); AtomicInteger attempts = new AtomicInteger();
        IMcpTool tool = new IMcpTool() {
            public String getName() { return "test_write"; }
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
        catalog.getAsJsonObject("tools").getAsJsonArray("tools").get(0).getAsJsonObject().addProperty("name", "test_write");
        try
        {
            tools.replaceAll(List.of(tool));
            try (var listener = new IpcListener(registry, "test");
                 var bootstrap = new StdioBootstrap(() -> {
                     if (attempts.incrementAndGet() == 2)
                     {
                         try { assertTrue(reconnect.await(5, TimeUnit.SECONDS)); }
                         catch (InterruptedException e) { throw new IOException(e); }
                     }
                     return registry;
                 }, () -> { starts.incrementAndGet(); return null; }, output, catalog))
            {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized());
                bootstrap.accept(call(2, "test_write"));
                awaitOutput(output, "notifications/tools/list_changed");
                assertTrue("The first warm call must execute without a retry", entered.await(3, TimeUnit.SECONDS));
                assertFalse(output.toString(StandardCharsets.UTF_8).contains("EDT is connecting"));
                listener.close();
                awaitOutput(output, "Outcome is unknown");
                assertRefusal(output, 2, "EDT disconnected during the request. Outcome is unknown; a write may have committed. "
                    + "Read back the state before deciding whether to retry. The bootstrap has not replayed the call.");
                var metadata = Files.createDirectories(registry.workspace().resolve(".metadata"));
                try (var channel = FileChannel.open(metadata.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     var lock = channel.lock())
                {
                    bootstrap.accept(call(3, "test_write"));
                    assertRefusal(output, 3, "EDT disconnected. Retry a read to reconnect. This call has not executed.");
                    reconnect.countDown(); bootstrap.accept(call(4, "test_write"));
                    assertRefusal(output, 4, "This workspace is already open, but its EDT MCP IPC listener is unavailable. "
                        + "Ask the user to inspect Window > Preferences > MCP Server. This call has not executed.");
                }
                release.countDown();
                assertEquals(1, writes.get()); assertEquals("Attach does not start a second EDT", 0, starts.get());
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("has not replayed"));
            }
        }
        finally { reconnect.countDown(); release.countDown(); tools.replaceAll(saved); }
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
            try (var listener = new IpcListener(registry, "test");
                 var bootstrap = new StdioBootstrap(registry, () -> { fail("Should attach"); return null; }, output, catalog()))
            {
                bootstrap.accept(init(1, false)); bootstrap.accept(initialized()); bootstrap.accept(call(2, "read"));
                awaitOutput(output, "notifications/tools/list_changed");
                awaitOutput(output, "Справочник.Ёж");
                var frames = new ByteArrayInputStream(output.toByteArray());
                JsonObject first = null;
                String frame;
                while ((frame = JsonFrames.read(frames)) != null)
                {
                    var response = JsonParser.parseString(frame).getAsJsonObject();
                    if (response.has("id") && response.get("id").getAsInt() == 2) { first = response; }
                }
                assertNotNull("The first warm call must return its real result", first);
                assertFalse(first.getAsJsonObject("result").has("isError"));
                bootstrap.accept(call(3, "read").replace("\"id\":3", "\"id\":9007199254740993"));
                awaitOutput(output, "9007199254740993");
                bootstrap.accept(call(4, "read").replace("\"id\":4", "\"id\":\"client-Ё\""));
                awaitOutput(output, "client-Ё");
                assertTrue(output.toString(StandardCharsets.UTF_8).contains("Справочник.Ёж"));
            }
        }
        finally { tools.replaceAll(saved); }
    }
}
