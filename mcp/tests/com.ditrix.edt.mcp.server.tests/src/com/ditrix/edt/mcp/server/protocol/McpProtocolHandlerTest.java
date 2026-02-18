/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link McpProtocolHandler}.
 * Verifies JSON-RPC protocol handling for initialize, tools/list, and error cases.
 * <p>
 * Note: tools/call with successful execution cannot be fully tested without
 * OSGi runtime (Activator.getDefault() returns null), but error paths are testable.
 * </p>
 */
public class McpProtocolHandlerTest
{
    private McpProtocolHandler handler;
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        registry.clear();
        handler = new McpProtocolHandler();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    // === Initialize ===

    @Test
    public void testInitialize()
    {
        String request = buildJsonRpcRequest(1, "initialize", null);
        String response = handler.processRequest(request);

        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertEquals("2.0", json.get("jsonrpc").getAsString());
        assertNotNull(json.get("result"));

        JsonObject result = json.getAsJsonObject("result");
        assertNotNull("Should have protocolVersion", result.get("protocolVersion"));
        assertNotNull("Should have capabilities", result.get("capabilities"));
        assertNotNull("Should have serverInfo", result.get("serverInfo"));

        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertNotNull(serverInfo.get("name"));
        assertNotNull(serverInfo.get("version"));
    }

    @Test
    public void testInitializeEchosClientProtocolVersion()
    {
        // Per MCP spec: server must echo back client's requested protocol version
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"lmstudio\",\"version\":\"1.0.0\"}}}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String echoed = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("Server must echo back client's protocol version", "2025-06-18", echoed);
    }

    @Test
    public void testInitializeUsesOwnVersionWhenClientVersionMissing()
    {
        // When no protocolVersion in params, fall back to server's latest
        String request = buildJsonRpcRequest(1, "initialize", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        String version = json.getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals(McpConstants.PROTOCOL_VERSION, version);
    }

    @Test
    public void testInitializePreservesRequestId()
    {
        String request = buildJsonRpcRequest(42, "initialize", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertEquals(42, json.get("id").getAsInt());
    }

    @Test
    public void testInitializeWithIdZeroPreservesIntegerType()
    {
        // LM Studio sends "id":0 - must not become "id":0.0 in response
        String request = buildJsonRpcRequest(0, "initialize", null);
        String response = handler.processRequest(request);

        assertNotNull(response);
        assertFalse("Response must not contain 0.0 as id", response.contains("\"id\":0.0"));
        JsonObject json = parseResponse(response);
        assertEquals(0, json.get("id").getAsInt());
    }

    @Test
    public void testInitializeStringId()
    {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"abc-123\",\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertEquals("abc-123", json.get("id").getAsString());
    }

    // === Initialized notification ===

    @Test
    public void testInitializedNotification()
    {
        String request = buildJsonRpcRequest(1, "notifications/initialized", null);
        String response = handler.processRequest(request);
        assertNull("notifications/initialized should return null (202 Accepted)", response);
    }

    // === Tools/List ===

    @Test
    public void testToolsListEmpty()
    {
        String request = buildJsonRpcRequest(1, "tools/list", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        JsonObject result = json.getAsJsonObject("result");
        assertNotNull(result.get("tools"));
        assertEquals(0, result.getAsJsonArray("tools").size());
    }

    @Test
    public void testToolsListWithTools()
    {
        registry.register(new StubTool("tool_alpha", "Alpha tool", "{\"type\":\"object\"}"));
        registry.register(new StubTool("tool_beta", "Beta tool",
            "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"));

        String request = buildJsonRpcRequest(1, "tools/list", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        JsonObject result = json.getAsJsonObject("result");
        assertEquals(2, result.getAsJsonArray("tools").size());

        // Verify tool entries have required fields
        for (JsonElement toolEl : result.getAsJsonArray("tools"))
        {
            JsonObject tool = toolEl.getAsJsonObject();
            assertNotNull("Tool should have name", tool.get("name"));
            assertNotNull("Tool should have description", tool.get("description"));
            assertNotNull("Tool should have inputSchema", tool.get("inputSchema"));
        }
    }

    // === Invalid Requests ===

    @Test
    public void testInvalidJsonRpcVersion()
    {
        String request = "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertEquals(McpConstants.ERROR_INVALID_REQUEST,
            json.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void testMissingJsonRpcVersion()
    {
        String request = "{\"id\":1,\"method\":\"initialize\"}";
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    @Test
    public void testMethodNotFound()
    {
        String request = buildJsonRpcRequest(1, "unknown/method", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        assertEquals(McpConstants.ERROR_METHOD_NOT_FOUND,
            json.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void testInvalidJson()
    {
        String response = handler.processRequest("not valid json {{{");
        // Should return an error response (either parse error or invalid request)
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    @Test
    public void testEmptyBody()
    {
        String response = handler.processRequest("");
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    @Test
    public void testNullBody()
    {
        String response = handler.processRequest(null);
        assertNotNull(response);
        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
    }

    // === Tools/Call Error Cases ===

    @Test
    public void testToolCallToolNotFound()
    {
        String request = buildToolCallRequest(1, "nonexistent_tool", null);
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull(json.get("error"));
        String message = json.getAsJsonObject("error").get("message").getAsString();
        assertTrue("Error should mention tool name", message.contains("nonexistent_tool"));
    }

    @Test
    public void testToolCallNullToolName()
    {
        String request = buildJsonRpcRequest(1, "tools/call",
            "{\"arguments\":{}}");
        String response = handler.processRequest(request);

        JsonObject json = parseResponse(response);
        assertNotNull("Should return error for null tool name", json.get("error"));
    }

    // === Helpers ===

    private String buildJsonRpcRequest(Object id, String method, String paramsJson)
    {
        StringBuilder sb = new StringBuilder("{\"jsonrpc\":\"2.0\"");
        if (id instanceof String)
        {
            sb.append(",\"id\":\"").append(id).append("\"");
        }
        else
        {
            sb.append(",\"id\":").append(id);
        }
        sb.append(",\"method\":\"").append(method).append("\"");
        if (paramsJson != null)
        {
            sb.append(",\"params\":").append(paramsJson);
        }
        sb.append("}");
        return sb.toString();
    }

    private String buildToolCallRequest(Object id, String toolName, String argsJson)
    {
        StringBuilder params = new StringBuilder("{\"name\":\"").append(toolName).append("\"");
        if (argsJson != null)
        {
            params.append(",\"arguments\":").append(argsJson);
        }
        else
        {
            params.append(",\"arguments\":{}");
        }
        params.append("}");
        return buildJsonRpcRequest(id, "tools/call", params.toString());
    }

    private JsonObject parseResponse(String response)
    {
        return JsonParser.parseString(response).getAsJsonObject();
    }

    /**
     * Minimal IMcpTool stub for testing.
     */
    private static class StubTool implements IMcpTool
    {
        private final String name;
        private final String description;
        private final String inputSchema;

        StubTool(String name, String description, String inputSchema)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        @Override
        public String getName() { return name; }

        @Override
        public String getDescription() { return description; }

        @Override
        public String getInputSchema() { return inputSchema; }

        @Override
        public String execute(Map<String, String> params) { return "{}"; }
    }
}
