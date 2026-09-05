/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The start-time admission decision of {@link McpServer}. Binding every interface exposes the
 * whole tool surface - arbitrary BSL included - and the shared-token check is a no-op while the
 * token is empty, so that combination is refused instead of warned about (#562).
 */
public class McpServerTest
{
    private static final int PORT = 8765;

    @Test
    public void testRemoteAccessWithNoTokenIsRefused()
    {
        String refusal = McpServer.remoteBindRefusal(true, "", PORT);

        assertNotNull("remote access with an empty token must not start", refusal);
        assertTrue(refusal, refusal.contains(Integer.toString(PORT)));
        // Both ways out have to be in the message: the caller cannot guess that turning the
        // preference off is an option, and cannot find the token field without being told.
        assertTrue(refusal, refusal.contains("auth token"));
        assertTrue(refusal, refusal.contains("Allow remote access"));
    }

    @Test
    public void testRemoteAccessWithNoTokenAtAllIsRefused()
    {
        // No preference store (headless, or a shutdown race) reads the token back as null.
        assertNotNull(McpServer.remoteBindRefusal(true, null, PORT));
    }

    @Test
    public void testAWhitespaceOnlyTokenCountsAsNoToken()
    {
        assertNotNull(McpServer.remoteBindRefusal(true, "   ", PORT));
        assertNotNull(McpServer.remoteBindRefusal(true, "\t\n", PORT));
    }

    @Test
    public void testRemoteAccessWithATokenStarts()
    {
        assertNull(McpServer.remoteBindRefusal(true, "s3cret", PORT));
    }

    @Test
    public void testALoopbackBindNeverNeedsAToken()
    {
        // The token stays OPTIONAL for the default bind: loopback is the access control there,
        // and requiring a token would break every existing local setup.
        assertNull(McpServer.remoteBindRefusal(false, "", PORT));
        assertNull(McpServer.remoteBindRefusal(false, null, PORT));
        assertNull(McpServer.remoteBindRefusal(false, "s3cret", PORT));
    }
}
