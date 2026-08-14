/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link McpToolRegistry}.
 * Verifies tool registration, lookup, and lifecycle management.
 */
public class McpToolRegistryTest
{
    private McpToolRegistry registry;

    @Before
    public void setUp()
    {
        registry = McpToolRegistry.getInstance();
        registry.clear();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    // === Singleton ===

    @Test
    public void testSingleton()
    {
        McpToolRegistry instance1 = McpToolRegistry.getInstance();
        McpToolRegistry instance2 = McpToolRegistry.getInstance();
        assertSame("Should return same instance", instance1, instance2);
    }

    // === Register ===

    @Test
    public void testRegisterTool()
    {
        IMcpTool tool = new StubTool("test_tool");
        registry.register(tool);
        assertNotNull(registry.getTool("test_tool"));
        assertEquals(1, registry.getToolCount());
    }

    @Test
    public void testRegisterNullTool()
    {
        registry.register(null);
        assertEquals("Null tool should not be registered", 0, registry.getToolCount());
    }

    @Test
    public void testRegisterToolWithNullName()
    {
        IMcpTool tool = new StubTool(null);
        registry.register(tool);
        assertEquals("Tool with null name should not be registered", 0, registry.getToolCount());
    }

    @Test
    public void testRegisterOverwritesSameName()
    {
        StubTool tool1 = new StubTool("same_name");
        tool1.description = "first";
        StubTool tool2 = new StubTool("same_name");
        tool2.description = "second";

        registry.register(tool1);
        registry.register(tool2);

        assertEquals("Should overwrite with same name", 1, registry.getToolCount());
        assertEquals("second", registry.getTool("same_name").getDescription());
    }

    @Test
    public void testRegisterMultipleTools()
    {
        registry.register(new StubTool("tool_a"));
        registry.register(new StubTool("tool_b"));
        registry.register(new StubTool("tool_c"));
        assertEquals(3, registry.getToolCount());
    }

    // === GetTool ===

    @Test
    public void testGetToolFound()
    {
        IMcpTool tool = new StubTool("my_tool");
        registry.register(tool);
        assertSame(tool, registry.getTool("my_tool"));
    }

    @Test
    public void testGetToolNotFound()
    {
        assertNull(registry.getTool("nonexistent"));
    }

    @Test(expected = NullPointerException.class)
    public void testGetToolNull()
    {
        // ConcurrentHashMap does not allow null keys
        registry.getTool(null);
    }

    // === GetAllTools ===

    @Test
    public void testGetAllToolsEmpty()
    {
        Collection<IMcpTool> tools = registry.getAllTools();
        assertNotNull(tools);
        assertTrue(tools.isEmpty());
    }

    @Test
    public void testGetAllToolsReturnsAll()
    {
        registry.register(new StubTool("a"));
        registry.register(new StubTool("b"));
        Collection<IMcpTool> tools = registry.getAllTools();
        assertEquals(2, tools.size());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testGetAllToolsUnmodifiable()
    {
        registry.register(new StubTool("a"));
        Collection<IMcpTool> tools = registry.getAllTools();
        tools.add(new StubTool("hacked"));
    }

    // === GetToolCount ===

    @Test
    public void testGetToolCountEmpty()
    {
        assertEquals(0, registry.getToolCount());
    }

    @Test
    public void testGetToolCountAfterOperations()
    {
        registry.register(new StubTool("a"));
        registry.register(new StubTool("b"));
        assertEquals(2, registry.getToolCount());
        registry.clear();
        assertEquals(0, registry.getToolCount());
    }

    // === Clear ===

    @Test
    public void testClear()
    {
        registry.register(new StubTool("x"));
        registry.register(new StubTool("y"));
        assertEquals(2, registry.getToolCount());

        registry.clear();
        assertEquals(0, registry.getToolCount());
        assertNull(registry.getTool("x"));
    }

    // === Bulk replacement ===

    @Test
    public void testReplaceAllPublishesTheWholeCatalogueAndDropsTheOldOne()
    {
        registry.register(new StubTool("old_tool")); //$NON-NLS-1$

        registry.replaceAll(Arrays.asList(new StubTool("tool_a"), new StubTool("tool_b"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(2, registry.getToolCount());
        assertNull("the previous catalogue must be gone", registry.getTool("old_tool")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(registry.getTool("tool_a")); //$NON-NLS-1$
        assertNotNull(registry.getTool("tool_b")); //$NON-NLS-1$
    }

    @Test
    public void testReplaceAllIgnoresNullEntriesAndAcceptsNoCatalogue()
    {
        registry.replaceAll(Arrays.asList(new StubTool("kept"), null, new StubTool(null))); //$NON-NLS-1$
        assertEquals(1, registry.getToolCount());

        registry.replaceAll(null);
        assertEquals(0, registry.getToolCount());
    }

    /**
     * A server restart republishes the catalogue while clients - and the in-process bridge -
     * keep reading it. Filling the live registry tool by tool would let them see it empty or
     * half-populated and be told that a registered tool does not exist.
     */
    @Test
    public void testConcurrentReadersNeverSeeAPartialCatalogue() throws Exception
    {
        List<IMcpTool> catalogue = new ArrayList<>();
        for (int i = 0; i < 60; i++)
        {
            catalogue.add(new StubTool("tool_" + i)); //$NON-NLS-1$
        }
        registry.replaceAll(catalogue);

        AtomicInteger smallestSeen = new AtomicInteger(Integer.MAX_VALUE);
        AtomicBoolean vanished = new AtomicBoolean();
        AtomicBoolean stop = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            while (!stop.get())
            {
                smallestSeen.getAndUpdate(seen -> Math.min(seen, registry.getToolCount()));
                if (registry.getTool("tool_0") == null) //$NON-NLS-1$
                {
                    vanished.set(true);
                }
            }
        }, "registry-reader"); //$NON-NLS-1$
        reader.start();
        try
        {
            for (int round = 0; round < 200; round++)
            {
                registry.replaceAll(catalogue);
            }
        }
        finally
        {
            stop.set(true);
            reader.join(5_000L);
        }

        assertFalse("a registered tool read as unknown during a republish", vanished.get()); //$NON-NLS-1$
        assertEquals("a reader observed a partially published catalogue", //$NON-NLS-1$
            catalogue.size(), smallestSeen.get());
    }

    // === Stub Tool ===

    /**
     * Minimal IMcpTool implementation for testing registry operations.
     */
    private static class StubTool implements IMcpTool
    {
        private final String name;
        String description = "stub description";

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
            return description;
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\"}";
        }

        @Override
        public String execute(Map<String, String> params)
        {
            return "{}";
        }
    }
}
