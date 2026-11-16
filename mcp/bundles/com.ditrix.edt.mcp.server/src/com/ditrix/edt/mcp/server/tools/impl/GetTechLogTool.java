// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogAnalysis;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogCapture;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogKnowledge;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader;

/** Read-only technological-log reference, aggregate and occurrence navigation. */
public class GetTechLogTool implements IMcpTool
{
    public static final String NAME = "get_tech_log";
    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Analyze 1C JSON technological logs: problems first, then details and correlated chains; describe explains events and capture profiles. "
            + "Absent events do not prove absence of a problem. Full parameters and examples: call get_tool_guide('get_tech_log').";
    }
    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("mode", "Default summary; describe needs no path.", "describe", "summary", "details", "chain", "events")
            .stringProperty("path", "Absolute JSON .log/.log.zip file or directory; required except describe.")
            .stringProperty("event", "Event reference in describe, exact event filter in events.")
            .stringProperty("property", "Property reference in describe; property name for contains in events.")
            .stringProperty("profile", "Capture profile reference in describe.")
            .stringProperty("problem", "Problem reference in describe; ids come from summary.")
            .stringProperty("groupId", "Group id from summary; required in details.")
            .stringProperty("occurrenceId", "Occurrence id from details/events; required in chain.")
            .integerProperty("minDurationMs", "Minimum event duration in milliseconds; default 0.")
            .stringProperty("contains", "Case-insensitive substring in property; events only.")
            .stringProperty("session", "Exact SessionID filter; events only.")
            .stringProperty("from", "ISO completion/interval lower bound; no timezone means platform local time.")
            .stringProperty("to", "ISO interval upper bound; same clock as log timestamps.")
            .integerProperty(McpKeys.LIMIT, "Page size, default 100, maximum 1000.")
            .integerProperty("offset", "Zero-based page offset, default 0.")
            .integerProperty("readBudgetMb", "Expanded-byte budget, default 64 MB, range 1..512.")
            .integerProperty("readBudgetSeconds", "Read time budget, default 10 seconds, range 1..30.").build();
    }
    @Override public ResponseType getResponseType() { return ResponseType.MARKDOWN; }
    @Override public boolean returnsInfobaseData() { return true; }

    @Override public String execute(Map<String, String> params)
    {
        String warning = TechLogCapture.user().warning();
        try
        {
            String mode = value(JsonUtils.extractStringArgument(params, "mode"), "summary");
            String path = JsonUtils.extractStringArgument(params, "path");
            String event = JsonUtils.extractStringArgument(params, "event");
            String property = JsonUtils.extractStringArgument(params, "property");
            String profile = JsonUtils.extractStringArgument(params, "profile");
            String problem = JsonUtils.extractStringArgument(params, "problem");
            String groupId = JsonUtils.extractStringArgument(params, "groupId");
            String occurrenceId = JsonUtils.extractStringArgument(params, "occurrenceId");
            int minDuration = integer(JsonUtils.extractStringArgument(params, "minDurationMs"), "minDurationMs", 0, 0, Integer.MAX_VALUE);
            String contains = JsonUtils.extractStringArgument(params, "contains");
            String session = JsonUtils.extractStringArgument(params, "session");
            String from = JsonUtils.extractStringArgument(params, "from");
            String to = JsonUtils.extractStringArgument(params, "to");
            int limit = Pagination.clampLimit(integer(JsonUtils.extractStringArgument(params, McpKeys.LIMIT), "limit", 100, 1, Integer.MAX_VALUE), Pagination.MAX_LIMIT);
            int offset = integer(JsonUtils.extractStringArgument(params, "offset"), "offset", 0, 0, Integer.MAX_VALUE);
            int mb = integer(JsonUtils.extractStringArgument(params, "readBudgetMb"), "readBudgetMb", 64, 1, TechLogReader.MAX_BUDGET_MB);
            int seconds = integer(JsonUtils.extractStringArgument(params, "readBudgetSeconds"), "readBudgetSeconds", 10, 1, TechLogReader.MAX_BUDGET_SECONDS);
            if (mode.equals("describe"))
            {
                List<String> categories = new ArrayList<>(), ids = new ArrayList<>();
                if (present(event)) { categories.add("events"); ids.add(event); }
                if (present(property)) { categories.add("properties"); ids.add(property); }
                if (present(profile)) { categories.add("profiles"); ids.add(profile); }
                if (present(problem)) { categories.add("problems"); ids.add(problem); }
                if (ids.size() > 1) throw new IllegalArgumentException("describe accepts one of event, property, profile, problem; call it separately for each entry.");
                return warning + TechLogKnowledge.describe(ids.isEmpty() ? "" : categories.get(0), ids.isEmpty() ? "" : ids.get(0));
            }
            if (!List.of("summary", "details", "chain", "events").contains(mode))
                throw new IllegalArgumentException("Unknown mode '" + mode + "'; use describe, summary, details, chain or events.");
            if (!present(path)) throw new IllegalArgumentException("path is required for " + mode + "; pass an absolute JSON log file/directory or use mode=describe.");
            var index = TechLogReader.read(Path.of(path), mb, seconds, from, to);
            if (mode.equals("summary")) return warning + TechLogAnalysis.summary(index, limit, offset);
            String header = warning + TechLogAnalysis.header(index) + "\n";
            try (var reads = new TechLogReader.ReadSession(mb, seconds))
            {
                if (mode.equals("details"))
                {
                    if (!present(groupId)) throw new IllegalArgumentException("groupId is required in details; get it from summary for the same path and filters.");
                    return header + TechLogAnalysis.renderEvents(TechLogAnalysis.details(index, groupId), limit, offset, reads);
                }
                if (mode.equals("chain"))
                {
                    if (!present(occurrenceId)) throw new IllegalArgumentException("occurrenceId is required in chain; get it from details or events.");
                    var chain = TechLogAnalysis.chain(index, occurrenceId);
                    var anchor = chain.events().stream().filter(e -> e.id().equals(occurrenceId)).findFirst().orElseThrow();
                    return header + chain.completeness() + "\n\n" + TechLogAnalysis.renderEvents(chain.events(), limit, offset, reads, anchor.start());
                }
                if (present(contains) && !present(property))
                    throw new IllegalArgumentException("contains requires property in events; for example property=Context, contains=Module.");
                List<TechLogReader.Event> matches = new ArrayList<>();
                LocalDateTime lower = present(from) ? TechLogReader.time(from) : LocalDateTime.MIN;
                LocalDateTime upper = present(to) ? TechLogReader.time(to) : LocalDateTime.MAX;
                for (var e : index.events)
                {
                    if (present(event) && !e.name().equalsIgnoreCase(event) || e.durationUs() < minDuration * 1000L
                        || present(session) && !e.session().equals(session) || e.end().isBefore(lower) || e.end().isAfter(upper)) continue;
                    if (present(contains) && !TechLogReader.get(reads.read(e.pointer()), property)
                        .toLowerCase(java.util.Locale.ROOT).contains(contains.toLowerCase(java.util.Locale.ROOT))) continue;
                    matches.add(e);
                }
                return header + TechLogAnalysis.renderEvents(matches, limit, offset, reads);
            }
        }
        catch (Exception e) { return ToolResult.error(warning + "get_tech_log: " + e.getMessage()
            + ". Use mode=describe or get_tool_guide('get_tech_log') for supported inputs.").toJson(); }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static String value(String value, String fallback)
    { return present(value) ? value : fallback; }
    private static int integer(String raw, String key, int fallback, int min, int max)
    {
        if (raw == null) return fallback;
        Integer value = JsonUtils.parseIntegralInt(raw);
        if (value == null || value < min || value > max)
            throw new IllegalArgumentException(key + "='" + raw + "' must be an integer in " + min + ".." + max + "; retry with a valid value");
        return value;
    }
}
