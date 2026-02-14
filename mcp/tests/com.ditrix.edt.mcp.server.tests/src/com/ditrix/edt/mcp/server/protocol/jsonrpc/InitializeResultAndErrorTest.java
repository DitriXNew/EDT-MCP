/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link InitializeResult} and {@link JsonRpcError} DTOs.
 */
public class InitializeResultAndErrorTest
{
    // ========== InitializeResult ==========

    @Test
    public void testInitializeResultFields()
    {
        InitializeResult result = new InitializeResult("2025-11-25", "EDT MCP", "1.0.0", "DitriX");
        assertEquals("2025-11-25", result.getProtocolVersion());
        assertNotNull(result.getCapabilities());
        assertNotNull(result.getServerInfo());
    }

    @Test
    public void testInitializeResultServerInfo()
    {
        InitializeResult result = new InitializeResult("2025-11-25", "TestServer", "2.0.0", "Author");
        InitializeResult.ServerInfo info = result.getServerInfo();
        assertEquals("TestServer", info.getName());
        assertEquals("2.0.0", info.getVersion());
        assertEquals("Author", info.getAuthor());
    }

    @Test
    public void testInitializeResultCapabilities()
    {
        InitializeResult result = new InitializeResult("v1", "s", "1.0", "a");
        assertNotNull(result.getCapabilities().getTools());
    }

    @Test
    public void testInitializeResultSerialization()
    {
        InitializeResult result = new InitializeResult(
            McpConstants.PROTOCOL_VERSION, "Test", "1.0", "Author");
        String json = GsonProvider.toJson(result);
        assertNotNull(json);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(McpConstants.PROTOCOL_VERSION,
            parsed.get("protocolVersion").getAsString());
        assertNotNull(parsed.get("capabilities"));
        assertNotNull(parsed.get("serverInfo"));

        JsonObject info = parsed.getAsJsonObject("serverInfo");
        assertEquals("Test", info.get("name").getAsString());
        assertEquals("1.0", info.get("version").getAsString());
        assertEquals("Author", info.get("author").getAsString());
    }

    @Test
    public void testInitializeResultWrappedInResponse()
    {
        InitializeResult result = new InitializeResult("2025-11-25", "S", "1.0", "A");
        JsonRpcResponse response = JsonRpcResponse.success(1, result);
        String json = GsonProvider.toJson(response);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("2.0", parsed.get("jsonrpc").getAsString());
        assertNotNull(parsed.get("result"));
    }

    // ========== JsonRpcError ==========

    @Test
    public void testJsonRpcErrorConstructor()
    {
        JsonRpcError error = new JsonRpcError(-32600, "Invalid Request");
        assertEquals(-32600, error.getCode());
        assertEquals("Invalid Request", error.getMessage());
    }

    @Test
    public void testJsonRpcErrorSerialization()
    {
        JsonRpcError error = new JsonRpcError(-32601, "Method not found");
        String json = GsonProvider.toJson(error);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(-32601, parsed.get("code").getAsInt());
        assertEquals("Method not found", parsed.get("message").getAsString());
    }

    @Test
    public void testJsonRpcErrorInResponse()
    {
        JsonRpcResponse response = JsonRpcResponse.error(1, -32603, "Internal error");
        String json = GsonProvider.toJson(response);

        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertNull("Error response should not have result", parsed.get("result"));
        assertNotNull(parsed.get("error"));
        assertEquals(-32603, parsed.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    public void testJsonRpcErrorCodes()
    {
        // Verify standard JSON-RPC error codes are negative
        assertTrue(McpConstants.ERROR_PARSE < 0);
        assertTrue(McpConstants.ERROR_INVALID_REQUEST < 0);
        assertTrue(McpConstants.ERROR_METHOD_NOT_FOUND < 0);
        assertTrue(McpConstants.ERROR_INVALID_PARAMS < 0);
        assertTrue(McpConstants.ERROR_INTERNAL < 0);
    }
}
