/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * Catalogue ratchet for {@link Toolsets}: every registered tool must be EXPLICITLY
 * assigned to a toolset, and every assigned name must be a real registered tool —
 * so a new or renamed tool fails the build until it is categorized, and the
 * catalogue cannot reference a tool that no longer exists.
 */
public class ToolsetsTest
{
    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void everyRegisteredToolIsExplicitlyMapped()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> unmapped = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            if (!Toolsets.isExplicitlyMapped(tool.getName()))
            {
                unmapped.add(tool.getName());
            }
        }
        assertTrue("Registered tools not assigned to any toolset in Toolsets (categorize them): " //$NON-NLS-1$
            + unmapped, unmapped.isEmpty());
    }

    @Test
    public void everyMappedToolIsRegistered()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        Set<String> registered = new HashSet<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            registered.add(tool.getName());
        }

        List<String> stale = new ArrayList<>();
        for (Toolsets.Toolset ts : Toolsets.all())
        {
            for (String name : Toolsets.toolNamesOf(ts.getId()))
            {
                if (!registered.contains(name))
                {
                    stale.add(name);
                }
            }
        }
        assertTrue("Toolsets maps names that are not registered tools (typo / removed tool?): " //$NON-NLS-1$
            + stale, stale.isEmpty());
    }

    @Test
    public void coreToolsetExistsAndCarriesTheMetaTools()
    {
        assertTrue(Toolsets.exists(Toolsets.CORE));
        List<String> core = Toolsets.toolNamesOf(Toolsets.CORE);
        assertTrue("core must contain list_toolsets", core.contains("list_toolsets")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("core must contain enable_toolset", core.contains("enable_toolset")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unknownToolsetIdDoesNotExist()
    {
        assertFalse(Toolsets.exists("no_such_toolset_zzz")); //$NON-NLS-1$
        assertFalse(Toolsets.exists(null));
    }

    @Test
    public void unmappedToolNameDefaultsToCoreButIsNotReportedAsMapped()
    {
        // Runtime defaulting keeps an uncategorized tool visible (toolsetOf -> core),
        // while isExplicitlyMapped stays false so the ratchet above still fires.
        assertEquals(Toolsets.CORE, Toolsets.toolsetOf("definitely_not_a_tool_zzz")); //$NON-NLS-1$
        assertFalse(Toolsets.isExplicitlyMapped("definitely_not_a_tool_zzz")); //$NON-NLS-1$
    }

    @Test
    public void progressiveDisclosureDefaultsOffHeadless()
    {
        // No Activator / preference store in the unit runtime -> the safe default (off),
        // which is what keeps tools/list unchanged unless the preference is set.
        assertFalse(Toolsets.isProgressiveDisclosureEnabled());
    }
}
