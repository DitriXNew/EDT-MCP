/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

/**
 * MCP protocol constants.
 */
public final class McpConstants
{
    /** MCP protocol version */
    public static final String PROTOCOL_VERSION = "2025-03-26"; //$NON-NLS-1$
    
    /** Server name */
    public static final String SERVER_NAME = "edt-mcp-server"; //$NON-NLS-1$
    
    /** Plugin author */
    public static final String AUTHOR = "DitriX"; //$NON-NLS-1$
    
    /** Plugin version - synced with Bundle-Version in MANIFEST.MF */
    public static final String PLUGIN_VERSION = "1.2.3"; //$NON-NLS-1$
    
    // JSON-RPC error codes
    /** Parse error */
    public static final int ERROR_PARSE = -32700;
    
    /** Invalid request */
    public static final int ERROR_INVALID_REQUEST = -32600;
    
    /** Method not found */
    public static final int ERROR_METHOD_NOT_FOUND = -32601;
    
    /** Invalid params */
    public static final int ERROR_INVALID_PARAMS = -32602;
    
    /** Internal error */
    public static final int ERROR_INTERNAL = -32603;
    
    // MCP methods
    /** Initialize method */
    public static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$
    
    /** Tools list method */
    public static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$
    
    /** Tools call method */
    public static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$
    
    private McpConstants()
    {
        // Utility class
    }
}
