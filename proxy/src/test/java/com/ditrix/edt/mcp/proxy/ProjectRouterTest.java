/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.ditrix.edt.mcp.proxy.ProjectRouter.RouteResult;
import com.google.gson.JsonObject;

/**
 * Unit tests for {@link ProjectRouter}: every {@link RouteResult.Kind}, the project-scoped
 * lookup rules (known owner / duplicate / on-miss rescan) and the unscoped fallback.
 * <p>
 * The router never talks to a backend itself - it only reads {@link BackendRegistry}'s
 * routing snapshot - so these tests drive a real registry through its package-private test
 * seams ({@code installStateForTest} / {@code setRefreshForTest}) instead of real sockets,
 * per the spec's "keep it SIMPLE" guidance.
 */
public class ProjectRouterTest
{
    private static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    private static Backend backend(int port)
    {
        return new Backend(port, HTTP_CLIENT, 5);
    }

    private static BackendRegistry newRegistry(int scanFrom, int scanTo)
    {
        ProxyConfig cfg = ProxyConfig.parse(new String[] { "--scan", scanFrom + "-" + scanTo }, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$
        return new BackendRegistry(cfg);
    }

    private static JsonObject toolCallRequest(String toolName, JsonObject arguments)
    {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName); //$NON-NLS-1$
        params.add("arguments", arguments); //$NON-NLS-1$
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0"); //$NON-NLS-1$ //$NON-NLS-2$
        request.addProperty("id", 1); //$NON-NLS-1$
        request.addProperty("method", METHOD_TOOLS_CALL); //$NON-NLS-1$
        request.add("params", params); //$NON-NLS-1$
        return request;
    }

    private static JsonObject projectArgs(String key, String value)
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty(key, value); //$NON-NLS-1$
        return arguments;
    }

    // ---- router_status / router_refresh -> PROXY_SELF ----

    @Test
    public void testRouterStatusIsProxySelf()
    {
        ProjectRouter router = new ProjectRouter(newRegistry(20000, 20001));

        RouteResult result =
            router.route(METHOD_TOOLS_CALL, toolCallRequest("router_status", new JsonObject())); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.PROXY_SELF, result.kind);
    }

    @Test
    public void testRouterRefreshIsProxySelf()
    {
        ProjectRouter router = new ProjectRouter(newRegistry(20000, 20001));

        RouteResult result =
            router.route(METHOD_TOOLS_CALL, toolCallRequest("router_refresh", new JsonObject())); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.PROXY_SELF, result.kind);
    }

    // ---- list_projects -> FAN_OUT_LIST_PROJECTS ----

    @Test
    public void testListProjectsIsFanOut()
    {
        ProjectRouter router = new ProjectRouter(newRegistry(20000, 20001));

        RouteResult result =
            router.route(METHOD_TOOLS_CALL, toolCallRequest("list_projects", new JsonObject())); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.FAN_OUT_LIST_PROJECTS, result.kind);
    }

    // ---- known project -> BACKEND(owner) ----

    @Test
    public void testKnownProjectRoutesToItsOwner()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend alpha = backend(20001);
        registry.installStateForTest(List.of(alpha), Map.of("Alpha", List.of(alpha))); //$NON-NLS-1$
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL,
            toolCallRequest("some_tool", projectArgs("projectName", "Alpha"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertSame(alpha, result.backend);
    }

    @Test
    public void testKnownProjectViaLegacyProjectArgument()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend alpha = backend(20001);
        registry.installStateForTest(List.of(alpha), Map.of("Alpha", List.of(alpha))); //$NON-NLS-1$
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL,
            toolCallRequest("some_tool", projectArgs("project", "Alpha"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertSame(alpha, result.backend);
    }

    // ---- duplicate project -> ERROR naming the ports ----

    @Test
    public void testDuplicateProjectYieldsErrorNamingBothPorts()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend one = backend(20001);
        Backend two = backend(20002);
        registry.installStateForTest(List.of(one, two), Map.of("Dup", List.of(one, two))); //$NON-NLS-1$
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL,
            toolCallRequest("some_tool", projectArgs("projectName", "Dup"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(RouteResult.Kind.ERROR, result.kind);
        assertTrue(result.errorMessage, result.errorMessage.contains("20001")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("20002")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("Dup")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("ambiguous")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("router_refresh")); //$NON-NLS-1$
    }

    // ---- unknown project -> ONE on-miss rescan, then re-lookup ----

    @Test
    public void testUnknownProjectRescansOnceThenRoutesWhenHotPlugged()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        AtomicInteger refreshCount = new AtomicInteger();
        Backend beta = backend(20003);
        registry.setRefreshForTest(() -> {
            refreshCount.incrementAndGet();
            registry.installStateForTest(List.of(beta), Map.of("Beta", List.of(beta))); //$NON-NLS-1$
        });
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL,
            toolCallRequest("some_tool", projectArgs("projectName", "Beta"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("hot-plug must be noticed by exactly one on-miss rescan", 1, refreshCount.get()); //$NON-NLS-1$
        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertSame(beta, result.backend);
    }

    @Test
    public void testUnknownProjectStillUnknownAfterRescanYieldsActionableError()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        AtomicInteger refreshCount = new AtomicInteger();
        registry.setRefreshForTest(refreshCount::incrementAndGet);
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL,
            toolCallRequest("some_tool", projectArgs("projectName", "Ghost"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("exactly one on-miss rescan, never a loop", 1, refreshCount.get()); //$NON-NLS-1$
        assertEquals(RouteResult.Kind.ERROR, result.kind);
        assertTrue(result.errorMessage,
            result.errorMessage.contains("No running EDT instance serves project 'Ghost'")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("Live backends: none")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("router_refresh")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("retry in ~30")); //$NON-NLS-1$
    }

    // ---- no project arg -> the first live backend ----

    @Test
    public void testNoProjectArgRoutesToFirstLiveBackendByAscendingPort()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend high = backend(20005);
        Backend low = backend(20002);
        registry.installStateForTest(List.of(high, low), Map.of());
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result =
            router.route(METHOD_TOOLS_CALL, toolCallRequest("some_unscoped_tool", new JsonObject())); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertEquals(20002, result.backend.getPort());
    }

    @Test
    public void testNonToolsCallMethodIgnoresProjectArgAndRoutesUnscoped()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend only = backend(20002);
        registry.installStateForTest(List.of(only), Map.of());
        ProjectRouter router = new ProjectRouter(registry);
        JsonObject request = toolCallRequest("irrelevant_tool", projectArgs("projectName", "Whatever")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        // Method is "ping", not "tools/call" - the tool-name / project rules never apply,
        // even though the request body itself carries a params.arguments.projectName.
        RouteResult result = router.route("ping", request); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertSame(only, result.backend);
    }

    @Test
    public void testMalformedRequestRoutesUnscoped()
    {
        BackendRegistry registry = newRegistry(20000, 20010);
        Backend only = backend(20002);
        registry.installStateForTest(List.of(only), Map.of());
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result = router.route(METHOD_TOOLS_CALL, null);

        assertEquals(RouteResult.Kind.BACKEND, result.kind);
        assertSame(only, result.backend);
    }

    // ---- zero live -> ERROR mentioning the scan range ----

    @Test
    public void testZeroLiveBackendsYieldsErrorMentioningScanRange()
    {
        BackendRegistry registry = newRegistry(21000, 21005);
        ProjectRouter router = new ProjectRouter(registry);

        RouteResult result =
            router.route(METHOD_TOOLS_CALL, toolCallRequest("some_unscoped_tool", new JsonObject())); //$NON-NLS-1$

        assertEquals(RouteResult.Kind.ERROR, result.kind);
        assertTrue(result.errorMessage, result.errorMessage.contains("21000-21005")); //$NON-NLS-1$
        assertTrue(result.errorMessage, result.errorMessage.contains("router_refresh")); //$NON-NLS-1$
    }

    // ---- extractProjectArg ----

    @Test
    public void testExtractProjectArgPrefersProjectNameOverProject()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("projectName", "Alpha"); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.addProperty("project", "Beta"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Alpha", ProjectRouter.extractProjectArg(toolCallRequest("some_tool", arguments))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testExtractProjectArgFallsBackToProject()
    {
        JsonObject request = toolCallRequest("some_tool", projectArgs("project", "Beta")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("Beta", ProjectRouter.extractProjectArg(request)); //$NON-NLS-1$
    }

    @Test
    public void testExtractProjectArgBlankProjectNameFallsBackToProject()
    {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("projectName", "   "); //$NON-NLS-1$ //$NON-NLS-2$
        arguments.addProperty("project", "Beta"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("Beta", //$NON-NLS-1$
            ProjectRouter.extractProjectArg(toolCallRequest("some_tool", arguments))); //$NON-NLS-1$
    }

    @Test
    public void testExtractProjectArgAbsentReturnsNull()
    {
        assertNull(ProjectRouter.extractProjectArg(null));
        assertNull(ProjectRouter.extractProjectArg(new JsonObject()));
        assertNull(ProjectRouter.extractProjectArg(toolCallRequest("some_tool", new JsonObject()))); //$NON-NLS-1$
    }
}
