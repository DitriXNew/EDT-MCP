/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

/**
 * Provides a shared Gson instance for JSON serialization/deserialization.
 * This avoids creating multiple Gson instances across the codebase.
 */
public final class GsonProvider
{
    /** Shared Gson instance - thread-safe for serialization/deserialization */
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapterFactory(new IntegerAwareLongTypeAdapterFactory())
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
     * TypeAdapterFactory that parses integer JSON numbers as Long instead of Double
     * when the target type is Object.
     * This fixes the JSON-RPC id round-trip: "id":0 must not become "id":0.0.
     */
    private static class IntegerAwareLongTypeAdapterFactory implements TypeAdapterFactory
    {
        @SuppressWarnings("unchecked")
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type)
        {
            if (type.getRawType() != Object.class)
            {
                return null;
            }
            return (TypeAdapter<T>) new ObjectTypeAdapter(gson);
        }
    }

    /**
     * Object type adapter that parses integer JSON numbers as Long (not Double).
     */
    private static class ObjectTypeAdapter extends TypeAdapter<Object>
    {
        private final Gson gson;

        ObjectTypeAdapter(Gson gson)
        {
            this.gson = gson;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void write(JsonWriter out, Object value) throws IOException
        {
            if (value == null)
            {
                out.nullValue();
                return;
            }
            TypeAdapter<Object> adapter = (TypeAdapter<Object>) gson.getAdapter(value.getClass());
            adapter.write(out, value);
        }

        @Override
        public Object read(JsonReader in) throws IOException
        {
            JsonToken token = in.peek();
            switch (token)
            {
                case NUMBER:
                    String raw = in.nextString();
                    try
                    {
                        return Long.parseLong(raw);
                    }
                    catch (NumberFormatException e)
                    {
                        return Double.parseDouble(raw);
                    }
                case STRING:
                    return in.nextString();
                case BOOLEAN:
                    return in.nextBoolean();
                case NULL:
                    in.nextNull();
                    return null;
                case BEGIN_ARRAY:
                    List<Object> list = new ArrayList<>();
                    in.beginArray();
                    while (in.hasNext())
                    {
                        list.add(read(in));
                    }
                    in.endArray();
                    return list;
                case BEGIN_OBJECT:
                    Map<String, Object> map = new LinkedHashMap<>();
                    in.beginObject();
                    while (in.hasNext())
                    {
                        map.put(in.nextName(), read(in));
                    }
                    in.endObject();
                    return map;
                default:
                    throw new IllegalStateException("Unexpected JSON token: " + token); //$NON-NLS-1$
            }
        }
    }
}
