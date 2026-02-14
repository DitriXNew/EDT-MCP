/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;

/**
 * Tests for JSON-RPC DTOs: {@link JsonRpcRequest}, {@link JsonRpcResponse}, {@link JsonRpcError}.
 */
public class JsonRpcDtoTest
{
    // --- JsonRpcRequest ---

    @Test
    public void testRequestDeserialization()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"tools/list\"}";
        JsonRpcRequest request = GsonProvider.fromJson(json, JsonRpcRequest.class);

        assertNotNull(request);
        assertEquals("2.0", request.getJsonrpc());
        assertEquals("tools/list", request.getMethod());
    }

    @Test
    public void testRequestWithParams()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"get_edt_version\",\"arguments\":{\"projectName\":\"TestProject\"}}}";
        JsonRpcRequest request = GsonProvider.fromJson(json, JsonRpcRequest.class);

        assertEquals("tools/call", request.getMethod());
        assertEquals("get_edt_version", request.getToolName());

        Map<String, Object> args = request.getArguments();
        assertNotNull(args);
        assertEquals("TestProject", args.get("projectName"));
    }

    @Test
    public void testRequestNoParams()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        JsonRpcRequest request = GsonProvider.fromJson(json, JsonRpcRequest.class);

        assertNull(request.getParams());
        assertNull(request.getToolName());
        assertNull(request.getArguments());
    }

    @Test
    public void testGetStringParam()
    {
        JsonRpcRequest request = new JsonRpcRequest();
        assertNull(request.getStringParam("foo"));

        Map<String, Object> params = new HashMap<>();
        params.put("name", "test_tool");
        request.setParams(params);
        assertEquals("test_tool", request.getStringParam("name"));
        assertNull(request.getStringParam("missing"));
    }

    // --- JsonRpcResponse ---

    @Test
    public void testSuccessResponse()
    {
        JsonRpcResponse response = JsonRpcResponse.success(1, "ok");
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(1, response.getId());
        assertEquals("ok", response.getResult());
        assertNull(response.getError());
    }

    @Test
    public void testErrorResponse()
    {
        JsonRpcResponse response = JsonRpcResponse.error(2, -32600, "Invalid request");
        assertEquals("2.0", response.getJsonrpc());
        assertEquals(2, response.getId());
        assertNull(response.getResult());
        assertNotNull(response.getError());
        assertEquals(-32600, response.getError().getCode());
        assertEquals("Invalid request", response.getError().getMessage());
    }

    @Test
    public void testSuccessResponseSerialization()
    {
        JsonRpcResponse response = JsonRpcResponse.success(1, "result_data");
        String json = GsonProvider.toJson(response);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"result\":\"result_data\""));
        assertFalse(json.contains("\"error\""));
    }

    @Test
    public void testErrorResponseSerialization()
    {
        JsonRpcResponse response = JsonRpcResponse.error(1, -32601, "Method not found");
        String json = GsonProvider.toJson(response);

        assertTrue(json.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(json.contains("\"code\":-32601"));
        assertTrue(json.contains("\"message\":\"Method not found\""));
    }
}
