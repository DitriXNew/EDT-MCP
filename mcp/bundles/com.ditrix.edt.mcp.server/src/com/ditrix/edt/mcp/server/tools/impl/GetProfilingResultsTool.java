/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Retrieves profiling results after a debug session.
 * Returns per-module, per-line execution data: call count (frequency), timing,
 * and percentage — effectively a code coverage report.
 *
 * <p>Accesses {@code IProfilingService} via {@code ServiceAccess.get()} and
 * reads accumulated {@code IProfilingResult} / {@code ILineProfilingResult} data.
 */
public class GetProfilingResultsTool implements IMcpTool
{
    public static final String NAME = "get_profiling_results"; //$NON-NLS-1$

    /** Output key: number of result sets returned. */
    private static final String KEY_COUNT = "count"; //$NON-NLS-1$

    /** Output key: whether profiling is currently active. */
    private static final String KEY_PROFILING_ACTIVE = "profilingActive"; //$NON-NLS-1$

    private static final String WIRING_BUNDLE = "com._1c.g5.wiring"; //$NON-NLS-1$
    private static final String PROFILING_CORE_BUNDLE = "com._1c.g5.v8.dt.profiling.core"; //$NON-NLS-1$

    /** Max lines per module in output to avoid response explosion. */
    private static final int MAX_LINES_PER_MODULE = 200;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Get profiling (performance measurement) results after a debug session: " //$NON-NLS-1$
            + "per-module, per-line call count, timing and percentage. Returns only the MOST " //$NON-NLS-1$
            + "RECENT measurement session (historical sessions are not returned). Also reports " //$NON-NLS-1$
            + "whether profiling is currently active. Run start_profiling + the test (then stop_profiling) first. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('get_profiling_results')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("moduleFilter", "Case-insensitive substring filter on module name") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("minFrequency", "Only include lines called at least N times (default: 1)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Debug session id; when set, reports active state for that session") //$NON-NLS-1$
            .enumProperty("responseFormat", //$NON-NLS-1$
                "concise (default) = leaner per-line rows (line, calls, pct only); " //$NON-NLS-1$
                    + "detailed = full rows incl. code text, method signature and dur/pureDur timing", //$NON-NLS-1$
                "concise", "detailed") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_COUNT, "Number of result sets returned (0 or 1 — only the latest session)") //$NON-NLS-1$
            .booleanProperty(KEY_PROFILING_ACTIVE, "Whether profiling is currently active") //$NON-NLS-1$
            .stringProperty("message", "Informational note when no results are available") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("results", //$NON-NLS-1$
                "Per-result sets: name, totalDurability, moduleCount and modules map of lines") //$NON-NLS-1$
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
        String moduleFilter = JsonUtils.extractStringArgument(params, "moduleFilter"); //$NON-NLS-1$
        int minFrequency = JsonUtils.extractIntArgument(params, "minFrequency", 1); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        // Output verbosity. Default (and any blank/unrecognized value) is concise: only
        // detailed emits the verbose per-line extras (code text, method signature, dur/pureDur).
        String responseFormat = JsonUtils.extractStringArgument(params, "responseFormat"); //$NON-NLS-1$
        boolean detailed = "detailed".equalsIgnoreCase( //$NON-NLS-1$
            responseFormat == null ? null : responseFormat.trim());
        // On/off state from the shared profiling state (single source of truth in
        // StartProfilingTool). When no applicationId is given we surface whether any
        // session is profiling so a client can still tell that a stop is pending.
        boolean profilingActive = applicationId != null && !applicationId.isEmpty()
            ? StartProfilingTool.isProfilingActive(applicationId)
            : StartProfilingTool.isAnyProfilingActive();

        try
        {
            // Get IProfilingService via ServiceAccess.get(IProfilingService.class)
            Bundle wiringBundle = Platform.getBundle(WIRING_BUNDLE);
            if (wiringBundle == null)
            {
                return ToolResult.error("Wiring bundle not found").toJson(); //$NON-NLS-1$
            }
            Bundle profilingBundle = Platform.getBundle(PROFILING_CORE_BUNDLE);
            if (profilingBundle == null)
            {
                return ToolResult.error("Profiling core bundle not found").toJson(); //$NON-NLS-1$
            }

            Class<?> serviceAccessClass = wiringBundle.loadClass("com._1c.g5.wiring.ServiceAccess"); //$NON-NLS-1$
            Class<?> profilingServiceClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingService"); //$NON-NLS-1$

            Method getMethod = serviceAccessClass.getMethod("get", Class.class); //$NON-NLS-1$
            Object profilingService = getMethod.invoke(null, profilingServiceClass);
            if (profilingService == null)
            {
                return ToolResult.error("IProfilingService not available — profiling bundle may not be active").toJson(); //$NON-NLS-1$
            }

            // IProfilingService.getResults() → List<IProfilingResult>
            Method getResults = profilingServiceClass.getMethod("getResults"); //$NON-NLS-1$
            List<?> results = (List<?>) getResults.invoke(profilingService);

            if (results == null || results.isEmpty())
            {
                return ToolResult.success()
                    .put(KEY_COUNT, 0)
                    .put(KEY_PROFILING_ACTIVE, profilingActive)
                    .put("message", "No profiling results available. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Make sure you called start_profiling before running the test.") //$NON-NLS-1$
                    .toJson();
            }

            // Process each IProfilingResult
            Class<?> profilingResultClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingResult"); //$NON-NLS-1$
            Class<?> lineResultClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.ILineProfilingResult"); //$NON-NLS-1$

            Method getProfilingResults = profilingResultClass.getMethod("getProfilingResults"); //$NON-NLS-1$
            Method getTotalDurability = profilingResultClass.getMethod("getTotalDurability"); //$NON-NLS-1$
            Method getResultName = profilingResultClass.getMethod("getName"); //$NON-NLS-1$

            // Contract: return ONLY the latest measurement session — historical sessions are
            // noise. getResults() is backed by an unordered cache + a store that survives
            // restarts, so "latest" is the max getDateOfSession() (not list position).
            Method getDateOfSession = profilingResultClass.getMethod("getDateOfSession"); //$NON-NLS-1$
            results = latestOnly(results, getDateOfSession);

            // ILineProfilingResult methods
            Method getLineNo = lineResultClass.getMethod("getLineNo"); //$NON-NLS-1$
            Method getFrequency = lineResultClass.getMethod("getFrequency"); //$NON-NLS-1$
            Method getModuleName = lineResultClass.getMethod("getModuleName"); //$NON-NLS-1$
            Method getLine = lineResultClass.getMethod("getLine"); //$NON-NLS-1$
            Method getPercentage = lineResultClass.getMethod("getPercentage"); //$NON-NLS-1$
            Method getMethodSignature = lineResultClass.getMethod("getMethodSignature"); //$NON-NLS-1$

            // IProfilingTimeHolder methods (parent interface)
            Class<?> timeHolderClass = profilingBundle.loadClass(
                "com._1c.g5.v8.dt.profiling.core.IProfilingTimeHolder"); //$NON-NLS-1$
            Method getDurability = timeHolderClass.getMethod("getDurability"); //$NON-NLS-1$
            Method getPureDurability = timeHolderClass.getMethod("getPureDurability"); //$NON-NLS-1$

            ProfilingReflection refl = ProfilingReflection.builder()
                .resultName(getResultName)
                .totalDurability(getTotalDurability)
                .profilingResults(getProfilingResults)
                .frequency(getFrequency)
                .moduleName(getModuleName)
                .lineNo(getLineNo)
                .percentage(getPercentage)
                .durability(getDurability)
                .pureDurability(getPureDurability)
                .line(getLine)
                .methodSignature(getMethodSignature)
                .build();

            List<Map<String, Object>> resultSummaries = new ArrayList<>();

            for (Object result : results)
            {
                resultSummaries.add(summarizeResult(result, refl, moduleFilter, minFrequency, detailed));
            }

            return ToolResult.success()
                .put(KEY_COUNT, resultSummaries.size())
                .put(KEY_PROFILING_ACTIVE, profilingActive)
                .put("results", resultSummaries) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_profiling_results", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Reduces the accumulated profiling results to a singleton list holding ONLY the most
     * recent measurement session (max {@code getDateOfSession()}). {@code getResults()} is
     * backed by an unordered cache plus a persistent store, so "latest" must be computed
     * from the session date, not list order. Historical sessions are intentionally dropped
     * — only the last measurement is meaningful. Falls back to the last element if no
     * session dates are present. Caller has already verified {@code results} is non-empty.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<?> latestOnly(List<?> results, Method getDateOfSession) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Object latest = null;
        Comparable latestDate = null;
        for (Object result : results)
        {
            Object date = getDateOfSession.invoke(result);
            if (!(date instanceof Comparable))
            {
                continue;
            }
            // Strict '>' so on an exact-date tie the first max wins; any tied session is
            // equally "latest" and count stays 1 either way.
            if (latestDate == null || ((Comparable) date).compareTo(latestDate) > 0)
            {
                latestDate = (Comparable) date;
                latest = result;
            }
        }
        if (latest == null)
        {
            latest = results.get(results.size() - 1);
        }
        return List.of(latest);
    }

    /**
     * Builds the summary map for a single {@code IProfilingResult}: its name/totalDurability plus
     * the per-module groups of qualifying lines. When the result exposes no line data, the summary
     * carries {@code lines=0} and no modules — exactly as the inline loop did before.
     */
    private static Map<String, Object> summarizeResult(Object result, ProfilingReflection refl,
        String moduleFilter, int minFrequency, boolean detailed) throws Exception
    {
        Map<String, Object> summary = new LinkedHashMap<>();
        String name = (String) refl.getResultName.invoke(result);
        double totalDur = ((Number) refl.getTotalDurability.invoke(result)).doubleValue();
        summary.put("name", name); //$NON-NLS-1$
        summary.put("totalDurability", Math.round(totalDur * 1000.0) / 1000.0); //$NON-NLS-1$

        List<?> lineResults = (List<?>) refl.getProfilingResults.invoke(result);
        if (lineResults == null)
        {
            summary.put("lines", 0); //$NON-NLS-1$
            return summary;
        }

        // Group by module
        Map<String, List<Map<String, Object>>> moduleGroups = new LinkedHashMap<>();
        for (Object lr : lineResults) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            long freq = (long) refl.getFrequency.invoke(lr);
            if (freq < minFrequency)
            {
                continue;
            }

            String modName = (String) refl.getModuleName.invoke(lr);
            if (modName == null) modName = "?"; //$NON-NLS-1$

            if (moduleFilter != null && !moduleFilter.isEmpty()
                && !modName.toLowerCase().contains(moduleFilter.toLowerCase()))
            {
                continue;
            }

            List<Map<String, Object>> lines = moduleGroups.computeIfAbsent(modName,
                k -> new ArrayList<>());

            if (lines.size() >= MAX_LINES_PER_MODULE)
            {
                continue; // cap per module
            }

            lines.add(buildLineInfo(lr, freq, refl, detailed));
        }

        summary.put("moduleCount", moduleGroups.size()); //$NON-NLS-1$
        summary.put("modules", moduleGroups); //$NON-NLS-1$
        return summary;
    }

    /**
     * Builds the per-line info map for one {@code ILineProfilingResult}. The verbose timing columns
     * and the (truncated) source text + method signature are emitted only in {@code detailed} mode;
     * concise mode keeps just line/calls/pct.
     */
    private static Map<String, Object> buildLineInfo(Object lr, long freq, ProfilingReflection refl,
        boolean detailed) throws Exception // NOSONAR propagates checked exceptions across the reflective boundary by design
    {
        Map<String, Object> lineInfo = new LinkedHashMap<>();
        lineInfo.put("line", refl.getLineNo.invoke(lr)); //$NON-NLS-1$
        lineInfo.put("calls", freq); //$NON-NLS-1$
        lineInfo.put("pct", Math.round(((Number) refl.getPercentage.invoke(lr)).doubleValue() * 100.0) / 100.0); //$NON-NLS-1$

        // Verbose per-line extras only in detailed: the secondary timing columns
        // and the source text + method signature. concise keeps line/calls/pct.
        if (detailed)
        {
            lineInfo.put("dur", Math.round(((Number) refl.getDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$
            lineInfo.put("pureDur", Math.round(((Number) refl.getPureDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$

            String code = (String) refl.getLine.invoke(lr);
            if (code != null && code.length() > 120)
            {
                code = code.substring(0, 120) + "..."; //$NON-NLS-1$
            }
            lineInfo.put("code", code); //$NON-NLS-1$
            lineInfo.put("method", refl.getMethodSignature.invoke(lr)); //$NON-NLS-1$
        }

        return lineInfo;
    }

    /**
     * Immutable holder for the reflective {@code Method} handles used while summarizing a profiling
     * result. Groups them so the per-result / per-line helpers keep a small signature. Built via
     * {@link Builder} so the construction site has no long parameter list; the field values are
     * exactly the {@code Method} handles assigned to the builder.
     */
    private static final class ProfilingReflection
    {
        final Method getResultName;
        final Method getTotalDurability;
        final Method getProfilingResults;
        final Method getFrequency;
        final Method getModuleName;
        final Method getLineNo;
        final Method getPercentage;
        final Method getDurability;
        final Method getPureDurability;
        final Method getLine;
        final Method getMethodSignature;

        private ProfilingReflection(Builder b)
        {
            this.getResultName = b.getResultName;
            this.getTotalDurability = b.getTotalDurability;
            this.getProfilingResults = b.getProfilingResults;
            this.getFrequency = b.getFrequency;
            this.getModuleName = b.getModuleName;
            this.getLineNo = b.getLineNo;
            this.getPercentage = b.getPercentage;
            this.getDurability = b.getDurability;
            this.getPureDurability = b.getPureDurability;
            this.getLine = b.getLine;
            this.getMethodSignature = b.getMethodSignature;
        }

        static Builder builder()
        {
            return new Builder();
        }

        /**
         * Mutable builder that collects the reflective {@code Method} handles before they are
         * frozen into an immutable {@link ProfilingReflection}. Exists solely to keep the
         * construction site free of an 11-argument call; it carries no logic.
         */
        private static final class Builder
        {
            private Method getResultName;
            private Method getTotalDurability;
            private Method getProfilingResults;
            private Method getFrequency;
            private Method getModuleName;
            private Method getLineNo;
            private Method getPercentage;
            private Method getDurability;
            private Method getPureDurability;
            private Method getLine;
            private Method getMethodSignature;

            Builder resultName(Method m) { this.getResultName = m; return this; }
            Builder totalDurability(Method m) { this.getTotalDurability = m; return this; }
            Builder profilingResults(Method m) { this.getProfilingResults = m; return this; }
            Builder frequency(Method m) { this.getFrequency = m; return this; }
            Builder moduleName(Method m) { this.getModuleName = m; return this; }
            Builder lineNo(Method m) { this.getLineNo = m; return this; }
            Builder percentage(Method m) { this.getPercentage = m; return this; }
            Builder durability(Method m) { this.getDurability = m; return this; }
            Builder pureDurability(Method m) { this.getPureDurability = m; return this; }
            Builder line(Method m) { this.getLine = m; return this; }
            Builder methodSignature(Method m) { this.getMethodSignature = m; return this; }

            ProfilingReflection build()
            {
                return new ProfilingReflection(this);
            }
        }
    }
}
