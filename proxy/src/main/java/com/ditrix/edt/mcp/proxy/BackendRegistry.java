/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Discovery registry of running EDT-MCP instances.
 * <p>
 * {@link #refresh()} port-scans the configured range, health-probes every port, asks each
 * live backend for its workspace projects ({@code list_projects}, bounded by the SHORT
 * {@link #DISCOVERY_TIMEOUT_SECONDS} so one backend hung on a tool call cannot stall the
 * whole scan), and atomically swaps in a new immutable routing snapshot: the live backend
 * list, the project-to-backend owner map, and the duplicate-project map. Readers always see
 * a consistent snapshot; a failed or mid-flight refresh never exposes a half-built state.
 * <p>
 * {@link Backend} instances are cached per port across refreshes so their MCP sessions
 * survive periodic rescans (no re-handshake every cycle).
 * <p>
 * {@link #refresh()} is called concurrently from the periodic scheduler AND from every HTTP
 * thread's on-miss rescan ({@link ProjectRouter}); see its javadoc for how overlapping calls
 * coalesce into a single scan without starving a legitimate sequential rescan.
 */
public final class BackendRegistry
{
    private static final Logger LOGGER = Logger.getLogger(BackendRegistry.class.getName());

    /** Connect timeout for all backend HTTP traffic; a dead port must fail a scan fast. */
    private static final int CONNECT_TIMEOUT_SECONDS = 2;

    /**
     * Timeout, in seconds, for the internal {@code list_projects} call a scan makes to each
     * live backend. Deliberately SHORT and independent of {@link ProxyConfig#backendTimeoutSeconds}
     * (the end-user routed-call budget, 300s by default): discovery only needs to know whether a
     * backend answers promptly, and a backend that answers {@code /health} but hangs on a tool
     * call must not be able to wedge the single-threaded periodic refresh, {@code Main}'s
     * synchronous first refresh (delays server start), or the inline on-miss rescan running on
     * the HTTP request thread ({@link ProjectRouter#route}).
     */
    private static final int DISCOVERY_TIMEOUT_SECONDS = 10;

    /**
     * How long a just-built snapshot is considered fresh enough that a caller who had to WAIT
     * for an in-flight scan can reuse it instead of starting a duplicate one right behind it.
     * This absorbs an on-miss stampede (many HTTP threads missing the same or different
     * projects at once, or the periodic cycle colliding with one of them) without delaying
     * anything: the periodic cycle ({@link ProxyConfig#refreshSeconds}) is an order of
     * magnitude longer than this window. See {@link #refresh()} for exactly when this applies
     * - a caller that finds the scan lock FREE always performs a real scan, so a legitimate
     * sequential on-miss rescan (e.g. right after a hot-plugged backend started) is never
     * starved by this debounce.
     */
    private static final long DEBOUNCE_MILLIS = 1000;

    private final ProxyConfig cfg;
    private final HttpClient client;

    /** Per-port backend cache: instances (and their sessions) survive refreshes. */
    private final Map<Integer, Backend> backendsByPort = new ConcurrentHashMap<>();

    /** Guards the scan body so at most one thread scans at a time; see {@link #refresh()}. */
    private final ReentrantLock scanLock = new ReentrantLock();

    /** Test seam: counts how many times the scan body actually ran (debounced/coalesced
     * calls do not increment it). Package-private for direct assertion in concurrency tests. */
    private final AtomicInteger scanCount = new AtomicInteger(0);

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile String cachedToolsList;
    private volatile long lastRefreshMillis;

    /** Test seam: when set, {@link #refresh()} runs this instead of scanning real ports. */
    private volatile Runnable refreshOverride;

    /**
     * Creates a registry scanning the range configured in {@code cfg}.
     *
     * @param cfg the proxy configuration (scan range, per-call timeout, refresh period)
     */
    public BackendRegistry(ProxyConfig cfg)
    {
        this.cfg = cfg;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();
    }

    /**
     * Returns the proxy configuration this registry was built with (scan range, timeouts).
     * Exposed so the routing/status layers can report the scan range without re-plumbing
     * the config through every collaborator.
     *
     * @return the proxy configuration
     */
    public ProxyConfig getConfig()
    {
        return cfg;
    }

    /**
     * Rescans the configured port range and atomically rebuilds the routing snapshot,
     * coalescing overlapping calls into a single scan.
     * <p>
     * For every port in {@code scanFrom..scanTo} (inclusive): probe {@code /health}; when
     * live, fetch the backend's project names via {@code list_projects}. A backend whose
     * project list cannot be fetched or parsed stays LIVE with an empty project set (it is
     * still reachable for unscoped calls and fan-out). The new snapshot replaces the old
     * one in a single volatile write, so readers never observe a half-updated registry.
     * <p>
     * <b>Concurrency.</b> A caller that acquires {@link #scanLock} uncontended (the common
     * case for a lone periodic tick, or a sequential on-miss rescan such as the one right
     * after a hot-plugged backend started) ALWAYS performs a real scan - never starved or
     * skipped just because a previous scan finished recently. A caller that instead finds a
     * scan already IN FLIGHT waits for it, then, once it holds the lock, reuses that scan's
     * result when it is still fresher than {@link #DEBOUNCE_MILLIS} instead of starting a
     * redundant duplicate scan right behind it. This is what absorbs an on-miss stampede
     * (several HTTP threads missing at once) without ever delaying a genuinely new scan.
     */
    public void refresh()
    {
        if (scanLock.tryLock())
        {
            try
            {
                doScan();
            }
            finally
            {
                scanLock.unlock();
            }
            return;
        }

        // Another thread is scanning right now - wait for it, then decide whether its
        // result is fresh enough to reuse instead of scanning again immediately behind it.
        scanLock.lock();
        try
        {
            if (System.currentTimeMillis() - lastRefreshMillis >= DEBOUNCE_MILLIS)
            {
                doScan();
            }
        }
        finally
        {
            scanLock.unlock();
        }
    }

    /**
     * Performs one real scan (or runs the test override) and swaps in the resulting
     * snapshot. Always called with {@link #scanLock} held, so at most one invocation runs at
     * a time; {@link #scanCount} is incremented exactly once per call, regardless of whether
     * the scan finds anything.
     */
    private void doScan()
    {
        scanCount.incrementAndGet();

        Runnable override = refreshOverride;
        if (override != null)
        {
            override.run();
            lastRefreshMillis = System.currentTimeMillis();
            return;
        }

        List<Backend> live = new ArrayList<>();
        Map<String, List<Backend>> holders = new LinkedHashMap<>();
        for (int port = cfg.scanFrom; port <= cfg.scanTo; port++)
        {
            if (Thread.currentThread().isInterrupted())
            {
                break;
            }
            Backend backend = backendsByPort.computeIfAbsent(port,
                p -> new Backend(p, client, cfg.backendTimeoutSeconds));
            if (!backend.probeHealth())
            {
                continue;
            }
            live.add(backend);
            for (String project : fetchProjectNames(backend))
            {
                holders.computeIfAbsent(project, k -> new ArrayList<>()).add(backend);
            }
        }

        Snapshot previous = snapshot;
        snapshot = Snapshot.build(live, holders);
        lastRefreshMillis = System.currentTimeMillis();
        logChange(previous, snapshot);
    }

    /**
     * Test seam: reports how many times the real scan body ({@link #doScan()}) actually ran,
     * so a concurrency test can prove overlapping {@link #refresh()} calls coalesced into a
     * single scan. Package-private on purpose.
     *
     * @return the number of completed scans (real or overridden) since construction
     */
    int scanCount()
    {
        return scanCount.get();
    }

    /**
     * Returns the live backends of the current snapshot, in ascending port order.
     *
     * @return an unmodifiable snapshot list; never {@code null}
     */
    public List<Backend> live()
    {
        return snapshot.live;
    }

    /**
     * Resolves the backend that owns a project. When a project is served by MULTIPLE
     * backends this returns the lowest-port holder; callers that must not route an
     * ambiguous project check {@link #duplicateProjects()} first.
     *
     * @param projectName the EDT project name
     * @return the owning backend, or {@code null} when no live backend serves the project
     */
    public Backend byProject(String projectName)
    {
        return projectName == null ? null : snapshot.owners.get(projectName);
    }

    /**
     * Returns the projects served by MORE than one live backend.
     *
     * @return an unmodifiable map of project name to the ascending ports holding it;
     *         only entries with two or more ports are present
     */
    public Map<String, List<Integer>> duplicateProjects()
    {
        return snapshot.duplicates;
    }

    /**
     * Returns all project names known to the current snapshot, sorted alphabetically.
     *
     * @return an unmodifiable sorted list; never {@code null}
     */
    public List<String> knownProjects()
    {
        return snapshot.knownProjects;
    }

    /**
     * Returns the projects of every live backend, keyed by ascending port. A live backend
     * with no (fetchable) projects maps to an empty list; a duplicated project appears
     * under every backend that holds it. Used to render actionable routing errors and the
     * {@code router_status} payload.
     *
     * @return an unmodifiable map of port to its sorted project names
     */
    public Map<Integer, List<String>> projectsByPort()
    {
        return snapshot.projectsByPort;
    }

    /**
     * Returns the last successfully forwarded raw {@code tools/list} JSON-RPC response,
     * kept so the proxy can keep serving a tool list while zero backends are alive.
     *
     * @return the cached raw response, or {@code null} when none was stored yet
     */
    public String cachedToolsListResponse()
    {
        return cachedToolsList;
    }

    /**
     * Stores a raw {@code tools/list} JSON-RPC response for later zero-backend serving.
     * Called by the request handler after a successful forward.
     *
     * @param raw the raw JSON-RPC response string
     */
    public void cacheToolsListResponse(String raw)
    {
        this.cachedToolsList = raw;
    }

    /**
     * Returns the wall-clock time of the last completed {@link #refresh()}.
     *
     * @return epoch milliseconds, or {@code 0} when no refresh ran yet
     */
    public long lastRefreshMillis()
    {
        return lastRefreshMillis;
    }

    /**
     * Schedules {@link #refresh()} at the configured fixed delay on the given executor.
     * A failing refresh is logged and never kills the schedule.
     *
     * @param ses the executor that owns the periodic task
     */
    public void startPeriodicRefresh(ScheduledExecutorService ses)
    {
        ses.scheduleWithFixedDelay(() -> {
            try
            {
                refresh();
            }
            catch (RuntimeException e)
            {
                LOGGER.log(Level.WARNING, "Periodic backend refresh failed", e); //$NON-NLS-1$
            }
        }, cfg.refreshSeconds, cfg.refreshSeconds, TimeUnit.SECONDS);
    }

    /**
     * Test seam: replaces the scan performed by {@link #refresh()} with the given action
     * (typically one that calls {@link #installStateForTest}). {@code null} restores the
     * real port scan. Package-private on purpose — production code never calls this.
     *
     * @param override the refresh replacement, or {@code null} for the real scan
     */
    void setRefreshForTest(Runnable override)
    {
        this.refreshOverride = override;
    }

    /**
     * Test seam: installs a routing snapshot directly, bypassing the port scan, so router
     * rules can be unit-tested without sockets. Package-private on purpose.
     *
     * @param liveBackends the backends to expose as live (any order; sorted by port here)
     * @param projectHolders project name to the backends holding it (any order)
     */
    void installStateForTest(List<Backend> liveBackends, Map<String, List<Backend>> projectHolders)
    {
        snapshot = Snapshot.build(liveBackends, projectHolders);
    }

    /**
     * Fetches a backend's project names via {@code list_projects}, defensively: any
     * transport or shape failure yields an empty list and the backend stays live. Bounded by
     * the SHORT {@link #DISCOVERY_TIMEOUT_SECONDS} rather than the backend's own end-user
     * {@link ProxyConfig#backendTimeoutSeconds} - a backend that answers {@code /health} but
     * hangs on this call must not be able to stall the whole scan.
     *
     * @param backend the live backend to query
     * @return the project names; empty on any failure
     */
    private static List<String> fetchProjectNames(Backend backend)
    {
        try
        {
            String raw = backend.callToolBlocking("list_projects", new JsonObject(), DISCOVERY_TIMEOUT_SECONDS); //$NON-NLS-1$
            return parseProjectNames(raw);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return List.of();
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(Level.FINE,
                "list_projects failed on backend :" + backend.getPort() + " - treating as no projects", e); //$NON-NLS-1$ //$NON-NLS-2$
            return List.of();
        }
    }

    /**
     * Parses the project names out of a raw {@code list_projects} JSON-RPC response.
     * <p>
     * PRIMARY: the machine contract {@code result.structuredContent.projects[*].name}.
     * FALLBACK (issue #302): when a backend's {@code list_projects} predates that contract
     * (an older or third-party plugin build that returns ONLY the human Markdown table in
     * {@code content}), the project names are recovered from the first column of that table,
     * so routing still works without a plugin upgrade.
     * <p>
     * Any missing level or unexpected shape yields the names collected so far (usually an
     * empty list) — never an exception. Package-private for direct unit testing.
     *
     * @param rawResponse the raw JSON-RPC response string; may be anything
     * @return the project names; never {@code null}
     */
    static List<String> parseProjectNames(String rawResponse)
    {
        JsonObject response = Json.parseObject(rawResponse);
        if (response == null)
        {
            return new ArrayList<>();
        }
        JsonObject result = Json.obj(response, "result"); //$NON-NLS-1$
        if (result == null)
        {
            return new ArrayList<>();
        }
        // PRIMARY (authoritative): structuredContent.projects. When that array is PRESENT it is the
        // definitive answer - even an EMPTY array means "this backend has zero projects", so we must
        // NOT fall back to a possibly-stale Markdown table (the two are built in separate workspace
        // passes, so an empty structured list beside a stale table row must not resurrect it).
        JsonObject structured = Json.obj(result, "structuredContent"); //$NON-NLS-1$
        if (structured != null)
        {
            JsonElement projects = structured.get("projects"); //$NON-NLS-1$
            if (projects != null && projects.isJsonArray())
            {
                return namesFromProjectsArray(projects.getAsJsonArray());
            }
        }
        // FALLBACK: no structured contract at all (an older/third-party build) -> the Markdown table.
        return namesFromMarkdownTable(result);
    }

    /**
     * Collects {@code [*].name} from a {@code structuredContent.projects} array, skipping blank
     * names and non-object entries.
     *
     * @param projects the {@code projects} JSON array
     * @return the project names; never {@code null}
     */
    private static List<String> namesFromProjectsArray(JsonArray projects)
    {
        List<String> names = new ArrayList<>();
        for (JsonElement element : projects)
        {
            if (!element.isJsonObject())
            {
                continue;
            }
            String name = Json.str(element.getAsJsonObject(), "name"); //$NON-NLS-1$
            if (name != null && !name.isBlank())
            {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * The fallback path (issue #302): recover project names from the first column of the
     * {@code list_projects} Markdown table carried in {@code result.content[*]}. Each content
     * item's text is read from either {@code text} (a text block / plain-text mode) or
     * {@code resource.text} (an embedded {@code text/markdown} resource). The table's header
     * ({@code Name}) and separator ({@code ---}) rows are skipped, and the {@code \|} escaping
     * {@code MarkdownUtils.escapeForTable} applies to a cell is undone.
     *
     * @param result the JSON-RPC {@code result} object
     * @return the project names; empty when no table is present
     */
    private static List<String> namesFromMarkdownTable(JsonObject result)
    {
        JsonElement content = result.get("content"); //$NON-NLS-1$
        if (content == null || !content.isJsonArray())
        {
            return new ArrayList<>();
        }
        StringBuilder md = new StringBuilder();
        for (JsonElement item : content.getAsJsonArray())
        {
            if (!item.isJsonObject())
            {
                continue;
            }
            JsonObject obj = item.getAsJsonObject();
            String text = Json.str(obj, "text"); //$NON-NLS-1$
            if (text == null)
            {
                JsonObject resource = Json.obj(obj, "resource"); //$NON-NLS-1$
                if (resource != null)
                {
                    text = Json.str(resource, "text"); //$NON-NLS-1$
                }
            }
            if (text != null)
            {
                md.append(text).append('\n');
            }
        }
        return firstColumnOfMarkdownTable(md.toString());
    }

    /**
     * Extracts the first-column cell of every DATA row of a Markdown pipe table, skipping the
     * header ({@code Name}) and separator ({@code ---}/{@code :--:}) rows and any non-table line.
     *
     * @param markdown the concatenated Markdown text
     * @return the first-column values; never {@code null}
     */
    private static List<String> firstColumnOfMarkdownTable(String markdown)
    {
        List<String> names = new ArrayList<>();
        boolean pastSeparator = false;
        for (String line : markdown.split("\n", -1)) //$NON-NLS-1$
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) != '|')
            {
                pastSeparator = false; // left this table; a following one needs its own separator
                continue;
            }
            // The cell boundary is the next UNESCAPED pipe: a '\|' inside the value (from
            // escapeForTable) must NOT be mistaken for the closing pipe.
            int secondPipe = indexOfUnescapedPipe(trimmed, 1);
            if (secondPipe < 0)
            {
                continue; // a row with a single pipe is not a well-formed table row
            }
            if (!pastSeparator)
            {
                // Header rows and the |---|---| separator (which divides header from data) contribute
                // no names; crossing the separator flips us into the data section. Gating on the
                // whole separator ROW - rather than matching the literal "Name"/dashes of a single
                // CELL - means a real project named "Name" or "---" in a DATA row is NOT mistaken for
                // the header/separator and dropped (issue #302).
                if (isSeparatorRow(trimmed))
                {
                    pastSeparator = true;
                }
                continue;
            }
            String cell = trimmed.substring(1, secondPipe).trim();
            // Undo the escapeForTable '|' -> '\|' escaping (the only escape that hits a name).
            names.add(cell.replace("\\|", "|")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return names;
    }

    /**
     * Whether a table row (starting with {@code '|'}) is the header/data SEPARATOR: EVERY cell is
     * dashes/colons only (e.g. {@code |------|:--:|}), which no real data row is. Detecting the full
     * row (not a lone cell) is what lets a project literally named {@code "---"} survive as data.
     *
     * @param rowLine the trimmed row line (its first char is {@code '|'})
     * @return {@code true} when the row is a Markdown table separator
     */
    private static boolean isSeparatorRow(String rowLine)
    {
        int from = 1;
        int cells = 0;
        while (from <= rowLine.length())
        {
            int end = indexOfUnescapedPipe(rowLine, from);
            if (end < 0)
            {
                // The segment AFTER the last pipe: when the row has no trailing '|' this is a real
                // trailing cell that MUST also be a separator (so "| --- | ready" - a data row whose
                // first cell happens to be dashes - is not mistaken for a separator). An empty
                // trailing segment just means the row ended with a '|', so there is no extra cell.
                String tail = rowLine.substring(from).trim();
                if (!tail.isEmpty())
                {
                    if (!isSeparatorCell(tail))
                    {
                        return false;
                    }
                    cells++;
                }
                break;
            }
            if (!isSeparatorCell(rowLine.substring(from, end).trim()))
            {
                return false;
            }
            cells++;
            from = end + 1;
        }
        return cells > 0;
    }

    /**
     * The index of the first {@code '|'} at or after {@code from} that is NOT escaped as
     * {@code \|}, or {@code -1}. A pipe preceded by a backslash is a {@code escapeForTable}
     * escape inside a cell value, not a column boundary.
     */
    private static int indexOfUnescapedPipe(String s, int from)
    {
        for (int i = from; i < s.length(); i++)
        {
            if (s.charAt(i) == '|' && (i == 0 || s.charAt(i - 1) != '\\'))
            {
                return i;
            }
        }
        return -1;
    }

    /** A Markdown table separator cell is NON-empty and only dashes/colons (e.g. {@code ---}, {@code :--:}). */
    private static boolean isSeparatorCell(String cell)
    {
        if (cell.isEmpty())
        {
            return false;
        }
        for (int i = 0; i < cell.length(); i++)
        {
            char c = cell.charAt(i);
            if (c != '-' && c != ':')
            {
                return false;
            }
        }
        return true;
    }

    private static void logChange(Snapshot previous, Snapshot current)
    {
        List<Integer> before = ports(previous.live);
        List<Integer> after = ports(current.live);
        if (!before.equals(after))
        {
            LOGGER.info("Live EDT backends changed: " + before + " -> " + after //$NON-NLS-1$ //$NON-NLS-2$
                + ", projects: " + current.knownProjects); //$NON-NLS-1$
        }
        else
        {
            LOGGER.fine("Backend refresh completed: live=" + after + ", projects=" + current.knownProjects); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static List<Integer> ports(List<Backend> backends)
    {
        List<Integer> ports = new ArrayList<>(backends.size());
        for (Backend backend : backends)
        {
            ports.add(backend.getPort());
        }
        return ports;
    }

    /**
     * One immutable, internally consistent routing state. Built off-line and swapped in
     * with a single volatile write, so readers never observe a half-updated registry.
     */
    private static final class Snapshot
    {
        static final Snapshot EMPTY = build(List.of(), Map.of());

        final List<Backend> live;
        final Map<String, Backend> owners;
        final Map<String, List<Integer>> duplicates;
        final List<String> knownProjects;
        final Map<Integer, List<String>> projectsByPort;

        private Snapshot(List<Backend> live, Map<String, Backend> owners, Map<String, List<Integer>> duplicates,
            List<String> knownProjects, Map<Integer, List<String>> projectsByPort)
        {
            this.live = live;
            this.owners = owners;
            this.duplicates = duplicates;
            this.knownProjects = knownProjects;
            this.projectsByPort = projectsByPort;
        }

        static Snapshot build(List<Backend> liveIn, Map<String, List<Backend>> holders)
        {
            List<Backend> live = new ArrayList<>(liveIn);
            live.sort(Comparator.comparingInt(Backend::getPort));

            Map<String, Backend> owners = new LinkedHashMap<>();
            Map<String, List<Integer>> duplicates = new LinkedHashMap<>();
            Map<Integer, List<String>> byPort = new TreeMap<>();
            for (Backend backend : live)
            {
                byPort.put(backend.getPort(), new ArrayList<>());
            }

            List<String> projectNames = new ArrayList<>(holders.keySet());
            Collections.sort(projectNames);
            for (String project : projectNames)
            {
                List<Backend> holding = new ArrayList<>(holders.get(project));
                holding.sort(Comparator.comparingInt(Backend::getPort));
                if (holding.isEmpty())
                {
                    continue;
                }
                owners.put(project, holding.get(0));
                if (holding.size() > 1)
                {
                    List<Integer> dupPorts = new ArrayList<>(holding.size());
                    for (Backend backend : holding)
                    {
                        dupPorts.add(backend.getPort());
                    }
                    duplicates.put(project, Collections.unmodifiableList(dupPorts));
                }
                for (Backend backend : holding)
                {
                    // a holder outside the live list (test-injected) still gets an entry
                    byPort.computeIfAbsent(backend.getPort(), p -> new ArrayList<>()).add(project);
                }
            }

            Map<Integer, List<String>> frozenByPort = new LinkedHashMap<>();
            for (Map.Entry<Integer, List<String>> entry : byPort.entrySet())
            {
                Collections.sort(entry.getValue());
                frozenByPort.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            }

            return new Snapshot(
                Collections.unmodifiableList(live),
                Collections.unmodifiableMap(owners),
                Collections.unmodifiableMap(duplicates),
                Collections.unmodifiableList(projectNames),
                Collections.unmodifiableMap(frozenByPort));
        }
    }
}
