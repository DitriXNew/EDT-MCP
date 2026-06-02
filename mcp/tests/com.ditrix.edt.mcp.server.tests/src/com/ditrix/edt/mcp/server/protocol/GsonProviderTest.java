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

    /**
     * MCP output is JSON over MCP, not HTML, so Gson HTML escaping must be disabled:
     * apostrophes (1C messages like {@code Field 'X' not found}) and the operators
     * {@code >=}, {@code &}, {@code <} must appear raw, never as {@code \\uXXXX}.
     */
    @Test
    public void testToJsonDoesNotHtmlEscape()
    {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("message", "Field 'X' not found, count >= 5 & more <tag>");
        String json = GsonProvider.toJson(map);

        // Raw characters survive serialization.
        assertTrue(json.contains("'"));
        assertTrue(json.contains(">="));
        assertTrue(json.contains("&"));
        assertTrue(json.contains("<"));

        // None of them are HTML-escaped into \\uXXXX form.
        assertFalse(json.contains("\\u0027")); // apostrophe
        assertFalse(json.contains("\\u003e")); // >
        assertFalse(json.contains("\\u003c")); // <
        assertFalse(json.contains("\\u0026")); // &
    }

    /**
     * The serialize-nulls variant shares the same no-HTML-escaping contract.
     */
    @Test
    public void testToJsonSerializeNullsDoesNotHtmlEscape()
    {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("message", "Field 'X' not found, count >= 5 & more <tag>");
        String json = GsonProvider.toJsonSerializeNulls(map);

        assertTrue(json.contains("'"));
        assertTrue(json.contains(">="));
        assertTrue(json.contains("&"));
        assertTrue(json.contains("<"));

        assertFalse(json.contains("\\u0027"));
        assertFalse(json.contains("\\u003e"));
        assertFalse(json.contains("\\u003c"));
        assertFalse(json.contains("\\u0026"));
    }
}
