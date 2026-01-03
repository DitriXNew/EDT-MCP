/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

/**
 * JSON utility methods.
 */
public final class JsonUtils
{
    private JsonUtils()
    {
        // Utility class
    }
    
    /**
     * Escapes special characters for JSON string.
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
     * Appends a localized string map (EMap) to JSON.
     * 
     * @param json StringBuilder to append to
     * @param map the EMap object
     */
    public static void appendLocalizedStringMap(StringBuilder json, Object map)
    {
        if (map == null)
        {
            json.append("{}"); //$NON-NLS-1$
            return;
        }
        
        try
        {
            // The map is EMap<String, String> from EMF
            if (map instanceof org.eclipse.emf.common.util.EMap)
            {
                @SuppressWarnings("unchecked")
                org.eclipse.emf.common.util.EMap<String, String> eMap = 
                    (org.eclipse.emf.common.util.EMap<String, String>) map;
                
                json.append("{"); //$NON-NLS-1$
                boolean first = true;
                for (java.util.Map.Entry<String, String> entry : eMap.entrySet())
                {
                    if (!first)
                    {
                        json.append(","); //$NON-NLS-1$
                    }
                    first = false;
                    json.append("\"").append(escapeJson(entry.getKey())).append("\": \"") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(escapeJson(entry.getValue())).append("\""); //$NON-NLS-1$
                }
                json.append("}"); //$NON-NLS-1$
            }
            else
            {
                json.append("\"").append(escapeJson(map.toString())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (Exception e)
        {
            json.append("{}"); //$NON-NLS-1$
        }
    }
    
    /**
     * Creates a JSON error response.
     * 
     * @param message error message
     * @return JSON error object string
     */
    public static String errorJson(String message)
    {
        return "{\"error\": \"" + escapeJson(message) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Extracts a string argument from JSON request body.
     * Simple parser for extracting "argumentName": "value" pairs.
     * 
     * @param requestBody the JSON body
     * @param argumentName the argument to extract
     * @return value or null if not found
     */
    public static String extractStringArgument(String requestBody, String argumentName)
    {
        try
        {
            // Look for "argumentName": "value" pattern
            String searchPattern = "\"" + argumentName + "\":"; //$NON-NLS-1$ //$NON-NLS-2$
            int idx = requestBody.indexOf(searchPattern);
            if (idx < 0)
            {
                return null;
            }
            
            // Find the value after the colon
            int valueStart = requestBody.indexOf("\"", idx + searchPattern.length()); //$NON-NLS-1$
            if (valueStart < 0)
            {
                return null;
            }
            
            int valueEnd = requestBody.indexOf("\"", valueStart + 1); //$NON-NLS-1$
            if (valueEnd < 0)
            {
                return null;
            }
            
            return requestBody.substring(valueStart + 1, valueEnd);
        }
        catch (Exception e)
        {
            return null;
        }
    }
    
    /**
     * Checks if the request body contains a specific method.
     * 
     * @param requestBody the JSON body
     * @param method the method name to check
     * @return true if method is present
     */
    public static boolean hasMethod(String requestBody, String method)
    {
        return requestBody.contains("\"method\"") && requestBody.contains("\"" + method + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
    
    /**
     * Checks if the request body contains a specific tool name in tools/call.
     * 
     * @param requestBody the JSON body
     * @param toolName the tool name to check
     * @return true if tool is present
     */
    public static boolean hasToolCall(String requestBody, String toolName)
    {
        return requestBody.contains("\"" + toolName + "\""); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Extracts a string argument from params map.
     * 
     * @param params the params map
     * @param argumentName the argument name to extract
     * @return value or null if not found
     */
    public static String extractStringArgument(java.util.Map<String, String> params, String argumentName)
    {
        if (params == null || argumentName == null)
        {
            return null;
        }
        return params.get(argumentName);
    }
}
