/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

/**
 * JSON-RPC 2.0 error object.
 */
public class JsonRpcError
{
    private int code;
    private String message;
    
    public JsonRpcError(int code, String message)
    {
        this.code = code;
        this.message = message;
    }
    
    public int getCode()
    {
        return code;
    }
    
    public String getMessage()
    {
        return message;
    }
}
