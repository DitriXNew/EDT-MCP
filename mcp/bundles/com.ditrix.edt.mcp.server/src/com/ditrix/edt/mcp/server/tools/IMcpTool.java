/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools;

import java.util.Map;

/**
 * Interface for MCP tool implementations.
 * Each tool provides a specific capability to MCP clients.
 */
public interface IMcpTool
{
    /**
     * Returns the unique name of the tool.
     * This name is used in MCP protocol to identify the tool.
     * 
     * @return tool name (e.g., "get_edt_version", "list_projects")
     */
    String getName();
    
    /**
     * Returns a human-readable description of the tool.
     * This description is sent to MCP clients in tools/list response.
     * 
     * @return tool description
     */
    String getDescription();
    
    /**
     * Returns the JSON Schema for input parameters.
     * Used by MCP clients to validate input before calling the tool.
     * 
     * @return input schema as JSON string
     */
    String getInputSchema();
    
    /**
     * Executes the tool with the given parameters.
     * 
     * @param params map of parameter name to value
     * @return result as JSON string
     */
    String execute(Map<String, String> params);
}
