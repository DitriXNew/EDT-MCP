package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.ipc.*;
import com.google.gson.*;

abstract class StdioTestSupport
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Before public void secureTemporaryDirectory() throws Exception
    { PrivatePaths.directory(temporary.getRoot().toPath()); }
    protected EndpointRegistry registry() throws Exception
    { return new EndpointRegistry(PrivatePaths.directory(temporary.newFolder().toPath()), temporary.newFolder().toPath()); }
    protected EndpointRegistry.Endpoint endpoint(EndpointRegistry registry, String generation, long pid)
    {
        return new EndpointRegistry.Endpoint(registry.workspace().toString(), generation, pid,
            ProcessHandle.current().info().startInstant().orElseThrow().toString(), "1.0.0", 1,
            registry.directory().resolve("socket").toString(), "private-secret");
    }

    protected static void awaitOutput(ByteArrayOutputStream output, String text) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline)
        { if (output.toString(StandardCharsets.UTF_8).contains(text)) { return; } Thread.sleep(10); }
        fail("Missing output: " + text + " in " + output.toString(StandardCharsets.UTF_8));
    }

    protected static void assertRefusal(ByteArrayOutputStream output, int id, String text) throws Exception
    {
        var frames = new ByteArrayInputStream(output.toByteArray()); String frame;
        while ((frame = JsonFrames.read(frames)) != null)
        {
            var response = JsonParser.parseString(frame).getAsJsonObject();
            if (!response.has("id") || response.get("id").getAsInt() != id) { continue; }
            var result = response.getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            var payload = JsonParser.parseString(result.getAsJsonArray("content").get(0)
                .getAsJsonObject().get("text").getAsString()).getAsJsonObject();
            assertFalse(payload.get("success").getAsBoolean());
            assertEquals(text, payload.get("error").getAsString()); return;
        }
        fail("Missing response for " + id);
    }

    protected static JsonObject catalog()
    {
        return JsonParser.parseString("{\"versions\":[\"2025-11-25\"],\"initialize\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{\"tools\":{\"listChanged\":true}},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}},\"tools\":{\"tools\":[{\"name\":\"read\",\"inputSchema\":{\"type\":\"object\"}}]}}").getAsJsonObject();
    }
    protected static String init(int id, boolean optOut)
    { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"initialize\",\"params\":{\"capabilities\":{\"experimental\":{\"structuredContent\":" + !optOut + "}}}}"; }
    protected static String initialized() { return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"; }
    protected static String call(int id, String name)
    { return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}"; }
}
