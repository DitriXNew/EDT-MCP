/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/** Shared validation and bounded-wait helpers for background-job polling tools. */
public final class BackgroundJobPolling
{
    private BackgroundJobPolling()
    {
        // Utility class
    }

    /**
     * Reads an optional per-call wait and enforces its transport-safe range.
     *
     * @param params tool arguments
     * @param key wait parameter name
     * @param defaultSeconds value used when the parameter is absent
     * @param maxSeconds largest transport-safe wait
     * @return validated seconds, or {@code null} when the supplied value is invalid
     */
    public static Integer readWaitSeconds(Map<String, String> params, String key,
        int defaultSeconds, int maxSeconds)
    {
        if (params == null || !params.containsKey(key))
        {
            return Integer.valueOf(defaultSeconds);
        }
        int value = JsonUtils.extractIntArgument(params, key, Integer.MIN_VALUE);
        return value >= 0 && value <= maxSeconds ? Integer.valueOf(value) : null;
    }

    /**
     * Builds the canonical actionable error for an invalid polling wait.
     *
     * @param key wait parameter name
     * @param value invalid caller value
     * @param defaultSeconds default per-call wait
     * @param maxSeconds largest transport-safe wait
     * @return error result JSON
     */
    public static String waitSecondsError(String key, String value, int defaultSeconds,
        int maxSeconds)
    {
        return ToolResult.error(key + " must be an integer from 0 to " //$NON-NLS-1$
            + maxSeconds + ", but was '" + value + "'. Pass 0 to return immediately, " //$NON-NLS-1$ //$NON-NLS-2$
            + "or omit it to wait up to " + defaultSeconds + " seconds in this call.") //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /** Returns immediately for zero, otherwise waits up to the validated per-call budget. */
    public static JobSnapshot await(BackgroundJobs jobs, String jobId, int waitSeconds)
    {
        return waitSeconds == 0 ? jobs.get(jobId)
            : jobs.await(jobId, TimeUnit.SECONDS.toMillis(waitSeconds));
    }
}
