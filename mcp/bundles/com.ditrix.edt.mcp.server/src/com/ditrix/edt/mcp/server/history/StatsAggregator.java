/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.McpConstants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Pure, side-effect-free aggregator that turns a snapshot of recorded MCP calls
 * into per-tool context-usage statistics.
 *
 * <p>It never records anything and never mutates its input: it takes a
 * {@code List<McpCallRecord>} snapshot (typically a copy of the recorder's ring
 * buffer) and produces a {@link StatsResult}. The single accumulation scan is
 * O(n) over the snapshot (per-record JSON parsing is bounded by the recorder's
 * per-record size cap); only the small distinct-key set is sorted afterwards.
 *
 * <p><b>Cross-slice contract.</b> This aggregator consumes {@code McpCallRecord}
 * (authored in a sibling slice) through the following read-only surface:
 * <ul>
 * <li>{@code long getTimestampMs()} &mdash; record time (for the window filter)</li>
 * <li>{@code String getMethod()} &mdash; the JSON-RPC method
 *     ({@code tools/call}, {@code initialize}, ...)</li>
 * <li>{@code String getToolName()} &mdash; the tool name for {@code tools/call}
 *     (may be {@code null} otherwise)</li>
 * <li>{@code String getRequestJson()} &mdash; the recorded request payload</li>
 * <li>{@code String getResponseJson()} &mdash; the recorded response payload</li>
 * <li>{@code long getDurationMs()} &mdash; wall-clock duration of the call</li>
 * </ul>
 * The error/OK status is <i>not</i> read from the record: this class owns its own
 * classifier ({@link #isErrorResponse(String)}) so the outcome always matches the
 * protocol handler contract (a JSON-RPC top-level {@code error}, or a tool result
 * with {@code isError:true} / {@code success:false}) rather than any substring
 * heuristic.
 *
 * <p>There is deliberately <b>no project dimension</b> in this v1 aggregator.
 */
public final class StatsAggregator
{
    /**
     * Prefix marking a token figure as approximate. Approximate counts must never
     * be rendered as exact numbers.
     */
    public static final String APPROX_PREFIX = "~"; //$NON-NLS-1$

    /**
     * Fallback row key for a record that carries no JSON-RPC method at all (should
     * not normally happen). Real methods (initialize / tools/list / notifications /
     * ping / …) are keyed by the method name itself, so each gets its own row.
     */
    public static final String NON_TOOL_METHODS_KEY = "(unknown)"; //$NON-NLS-1$

    // Response-envelope keys used by the status classifier. These mirror the wire
    // shape produced by the protocol handler; they are matched structurally (typed
    // JSON members), never by substring.
    private static final String KEY_ERROR = "error"; //$NON-NLS-1$
    private static final String KEY_RESULT = "result"; //$NON-NLS-1$
    private static final String KEY_IS_ERROR = "isError"; //$NON-NLS-1$
    private static final String KEY_STRUCTURED_CONTENT = "structuredContent"; //$NON-NLS-1$
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$

    /** Ranks context-eaters first: context weight desc, then calls desc, then key. */
    private static final Comparator<ToolStats> BY_CONTEXT_WEIGHT_DESC =
        Comparator.comparingLong(ToolStats::getContextWeight).reversed()
            .thenComparing(Comparator.comparingLong(ToolStats::getCalls).reversed())
            .thenComparing(ToolStats::getToolName);

    private static final int TOP_N = 3;

    private StatsAggregator()
    {
        // Utility class.
    }

    /**
     * Aggregates the whole snapshot (no time-window filter).
     *
     * @param snapshot the recorded calls; {@code null} is treated as empty
     * @return the computed statistics, never {@code null}
     */
    public static StatsResult aggregate(List<McpCallRecord> snapshot)
    {
        return aggregate(snapshot, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    /**
     * Aggregates the records whose timestamp falls in the half-open window
     * {@code [fromMillisInclusive, toMillisExclusive)}. Use {@link Long#MIN_VALUE}
     * / {@link Long#MAX_VALUE} for an open bound.
     *
     * @param snapshot the recorded calls; {@code null} is treated as empty
     * @param fromMillisInclusive lower bound on {@code getTimestampMs()}, inclusive
     * @param toMillisExclusive upper bound on {@code getTimestampMs()}, exclusive
     * @return the computed statistics, never {@code null}
     */
    public static StatsResult aggregate(List<McpCallRecord> snapshot, long fromMillisInclusive,
        long toMillisExclusive)
    {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        long totalCalls = 0;

        if (snapshot != null)
        {
            for (McpCallRecord record : snapshot)
            {
                if (record == null)
                {
                    continue;
                }
                long ts = record.getTimestampMs();
                if (ts < fromMillisInclusive || ts >= toMillisExclusive)
                {
                    continue;
                }

                Accumulator acc = byKey.computeIfAbsent(keyOf(record), Accumulator::new);
                String request = record.getRequestJson();
                String response = record.getResponseJson();

                acc.calls++;
                acc.totalDurationMs += Math.max(0L, record.getDurationMs());
                acc.requestChars += charCount(request);
                acc.requestWords += countWords(request);
                acc.responseChars += charCount(response);
                acc.responseWords += countWords(response);
                if (isErrorResponse(response))
                {
                    acc.errorCount++;
                }
                totalCalls++;
            }
        }

        List<ToolStats> rows = new ArrayList<>(byKey.size());
        long totalOutputChars = 0;
        long totalErrors = 0;
        for (Accumulator acc : byKey.values())
        {
            rows.add(new ToolStats(acc.key, acc.calls, acc.totalDurationMs, acc.requestChars,
                acc.requestWords, acc.responseChars, acc.responseWords, acc.errorCount, totalCalls));
            totalOutputChars += acc.responseChars;
            totalErrors += acc.errorCount;
        }
        rows.sort(BY_CONTEXT_WEIGHT_DESC);

        List<ToolStats> top = new ArrayList<>(rows.subList(0, Math.min(TOP_N, rows.size())));

        return new StatsResult(rows, top, totalCalls, totalOutputChars, totalErrors);
    }

    /**
     * Row key for a record: the tool name for a {@code tools/call} with a non-blank
     * tool, otherwise the JSON-RPC method itself (so {@code tools/list},
     * {@code initialize}, {@code notifications/initialized}, {@code ping}, … each get
     * their own row instead of being lumped together — {@code tools/list} in
     * particular is a real context-eater). Falls back to {@link #NON_TOOL_METHODS_KEY}
     * only for a record with no method at all.
     *
     * @param record the record to key
     * @return the row key, never {@code null}
     */
    static String keyOf(McpCallRecord record)
    {
        String method = record.getMethod();
        if (McpConstants.METHOD_TOOLS_CALL.equals(method))
        {
            String tool = record.getToolName();
            if (tool != null && !tool.trim().isEmpty())
            {
                return tool;
            }
        }
        if (method != null && !method.trim().isEmpty())
        {
            return method;
        }
        return NON_TOOL_METHODS_KEY;
    }

    /**
     * Classifies a recorded response as an error outcome, matching the protocol
     * handler contract: a JSON-RPC top-level {@code error} object, or a tool
     * result carrying {@code isError:true} or {@code success:false} (structurally,
     * never by substring). Any parse failure or non-object payload is treated as a
     * non-error so the stats path never throws.
     *
     * @param responseJson the recorded response payload (may be {@code null})
     * @return {@code true} when the response represents an error outcome
     */
    static boolean isErrorResponse(String responseJson)
    {
        if (responseJson == null)
        {
            return false;
        }
        String trimmed = responseJson.trim();
        if (trimmed.isEmpty())
        {
            return false;
        }
        try
        {
            JsonElement element = JsonParser.parseString(trimmed);
            if (!element.isJsonObject())
            {
                return false;
            }
            JsonObject obj = element.getAsJsonObject();

            // 1) JSON-RPC top-level error object.
            if (obj.has(KEY_ERROR) && obj.get(KEY_ERROR).isJsonObject())
            {
                return true;
            }

            // 2) tools/call result: isError:true, or structuredContent.success==false.
            JsonElement resultEl = obj.get(KEY_RESULT);
            if (resultEl != null && resultEl.isJsonObject())
            {
                JsonObject result = resultEl.getAsJsonObject();
                if (isBool(result, KEY_IS_ERROR, true))
                {
                    return true;
                }
                JsonElement sc = result.get(KEY_STRUCTURED_CONTENT);
                if (sc != null && sc.isJsonObject() && isBool(sc.getAsJsonObject(), KEY_SUCCESS, false))
                {
                    return true;
                }
                if (isBool(result, KEY_SUCCESS, false))
                {
                    return true;
                }
            }

            // 3) Defensive: an unwrapped ToolResult payload {success:false}.
            return isBool(obj, KEY_SUCCESS, false);
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * Renders an approximate token figure with the {@link #APPROX_PREFIX} so it is
     * never mistaken for an exact count.
     *
     * @param approxTokens the approximate token estimate
     * @return e.g. {@code "~1234"}
     */
    public static String formatApproxTokens(long approxTokens)
    {
        return APPROX_PREFIX + approxTokens;
    }

    /**
     * Number of characters in a payload (0 for {@code null}).
     */
    static long charCount(String s)
    {
        return s == null ? 0L : s.length();
    }

    /**
     * Number of whitespace-separated words in a payload (0 for {@code null}/blank).
     * Linear in the payload length; allocation-free.
     */
    static long countWords(String s)
    {
        if (s == null)
        {
            return 0L;
        }
        long words = 0;
        boolean inWord = false;
        for (int i = 0; i < s.length(); i++)
        {
            if (Character.isWhitespace(s.charAt(i)))
            {
                inWord = false;
            }
            else if (!inWord)
            {
                inWord = true;
                words++;
            }
        }
        return words;
    }

    private static boolean isBool(JsonObject obj, String key, boolean expected)
    {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()
            && el.getAsBoolean() == expected;
    }

    /** Mutable per-key tally used only during the single accumulation scan. */
    private static final class Accumulator
    {
        private final String key;
        private long calls;
        private long totalDurationMs;
        private long requestChars;
        private long requestWords;
        private long responseChars;
        private long responseWords;
        private long errorCount;

        Accumulator(String key)
        {
            this.key = key;
        }
    }

    /**
     * Immutable result of an aggregation: the sorted per-tool rows, the top-3
     * context-eaters, and the run-wide summary totals.
     */
    public static final class StatsResult
    {
        private final List<ToolStats> rows;
        private final List<ToolStats> top;
        private final long totalCalls;
        private final long totalOutputChars;
        private final long totalErrors;

        StatsResult(List<ToolStats> rows, List<ToolStats> top, long totalCalls, long totalOutputChars,
            long totalErrors)
        {
            this.rows = Collections.unmodifiableList(rows);
            this.top = Collections.unmodifiableList(top);
            this.totalCalls = totalCalls;
            this.totalOutputChars = totalOutputChars;
            this.totalErrors = totalErrors;
        }

        /**
         * @return the per-tool rows, ordered by context weight (heaviest first),
         *         unmodifiable
         */
        public List<ToolStats> getRows()
        {
            return rows;
        }

        /**
         * @return the up-to-three heaviest context-eaters, unmodifiable
         */
        public List<ToolStats> getTop3()
        {
            return top;
        }

        /**
         * @return total number of recorded calls in the aggregated window
         */
        public long getTotalCalls()
        {
            return totalCalls;
        }

        /**
         * @return total response characters across all calls (context input volume)
         */
        public long getTotalOutputChars()
        {
            return totalOutputChars;
        }

        /**
         * @return approximate total tokens ({@code totalOutputChars / 4}); coarse,
         *         never exact
         */
        public long getApproxTotalTokens()
        {
            return totalOutputChars / 4;
        }

        /**
         * @return the approximate total tokens rendered with a leading tilde
         */
        public String getApproxTotalTokensDisplay()
        {
            return formatApproxTokens(getApproxTotalTokens());
        }

        /**
         * @return total number of calls classified as an error outcome
         */
        public long getTotalErrors()
        {
            return totalErrors;
        }
    }
}
