/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for {@link OutputSchemaCompactor}.
 */
public class OutputSchemaCompactorTest
{
    @Test
    public void testNullIsPassedThrough()
    {
        assertNull(OutputSchemaCompactor.compact(null));
    }

    @Test
    public void testTopLevelDescriptionIsRemoved()
    {
        JsonElement compacted = compact("{\"type\":\"object\",\"description\":\"The envelope\"}");

        assertFalse(compacted.getAsJsonObject().has("description"));
        assertEquals("object", compacted.getAsJsonObject().get("type").getAsString());
    }

    @Test
    public void testNestedPropertyDescriptionsAreRemoved()
    {
        JsonElement compacted = compact("{\"type\":\"object\",\"properties\":{"
            + "\"success\":{\"type\":\"boolean\",\"description\":\"Whether it worked\"},"
            + "\"rows\":{\"type\":\"array\",\"description\":\"The rows\","
            + "\"items\":{\"type\":\"object\",\"description\":\"One row\"}}}}");

        JsonObject properties = compacted.getAsJsonObject().getAsJsonObject("properties");
        assertFalse(properties.getAsJsonObject("success").has("description"));
        assertFalse(properties.getAsJsonObject("rows").has("description"));
        assertFalse(properties.getAsJsonObject("rows").getAsJsonObject("items").has("description"));
    }

    /**
     * The shape a client validates {@code structuredContent} against must survive
     * untouched — only the prose goes.
     */
    @Test
    public void testShapeIsPreserved()
    {
        JsonElement compacted = compact("{\"type\":\"object\",\"required\":[\"success\"],"
            + "\"properties\":{\"success\":{\"type\":\"boolean\",\"description\":\"x\"},"
            + "\"action\":{\"type\":\"string\",\"enum\":[\"adopted\",\"alreadyAdopted\"],"
            + "\"description\":\"y\"}}}");

        JsonObject root = compacted.getAsJsonObject();
        assertEquals("success", root.getAsJsonArray("required").get(0).getAsString());
        JsonObject action = root.getAsJsonObject("properties").getAsJsonObject("action");
        assertEquals("string", action.get("type").getAsString());
        assertEquals(2, action.getAsJsonArray("enum").size());
        assertEquals("adopted", action.getAsJsonArray("enum").get(0).getAsString());
    }

    /**
     * A schema keyword and a PROPERTY NAME share one namespace. {@code delete_metadata}
     * really declares a property called {@code items} and {@code set_variable} one
     * called {@code type}; a property named {@code description} is equally legal. None
     * of them may be mistaken for the keyword of the same name.
     */
    @Test
    public void testPropertyNamedLikeAKeywordSurvives()
    {
        JsonElement compacted = compact("{\"type\":\"object\",\"properties\":{"
            + "\"items\":{\"type\":\"array\",\"description\":\"drop me\"},"
            + "\"type\":{\"type\":\"string\",\"description\":\"drop me\"},"
            + "\"description\":{\"type\":\"string\",\"description\":\"drop me\"}}}");

        JsonObject properties = compacted.getAsJsonObject().getAsJsonObject("properties");
        // The properties themselves are still declared...
        assertTrue("property 'items' must survive", properties.has("items"));
        assertTrue("property 'type' must survive", properties.has("type"));
        assertTrue("property 'description' must survive", properties.has("description"));
        // ...and each one's own prose is gone.
        assertFalse(properties.getAsJsonObject("items").has("description"));
        assertFalse(properties.getAsJsonObject("type").has("description"));
        assertFalse(properties.getAsJsonObject("description").has("description"));
        assertEquals("string", properties.getAsJsonObject("description").get("type").getAsString());
    }

    @Test
    public void testApplicatorArraysAreWalked()
    {
        JsonElement compacted = compact("{\"anyOf\":["
            + "{\"type\":\"object\",\"description\":\"a\"},"
            + "{\"type\":\"null\",\"description\":\"b\"}]}");

        assertFalse(compacted.getAsJsonObject().getAsJsonArray("anyOf").get(0)
            .getAsJsonObject().has("description"));
        assertFalse(compacted.getAsJsonObject().getAsJsonArray("anyOf").get(1)
            .getAsJsonObject().has("description"));
    }

    /**
     * {@code additionalProperties} is a boolean as often as it is a schema; the walk
     * must not choke on the boolean form.
     */
    @Test
    public void testBooleanAdditionalPropertiesIsLeftAlone()
    {
        JsonElement compacted =
            compact("{\"type\":\"object\",\"additionalProperties\":true,\"description\":\"x\"}");

        assertTrue(compacted.getAsJsonObject().get("additionalProperties").getAsBoolean());
        assertFalse(compacted.getAsJsonObject().has("description"));
    }

    @Test
    public void testInputIsNotMutated()
    {
        JsonElement original = JsonParser.parseString(
            "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\",\"description\":\"keep\"}}}");

        OutputSchemaCompactor.compact(original);

        assertTrue("compact() must not mutate its argument",
            original.getAsJsonObject().getAsJsonObject("properties").getAsJsonObject("a")
                .has("description"));
    }

    private static JsonElement compact(String schemaJson)
    {
        return OutputSchemaCompactor.compact(JsonParser.parseString(schemaJson));
    }
}
