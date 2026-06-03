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
        // The success content fallback is now a non-empty digest, not "Done".
        String text = result.getContent().get(0).getText();
        assertNotNull(text);
        assertFalse("success digest must not be empty", text.isEmpty());
        assertNotEquals("success digest must not be the opaque \"Done\"", "Done", text);
        assertNotNull(result.getStructuredContent());
    }

    @Test
    public void testJsonErrorResultSetsIsError()
    {
        JsonElement structured = JsonParser.parseString("{\"success\":false,\"error\":\"boom\"}");
        ToolCallResult result = ToolCallResult.json(structured, true);

        assertEquals(Boolean.TRUE, result.getIsError());
        // The content text now carries the REAL error message (from the payload's
        // error field), so a content-only client/model sees WHY it failed — not a
        // bare "Error" placeholder.
        assertEquals("boom", result.getContent().get(0).getText());

        String json = GsonProvider.toJson(result);
        JsonElement element = JsonParser.parseString(json);
        assertTrue("error result must carry isError:true",
            element.getAsJsonObject().get("isError").getAsBoolean());
    }

    @Test
    public void testJsonSuccessOmitsIsError()
    {
        JsonElement structured = JsonParser.parseString("{\"count\":42}");
        ToolCallResult result = ToolCallResult.json(structured);

        assertNull(result.getIsError());
        // Success content is a digest, never the opaque "Done".
        assertNotEquals("Done", result.getContent().get(0).getText());

        String json = GsonProvider.toJson(result);
        JsonElement element = JsonParser.parseString(json);
        assertFalse("success result must not carry isError",
            element.getAsJsonObject().has("isError"));
    }

    @Test
    public void testJsonSuccessDigestSummarizesObject()
    {
        // The digest is derived from the structured content: it names the
        // top-level keys and the size of the primary (first array) collection,
        // so a content-only client still sees something meaningful.
        JsonElement structured = JsonParser.parseString(
            "{\"project\":\"Demo\",\"modules\":[1,2,3]}");
        ToolCallResult result = ToolCallResult.json(structured);

        String text = result.getContent().get(0).getText();
        assertTrue("digest should mention the array count", text.contains("modules: 3"));
        assertTrue("digest should list the keys", text.contains("project"));
        // structuredContent must remain the full, untouched data.
        assertSame(structured, result.getStructuredContent());
    }

    @Test
    public void testJsonSuccessDigestIsBounded()
    {
        // A large payload must not blow up the content fallback: the digest is
        // capped (~500 chars) and ends with an ellipsis when truncated.
        StringBuilder big = new StringBuilder("{");
        for (int i = 0; i < 400; i++)
        {
            if (i > 0)
            {
                big.append(",");
            }
            big.append("\"averyverylongkeyname").append(i).append("\":").append(i);
        }
        big.append("}");
        JsonElement structured = JsonParser.parseString(big.toString());
        ToolCallResult result = ToolCallResult.json(structured);

        String text = result.getContent().get(0).getText();
        assertTrue("digest must be bounded", text.length() <= 500);
        assertTrue("truncated digest must end with an ellipsis",
            text.endsWith(String.valueOf((char)0x2026)));
        // The full data is still intact in structuredContent.
        assertEquals(400, structured.getAsJsonObject().size());
    }

    @Test
    public void testJsonErrorContentCarriesRealMessage()
    {
        // The failure content fallback is the real error message (the payload's
        // error field), never a success digest and never a bare "Error" placeholder.
        JsonElement structured = JsonParser.parseString("{\"success\":false,\"error\":\"boom\"}");
        ToolCallResult result = ToolCallResult.json(structured, true);

        assertEquals("boom", result.getContent().get(0).getText());
    }

    @Test
    public void testJsonErrorWithoutMessageFallsBackToError()
    {
        // No usable error string in the payload -> the literal "Error" fallback.
        JsonElement structured = JsonParser.parseString("{\"success\":false}");
        ToolCallResult result = ToolCallResult.json(structured, true);

        assertEquals("Error", result.getContent().get(0).getText());
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
