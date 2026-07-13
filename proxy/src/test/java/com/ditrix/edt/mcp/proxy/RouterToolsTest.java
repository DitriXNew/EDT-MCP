/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Unit tests for {@link RouterTools}: the {@code toolsListInjection()} descriptor pair, its
 * injection into a realistic backend {@code tools/list} response, and the
 * {@code router_status} / {@code router_refresh} payload shape.
 */
public class RouterToolsTest
{
    /** Restores a neutral configuration so no test leaks its {@code ProxyConfig} into the next. */
    @After
    public void resetRouterConfig()
    {
        RouterTools.configure(ProxyConfig.parse(new String[0], Map.of()));
    }

    private static String sampleToolsListResponse()
    {
        JsonObject listProjectsTool = descriptor("list_projects", "Lists workspace projects"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject metadataTool = descriptor("get_metadata_details", "Reads a metadata object"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray tools = new JsonArray();
        tools.add(listProjectsTool);
        tools.add(metadataTool);

        JsonObject result = new JsonObject();
        result.add("tools", tools); //$NON-NLS-1$

        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        envelope.addProperty("id", 1); //$NON-NLS-1$
        envelope.add("result", result); //$NON-NLS-1$
        return Json.compact(envelope);
    }

    private static JsonObject descriptor(String name, String description)
    {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
        schema.add("properties", new JsonObject()); //$NON-NLS-1$
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name); //$NON-NLS-1$
        tool.addProperty("description", description); //$NON-NLS-1$
        tool.add("inputSchema", schema); //$NON-NLS-1$
        return tool;
    }

    private static Set<String> toolNames(JsonArray tools)
    {
        Set<String> names = new LinkedHashSet<>();
        for (JsonElement tool : tools)
        {
            names.add(tool.getAsJsonObject().get("name").getAsString()); //$NON-NLS-1$
        }
        return names;
    }

    private static List<String> toStringList(JsonArray array)
    {
        List<String> values = new ArrayList<>();
        for (JsonElement element : array)
        {
            values.add(element.getAsString());
        }
        return values;
    }

    // ---- toolsListInjection() ----

    @Test
    public void testToolsListInjectionIsAValidJsonArrayWithBothDescriptors()
    {
        String json = RouterTools.toolsListInjection();

        JsonElement parsed = JsonParser.parseString(json);
        assertTrue("toolsListInjection() must be a JSON array", parsed.isJsonArray()); //$NON-NLS-1$
        JsonArray descriptors = parsed.getAsJsonArray();
        assertEquals(2, descriptors.size());
        assertEquals(Set.of("router_status", "router_refresh"), toolNames(descriptors)); //$NON-NLS-1$ //$NON-NLS-2$

        for (JsonElement element : descriptors)
        {
            JsonObject tool = element.getAsJsonObject();
            assertFalse("each descriptor needs a non-blank description: " + tool, //$NON-NLS-1$
                tool.get("description").getAsString().isBlank()); //$NON-NLS-1$
            JsonObject schema = tool.getAsJsonObject("inputSchema"); //$NON-NLS-1$
            assertEquals("object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- injectIntoToolsList() ----

    @Test
    public void testInjectIntoToolsListAppendsIntoARealisticSample()
    {
        String injected = RouterTools.injectIntoToolsList(sampleToolsListResponse());

        JsonArray tools = Json.parseObject(injected).getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(4, tools.size());
        Set<String> names = toolNames(tools);
        assertTrue(names.contains("list_projects")); //$NON-NLS-1$
        assertTrue(names.contains("get_metadata_details")); //$NON-NLS-1$
        assertTrue(names.contains("router_status")); //$NON-NLS-1$
        assertTrue(names.contains("router_refresh")); //$NON-NLS-1$
    }

    @Test
    public void testInjectIntoToolsListIsIdempotentWhenAlreadyInjected()
    {
        String onceInjected = RouterTools.injectIntoToolsList(sampleToolsListResponse());

        String twiceInjected = RouterTools.injectIntoToolsList(onceInjected);

        JsonArray tools = Json.parseObject(twiceInjected).getAsJsonObject("result").getAsJsonArray("tools"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("a second injection must not duplicate the router tools", 4, tools.size()); //$NON-NLS-1$
    }

    @Test
    public void testInjectIntoToolsListLeavesMalformedInputUnchanged()
    {
        assertEquals("not json", RouterTools.injectIntoToolsList("not json")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(RouterTools.injectIntoToolsList(null));
    }

    @Test
    public void testInjectIntoToolsListLeavesAResultlessResponseUnchanged()
    {
        String noResult = "{\"jsonrpc\":\"2.0\",\"id\":1}"; //$NON-NLS-1$

        assertEquals(noResult, RouterTools.injectIntoToolsList(noResult));
    }

    // ---- routerStatus() payload shape ----

    @Test
    public void testRouterStatusPayloadShape()
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--port", "9999", "--scan", "100-110" }, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        RouterTools.configure(cfg);
        BackendRegistry registry = new BackendRegistry(cfg);
        Backend alpha = new Backend(101, HttpClient.newHttpClient(), 5);
        Backend beta = new Backend(102, HttpClient.newHttpClient(), 5);
        registry.installStateForTest(List.of(alpha, beta),
            Map.of("Alpha", List.of(alpha), "Beta", List.of(beta))); //$NON-NLS-1$ //$NON-NLS-2$

        String response = RouterTools.routerStatus(registry, 5);

        JsonObject envelope = Json.parseObject(response);
        assertEquals("2.0", envelope.get("jsonrpc").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(5, envelope.get("id").getAsInt()); //$NON-NLS-1$
        JsonObject result = envelope.getAsJsonObject("result"); //$NON-NLS-1$
        assertFalse("a successful status must not carry isError", result.has("isError")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject structured = result.getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertTrue(structured.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals(9999, structured.get("proxyPort").getAsInt()); //$NON-NLS-1$
        assertEquals("100-110", structured.get("scanRange").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray backends = structured.getAsJsonArray("backends"); //$NON-NLS-1$
        assertEquals(2, backends.size());
        JsonObject firstBackend = backends.get(0).getAsJsonObject();
        assertEquals(101, firstBackend.get("port").getAsInt()); //$NON-NLS-1$
        assertEquals(List.of("Alpha"), toStringList(firstBackend.getAsJsonArray("projects"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(structured.get("lastRefreshMs").getAsLong() >= 0); //$NON-NLS-1$
        assertTrue("no project is duplicated in this fixture", //$NON-NLS-1$
            structured.getAsJsonObject("duplicates").entrySet().isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testRouterStatusFallsBackToZeroAndEmptyScanRangeWhenUnconfigured()
    {
        RouterTools.configure(null);
        BackendRegistry registry = new BackendRegistry(ProxyConfig.parse(new String[0], Map.of()));

        String response = RouterTools.routerStatus(registry, 1);

        JsonObject structured = Json.parseObject(response).getAsJsonObject("result") //$NON-NLS-1$
            .getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertEquals(0, structured.get("proxyPort").getAsInt()); //$NON-NLS-1$
        assertEquals("", structured.get("scanRange").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- routerRefresh() ----

    @Test
    public void testRouterRefreshTriggersARefreshThenReturnsTheFreshStatus()
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--scan", "9990-9992" }, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$
        RouterTools.configure(cfg);
        BackendRegistry registry = new BackendRegistry(cfg);
        AtomicBoolean refreshed = new AtomicBoolean();
        registry.setRefreshForTest(() -> refreshed.set(true));

        String response = RouterTools.routerRefresh(registry, 3);

        assertTrue("routerRefresh must trigger a registry refresh", refreshed.get()); //$NON-NLS-1$
        JsonObject structured = Json.parseObject(response).getAsJsonObject("result") //$NON-NLS-1$
            .getAsJsonObject("structuredContent"); //$NON-NLS-1$
        assertTrue(structured.get("success").getAsBoolean()); //$NON-NLS-1$
        assertTrue("a fresh refresh must record a timestamp", //$NON-NLS-1$
            structured.get("lastRefreshMs").getAsLong() > 0); //$NON-NLS-1$
    }
}
