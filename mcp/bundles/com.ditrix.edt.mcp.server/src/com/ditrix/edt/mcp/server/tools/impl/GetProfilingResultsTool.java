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
 * Retrieves profiling (замер производительности) results after a debug session.
 * Returns per-module, per-line execution data: call count (frequency), timing,
 * and percentage — effectively a code coverage report.
 *
 * <p>Accesses {@code IProfilingService} via {@code ServiceAccess.get()} and
 * reads accumulated {@code IProfilingResult} / {@code ILineProfilingResult} data.
 */
public class GetProfilingResultsTool implements IMcpTool
{
    public static final String NAME = "get_profiling_results"; //$NON-NLS-1$

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
            + "per-module, per-line call count, timing and percentage. Also reports whether " //$NON-NLS-1$
            + "profiling is currently active. Run start_profiling + the test (then stop_profiling) first. " //$NON-NLS-1$
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
            .integerProperty("count", "Number of profiling result sets returned") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("profilingActive", "Whether profiling is currently active") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Informational note when no results are available") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("results", //$NON-NLS-1$
                "Per-result sets: name, totalDurability, moduleCount and modules map of lines") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Retrieve the accumulated 1C performance-measurement (profiling) readout: which BSL " //$NON-NLS-1$
            + "lines ran, how often, and how long. Effectively a line-level coverage + timing report, " //$NON-NLS-1$
            + "grouped per module. Returns JSON.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- After a profiled debug run, to see which code was exercised and where time went.\n" //$NON-NLS-1$
            + "- Typical sequence: `debug_launch` (or `debug_yaxunit_tests`) -> `start_profiling` -> " //$NON-NLS-1$
            + "run the scenario/test -> `stop_profiling` (finalizes collection) -> `get_profiling_results`.\n" //$NON-NLS-1$
            + "- You can also call it just to check whether profiling is currently active (see " //$NON-NLS-1$
            + "`applicationId` below) without having results yet.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `moduleFilter` - optional case-insensitive substring matched against the module name; " //$NON-NLS-1$
            + "only matching modules appear. Omit to include every module.\n" //$NON-NLS-1$
            + "- `minFrequency` - optional integer (default `1`); drops lines whose call count is below " //$NON-NLS-1$
            + "this threshold. Raise it to hide rarely-hit lines and focus on hot paths.\n" //$NON-NLS-1$
            + "- `applicationId` - optional debug session id. When supplied, the `profilingActive` field " //$NON-NLS-1$
            + "reflects that specific session's on/off state. When omitted, `profilingActive` reflects " //$NON-NLS-1$
            + "whether ANY session is currently profiling, so a client can still tell a stop is pending.\n" //$NON-NLS-1$
            + "- `responseFormat` - optional, `concise` (default) or `detailed`. `concise` returns lean " //$NON-NLS-1$
            + "per-line rows (`line`, `calls`, `pct` only) to save tokens; `detailed` adds the verbose " //$NON-NLS-1$
            + "extras `code` (source text), `method` (signature) and the `dur`/`pureDur` timing columns. " //$NON-NLS-1$
            + "An unrecognized value falls back to `concise`. The top-level `count` / `profilingActive` / " //$NON-NLS-1$
            + "`message` and the per-result `name` / `totalDurability` / `moduleCount` are identical in " //$NON-NLS-1$
            + "both formats — only the per-line detail differs.\n\n" //$NON-NLS-1$

            + "## Output\n" //$NON-NLS-1$
            + "JSON with `count` (number of profiling result sets), `profilingActive` (boolean) and " //$NON-NLS-1$
            + "`results`. Each result has `name`, `totalDurability` and a `modules` map (module name -> " //$NON-NLS-1$
            + "list of lines). In `detailed`, each line carries `line`, `calls` (frequency), `pct`, " //$NON-NLS-1$
            + "`dur`, `pureDur`, `code` (source text, truncated to 120 chars) and `method` (the method " //$NON-NLS-1$
            + "signature); `concise` (the default) keeps only `line`, `calls` and `pct`.\n" //$NON-NLS-1$
            + "Output is capped at 200 lines per module to keep the response bounded; narrow with " //$NON-NLS-1$
            + "`moduleFilter` / `minFrequency` if you hit the cap.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- Everything collected: `{}`.\n" //$NON-NLS-1$
            + "- Hot lines in one module: `{moduleFilter: \"CommonModule\", minFrequency: 10}`.\n" //$NON-NLS-1$
            + "- Check a specific session's state: `{applicationId: \"<id>\"}`.\n\n" //$NON-NLS-1$

            + "## Notes & gotchas\n" //$NON-NLS-1$
            + "- An empty/zero result usually means `start_profiling` was not called before the run, or " //$NON-NLS-1$
            + "the data has not been finalized yet - call `stop_profiling` first.\n" //$NON-NLS-1$
            + "- `profilingActive: true` with `count: 0` means a session is profiling but no results " //$NON-NLS-1$
            + "have been flushed; finish the scenario and stop profiling.\n" //$NON-NLS-1$
            + "- Results are read-only; this tool never toggles profiling on or off.\n"; //$NON-NLS-1$
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
                    .put("count", 0) //$NON-NLS-1$
                    .put("profilingActive", profilingActive) //$NON-NLS-1$
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

            List<Map<String, Object>> resultSummaries = new ArrayList<>();

            for (Object result : results)
            {
                Map<String, Object> summary = new LinkedHashMap<>();
                String name = (String) getResultName.invoke(result);
                double totalDur = ((Number) getTotalDurability.invoke(result)).doubleValue();
                summary.put("name", name); //$NON-NLS-1$
                summary.put("totalDurability", Math.round(totalDur * 1000.0) / 1000.0); //$NON-NLS-1$

                List<?> lineResults = (List<?>) getProfilingResults.invoke(result);
                if (lineResults == null)
                {
                    summary.put("lines", 0); //$NON-NLS-1$
                    resultSummaries.add(summary);
                    continue;
                }

                // Group by module
                Map<String, List<Map<String, Object>>> moduleGroups = new LinkedHashMap<>();
                for (Object lr : lineResults)
                {
                    long freq = (long) getFrequency.invoke(lr);
                    if (freq < minFrequency)
                    {
                        continue;
                    }

                    String modName = (String) getModuleName.invoke(lr);
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

                    Map<String, Object> lineInfo = new LinkedHashMap<>();
                    lineInfo.put("line", getLineNo.invoke(lr)); //$NON-NLS-1$
                    lineInfo.put("calls", freq); //$NON-NLS-1$
                    lineInfo.put("pct", Math.round(((Number) getPercentage.invoke(lr)).doubleValue() * 100.0) / 100.0); //$NON-NLS-1$

                    // Verbose per-line extras only in detailed: the secondary timing columns
                    // and the source text + method signature. concise keeps line/calls/pct.
                    if (detailed)
                    {
                        lineInfo.put("dur", Math.round(((Number) getDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$
                        lineInfo.put("pureDur", Math.round(((Number) getPureDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$

                        String code = (String) getLine.invoke(lr);
                        if (code != null && code.length() > 120)
                        {
                            code = code.substring(0, 120) + "..."; //$NON-NLS-1$
                        }
                        lineInfo.put("code", code); //$NON-NLS-1$
                        lineInfo.put("method", getMethodSignature.invoke(lr)); //$NON-NLS-1$
                    }

                    lines.add(lineInfo);
                }

                summary.put("moduleCount", moduleGroups.size()); //$NON-NLS-1$
                summary.put("modules", moduleGroups); //$NON-NLS-1$
                resultSummaries.add(summary);
            }

            return ToolResult.success()
                .put("count", resultSummaries.size()) //$NON-NLS-1$
                .put("profilingActive", profilingActive) //$NON-NLS-1$
                .put("results", resultSummaries) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_profiling_results", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
