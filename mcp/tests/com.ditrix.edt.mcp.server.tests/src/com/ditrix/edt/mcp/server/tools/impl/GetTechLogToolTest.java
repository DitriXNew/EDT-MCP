// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.TechLogTest;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRedactor;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRule;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleScope;
import com.ditrix.edt.mcp.server.utils.privacy.PiiRuleSet;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader;

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

    @Test public void detailsAreFlaggedForPiiRedactionAndEmailIsMasked() throws Exception
    {
        assertTrue(tool.returnsInfobaseData());
        Path path = Files.createTempFile("tech-log-pii-", ".log");
        try
        {
            Files.writeString(path,
                "{\"ts\":\"2026-11-15T23:43:10\",\"name\":\"CALL\",\"duration\":\"1\",\"Usr\":\"reader@example.com\"}\n");
            var index = TechLogReader.read(path, 1, 10, null, null);
            String details = tool.execute(Map.of("mode", "details", "path", path.toString(),
                "groupId", index.groups.keySet().iterator().next()));
            assertTrue(details, details.contains("reader@example.com"));
            var rules = PiiRuleSet.of(new PiiRule(true, "[\\w.]+@[\\w.]+", PiiRuleScope.VALUE, "[email]", false));
            String masked = PiiRedactor.apply(details, rules, "test-salt");
            assertFalse(masked, masked.contains("reader@example.com")); assertTrue(masked.contains("[email]"));
            String clean = details.replace("reader@example.com", "reader");
            assertSame(clean, PiiRedactor.apply(clean, rules, "test-salt"));
        }
        finally { Files.deleteIfExists(path); }
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
    @Test public void chainOffsetsUseAnchorStartEvenWhenAnchorIsNotOnPage() throws Exception
    {
        Path path = Files.createTempFile("tech-log-offsets-", ".log");
        try
        {
            String common = "\"process\":\"rphost\",\"PID\":\"42\",\"OSThread\":\"1\"";
            Files.writeString(path,
                "{\"ts\":\"2026-11-16T02:51:03.020000\",\"name\":\"CALL\",\"duration\":\"20000\"," + common + "}\n"
                + "{\"ts\":\"2026-11-16T02:51:03.013400\",\"name\":\"EXCP\",\"duration\":\"1000\"," + common + "}\n"
                + "{\"ts\":\"2026-11-16T02:51:03.016500\",\"name\":\"QERR\",\"duration\":\"1000\"," + common + "}\n");
            var index = TechLogReader.read(path, 1, 10, null, null);
            String anchor = index.events.get(1).id();
            String result = tool.execute(Map.of("mode", "chain", "path", path.toString(), "occurrenceId", anchor));
            assertTrue(result, result.contains("Offset from anchor start ms"));
            assertTrue(result, result.contains("-12.400 ms") && result.contains("+0.000 ms") && result.contains("+3.100 ms"));
            String page = tool.execute(Map.of("mode", "chain", "path", path.toString(), "occurrenceId", anchor, "limit", "1", "offset", "2"));
            assertTrue(page, page.contains("+3.100 ms"));
            assertFalse(tool.execute(Map.of("mode", "events", "path", path.toString())).contains("Offset from anchor"));
        }
        finally { Files.deleteIfExists(path); }
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
