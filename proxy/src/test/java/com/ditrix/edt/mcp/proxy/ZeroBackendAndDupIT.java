/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.TOOL_ECHO_PORT;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.TOOL_LIST_PROJECTS;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.TOOL_ROUTER_REFRESH;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.TOOL_ROUTER_STATUS;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.errorText;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.isToolError;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.projectArgs;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.reserveFreePorts;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.stopQuietly;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.structuredContent;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.toolNames;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.McpTestClient;
import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.google.gson.JsonObject;

/**
 * The two degenerate registry states:
 * <ul>
 * <li>ZERO backends (the scanned range is empty in effect — both reserved ports stay
 * closed): the proxy must stay alive — {@code initialize} works, {@code tools/list}
 * serves ONLY the {@code router_*} tools (no backend, no cache), fanned-out
 * {@code list_projects} is the "No running EDT backends" JSON-RPC error, and
 * {@code router_status} reports 0 backends;</li>
 * <li>DUPLICATE project (two backends BOTH serving the same project): a routed call is
 * refused with an error naming both owning ports.</li>
 * </ul>
 */
public class ZeroBackendAndDupIT
{
    private static final String DUP_PROJECT = "DupProject"; //$NON-NLS-1$

    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private FakeBackend backendOne;
    private FakeBackend backendTwo;
    private ProxyFixture proxy;

    /** Stops the proxy and whatever backends the scenario started. */
    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
        stopQuietly(backendOne);
        stopQuietly(backendTwo);
    }

    /**
     * With zero live backends the proxy still initializes, exposes only the router tools,
     * refuses the fanned-out {@code list_projects} with the -32000 "No running EDT
     * backends" error, and {@code router_status} reports an empty registry.
     */
    @Test
    public void testZeroBackendsProxyStaysAliveWithRouterOnlySurface() throws Exception
    {
        int[] deadPorts = reserveFreePorts(2); // reserved and released - nothing listens there
        proxy = new ProxyFixture(deadPorts[0], deadPorts[1]);
        proxy.start();
        McpTestClient client = new McpTestClient(proxy.port());

        // initialize: the proxy answers itself - no backend involved.
        JsonObject init = client.handshake();
        JsonObject initResult = init.getAsJsonObject("result"); //$NON-NLS-1$
        assertNotNull("initialize must succeed with zero backends: " + init, initResult); //$NON-NLS-1$
        assertEquals("initialize must echo the client's protocolVersion", //$NON-NLS-1$
            McpTestClient.PROTOCOL_VERSION, initResult.get("protocolVersion").getAsString()); //$NON-NLS-1$
        assertEquals("serverInfo must identify the proxy", "edt-mcp-proxy", //$NON-NLS-1$ //$NON-NLS-2$
            initResult.getAsJsonObject("serverInfo").get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$

        // tools/list: no backend and no cache -> the minimal router_*-only list.
        JsonObject toolsList = client.request("tools/list", new JsonObject()); //$NON-NLS-1$
        Set<String> names = toolNames(toolsList);
        assertEquals("with zero backends and no cache the list must hold ONLY the router tools: " + names, //$NON-NLS-1$
            Set.of(TOOL_ROUTER_STATUS, TOOL_ROUTER_REFRESH), names);

        // list_projects fan-out over zero backends -> the JSON-RPC -32000 error.
        JsonObject listProjects = client.callTool(TOOL_LIST_PROJECTS, new JsonObject());
        assertTrue("zero-backend list_projects must be a JSON-RPC error: " + listProjects, //$NON-NLS-1$
            listProjects.has("error")); //$NON-NLS-1$
        JsonObject error = listProjects.getAsJsonObject("error"); //$NON-NLS-1$
        assertEquals("fan-out over zero backends must use code -32000", //$NON-NLS-1$
            -32000, error.get("code").getAsInt()); //$NON-NLS-1$
        assertTrue("the error must say no backends are running: " + error, //$NON-NLS-1$
            error.get("message").getAsString().contains("No running EDT backends")); //$NON-NLS-1$ //$NON-NLS-2$

        // router_status: the proxy-self tool must report an EMPTY registry, not fail.
        JsonObject status = client.callTool(TOOL_ROUTER_STATUS, new JsonObject());
        assertFalse("router_status must succeed with zero backends: " + status, isToolError(status)); //$NON-NLS-1$
        JsonObject structured = structuredContent(status);
        assertTrue("router_status must report success: " + structured, //$NON-NLS-1$
            structured.get("success").getAsBoolean()); //$NON-NLS-1$
        assertEquals("router_status must report zero backends: " + structured, //$NON-NLS-1$
            0, structured.getAsJsonArray("backends").size()); //$NON-NLS-1$
        assertEquals("router_status must report the configured scan range", //$NON-NLS-1$
            deadPorts[0] + "-" + deadPorts[1], structured.get("scanRange").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * A scanned port that answers {@code /health} but declares {@code "role":"proxy"} —
     * another edt-mcp-proxy, or this very proxy when its own (ephemeral) port lands inside
     * the scan range — must NOT be registered as a backend: forwarding to a proxy re-enters
     * a proxy and recurses without bound (the CI-runner-killing runaway this guards
     * against). The registry must stay EMPTY instead.
     */
    @Test
    public void testProxyRoleResponderInScanRangeIsNotRegisteredAsBackend() throws Exception
    {
        int[] deadPorts = reserveFreePorts(2);
        ProxyFixture inner = new ProxyFixture(deadPorts[0], deadPorts[1]);
        inner.start();
        try
        {
            // The outer proxy's scan range covers EXACTLY the inner proxy - the deterministic
            // version of the flaky self-discovery (own ephemeral port inside a wide range).
            proxy = new ProxyFixture(inner.port(), inner.port());
            proxy.start();
            McpTestClient client = new McpTestClient(proxy.port());
            client.handshake();

            JsonObject status = client.callTool(TOOL_ROUTER_STATUS, new JsonObject());
            assertFalse("router_status must succeed: " + status, isToolError(status)); //$NON-NLS-1$
            JsonObject structured = structuredContent(status);
            assertEquals("a proxy-role /health responder must never be registered as a backend: " //$NON-NLS-1$
                + structured, 0, structured.getAsJsonArray("backends").size()); //$NON-NLS-1$
        }
        finally
        {
            inner.stop();
        }
    }

    /**
     * Two backends both serving the same project: a call routed by that project name must
     * be refused with the duplicate error naming BOTH owning ports (the proxy must never
     * silently pick one).
     */
    @Test
    public void testDuplicateProjectRefusedNamingBothPorts() throws Exception
    {
        int[] ports = reserveFreePorts(2);
        backendOne = new FakeBackend(ports[0], List.of(DUP_PROJECT));
        backendTwo = new FakeBackend(ports[1], List.of(DUP_PROJECT));
        backendOne.start();
        backendTwo.start();
        proxy = new ProxyFixture(ports[0], ports[1]);
        proxy.start();
        McpTestClient client = new McpTestClient(proxy.port());
        client.handshake();

        JsonObject response = client.callTool(TOOL_ECHO_PORT, projectArgs(DUP_PROJECT));
        assertTrue("a duplicated project must be refused, not silently routed: " + response, //$NON-NLS-1$
            isToolError(response));
        String text = errorText(response);
        assertTrue("the duplicate error must name port :" + ports[0] + ": " + text, //$NON-NLS-1$ //$NON-NLS-2$
            text.contains(String.valueOf(ports[0])));
        assertTrue("the duplicate error must name port :" + ports[1] + ": " + text, //$NON-NLS-1$ //$NON-NLS-2$
            text.contains(String.valueOf(ports[1])));
    }
}
