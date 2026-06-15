/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for JSON Schema objects.
 * Used to build inputSchema for MCP tools in a type-safe way.
 */
public class JsonSchemaBuilder
{
    /** JSON Schema {@code "object"} type token. */
    private static final String TYPE_OBJECT = "object"; //$NON-NLS-1$

    /** JSON Schema {@code "string"} type token. */
    private static final String TYPE_STRING = "string"; //$NON-NLS-1$

    /** JSON Schema {@code "description"} key. */
    private static final String KEY_DESCRIPTION = "description"; //$NON-NLS-1$

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
        builder.schema.put("type", TYPE_OBJECT); //$NON-NLS-1$
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
        prop.put("type", TYPE_STRING); //$NON-NLS-1$
        prop.put(KEY_DESCRIPTION, description);
        properties.put(name, prop);

        if (required)
        {
            this.required.add(name);
        }
        return this;
    }

    /**
     * Adds a string property restricted to a closed set of values (a JSON Schema
     * {@code enum}). Only use this when {@code execute()} accepts exactly the given
     * values — never for a bilingual or open-ended vocabulary (a metadata TYPE
     * token, a synonym, a free-form name).
     *
     * @param name property name
     * @param description property description
     * @param values the closed set of accepted values
     * @return this builder
     */
    public JsonSchemaBuilder enumProperty(String name, String description, String... values)
    {
        return enumProperty(name, description, false, values);
    }

    /**
     * Adds a string property restricted to a closed set of values (a JSON Schema
     * {@code enum}). Only use this when {@code execute()} accepts exactly the given
     * values — never for a bilingual or open-ended vocabulary (a metadata TYPE
     * token, a synonym, a free-form name).
     *
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @param values the closed set of accepted values
     * @return this builder
     */
    public JsonSchemaBuilder enumProperty(String name, String description, boolean required, String... values)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", TYPE_STRING); //$NON-NLS-1$
        prop.put(KEY_DESCRIPTION, description);
        prop.put("enum", Arrays.asList(values)); //$NON-NLS-1$
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
        prop.put(KEY_DESCRIPTION, description);
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
        prop.put(KEY_DESCRIPTION, description);
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
        items.put("type", TYPE_STRING); //$NON-NLS-1$

        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("items", items); //$NON-NLS-1$
        prop.put(KEY_DESCRIPTION, description);
        properties.put(name, prop);
        
        if (required)
        {
            this.required.add(name);
        }
        return this;
    }
    
    /**
     * Adds a number (floating-point) property to the schema. Use for values whose
     * Java type is {@code double}/{@code float} (e.g. a duration in seconds, a
     * percentage); use {@link #integerProperty} for {@code int}/{@code long}.
     *
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder numberProperty(String name, String description)
    {
        return numberProperty(name, description, false);
    }

    /**
     * Adds a number (floating-point) property to the schema.
     *
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder numberProperty(String name, String description, boolean required)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "number"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put(KEY_DESCRIPTION, description);
        properties.put(name, prop);

        if (required)
        {
            this.required.add(name);
        }
        return this;
    }

    /**
     * Adds an object property to the schema. The nested object's inner shape is left
     * unconstrained ({@code {"type":"object"}}) on purpose: an {@code outputSchema}
     * must stay permissive so a conformant client never rejects a valid payload whose
     * nested fields are conditional. Use for a {@code Map}/nested-object field.
     *
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder objectProperty(String name, String description)
    {
        return objectProperty(name, description, false);
    }

    /**
     * Adds an object property to the schema (inner shape unconstrained).
     *
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder objectProperty(String name, String description, boolean required)
    {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", TYPE_OBJECT); //$NON-NLS-1$
        prop.put(KEY_DESCRIPTION, description);
        properties.put(name, prop);

        if (required)
        {
            this.required.add(name);
        }
        return this;
    }

    /**
     * Adds an array-of-objects property to the schema. The element shape is declared
     * as {@code {"items":{"type":"object"}}} only — deliberately not constraining the
     * per-element fields, so the {@code outputSchema} describes "a list of records"
     * without over-fitting to fields that may be conditional. Use for a
     * {@code List<Map<...>>} field.
     *
     * @param name property name
     * @param description property description
     * @return this builder
     */
    public JsonSchemaBuilder objectArrayProperty(String name, String description)
    {
        return objectArrayProperty(name, description, false);
    }

    /**
     * Adds an array-of-objects property to the schema (element shape unconstrained).
     *
     * @param name property name
     * @param description property description
     * @param required whether property is required
     * @return this builder
     */
    public JsonSchemaBuilder objectArrayProperty(String name, String description, boolean required)
    {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", TYPE_OBJECT); //$NON-NLS-1$

        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array"); //$NON-NLS-1$ //$NON-NLS-2$
        prop.put("items", items); //$NON-NLS-1$
        prop.put(KEY_DESCRIPTION, description);
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
