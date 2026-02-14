/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link JsonSchemaBuilder}.
 */
public class JsonSchemaBuilderTest
{
    @Test
    public void testEmptyObject()
    {
        String schema = JsonSchemaBuilder.object().build();
        assertNotNull(schema);
        assertTrue(schema.contains("\"type\":\"object\""));
        assertTrue(schema.contains("\"properties\":{}"));
        assertTrue(schema.contains("\"required\":[]"));
    }

    @Test
    public void testStringProperty()
    {
        String schema = JsonSchemaBuilder.object()
            .stringProperty("name", "Project name")
            .build();

        assertTrue(schema.contains("\"name\""));
        assertTrue(schema.contains("\"type\":\"string\""));
        assertTrue(schema.contains("\"description\":\"Project name\""));
        // Not required by default
        assertTrue(schema.contains("\"required\":[]"));
    }

    @Test
    public void testRequiredStringProperty()
    {
        String schema = JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name", true)
            .build();

        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"required\":[\"projectName\"]"));
    }

    @Test
    public void testIntegerProperty()
    {
        String schema = JsonSchemaBuilder.object()
            .integerProperty("limit", "Max results")
            .build();

        assertTrue(schema.contains("\"limit\""));
        assertTrue(schema.contains("\"type\":\"integer\""));
    }

    @Test
    public void testBooleanProperty()
    {
        String schema = JsonSchemaBuilder.object()
            .booleanProperty("full", "Return all props")
            .build();

        assertTrue(schema.contains("\"full\""));
        assertTrue(schema.contains("\"type\":\"boolean\""));
    }

    @Test
    public void testStringArrayProperty()
    {
        String schema = JsonSchemaBuilder.object()
            .stringArrayProperty("objects", "FQN list", true)
            .build();

        assertTrue(schema.contains("\"objects\""));
        assertTrue(schema.contains("\"type\":\"array\""));
        assertTrue(schema.contains("\"items\""));
        assertTrue(schema.contains("\"required\":[\"objects\"]"));
    }

    @Test
    public void testMultipleProperties()
    {
        String schema = JsonSchemaBuilder.object()
            .stringProperty("projectName", "Project name", true)
            .stringProperty("modulePath", "Module path", true)
            .integerProperty("limit", "Max results")
            .booleanProperty("caseSensitive", "Case sensitive")
            .build();

        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"modulePath\""));
        assertTrue(schema.contains("\"limit\""));
        assertTrue(schema.contains("\"caseSensitive\""));
        assertTrue(schema.contains("\"projectName\""));
        assertTrue(schema.contains("\"modulePath\""));
    }

    @Test
    public void testBuildMapReturnsMap()
    {
        var map = JsonSchemaBuilder.object()
            .stringProperty("name", "Test")
            .buildMap();

        assertNotNull(map);
        assertEquals("object", map.get("type"));
        assertNotNull(map.get("properties"));
        assertNotNull(map.get("required"));
    }
}
