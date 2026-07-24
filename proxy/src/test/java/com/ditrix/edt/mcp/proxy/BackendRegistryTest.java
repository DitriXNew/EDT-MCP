/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Unit tests for {@link BackendRegistry}: a real {@link BackendRegistry#refresh()} scan
 * against {@link FakeBackend} instances builds the live/owner/duplicate snapshot, an empty
 * scan range yields no live backends without opening a single socket, the {@code tools/list}
 * cache round-trips, and {@link BackendRegistry#parseProjectNames} parses defensively.
 */
public class BackendRegistryTest
{
    /**
     * Hard cap per test so a stuck socket fails fast instead of wedging the build. Generous
     * enough to comfortably fit the discovery-timeout test below, which deliberately waits
     * out a real ~10s HTTP timeout.
     */
    @Rule
    public final Timeout globalTimeout = Timeout.seconds(60);

    private FakeBackend backendOne;
    private FakeBackend backendTwo;

    /** Stops whichever fakes a test started (null/double-stop safe). */
    @After
    public void tearDown()
    {
        stopQuietly(backendOne);
        stopQuietly(backendTwo);
    }

    // ---- refresh() against 2 FakeBackends builds the map ----

    @Test
    public void testRefreshAgainstTwoBackendsBuildsTheProjectMap() throws IOException
    {
        int[] ports = reserveFreePorts(2);
        backendOne = new FakeBackend(ports[0], "ProjectA"); //$NON-NLS-1$
        backendTwo = new FakeBackend(ports[1], "ProjectB"); //$NON-NLS-1$
        backendOne.start();
        backendTwo.start();
        BackendRegistry registry = new BackendRegistry(scanningConfig(ports[0], ports[1]));

        registry.refresh();

        List<Backend> live = registry.live();
        assertEquals(2, live.size());
        assertEquals(ports[0], live.get(0).getPort());
        assertEquals(ports[1], live.get(1).getPort());
        assertEquals(ports[0], registry.byProject("ProjectA").getPort()); //$NON-NLS-1$
        assertEquals(ports[1], registry.byProject("ProjectB").getPort()); //$NON-NLS-1$
        assertEquals(List.of("ProjectA", "ProjectB"), registry.knownProjects()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("a completed refresh must record a timestamp", registry.lastRefreshMillis() > 0); //$NON-NLS-1$
    }

    // ---- duplicateProjects() with overlapping projects ----

    @Test
    public void testDuplicateProjectsWithOverlappingOwnership() throws IOException
    {
        int[] ports = reserveFreePorts(2);
        backendOne = new FakeBackend(ports[0], "Shared"); //$NON-NLS-1$
        backendTwo = new FakeBackend(ports[1], "Shared"); //$NON-NLS-1$
        backendOne.start();
        backendTwo.start();
        BackendRegistry registry = new BackendRegistry(scanningConfig(ports[0], ports[1]));

        registry.refresh();

        Map<String, List<Integer>> duplicates = registry.duplicateProjects();
        assertEquals(List.of(ports[0], ports[1]), duplicates.get("Shared")); //$NON-NLS-1$
        assertEquals("both duplicate holders must still be live", 2, registry.live().size()); //$NON-NLS-1$
    }

    // ---- discovery timeout bounds a hung list_projects (defect #2) ----

    /**
     * A backend that answers {@code /health} promptly but hangs on {@code list_projects} must
     * not be able to stall a whole {@link BackendRegistry#refresh()}: the internal discovery
     * call is bounded by the SHORT discovery timeout, independent of the much longer end-user
     * {@code backendTimeoutSeconds} budget. The backend still ends up LIVE (health is
     * unaffected) with an EMPTY project set (the timed-out call is treated like any other
     * {@code list_projects} failure).
     */
    @Test
    public void testDiscoveryTimeoutBoundsAHungListProjectsWithoutBlockingHealth() throws IOException
    {
        int[] ports = reserveFreePorts(1);
        backendOne = new FakeBackend(ports[0], "SlowProject"); //$NON-NLS-1$
        backendOne.setListProjectsDelayMillis(60_000L); // far longer than the discovery timeout
        backendOne.start();
        BackendRegistry registry = new BackendRegistry(scanningConfig(ports[0], ports[0]));

        long start = System.currentTimeMillis();
        registry.refresh();
        long elapsedMillis = System.currentTimeMillis() - start;

        assertTrue("a hung list_projects must not block the scan anywhere near its own 60s delay: " //$NON-NLS-1$
            + elapsedMillis + "ms", elapsedMillis < 30_000); //$NON-NLS-1$
        assertEquals("the slow backend must still be reported live - only list_projects hung, not /health", //$NON-NLS-1$
            1, registry.live().size());
        assertTrue("a backend whose list_projects didn't return in time must contribute no projects", //$NON-NLS-1$
            registry.knownProjects().isEmpty());
    }

    // ---- concurrent refresh() calls coalesce into one scan (defect #3) ----

    /**
     * Two threads calling {@link BackendRegistry#refresh()} at the same time against a
     * registry whose scan is artificially slowed must still only run the scan body ONCE: the
     * thread that loses the race waits for the in-flight scan, then reuses its fresh result
     * instead of starting a redundant duplicate scan.
     */
    @Test
    public void testConcurrentRefreshCallsCoalesceIntoASingleScan() throws Exception
    {
        int[] ports = reserveFreePorts(1);
        backendOne = new FakeBackend(ports[0], "ProjectA"); //$NON-NLS-1$
        backendOne.setListProjectsDelayMillis(500L); // wide enough to guarantee overlap below
        backendOne.start();
        BackendRegistry registry = new BackendRegistry(scanningConfig(ports[0], ports[0]));

        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable callRefresh = () -> {
            bothReady.countDown();
            awaitUninterruptibly(go);
            registry.refresh();
        };
        Thread first = new Thread(callRefresh, "refresh-1"); //$NON-NLS-1$
        Thread second = new Thread(callRefresh, "refresh-2"); //$NON-NLS-1$
        first.start();
        second.start();
        bothReady.await();
        go.countDown();
        first.join();
        second.join();

        assertEquals("two overlapping refresh() calls must coalesce into ONE scan", 1, registry.scanCount()); //$NON-NLS-1$
        assertEquals(1, registry.live().size());
        assertEquals(List.of("ProjectA"), registry.knownProjects()); //$NON-NLS-1$
    }

    // ---- empty scan range -> live() empty ----

    @Test
    public void testEmptyScanRangeYieldsNoLiveBackends()
    {
        // FROM > TO is the documented empty-range configuration - no socket is ever opened.
        BackendRegistry registry = new BackendRegistry(scanningConfig(2, 1));
        assertEquals(0L, registry.lastRefreshMillis());

        registry.refresh();

        assertTrue(registry.live().isEmpty());
        assertTrue(registry.knownProjects().isEmpty());
        assertTrue(registry.duplicateProjects().isEmpty());
        assertTrue("refresh() must still record that it ran", registry.lastRefreshMillis() > 0); //$NON-NLS-1$
    }

    // ---- cachedToolsListResponse round-trip ----

    @Test
    public void testCachedToolsListResponseRoundTrip()
    {
        BackendRegistry registry = new BackendRegistry(scanningConfig(2, 1));
        assertNull(registry.cachedToolsListResponse());

        registry.cacheToolsListResponse("{\"jsonrpc\":\"2.0\"}"); //$NON-NLS-1$

        assertEquals("{\"jsonrpc\":\"2.0\"}", registry.cachedToolsListResponse()); //$NON-NLS-1$
    }

    // ---- parseProjectNames: package-private, directly unit-testable ----

    @Test
    public void testParseProjectNamesValid()
    {
        String raw = "{\"result\":{\"structuredContent\":{\"projects\":" //$NON-NLS-1$
            + "[{\"name\":\"A\"},{\"name\":\"B\"}]}}}"; //$NON-NLS-1$

        assertEquals(List.of("A", "B"), BackendRegistry.parseProjectNames(raw)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testParseProjectNamesNullOrMalformedYieldsEmpty()
    {
        assertTrue(BackendRegistry.parseProjectNames(null).isEmpty());
        assertTrue(BackendRegistry.parseProjectNames("not json at all {").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testParseProjectNamesMissingLevelsYieldsEmpty()
    {
        assertTrue(BackendRegistry.parseProjectNames("{}").isEmpty()); //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames("{\"result\":{}}").isEmpty()); //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames("{\"result\":{\"structuredContent\":{}}}").isEmpty()); //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames(
            "{\"result\":{\"structuredContent\":{\"projects\":\"not-an-array\"}}}").isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void testParseProjectNamesSkipsBlankAndNonObjectEntries()
    {
        String raw = "{\"result\":{\"structuredContent\":{\"projects\":" //$NON-NLS-1$
            + "[{\"name\":\"A\"},{\"name\":\"  \"},\"oops\",{}]}}}"; //$NON-NLS-1$

        assertEquals(List.of("A"), BackendRegistry.parseProjectNames(raw)); //$NON-NLS-1$
    }

    @Test
    public void testParseProjectNamesRejectsAFailedPayload()
    {
        // A payload that declares failure is not a project list, even with a projects array -
        // accepting it would register phantom projects for routing.
        String raw = "{\"result\":{\"structuredContent\":{\"success\":false," //$NON-NLS-1$
            + "\"projects\":[{\"name\":\"Phantom\"}]}}}"; //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames(raw).isEmpty());
        assertFalse(BackendRegistry.hasStructuredProjects(raw));
    }

    @Test
    public void testPlainTextModeStillYieldsTheMachineList()
    {
        // Plain-text mode (the Cursor-compatibility preference) delivers the SAME JSON payload as
        // content text instead of structuredContent - that is still the machine contract, so such a
        // backend must be supported and routable, not reported as an unsupported plugin version.
        String payload = "{\"success\":true,\"projects\":[{\"name\":\"Trade\"}]}"; //$NON-NLS-1$
        String raw = textResult(payload);
        assertTrue(BackendRegistry.hasStructuredProjects(raw));
        assertEquals(List.of("Trade"), BackendRegistry.parseProjectNames(raw)); //$NON-NLS-1$
    }

    @Test
    public void testMarkdownOnlyResponseIsNotAMachineList()
    {
        // A genuinely old plugin answers with the human table only - no Markdown scraping, so it
        // yields nothing and is classified as unsupported by the caller.
        String md = "| Name | State |\n|---|---|\n| Trade | ready |\n"; //$NON-NLS-1$
        assertFalse(BackendRegistry.hasStructuredProjects(textResult(md)));
        assertTrue(BackendRegistry.parseProjectNames(textResult(md)).isEmpty());
    }

    @Test
    public void testToolErrorWithStaleProjectsContributesNothing()
    {
        // isError:true is checked FIRST, so a failed response that still carries a partial/stale
        // projects array cannot register those names for routing.
        String raw = "{\"result\":{\"isError\":true,\"structuredContent\":{\"success\":true," //$NON-NLS-1$
            + "\"projects\":[{\"name\":\"Stale\"}]}}}"; //$NON-NLS-1$
        // The response DOES carry a machine list, so only the isError-FIRST ordering keeps those
        // names out of the routing table (the probe returns no projects for a tool error).
        assertTrue("the response must be recognised as a tool error", //$NON-NLS-1$
            BackendRegistry.isToolError(raw));
        assertTrue("...even though it also carries a projects array", //$NON-NLS-1$
            BackendRegistry.hasStructuredProjects(raw));
    }

    // ---- helpers ----

    /** A tools/call response whose only content is a plain text block (plain-text mode shape). */
    private static String textResult(String text)
    {
        com.google.gson.JsonObject item = new com.google.gson.JsonObject();
        item.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("text", text); //$NON-NLS-1$
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        content.add(item);
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.add("content", content); //$NON-NLS-1$
        com.google.gson.JsonObject response = new com.google.gson.JsonObject();
        response.add("result", result); //$NON-NLS-1$
        return response.toString();
    }

    private static ProxyConfig scanningConfig(int from, int to)
    {
        return ProxyConfig.parse(new String[] { "--scan", from + "-" + to }, Map.of()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Awaits a latch, converting an {@link InterruptedException} into re-asserting the
     * thread's interrupt flag instead of propagating a checked exception - keeps the
     * concurrency test's {@link Runnable} lambda simple.
     */
    private static void awaitUninterruptibly(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Reserves {@code count} currently-free TCP ports by binding port-0 sockets and closing
     * them right away (the same approach the integration tests use); the small race between
     * releasing the port and the {@link FakeBackend} re-binding it is accepted.
     */
    private static int[] reserveFreePorts(int count) throws IOException
    {
        ServerSocket[] sockets = new ServerSocket[count];
        int[] ports = new int[count];
        try
        {
            for (int i = 0; i < count; i++)
            {
                sockets[i] = new ServerSocket(0);
                sockets[i].setReuseAddress(true);
                ports[i] = sockets[i].getLocalPort();
            }
        }
        finally
        {
            for (ServerSocket socket : sockets)
            {
                if (socket != null)
                {
                    socket.close();
                }
            }
        }
        Arrays.sort(ports);
        return ports;
    }

    private static void stopQuietly(FakeBackend backend)
    {
        if (backend == null)
        {
            return;
        }
        try
        {
            backend.stop();
        }
        catch (RuntimeException e)
        {
            // already stopped - nothing to clean up
        }
    }
}
