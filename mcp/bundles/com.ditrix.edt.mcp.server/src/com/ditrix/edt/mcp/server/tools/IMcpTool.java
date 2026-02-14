/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
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
     * Response content type for tool results.
     */
    enum ResponseType
    {
        /** Plain text response */
        TEXT,
        /** JSON response with structuredContent */
        JSON,
        /** Markdown response returned as EmbeddedResource with mimeType */
        MARKDOWN
    }
    
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
     * @return result string (format depends on getResponseType())
     */
    String execute(Map<String, String> params);
    
    /**
     * Returns the response content type for this tool.
     * Default is MARKDOWN for better context efficiency.
     * 
     * @return response type
     */
    default ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    /**
     * Returns the result file name for EmbeddedResource URI.
     * Used when response type is MARKDOWN.
     * Default returns tool name with .md extension.
     * Override to provide dynamic file name based on parameters.
     * 
     * @param params the execution parameters
     * @return file name with extension (e.g., "begin-transaction.md")
     */
    default String getResultFileName(Map<String, String> params)
    {
        return getName() + ".md"; //$NON-NLS-1$
    }
}
