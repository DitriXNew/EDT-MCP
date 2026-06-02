/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
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
        // Per JSON-RPC 2.0: when the id cannot be determined (parse error /
        // invalid request) the error response id MUST be null. A real id from
        // the parsed request overwrites this below.
        Object requestId = null;
        
        try
        {
            // Parse request using GsonProvider
            JsonRpcRequest request = parse(requestBody);
            if (request != null)
            {
                requestId = normalizeId(request.getId());
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
                // Per spec: echo back the client's requested protocol version if it is a
                // known/supported version; otherwise, fall back to our latest version.
                String clientVersion = request.getStringParam("protocolVersion"); //$NON-NLS-1$
                return buildInitializeResponse(requestId, clientVersion);
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
     * Parses a JSON-RPC request using GsonProvider. Shared by both the protocol
     * dispatch path ({@link #processRequest}) and the transport's interruptible
     * tool executor, so JSON id/name extraction lives in one place.
     *
     * @param requestBody the raw request body
     * @return the parsed request, or {@code null} on a JSON syntax error
     */
    public JsonRpcRequest parse(String requestBody)
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
     * Normalizes a JSON-RPC request id. Gson deserializes JSON numbers into
     * {@code Object} fields as {@link Double}; whole-number Doubles are converted
     * to {@link Long} so {@code "id":0} serializes back as {@code 0} (not
     * {@code 0.0}), which is required for JSON-RPC id matching. Strings, nulls,
     * and non-whole numbers are returned unchanged.
     *
     * @param id the raw id from a parsed request (may be {@code null})
     * @return the normalized id
     */
    public static Object normalizeId(Object id)
    {
        if (id instanceof Double)
        {
            double d = (Double) id;
            if (!Double.isInfinite(d) && d == Math.floor(d)
                && d >= Long.MIN_VALUE && d <= Long.MAX_VALUE)
            {
                return ((Double) id).longValue();
            }
        }
        return id;
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

        // Check if tool is enabled
        if (!toolRegistry.isToolEnabled(toolName))
        {
            String msg = "Tool '" + toolName + "' is disabled by the user. " //$NON-NLS-1$ //$NON-NLS-2$
                + "If this functionality is needed, ask the user to enable it: " //$NON-NLS-1$
                + "EDT Preferences \u2192 MCP Server \u2192 Tools tab \u2192 check '" + toolName + "'."; //$NON-NLS-1$ //$NON-NLS-2$
            return buildToolCallTextResponse(msg, requestId);
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
        
        // Check if plain text mode is enabled (Cursor compatibility).
        // Activator.getDefault() can be null during a shutdown race; in that
        // case fall back to the safe default (structured content, not plain text).
        boolean plainTextMode = Activator.getDefault() != null
            && Activator.getDefault().getPreferenceStore()
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
                // In plain text mode, return markdown as plain text instead of structured content
                if (plainTextMode)
                {
                    return buildToolCallTextResponse(result, requestId);
                }
                return buildToolCallJsonResponse(result, requestId);
            case MARKDOWN:
                // A ToolResult.error JSON payload is delivered as a structured JSON
                // error (isError:true) instead of a markdown resource, so failures
                // are machine-detectable regardless of the declared response type.
                if (isJsonErrorPayload(result))
                {
                    return buildToolCallJsonResponse(result, requestId);
                }
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
            case YAML:
                // Same delivery as MARKDOWN (error diversion, signal append, plain
                // text fallback) but the embedded resource advertises a YAML
                // mimeType so it agrees with the .yaml resource URI and the body.
                if (isJsonErrorPayload(result))
                {
                    return buildToolCallJsonResponse(result, requestId);
                }
                if (signal != null)
                {
                    result = result + "\n\n---\n# USER SIGNAL: " + signal.getMessage();
                }
                if (plainTextMode)
                {
                    return buildToolCallTextResponse(result, requestId);
                }
                String yamlFileName = tool.getResultFileName(params);
                return buildToolCallResourceResponse(result, "text/yaml", yamlFileName, requestId); //$NON-NLS-1$
            case IMAGE:
                // Images always returned as embedded resource (ignore plain text mode)
                // For images, user signals are ignored
                if (isJsonErrorPayload(result))
                {
                    return buildToolCallJsonResponse(result, requestId);
                }
                String imageFileName = tool.getResultFileName(params);
                return buildToolCallResourceBlobResponse(result, "image/png", imageFileName, requestId); //$NON-NLS-1$
            case TEXT:
            default:
                // See MARKDOWN: a ToolResult.error JSON payload is delivered as a
                // structured JSON error regardless of the declared response type.
                if (isJsonErrorPayload(result))
                {
                    return buildToolCallJsonResponse(result, requestId);
                }
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
     * Echoes back the client's requested protocol version (per spec) if it is a
     * recognized date-format version; otherwise uses our latest version.
     */
    private String buildInitializeResponse(Object requestId, String clientVersion)
    {
        // Use the client's version if it looks like a valid MCP version date (YYYY-MM-DD),
        // otherwise fall back to our supported version.
        String version = (clientVersion != null && clientVersion.matches("\\d{4}-\\d{2}-\\d{2}")) //$NON-NLS-1$
            ? clientVersion : McpConstants.PROTOCOL_VERSION;
        InitializeResult result = new InitializeResult(
            version,
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
        
        for (IMcpTool tool : toolRegistry.getEnabledTools())
        {
            // Parse inputSchema from JSON string to JsonElement
            JsonElement schema = JsonParser.parseString(tool.getInputSchema());
            // A tool may supply explicit annotations; otherwise the central
            // classifier derives the MCP behavioral hints from the tool name.
            Object annotations = tool.getAnnotations() != null
                ? tool.getAnnotations()
                : ToolAnnotationClassifier.classify(tool.getName());
            result.addTool(tool.getName(), tool.getDescription(), schema, annotations);
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
     * Uses structuredContent per MCP 2025-11-25. A {@code ToolResult.error} JSON
     * payload (success:false / error field) is flagged with {@code isError:true}
     * so MCP clients can detect a tool-level failure instead of treating every
     * tools/call as successful.
     */
    private String buildToolCallJsonResponse(String jsonResult, Object requestId)
    {
        // Parse the JSON string to JsonElement for proper nesting
        JsonElement structured = JsonParser.parseString(jsonResult);
        boolean isError = isJsonErrorPayload(jsonResult);
        ToolCallResult toolResult = ToolCallResult.json(structured, isError);
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
     * Builds tool call response for resource with blob data (e.g., images).
     */
    private String buildToolCallResourceBlobResponse(String base64Blob, String mimeType, String fileName, Object requestId)
    {
        ToolCallResult toolResult = ToolCallResult.resourceBlob("embedded://" + fileName, mimeType, base64Blob); //$NON-NLS-1$
        return GsonProvider.toJson(JsonRpcResponse.success(requestId, toolResult));
    }

    /**
     * Checks whether tool result is a JSON error payload (ToolResult.error JSON).
     */
    private boolean isJsonErrorPayload(String result)
    {
        if (result == null)
        {
            return false;
        }

        try
        {
            JsonElement element = JsonParser.parseString(result);
            if (!element.isJsonObject())
            {
                return false;
            }

            com.google.gson.JsonObject obj = element.getAsJsonObject();
            if (obj.has("success") && obj.get("success").isJsonPrimitive()
                && obj.get("success").getAsJsonPrimitive().isBoolean()
                && !obj.get("success").getAsBoolean())
            {
                return true;
            }

            return obj.has("error");
        }
        catch (Exception e)
        {
            return false;
        }
    }
    
    /**
     * Builds error response.
     */
    private String buildErrorResponse(int code, String message, Object requestId)
    {
        if (requestId == null)
        {
            // JSON-RPC 2.0: when the request id cannot be determined (parse error /
            // invalid request) the error response MUST carry id:null. The shared
            // Gson omits null fields, so build this envelope explicitly with a
            // serialize-nulls writer. Only this path is affected; every id-bearing
            // response keeps the normal (null-omitting) shape.
            com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
            envelope.addProperty("jsonrpc", McpConstants.JSONRPC_VERSION); //$NON-NLS-1$
            envelope.add("id", com.google.gson.JsonNull.INSTANCE); //$NON-NLS-1$
            com.google.gson.JsonObject err = new com.google.gson.JsonObject();
            err.addProperty("code", code); //$NON-NLS-1$
            if (message != null)
            {
                err.addProperty("message", message); //$NON-NLS-1$
            }
            envelope.add("error", err); //$NON-NLS-1$
            return GsonProvider.toJsonSerializeNulls(envelope);
        }
        return GsonProvider.toJson(JsonRpcResponse.error(requestId, code, message));
    }
}
