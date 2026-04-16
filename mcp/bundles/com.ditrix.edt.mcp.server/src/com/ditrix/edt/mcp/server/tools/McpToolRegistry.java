/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolSettingsService;

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
     * Unregisters a tool by name.
     *
     * @param name the tool name to remove
     */
    public void unregister(String name)
    {
        if (name != null)
        {
            tools.remove(name);
        }
    }

    /**
     * Returns all registered tools (regardless of enablement state).
     *
     * @return collection of all tools
     */
    public Collection<IMcpTool> getAllTools()
    {
        return Collections.unmodifiableCollection(tools.values());
    }

    /**
     * Returns only enabled tools (filtered by ToolSettingsService).
     * This is the method used by the MCP protocol handler to determine
     * which tools are exposed to clients.
     *
     * @return collection of enabled tools
     */
    public Collection<IMcpTool> getEnabledTools()
    {
        Set<String> disabled = ToolSettingsService.getInstance().getDisabledTools();
        if (disabled.isEmpty())
        {
            return Collections.unmodifiableCollection(tools.values());
        }
        return tools.values().stream()
            .filter(tool -> !disabled.contains(tool.getName()))
            .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Checks whether a registered tool is currently enabled.
     *
     * @param name the tool name
     * @return true if the tool is registered and enabled
     */
    public boolean isToolEnabled(String name)
    {
        if (name == null)
        {
            return false;
        }
        return tools.containsKey(name)
            && ToolSettingsService.getInstance().isToolEnabled(name);
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
