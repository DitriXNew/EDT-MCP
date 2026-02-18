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
}
