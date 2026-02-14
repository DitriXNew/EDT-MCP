/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Registry for MCP tools.
 * Manages registration and lookup of tools by name.
 */
public class McpToolRegistry
{
    private static final McpToolRegistry INSTANCE = new McpToolRegistry();
    
    private final Map<String, IMcpTool> tools = new ConcurrentHashMap<>();
    
    private McpToolRegistry()
    {
        // Private constructor for singleton
    }
    
    /**
     * Returns the singleton instance.
     * 
     * @return registry instance
     */
    public static McpToolRegistry getInstance()
    {
        return INSTANCE;
    }
    
    /**
     * Registers a tool.
     * 
     * @param tool the tool to register
     */
    public void register(IMcpTool tool)
    {
        if (tool == null || tool.getName() == null)
        {
            return;
        }
        tools.put(tool.getName(), tool);
        Activator.logInfo("Registered MCP tool: " + tool.getName()); //$NON-NLS-1$
    }
    
    /**
     * Unregisters a tool.
     * 
     * @param name the tool name
     */
    public void unregister(String name)
    {
        if (name != null)
        {
            tools.remove(name);
            Activator.logInfo("Unregistered MCP tool: " + name); //$NON-NLS-1$
        }
    }
    
    /**
     * Returns a tool by name.
     * 
     * @param name the tool name
     * @return tool or null if not found
     */
    public IMcpTool getTool(String name)
    {
        return tools.get(name);
    }
    
    /**
     * Returns all registered tools.
     * 
     * @return collection of tools
     */
    public Collection<IMcpTool> getAllTools()
    {
        return Collections.unmodifiableCollection(tools.values());
    }
    
    /**
     * Checks if a tool is registered.
     * 
     * @param name the tool name
     * @return true if registered
     */
    public boolean hasTool(String name)
    {
        return tools.containsKey(name);
    }
    
    /**
     * Returns the number of registered tools.
     * 
     * @return tool count
     */
    public int getToolCount()
    {
        return tools.size();
    }
    
    /**
     * Clears all registered tools.
     */
    public void clear()
    {
        tools.clear();
    }
}
