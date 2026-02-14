/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ToolCallResult} and {@link ToolsListResult}.
 */
public class ToolCallResultTest
{
    @Test
    public void testTextResult()
    {
        ToolCallResult result = ToolCallResult.text("Hello, world!");
        assertNotNull(result.getContent());
        assertEquals(1, result.getContent().size());
        assertEquals("text", result.getContent().get(0).getType());
        assertEquals("Hello, world!", result.getContent().get(0).getText());
        assertNull(result.getStructuredContent());
    }

    @Test
    public void testJsonResult()
    {
        JsonElement structured = JsonParser.parseString("{\"count\":42}");
        ToolCallResult result = ToolCallResult.json(structured);

        assertNotNull(result.getContent());
        assertEquals(1, result.getContent().size());
        assertEquals("text", result.getContent().get(0).getType());
        assertEquals("Done", result.getContent().get(0).getText());
        assertNotNull(result.getStructuredContent());
    }

    @Test
    public void testResourceResult()
    {
        ToolCallResult result = ToolCallResult.resource(
            "embedded://result.md", "text/markdown", "# Title");

        assertEquals(1, result.getContent().size());
        var item = result.getContent().get(0);
        assertEquals("resource", item.getType());
        assertNotNull(item.getResource());
    }

    @Test
    public void testResourceBlobResult()
    {
        ToolCallResult result = ToolCallResult.resourceBlob(
            "embedded://screenshot.png", "image/png", "base64data==");

        assertEquals(1, result.getContent().size());
        assertEquals("resource", result.getContent().get(0).getType());
    }

    @Test
    public void testTextResultSerialization()
    {
        ToolCallResult result = ToolCallResult.text("test output");
        String json = GsonProvider.toJson(result);

        JsonElement element = JsonParser.parseString(json);
        var contentArray = element.getAsJsonObject().get("content").getAsJsonArray();
        assertEquals(1, contentArray.size());
        assertEquals("text", contentArray.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("test output", contentArray.get(0).getAsJsonObject().get("text").getAsString());
    }

    // --- ToolsListResult ---

    @Test
    public void testToolsListEmpty()
    {
        ToolsListResult listResult = new ToolsListResult();
        assertNotNull(listResult.getTools());
        assertTrue(listResult.getTools().isEmpty());
    }

    @Test
    public void testToolsListAddTool()
    {
        ToolsListResult listResult = new ToolsListResult();
        JsonElement schema = JsonParser.parseString("{\"type\":\"object\"}");
        listResult.addTool("get_edt_version", "Get EDT version", schema);

        assertEquals(1, listResult.getTools().size());
        var tool = listResult.getTools().get(0);
        assertEquals("get_edt_version", tool.getName());
        assertEquals("Get EDT version", tool.getDescription());
        assertNotNull(tool.getInputSchema());
    }

    @Test
    public void testToolsListSerialization()
    {
        ToolsListResult listResult = new ToolsListResult();
        JsonElement schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}");
        listResult.addTool("test_tool", "A test tool", schema);

        String json = GsonProvider.toJson(listResult);
        JsonElement element = JsonParser.parseString(json);
        var tools = element.getAsJsonObject().get("tools").getAsJsonArray();
        assertEquals(1, tools.size());
        assertEquals("test_tool", tools.get(0).getAsJsonObject().get("name").getAsString());
    }
}
