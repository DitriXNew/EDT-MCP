/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.protocol;

/**
 * MCP protocol constants.
 * Implements MCP 2025-11-25 specification.
 */
public final class McpConstants
{
    /** JSON-RPC version */
    public static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$
    
    /** MCP protocol version - updated to 2025-11-25 */
    public static final String PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$
    
    /** Server name */
    public static final String SERVER_NAME = "edt-mcp-server"; //$NON-NLS-1$
    
    /** Plugin author */
    public static final String AUTHOR = "DitriX"; //$NON-NLS-1$
    
    /** Plugin version - synced with Bundle-Version in MANIFEST.MF */
    public static final String PLUGIN_VERSION = "1.22.0"; //$NON-NLS-1$
    
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
    
    // HTTP Headers
    /** MCP Protocol Version header */
    public static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"; //$NON-NLS-1$
    
    /** MCP Session ID header */
    public static final String HEADER_SESSION_ID = "MCP-Session-Id"; //$NON-NLS-1$
    
    // MCP methods
    /** Initialize method */
    public static final String METHOD_INITIALIZE = "initialize"; //$NON-NLS-1$
    
    /** Initialized notification */
    public static final String METHOD_INITIALIZED = "notifications/initialized"; //$NON-NLS-1$
    
    /** Tools list method */
    public static final String METHOD_TOOLS_LIST = "tools/list"; //$NON-NLS-1$
    
    /** Tools call method */
    public static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$
    
    private McpConstants()
    {
        // Utility class
    }
}
