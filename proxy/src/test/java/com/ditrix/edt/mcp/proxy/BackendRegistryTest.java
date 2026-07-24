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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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

    // ---- parseProjectNames: Markdown-table fallback (issue #302) ----

    @Test
    public void testParseProjectNamesMarkdownFallbackFromResource()
    {
        // A backend whose list_projects predates structuredContent returns only the human
        // Markdown table (as an embedded resource). Names come from the first column.
        String md = "## Workspace Projects\n\n**Total:** 2 projects\n\n" //$NON-NLS-1$
            + "| Name | State | Path | Open | EDT Project | Natures |\n" //$NON-NLS-1$
            + "|------|-------|------|------|-------------|--------|\n" //$NON-NLS-1$
            + "| Trade | ready | /ws/Trade | Yes | Yes | V8ConfigurationNature |\n" //$NON-NLS-1$
            + "| ERPKAUT | ready | /ws/ERPKAUT | Yes | Yes | V8ExtensionNature |\n"; //$NON-NLS-1$
        assertEquals(List.of("Trade", "ERPKAUT"), //$NON-NLS-1$ //$NON-NLS-2$
            BackendRegistry.parseProjectNames(resourceResult(md)));
    }

    @Test
    public void testParseProjectNamesMarkdownFallbackFromTextBlock()
    {
        // Plain-text mode: the Markdown arrives as a text block, not a resource.
        String md = "| Name | State |\n|------|-------|\n| Trade | ready |\n"; //$NON-NLS-1$
        assertEquals(List.of("Trade"), BackendRegistry.parseProjectNames(textResult(md))); //$NON-NLS-1$
    }

    @Test
    public void testParseProjectNamesPrefersStructuredOverMarkdown()
    {
        // When BOTH are present, the machine contract wins; the table is ignored.
        JsonObject structured = new JsonObject();
        JsonArray projects = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", "FromStructured"); //$NON-NLS-1$ //$NON-NLS-2$
        projects.add(p);
        structured.add("projects", projects); //$NON-NLS-1$
        JsonObject result = resultWithContent("| Name |\n|---|\n| FromMarkdown |\n"); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$
        JsonObject response = new JsonObject();
        response.add("result", result); //$NON-NLS-1$
        assertEquals(List.of("FromStructured"), //$NON-NLS-1$
            BackendRegistry.parseProjectNames(response.toString()));
    }

    @Test
    public void testParseProjectNamesMarkdownUnescapesPipe()
    {
        // escapeForTable turns '|' into '\|'; the fallback must undo that.
        String md = "| Name | State |\n|---|---|\n| Weird\\|Name | ready |\n"; //$NON-NLS-1$
        assertEquals(List.of("Weird|Name"), BackendRegistry.parseProjectNames(resourceResult(md))); //$NON-NLS-1$
    }

    @Test
    public void testParseProjectNamesMarkdownEmptyTableYieldsEmpty()
    {
        String md = "## Workspace Projects\n\n**Total:** 0 projects\n\n*No projects found.*\n"; //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames(resourceResult(md)).isEmpty());
    }

    @Test
    public void testParseProjectNamesMarkdownDataRowNamedLikeHeaderOrSeparatorSurvives()
    {
        // A real project literally named "Name" or "---" sits in a DATA row (after the separator)
        // and must NOT be mistaken for the header/separator and dropped.
        String md = "| Name | State |\n|------|-------|\n| Name | ready |\n| --- | ready |\n"; //$NON-NLS-1$
        assertEquals(List.of("Name", "---"), //$NON-NLS-1$ //$NON-NLS-2$
            BackendRegistry.parseProjectNames(resourceResult(md)));
    }

    @Test
    public void testParseProjectNamesEmptyStructuredIsAuthoritativeNotMarkdown()
    {
        // structuredContent.projects present but EMPTY means "zero projects" (authoritative); a stale
        // Markdown row beside it must NOT be resurrected (the two are separate workspace passes).
        JsonObject structured = new JsonObject();
        structured.add("projects", new JsonArray()); // empty, but PRESENT //$NON-NLS-1$
        JsonObject result = resultWithContent("| Name |\n|---|\n| Stale |\n"); //$NON-NLS-1$
        result.add("structuredContent", structured); //$NON-NLS-1$
        JsonObject response = new JsonObject();
        response.add("result", result); //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames(response.toString()).isEmpty());
    }

    @Test
    public void testParseProjectNamesSeparatorRowValidatesTrailingCell()
    {
        // "| --- | ready" (no trailing pipe, a non-dash trailing cell) is NOT a separator; treating
        // it as one would wrongly start collecting the rows after it. No valid separator -> empty.
        String md = "| --- | ready\n| Trade | ready\n"; //$NON-NLS-1$
        assertTrue(BackendRegistry.parseProjectNames(resourceResult(md)).isEmpty());
    }

    @Test
    public void testProjectsFromMarkdownTableParsesFullColumns()
    {
        // A content-only backend's table is parsed into project objects with all list_projects columns
        // so the fan-out keeps the columns, not just the name.
        String md = "## Workspace Projects\n\n**Total:** 1 projects\n\n" //$NON-NLS-1$
            + "| Name | State | Path | Open | EDT Project | Natures |\n" //$NON-NLS-1$
            + "|------|-------|------|------|-------------|--------|\n" //$NON-NLS-1$
            + "| Trade | ready | /ws/Trade | Yes | No | V8ConfigurationNature |\n"; //$NON-NLS-1$

        com.google.gson.JsonArray projects = BackendRegistry.projectsFromMarkdownTable(resultWithContent(md));

        assertEquals(1, projects.size());
        JsonObject p = projects.get(0).getAsJsonObject();
        assertEquals("Trade", p.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("ready", p.get("state").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/ws/Trade", p.get("path").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(p.get("open").getAsBoolean()); //$NON-NLS-1$
        assertFalse(p.get("edtProject").getAsBoolean()); //$NON-NLS-1$
        assertEquals("V8ConfigurationNature", p.get("natures").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void testProjectsFromMarkdownTableOmitsDashBooleans()
    {
        // A "-" in the Open/EDT columns (a closed/uninspected project) is OMITTED, matching the
        // structured shape (absence = not inspected), rather than stored as a bogus value.
        String md = "| Name | State | Path | Open | EDT Project | Natures |\n" //$NON-NLS-1$
            + "|------|-------|------|------|-------------|--------|\n" //$NON-NLS-1$
            + "| Closed | - | - | No | - | - |\n"; //$NON-NLS-1$

        JsonObject p = BackendRegistry.projectsFromMarkdownTable(resultWithContent(md)).get(0).getAsJsonObject();

        assertEquals("Closed", p.get("name").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(p.get("open").getAsBoolean()); //$NON-NLS-1$
        assertNull("edtProject '-' must be omitted", p.get("edtProject")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- helpers ----

    /** A tools/call response whose only content is an embedded markdown resource. */
    private static String resourceResult(String markdown)
    {
        JsonObject resource = new JsonObject();
        resource.addProperty("text", markdown); //$NON-NLS-1$
        JsonObject item = new JsonObject();
        item.addProperty("type", "resource"); //$NON-NLS-1$ //$NON-NLS-2$
        item.add("resource", resource); //$NON-NLS-1$
        return wrapContent(item);
    }

    /** A tools/call response whose only content is a plain text block. */
    private static String textResult(String text)
    {
        JsonObject item = new JsonObject();
        item.addProperty("type", "text"); //$NON-NLS-1$ //$NON-NLS-2$
        item.addProperty("text", text); //$NON-NLS-1$
        return wrapContent(item);
    }

    private static String wrapContent(JsonObject contentItem)
    {
        JsonObject response = new JsonObject();
        response.add("result", resultWithContentItem(contentItem)); //$NON-NLS-1$
        return response.toString();
    }

    private static JsonObject resultWithContent(String markdown)
    {
        JsonObject resource = new JsonObject();
        resource.addProperty("text", markdown); //$NON-NLS-1$
        JsonObject item = new JsonObject();
        item.addProperty("type", "resource"); //$NON-NLS-1$ //$NON-NLS-2$
        item.add("resource", resource); //$NON-NLS-1$
        return resultWithContentItem(item);
    }

    private static JsonObject resultWithContentItem(JsonObject contentItem)
    {
        JsonArray content = new JsonArray();
        content.add(contentItem);
        JsonObject result = new JsonObject();
        result.add("content", content); //$NON-NLS-1$
        return result;
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
