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
    /**
     * Shared Gson instance - thread-safe for serialization/deserialization.
     * HTML escaping is disabled: this output is JSON over MCP, not HTML, so raw
     * apostrophes ({@code Field 'X' not found}) and operators ({@code >=}, {@code &},
     * {@code <}) must stay readable for content/text consumers instead of being
     * emitted as {@code \\uXXXX} escapes.
     */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Like {@link #GSON} but writes explicit nulls. Used only where the wire
     * format requires a null-valued field to be present (e.g. JSON-RPC id:null on
     * a parse-error response); the default {@link #GSON} omits null fields.
     */
    private static final Gson GSON_SERIALIZE_NULLS =
        new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

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
     * Serializes to JSON writing explicit nulls. Use only where the wire format
     * requires a null-valued field to be present (JSON-RPC id:null); the default
     * {@link #toJson(Object)} omits nulls.
     *
     * @param src the object to serialize
     * @return JSON string with null fields written
     */
    public static String toJsonSerializeNulls(Object src)
    {
        return GSON_SERIALIZE_NULLS.toJson(src);
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
