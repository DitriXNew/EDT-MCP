/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for MCP tool results.
 * Uses GsonProvider for JSON serialization to avoid manual string building.
 */
public class ToolResult
{
    private final Map<String, Object> data = new HashMap<>();
    
    private ToolResult()
    {
    }
    
    /**
     * Creates a new success result.
     */
    public static ToolResult success()
    {
        ToolResult result = new ToolResult();
        result.data.put("success", true);
        return result;
    }
    
    /**
     * Creates a new error result.
     * 
     * @param message error message
     */
    public static ToolResult error(String message)
    {
        ToolResult result = new ToolResult();
        result.data.put("success", false);
        result.data.put("error", message);
        return result;
    }
    
    /**
     * Adds a string field.
     */
    public ToolResult put(String key, String value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds an integer field.
     */
    public ToolResult put(String key, int value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a long field.
     */
    public ToolResult put(String key, long value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a boolean field.
     */
    public ToolResult put(String key, boolean value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds a list field.
     */
    public ToolResult put(String key, List<?> value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Adds any object field (will be serialized by Gson).
     */
    public ToolResult put(String key, Object value)
    {
        data.put(key, value);
        return this;
    }
    
    /**
     * Converts to JSON string.
     */
    public String toJson()
    {
        return GsonProvider.toJson(data);
    }
    
    /**
     * Static helper to serialize any object to JSON.
     */
    public static String toJsonStatic(Object obj)
    {
        return GsonProvider.toJson(obj);
    }
}