/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Thin, null-safe JSON helpers around one shared {@link Gson} instance.
 *
 * <p>The proxy only ever <b>parses</b> request JSON to pick a route and <b>builds</b> its own
 * small responses; it never rewrites a forwarded body. All helpers return {@code null} instead
 * of throwing on malformed or missing data, so the callers can degrade gracefully.
 */
public final class Json
{
    /** The single shared Gson instance (thread-safe). */
    private static final Gson GSON = new Gson();

    private Json()
    {
        // utility class
    }

    /**
     * Parses a string into a JSON object.
     *
     * @param s the raw JSON text, may be {@code null}
     * @return the parsed object, or {@code null} on any failure (null/blank input, malformed JSON,
     *         or a root element that is not a JSON object)
     */
    public static JsonObject parseObject(String s)
    {
        if (s == null || s.isBlank())
        {
            return null;
        }
        try
        {
            JsonElement element = JsonParser.parseString(s);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Reads a primitive member of a JSON object as a string, null-safely.
     *
     * @param o the object to read from, may be {@code null}
     * @param key the member name, may be {@code null}
     * @return the member's string form when it is a JSON primitive (strings verbatim, numbers and
     *         booleans converted), otherwise {@code null}
     */
    public static String str(JsonObject o, String key)
    {
        if (o == null || key == null)
        {
            return null;
        }
        JsonElement element = o.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    /**
     * Reads a nested JSON object member, null-safely.
     *
     * @param o the object to read from, may be {@code null}
     * @param key the member name, may be {@code null}
     * @return the nested object, or {@code null} when absent or not a JSON object
     */
    public static JsonObject obj(JsonObject o, String key)
    {
        if (o == null || key == null)
        {
            return null;
        }
        JsonElement element = o.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /**
     * Serializes a JSON element to its compact (single-line, no extra whitespace) form.
     *
     * @param e the element to serialize; {@code null} is treated as JSON {@code null}
     * @return the compact JSON text, never {@code null}
     */
    public static String compact(JsonElement e)
    {
        return GSON.toJson(e == null ? JsonNull.INSTANCE : e);
    }
}
