/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

/**
 * One immutable record of a single MCP JSON-RPC request/response exchange, as
 * captured at the transport choke point ({@code McpProtocolHandler.processRequest}).
 *
 * <p>Instances are value objects: every field is {@code final}, there are no
 * setters, and the only stored references are {@link String}s (themselves
 * immutable) and primitives. A record can therefore be handed to the UI reader
 * thread and to file-log consumers without any copying or synchronization.</p>
 *
 * <p>The {@code requestJson} / {@code responseJson} payloads may already have been
 * truncated (with a trailing truncation marker) by {@link McpCallHistory} to keep
 * the in-memory ring bounded; this class stores whatever text it is given verbatim
 * and never inspects or mutates it.</p>
 */
public final class McpCallRecord
{
    /** Wall-clock time the exchange was recorded, in epoch milliseconds. */
    private final long timestampMs;

    /** JSON-RPC method (e.g. {@code "tools/call"}, {@code "initialize"}); may be {@code null}. */
    private final String method;

    /**
     * The tool name for a {@code tools/call} exchange, or {@code null} for any
     * other method (there is no tool associated with, e.g., {@code initialize}).
     */
    private final String toolName;

    /** The (possibly truncated) request JSON body; may be {@code null}. */
    private final String requestJson;

    /**
     * The (possibly truncated) response JSON body, or {@code null} when the
     * exchange produced no response body (e.g. a notification answered with 202).
     */
    private final String responseJson;

    /** Wall-clock duration of the exchange in milliseconds. */
    private final long durationMs;

    /**
     * Creates an immutable call record. All arguments are stored verbatim; the
     * caller is responsible for any payload capping/truncation before construction.
     *
     * @param timestampMs epoch-millisecond timestamp of the exchange
     * @param method the JSON-RPC method (may be {@code null})
     * @param toolName the tool name for a {@code tools/call}, else {@code null}
     * @param requestJson the (possibly truncated) request body (may be {@code null})
     * @param responseJson the (possibly truncated) response body (may be {@code null})
     * @param durationMs the exchange duration in milliseconds
     */
    public McpCallRecord(long timestampMs, String method, String toolName, String requestJson,
        String responseJson, long durationMs)
    {
        this.timestampMs = timestampMs;
        this.method = method;
        this.toolName = toolName;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
        this.durationMs = durationMs;
    }

    /**
     * @return the epoch-millisecond timestamp of the exchange
     */
    public long getTimestampMs()
    {
        return timestampMs;
    }

    /**
     * @return the JSON-RPC method, or {@code null}
     */
    public String getMethod()
    {
        return method;
    }

    /**
     * @return the tool name for a {@code tools/call} exchange, or {@code null}
     */
    public String getToolName()
    {
        return toolName;
    }

    /**
     * @return the (possibly truncated) request JSON body, or {@code null}
     */
    public String getRequestJson()
    {
        return requestJson;
    }

    /**
     * @return the (possibly truncated) response JSON body, or {@code null}
     */
    public String getResponseJson()
    {
        return responseJson;
    }

    /**
     * @return the exchange duration in milliseconds
     */
    public long getDurationMs()
    {
        return durationMs;
    }
}
