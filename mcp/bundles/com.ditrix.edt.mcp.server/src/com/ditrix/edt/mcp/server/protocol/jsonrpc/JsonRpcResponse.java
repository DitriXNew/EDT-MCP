/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

/**
 * JSON-RPC 2.0 response object.
 */
public class JsonRpcResponse
{
    private final String jsonrpc = "2.0"; //$NON-NLS-1$
    private Object id;
    private Object result;
    private JsonRpcError error;
    
    private JsonRpcResponse()
    {
    }
    
    /**
     * Creates a success response with result.
     */
    public static JsonRpcResponse success(Object id, Object result)
    {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.result = result;
        return response;
    }
    
    /**
     * Creates an error response.
     */
    public static JsonRpcResponse error(Object id, int code, String message)
    {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.error = new JsonRpcError(code, message);
        return response;
    }
    
    public String getJsonrpc()
    {
        return jsonrpc;
    }
    
    public Object getId()
    {
        return id;
    }
    
    public Object getResult()
    {
        return result;
    }
    
    public JsonRpcError getError()
    {
        return error;
    }
}
