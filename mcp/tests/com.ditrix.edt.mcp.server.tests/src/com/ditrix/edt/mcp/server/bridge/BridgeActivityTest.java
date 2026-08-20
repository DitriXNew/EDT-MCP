/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/**
 * The liveness signal a caller waiting on an assistant turn reads: is anything still coming
 * through the in-process bridge? Each test states the property that made the retained call
 * history unusable for this.
 */
public class BridgeActivityTest
{
    private static final String PROBE_TOOL_NAME = "bridge_activity_probe"; //$NON-NLS-1$

    private final AtomicLong ticksDuringCall = new AtomicLong(-1L);
    private final AtomicInteger inFlightDuringCall = new AtomicInteger(-1);

    @Before
    public void setUp()
    {
        McpToolRegistry.getInstance().clear();
        McpToolRegistry.getInstance()
            .register(new ActivityProbeTool(ticksDuringCall, inFlightDuringCall));
        BridgeActivity.resetForTest();
    }

    @After
    public void tearDown()
    {
        McpToolRegistry.getInstance().clear();
        BridgeActivity.resetForTest();
    }

    @Test
    public void testACallIsVisibleWhileItRunsAndNotAfterwards()
    {
        // THE property the retained history cannot provide: one tool that runs for minutes ticks
        // once, so "has the counter moved?" would call it silence. In flight is what covers it.
        EdtMcpBridge bridge = newBridge();

        bridge.callTool(PROBE_TOOL_NAME, "{\"value\":\"x\"}"); //$NON-NLS-1$

        assertEquals("the call must be in flight while the tool runs", //$NON-NLS-1$
            1, inFlightDuringCall.get());
        assertEquals("and counted before it runs, not after it returns", //$NON-NLS-1$
            1L, ticksDuringCall.get());
        assertEquals("nothing may stay in flight once the call returned", //$NON-NLS-1$
            0, BridgeActivity.inFlight());
    }

    @Test
    public void testTicksOnlyEverGrow()
    {
        // Monotonic and unbounded, unlike a bounded ring whose size stops changing when it is
        // full - and unlike anything a user preference can switch off.
        EdtMcpBridge bridge = newBridge();
        long before = BridgeActivity.ticks();

        bridge.callTool(PROBE_TOOL_NAME, "{\"value\":\"a\"}"); //$NON-NLS-1$
        bridge.callTool(PROBE_TOOL_NAME, "{\"value\":\"b\"}"); //$NON-NLS-1$
        bridge.listTools();

        assertTrue("every bridge call must move the counter", //$NON-NLS-1$
            BridgeActivity.ticks() >= before + 3);
    }

    @Test
    public void testARejectedCallIsStillActivityAndStillReleased()
    {
        // A malformed call never reaches a tool, but the model DID just act: it is alive. And the
        // release must survive the early return, or the gauge would leak and never read idle.
        EdtMcpBridge bridge = newBridge();
        long before = BridgeActivity.ticks();

        bridge.callTool(PROBE_TOOL_NAME, "{ not json"); //$NON-NLS-1$

        assertEquals(before + 1, BridgeActivity.ticks());
        assertEquals(0, BridgeActivity.inFlight());
    }

    private static EdtMcpBridge newBridge()
    {
        return new EdtMcpBridge(McpToolRegistry.getInstance(), new McpProtocolHandler());
    }

    private static final class ActivityProbeTool implements IMcpTool
    {
        private final AtomicLong ticks;
        private final AtomicInteger inFlight;

        private ActivityProbeTool(AtomicLong ticks, AtomicInteger inFlight)
        {
            this.ticks = ticks;
            this.inFlight = inFlight;
        }

        @Override
        public String getName()
        {
            return PROBE_TOOL_NAME;
        }

        @Override
        public String getDescription()
        {
            return "Bridge activity probe"; //$NON-NLS-1$
        }

        @Override
        public String getInputSchema()
        {
            return "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
                + "\"value\":{\"type\":\"string\"}},\"required\":[\"value\"]}"; //$NON-NLS-1$
        }

        @Override
        public String execute(Map<String, String> params)
        {
            // Read from INSIDE the call: that is where a long-running tool lives.
            ticks.set(BridgeActivity.ticks());
            inFlight.set(BridgeActivity.inFlight());
            return "ok"; //$NON-NLS-1$
        }

        @Override
        public ResponseType getResponseType()
        {
            return ResponseType.TEXT;
        }
    }
}
