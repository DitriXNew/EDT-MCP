/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;
import com._1c.g5.v8.dt.profiling.core.compare.ICompareService;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProfilingReport;
import com.ditrix.edt.mcp.server.utils.ProfilingSupport;
import com.ditrix.edt.mcp.server.utils.ProfilingSessionRegistry;

/** Select, summarize, page and compare persisted EDT measurements. */
public class GetProfilingResultsTool implements IMcpTool
{
    public static final String NAME = "get_profiling_results";

    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Inspect BSL performance measurements: summary, list, paged lines, callers of a method and comparison.";
    }

    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("mode", "Default: summary. callers: the lines calling methodFilter.", "summary", "list", "lines",
                "callers", "compare")
            .stringProperty("measurementId", "Measurement UUID; default: latest by session date. Use mode=list.")
            .integerProperty("top", "Summary/compare top size: 1..20, default 10.")
            .stringProperty("moduleFilter", "Case-insensitive module substring.")
            .stringProperty("methodFilter", "Case-insensitive method substring; for callers, the called method's name.")
            .integerProperty("minFrequency", "Minimum line calls: 0..2147483647, default 1.")
            .integerProperty(McpKeys.LIMIT, "Page size: 1..100, default 50 (list/lines/compare lines).")
            .integerProperty("offset", "Page offset: nonnegative integer, default 0.")
            .stringProperty("baseId", "Required for compare; measurement UUID from mode=list.")
            .stringProperty("comparedId", "Compared UUID; default: measurementId or latest.")
            .enumProperty("responseFormat", "Lines only: concise (default) omits method/source columns.", "concise", "detailed")
            .stringProperty(McpKeys.APPLICATION_ID, "Selects the active-profiling flag only, not the measurement.")
            .build();
    }

    @Override public String execute(Map<String, String> params)
    {
        try
        {
            String mode = choice(JsonUtils.extractStringArgument(params, "mode"), "mode", "summary", "summary", "list", "lines",
                "callers", "compare");
            String measurementId = JsonUtils.extractStringArgument(params, "measurementId");
            int top = integer(JsonUtils.extractStringArgument(params, "top"), "top", 10, 1, 20);
            String module = JsonUtils.extractStringArgument(params, "moduleFilter");
            String method = JsonUtils.extractStringArgument(params, "methodFilter");
            int frequency = integer(JsonUtils.extractStringArgument(params, "minFrequency"), "minFrequency", 1, 0, Integer.MAX_VALUE);
            int limit = Pagination.clampLimit(integer(JsonUtils.extractStringArgument(params, McpKeys.LIMIT), McpKeys.LIMIT, 50, 1, 100), 100);
            int offset = integer(JsonUtils.extractStringArgument(params, "offset"), "offset", 0, 0, Integer.MAX_VALUE);
            String baseId = JsonUtils.extractStringArgument(params, "baseId");
            String comparedId = JsonUtils.extractStringArgument(params, "comparedId");
            String response = choice(JsonUtils.extractStringArgument(params, "responseFormat"), "responseFormat", "concise", "concise", "detailed");
            String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
            if (mode.equals("compare") && (baseId == null || baseId.isBlank()))
            {
                throw new IllegalArgumentException("baseId is required for compare. Use mode=list to choose a measurement UUID.");
            }
            if (mode.equals("callers") && (method == null || method.isBlank()))
            {
                throw new IllegalArgumentException("methodFilter is required for callers: pass the name of the called "
                    + "method, e.g. FillCheckProcessing.");
            }
            IProfilingService service = profilingService();
            List<IProfilingResult> results = ProfilingSupport.newest(service.getResults());
            String state = "profilingActive: " + (applicationId == null || applicationId.isBlank()
                ? ProfilingSessionRegistry.isAnyProfilingActive() : ProfilingSessionRegistry.isProfilingActive(applicationId)) + ".\n\n";
            if (mode.equals("list"))
            {
                if (measurementId != null) { ProfilingSupport.select(results, measurementId); }
                return (measurementId == null ? "" : ProfilingReport.identity(ProfilingSupport.select(results, measurementId)))
                    + ProfilingReport.measurements(results, offset, limit) + state;
            }
            if (results.isEmpty() && measurementId == null && !mode.equals("compare"))
            {
                return "Measurement: none. Time unit: ms.\n\nNo profiling results available. "
                    + "Use manage_profiling_results mode=import or start_profiling before executing BSL.\n\n" + state;
            }
            IProfilingResult result = ProfilingSupport.select(results, mode.equals("compare") && comparedId != null ? comparedId : measurementId);
            if (mode.equals("compare"))
            {
                ICompareService compare = compareService();
                IProfilingResult base = ProfilingSupport.select(results, baseId);
                return ProfilingReport.compare(base, result, compare.compare(base, result), module, method, frequency, top, offset, limit) + state;
            }
            List<ProfilingReport.Row> all = ProfilingReport.rows(result);
            List<ProfilingReport.Row> selected = ProfilingReport.filter(all, module, method, frequency);
            if (mode.equals("callers"))
            {
                return ProfilingReport.callers(result, all, module, method, frequency, offset, limit) + state;
            }
            if (mode.equals("lines"))
            {
                List<ProfilingReport.Row> shown = ProfilingReport.page(ProfilingReport.byPure(selected), offset, limit);
                return ProfilingReport.identity(result) + ProfilingReport.unresolvedNotice(all) + "Lines sorted by pure time.\n\n"
                    + ProfilingReport.pageCount(offset, shown.size(), selected.size())
                    + ProfilingReport.lineTable(shown, response.equals("detailed")) + state;
            }
            return ProfilingReport.summary(result, all, selected, top) + state;
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return ToolResult.error("Cannot get profiling results: " + PlatformFailures.describe(e)
                + " Use mode=list to verify IDs and check the requested mode.").toJson();
        }
    }

    protected IProfilingService profilingService() { return ProfilingSupport.service(); }
    protected ICompareService compareService()
    {
        ICompareService service = ServiceAccess.get(ICompareService.class);
        if (service == null) { throw new IllegalStateException("ICompareService is unavailable. Enable EDT profiling."); }
        return service;
    }
    private static int integer(String raw, String name, int fallback, int minimum, int maximum)
    {
        if (raw == null) { return fallback; }
        Integer value = JsonUtils.parseIntegralInt(raw);
        if (value == null || value < minimum || value > maximum)
        {
            throw new IllegalArgumentException(name + " '" + raw + "' is invalid. Supply an integer in " + minimum + ".." + maximum + ".");
        }
        return value;
    }
    private static String choice(String raw, String name, String fallback, String... choices)
    {
        if (raw == null) { return fallback; }
        if (!List.of(choices).contains(raw))
        {
            throw new IllegalArgumentException(name + " '" + raw + "' is invalid. Use one of: " + String.join(", ", choices) + ".");
        }
        return raw;
    }
}
