/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Provides a shared Gson instance for JSON serialization/deserialization.
 * This avoids creating multiple Gson instances across the codebase.
 */
public final class GsonProvider
{
    /** Shared Gson instance - thread-safe for serialization/deserialization */
    private static final Gson GSON = new GsonBuilder().create();
    
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
}
