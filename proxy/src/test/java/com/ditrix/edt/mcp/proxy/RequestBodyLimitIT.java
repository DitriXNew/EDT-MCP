/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.ditrix.edt.mcp.proxy.ProxyRoutingIT.ProxyFixture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Integration test for the {@code McpProxyHandler} request-body size cap (issue #253
 * hardening, {@code McpProxyHandler.MAX_BODY_BYTES} = 4 MiB).
 *
 * <p>Two ways an oversized body can reach the handler, both proven to be rejected with
 * {@code 413} without the handler ever buffering the whole thing:
 * <ul>
 * <li>a real body sent with a genuine, oversized {@code Content-Length} header - rejected
 * up front, before a single byte of the body is read;</li>
 * <li>a body with NO declared length (chunked transfer, the JDK client's behaviour for
 * {@link HttpRequest.BodyPublishers#ofInputStream}) that only turns out to be oversized while
 * streaming - caught by the bounded read itself.</li>
 * </ul>
 *
 * <p>No backend is needed: the size cap is enforced in {@code McpProxyHandler.readBody}, before
 * any session or routing logic runs, so a bare {@code initialize} POST is enough to exercise it.
 * A payload a few MiB over the cap stands in for an attacker-scale (multi-GB) one; what proves
 * the fix does not OOM on that scale is that the handler caps its buffer at
 * {@code MAX_BODY_BYTES + 1} bytes regardless of how much more the client sends (see
 * {@code McpProxyHandler.readBody}), which these tests exercise the same code path for.
 */
public class RequestBodyLimitIT
{
    /** Mirrors {@code McpProxyHandler.MAX_BODY_BYTES} - kept independent on purpose (a black-box test). */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    /** Comfortably over the cap without being an unreasonably slow test payload. */
    private static final int OVERSIZED_BODY_BYTES = MAX_BODY_BYTES + (1024 * 1024);

    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ProxyFixture proxy;
    private URI mcpUri;

    /**
     * Starts the proxy with an empty (both ports closed) scan range - no backend is needed to
     * exercise the body-size cap, which is enforced before routing.
     */
    @Before
    public void setUp() throws Exception
    {
        int[] deadPorts = ProxyRoutingIT.reserveFreePorts(2); //$NON-NLS-1$
        proxy = new ProxyFixture(deadPorts[0], deadPorts[1]);
        proxy.start();
        mcpUri = URI.create("http://127.0.0.1:" + proxy.port() + "/mcp"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Stops the proxy. */
    @After
    public void tearDown()
    {
        if (proxy != null)
        {
            proxy.stop();
        }
    }

    /**
     * A body sent with a genuine {@code Content-Length} header over the cap must be rejected
     * with {@code 413} - proving the header fast-path: the handler must reject before reading
     * any of the (oversized) body.
     */
    @Test
    public void testOversizedContentLengthBodyRejectedWith413() throws Exception
    {
        byte[] body = oversizedPayload();
        HttpRequest request = HttpRequest.newBuilder(mcpUri)
            .timeout(Duration.ofSeconds(30)) //$NON-NLS-1$
            .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .header("Accept", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertRejectedWith413(response);
    }

    /**
     * A body with NO declared {@code Content-Length} (the JDK client streams it chunked when
     * given {@link HttpRequest.BodyPublishers#ofInputStream}) that exceeds the cap only while
     * being read must ALSO be rejected with {@code 413} - proving the bounded-read fallback
     * catches what the header check cannot see up front.
     */
    @Test
    public void testOversizedChunkedBodyWithNoContentLengthRejectedWith413() throws Exception
    {
        byte[] body = oversizedPayload();
        HttpRequest request = HttpRequest.newBuilder(mcpUri)
            .timeout(Duration.ofSeconds(30)) //$NON-NLS-1$
            .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .header("Accept", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
            .build();
        // Sanity-check the premise: an unknown-length publisher carries no Content-Length,
        // which is exactly the case the bounded read (not the header fast-path) must catch.
        assertTrue("test premise: the publisher must report an unknown content length", //$NON-NLS-1$
            request.bodyPublisher().orElseThrow().contentLength() <= 0);

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertRejectedWith413(response);
    }

    /**
     * A body AT the cap (not over it) must still be processed normally - proves the check is
     * "over the cap", not an off-by-one that also rejects legitimate large-but-allowed bodies.
     * An unparseable-but-allowed-size body reaches the existing "malformed JSON-RPC" handling
     * (200 with a JSON-RPC error), never a transport-level 413.
     */
    @Test
    public void testBodyAtTheCapIsNotRejectedByTheSizeCheck() throws Exception
    {
        byte[] body = new byte[MAX_BODY_BYTES];
        Arrays.fill(body, (byte)'x');
        HttpRequest request = HttpRequest.newBuilder(mcpUri)
            .timeout(Duration.ofSeconds(30)) //$NON-NLS-1$
            .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .header("Accept", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals("a body AT the cap must not hit the 413 size guard: " + response.body(), //$NON-NLS-1$
            200, response.statusCode());
    }

    private static byte[] oversizedPayload()
    {
        byte[] body = new byte[OVERSIZED_BODY_BYTES];
        Arrays.fill(body, (byte)'x');
        return body;
    }

    private static void assertRejectedWith413(HttpResponse<String> response)
    {
        assertEquals("an oversized body must be rejected at the transport level: " + response.body(), //$NON-NLS-1$
            413, response.statusCode());
        JsonObject parsed = JsonParser.parseString(response.body()).getAsJsonObject();
        assertTrue("413 body must carry a JSON-RPC error envelope: " + parsed, parsed.has("error")); //$NON-NLS-1$ //$NON-NLS-2$
        String message = parsed.getAsJsonObject("error").get("message").getAsString(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("413 message must name the byte limit: " + message, //$NON-NLS-1$
            message.contains(String.valueOf(MAX_BODY_BYTES)));
    }
}
