/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for JSON Schema objects.
 * Used to build inputSchema for MCP tools in a type-safe way.
 */
public class JsonSchemaBuilder
{
    private final Map<String, Object> schema = new LinkedHashMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();
    
    /**
     * Creates a new schema builder with type "object".
     * 
     * @return new builder
     */
    public static JsonSchemaBuilder object()
    {
        JsonSchemaBuilder builder = new JsonSchemaBuilder();
        builder.schema.put("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        return builder;
    }
    
    /**
     * Adds a string property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder stringProperty(String name, String description)
    {
        return stringProperty(name, description, false);
    }
    
    /**
     * Adds a string property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder stringProperty(String name, String description, boolean required)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("description", description); //$NON-NLS-1$
        properties.put(name, prop);
        
        if (required)
        {
            this.required.add(name);
        }
        return this;
    }
    
    /**
     * Adds an integer property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder integerProperty(String name, String description)
    {
        return integerProperty(name, description, false);
    }
    
    /**
     * Adds an integer property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder integerProperty(String name, String description, boolean required)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "integer"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("description", description); //$NON-NLS-1$
        properties.put(name, prop);
        
        if (required)
        {
            this.required.add(name);
        }
        return this;
    }
    
    /**
     * Adds a boolean property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder booleanProperty(String name, String description)
    {
        return booleanProperty(name, description, false);
    }
    
    /**
     * Adds a boolean property to the schema.
     * 
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder booleanProperty(String name, String description, boolean required)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "boolean"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("description", description); //$NON-NLS-1$
        properties.put(name, prop);
        
        if (required)
        {
            this.required.add(name);
        }
        return this;
    }
    
    /**
     * Adds an array property with string items to the schema.
     * 
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder stringArrayProperty(String name, String description)
    {
        return stringArrayProperty(name, description, false);
    }
    
    /**
     * Adds an array property with string items to the schema.
     * 
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder stringArrayProperty(String name, String description, boolean required)
    {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string"); //$NON-NLS-1$ //$NON-NLS-2$
        
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("items", items); //$NON-NLS-1$
        prop.put("description", description); //$NON-NLS-1$
        properties.put(name, prop);
        
        if (required)
        {
            this.required.add(name);
        }
        return this;
    }
    
    /**
     * Builds the schema as a JSON string.
     * 
     * @return JSON schema string
     */
    public String build()
    {
        schema.put("properties", properties); //$NON-NLS-1$
        schema.put("required", required); //$NON-NLS-1$
        return GsonProvider.toJson(schema);
    }
    
    /**
     * Builds the schema as a Map for direct use.
     * 
     * @return schema as map
     */
    public Map<String, Object> buildMap()
    {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("properties", new LinkedHashMap<>(properties)); //$NON-NLS-1$
        result.put("required", new ArrayList<>(required)); //$NON-NLS-1$
        return result;
    }
}
