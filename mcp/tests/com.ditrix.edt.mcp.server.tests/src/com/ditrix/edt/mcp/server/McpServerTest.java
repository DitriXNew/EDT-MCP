/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import com.ditrix.edt.mcp.server.transport.HttpTransport;

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
    public void testTheBindAgreesWithTheAuthorizerAboutWhatCountsAsAToken()
    {
        // Two notions of "a token is set" would fail in the worst direction: the bind opens the
        // port to the network on a value the authorizer trims away and then rejects, leaving a
        // remotely reachable server that answers nothing. Both read HttpTransport.normalizeToken,
        // and this pins them together on the values where they used to disagree.
        for (String configured : new String[] { null, "", "   ", "\t\n", "s3cret", "  s3cret  " })
        {
            boolean bindPermitted = McpServer.remoteBindRefusal(true, configured, PORT) == null;
            boolean authorizerSeesAToken = !HttpTransport.normalizeToken(configured).isEmpty();
            assertEquals("the bind and the authorizer disagree about " + describe(configured),
                authorizerSeesAToken, bindPermitted);
        }
    }

    private static String describe(String token)
    {
        return token == null ? "null" : "'" + token + "'";
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

    /**
     * The preferences page persists the new settings and then RESTARTS, so a refusal that lived
     * only inside {@code start()} would arrive after {@code stop()} had already taken a healthy
     * loopback listener offline - failing open in the one way the refusal exists to prevent.
     */
    @Test
    public void testARefusedRestartNeverStopsTheRunningServer()
    {
        RecordingServer server = new RecordingServer(new McpServer.BindConfig(true, ""));

        try
        {
            server.restart(PORT);
            fail("a restart into an unauthenticated remote bind must be refused");
        }
        catch (IOException refused)
        {
            assertTrue(refused.getMessage(), refused.getMessage().contains("auth token"));
        }
        assertEquals("a refused reconfiguration must leave the live server running", "",
            server.calls());
    }

    @Test
    public void testAPermittedRestartStopsAndThenStarts() throws IOException
    {
        RecordingServer server = new RecordingServer(new McpServer.BindConfig(true, "s3cret"));

        server.restart(PORT);

        assertEquals("stop start ", server.calls());
    }

    /**
     * The authorizer asks the server whether the open listener faces the network, and answers
     * "refuse everything" when it does and the token has since been erased. A server that never
     * opened one must therefore not claim it did - otherwise a stale true would refuse requests
     * on a perfectly good loopback bind, and a stale false would serve a remote one unguarded.
     */
    @Test
    public void testAServerThatNeverBoundRemotelyDoesNotClaimItDid()
    {
        McpServer neverStarted = new McpServer();
        assertFalse("a server that was never started is not bound to anything",
            neverStarted.isBoundRemotely());

        // A real start() that stops at the refusal: it must not have reached the socket, and
        // must not have recorded the remote bind it was refused.
        McpServer refused = new McpServer()
        {
            @Override
            BindConfig readBindConfig()
            {
                return new BindConfig(true, "   ");
            }
        };
        try
        {
            refused.start(PORT);
            fail("an unauthenticated remote bind must be refused");
        }
        catch (IOException expected)
        {
            assertTrue(expected.getMessage(), expected.getMessage().contains("auth token"));
        }
        assertFalse("a refused start must leave the flag where it was", refused.isBoundRemotely());

        refused.stop();
        assertFalse("and stop() clears it whichever way the start ended", refused.isBoundRemotely());
    }

    /**
     * A server whose configuration is supplied rather than read, and whose lifecycle calls are
     * recorded instead of performed - {@code start()} binds a socket, which a unit test must not.
     */
    private static final class RecordingServer
        extends McpServer
    {
        private final BindConfig config;
        private final StringBuilder calls = new StringBuilder();

        RecordingServer(BindConfig config)
        {
            this.config = config;
        }

        String calls()
        {
            return calls.toString();
        }

        @Override
        BindConfig readBindConfig()
        {
            return config;
        }

        @Override
        public synchronized void stop()
        {
            calls.append("stop ");
        }

        @Override
        public synchronized void start(int port)
        {
            calls.append("start ");
        }
    }
}
