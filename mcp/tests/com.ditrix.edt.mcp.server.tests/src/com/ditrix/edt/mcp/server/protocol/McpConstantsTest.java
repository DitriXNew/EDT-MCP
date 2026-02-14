/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests for {@link McpConstants}.
 */
public class McpConstantsTest
{
    @Test
    public void testProtocolVersion()
    {
        assertNotNull(McpConstants.PROTOCOL_VERSION);
        // Must match MCP spec date format YYYY-MM-DD
        assertTrue(McpConstants.PROTOCOL_VERSION.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void testJsonRpcVersion()
    {
        assertEquals("2.0", McpConstants.JSONRPC_VERSION);
    }

    @Test
    public void testServerName()
    {
        assertEquals("edt-mcp-server", McpConstants.SERVER_NAME);
    }

    @Test
    public void testErrorCodes()
    {
        assertEquals(-32700, McpConstants.ERROR_PARSE);
        assertEquals(-32600, McpConstants.ERROR_INVALID_REQUEST);
        assertEquals(-32601, McpConstants.ERROR_METHOD_NOT_FOUND);
        assertEquals(-32602, McpConstants.ERROR_INVALID_PARAMS);
        assertEquals(-32603, McpConstants.ERROR_INTERNAL);
    }

    @Test
    public void testMcpMethods()
    {
        assertEquals("initialize", McpConstants.METHOD_INITIALIZE);
        assertEquals("notifications/initialized", McpConstants.METHOD_INITIALIZED);
        assertEquals("tools/list", McpConstants.METHOD_TOOLS_LIST);
        assertEquals("tools/call", McpConstants.METHOD_TOOLS_CALL);
    }

    @Test
    public void testHeaders()
    {
        assertEquals("MCP-Protocol-Version", McpConstants.HEADER_PROTOCOL_VERSION);
        assertEquals("MCP-Session-Id", McpConstants.HEADER_SESSION_ID);
    }
}
