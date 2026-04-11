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
        return "Get profiling (performance measurement) results after a debug session. " //$NON-NLS-1$
            + "Returns per-module, per-line data: call count, timing, percentage. " //$NON-NLS-1$
            + "Optionally filter by module name. Call after start_profiling + test run."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("moduleFilter", "Optional substring filter on module name") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("minFrequency", "Only include lines called at least N times (default: 1)") //$NON-NLS-1$ //$NON-NLS-2$
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
                    lineInfo.put("dur", Math.round(((Number) getDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$
                    lineInfo.put("pureDur", Math.round(((Number) getPureDurability.invoke(lr)).doubleValue() * 1000.0) / 1000.0); //$NON-NLS-1$

                    String code = (String) getLine.invoke(lr);
                    if (code != null && code.length() > 120)
                    {
                        code = code.substring(0, 120) + "..."; //$NON-NLS-1$
                    }
                    lineInfo.put("code", code); //$NON-NLS-1$
                    lineInfo.put("method", getMethodSignature.invoke(lr)); //$NON-NLS-1$

                    lines.add(lineInfo);
                }

                summary.put("moduleCount", moduleGroups.size()); //$NON-NLS-1$
                summary.put("modules", moduleGroups); //$NON-NLS-1$
                resultSummaries.add(summary);
            }

            return ToolResult.success()
                .put("count", resultSummaries.size()) //$NON-NLS-1$
                .put("results", resultSummaries) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_profiling_results", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
