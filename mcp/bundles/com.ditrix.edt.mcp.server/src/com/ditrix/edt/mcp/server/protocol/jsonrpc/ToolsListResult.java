/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol.jsonrpc;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP tools/list response result.
 */
public class ToolsListResult
{
    private List<ToolInfo> tools = new ArrayList<>();
    
    public void addTool(String name, String description, Object inputSchema)
    {
        tools.add(new ToolInfo(name, description, inputSchema, null));
    }

    public void addTool(String name, String description, Object inputSchema, Object annotations)
    {
        tools.add(new ToolInfo(name, description, inputSchema, annotations));
    }

    public List<ToolInfo> getTools()
    {
        return tools;
    }

    /**
     * Tool info for tools/list response.
     */
    public static class ToolInfo
    {
        private String name;
        private String description;
        private Object inputSchema;
        private Object annotations;

        public ToolInfo(String name, String description, Object inputSchema)
        {
            this(name, description, inputSchema, null);
        }

        public ToolInfo(String name, String description, Object inputSchema, Object annotations)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.annotations = annotations;
        }

        public String getName()
        {
            return name;
        }

        public String getDescription()
        {
            return description;
        }

        public Object getInputSchema()
        {
            return inputSchema;
        }

        public Object getAnnotations()
        {
            return annotations;
        }
    }
}
