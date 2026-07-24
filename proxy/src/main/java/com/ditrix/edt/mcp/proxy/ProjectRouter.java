/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Decides where one {@code tools/call} request goes: a specific backend (scoped by the
 * {@code projectName}/{@code project} argument), the {@code list_projects} fan-out, the
 * proxy's own router tools, or an actionable error.
 * <p>
 * The hot-plug path lives here: a project MISS triggers ONE synchronous registry rescan
 * before failing — the AI agent may have just started that EDT instance, and the periodic
 * refresh must not be the only way to notice it.
 * <p>
 * Other JSON-RPC methods ({@code initialize}, {@code tools/list}, ...) are handled by
 * {@code McpProxyHandler} directly and never reach these rules.
 */
public final class ProjectRouter
{
    /** Proxy-served status tool: reports backends, projects, duplicates, scan range. */
    public static final String TOOL_ROUTER_STATUS = "router_status"; //$NON-NLS-1$

    /** Proxy-served refresh tool: forces a rescan, then reports the status payload. */
    public static final String TOOL_ROUTER_REFRESH = "router_refresh"; //$NON-NLS-1$

    /** The one fanned-out tool: every backend's projects merged into a single response. */
    public static final String TOOL_LIST_PROJECTS = "list_projects"; //$NON-NLS-1$

    private static final String METHOD_TOOLS_CALL = "tools/call"; //$NON-NLS-1$

    private final BackendRegistry registry;

    /**
     * Creates a router over the given registry.
     *
     * @param registry the backend registry providing the routing snapshot
     */
    public ProjectRouter(BackendRegistry registry)
    {
        this.registry = registry;
    }

    /**
     * Extracts the project scope from a {@code tools/call} request:
     * {@code params.arguments.projectName}, else {@code params.arguments.project},
     * else {@code null}. A blank value counts as absent.
     *
     * @param requestJson the parsed JSON-RPC request; may be {@code null}
     * @return the project name, or {@code null} when the call is unscoped
     */
    public static String extractProjectArg(JsonObject requestJson)
    {
        if (requestJson == null)
        {
            return null;
        }
        JsonObject params = Json.obj(requestJson, "params"); //$NON-NLS-1$
        if (params == null)
        {
            return null;
        }
        JsonObject arguments = Json.obj(params, "arguments"); //$NON-NLS-1$
        if (arguments == null)
        {
            return null;
        }
        String name = Json.str(arguments, "projectName"); //$NON-NLS-1$
        if (name == null || name.isBlank())
        {
            name = Json.str(arguments, "project"); //$NON-NLS-1$
        }
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * Routes one request. Rules (for {@code tools/call}):
     * <ul>
     * <li>{@code router_status} / {@code router_refresh} — the proxy answers itself;</li>
     * <li>{@code list_projects} — fan out to every live backend and merge;</li>
     * <li>a project argument that maps to exactly one backend — that backend;</li>
     * <li>a project served by several backends — an error naming the ports;</li>
     * <li>an unknown project — ONE on-miss rescan (hot-plug), then re-lookup; still
     *     unknown — an actionable error;</li>
     * <li>no project argument — the first live backend; zero live — an error naming the
     *     scan range.</li>
     * </ul>
     * A non-{@code tools/call} method skips the tool rules and routes like an unscoped call.
     *
     * @param method the JSON-RPC method of the request
     * @param requestJson the parsed request; may be {@code null} for a malformed body
     * @return the routing decision; never {@code null}
     */
    public RouteResult route(String method, JsonObject requestJson)
    {
        if (METHOD_TOOLS_CALL.equals(method))
        {
            JsonObject params = requestJson == null ? null : Json.obj(requestJson, "params"); //$NON-NLS-1$
            String toolName = params == null ? null : Json.str(params, "name"); //$NON-NLS-1$
            if (TOOL_ROUTER_STATUS.equals(toolName) || TOOL_ROUTER_REFRESH.equals(toolName))
            {
                return RouteResult.self();
            }
            if (TOOL_LIST_PROJECTS.equals(toolName))
            {
                return RouteResult.fanOut();
            }
            String project = extractProjectArg(requestJson);
            if (project != null)
            {
                return routeScoped(project);
            }
        }
        return routeUnscoped();
    }

    /**
     * Routes a project-scoped call, rescanning ONCE on a miss (the hot-plug path).
     */
    private RouteResult routeScoped(String project)
    {
        RouteResult known = lookup(project);
        if (known != null)
        {
            return known;
        }
        // On-miss rescan: the AI agent may have just started the EDT instance that
        // serves this project — notice it NOW instead of waiting for the periodic refresh.
        registry.refresh();
        RouteResult rescanned = lookup(project);
        if (rescanned != null)
        {
            return rescanned;
        }
        return RouteResult.error("No running EDT instance serves project '" + project //$NON-NLS-1$
            + "'. Live backends: " + describeLiveBackends() //$NON-NLS-1$
            + describeUnsupportedBackends()
            + ". If you just started EDT it may still be initializing - retry in ~30 s, or call router_refresh."); //$NON-NLS-1$
    }

    /**
     * A clause naming the live backends whose EDT-MCP plugin does NOT support the machine project
     * list, or {@code ""} when every live backend supports it. Such a backend's projects can never be
     * routed, so a routing failure must say WHY instead of just reporting the project as unknown.
     *
     * @return the clause to append to a routing error, possibly empty
     */
    private String describeUnsupportedBackends()
    {
        List<Integer> unsupported = registry.unsupportedBackends();
        if (unsupported.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return ". NOTE: backend(s) on port(s) " + joinPorts(unsupported) //$NON-NLS-1$
            + " run an EDT-MCP plugin version that does not support the machine project list" //$NON-NLS-1$
            + " (list_projects with format=json), so their projects cannot be routed - update the" //$NON-NLS-1$
            + " plugin in those EDT instances"; //$NON-NLS-1$
    }

    /**
     * Looks the project up in the current snapshot: the duplicate error wins over the
     * owner route (a duplicated project is "known" too, but must not be routed silently).
     *
     * @return the decision, or {@code null} when the project is unknown
     */
    private RouteResult lookup(String project)
    {
        List<Integer> dupPorts = registry.duplicateProjects().get(project);
        if (dupPorts != null)
        {
            return RouteResult.error("Project '" + project + "' is served by more than one EDT instance (ports " //$NON-NLS-1$ //$NON-NLS-2$
                + joinPorts(dupPorts)
                + "). Routing is ambiguous - close the project in the duplicate EDT instance, then call router_refresh."); //$NON-NLS-1$
        }
        Backend owner = registry.byProject(project);
        return owner == null ? null : RouteResult.backend(owner);
    }

    /**
     * Routes an unscoped call: the first (lowest-port) live backend, or the zero-backend
     * error naming the scan range.
     */
    private RouteResult routeUnscoped()
    {
        List<Backend> live = registry.live();
        if (!live.isEmpty())
        {
            return RouteResult.backend(live.get(0));
        }
        ProxyConfig cfg = registry.getConfig();
        return RouteResult.error("No running EDT backends. Scanned ports " + cfg.scanFrom + "-" + cfg.scanTo //$NON-NLS-1$ //$NON-NLS-2$
            + " and found none alive. Start an EDT instance with the EDT-MCP plugin enabled, then retry or call router_refresh."); //$NON-NLS-1$
    }

    /**
     * Renders the live backends with their projects for error texts, e.g.
     * {@code :8765 (projects: Alpha, Beta); :8766 (projects: none)}, or {@code none}.
     */
    private String describeLiveBackends()
    {
        Map<Integer, List<String>> byPort = registry.projectsByPort();
        if (byPort.isEmpty())
        {
            return "none"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, List<String>> entry : byPort.entrySet())
        {
            if (sb.length() > 0)
            {
                sb.append("; "); //$NON-NLS-1$
            }
            sb.append(':').append(entry.getKey()).append(" (projects: "); //$NON-NLS-1$
            sb.append(entry.getValue().isEmpty() ? "none" : String.join(", ", entry.getValue())); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(')');
        }
        return sb.toString();
    }

    private static String joinPorts(List<Integer> ports)
    {
        StringBuilder sb = new StringBuilder();
        for (Integer port : ports)
        {
            if (sb.length() > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(port);
        }
        return sb.toString();
    }

    /**
     * One routing decision: where the request goes, or why it cannot go anywhere.
     */
    public static final class RouteResult
    {
        /** The kinds of routing decision. */
        public enum Kind
        {
            /** Forward the raw request to {@link RouteResult#backend}. */
            BACKEND,
            /** Fan {@code list_projects} out to every live backend and merge. */
            FAN_OUT_LIST_PROJECTS,
            /** The proxy answers itself (router_status / router_refresh). */
            PROXY_SELF,
            /** No route; {@link RouteResult#errorMessage} explains what to do. */
            ERROR
        }

        /** The decision kind; never {@code null}. */
        public final Kind kind;

        /** The target backend; non-{@code null} only for {@link Kind#BACKEND}. */
        public final Backend backend;

        /** The actionable error text; non-{@code null} only for {@link Kind#ERROR}. */
        public final String errorMessage;

        private RouteResult(Kind kind, Backend backend, String errorMessage)
        {
            this.kind = kind;
            this.backend = backend;
            this.errorMessage = errorMessage;
        }

        /**
         * Creates a decision that forwards to one backend.
         *
         * @param b the target backend
         * @return the BACKEND decision
         */
        public static RouteResult backend(Backend b)
        {
            return new RouteResult(Kind.BACKEND, b, null);
        }

        /**
         * Creates the {@code list_projects} fan-out decision.
         *
         * @return the FAN_OUT_LIST_PROJECTS decision
         */
        public static RouteResult fanOut()
        {
            return new RouteResult(Kind.FAN_OUT_LIST_PROJECTS, null, null);
        }

        /**
         * Creates the proxy-answers-itself decision (router tools).
         *
         * @return the PROXY_SELF decision
         */
        public static RouteResult self()
        {
            return new RouteResult(Kind.PROXY_SELF, null, null);
        }

        /**
         * Creates an error decision with an actionable message.
         *
         * @param msg the error text shown to the client
         * @return the ERROR decision
         */
        public static RouteResult error(String msg)
        {
            return new RouteResult(Kind.ERROR, null, msg);
        }
    }
}
