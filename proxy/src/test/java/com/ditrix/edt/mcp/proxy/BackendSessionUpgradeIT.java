/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * A long-lived proxy meeting a backend that has been UPGRADED to validate MCP sessions.
 *
 * <p>{@link Backend} handshakes once and keeps the result across registry rescans, so a proxy
 * that handshook with a plugin issuing no {@code Mcp-Session-Id} caches
 * {@code handshakeDone=true} with a {@code null} session. When that plugin is upgraded and
 * starts requiring one, every forwarded call presents no session and is refused - and the
 * stale-session retry only fired on {@code 404}, so nothing ever re-handshook and the backend
 * stayed locked out until someone restarted the proxy. These tests pin the recovery, and pin
 * that it stayed narrow.
 */
public class BackendSessionUpgradeIT
{
    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public Timeout timeout = Timeout.seconds(60);

    private FakeBackend backend;
    private Backend client;

    @Before
    public void setUp() throws Exception
    {
        backend = new FakeBackend("Alpha");
        backend.start();
        client = new Backend(backend.getPort(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), 15);
    }

    @After
    public void tearDown()
    {
        if (backend != null)
        {
            backend.stop();
        }
    }

    @Test
    public void aBackendUpgradedToRequireSessionsIsRecoveredWithoutRestartingTheProxy() throws Exception
    {
        // The proxy handshakes with the OLD plugin: no session is issued, and none is needed.
        backend.setIssuesSessions(false);
        assertEquals("the sessionless handshake must work", 200, forwardPing().statusCode());
        assertEquals("and it must be cached, not repeated", 1, backend.getInitializeCount());

        // That plugin is upgraded underneath the running proxy. The cached handshake now
        // presents no session, which the upgraded plugin refuses with 400.
        backend.setIssuesSessions(true);

        HttpResponse<InputStream> response = forwardPing();

        assertEquals("the refusal must be recovered from, not returned to the client", //$NON-NLS-1$
            200, response.statusCode());
        assertTrue("recovery means a fresh handshake, not a lucky retry: " //$NON-NLS-1$
            + backend.getInitializeCount(), backend.getInitializeCount() >= 2);
        assertTrue("and the retried call must actually be answered", //$NON-NLS-1$
            bodyOf(response).contains("jsonrpc"));

        // And the recovery is durable: the next call reuses the session it just obtained.
        int handshakesSoFar = backend.getInitializeCount();
        assertEquals(200, forwardPing().statusCode());
        assertEquals("a recovered backend must not re-handshake on every call", //$NON-NLS-1$
            handshakesSoFar, backend.getInitializeCount());
    }

    @Test
    public void aStaleSessionIsStillRecoveredThroughThe404Path() throws Exception
    {
        // The pre-existing recovery must keep working: this change added a second trigger, it
        // did not replace the first.
        assertEquals(200, forwardPing().statusCode());
        backend.invalidateSessions();

        assertEquals(200, forwardPing().statusCode());
        assertTrue("a 404 must still force a re-handshake", backend.getInitializeCount() >= 2); //$NON-NLS-1$
    }

    @Test
    public void aBadRequestFromACallThatDidPresentASessionIsNotRetried() throws Exception
    {
        // The other direction of the same change: 400 is only a session signal when we sent no
        // session. A backend answering 400 to a properly-sessioned call is reporting a bad
        // request, and retrying it would turn one client error into two calls forever.
        assertEquals(200, forwardPing().statusCode());
        int handshakesAfterFirstCall = backend.getInitializeCount();

        // A malformed body: the fake answers it inside the session, so any 400 here would be
        // about the request, not the session.
        HttpResponse<InputStream> response = client.forward("{\"jsonrpc\":\"2.0\",\"id\":9}"); //$NON-NLS-1$
        drain(response);

        assertEquals("a call inside a valid session must not trigger a re-handshake", //$NON-NLS-1$
            handshakesAfterFirstCall, backend.getInitializeCount());
    }

    @Test
    public void aRefusalThatCameWithASessionIsStillARefusal() throws Exception
    {
        // The session header is not evidence that the backend agreed to talk: it is free to mint
        // one and still report the handshake failed. Judging the outcome by "an id arrived" would
        // send notifications/initialized and forward calls into an initialization that never
        // completed - and the caller would never learn why.
        backend.setRefusesInitialize(true);

        try
        {
            forwardPing();
            throw new AssertionError("a refusal is a refusal even when a session came with it"); //$NON-NLS-1$
        }
        catch (java.io.IOException expected)
        {
            assertTrue("the failure must carry the backend's own reason: " + expected.getMessage(), //$NON-NLS-1$
                expected.getMessage().contains("Session limit reached")); //$NON-NLS-1$
        }

        backend.setRefusesInitialize(false);
        assertEquals("and nothing was cached, so recovery is immediate", 200, forwardPing().statusCode()); //$NON-NLS-1$
    }

    @Test
    public void aRefusedHandshakeFailsInsteadOfLookingLikeALegacyBackend() throws Exception
    {
        // JSON-RPC reports failure INSIDE a 200, and the plugin's session cap answers exactly
        // that way: 200, an error, no session header. Cached as "a backend that issues no
        // sessions", it would make every later call present no session, be answered 400, and be
        // retried as a legacy handshake - two calls per request forever, with the real reason
        // (the cap) never reaching the caller.
        backend.setIssuesSessions(false);
        backend.setRefusesInitialize(true);

        try
        {
            forwardPing();
            throw new AssertionError("a refused handshake must not be cached as a successful one"); //$NON-NLS-1$
        }
        catch (java.io.IOException expected)
        {
            assertTrue("the failure must carry the backend's own reason, not a generic one: " //$NON-NLS-1$
                + expected.getMessage(), expected.getMessage().contains("Session limit reached")); //$NON-NLS-1$
        }

        // And nothing was cached: once the backend recovers, the next call handshakes normally
        // rather than staying stuck on a poisoned session.
        backend.setRefusesInitialize(false);
        backend.setIssuesSessions(true);
        assertEquals(200, forwardPing().statusCode());
    }

    private HttpResponse<InputStream> forwardPing() throws Exception
    {
        return client.forward("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{}}"); //$NON-NLS-1$
    }

    private static String bodyOf(HttpResponse<InputStream> response) throws Exception
    {
        try (InputStream in = response.body())
        {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void drain(HttpResponse<InputStream> response) throws Exception
    {
        bodyOf(response);
    }
}
