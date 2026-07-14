/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;

/**
 * Direct wire-level coverage of {@code POST /admin/shutdown} (issue #253 follow-up, spec
 * section 7/8): accepted from a loopback caller - the ONLY remote address this in-process
 * harness can exercise; a genuinely non-loopback remote peer cannot be faked without a real
 * second host, so the {@code 403} branch is instead unit-tested directly on the address-check
 * helper in {@link ProxyServerTest} - rejected for any HTTP method other than {@code POST}, and
 * proven to actually stop the server afterwards.
 */
public class AdminShutdownIT
{
    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * A {@code POST} from loopback must be accepted ({@code 200 {"status":"stopping"}}) and the
     * proxy must actually stop shortly afterwards.
     */
    @Test
    public void testPostFromLoopbackStopsTheProxy() throws Exception
    {
        int deadPort = ProxyRoutingIT.reserveFreePorts(1)[0]; // no backend needed for this scenario
        ProxyFixture proxy = new ProxyFixture(deadPort, deadPort);
        proxy.start();
        int proxyPort = proxy.port();
        HttpClient client = HttpClient.newBuilder().connectTimeout(CLIENT_TIMEOUT).build();

        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(adminShutdownUri(proxyPort))
                .timeout(CLIENT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue("the response must report the proxy is stopping: " + response.body(), //$NON-NLS-1$
            response.body().contains("stopping")); //$NON-NLS-1$

        ProxyRoutingIT.awaitHealthUnreachable(proxyPort, 10_000);
    }

    /**
     * Any HTTP method other than {@code POST} must be rejected with {@code 405}, without
     * stopping the proxy.
     */
    @Test
    public void testGetMethodRejectedWith405AndProxyStaysUp() throws Exception
    {
        int deadPort = ProxyRoutingIT.reserveFreePorts(1)[0];
        ProxyFixture proxy = new ProxyFixture(deadPort, deadPort);
        proxy.start();
        try
        {
            HttpClient client = HttpClient.newBuilder().connectTimeout(CLIENT_TIMEOUT).build();

            HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(adminShutdownUri(proxy.port())).timeout(CLIENT_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());

            // The proxy must still be answering - a rejected GET must not have stopped it.
            HttpResponse<String> health = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + proxy.port() + "/health")) //$NON-NLS-1$ //$NON-NLS-2$
                    .timeout(CLIENT_TIMEOUT)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
        }
        finally
        {
            proxy.stop();
        }
    }

    private static URI adminShutdownUri(int port)
    {
        return URI.create("http://127.0.0.1:" + port + "/admin/shutdown"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
