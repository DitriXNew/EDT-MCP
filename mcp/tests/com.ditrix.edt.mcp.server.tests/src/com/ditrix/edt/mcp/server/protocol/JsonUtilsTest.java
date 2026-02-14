/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link JsonUtils}.
 */
public class JsonUtilsTest
{
    // --- escapeJson ---

    @Test
    public void testEscapeJsonNull()
    {
        assertEquals("", JsonUtils.escapeJson(null));
    }

    @Test
    public void testEscapeJsonEmpty()
    {
        assertEquals("", JsonUtils.escapeJson(""));
    }

    @Test
    public void testEscapeJsonSpecialChars()
    {
        assertEquals("line1\\nline2", JsonUtils.escapeJson("line1\nline2"));
        assertEquals("tab\\there", JsonUtils.escapeJson("tab\there"));
        assertEquals("quote\\\"here", JsonUtils.escapeJson("quote\"here"));
        assertEquals("backslash\\\\here", JsonUtils.escapeJson("backslash\\here"));
        assertEquals("cr\\rhere", JsonUtils.escapeJson("cr\rhere"));
    }

    @Test
    public void testEscapeJsonPlainText()
    {
        assertEquals("hello world", JsonUtils.escapeJson("hello world"));
    }

    // --- extractStringArgument ---

    @Test
    public void testExtractStringArgumentNull()
    {
        assertNull(JsonUtils.extractStringArgument(null, "key"));
        assertNull(JsonUtils.extractStringArgument(new HashMap<>(), null));
    }

    @Test
    public void testExtractStringArgumentExists()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject");
        assertEquals("TestProject", JsonUtils.extractStringArgument(params, "projectName"));
    }

    @Test
    public void testExtractStringArgumentMissing()
    {
        Map<String, String> params = new HashMap<>();
        params.put("projectName", "TestProject");
        assertNull(JsonUtils.extractStringArgument(params, "otherKey"));
    }

    // --- extractBooleanArgument ---

    @Test
    public void testExtractBooleanArgumentTrue()
    {
        Map<String, String> params = new HashMap<>();
        params.put("flag", "true");
        assertTrue(JsonUtils.extractBooleanArgument(params, "flag", false));
    }

    @Test
    public void testExtractBooleanArgumentFalse()
    {
        Map<String, String> params = new HashMap<>();
        params.put("flag", "false");
        assertFalse(JsonUtils.extractBooleanArgument(params, "flag", true));
    }

    @Test
    public void testExtractBooleanArgumentYesNo()
    {
        Map<String, String> params = new HashMap<>();
        params.put("a", "yes");
        params.put("b", "no");
        params.put("c", "1");
        params.put("d", "0");
        assertTrue(JsonUtils.extractBooleanArgument(params, "a", false));
        assertFalse(JsonUtils.extractBooleanArgument(params, "b", true));
        assertTrue(JsonUtils.extractBooleanArgument(params, "c", false));
        assertFalse(JsonUtils.extractBooleanArgument(params, "d", true));
    }

    @Test
    public void testExtractBooleanArgumentDefault()
    {
        Map<String, String> params = new HashMap<>();
        assertTrue(JsonUtils.extractBooleanArgument(params, "missing", true));
        assertFalse(JsonUtils.extractBooleanArgument(params, "missing", false));
        assertTrue(JsonUtils.extractBooleanArgument(null, "key", true));
    }

    @Test
    public void testExtractBooleanArgumentInvalid()
    {
        Map<String, String> params = new HashMap<>();
        params.put("flag", "maybe");
        assertTrue(JsonUtils.extractBooleanArgument(params, "flag", true));
    }

    // --- extractIntArgument ---

    @Test
    public void testExtractIntArgumentValid()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "100");
        assertEquals(100, JsonUtils.extractIntArgument(params, "limit", 50));
    }

    @Test
    public void testExtractIntArgumentFloat()
    {
        // Gson may serialize int as "1.0"
        Map<String, String> params = new HashMap<>();
        params.put("limit", "100.0");
        assertEquals(100, JsonUtils.extractIntArgument(params, "limit", 50));
    }

    @Test
    public void testExtractIntArgumentNonInteger()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "1.5");
        assertEquals(50, JsonUtils.extractIntArgument(params, "limit", 50));
    }

    @Test
    public void testExtractIntArgumentInvalid()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "abc");
        assertEquals(50, JsonUtils.extractIntArgument(params, "limit", 50));
    }

    @Test
    public void testExtractIntArgumentNull()
    {
        assertEquals(50, JsonUtils.extractIntArgument(null, "limit", 50));
        assertEquals(50, JsonUtils.extractIntArgument(new HashMap<>(), null, 50));
    }

    @Test
    public void testExtractIntArgumentEmpty()
    {
        Map<String, String> params = new HashMap<>();
        params.put("limit", "");
        assertEquals(50, JsonUtils.extractIntArgument(params, "limit", 50));
    }

    // --- extractArrayArgument ---

    @Test
    public void testExtractArrayArgumentJsonArray()
    {
        Map<String, String> params = new HashMap<>();
        params.put("objects", "[\"Catalog.Products\",\"Document.SalesOrder\"]");
        List<String> result = JsonUtils.extractArrayArgument(params, "objects");
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Catalog.Products", result.get(0));
        assertEquals("Document.SalesOrder", result.get(1));
    }

    @Test
    public void testExtractArrayArgumentCommaSeparated()
    {
        Map<String, String> params = new HashMap<>();
        params.put("objects", "Catalog.Products, Document.SalesOrder");
        List<String> result = JsonUtils.extractArrayArgument(params, "objects");
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Catalog.Products", result.get(0));
        assertEquals("Document.SalesOrder", result.get(1));
    }

    @Test
    public void testExtractArrayArgumentNull()
    {
        assertNull(JsonUtils.extractArrayArgument(null, "key"));
        assertNull(JsonUtils.extractArrayArgument(new HashMap<>(), null));
    }

    @Test
    public void testExtractArrayArgumentEmpty()
    {
        Map<String, String> params = new HashMap<>();
        params.put("objects", "");
        assertNull(JsonUtils.extractArrayArgument(params, "objects"));
    }

    @Test
    public void testExtractArrayArgumentMissing()
    {
        Map<String, String> params = new HashMap<>();
        assertNull(JsonUtils.extractArrayArgument(params, "missing"));
    }

    // --- buildJsonRpcError ---

    @Test
    public void testBuildJsonRpcError()
    {
        String error = JsonUtils.buildJsonRpcError(-32600, "Invalid request", 1);
        assertNotNull(error);
        assertTrue(error.contains("\"jsonrpc\":\"2.0\""));
        assertTrue(error.contains("\"code\":-32600"));
        assertTrue(error.contains("\"message\":\"Invalid request\""));
    }

    @Test
    public void testBuildJsonRpcErrorNullMessage()
    {
        String error = JsonUtils.buildJsonRpcError(-32603, null, 1);
        assertTrue(error.contains("\"message\":\"Unknown error\""));
    }

    @Test
    public void testBuildJsonRpcErrorStringId()
    {
        String error = JsonUtils.buildJsonRpcError(-32600, "Test", "abc");
        assertTrue(error.contains("\"id\":\"abc\""));
    }

    @Test
    public void testBuildJsonRpcErrorNullId()
    {
        String error = JsonUtils.buildJsonRpcError(-32600, "Test", null);
        // Gson may serialize null id as absent or as "id":null depending on configuration
        // The key requirement is that the response is valid JSON with the error
        assertTrue(error.contains("\"code\":-32600"));
        assertTrue(error.contains("\"message\":\"Test\""));
    }

    // --- buildSimpleError ---

    @Test
    public void testBuildSimpleError()
    {
        String error = JsonUtils.buildSimpleError("Something failed");
        assertNotNull(error);
        assertTrue(error.contains("\"error\":\"Something failed\""));
    }

    @Test
    public void testBuildSimpleErrorNull()
    {
        String error = JsonUtils.buildSimpleError(null);
        assertTrue(error.contains("\"error\":\"Unknown error\""));
    }

    // --- buildHealthResponse ---

    @Test
    public void testBuildHealthResponse()
    {
        String response = JsonUtils.buildHealthResponse("2025.2.0");
        assertNotNull(response);
        assertTrue(response.contains("\"status\":\"ok\""));
        assertTrue(response.contains("\"edt_version\":\"2025.2.0\""));
    }
}
