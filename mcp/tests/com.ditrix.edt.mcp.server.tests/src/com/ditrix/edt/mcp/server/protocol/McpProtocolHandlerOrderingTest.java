/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/** Pins catalogue ordering in the serialized JSON-RPC responses. */
public class McpProtocolHandlerOrderingTest
{
    private final McpToolRegistry registry = McpToolRegistry.getInstance();
    private McpProtocolHandler handler;

    @Before
    public void setUp()
    {
        registry.clear();
        handler = new McpProtocolHandler();
    }

    @After
    public void tearDown()
    {
        registry.clear();
    }

    @Test
    public void testToolsListIsNameSortedOnTheWire()
    {
        List<IMcpTool> tools = catalogue();
        registerInReverse(tools);

        assertEquals(sortedNames(tools), listedNames(response("tools/list"), "tools")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testResourcesListIsNameSortedOnTheWire()
    {
        List<IMcpTool> tools = catalogue();
        registerInReverse(tools);
        JsonArray resources = resultArray(response("resources/list"), "resources"); //$NON-NLS-1$ //$NON-NLS-2$
        List<String> actual = new ArrayList<>();
        for (JsonElement resource : resources)
        {
            String uri = resource.getAsJsonObject().get("uri").getAsString(); //$NON-NLS-1$
            String name = uri.substring(McpConstants.GUIDE_URI_SCHEME.length());
            assertEquals(McpConstants.GUIDE_URI_SCHEME + name, uri);
            assertEquals(name + " guide", resource.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            actual.add(name);
        }
        assertEquals(sortedNames(tools), actual);
    }

    @Test
    public void testToolsListBytesDoNotDependOnRegistrationOrder()
    {
        List<IMcpTool> tools = catalogue();
        // These names have the same Java hash, so map iteration exposes insertion history.
        assertEquals("tool_Aa".hashCode(), "tool_BB".hashCode()); //$NON-NLS-1$ //$NON-NLS-2$
        tools.add(new StubTool("tool_Aa")); //$NON-NLS-1$
        tools.add(new StubTool("tool_BB")); //$NON-NLS-1$
        registerInReverse(tools);
        String reverseOrder = response("tools/list"); //$NON-NLS-1$

        registry.clear();
        for (IMcpTool tool : tools)
        {
            registry.register(tool);
        }
        String forwardOrder = response("tools/list"); //$NON-NLS-1$

        assertEquals("Serialized tools/list must be identical for either insertion order", //$NON-NLS-1$
            reverseOrder, forwardOrder);
        assertEquals(sortedNames(tools), listedNames(forwardOrder, "tools")); //$NON-NLS-1$
    }

    private String response(String method)
    {
        return handler.processRequest("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" //$NON-NLS-1$
            + method + "\",\"params\":{}}"); //$NON-NLS-1$
    }

    private void registerInReverse(List<IMcpTool> tools)
    {
        for (int i = tools.size() - 1; i >= 0; i--)
        {
            registry.register(tools.get(i));
        }
    }

    private static List<IMcpTool> catalogue()
    {
        List<IMcpTool> tools = new ArrayList<>();
        for (int i = 0; i < 40; i++)
        {
            tools.add(new StubTool("tool_" + (i < 10 ? "0" : "") + i)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        return tools;
    }

    private static List<String> sortedNames(List<IMcpTool> tools)
    {
        return tools.stream().map(IMcpTool::getName).sorted().collect(Collectors.toList());
    }

    private static JsonArray resultArray(String response, String member)
    {
        return JsonParser.parseString(response).getAsJsonObject()
            .getAsJsonObject("result").getAsJsonArray(member); //$NON-NLS-1$
    }

    private static List<String> listedNames(String response, String member)
    {
        List<String> names = new ArrayList<>();
        for (JsonElement tool : resultArray(response, member))
        {
            names.add(tool.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
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
