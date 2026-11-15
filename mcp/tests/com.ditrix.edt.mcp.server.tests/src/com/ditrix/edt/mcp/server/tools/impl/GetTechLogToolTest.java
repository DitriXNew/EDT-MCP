// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.TechLogTest;

public class GetTechLogToolTest
{
    private final GetTechLogTool tool = new GetTechLogTool();
    @Test public void contract()
    {
        assertEquals("get_tech_log", tool.getName()); assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertTrue(tool.getDescription().contains("get_tool_guide('get_tech_log')"));
        assertTrue(com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier.classify(tool.getName()).getReadOnlyHint());
        assertTrue(com.google.gson.JsonParser.parseString(tool.getInputSchema()).getAsJsonObject().getAsJsonObject("properties").has("occurrenceId"));
    }
    @Test public void referenceWithoutPathAndAllSelectors()
    {
        assertTrue(tool.execute(Map.of("mode", "describe")).contains("Workflow"));
        for (var pair : Map.of("event", "TLOCK", "property", "duration", "profile", "db", "problem", "managed-deadlocks").entrySet())
        {
            String result = tool.execute(Map.of("mode", "describe", pair.getKey(), pair.getValue()));
            assertTrue(result, result.contains("https://"));
        }
        assertTrue(tool.execute(Map.of("mode", "describe", "event", "TDEADLOK")).contains("TDEADLOCK"));
    }
    @Test public void defaultSummaryDetailsAndChain() throws Exception
    {
        Path path = TechLogTest.root().resolve("tests/fixtures/tech-log/synthetic.log");
        String summary = tool.execute(Map.of("path", path.toString()));
        assertTrue(summary, summary.contains("## Problems")); assertTrue(summary.contains("## Coverage"));
        var matcher = java.util.regex.Pattern.compile("\\| ([a-f0-9]{24}) \\| process-crashes").matcher(summary);
        assertTrue(summary, matcher.find()); String id = matcher.group(1);
        String details = tool.execute(Map.of("mode", "details", "path", path.toString(), "groupId", id));
        assertTrue(details, details.contains("Abnormal termination"));
        matcher = java.util.regex.Pattern.compile("\\| ([a-f0-9]{24}) \\|").matcher(details);
        assertTrue(matcher.find()); String occurrence = matcher.group(1);
        assertTrue(tool.execute(Map.of("mode", "chain", "path", path.toString(), "occurrenceId", occurrence)).contains("INCOMPLETE"));
    }
    @Test public void eventsFiltersAndPaging()
    {
        String path = TechLogTest.root().resolve("tests/fixtures/tech-log/synthetic.log").toString();
        String result = tool.execute(Map.of("mode", "events", "path", path, "event", "EXCP", "session", "7",
            "property", "descr", "contains", "FAILURE", "limit", "1", "offset", "1", "minDurationMs", "0"));
        assertTrue(result, result.contains("showing 2-2 of 2")); assertTrue(result.contains("Failure id=456"));
        assertFalse(result.contains("Failure id=123"));
    }
    @Test public void invalidInputsNameFixAndNeverEscape()
    {
        for (Map<String, String> p : java.util.List.of(Map.of("mode", "wat"), Map.of("mode", "details"),
            Map.of("mode", "describe", "event", "EXCP", "profile", "db"), Map.of("readBudgetMb", "0"),
            Map.of("limit", "1.5"), Map.of("offset", "-1")))
        { String r = tool.execute(p); assertTrue(r, r.contains("\"success\":false")); assertTrue(r.contains("get_tool_guide")); }
    }
}
