/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

/**
 * Coverage ratchet: every registered {@link IMcpTool} must have a corresponding
 * {@code XxxToolTest} on the test classpath, OR be listed in
 * {@link #KNOWN_UNTESTED}. A newly added tool that ships without a test fails the
 * build until a test is written or the tool is consciously allow-listed — so the
 * set of untested tools cannot silently grow (it is meant to SHRINK over time).
 * <p>
 * This is the registry-driven gate from card {@code tests-coverage-ci-gate}. The
 * complementary e2e backstop (every tool has a unit test OR an e2e scenario)
 * needs the live e2e suite and is tracked on that card.
 */
public class BuiltInToolTestCoverageTest
{
    /**
     * Tools with no {@code XxxToolTest}. RATCHET: a newly added tool that ships
     * without a test fails {@link #everyRegisteredToolHasATestOrIsAllowlisted}
     * until a test is written or (deliberately) its simple class name is added
     * here. As of 2026-06-01 every registered tool has a test, so this allow-list
     * is EMPTY — keep it that way.
     */
    private static final Set<String> KNOWN_UNTESTED = new HashSet<>();

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
    }

    @Test
    public void everyRegisteredToolHasATestOrIsAllowlisted()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        List<String> missing = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String simpleName = tool.getClass().getSimpleName();
            String testClassFqn = tool.getClass().getName() + "Test"; //$NON-NLS-1$
            if (!classExists(testClassFqn) && !KNOWN_UNTESTED.contains(simpleName))
            {
                missing.add(simpleName);
            }
        }
        assertTrue("Registered tools with no XxxToolTest and not in the KNOWN_UNTESTED allow-list: " //$NON-NLS-1$
            + missing + ". Add a unit test (preferred), or deliberately add it to the allow-list.", //$NON-NLS-1$
            missing.isEmpty());
    }

    /**
     * Keeps the allow-list honest and tightens the ratchet: an entry that now HAS
     * a test must be removed, and every entry must name a real registered tool.
     */
    @Test
    public void allowListHasNoStaleEntries()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        BuiltInToolRegistrar.registerAll(registry);

        Set<String> registeredSimpleNames = new HashSet<>();
        List<String> nowTested = new ArrayList<>();
        for (IMcpTool tool : registry.getAllTools())
        {
            String simpleName = tool.getClass().getSimpleName();
            registeredSimpleNames.add(simpleName);
            if (KNOWN_UNTESTED.contains(simpleName) && classExists(tool.getClass().getName() + "Test")) //$NON-NLS-1$
            {
                nowTested.add(simpleName);
            }
        }
        assertTrue("KNOWN_UNTESTED lists tools that now HAVE a test - remove them to tighten the ratchet: " //$NON-NLS-1$
            + nowTested, nowTested.isEmpty());

        List<String> unknown = new ArrayList<>();
        for (String name : KNOWN_UNTESTED)
        {
            if (!registeredSimpleNames.contains(name))
            {
                unknown.add(name);
            }
        }
        assertTrue("KNOWN_UNTESTED has entries that are not registered tools (typo / removed tool?): " //$NON-NLS-1$
            + unknown, unknown.isEmpty());
    }

    private static boolean classExists(String fqn)
    {
        try
        {
            Class.forName(fqn);
            return true;
        }
        catch (ClassNotFoundException e)
        {
            return false;
        }
    }
}
