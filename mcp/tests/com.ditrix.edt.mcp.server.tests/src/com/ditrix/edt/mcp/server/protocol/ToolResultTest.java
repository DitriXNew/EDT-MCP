/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.List;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ToolResult}.
 */
public class ToolResultTest
{
    @Test
    public void testSuccessResult()
    {
        String json = ToolResult.success().toJson();
        JsonElement element = JsonParser.parseString(json);
        assertTrue(element.getAsJsonObject().get("success").getAsBoolean());
    }

    @Test
    public void testErrorResult()
    {
        String json = ToolResult.error("Something went wrong").toJson();
        JsonElement element = JsonParser.parseString(json);
        assertFalse(element.getAsJsonObject().get("success").getAsBoolean());
        assertEquals("Something went wrong", element.getAsJsonObject().get("error").getAsString());
    }

    @Test
    public void testPutString()
    {
        String json = ToolResult.success()
            .put("name", "TestProject")
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals("TestProject", element.getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testPutInt()
    {
        String json = ToolResult.success()
            .put("count", 42)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(42, element.getAsJsonObject().get("count").getAsInt());
    }

    @Test
    public void testPutLong()
    {
        String json = ToolResult.success()
            .put("bigCount", 123456789L)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(123456789L, element.getAsJsonObject().get("bigCount").getAsLong());
    }

    @Test
    public void testPutBoolean()
    {
        String json = ToolResult.success()
            .put("active", true)
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertTrue(element.getAsJsonObject().get("active").getAsBoolean());
    }

    @Test
    public void testPutList()
    {
        String json = ToolResult.success()
            .put("items", List.of("a", "b", "c"))
            .toJson();
        JsonElement element = JsonParser.parseString(json);
        assertEquals(3, element.getAsJsonObject().get("items").getAsJsonArray().size());
    }

    @Test
    public void testChaining()
    {
        String json = ToolResult.success()
            .put("name", "Test")
            .put("count", 5)
            .put("active", true)
            .toJson();

        JsonElement element = JsonParser.parseString(json);
        var obj = element.getAsJsonObject();
        assertTrue(obj.get("success").getAsBoolean());
        assertEquals("Test", obj.get("name").getAsString());
        assertEquals(5, obj.get("count").getAsInt());
        assertTrue(obj.get("active").getAsBoolean());
    }

    @Test
    public void testToJsonStatic()
    {
        String json = ToolResult.toJsonStatic(List.of(1, 2, 3));
        assertNotNull(json);
        assertTrue(json.startsWith("["));
    }
}
