/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Name ordering and snapshot guarantees shared by every catalogue consumer. */
public class McpToolRegistryOrderingTest
{
    private final McpToolRegistry registry = McpToolRegistry.getInstance();

    @Before
    @After
    public void reset()
    {
        registry.clear();
    }

    @Test
    public void testAllToolsAreSortedUnmodifiableSnapshots()
    {
        List<IMcpTool> tools = catalogue("tool_"); //$NON-NLS-1$
        registerInReverse(tools);

        Collection<IMcpTool> snapshot = registry.getAllTools();
        assertEquals(names(tools), names(snapshot));
        assertUnmodifiable(snapshot);

        registry.unregister(tools.get(0).getName());
        assertEquals("A returned catalogue must remain a complete snapshot", //$NON-NLS-1$
            names(tools), names(snapshot));
    }

    @Test
    public void testEnabledToolsRemainSortedAfterFiltering()
    {
        List<IMcpTool> tools = catalogue("tool_"); //$NON-NLS-1$
        registerInReverse(tools);
        // The headless shipped defaults disable git, so exercise enablement filtering too.
        registry.register(new StubTool("git")); //$NON-NLS-1$

        Collection<IMcpTool> enabled = registry.getEnabledTools();
        assertEquals(names(tools), names(enabled));
        assertUnmodifiable(enabled);
    }

    @Test
    public void testVisibleToolsRemainSortedAfterFiltering()
    {
        List<IMcpTool> tools = catalogue("tool_"); //$NON-NLS-1$
        registerInReverse(tools);
        registry.register(new StubTool("git")); //$NON-NLS-1$

        Collection<IMcpTool> visible = registry.getVisibleTools();
        assertEquals(names(tools), names(visible));
        assertUnmodifiable(visible);
    }

    @Test
    public void testReplacementKeepsSortedWholeCatalogues() throws InterruptedException
    {
        List<IMcpTool> first = catalogue("first_"); //$NON-NLS-1$
        List<IMcpTool> second = catalogue("second_"); //$NON-NLS-1$
        List<String> firstNames = names(first);
        List<String> secondNames = names(second);
        Collections.reverse(first);
        Collections.reverse(second);
        registry.replaceAll(first);
        assertEquals(firstNames, names(registry.getAllTools()));

        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            for (int round = 0; round < 40 && !stop.get(); round++)
            {
                registry.replaceAll(round % 2 == 0 ? second : first);
            }
        }, "registry-ordering-writer"); //$NON-NLS-1$
        writer.setDaemon(true);
        writer.start();
        try
        {
            for (int read = 0; read < 100; read++)
            {
                assertWholeSortedCatalogue(registry.getAllTools(), firstNames, secondNames);
                assertWholeSortedCatalogue(registry.getEnabledTools(), firstNames, secondNames);
                assertWholeSortedCatalogue(registry.getVisibleTools(), firstNames, secondNames);
            }
        }
        finally
        {
            stop.set(true);
            writer.join(5_000L);
        }
        assertFalse("The replacement thread must finish", writer.isAlive()); //$NON-NLS-1$
    }

    private void registerInReverse(List<IMcpTool> tools)
    {
        for (int i = tools.size() - 1; i >= 0; i--)
        {
            registry.register(tools.get(i));
        }
    }

    private static List<IMcpTool> catalogue(String prefix)
    {
        List<IMcpTool> tools = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            tools.add(new StubTool(prefix + (i < 10 ? "0" : "") + i)); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return tools;
    }

    private static List<String> names(Collection<IMcpTool> tools)
    {
        return tools.stream().map(IMcpTool::getName).collect(Collectors.toList());
    }

    private static void assertUnmodifiable(Collection<IMcpTool> tools)
    {
        try
        {
            tools.clear();
            fail("Catalogue snapshots must be unmodifiable"); //$NON-NLS-1$
        }
        catch (UnsupportedOperationException expected)
        {
            // The caller cannot mutate the returned catalogue.
        }
    }

    private static void assertWholeSortedCatalogue(Collection<IMcpTool> tools,
        List<String> first, List<String> second)
    {
        List<String> actual = names(tools);
        assertTrue("A reader must see one whole, name-sorted catalogue: " + actual, //$NON-NLS-1$
            actual.equals(first) || actual.equals(second));
    }

    private static final class StubTool implements IMcpTool
    {
        private final String name;

        StubTool(String name)
        {
            this.name = name;
        }

        @Override
        public String getName()
        {
            return name;
        }

        @Override
        public String getDescription()
        {
            return "Ordering test tool"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}"; //$NON-NLS-1$
        }
    }
}
