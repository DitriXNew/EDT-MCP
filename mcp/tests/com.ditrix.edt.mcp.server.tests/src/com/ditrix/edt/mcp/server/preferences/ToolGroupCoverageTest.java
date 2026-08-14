/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * Registry-driven ratchet for the Tools preference model: every production tool must belong to
 * exactly one {@link ToolGroup}, and every name declared by a group must still be registered.
 */
public class ToolGroupCoverageTest
{
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    /** Sanity: production registration must run, otherwise both coverage checks pass vacuously. */
    @Test
    public void testRegistryIsPopulated()
    {
        assertTrue("registerAll() should register a non-trivial production tool set", //$NON-NLS-1$
            registry.getToolCount() >= 50);
    }

    @Test
    public void testEveryRegisteredToolBelongsToExactlyOneGroup()
    {
        List<String> violations = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            int memberships = 0;
            for (ToolGroup group : ToolGroup.values())
            {
                if (group.getToolNames().contains(tool.getName()))
                {
                    memberships++;
                }
            }
            if (ToolGroup.getGroupForTool(tool.getName()) == null || memberships != 1)
            {
                violations.add(tool.getName() + " (memberships=" + memberships + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        assertTrue("Registered tools must belong to exactly one ToolGroup: " + violations //$NON-NLS-1$
            + ". Add each offending tool to the right group in ToolGroup and remove duplicates.", //$NON-NLS-1$
            violations.isEmpty());
    }

    @Test
    public void testEveryGroupedToolIsRegistered()
    {
        Set<String> registered = new HashSet<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            registered.add(tool.getName());
        }

        List<String> stale = new ArrayList<>();
        for (ToolGroup group : ToolGroup.values())
        {
            for (String toolName : group.getToolNames())
            {
                if (!registered.contains(toolName))
                {
                    stale.add(toolName + " (" + group.name() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }

        assertTrue("ToolGroup contains names that are not registered tools: " + stale //$NON-NLS-1$
            + ". Replace each stale name and add the registered tool to the right group in ToolGroup.", //$NON-NLS-1$
            stale.isEmpty());
    }
}
