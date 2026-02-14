/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import java.util.Map;

/**
 * JSON-RPC 2.0 request object.
 */
public class JsonRpcRequest
{
    private String jsonrpc;
    private Object id;
    private String method;
    private Map<String, Object> params;
    
    public String getJsonrpc()
    {
        return jsonrpc;
    }
    
    public void setJsonrpc(String jsonrpc)
    {
        this.jsonrpc = jsonrpc;
    }
    
    public Object getId()
    {
        return id;
    }
    
    public void setId(Object id)
    {
        this.id = id;
    }
    
    public String getMethod()
    {
        return method;
    }
    
    public void setMethod(String method)
    {
        this.method = method;
    }
    
    public Map<String, Object> getParams()
    {
        return params;
    }
    
    public void setParams(Map<String, Object> params)
    {
        this.params = params;
    }
    
    /**
     * Gets a string parameter value.
     */
    public String getStringParam(String name)
    {
        if (params == null)
        {
            return null;
        }
        Object value = params.get(name);
        return value != null ? value.toString() : null;
    }
    
    /**
     * Gets the nested "arguments" map from params (for tools/call).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getArguments()
    {
        if (params == null)
        {
            return null;
        }
        Object args = params.get("arguments"); //$NON-NLS-1$
        if (args instanceof Map)
        {
            return (Map<String, Object>) args;
        }
        return null;
    }
    
    /**
     * Gets the tool name from params.name (for tools/call).
     */
    public String getToolName()
    {
        return getStringParam("name"); //$NON-NLS-1$
    }
}
