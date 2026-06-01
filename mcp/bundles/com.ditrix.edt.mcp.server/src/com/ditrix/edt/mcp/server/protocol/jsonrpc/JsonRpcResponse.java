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
     * <p>
     * When {@code id} is null (the request id could not be determined, e.g. a
     * parse error or invalid request), the shared Gson instance omits the id
     * field from the wire JSON. The important invariant is that no fabricated id
     * (e.g. 1) is returned, which could be mis-correlated to a pending request.
     * Emitting a strict {@code "id":null} per JSON-RPC 2.0 requires envelope-level
     * serialization changes and is folded into the A9-wire e2e task.
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
