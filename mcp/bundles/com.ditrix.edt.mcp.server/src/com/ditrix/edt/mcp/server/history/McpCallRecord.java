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
 * and never inspects or mutates it. Because a stored body may be capped, the record
 * ALSO carries {@link #getOriginalRequestChars()} / {@link #getOriginalResponseChars()}
 * &mdash; the true character counts of the payloads <em>before</em> truncation &mdash;
 * so the context-usage statistics measure the real size a large response contributed
 * to the LLM context, not the capped stored length.</p>
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

    /** Character count of the request payload BEFORE any truncation (0 when null). */
    private final int originalRequestChars;

    /** Character count of the response payload BEFORE any truncation (0 when null). */
    private final int originalResponseChars;

    /**
     * Creates an immutable call record. The {@code requestJson} / {@code responseJson}
     * bodies are stored verbatim (the caller caps them for the bounded ring); the
     * {@code originalRequestChars} / {@code originalResponseChars} carry the true
     * pre-truncation sizes so the statistics never undercount a capped payload.
     *
     * @param timestampMs epoch-millisecond timestamp of the exchange
     * @param method the JSON-RPC method (may be {@code null})
     * @param toolName the tool name for a {@code tools/call}, else {@code null}
     * @param requestJson the (possibly truncated) request body (may be {@code null})
     * @param responseJson the (possibly truncated) response body (may be {@code null})
     * @param durationMs the exchange duration in milliseconds
     * @param originalRequestChars the request length BEFORE truncation (0 when null)
     * @param originalResponseChars the response length BEFORE truncation (0 when null)
     */
    public McpCallRecord(long timestampMs, String method, String toolName, String requestJson,
        String responseJson, long durationMs, int originalRequestChars, int originalResponseChars)
    {
        this.timestampMs = timestampMs;
        this.method = method;
        this.toolName = toolName;
        this.requestJson = requestJson;
        this.responseJson = responseJson;
        this.durationMs = durationMs;
        this.originalRequestChars = originalRequestChars;
        this.originalResponseChars = originalResponseChars;
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

    /**
     * The true request-payload size in characters, measured BEFORE the ring truncated
     * the stored body. Use this (not {@code getRequestJson().length()}) for size
     * statistics so a capped payload is not undercounted.
     *
     * @return the pre-truncation request character count (0 when the request was null)
     */
    public int getOriginalRequestChars()
    {
        return originalRequestChars;
    }

    /**
     * The true response-payload size in characters, measured BEFORE the ring truncated
     * the stored body. Use this (not {@code getResponseJson().length()}) for size
     * statistics so a large, capped response is ranked by its real context contribution.
     *
     * @return the pre-truncation response character count (0 when the response was null)
     */
    public int getOriginalResponseChars()
    {
        return originalResponseChars;
    }
}
