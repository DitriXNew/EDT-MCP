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
