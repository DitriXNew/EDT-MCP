/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * {@code getVisibleTools()} with progressive disclosure OFF (the headless default:
 * no Activator/preference store) must equal {@code getEnabledTools()} — the
 * no-regression guarantee that the default tool surface is unchanged by the
 * toolset machinery. The disclosure-ON filtering needs the live preference and is
 * verified by the e2e/live loop.
 */
public class McpToolRegistryVisibilityTest
{
    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
        ToolsetState.getInstance().reset();
    }

    @Test
    public void visibleEqualsEnabledWhenDisclosureOff()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        // Even with a toolset "revealed", disclosure is OFF headless, so the filter
        // is bypassed and the visible set is the full enabled set.
        ToolsetState.getInstance().enable(Toolsets.DEBUG);

        Set<String> enabled = names(registry.getEnabledTools());
        Set<String> visible = names(registry.getVisibleTools());
        assertEquals("visible must equal enabled when progressive disclosure is off", //$NON-NLS-1$
            enabled, visible);
    }

    @Test
    public void metaToolsAreRegisteredAndVisible()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        Set<String> visible = names(registry.getVisibleTools());
        assertTrue("list_toolsets must be visible", visible.contains("list_toolsets")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("enable_toolset must be visible", visible.contains("enable_toolset")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static Set<String> names(Iterable<IMcpTool> tools)
    {
        Set<String> result = new HashSet<>();
        for (IMcpTool tool : tools)
        {
            result.add(tool.getName());
        }
        return result;
    }
}
