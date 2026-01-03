/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

import java.util.HashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Handles MCP JSON-RPC protocol messages.
 */
public class McpProtocolHandler
{
    private final McpToolRegistry toolRegistry;
    
    /**
     * Creates a new protocol handler.
     */
    public McpProtocolHandler()
    {
        this.toolRegistry = McpToolRegistry.getInstance();
    }
    
    /**
     * Processes an MCP JSON-RPC request.
     * 
     * @param requestBody the JSON request body
     * @return JSON response
     */
    public String processRequest(String requestBody)
    {
        try
        {
            // Check for initialize method
            if (JsonUtils.hasMethod(requestBody, McpConstants.METHOD_INITIALIZE))
            {
                return buildInitializeResponse();
            }
            
            // Check for tools/list method
            if (JsonUtils.hasMethod(requestBody, McpConstants.METHOD_TOOLS_LIST))
            {
                return buildToolsListResponse();
            }
            
            // Check for tools/call method
            if (JsonUtils.hasMethod(requestBody, McpConstants.METHOD_TOOLS_CALL))
            {
                return handleToolCall(requestBody);
            }
            
            // Method not found
            return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Method not found"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error processing MCP request", e); //$NON-NLS-1$
            return buildErrorResponse(McpConstants.ERROR_INTERNAL, e.getMessage());
        }
    }
    
    /**
     * Handles a tools/call request.
     */
    private String handleToolCall(String requestBody)
    {
        // Find which tool is being called
        for (IMcpTool tool : toolRegistry.getAllTools())
        {
            if (JsonUtils.hasToolCall(requestBody, tool.getName()))
            {
                Activator.logInfo("Processing tools/call: " + tool.getName()); //$NON-NLS-1$
                
                // Extract parameters
                Map<String, String> params = extractToolParams(requestBody, tool);
                
                // Execute tool
                String result = tool.execute(params);
                
                // Return response based on whether result is JSON
                if (isJsonResult(result))
                {
                    return buildToolCallJsonResponse(result);
                }
                else
                {
                    return buildToolCallTextResponse(result);
                }
            }
        }
        
        // Tool not found
        return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Tool not found"); //$NON-NLS-1$
    }
    
    /**
     * Extracts tool parameters from request.
     */
    private Map<String, String> extractToolParams(String requestBody, IMcpTool tool)
    {
        Map<String, String> params = new HashMap<>();
        
        // Extract all common parameters
        String[] paramNames = {
            "projectName", "typeName", "errorType", "severity", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "checkId", "filePath", "priority", "limit", "clean" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        };
        
        for (String paramName : paramNames)
        {
            String value = JsonUtils.extractStringArgument(requestBody, paramName);
            if (value != null)
            {
                params.put(paramName, value);
            }
        }
        
        return params;
    }
    
    /**
     * Checks if result looks like JSON.
     */
    private boolean isJsonResult(String result)
    {
        if (result == null || result.isEmpty())
        {
            return false;
        }
        String trimmed = result.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || //$NON-NLS-1$ //$NON-NLS-2$
               (trimmed.startsWith("[") && trimmed.endsWith("]")); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Builds initialize response.
     */
    private String buildInitializeResponse()
    {
        return "{\"jsonrpc\": \"2.0\", \"result\": {" + //$NON-NLS-1$
            "\"protocolVersion\": \"" + McpConstants.PROTOCOL_VERSION + "\"," + //$NON-NLS-1$ //$NON-NLS-2$
            "\"capabilities\": {\"tools\": {}}," + //$NON-NLS-1$
            "\"serverInfo\": {\"name\": \"" + McpConstants.SERVER_NAME + "\", " + //$NON-NLS-1$ //$NON-NLS-2$
            "\"version\": \"" + McpConstants.PLUGIN_VERSION + "\", " + //$NON-NLS-1$ //$NON-NLS-2$
            "\"author\": \"" + McpConstants.AUTHOR + "\"}" + //$NON-NLS-1$ //$NON-NLS-2$
            "}, \"id\": 1}"; //$NON-NLS-1$
    }
    
    /**
     * Builds tools/list response dynamically from registry.
     */
    private String buildToolsListResponse()
    {
        StringBuilder json = new StringBuilder();
        json.append("{\"jsonrpc\": \"2.0\", \"result\": {\"tools\": ["); //$NON-NLS-1$
        
        boolean first = true;
        for (IMcpTool tool : toolRegistry.getAllTools())
        {
            if (!first)
            {
                json.append(","); //$NON-NLS-1$
            }
            first = false;
            
            json.append("{"); //$NON-NLS-1$
            json.append("\"name\": \"").append(JsonUtils.escapeJson(tool.getName())).append("\", "); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"description\": \"").append(JsonUtils.escapeJson(tool.getDescription())).append("\", "); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"inputSchema\": ").append(tool.getInputSchema()); //$NON-NLS-1$
            json.append("}"); //$NON-NLS-1$
        }
        
        json.append("]}, \"id\": 1}"); //$NON-NLS-1$
        return json.toString();
    }
    
    /**
     * Builds tool call response for text result.
     */
    private String buildToolCallTextResponse(String result)
    {
        return "{\"jsonrpc\": \"2.0\", \"result\": {" + //$NON-NLS-1$
            "\"content\": [{\"type\": \"text\", \"text\": \"" + JsonUtils.escapeJson(result) + "\"}]" + //$NON-NLS-1$ //$NON-NLS-2$
            "}, \"id\": 1}"; //$NON-NLS-1$
    }
    
    /**
     * Builds tool call response for JSON result.
     */
    private String buildToolCallJsonResponse(String jsonResult)
    {
        return "{\"jsonrpc\": \"2.0\", \"result\": {" + //$NON-NLS-1$
            "\"content\": [{\"type\": \"text\", \"text\": " + jsonResult + "}]" + //$NON-NLS-1$ //$NON-NLS-2$
            "}, \"id\": 1}"; //$NON-NLS-1$
    }
    
    /**
     * Builds error response.
     */
    private String buildErrorResponse(int code, String message)
    {
        return "{\"jsonrpc\": \"2.0\", \"error\": {\"code\": " + code + //$NON-NLS-1$
            ", \"message\": \"" + JsonUtils.escapeJson(message) + "\"}, \"id\": null}"; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
