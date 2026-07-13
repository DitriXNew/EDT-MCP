/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.PROJECT_A;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.PROJECT_B;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.TOOL_ECHO_PORT;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.errorText;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.isToolError;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.projectArgs;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.reserveFreePorts;
import static com.ditrix.edt.mcp.proxy.ProxyRoutingIT.stopQuietly;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.McpTestClient;
import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.google.gson.JsonObject;

/**
 * Hot-plug and failover behaviour of the proxy, all without any periodic refresh running
 * (the fixture never starts one), so every recovery observed here is the router's own
 * on-miss rescan or the handler's refresh-on-IOException — never a background timer:
 * <ul>
 * <li>a call for an unknown project yields the actionable error pointing at
 * {@code router_refresh};</li>
 * <li>after the missing backend is started, the IMMEDIATE retry succeeds (on-miss
 * rescan, the hot-plug path);</li>
 * <li>after a live backend is stopped, a call routed to it yields an error naming the
 * dead backend (or, if a rescan already dropped it, the no-instance message).</li>
 * </ul>
 */
public class HotplugFailoverIT
{
    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private int[] ports;
    private FakeBackend backendA;
    private FakeBackend backendB;
    private ProxyFixture proxy;
    private McpTestClient client;

    /**
     * Starts ONLY backend A on the lower reserved port; the higher port stays free for the
     * hot-plug scenario but is inside the proxy's scan range. Then starts the proxy and
     * performs the client handshake.
     */
    @Before
    public void setUp() throws Exception
    {
        ports = reserveFreePorts(2);
        backendA = new FakeBackend(ports[0], List.of(PROJECT_A));
        backendA.start();
        proxy = new ProxyFixture(ports[0], ports[1]);
        proxy.start();
        client = new McpTestClient(proxy.port());
        client.handshake();
    }

    /** Stops the proxy and whatever backends a scenario left running. */
    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
        stopQuietly(backendA);
        stopQuietly(backendB);
    }

    /**
     * Unknown project → the actionable error; start the missing backend → the IMMEDIATE
     * retry succeeds via the on-miss rescan (no periodic refresh is running to help).
     */
    @Test
    public void testUnknownProjectErrorThenHotplugImmediateRetry() throws Exception
    {
        JsonObject miss = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_B));
        assertTrue("a call for a project no backend serves must be a tool error: " + miss, //$NON-NLS-1$
            isToolError(miss));
        String missText = errorText(miss);
        assertTrue("the error must name the missing project: " + missText, //$NON-NLS-1$
            missText.contains("No running EDT instance serves project '" + PROJECT_B + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must be actionable and point at router_refresh: " + missText, //$NON-NLS-1$
            missText.contains("router_refresh")); //$NON-NLS-1$

        // Hot-plug: the "AI agent" starts the missing EDT instance inside the scan range.
        backendB = new FakeBackend(ports[1], List.of(PROJECT_B));
        backendB.start();

        // The very next call must succeed - the router rescans on a project miss.
        JsonObject hit = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_B));
        assertFalse("the hot-plugged backend must be routable immediately: " + hit, isToolError(hit)); //$NON-NLS-1$
        assertTrue("the retry must reach the new backend :" + ports[1] + ": " + hit, //$NON-NLS-1$ //$NON-NLS-2$
            hit.toString().contains(String.valueOf(ports[1])));
    }

    /**
     * Stop a live backend → a call routed to it must fail with an error naming the dead
     * backend (":&lt;port&gt; stopped responding") or, when a rescan already dropped the
     * backend, the no-instance message — both are the spec-sanctioned outcomes.
     */
    @Test
    public void testStoppedBackendYieldsActionableError() throws Exception
    {
        // Prove the route works while the backend is alive (also warms the proxy's session).
        JsonObject ok = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_A));
        assertFalse("echo_port must succeed while the backend is alive: " + ok, isToolError(ok)); //$NON-NLS-1$
        assertTrue("the call must reach backend :" + ports[0] + ": " + ok, //$NON-NLS-1$ //$NON-NLS-2$
            ok.toString().contains(String.valueOf(ports[0])));

        backendA.stop();

        JsonObject fail = client.callTool(TOOL_ECHO_PORT, projectArgs(PROJECT_A));
        assertTrue("a call to a stopped backend must be an error: " + fail, //$NON-NLS-1$
            isToolError(fail) || fail.has("error")); //$NON-NLS-1$
        String failText = errorText(fail);
        boolean mentionsStopped = failText.contains(":" + ports[0] + " stopped responding"); //$NON-NLS-1$ //$NON-NLS-2$
        boolean mentionsNoInstance =
            failText.contains("No running EDT instance serves project '" + PROJECT_A + "'"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the error must mention the dead backend :" + ports[0] //$NON-NLS-1$
            + " or the refreshed no-instance state: " + failText, //$NON-NLS-1$
            mentionsStopped || mentionsNoInstance);
    }
}
