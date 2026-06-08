/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.ditrix.edt.mcp.server.utils.GuideLoader;

/**
 * Registry-driven ratchet for tool guides: every registered {@link IMcpTool} must
 * have a non-empty extended guide, served from its bundled Markdown resource
 * {@code guides/<name>.md} via {@link GuideLoader}.
 *
 * <p>Tool guides are the single source of truth in {@code guides/*.md} (one file
 * per tool, packaged into the plugin via {@code build.properties} {@code bin.includes}).
 * This test fails the build when a tool ships without a guide file — so adding a
 * tool now requires adding its guide — and doubles as the proof that the resource
 * loader actually resolves the bundled Markdown at runtime (the default
 * {@code getGuide()} path), not just that the files exist on disk.</p>
 */
public class GuideCoverageTest
{
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        // Same production registration the prefs UI / Activator uses.
        registry = McpToolRegistry.getInstance();
        new McpServer().registerTools();
        GuideLoader.clearCache();
    }

    @After
    public void tearDown()
    {
        registry.clear();
        GuideLoader.clearCache();
    }

    /** Sanity: the production registration actually populated tools (guards a vacuous pass). */
    @Test
    public void testRegistryIsPopulated()
    {
        assertTrue("registerTools() should register a non-trivial set of tools", //$NON-NLS-1$
            registry.getToolCount() >= 50);
    }

    @Test
    public void testEveryToolHasANonEmptyGuide()
    {
        List<String> problems = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String guide = tool.getGuide();
            if (guide == null || guide.trim().isEmpty())
            {
                problems.add(tool.getClass().getSimpleName() + " (" + tool.getName() //$NON-NLS-1$
                    + "): no guide — add guides/" + tool.getName() + ".md"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        assertTrue("tool guide coverage violations:\n  " + String.join("\n  ", problems), //$NON-NLS-1$ //$NON-NLS-2$
            problems.isEmpty());
    }

    /** The loader resolves a bundled guide by tool name (validates the resource path itself). */
    @Test
    public void testLoaderResolvesBundledGuide()
    {
        String guide = GuideLoader.load("list_projects"); //$NON-NLS-1$
        assertTrue("GuideLoader should resolve guides/list_projects.md from the bundle", //$NON-NLS-1$
            guide != null && !guide.trim().isEmpty());
    }

    /** A missing guide degrades to an empty string rather than throwing. */
    @Test
    public void testMissingGuideIsEmptyNotError()
    {
        assertTrue("an unknown tool name must yield an empty guide, not an error", //$NON-NLS-1$
            GuideLoader.load("no_such_tool_zzz").isEmpty()); //$NON-NLS-1$
    }
}
