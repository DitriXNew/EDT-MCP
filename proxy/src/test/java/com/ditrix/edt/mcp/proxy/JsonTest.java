/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.google.gson.JsonObject;

/**
 * Unit tests for the {@link Json} helpers: lenient parsing, null-safe accessors and
 * compact serialization.
 */
public class JsonTest
{
    // ---- parseObject ----

    @Test
    public void testParseObjectValid()
    {
        JsonObject o = Json.parseObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"params\":{\"name\":\"x\"}}");

        assertNotNull(o);
        assertEquals("2.0", o.get("jsonrpc").getAsString());
        assertEquals(1, o.get("id").getAsInt());
    }

    @Test
    public void testParseObjectNullInput()
    {
        assertNull(Json.parseObject(null));
    }

    @Test
    public void testParseObjectBlankInput()
    {
        assertNull(Json.parseObject(""));
        assertNull(Json.parseObject("   "));
    }

    @Test
    public void testParseObjectMalformed()
    {
        assertNull(Json.parseObject("{nope"));
        assertNull(Json.parseObject("not json at all {"));
    }

    @Test
    public void testParseObjectNonObjectRoot()
    {
        assertNull(Json.parseObject("[1,2,3]"));
        assertNull(Json.parseObject("42"));
        assertNull(Json.parseObject("\"a string\""));
        assertNull(Json.parseObject("null"));
    }

    // ---- str ----

    @Test
    public void testStrReturnsStringValue()
    {
        JsonObject o = Json.parseObject("{\"projectName\":\"TestConfiguration\"}");

        assertEquals("TestConfiguration", Json.str(o, "projectName"));
    }

    @Test
    public void testStrMissingKey()
    {
        JsonObject o = Json.parseObject("{\"a\":\"b\"}");

        assertNull(Json.str(o, "missing"));
    }

    @Test
    public void testStrNullSafe()
    {
        assertNull(Json.str(null, "key"));
        assertNull(Json.str(new JsonObject(), null));
    }

    @Test
    public void testStrNonPrimitiveReturnsNull()
    {
        JsonObject o = Json.parseObject("{\"nested\":{\"a\":1},\"list\":[1],\"nil\":null}");

        assertNull(Json.str(o, "nested"));
        assertNull(Json.str(o, "list"));
        assertNull(Json.str(o, "nil"));
    }

    @Test
    public void testStrNumberPrimitiveReturnsStringForm()
    {
        // Documented leniency: non-string primitives come back in string form.
        JsonObject o = Json.parseObject("{\"id\":5,\"flag\":true}");

        assertEquals("5", Json.str(o, "id"));
        assertEquals("true", Json.str(o, "flag"));
    }

    // ---- obj ----

    @Test
    public void testObjReturnsNestedObject()
    {
        JsonObject o = Json.parseObject("{\"params\":{\"arguments\":{\"projectName\":\"A\"}}}");

        JsonObject params = Json.obj(o, "params");
        assertNotNull(params);
        JsonObject arguments = Json.obj(params, "arguments");
        assertNotNull(arguments);
        assertEquals("A", Json.str(arguments, "projectName"));
    }

    @Test
    public void testObjMissingOrNonObject()
    {
        JsonObject o = Json.parseObject("{\"s\":\"text\",\"n\":1,\"list\":[]}");

        assertNull(Json.obj(o, "missing"));
        assertNull(Json.obj(o, "s"));
        assertNull(Json.obj(o, "n"));
        assertNull(Json.obj(o, "list"));
    }

    @Test
    public void testObjNullSafe()
    {
        assertNull(Json.obj(null, "key"));
        assertNull(Json.obj(new JsonObject(), null));
    }

    // ---- compact ----

    @Test
    public void testCompactProducesSingleLineJson()
    {
        JsonObject o = new JsonObject();
        o.addProperty("status", "ok");
        o.addProperty("backends", 2);

        String compact = Json.compact(o);

        assertEquals("{\"status\":\"ok\",\"backends\":2}", compact);
    }

    @Test
    public void testCompactRoundTrip()
    {
        String source = "{\"a\":{\"b\":[1,2],\"c\":\"x\"}}";

        JsonObject parsed = Json.parseObject(source);

        assertEquals(source, Json.compact(parsed));
    }

    @Test
    public void testCompactNullIsJsonNull()
    {
        assertEquals("null", Json.compact(null));
    }
}
