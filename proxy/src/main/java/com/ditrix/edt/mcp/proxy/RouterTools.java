/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The proxy's own MCP tool surface: the {@code router_status} and {@code router_refresh}
 * tool descriptors, their injection into a backend's {@code tools/list} response, and the
 * status/refresh tool responses themselves.
 *
 * <p>Responses use the plugin's tools/call wire shapes 1:1: a success is a JSON-RPC success
 * envelope whose {@code result} carries {@code content[0].text} (a short digest) plus
 * {@code structuredContent} - unless the calling session opted OUT of it, in which case the
 * payload moves into the text channel and the field is omitted; a tool-level failure additionally carries {@code isError:true}
 * and a {@code structuredContent} of {@code {"success":false,"error":"..."}} (the plugin's
 * {@code ToolResult.error} payload).</p>
 */
public final class RouterTools
{
    /** Name of the proxy status tool. */
    static final String TOOL_ROUTER_STATUS = "router_status"; //$NON-NLS-1$

    /** Name of the proxy rescan tool. */
    static final String TOOL_ROUTER_REFRESH = "router_refresh"; //$NON-NLS-1$

    private static final String KEY_JSONRPC = "jsonrpc"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_TOOLS = "tools"; //$NON-NLS-1$
    private static final String KEY_NAME = "name"; //$NON-NLS-1$
    private static final String KEY_TYPE = "type"; //$NON-NLS-1$
    private static final String KEY_TEXT = "text"; //$NON-NLS-1$
    private static final String KEY_CONTENT = "content"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_IS_ERROR = "isError"; //$NON-NLS-1$
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String JSONRPC_VERSION = "2.0"; //$NON-NLS-1$

    /**
     * The proxy configuration used for the {@code proxyPort} / {@code scanRange} status
     * fields. Set once by {@link McpProxyHandler}'s constructor; {@code volatile} because the
     * handler runs on transport threads. When unset (defensive), the status falls back to
     * {@code 0} / an empty scan range instead of failing.
     */
    private static volatile ProxyConfig config;

    private RouterTools()
    {
        // utility class
    }

    /**
     * Installs the proxy configuration used by {@link #routerStatus} for the
     * {@code proxyPort} and {@code scanRange} fields. Called by {@code McpProxyHandler}'s
     * constructor (and by tests).
     *
     * @param cfg the proxy configuration
     */
    static void configure(ProxyConfig cfg)
    {
        config = cfg;
    }

    /**
     * The two router tool descriptors as a JSON array fragment, each with a name, a
     * description, and a no-parameter object {@code inputSchema} — the same descriptor shape
     * the plugin emits in {@code tools/list}.
     *
     * @return the serialized JSON array of the two descriptors
     */
    public static String toolsListInjection()
    {
        return Json.compact(descriptorsArray());
    }

    /**
     * Appends the two router tool descriptors to {@code result.tools} of a backend's
     * {@code tools/list} JSON-RPC response. Defensive: an unparseable response, a response
     * without a {@code result} object, or a response that already contains the router tools
     * is returned unchanged.
     *
     * @param backendToolsListResponse the backend's raw tools/list JSON-RPC response
     * @return the response with the router tools appended, or the input unchanged
     */
    public static String injectIntoToolsList(String backendToolsListResponse)
    {
        JsonObject envelope = Json.parseObject(backendToolsListResponse);
        if (envelope == null)
        {
            return backendToolsListResponse;
        }
        JsonObject result = Json.obj(envelope, KEY_RESULT);
        if (result == null)
        {
            return backendToolsListResponse;
        }

        JsonElement toolsElement = result.get(KEY_TOOLS);
        JsonArray tools;
        if (toolsElement != null && toolsElement.isJsonArray())
        {
            tools = toolsElement.getAsJsonArray();
        }
        else
        {
            tools = new JsonArray();
            result.add(KEY_TOOLS, tools);
        }

        if (containsTool(tools, TOOL_ROUTER_STATUS))
        {
            // Already injected (e.g. a double-inject on a cached response) - keep it as is.
            return backendToolsListResponse;
        }

        for (JsonElement descriptor : descriptorsArray())
        {
            tools.add(descriptor);
        }
        return Json.compact(envelope);
    }

    /**
     * Builds the {@code router_status} tools/call response: the live backends with the
     * projects each serves, the duplicate projects, the last registry refresh time, the
     * proxy port, and the scanned port range.
     *
     * @param registry the backend registry to report on
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC tools/call response
     */
    public static String routerStatus(BackendRegistry registry, Object requestId)
    {
        return routerStatus(registry, requestId, true);
    }

    /**
     * As {@link #routerStatus(BackendRegistry, Object)}, but able to suppress
     * {@code structuredContent} for a client that declared it cannot accept it. The payload is then
     * delivered as TEXT, exactly like a backend answers such a client.
     *
     * @param registry the backend registry to describe
     * @param requestId the id to write into the envelope
     * @param allowStructuredContent whether the calling session accepts {@code structuredContent}
     * @return the JSON-RPC response
     */
    public static String routerStatus(BackendRegistry registry, Object requestId,
        boolean allowStructuredContent)
    {
        Map<String, List<Integer>> duplicates = registry.duplicateProjects();
        Map<Integer, List<String>> projectsByPort = projectsByPort(registry, duplicates);

        JsonArray backends = new JsonArray();
        for (Map.Entry<Integer, List<String>> entry : projectsByPort.entrySet())
        {
            JsonObject backend = new JsonObject();
            backend.addProperty("port", entry.getKey()); //$NON-NLS-1$
            JsonArray projects = new JsonArray();
            for (String project : entry.getValue())
            {
                projects.add(project);
            }
            backend.add("projects", projects); //$NON-NLS-1$
            backends.add(backend);
        }

        JsonObject duplicatesJson = new JsonObject();
        for (Map.Entry<String, List<Integer>> entry : duplicates.entrySet())
        {
            JsonArray ports = new JsonArray();
            for (Integer port : entry.getValue())
            {
                ports.add(port);
            }
            duplicatesJson.add(entry.getKey(), ports);
        }

        ProxyConfig cfg = config;
        JsonObject structured = new JsonObject();
        structured.addProperty(KEY_SUCCESS, true);
        structured.addProperty("proxyPort", cfg != null ? cfg.port : 0); //$NON-NLS-1$
        structured.add("backends", backends); //$NON-NLS-1$
        // Live backends whose plugin does not support list_projects(format=json): their projects are
        // not routable, so an operator must see them here and not just in the log.
        JsonArray unsupportedJson = new JsonArray();
        BackendRegistry.UnroutableBackends unroutable = registry.unroutableBackends();
        for (Integer port : unroutable.unsupported())
        {
            unsupportedJson.add(port);
        }
        structured.add("unsupportedBackends", unsupportedJson); //$NON-NLS-1$
        JsonArray truncatedJson = new JsonArray();
        for (Integer port : unroutable.truncated())
        {
            truncatedJson.add(port);
        }
        // Reported apart from unsupportedBackends: the plugin there is CURRENT, its answer was cut by
        // the output size cap, and the fix is a smaller project list rather than an upgrade.
        structured.add("truncatedBackends", truncatedJson); //$NON-NLS-1$
        structured.add("duplicates", duplicatesJson); //$NON-NLS-1$
        structured.addProperty("lastRefreshMs", registry.lastRefreshMillis()); //$NON-NLS-1$
        structured.addProperty("scanRange", //$NON-NLS-1$
            cfg != null ? cfg.scanFrom + "-" + cfg.scanTo : ""); //$NON-NLS-1$ //$NON-NLS-2$

        return toolCallSuccess(structured, "OK - backends: " + backends.size(), requestId, //$NON-NLS-1$
            allowStructuredContent);
    }

    /**
     * Handles {@code router_refresh}: rescans the port range (rebuilding the routing table)
     * and returns the fresh {@link #routerStatus} payload.
     *
     * @param registry the backend registry to refresh and report on
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC tools/call response
     */
    public static String routerRefresh(BackendRegistry registry, Object requestId)
    {
        return routerRefresh(registry, requestId, true);
    }

    /**
     * As {@link #routerRefresh(BackendRegistry, Object)}, but able to suppress
     * {@code structuredContent} for a client that declared it cannot accept it.
     *
     * @param registry the backend registry to refresh
     * @param requestId the id to write into the envelope
     * @param allowStructuredContent whether the calling session accepts {@code structuredContent}
     * @return the JSON-RPC response
     */
    public static String routerRefresh(BackendRegistry registry, Object requestId,
        boolean allowStructuredContent)
    {
        registry.refresh();
        return routerStatus(registry, requestId, allowStructuredContent);
    }

    /**
     * Builds a tools/call TOOL-LEVEL error response in the plugin's {@code ToolResult.error}
     * wire shape: a JSON-RPC success envelope whose {@code result} carries the error text in
     * {@code content[0].text}, the {@code {"success":false,"error":"..."}} payload in
     * {@code structuredContent}, and {@code isError:true}.
     *
     * @param message the actionable error message ({@code null} becomes {@code "Unknown error"})
     * @param requestId the JSON-RPC request id to echo
     * @return the serialized JSON-RPC tools/call error response
     */
    static String toolCallError(String message, Object requestId)
    {
        return toolCallError(message, requestId, true);
    }

    /**
     * As {@link #toolCallError(String, Object)}, but able to suppress {@code structuredContent} for a
     * client that declared it cannot accept it. The error TEXT is unchanged either way, so such a
     * client still reads the message in {@code content[0].text}.
     *
     * @param message the actionable error message ({@code null} becomes {@code "Unknown error"})
     * @param requestId the JSON-RPC request id to echo
     * @param allowStructuredContent whether the calling session accepts {@code structuredContent}
     * @return the serialized JSON-RPC tools/call error response
     */
    static String toolCallError(String message, Object requestId, boolean allowStructuredContent)
    {
        String text = message != null ? message : "Unknown error"; //$NON-NLS-1$
        JsonObject structured = new JsonObject();
        structured.addProperty(KEY_SUCCESS, false);
        structured.addProperty(KEY_ERROR, text);

        JsonObject result = allowStructuredContent ? buildToolCallResult(structured, text)
            : buildErrorResultWithoutStructuredContent(text);
        result.addProperty(KEY_IS_ERROR, true);
        return wrapEnvelope(result, requestId);
    }

    /**
     * The two router tool descriptors as a fresh Gson array. Package-private so the handler
     * can build the zero-backend minimal tools/list without round-tripping through a string.
     *
     * @return a new array holding the two descriptors
     */
    static JsonArray descriptorsArray()
    {
        JsonArray tools = new JsonArray();
        tools.add(descriptor(TOOL_ROUTER_STATUS,
            "Shows the proxy router status: live EDT-MCP backends, the projects each serves, " //$NON-NLS-1$
                + "duplicate projects, the last refresh time, and the scanned port range.")); //$NON-NLS-1$
        tools.add(descriptor(TOOL_ROUTER_REFRESH,
            "Rescans the configured port range for running EDT-MCP backends, rebuilds the " //$NON-NLS-1$
                + "project routing table, and returns the router status.")); //$NON-NLS-1$
        return tools;
    }

    private static JsonObject descriptor(String name, String description)
    {
        JsonObject tool = new JsonObject();
        tool.addProperty(KEY_NAME, name);
        tool.addProperty("description", description); //$NON-NLS-1$
        JsonObject schema = new JsonObject();
        schema.addProperty(KEY_TYPE, "object"); //$NON-NLS-1$
        schema.add("properties", new JsonObject()); //$NON-NLS-1$
        tool.add("inputSchema", schema); //$NON-NLS-1$
        return tool;
    }

    private static boolean containsTool(JsonArray tools, String name)
    {
        for (JsonElement tool : tools)
        {
            if (tool.isJsonObject() && name.equals(Json.str(tool.getAsJsonObject(), KEY_NAME)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Groups the registry's known projects by owning backend port, in live (ascending port)
     * order. A duplicate project is listed under EVERY port that holds it.
     */
    private static Map<Integer, List<String>> projectsByPort(BackendRegistry registry,
        Map<String, List<Integer>> duplicates)
    {
        Map<Integer, List<String>> projectsByPort = new LinkedHashMap<>();
        for (Backend backend : registry.live())
        {
            projectsByPort.put(backend.getPort(), new ArrayList<>());
        }
        for (String project : registry.knownProjects())
        {
            List<Integer> duplicatePorts = duplicates.get(project);
            if (duplicatePorts != null)
            {
                for (Integer port : duplicatePorts)
                {
                    projectsByPort.computeIfAbsent(port, p -> new ArrayList<>()).add(project);
                }
            }
            else
            {
                Backend owner = registry.byProject(project);
                if (owner != null)
                {
                    projectsByPort.computeIfAbsent(owner.getPort(), p -> new ArrayList<>()).add(project);
                }
            }
        }
        return projectsByPort;
    }

    private static String toolCallSuccess(JsonObject structuredContent, String digest, Object requestId,
        boolean allowStructuredContent)
    {
        return wrapEnvelope(buildToolCallResult(structuredContent, digest, allowStructuredContent), requestId);
    }

    private static JsonObject buildToolCallResult(JsonObject structuredContent, String text)
    {
        return buildToolCallResult(structuredContent, text, true);
    }

    /**
     * The error result for a client that opted out of {@code structuredContent}: the message stays in
     * the text channel, which is the only one such a client reads.
     *
     * @param text the error message
     * @return the {@code result} object, without {@code structuredContent}
     */
    private static JsonObject buildErrorResultWithoutStructuredContent(String text)
    {
        JsonObject textItem = new JsonObject();
        textItem.addProperty(KEY_TYPE, KEY_TEXT);
        textItem.addProperty(KEY_TEXT, text);
        JsonArray content = new JsonArray();
        content.add(textItem);
        JsonObject result = new JsonObject();
        result.add(KEY_CONTENT, content);
        return result;
    }

    private static JsonObject buildToolCallResult(JsonObject structuredContent, String text,
        boolean allowStructuredContent)
    {
        JsonObject textItem = new JsonObject();
        textItem.addProperty(KEY_TYPE, KEY_TEXT);
        // A client that opted out still needs the DATA: it goes into the text channel, which is what
        // a backend gives such a client, instead of the one-line digest.
        // Capped like every other text channel: the direct backend path and the fan-out both bound
        // what they put in content, so the proxy's own tools must not return an unbounded payload
        // to an opted-out client just because the registry grew.
        textItem.addProperty(KEY_TEXT,
            allowStructuredContent ? text : FanOut.capText(Json.compact(structuredContent)));
        JsonArray content = new JsonArray();
        content.add(textItem);

        JsonObject result = new JsonObject();
        result.add(KEY_CONTENT, content);
        if (allowStructuredContent)
        {
            result.add(KEY_STRUCTURED_CONTENT, structuredContent);
        }
        return result;
    }

    private static String wrapEnvelope(JsonObject result, Object requestId)
    {
        JsonObject envelope = new JsonObject();
        envelope.addProperty(KEY_JSONRPC, JSONRPC_VERSION);
        FanOut.writeId(envelope, requestId);
        envelope.add(KEY_RESULT, result);
        return Json.compact(envelope);
    }
}
