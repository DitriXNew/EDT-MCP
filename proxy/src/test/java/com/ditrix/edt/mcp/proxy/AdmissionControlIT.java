/**
 * MCP Server for EDT - Proxy Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * The proxy's admission control (issue #567): a bounded worker pool, and a retryable {@code 503}
 * once it is carrying more than {@link McpProxyHandler#MAX_IN_FLIGHT_REQUESTS} requests.
 *
 * <p>Before this, the pool was {@code Executors.newCachedThreadPool()} - one thread per concurrent
 * request, with nothing to shed against - in the component most likely to be reachable from a
 * network.
 *
 * <p>The saturation is produced by installing a pool this test controls, rather than by racing
 * real requests: a load test that has to WIN a race to assert anything is a flaky test, and the
 * decision under test is exactly "given a pool this deep, shed". The wire test then proves the
 * shed answer really reaches a client, headers and all.
 */
public class AdmissionControlIT
{
    /** Hard cap per test so a transport hang fails fast instead of wedging the build. */
    @Rule
    public Timeout timeout = Timeout.seconds(60);

    /** Released in tearDown so no blocked worker outlives the test. */
    private final CountDownLatch release = new CountDownLatch(1);

    private ThreadPoolExecutor saturated;
    private ProxyRoutingIT.ProxyFixture fixture;

    @After
    public void tearDown()
    {
        release.countDown();
        if (saturated != null)
        {
            saturated.shutdownNow();
        }
        if (fixture != null)
        {
            fixture.stop();
        }
    }

    @Test
    public void aHandlerWithNoPoolBehindItShedsNothing()
    {
        // A handler driven directly (a unit test, or before ProxyServer.start installs the pool)
        // has no queue to protect, and must not refuse requests because of it.
        assertFalse(newHandler().overloaded());
    }

    @Test
    public void aQuietPoolIsNotOverloadedAndASaturatedOneIs()
    {
        McpProxyHandler handler = newHandler();
        handler.setWorkerPool(saturate(0));
        assertFalse("an idle pool must not shed", handler.overloaded()); //$NON-NLS-1$

        // Exactly at the threshold is still admitted - the check is "> MAX_IN_FLIGHT_REQUESTS" -
        // and one more sheds. Pinning both edges is what stops the bound drifting by one.
        handler = newHandler();
        handler.setWorkerPool(saturate(McpProxyHandler.MAX_IN_FLIGHT_REQUESTS));
        assertFalse("at the threshold the request is still served", handler.overloaded()); //$NON-NLS-1$

        handler = newHandler();
        handler.setWorkerPool(saturate(McpProxyHandler.MAX_IN_FLIGHT_REQUESTS + 1));
        assertTrue("one past the threshold must shed", handler.overloaded()); //$NON-NLS-1$
    }

    @Test
    public void anOverloadedProxyAnswers503WithRetryAfter() throws Exception
    {
        fixture = new ProxyRoutingIT.ProxyFixture(1, 1);
        fixture.start();
        // Replace only the HANDLER's view of the pool: the server keeps serving on its own
        // threads, so the shed answer is produced and delivered exactly as it would be under a
        // genuinely full queue.
        fixture.handler().setWorkerPool(saturate(McpProxyHandler.MAX_IN_FLIGHT_REQUESTS + 1));

        HttpResponse<String> response = post(fixture.port(),
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"); //$NON-NLS-1$

        assertEquals("an overloaded proxy sheds with 503, not by dropping the connection", //$NON-NLS-1$
            503, response.statusCode());
        assertEquals("and tells the caller when to come back", //$NON-NLS-1$
            "2", response.headers().firstValue("Retry-After").orElse(null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the body must say why: " + response.body(), //$NON-NLS-1$
            response.body().contains("overloaded")); //$NON-NLS-1$
    }

    /** A handler with no server behind it - enough for the admission decision. */
    private static McpProxyHandler newHandler()
    {
        ProxyConfig config = ProxyConfig.parse(new String[] {"--port", "0", "--scan", "1-1"}, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return new McpProxyHandler(config, new BackendRegistry(config), new SessionManager());
    }

    /**
     * A single-threaded pool carrying exactly {@code depth} blocked/queued tasks, so
     * {@code active + queued} equals {@code depth} until {@link #release} is counted down.
     */
    private ThreadPoolExecutor saturate(int depth)
    {
        if (saturated != null)
        {
            saturated.shutdownNow();
        }
        saturated = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        for (int i = 0; i < depth; i++)
        {
            saturated.execute(() -> {
                try
                {
                    release.await();
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });
        }
        awaitDepth(depth);
        return saturated;
    }

    /**
     * Waits until the pool actually reports the requested depth: {@code execute} returns before
     * the worker thread has picked its task up, and {@code getActiveCount} would otherwise be
     * read in that gap and make this test flaky in the direction of a false PASS.
     */
    private void awaitDepth(int depth)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (saturated.getActiveCount() + saturated.getQueue().size() != depth)
        {
            if (System.nanoTime() > deadline)
            {
                throw new IllegalStateException("pool never reached depth " + depth //$NON-NLS-1$
                    + " (active=" + saturated.getActiveCount() //$NON-NLS-1$
                    + ", queued=" + saturated.getQueue().size() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            Thread.onSpinWait();
        }
    }

    private static HttpResponse<String> post(int port, String body) throws Exception
    {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp")) //$NON-NLS-1$ //$NON-NLS-2$
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json") //$NON-NLS-1$ //$NON-NLS-2$
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
