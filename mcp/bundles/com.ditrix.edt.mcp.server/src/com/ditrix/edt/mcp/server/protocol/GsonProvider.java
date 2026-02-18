/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;

/**
 * Provides a shared Gson instance for JSON serialization/deserialization.
 * This avoids creating multiple Gson instances across the codebase.
 */
public final class GsonProvider
{
    /** Shared Gson instance - thread-safe for serialization/deserialization */
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(Object.class, new IntegerAwareObjectDeserializer())
        .create();
    
    private GsonProvider()
    {
        // Utility class
    }
    
    /**
     * Returns the shared Gson instance.
     * 
     * @return Gson instance
     */
    public static Gson get()
    {
        return GSON;
    }
    
    /**
     * Serializes an object to JSON string.
     * 
     * @param src the object to serialize
     * @return JSON string
     */
    public static String toJson(Object src)
    {
        return GSON.toJson(src);
    }
    
    /**
     * Deserializes JSON string to an object.
     * 
     * @param <T> the type of the desired object
     * @param json the JSON string
     * @param classOfT the class of T
     * @return an object of type T
     */
    public static <T> T fromJson(String json, Class<T> classOfT)
    {
        return GSON.fromJson(json, classOfT);
    }

    /**
     * JsonDeserializer for Object.class that parses integer JSON numbers as Long
     * instead of Double. Uses only public com.google.gson API (no stream classes).
     * This fixes the JSON-RPC id round-trip: "id":0 must not become "id":0.0.
     */
    private static class IntegerAwareObjectDeserializer implements JsonDeserializer<Object>
    {
        @Override
        public Object deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException
        {
            if (json.isJsonNull())
            {
                return null;
            }
            if (json.isJsonPrimitive())
            {
                JsonPrimitive prim = json.getAsJsonPrimitive();
                if (prim.isBoolean())
                {
                    return prim.getAsBoolean();
                }
                if (prim.isString())
                {
                    return prim.getAsString();
                }
                // Number: try Long first (preserves integer IDs), fall back to Double
                String raw = prim.getAsNumber().toString();
                try
                {
                    return Long.parseLong(raw);
                }
                catch (NumberFormatException e)
                {
                    return prim.getAsDouble();
                }
            }
            if (json.isJsonArray())
            {
                List<Object> list = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray())
                {
                    list.add(context.deserialize(element, Object.class));
                }
                return list;
            }
            // JsonObject
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet())
            {
                map.put(entry.getKey(), context.deserialize(entry.getValue(), Object.class));
            }
            return map;
        }
    }
}
