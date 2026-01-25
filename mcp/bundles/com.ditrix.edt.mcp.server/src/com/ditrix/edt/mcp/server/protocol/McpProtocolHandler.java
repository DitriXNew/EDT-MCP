/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.UserSignal;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.InitializeResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcRequest;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.JsonRpcResponse;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolCallResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolsListResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Handles MCP JSON-RPC protocol messages.
 * Supports Streamable HTTP transport as per MCP 2025-03-26 specification.
 * Uses GsonProvider for JSON serialization/deserialization.
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
     * @return JSON response with correct id from request
     */
    public String processRequest(String requestBody)
    {
        Object requestId = 1; // Default id
        
        try
        {
            // Parse request using GsonProvider
            JsonRpcRequest request = parseRequest(requestBody);
            if (request != null && request.getId() != null)
            {
                requestId = request.getId();
            }
            
            // Validate JSON-RPC version
            if (request == null || !McpConstants.JSONRPC_VERSION.equals(request.getJsonrpc()))
            {
                return buildErrorResponse(McpConstants.ERROR_INVALID_REQUEST, 
                    "Invalid JSON-RPC version, expected 2.0", requestId); //$NON-NLS-1$
            }
            
            String method = request.getMethod();
            
            // Check for initialize method
            if (McpConstants.METHOD_INITIALIZE.equals(method))
            {
                return buildInitializeResponse(requestId);
            }
            
            // Check for initialized notification (no response needed, but return 202)
            if (McpConstants.METHOD_INITIALIZED.equals(method))
            {
                return null; // Signal for 202 Accepted with no body
            }
            
            // Check for tools/list method
            if (McpConstants.METHOD_TOOLS_LIST.equals(method))
            {
                return buildToolsListResponse(requestId);
            }
            
            // Check for tools/call method
            if (McpConstants.METHOD_TOOLS_CALL.equals(method))
            {
                return handleToolCall(request, requestId);
            }
            
            // Method not found
            return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Method not found", requestId); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error processing MCP request", e); //$NON-NLS-1$
            return buildErrorResponse(McpConstants.ERROR_INTERNAL, e.getMessage(), requestId);
        }
    }
    
    /**
     * Parses JSON-RPC request using GsonProvider.
     */
    private JsonRpcRequest parseRequest(String requestBody)
    {
        try
        {
            return GsonProvider.fromJson(requestBody, JsonRpcRequest.class);
        }
        catch (JsonSyntaxException e)
        {
            Activator.logError("Failed to parse JSON-RPC request", e); //$NON-NLS-1$
            return null;
        }
    }
    
    /**
     * Handles a tools/call request.
     */
    private String handleToolCall(JsonRpcRequest request, Object requestId)
    {
        String toolName = request != null ? request.getToolName() : null;
        
        // Find tool by name
        IMcpTool tool = toolRegistry.getTool(toolName);
        if (tool == null)
        {
            return buildErrorResponse(McpConstants.ERROR_METHOD_NOT_FOUND, "Tool not found: " + toolName, requestId); //$NON-NLS-1$
        }
        
        Activator.logInfo("Processing tools/call: " + tool.getName()); //$NON-NLS-1$
        
        // Extract parameters from request arguments
        Map<String, String> params = extractToolParams(request);
        
        // Set current tool name for status bar display
        McpServer server = Activator.getDefault() != null ? Activator.getDefault().getMcpServer() : null;
        if (server != null)
        {
            server.setCurrentToolName(tool.getName());
        }
        
        // Execute tool
        String result;
        try
        {
            result = tool.execute(params);
        }
        finally
        {
            // Clear current tool name after execution
            if (server != null)
            {
                server.setCurrentToolName(null);
            }
        }
        
        // Check if user sent a signal during execution
        UserSignal signal = null;
        if (server != null)
        {
            signal = server.consumeUserSignal();
        }
        
        // Check if plain text mode is enabled (Cursor compatibility)
        boolean plainTextMode = Activator.getDefault().getPreferenceStore()
            .getBoolean(PreferenceConstants.PREF_PLAIN_TEXT_MODE);
        
        // Return response based on tool's declared response type
        switch (tool.getResponseType())
        {
            case JSON:
                // For JSON, add signal as a separate field if present
                if (signal != null)
                {
                    // Parse JSON and add userSignal field
                    result = addUserSignalToJson(result, signal);
                }
                return buildToolCallJsonResponse(result, requestId);
            case MARKDOWN:
                // Append user signal as markdown
                if (signal != null)
                {
                    result = result + "\n\n---\n**USER SIGNAL:** " + signal.getMessage();
                }
                // In plain text mode, return markdown as plain text instead of embedded resource
                if (plainTextMode)
                {
                    return buildToolCallTextResponse(result, requestId);
                }
                String fileName = tool.getResultFileName(params);
                return buildToolCallResourceResponse(result, "text/markdown", fileName, requestId); //$NON-NLS-1$
            case TEXT:
            default:
                // Append user signal as text
                if (signal != null)
                {
                    result = result + "\n\n---\nUSER SIGNAL: " + signal.getMessage();
                }
                return buildToolCallTextResponse(result, requestId);
        }
    }
    
    /**
     * Adds user signal to a JSON result string using Gson for proper JSON handling.
     */
    private String addUserSignalToJson(String jsonResult, UserSignal signal)
    {
        try
        {
            // Parse the original JSON
            JsonElement element = JsonParser.parseString(jsonResult);
            if (element.isJsonObject())
            {
                com.google.gson.JsonObject jsonObject = element.getAsJsonObject();
                
                // Create userSignal object
                com.google.gson.JsonObject signalObject = new com.google.gson.JsonObject();
                signalObject.addProperty("type", signal.getType().name());
                signalObject.addProperty("message", signal.getMessage());
                
                // Add to result
                jsonObject.add("userSignal", signalObject);
                
                return new com.google.gson.Gson().toJson(jsonObject);
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to add user signal to JSON", e);
        }
        return jsonResult;
    }
    
    /**
     * Extracts tool parameters from request.
     */
    private Map<String, String> extractToolParams(JsonRpcRequest request)
    {
        Map<String, String> params = new HashMap<>();
        
        Map<String, Object> arguments = request != null ? request.getArguments() : null;
        if (arguments == null)
        {
            return params;
        }
        
        // Convert all arguments to strings
        for (Map.Entry<String, Object> entry : arguments.entrySet())
        {
            Object value = entry.getValue();
            if (value != null)
            {
                if (value instanceof List || value instanceof Map)
                {
                    // Serialize complex types back to JSON
                    params.put(entry.getKey(), GsonProvider.toJson(value));
                }
                else
                {
                    params.put(entry.getKey(), value.toString());
                }
            }
        }
        
        return params;
    }
    
    /**
     * Builds initialize response.
     */
    private String buildInitializeResponse(Object requestId)
    {
        InitializeResult result = new InitializeResult(
            McpConstants.PROTOCOL_VERSION,
            McpConstants.SERVER_NAME,
            McpConstants.PLUGIN_VERSION,
            McpConstants.AUTHOR
        );
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }
    
    /**
     * Builds tools/list response dynamically from registry.
     */
    private String buildToolsListResponse(Object requestId)
    {
        ToolsListResult result = new ToolsListResult();
        
        for (IMcpTool tool : toolRegistry.getAllTools())
        {
            // Parse inputSchema from JSON string to JsonElement
            JsonElement schema = JsonParser.parseString(tool.getInputSchema());
            result.addTool(tool.getName(), tool.getDescription(), schema);
        }
        
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, result));
    }
    
    /**
     * Builds tool call response for text result.
     */
    private String buildToolCallTextResponse(String result, Object requestId)
    {
        ToolCallResult toolResult = ToolCallResult.text(result);
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }
    
    /**
     * Builds tool call response for JSON result.
     * Uses structuredContent per MCP 2025-11-25.
     */
    private String buildToolCallJsonResponse(String jsonResult, Object requestId)
    {
        // Parse the JSON string to JsonElement for proper nesting
        JsonElement structured = JsonParser.parseString(jsonResult);
        ToolCallResult toolResult = ToolCallResult.json(structured);
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }
    
    /**
     * Builds tool call response for resource with MIME type (e.g., Markdown).
     */
    private String buildToolCallResourceResponse(String content, String mimeType, String fileName, Object requestId)
    {
        ToolCallResult toolResult = ToolCallResult.resource("embedded://" + fileName, mimeType, content); //$NON-NLS-1$
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }
    
    /**
     * Builds error response.
     */
    private String buildErrorResponse(int code, String message, Object requestId)
    {
        return GsonProvider.toJson(JsonRpcResponse.error(requestId, code, message));
    }
}
