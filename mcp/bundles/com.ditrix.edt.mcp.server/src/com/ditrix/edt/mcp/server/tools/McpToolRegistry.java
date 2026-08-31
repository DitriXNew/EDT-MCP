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
public class McpToolRegistry // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    /** Legacy call names accepted by {@link #getTool(String)} but never advertised. */
    private static final Map<String, String> LEGACY_TOOL_NAMES = Map.of(
        "debug_launch", "launch"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final McpToolRegistry INSTANCE = new McpToolRegistry();

    /**
     * The live catalogue, REPLACED as a whole rather than emptied and refilled.
     * <p>
     * Readers - {@code tools/list}, {@code tools/call} and the in-process bridge - run
     * concurrently with a server restart, which re-registers every tool. Refilling in place
     * would let them observe an empty or half-populated catalogue and report an existing tool
     * as unknown, so a bulk update publishes a finished map instead: every reader sees either
     * the whole previous catalogue or the whole new one.
     */
    private volatile Map<String, IMcpTool> tools = new ConcurrentHashMap<>();

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
     * Replaces the whole catalogue in one step.
     * <p>
     * This is what a (re)start must use instead of {@link #clear()} followed by
     * {@link #register(IMcpTool)} per tool: a concurrent {@code tools/list} would otherwise
     * see the gap between the two and answer with a partial catalogue, and a concurrent
     * {@code tools/call} would report a tool that exists as unknown.
     *
     * @param replacement the tools to publish; {@code null} entries and unnamed tools are
     *            ignored, a {@code null} collection empties the registry
     */
    public void replaceAll(Collection<IMcpTool> replacement)
    {
        Map<String, IMcpTool> published = new ConcurrentHashMap<>();
        if (replacement != null)
        {
            for (IMcpTool tool : replacement)
            {
                if (tool == null || tool.getName() == null)
                {
                    continue;
                }
                published.put(tool.getName(), tool);
                Activator.logInfo("Registered MCP tool: " + tool.getName()); //$NON-NLS-1$
            }
        }
        // The single point where readers switch over, and they switch over completely.
        tools = published;
    }

    /**
     * Returns a tool by name.
     * 
     * @param name the tool name
     * @return tool or null if not found
     */
    public IMcpTool getTool(String name)
    {
        IMcpTool tool = tools.get(name);
        if (tool != null)
        {
            return tool;
        }
        String currentName = LEGACY_TOOL_NAMES.get(name);
        return currentName == null ? null : tools.get(currentName);
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
     * Returns the tools VISIBLE in {@code tools/list} (and {@code resources/list}).
     * <p>
     * When progressive tool disclosure is off (the default) this is exactly
     * {@link #getEnabledTools()} — no behavior change. When it is on, the result is
     * further narrowed to tools whose {@link Toolsets toolset} is currently visible
     * ({@link Toolsets#CORE} plus the toolsets revealed via {@code enable_toolset},
     * see {@link ToolsetState}), shrinking the always-loaded surface. Visibility
     * never affects callability: a hidden tool is still registered and can be called
     * by name (the protocol handler gates calls on {@link #isToolEnabled} only).
     *
     * @return the visible tools
     */
    public Collection<IMcpTool> getVisibleTools()
    {
        Collection<IMcpTool> enabled = getEnabledTools();
        if (!Toolsets.isProgressiveDisclosureEnabled())
        {
            return enabled;
        }
        ToolsetState state = ToolsetState.getInstance();
        return enabled.stream()
            .filter(tool -> state.isVisible(Toolsets.toolsetOf(tool.getName())))
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
        IMcpTool tool = getTool(name);
        return tool != null
            && ToolSettingsService.getInstance().isToolEnabled(tool.getName());
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
     * <p>
     * To REPLACE the catalogue use {@link #replaceAll(Collection)}: clearing and re-registering
     * exposes the empty state in between.
     */
    public void clear()
    {
        tools = new ConcurrentHashMap<>();
    }
}
