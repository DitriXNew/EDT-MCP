/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallRecord;
import com.ditrix.edt.mcp.server.history.StatsAggregator;
import com.ditrix.edt.mcp.server.history.StatsAggregator.StatsResult;
import com.ditrix.edt.mcp.server.history.ToolStats;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Pagination;

/**
 * Returns the recorded MCP call history from the in-memory {@link McpCallHistory}
 * ring so an AI client can introspect its OWN traffic — which tools it has been
 * calling, how long they took, which failed, and (above all) what has been filling
 * its context window.
 *
 * <p>Read-only: it takes a defensive {@link McpCallHistory#snapshot() snapshot} of
 * the ring and reads it; there is no model access, no transaction, no session, no
 * UI, and it never mutates the recorder, the history view, or the record model.
 *
 * <p>The snapshot is passed through the same AND filters an interactive log viewer
 * would offer — {@code tool} substring, {@code status} (all / error / ok),
 * {@code minDurationMs}, and a half-open {@code [sinceMs, untilMs)} time window —
 * then ordered newest-first and capped at {@code limit}. Each returned record is
 * metadata only by default (timestamp, method, resolved tool key, duration, status,
 * payload sizes); the raw request/response bodies are included only when
 * {@code includeBodies} is set. When {@code includeStats} is set, the tool appends
 * the aggregated per-tool context-usage statistics for the FILTERED set.
 *
 * <h2>Reuse contract</h2>
 * <ul>
 * <li>The per-record row key and the {@code tool} substring filter both go through
 *     {@link StatsAggregator#keyOf(McpCallRecord)} — one keying, never duplicated.</li>
 * <li>The {@code status} classification goes through
 *     {@link StatsAggregator#isErrorResponse(String)} — the same structural
 *     classifier the stats path uses (a JSON-RPC top-level {@code error}, or a tool
 *     result with {@code isError:true} / {@code success:false}).</li>
 * <li>The optional statistics block is produced by
 *     {@link StatsAggregator#aggregate(List)} — the same aggregator the history view
 *     renders.</li>
 * </ul>
 *
 * <p><b>PII:</b> when {@code includeBodies} is set the returned bodies can contain
 * whatever the recorded tools returned, including infobase data / personal data
 * (e.g. a recorded {@code get_variables} / {@code evaluate_expression} response).
 * Treat that output as sensitive; the default (metadata only) carries none. When
 * {@code includeBodies} is set this is effectively a {@code returnsInfobaseData}
 * surface for the future PII-redactor (#242); it does NOT redact here, and the
 * redactor must include this {@code includeBodies} path when it wires per-tool
 * flags (it sees the outer tool name, not the nested tools whose bodies it embeds).
 */
public class GetMcpHistoryTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "get_mcp_history"; //$NON-NLS-1$

    // --- Input parameter names (lowerCamelCase; every one is declared in getInputSchema) ---

    /** Input param: case-insensitive substring match on the call key (tool name / method). */
    private static final String KEY_TOOL = "tool"; //$NON-NLS-1$

    /** Input param: outcome filter (all / error / ok). */
    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    /** Input param: minimum call duration, in milliseconds. */
    private static final String KEY_MIN_DURATION_MS = "minDurationMs"; //$NON-NLS-1$

    /** Input param: time-window lower bound (epoch ms), inclusive. */
    private static final String KEY_SINCE_MS = "sinceMs"; //$NON-NLS-1$

    /** Input param: time-window upper bound (epoch ms), exclusive. */
    private static final String KEY_UNTIL_MS = "untilMs"; //$NON-NLS-1$

    /** Input param: include the raw request/response bodies (default false = metadata only). */
    private static final String KEY_INCLUDE_BODIES = "includeBodies"; //$NON-NLS-1$

    /** Input param: append the aggregated per-tool statistics for the filtered set. */
    private static final String KEY_INCLUDE_STATS = "includeStats"; //$NON-NLS-1$

    // --- status enum values ---

    /** {@code status} value: keep every record (default). */
    private static final String STATUS_ALL = "all"; //$NON-NLS-1$

    /** {@code status} value: only records classified as an error outcome. */
    private static final String STATUS_ERROR = "error"; //$NON-NLS-1$

    /** {@code status} value: only records NOT classified as an error outcome. */
    private static final String STATUS_OK = "ok"; //$NON-NLS-1$

    // --- Output result keys (top level) ---

    /** Output key: total records matching the filters (before the limit cap). */
    private static final String KEY_MATCHED = "matched"; //$NON-NLS-1$

    /** Output key: number of records returned on this page (after the limit cap). */
    private static final String KEY_RETURNED = "returned"; //$NON-NLS-1$

    /** Output key: total records currently retained in the ring (regardless of the filters). */
    private static final String KEY_TOTAL_RECORDED = "totalRecorded"; //$NON-NLS-1$

    /** Output key: whether recording is currently enabled (an empty ring may simply mean "off"). */
    private static final String KEY_RECORDING = "recording"; //$NON-NLS-1$

    /** Output key: the ring capacity (how many exchanges history retains). */
    private static final String KEY_BUFFER_SIZE = "bufferSize"; //$NON-NLS-1$

    /** Output key: the page of matched records, newest first. */
    private static final String KEY_RECORDS = "records"; //$NON-NLS-1$

    /** Output key: the optional aggregated statistics block (present only with includeStats). */
    private static final String KEY_STATS = "stats"; //$NON-NLS-1$

    // --- Per-record output keys (metadata) ---

    private static final String REC_TIMESTAMP_MS = "timestampMs"; //$NON-NLS-1$
    private static final String REC_METHOD = "method"; //$NON-NLS-1$
    private static final String REC_TOOL = "tool"; //$NON-NLS-1$
    private static final String REC_DURATION_MS = "durationMs"; //$NON-NLS-1$
    private static final String REC_STATUS = "status"; //$NON-NLS-1$
    private static final String REC_REQUEST_CHARS = "requestChars"; //$NON-NLS-1$
    private static final String REC_RESPONSE_CHARS = "responseChars"; //$NON-NLS-1$
    private static final String REC_REQUEST_JSON = "requestJson"; //$NON-NLS-1$
    private static final String REC_RESPONSE_JSON = "responseJson"; //$NON-NLS-1$

    // --- stats block keys ---

    private static final String STATS_SUMMARY = "summary"; //$NON-NLS-1$
    private static final String STATS_ROWS = "rows"; //$NON-NLS-1$
    private static final String STATS_TOP3 = "top3"; //$NON-NLS-1$

    private static final String SUMMARY_TOTAL_CALLS = "totalCalls"; //$NON-NLS-1$
    private static final String SUMMARY_TOTAL_OUTPUT_CHARS = "totalOutputChars"; //$NON-NLS-1$
    private static final String SUMMARY_APPROX_TOTAL_TOKENS = "approxTotalTokens"; //$NON-NLS-1$
    private static final String SUMMARY_APPROX_TOTAL_TOKENS_DISPLAY = "approxTotalTokensDisplay"; //$NON-NLS-1$
    private static final String SUMMARY_TOTAL_ERRORS = "totalErrors"; //$NON-NLS-1$

    private static final String ROW_TOOL = "tool"; //$NON-NLS-1$
    private static final String ROW_CALLS = "calls"; //$NON-NLS-1$
    private static final String ROW_SHARE_PERCENT = "sharePercent"; //$NON-NLS-1$
    private static final String ROW_TOTAL_DURATION_MS = "totalDurationMs"; //$NON-NLS-1$
    private static final String ROW_AVG_DURATION_MS = "avgDurationMs"; //$NON-NLS-1$
    private static final String ROW_REQUEST_CHARS = "requestChars"; //$NON-NLS-1$
    private static final String ROW_REQUEST_WORDS = "requestWords"; //$NON-NLS-1$
    private static final String ROW_RESPONSE_CHARS = "responseChars"; //$NON-NLS-1$
    private static final String ROW_RESPONSE_WORDS = "responseWords"; //$NON-NLS-1$
    private static final String ROW_APPROX_TOKENS = "approxTokens"; //$NON-NLS-1$
    private static final String ROW_APPROX_TOKENS_DISPLAY = "approxTokensDisplay"; //$NON-NLS-1$
    private static final String ROW_CONTEXT_WEIGHT = "contextWeight"; //$NON-NLS-1$
    private static final String ROW_ERROR_COUNT = "errorCount"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Return the recorded MCP call history (this server's in-memory ring of " //$NON-NLS-1$
            + "request/response exchanges) so you can introspect your OWN traffic: which tools you " //$NON-NLS-1$
            + "called, how long they took, which failed, and what has been filling your context. " //$NON-NLS-1$
            + "Read-only (a snapshot; never mutates anything). Filters (AND): tool (substring over " //$NON-NLS-1$
            + "the tool name / method), status (all|error|ok), minDurationMs, sinceMs/untilMs " //$NON-NLS-1$
            + "(half-open epoch-ms window); newest first, capped by limit. Records are metadata only " //$NON-NLS-1$
            + "by default; set includeBodies for the raw payloads (may carry infobase data), or " //$NON-NLS-1$
            + "includeStats for the aggregated per-tool context-usage totals. Full parameters and " //$NON-NLS-1$
            + "examples: call get_tool_guide('get_mcp_history')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_TOOL,
                "Case-insensitive substring match on the call key: the tool name for a tools/call " //$NON-NLS-1$
                + "exchange, otherwise the JSON-RPC method (e.g. tools/list, initialize). Omit to " //$NON-NLS-1$
                + "match every call.") //$NON-NLS-1$
            .enumProperty(KEY_STATUS,
                "Outcome filter: all (default), error (only failed calls), or ok (only successful " //$NON-NLS-1$
                + "calls). Classified the same way the statistics are (a JSON-RPC error, or a tool " //$NON-NLS-1$
                + "result with isError:true / success:false).", //$NON-NLS-1$
                STATUS_ALL, STATUS_ERROR, STATUS_OK)
            .integerProperty(KEY_MIN_DURATION_MS,
                "Keep only calls whose duration is at least this many milliseconds (find the slow " //$NON-NLS-1$
                + "ones). Default 0 (no lower bound).") //$NON-NLS-1$
            .integerProperty(KEY_SINCE_MS,
                "Time-window lower bound, INCLUSIVE, as an epoch-millisecond timestamp (matches the " //$NON-NLS-1$
                + "timestampMs of a returned record). Omit for no lower bound.") //$NON-NLS-1$
            .integerProperty(KEY_UNTIL_MS,
                "Time-window upper bound, EXCLUSIVE, as an epoch-millisecond timestamp. Omit for no " //$NON-NLS-1$
                + "upper bound. The window is half-open [sinceMs, untilMs).") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Max records to return, newest first (default " + Pagination.DEFAULT_LIMIT + ", max " //$NON-NLS-1$ //$NON-NLS-2$
                + Pagination.MAX_LIMIT + "; clamped, never rejected).") //$NON-NLS-1$
            .booleanProperty(KEY_INCLUDE_BODIES,
                "Include the raw request/response JSON bodies for each record (default false = " //$NON-NLS-1$
                + "metadata only). The bodies can carry infobase / personal data; keep them off " //$NON-NLS-1$
                + "unless you need the payloads.") //$NON-NLS-1$
            .booleanProperty(KEY_INCLUDE_STATS,
                "Append the aggregated per-tool context-usage statistics (rows + summary + top3) for " //$NON-NLS-1$
                + "the FILTERED set of records (default false).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the read succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_MATCHED, "Total records matching the filters (before the limit cap).") //$NON-NLS-1$
            .integerProperty(KEY_RETURNED, "Number of records returned on this page (after the limit cap).") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "The effective page size (after clamping to [1, max]).") //$NON-NLS-1$
            .integerProperty(KEY_TOTAL_RECORDED, "Total records currently retained in the ring.") //$NON-NLS-1$
            .booleanProperty(KEY_RECORDING, "Whether recording is currently enabled.") //$NON-NLS-1$
            .integerProperty(KEY_BUFFER_SIZE, "The ring capacity (how many exchanges history retains).") //$NON-NLS-1$
            .objectArrayProperty(KEY_RECORDS,
                "The page of matched records, newest first: {timestampMs, method, tool, durationMs, " //$NON-NLS-1$
                + "status, requestChars, responseChars, (+ requestJson/responseJson when " //$NON-NLS-1$
                + "includeBodies)}.") //$NON-NLS-1$
            .objectProperty(KEY_STATS,
                "The aggregated per-tool statistics for the filtered set (present only when " //$NON-NLS-1$
                + "includeStats): {summary, rows[], top3[]}.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Validate the status enum up front so a bad value is an actionable error (headless-safe:
        // returns before touching the ring).
        String statusRaw = JsonUtils.extractStringArgument(params, KEY_STATUS);
        String status = normalizeStatus(statusRaw);
        if (status == null)
        {
            return ToolResult.error("Invalid status: '" + statusRaw + "'. Must be one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + STATUS_ALL + ", " + STATUS_ERROR + ", " + STATUS_OK + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        String toolRaw = JsonUtils.extractStringArgument(params, KEY_TOOL);
        String toolFilterLc = toolRaw == null || toolRaw.isBlank() ? null
            : toolRaw.trim().toLowerCase(Locale.ROOT);

        long minDurationMs = Math.max(0L, JsonUtils.extractLongArgument(params, KEY_MIN_DURATION_MS, 0L));
        long sinceMs = JsonUtils.extractLongArgument(params, KEY_SINCE_MS, Long.MIN_VALUE);
        long untilMs = JsonUtils.extractLongArgument(params, KEY_UNTIL_MS, Long.MAX_VALUE);

        int limit = Pagination.clampLimit(
            JsonUtils.extractIntArgument(params, McpKeys.LIMIT, Pagination.DEFAULT_LIMIT),
            Pagination.MAX_LIMIT);
        boolean includeBodies = JsonUtils.extractBooleanArgument(params, KEY_INCLUDE_BODIES, false);
        boolean includeStats = JsonUtils.extractBooleanArgument(params, KEY_INCLUDE_STATS, false);

        McpCallHistory history = McpCallHistory.getInstance();
        // Read-only: a defensive copy of the ring (oldest first); no model / tx / session.
        List<McpCallRecord> snapshot = history.snapshot();

        // Apply the AND filters over the snapshot.
        List<McpCallRecord> filtered = new ArrayList<>();
        for (McpCallRecord record : snapshot)
        {
            if (record == null)
            {
                continue;
            }
            long ts = record.getTimestampMs();
            if (ts < sinceMs || ts >= untilMs) // half-open [sinceMs, untilMs)
            {
                continue;
            }
            if (record.getDurationMs() < minDurationMs)
            {
                continue;
            }
            if (toolFilterLc != null
                && !StatsAggregator.keyOf(record).toLowerCase(Locale.ROOT).contains(toolFilterLc))
            {
                continue;
            }
            if (!statusMatches(status, record))
            {
                continue;
            }
            filtered.add(record);
        }

        // Newest first: the snapshot is oldest-first, so reverse the filtered view.
        Collections.reverse(filtered);
        int matched = filtered.size();

        // Cap the page at limit (newest first). The stats block below still aggregates the FULL
        // filtered set, not just this page, so the totals reflect everything that matched.
        List<McpCallRecord> page = matched > limit ? filtered.subList(0, limit) : filtered;

        List<Map<String, Object>> records = new ArrayList<>(page.size());
        for (McpCallRecord record : page)
        {
            records.add(toRecordMap(record, includeBodies));
        }

        ToolResult result = ToolResult.success()
            .put(KEY_MATCHED, matched)
            .put(KEY_RETURNED, records.size())
            .put(McpKeys.LIMIT, limit)
            .put(KEY_TOTAL_RECORDED, snapshot.size())
            .put(KEY_RECORDING, history.isRecordingEnabled())
            .put(KEY_BUFFER_SIZE, history.getBufferSize())
            .put(KEY_RECORDS, records);

        if (includeStats)
        {
            result.put(KEY_STATS, buildStats(StatsAggregator.aggregate(filtered)));
        }

        return result.toJson();
    }

    /**
     * Validates the {@code status} parameter. Returns {@link #STATUS_ALL} when
     * absent/blank (the default), the trimmed value when it is one of the accepted
     * tokens, or {@code null} for an out-of-set value (the caller then returns an
     * actionable error).
     *
     * @param raw the raw {@code status} argument (may be {@code null})
     * @return the canonical status, or {@code null} when the value is invalid
     */
    private static String normalizeStatus(String raw)
    {
        if (raw == null || raw.isBlank())
        {
            return STATUS_ALL;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (STATUS_ALL.equals(v) || STATUS_ERROR.equals(v) || STATUS_OK.equals(v))
        {
            return v;
        }
        return null;
    }

    /**
     * Whether a record passes the {@code status} filter, reusing the same structural
     * error classifier the statistics use ({@link StatsAggregator#isErrorResponse}).
     *
     * @param status the normalized status ({@link #STATUS_ALL}/{@link #STATUS_ERROR}/{@link #STATUS_OK})
     * @param record the record to test
     * @return {@code true} when the record matches the requested outcome
     */
    private static boolean statusMatches(String status, McpCallRecord record)
    {
        if (STATUS_ALL.equals(status))
        {
            return true;
        }
        boolean isError = StatsAggregator.isErrorResponse(record.getResponseJson());
        return STATUS_ERROR.equals(status) ? isError : !isError;
    }

    /**
     * Maps one recorded exchange to its {@code structuredContent} shape. Metadata
     * only by default; the raw request/response bodies are attached only when
     * {@code includeBodies} is set. The row {@code tool} key and {@code status} reuse
     * the aggregator's keying / classification so this tool and the statistics agree.
     *
     * @param record the recorded exchange
     * @param includeBodies whether to attach the raw request/response bodies
     * @return an ordered map with the per-record output keys
     */
    private static Map<String, Object> toRecordMap(McpCallRecord record, boolean includeBodies)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(REC_TIMESTAMP_MS, record.getTimestampMs());
        // Coalesce a null method (McpProtocolHandler records null for a method-less / unparseable
        // request) so the key is always present - Gson (no serializeNulls) would otherwise OMIT it,
        // breaking the declared output schema and the "method" in rec e2e invariant.
        String method = record.getMethod();
        m.put(REC_METHOD, method != null ? method : ""); //$NON-NLS-1$
        m.put(REC_TOOL, StatsAggregator.keyOf(record));
        m.put(REC_DURATION_MS, record.getDurationMs());
        m.put(REC_STATUS, StatsAggregator.isErrorResponse(record.getResponseJson()) ? STATUS_ERROR : STATUS_OK);
        m.put(REC_REQUEST_CHARS, charCount(record.getRequestJson()));
        m.put(REC_RESPONSE_CHARS, charCount(record.getResponseJson()));
        if (includeBodies)
        {
            m.put(REC_REQUEST_JSON, record.getRequestJson());
            m.put(REC_RESPONSE_JSON, record.getResponseJson());
        }
        return m;
    }

    /**
     * Builds the optional statistics block from an aggregation of the filtered set:
     * the run-wide {@code summary}, the per-tool {@code rows} (heaviest context first)
     * and the {@code top3} context-eaters.
     *
     * @param stats the aggregation of the filtered records
     * @return the stats block map
     */
    private static Map<String, Object> buildStats(StatsResult stats)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(STATS_SUMMARY, buildSummary(stats));
        m.put(STATS_ROWS, buildRows(stats.getRows()));
        m.put(STATS_TOP3, buildRows(stats.getTop3()));
        return m;
    }

    /**
     * Builds the run-wide summary totals. Token figures are approximate and carry the
     * tilde-prefixed display string so they are never mistaken for exact counts.
     *
     * @param stats the aggregation of the filtered records
     * @return the summary map
     */
    private static Map<String, Object> buildSummary(StatsResult stats)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(SUMMARY_TOTAL_CALLS, stats.getTotalCalls());
        m.put(SUMMARY_TOTAL_OUTPUT_CHARS, stats.getTotalOutputChars());
        m.put(SUMMARY_APPROX_TOTAL_TOKENS, stats.getApproxTotalTokens());
        m.put(SUMMARY_APPROX_TOTAL_TOKENS_DISPLAY, stats.getApproxTotalTokensDisplay());
        m.put(SUMMARY_TOTAL_ERRORS, stats.getTotalErrors());
        return m;
    }

    /**
     * Maps a list of {@link ToolStats} rows to their JSON shapes.
     *
     * @param rows the per-tool rows
     * @return the list of row maps
     */
    private static List<Map<String, Object>> buildRows(List<ToolStats> rows)
    {
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (ToolStats r : rows)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(ROW_TOOL, r.getToolName());
            m.put(ROW_CALLS, r.getCalls());
            m.put(ROW_SHARE_PERCENT, r.getSharePercent());
            m.put(ROW_TOTAL_DURATION_MS, r.getTotalDurationMs());
            m.put(ROW_AVG_DURATION_MS, r.getAvgDurationMs());
            m.put(ROW_REQUEST_CHARS, r.getRequestChars());
            m.put(ROW_REQUEST_WORDS, r.getRequestWords());
            m.put(ROW_RESPONSE_CHARS, r.getResponseChars());
            m.put(ROW_RESPONSE_WORDS, r.getResponseWords());
            m.put(ROW_APPROX_TOKENS, r.getApproxTokens());
            m.put(ROW_APPROX_TOKENS_DISPLAY, r.getApproxTokensDisplay());
            m.put(ROW_CONTEXT_WEIGHT, r.getContextWeight());
            m.put(ROW_ERROR_COUNT, r.getErrorCount());
            out.add(m);
        }
        return out;
    }

    /**
     * Character count of a payload (0 for {@code null}).
     *
     * @param s the payload (may be {@code null})
     * @return its length, or 0
     */
    private static int charCount(String s)
    {
        return s == null ? 0 : s.length();
    }
}
