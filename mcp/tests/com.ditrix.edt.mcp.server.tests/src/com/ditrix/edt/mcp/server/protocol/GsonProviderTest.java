/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link GsonProvider}.
 */
public class GsonProviderTest
{
    @Test
    public void testToJsonPrimitive()
    {
        assertEquals("\"hello\"", GsonProvider.toJson("hello"));
    }

    @Test
    public void testToJsonObject()
    {
        var map = new java.util.HashMap<String, Object>();
        map.put("key", "value");
        String json = GsonProvider.toJson(map);
        assertTrue(json.contains("\"key\":\"value\""));
    }

    @Test
    public void testFromJsonObject()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        var request = GsonProvider.fromJson(json,
            com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest.class);
        assertNotNull(request);
        assertEquals("2.0", request.getJsonrpc());
        assertEquals("initialize", request.getMethod());
    }

    @Test
    public void testGetReturnsSameInstance()
    {
        assertSame(GsonProvider.get(), GsonProvider.get());
    }

    @Test
    public void testIntegerIdPreservedAsLong()
    {
        // "id":0 must not become "id":0.0 - Gson by default would use Double for Object
        String json = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\"}";
        var request = GsonProvider.fromJson(json,
            com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest.class);
        assertNotNull(request);
        // id must be a Long, not a Double, so it serializes as 0 not 0.0
        assertTrue("id should be a Long", request.getId() instanceof Long);
        assertEquals(0L, request.getId());
    }

    @Test
    public void testIntegerIdRoundTrip()
    {
        // Verify integer id round-trips without decimal point
        String json = "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\"}";
        var request = GsonProvider.fromJson(json,
            com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest.class);
        var response = com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcResponse.success(
            request.getId(), "ok");
        String responseJson = GsonProvider.toJson(response);
        // Must be "id":0 not "id":0.0
        assertTrue("Response id must be integer 0, not float 0.0",
            responseJson.contains("\"id\":0") && !responseJson.contains("\"id\":0.0"));
    }
}
