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
        tools.add(new ToolInfo(name, description, inputSchema, null, null));
    }

    public void addTool(String name, String description, Object inputSchema, Object annotations)
    {
        tools.add(new ToolInfo(name, description, inputSchema, annotations, null));
    }

    public void addTool(String name, String description, Object inputSchema, Object annotations,
        Object outputSchema)
    {
        tools.add(new ToolInfo(name, description, inputSchema, annotations, outputSchema));
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
        // Serialized into tools/list only when non-null (the shared Gson omits null
        // fields), so TEXT/MARKDOWN tools without structured output emit no outputSchema.
        private Object outputSchema;

        public ToolInfo(String name, String description, Object inputSchema)
        {
            this(name, description, inputSchema, null, null);
        }

        public ToolInfo(String name, String description, Object inputSchema, Object annotations)
        {
            this(name, description, inputSchema, annotations, null);
        }

        public ToolInfo(String name, String description, Object inputSchema, Object annotations,
            Object outputSchema)
        {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.annotations = annotations;
            this.outputSchema = outputSchema;
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

        public Object getOutputSchema()
        {
            return outputSchema;
        }
    }
}
