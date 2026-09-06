/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;
import com.ditrix.edt.mcp.server.transport.McpHttpHandler.Route;

/**
 * The transport's routing decision. {@code handleMcpRequest} needs a live exchange and the
 * server's executors, so the decision itself lives in {@link Route} and is exercised here on
 * bodies parsed by the same {@link McpProtocolHandler#parse} the handler uses.
 */
public class McpHttpHandlerTest
{
    private final McpProtocolHandler handler = new McpProtocolHandler();

    @Test
    public void testAToolCallCarryingTheQuotedWordInitializeStillRoutesAsAToolCall()
    {
        // Exactly the reachable case of #565: an argument whose VALUE is that word - searching
        // the configuration for `initialize`, or naming a method that - serializes as the very
        // token the old probe looked for, and such a call was then answered with a stray
        // Mcp-Session-Id header on both the JSON and the SSE path.
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"search_in_code\","
            + "\"arguments\":{\"query\":\"initialize\"}}}";

        assertTrue("the substring probe this replaced must really match this body, "
            + "or the test proves nothing",
            body.contains("\"" + McpConstants.METHOD_INITIALIZE + "\""));
        assertEquals(Route.TOOL_CALL, Route.of(handler.parse(body)));
    }

    @Test
    public void testTheRealInitializeStillRoutesAsInitialize()
    {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-11-25\"}}";

        assertEquals(Route.INITIALIZE, Route.of(handler.parse(body)));
    }

    @Test
    public void testEveryOtherMethodTakesThePlainProtocolPath()
    {
        assertEquals(Route.OTHER,
            Route.of(handler.parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")));
        assertEquals(Route.OTHER,
            Route.of(handler.parse("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}")));
    }

    @Test
    public void testAnUnparseableBodyTakesThePlainProtocolPath()
    {
        // parse() returns null on a syntax error; the protocol handler answers "invalid request"
        // for it, which is the OTHER path - never a session-issuing initialize.
        assertEquals(Route.OTHER, Route.of(handler.parse("{ not json")));
        assertEquals(Route.OTHER, Route.of(null));
    }

    @Test
    public void testAMethodlessBodyNamingInitializeElsewhereTakesThePlainPath()
    {
        // A notification or a malformed call whose params merely mention the word: no method,
        // so no special path. The substring probe called this one an initialize too.
        String body = "{\"jsonrpc\":\"2.0\",\"params\":{\"text\":\"initialize\"}}";

        assertTrue(body.contains("\"" + McpConstants.METHOD_INITIALIZE + "\""));
        assertEquals(Route.OTHER, Route.of(handler.parse(body)));
    }
}
