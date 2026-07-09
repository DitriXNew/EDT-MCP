/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

/**
 * One immutable per-tool row of the MCP context-usage statistics.
 *
 * <p>A row aggregates every recorded call that maps to the same key: for a
 * {@code tools/call} message the key is the tool name; every other JSON-RPC
 * method (initialize / tools/list / notifications / ping / ...) is collapsed
 * into the single synthetic bucket {@link StatsAggregator#NON_TOOL_METHODS_KEY}.
 *
 * <p>The headline metric is the <b>context weight</b> &mdash; a heuristic ranking
 * of how much of the LLM context a tool tends to fill. It is defined (per the
 * approved spec) as the response character total plus the approximate token count
 * ({@code responseChars / 4}) plus the response word count. Token counts are
 * <i>approximate</i> and are always rendered with a tilde prefix
 * ({@link StatsAggregator#APPROX_PREFIX}); they must never be presented as exact.
 *
 * <p>Instances are produced only by {@link StatsAggregator}; the class is a
 * side-effect-free value holder.
 */
public final class ToolStats
{
    private final String toolName;
    private final long calls;
    private final double sharePercent;
    private final long totalDurationMs;
    private final long avgDurationMs;
    private final long requestChars;
    private final long requestWords;
    private final long responseChars;
    private final long responseWords;
    private final long approxTokens;
    private final long contextWeight;
    private final long errorCount;

    /**
     * Builds a per-tool row from the accumulated raw sums. Derived values (share,
     * average duration, approximate tokens, context weight) are computed once here
     * so the formula lives in a single place.
     *
     * @param toolName the row key (a tool name, or the synthetic non-tool bucket)
     * @param calls number of recorded calls in this row (&gt; 0)
     * @param totalDurationMs summed wall-clock duration across the calls, in ms
     * @param requestChars summed request-JSON character count
     * @param requestWords summed request-JSON word count
     * @param responseChars summed response-JSON character count (context input)
     * @param responseWords summed response-JSON word count
     * @param errorCount number of calls classified as an error outcome
     * @param totalCallsForShare the grand total of calls across all rows, used to
     *            compute this row's share percentage
     */
    ToolStats(String toolName, long calls, long totalDurationMs, long requestChars, long requestWords,
        long responseChars, long responseWords, long errorCount, long totalCallsForShare)
    {
        this.toolName = toolName;
        this.calls = calls;
        this.totalDurationMs = totalDurationMs;
        this.requestChars = requestChars;
        this.requestWords = requestWords;
        this.responseChars = responseChars;
        this.responseWords = responseWords;
        this.errorCount = errorCount;
        this.avgDurationMs = calls > 0 ? Math.round((double)totalDurationMs / calls) : 0L;
        this.sharePercent = totalCallsForShare > 0 ? (calls * 100.0d) / totalCallsForShare : 0.0d;
        this.approxTokens = responseChars / 4;
        this.contextWeight = responseChars + this.approxTokens + responseWords;
    }

    /**
     * @return the row key: a tool name for {@code tools/call} rows, otherwise the
     *         synthetic {@link StatsAggregator#NON_TOOL_METHODS_KEY} bucket
     */
    public String getToolName()
    {
        return toolName;
    }

    /**
     * @return number of recorded calls aggregated into this row
     */
    public long getCalls()
    {
        return calls;
    }

    /**
     * @return this row's share of all recorded calls, in percent (0..100)
     */
    public double getSharePercent()
    {
        return sharePercent;
    }

    /**
     * @return summed wall-clock duration across the calls, in milliseconds
     */
    public long getTotalDurationMs()
    {
        return totalDurationMs;
    }

    /**
     * @return mean call duration in milliseconds (rounded), or 0 when no calls
     */
    public long getAvgDurationMs()
    {
        return avgDurationMs;
    }

    /**
     * @return summed request-JSON character count across the calls
     */
    public long getRequestChars()
    {
        return requestChars;
    }

    /**
     * @return summed request-JSON word count across the calls
     */
    public long getRequestWords()
    {
        return requestWords;
    }

    /**
     * @return summed response-JSON character count (what enters the LLM context)
     */
    public long getResponseChars()
    {
        return responseChars;
    }

    /**
     * @return summed response-JSON word count across the calls
     */
    public long getResponseWords()
    {
        return responseWords;
    }

    /**
     * Approximate number of tokens carried by the responses, estimated as
     * {@code responseChars / 4}. This is a coarse heuristic, never an exact count.
     *
     * @return the approximate token estimate
     */
    public long getApproxTokens()
    {
        return approxTokens;
    }

    /**
     * @return the approximate token estimate rendered with a leading tilde
     *         (e.g. {@code "~1234"}), signalling it is not an exact figure
     */
    public String getApproxTokensDisplay()
    {
        return StatsAggregator.formatApproxTokens(approxTokens);
    }

    /**
     * The context weight used to rank context-eaters: response characters plus the
     * approximate token estimate plus response words. Higher means the tool tends
     * to fill more of the LLM context.
     *
     * @return the composite context-weight metric
     */
    public long getContextWeight()
    {
        return contextWeight;
    }

    /**
     * @return number of calls in this row classified as an error outcome
     */
    public long getErrorCount()
    {
        return errorCount;
    }
}
