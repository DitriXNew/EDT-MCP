/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * JSON utility methods for parameter extraction and JSON building.
 */
public final class JsonUtils
{
    private static final Gson GSON = new Gson();
    
    private JsonUtils()
    {
        // Utility class
    }
    
    /**
     * Escapes special characters for JSON string.
     * Note: Prefer using Gson for JSON serialization.
     * This method is kept for cases where direct escaping is needed.
     * 
     * @param s input string
     * @return escaped string
     */
    public static String escapeJson(String s)
    {
        if (s == null)
        {
            return ""; //$NON-NLS-1$
        }
        return s.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "\\\"") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\n", "\\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\r", "\\r") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\t", "\\t"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Builds a JSON-RPC 2.0 error response.
     * 
     * @param code the error code
     * @param message the error message
     * @param requestId the request ID (can be null)
     * @return JSON-RPC error response string
     */
    public static String buildJsonRpcError(int code, String message, Object requestId)
    {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        
        JsonObject error = new JsonObject();
        error.addProperty("code", code); //$NON-NLS-1$
        error.addProperty("message", message != null ? message : "Unknown error"); //$NON-NLS-1$ //$NON-NLS-2$
        response.add("error", error); //$NON-NLS-1$
        
        if (requestId == null)
        {
            response.add("id", null); //$NON-NLS-1$
        }
        else if (requestId instanceof String)
        {
            response.addProperty("id", (String) requestId); //$NON-NLS-1$
        }
        else if (requestId instanceof Number)
        {
            response.addProperty("id", (Number) requestId); //$NON-NLS-1$
        }
        
        return GSON.toJson(response);
    }
    
    /**
     * Builds a simple JSON error response (non-JSON-RPC).
     * 
     * @param message the error message
     * @return JSON error response string
     */
    public static String buildSimpleError(String message)
    {
        JsonObject response = new JsonObject();
        response.addProperty("error", message != null ? message : "Unknown error"); //$NON-NLS-1$ //$NON-NLS-2$
        return GSON.toJson(response);
    }
    
    /**
     * Builds a server info JSON response.
     * 
     * @param name server name
     * @param version plugin version
     * @param edtVersion EDT version
     * @param protocolVersion MCP protocol version
     * @return JSON response string
     */
    public static String buildServerInfo(String name, String version, String edtVersion, String protocolVersion)
    {
        JsonObject response = new JsonObject();
        response.addProperty("name", name); //$NON-NLS-1$
        response.addProperty("version", version); //$NON-NLS-1$
        response.addProperty("edt_version", edtVersion); //$NON-NLS-1$
        response.addProperty("protocol_version", protocolVersion); //$NON-NLS-1$
        response.addProperty("status", "running"); //$NON-NLS-1$ //$NON-NLS-2$
        return GSON.toJson(response);
    }
    
    /**
     * Builds a health check JSON response.
     * 
     * @param edtVersion EDT version
     * @return JSON response string
     */
    public static String buildHealthResponse(String edtVersion)
    {
        JsonObject response = new JsonObject();
        response.addProperty("status", "ok"); //$NON-NLS-1$ //$NON-NLS-2$
        response.addProperty("edt_version", edtVersion); //$NON-NLS-1$
        return GSON.toJson(response);
    }
    
    /**
     * Extracts a string argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @return value or null if not found
     */
    public static String extractStringArgument(Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        return params.get(argumentName);
    }
    
    /**
     * Extracts an array argument from params map.
     * The value can be a JSON array string like ["a", "b"] or a comma-separated string.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @return list of strings or null if not found
     */
    public static List<String> extractArrayArgument(Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return null;
        }
        
        value = value.trim();
        
        // Check if it's a JSON array
        if (value.startsWith("[")) //$NON-NLS-1$
        {
            try
            {
                JsonElement element = JsonParser.parseString(value);
                if (element.isJsonArray())
                {
                    JsonArray array = element.getAsJsonArray();
                    List<String> result = new ArrayList<>(array.size());
                    for (JsonElement el : array)
                    {
                        if (el.isJsonPrimitive())
                        {
                            result.add(el.getAsString());
                        }
                    }
                    return result;
                }
            }
            catch (Exception e)
            {
                // Fall through to comma-separated parsing
            }
        }
        
        // Parse as comma-separated
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            String trimmed = part.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        
        return result.isEmpty() ? null : result;
    }
    
    /**
     * Extracts a boolean argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @param defaultValue the default value if not found or invalid
     * @return boolean value or default
     */
    public static boolean extractBooleanArgument(Map<String, String> params, String argumentName, boolean defaultValue)
    {
        if (params == null || argumentName == null)
        {
            return defaultValue;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        
        value = value.trim().toLowerCase();
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return true;
        }
        else if ("false".equals(value) || "0".equals(value) || "no".equals(value)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            return false;
        }
        
        return defaultValue;
    }
    
    /**
     * Extracts an integer argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @param defaultValue the default value if not found or invalid
     * @return integer value or default
     */
    public static int extractIntArgument(Map<String, String> params, String argumentName, int defaultValue)
    {
        if (params == null || argumentName == null)
        {
            return defaultValue;
        }
        
        String value = params.get(argumentName);
        if (value == null || value.isEmpty())
        {
            return defaultValue;
        }
        
        try
        {
            // Handle both "1" and "1.0" (Gson converts JSON numbers to Double.toString())
            // "1" and "1.0" → 1
            // "1.1" → defaultValue
            // "abc" → defaultValue
            double d = Double.parseDouble(value.trim());
            if (d != Math.floor(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE)
            {
                return defaultValue;
            }
            return (int) d;
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }
}
