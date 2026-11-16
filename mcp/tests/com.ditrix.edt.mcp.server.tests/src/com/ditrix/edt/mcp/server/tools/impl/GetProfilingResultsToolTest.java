/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.profiling.core.*;
import com._1c.g5.v8.dt.profiling.core.compare.*;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.ditrix.edt.mcp.server.utils.ProfilingReport;
import com.google.gson.JsonParser;

/** Drives real execute/render/file branches through typed EDT service mocks. */
public class GetProfilingResultsToolTest
{
    private IProfilingService service;
    private ICompareService compare;
    private IProfilingResult first;
    private IProfilingResult second;
    private TestTool tool;

    private class TestTool extends GetProfilingResultsTool
    {
        @Override protected IProfilingService profilingService() { return service; }
        @Override protected ICompareService compareService() { return compare; }
    }

    public static ILineProfilingResult line(String module, String method, int number, long calls, long nanos, long pure, String source)
    {
        ILineProfilingResult line = mock(ILineProfilingResult.class);
        when(line.getModuleName()).thenReturn(module);
        when(line.getMethodSignature()).thenReturn(method);
        when(line.getLineNo()).thenReturn(number);
        when(line.getFrequency()).thenReturn(calls);
        // Deliberately unrelated ticks: treating ticks as nanoseconds must fail.
        when(line.getDurability()).thenReturn(987654321L);
        when(line.getPureDurability()).thenReturn(123456789L);
        when(line.getDebugTargetType()).thenReturn(DebugTargetType.CLIENT);
        when(line.getTimeForDebugTarget(DebugTargetType.CLIENT)).thenReturn(Duration.ofNanos(nanos));
        when(line.getPureTimeForDebugTarget(DebugTargetType.CLIENT)).thenReturn(Duration.ofNanos(pure));
        when(line.getLine()).thenReturn(source);
        when(line.getServerCallSignal()).thenReturn(ServerCallSignal.NO_SERVER_CALL);
        return line;
    }

    public static IProfilingResult measurement(String name, int day, List<ILineProfilingResult> lines)
    {
        IProfilingResult result = mock(IProfilingResult.class);
        when(result.getUuid()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(result.getName()).thenReturn(name);
        when(result.getDateOfSession()).thenReturn(LocalDateTime.of(2026, 6, day, 12, 0));
        when(result.getDebugTargets()).thenReturn(Set.of(DebugTargetType.CLIENT));
        when(result.getConnectionString()).thenReturn("File=\"C:\\test\";");
        when(result.getProfilingResults()).thenReturn(lines);
        return result;
    }

    @Before public void setup()
    {
        StartProfilingTool.clearStateForTests();
        service = mock(IProfilingService.class);
        compare = mock(ICompareService.class);
        first = measurement("Before", 1, List.of(line("Module", "Slow()", 1, 2, 8_000_000, 4_000_000, "a | b"),
            line("Module", "Slow()", 2, 10, 6_000_000, 2_000_000, "second"),
            line("Other", "Fast()", 3, 20, 2_000_000, 1_000_000, "third")));
        second = measurement("After", 2, List.of(line("Module", "Slow()", 1, 1, 1_000_000, 1_000_000, "after")));
        when(service.getResults()).thenReturn(List.of(first, second));
        tool = new TestTool();
    }
    @After public void clear() { StartProfilingTool.clearStateForTests(); }

    private Map<String, String> named(String... extra)
    {
        Map<String, String> params = new java.util.HashMap<>();
        params.put("measurementId", first.getUuid().toString());
        for (int i = 0; i < extra.length; i += 2) { params.put(extra[i], extra[i + 1]); }
        return params;
    }
    private static void error(String output, String... names)
    {
        assertFalse(output, JsonParser.parseString(output).getAsJsonObject().get("success").getAsBoolean());
        for (String name : names) { assertTrue(output, output.contains(name)); }
    }

    @Test public void markdownContract()
    {
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertNull(tool.getOutputSchema());
        assertTrue(com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier.classify(tool.getName()).getReadOnlyHint());
        assertNull(tool.getAnnotations()); // Use the canonical read-only classification.
    }
    @Test public void profilingGoldenEntriesMatchTheProductionWireSchemas() throws Exception
    {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("tests/e2e/tools_list.golden.json"))) { root = root.getParent(); }
        assertNotNull("Repository root with the committed golden", root);
        var golden = JsonParser.parseString(Files.readString(root.resolve("tests/e2e/tools_list.golden.json"))).getAsJsonArray();
        for (com.ditrix.edt.mcp.server.tools.IMcpTool actual : List.of(tool, new StopProfilingTool(), new ManageProfilingResultsTool()))
        {
            var entry = java.util.stream.StreamSupport.stream(golden.spliterator(), false)
                .map(com.google.gson.JsonElement::getAsJsonObject)
                .filter(e -> e.get("name").getAsString().equals(actual.getName())).findFirst().orElseThrow();
            assertEquals(actual.getName(), entry.get("description").getAsString(), actual.getDescription());
            assertEquals(actual.getName(), entry.get("inputSchema"),
                com.ditrix.edt.mcp.server.protocol.InputSchemaCompactor.compact(actual.getName(), JsonParser.parseString(actual.getInputSchema())));
            assertEquals(actual.getName(), entry.get("outputSchema"), actual.getOutputSchema() == null ? com.google.gson.JsonNull.INSTANCE
                : com.ditrix.edt.mcp.server.protocol.OutputSchemaCompactor.compact(JsonParser.parseString(actual.getOutputSchema())));
            var annotations = actual.getAnnotations() == null
                ? com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier.classify(actual.getName()) : actual.getAnnotations();
            assertEquals(actual.getName(), entry.get("annotations"), JsonParser.parseString(
                com.ditrix.edt.mcp.server.protocol.GsonProvider.toJson(annotations)));
        }
    }
    @Test public void latestUsesDateAndNamesMeasurement()
    {
        String result = tool.execute(Map.of());
        assertTrue(result, result.contains("After") && result.contains(second.getUuid().toString()));
        assertTrue(result, result.contains("Time unit: ms") && result.contains("1.000"));
    }
    @Test public void summaryAggregatesOwnTimeAndDocumentsLineSums()
    {
        String result = tool.execute(named("top", "1"));
        assertTrue(result, result.contains("16.000") && result.contains("7.000"));
        assertTrue(result, result.contains("6.000") && result.contains("Modules: 2; methods: 2; line records: 3"));
        assertTrue(result, result.contains("showing 1 of 3") && result.contains("Increase top"));
        assertTrue(result, result.contains("Top lines by call count") && result.contains("Top modules by own"));
        assertTrue(result, result.contains("double-count") && result.contains("a \\| b"));
    }
    @Test public void pureRollupsAndCallTopsAreExact()
    {
        List<ProfilingReport.Row> rows = ProfilingReport.rows(first);
        assertEquals(6, ProfilingReport.rollups(rows, true).get(0).pure(), 0);
        assertEquals(14, ProfilingReport.rollups(rows, false).get(0).time(), 0);
        assertEquals(1, ProfilingReport.byPure(rows).get(0).line());
        String output = tool.execute(named("top", "1"));
        String callTop = output.substring(output.indexOf("## Top lines by call count"), output.indexOf("## Top lines with server calls"));
        assertTrue(callTop, callTop.contains("third") && !callTop.contains("second"));
        assertTrue(output, output.contains("2.000")); // avg pure/call = 4/2
    }
    @Test public void methodInvocationsCountTheFirstRecordedLine()
    {
        List<ProfilingReport.Row> rows = ProfilingReport.rows(first);
        var methods = ProfilingReport.rollups(rows, true);
        assertEquals("Slow()", methods.get(0).method());
        assertEquals(2, methods.get(0).invocations());
        assertEquals(12, methods.get(0).lineCalls());
        assertEquals(20, methods.get(1).invocations());
        assertEquals(-1, ProfilingReport.rollups(rows, false).get(0).invocations());
        // A filter that drops the entry line keeps the whole-measurement estimate.
        assertEquals(2, ProfilingReport.rollups(ProfilingReport.filter(rows, null, null, 5), rows, true).get(0).invocations());
        assertTrue(tool.execute(named("minFrequency", "5")).contains("| Module | Slow() | 2.000 | 6.000 | 2 | 10 |"));
        String output = tool.execute(named());
        assertTrue(output, output.contains("Invocations (est.)") && output.contains("loop header"));
        assertTrue(output, output.contains("| Module | Slow() | 6.000 | 14.000 | 2 | 12 |"));
    }
    @Test public void callersListCallLinesByInclusiveTime()
    {
        String provesti = "\u041f\u0440\u043e\u0432\u0435\u0441\u0442\u0438";
        List<ILineProfilingResult> calls = List.of(
            line("Module", "Slow()", 1, 2, 8_000_000, 4_000_000, "Slow(n - 1);"),
            line("Caller", "Run()", 5, 3, 10_000_000, 1_000_000, "Result = Slow(1);"),
            line("Other", "Go()", 2, 1, 40_000_000, 2_000_000, "x = Module.slow (2);"),
            line("Other", "Go()", 3, 1, 50_000_000, 2_000_000, "SlowDown(3);"),
            line("Other", "Go()", 4, 1, 60_000_000, 2_000_000, "NotSlow(3); y = Slow;"),
            line("Chain", "Go()", 9, 1, 1_000_000, 1_000_000, "Doc.Module.Slow(4);"),
            line("Handler", "Slow(Cancel)", 2, 1, 2_000_000, 1_000_000, "Common.Slow(Cancel);"),
            line("Text", "Log()", 4, 1, 70_000_000, 1_000_000, "Message(\"Slow() failed\"); // Slow(1)"),
            line("Text", "Log()", 5, 1, 80_000_000, 1_000_000, "|Slow(2)\" + Done(x);"),
            line("Module", "Slow()", 6, 1, 90_000_000, 1_000_000, "Slow(n - 2); // Common.Slow()"),
            line("Doc", "Post()", 7, 1, 5_000_000, 1_000_000, "Doc." + provesti.toLowerCase() + "();"));
        when(first.getProfilingResults()).thenReturn(calls);
        String output = tool.execute(named("mode", "callers", "methodFilter", "Slow"));
        assertTrue(output, output.contains("Callers of `Slow`") && output.contains("inclusive"));
        assertTrue(output, output.contains("Total over 4 call line(s): 53.000 ms inclusive, 6 calls."));
        assertTrue(output, output.indexOf("Module.slow (2)") < output.indexOf("Result = Slow(1)"));
        assertFalse(output, output.contains("Slow(n - 1)") || output.contains("SlowDown") || output.contains("NotSlow"));
        output = tool.execute(named("mode", "callers", "methodFilter", "Slow()", "moduleFilter", "caller"));
        assertTrue(output, output.contains("Total over 1 call line(s)") && output.contains("Result = Slow(1)"));
        output = tool.execute(named("mode", "callers", "methodFilter", provesti + "(Cancel) Export"));
        assertTrue(output, output.contains("Total over 1 call line(s)") && output.contains("| Doc | Post() | 7 |"));
        error(tool.execute(named("mode", "callers")), "methodFilter is required for callers");
    }
    @Test public void sourceTruncationIsExplicitAndServerCallsAreSelected()
    {
        ILineProfilingResult server = first.getProfilingResults().get(0);
        when(server.getServerCallSignal()).thenReturn(ServerCallSignal.INTERNAL_SERVER_CALL);
        when(server.getLine()).thenReturn("x".repeat(140));
        String result = tool.execute(named());
        assertTrue(result, result.contains("source shortened; export for full text"));
        String section = result.substring(result.indexOf("## Top lines with server calls"), result.indexOf("## Top methods"));
        assertTrue(section, section.contains("INTERNAL_SERVER_CALL") && section.contains("showing 1 of 1"));
    }
    @Test public void linesPageByPureTime()
    {
        String output = tool.execute(named("mode", "lines", "limit", "1", "offset", "1", "responseFormat", "detailed"));
        assertTrue(output, output.contains("second") && !output.contains("a \\| b"));
        assertTrue(output, output.contains("Showing 2-2 of 3") && output.contains("offset=2"));
    }
    @Test public void noPerModule200LineCap()
    {
        List<ILineProfilingResult> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 201; i++) { many.add(line("Module", "Slow()", i, 1, i * 1000000L, i * 1000000L, "line-" + i)); }
        when(first.getProfilingResults()).thenReturn(many);
        String output = tool.execute(named("mode", "lines", "limit", "1", "responseFormat", "detailed"));
        assertTrue(output, output.contains("line-201") && output.contains("Showing 1-1 of 201"));
    }
    @Test public void conciseDropsMethodAndCodeColumns()
    {
        String output = tool.execute(named("mode", "lines"));
        assertTrue(output, output.contains("Pure ms"));
        assertFalse(output, output.contains("Slow()") || output.contains("a \\| b"));
    }
    @Test public void filtersAndPageBeyondEnd()
    {
        String output = tool.execute(named("mode", "lines", "moduleFilter", "MODULE", "methodFilter", "slow", "minFrequency", "5"));
        assertTrue(output, output.contains("Showing 1-1 of 1"));
        output = tool.execute(named("mode", "lines", "offset", "2147483647"));
        assertTrue(output, output.contains("Showing 0 of 3") && !output.contains("next page"));
        assertFalse(output, output.contains("2147483648"));
    }
    @Test public void listIsNewestFirstAndPaged()
    {
        String output = tool.execute(Map.of("mode", "list", "limit", "1"));
        assertTrue(output, output.contains("After") && !output.contains("Before"));
        assertTrue(output, output.contains("Showing 1-1 of 2") && output.contains("offset=1"));
        assertTrue(tool.execute(Map.of("mode", "list", "offset", "1")).contains("Before"));
    }
    @Test public void listShowsEmptyMeasurementLineCountsAndEscapesCells()
    {
        when(second.getProfilingResults()).thenReturn(List.of());
        when(second.getName()).thenReturn("Empty | capture\nno code");
        String output = tool.execute(Map.of("mode", "list"));
        assertTrue(output, output.contains("| Line records |"));
        assertTrue(output, output.contains("Empty \\| capture no code"));
        String emptyRow = output.lines().filter(s -> s.contains(second.getUuid().toString())).findFirst().orElseThrow();
        String measuredRow = output.lines().filter(s -> s.contains(first.getUuid().toString())).findFirst().orElseThrow();
        assertTrue(emptyRow, emptyRow.endsWith("| 0 |"));
        assertTrue(measuredRow, measuredRow.endsWith("| 3 |"));
    }
    @Test public void unresolvedRowsExplainNaBelowTablesInEveryReadMode()
    {
        assertFalse(tool.execute(named()).contains("\n\nn/a"));
        when(first.getProfilingResults().get(0).getModuleName()).thenReturn("<module not found>");
        for (String mode : List.of("summary", "lines"))
        {
            String output = tool.execute(named("mode", mode, "responseFormat", "detailed"));
            assertTrue(output, output.contains("\n\nn/a"));
            assertTrue(output, output.contains("platform modules or other projects"));
            assertTrue(output, output.contains("not a defect of the measurement"));
        }
        ICompareResult result = mock(ICompareResult.class);
        IMethodCompareResult unresolved = method("<method not found>", 1_000_000, 2_000_000);
        when(unresolved.getModuleName()).thenReturn("<module not found>");
        when(result.getMethodsCompareResults()).thenReturn(List.of(unresolved));
        when(compare.compare(first, second)).thenReturn(result);
        when(first.getProfilingResults().get(0).getModuleName()).thenReturn("Module");
        String output = tool.execute(Map.of("mode", "compare", "baseId", first.getUuid().toString()));
        assertTrue(output, output.contains("\n\nn/a"));
        assertTrue(output, output.contains("platform modules or other projects"));
        assertTrue(output, output.contains("not a defect of the measurement"));
    }
    @Test public void emptyStoreIsActionableAndNamedMissingIsError()
    {
        when(service.getResults()).thenReturn(List.of());
        assertTrue(tool.execute(Map.of()).contains("No profiling results available"));
        error(tool.execute(named()), first.getUuid().toString(), "mode=list");
    }
    @Test public void activeFlagStillUsesRequestedApplication()
    {
        StartProfilingTool.markActive("app");
        assertTrue(tool.execute(Map.of()).contains("profilingActive: true"));
        assertTrue(tool.execute(Map.of("applicationId", "unknown")).contains("profilingActive: false"));
    }
    @Test public void invalidEnumsFailBeforeServiceAccess()
    {
        for (String param : List.of("mode", "responseFormat"))
        {
            error(tool.execute(Map.of(param, "garbage")), param, "garbage", "Use one of");
        }
        verifyZeroInteractions(service);
    }
    @Test public void invalidNumbersAreNeverCoerced()
    {
        for (String param : List.of("top", "limit", "offset", "minFrequency"))
        {
            for (String invalid : List.of("abc", "1.5", "-1", "2147483648", "", "NaN"))
            {
                error(tool.execute(Map.of(param, invalid)), param, "integer");
            }
        }
        error(tool.execute(Map.of("limit", "101")), "limit", "1..100");
        error(tool.execute(Map.of("top", "21")), "top", "1..20");
        error(tool.execute(Map.of("top", "0")), "top");
        verifyZeroInteractions(service);
    }
    @Test public void integralTransportNumberAccepted()
    {
        assertTrue(tool.execute(named("minFrequency", "5.0", "limit", "1.0", "top", "1.0")).contains("showing 1 of 2"));
    }
    @Test public void compareRequiresBase()
    {
        error(tool.execute(Map.of("mode", "compare")), "baseId", "mode=list");
        verifyZeroInteractions(compare);
    }
    @Test public void compareUsesEdtAndRanksAbsoluteOwnTime()
    {
        ICompareResult result = mock(ICompareResult.class);
        IMethodCompareResult big = method("Big", 10_000_000, 15_000_000);
        IMethodCompareResult highPercent = method("Tiny", 1, 1_000_001);
        IMethodCompareResult faster = method("Faster", 9_000_000, 1_000_000);
        when(result.getMethodsCompareResults()).thenReturn(List.of(highPercent, faster, big));
        when(compare.compare(first, second)).thenReturn(result);
        String output = tool.execute(Map.of("mode", "compare", "baseId", first.getUuid().toString(), "top", "1"));
        assertTrue(output, output.contains("Big") && !output.contains("Tiny"));
        assertTrue(output, output.contains("Faster") && output.contains("-8.000 ms"));
        assertTrue(output, output.contains("Base \u2192 compared") && output.contains("Client") && output.contains("number and trimmed text"));
        verify(compare).compare(first, second);
    }
    private static IMethodCompareResult method(String name, long base, long compared)
    {
        IMethodCompareResult method = mock(IMethodCompareResult.class);
        when(method.getMethodName()).thenReturn(name);
        when(method.getModuleName()).thenReturn("Module");
        when(method.getPureTime(CompareSide.BASE)).thenReturn(base);
        when(method.getPureTime(CompareSide.COMPARED)).thenReturn(compared);
        return method;
    }
    @Test public void unmatchedNeverReadsMissingSideAndLinesHaveBothCalls()
    {
        ICompareResult result = mock(ICompareResult.class);
        IMethodCompareResult unmatched = mock(IMethodCompareResult.class, withSettings().extraInterfaces(IUnmatchedCompareResult.class));
        when(unmatched.getPureTime(any())).thenThrow(new UnsupportedOperationException("missing side"));
        ILineCompareResult missingLine = mock(ILineCompareResult.class, withSettings().extraInterfaces(IUnmatchedCompareResult.class));
        when(missingLine.getFrequency(any())).thenThrow(new UnsupportedOperationException("missing side"));
        when(missingLine.getModuleName()).thenReturn("Module");
        when(unmatched.getLinesCompareResults()).thenReturn(List.of(missingLine));
        IMethodCompareResult matched = method("Slow", 1000000, 2000000);
        ILineCompareResult line = mock(ILineCompareResult.class);
        when(line.getModuleName()).thenReturn("Module");
        when(line.getFrequency(CompareSide.BASE)).thenReturn(7L);
        when(line.getFrequency(CompareSide.COMPARED)).thenReturn(9L);
        when(line.getPureTime(CompareSide.BASE)).thenReturn(1000000L);
        when(line.getPureTime(CompareSide.COMPARED)).thenReturn(2000000L);
        when(matched.getLinesCompareResults()).thenReturn(List.of(line));
        when(result.getMethodsCompareResults()).thenReturn(List.of(unmatched, matched));
        when(compare.compare(first, second)).thenReturn(result);
        String output = tool.execute(Map.of("mode", "compare", "baseId", first.getUuid().toString(), "moduleFilter", "Module"));
        assertTrue(output, output.contains("methods=1, lines=1") && output.contains("| 7 | 9 | 1.000 | 2.000 |"));
        verify(unmatched, never()).getPureTime(any());
        verify(missingLine, never()).getFrequency(any());
    }
    @Test public void exchangeModesAreRejectedBeforeServiceAccess()
    {
        for (String mode : List.of("export", "import"))
        {
            error(tool.execute(Map.of("mode", mode)), mode, "Use one of");
        }
        var schema = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject().getAsJsonObject("properties");
        for (String key : List.of("exportPath", "exportFormat", "importPath")) { assertFalse(schema.has(key)); }
        verifyZeroInteractions(service);
    }

    @Test public void everyMarkdownTableEndsWithABlankLine()
    {
        ICompareResult compared = mock(ICompareResult.class);
        when(compare.compare(first, second)).thenReturn(compared);
        for (Map<String, String> params : List.of(named(), named("mode", "lines"), named("mode", "list"),
            named("mode", "callers", "methodFilter", "Slow"),
            Map.of("mode", "compare", "baseId", first.getUuid().toString(), "moduleFilter", "Module")))
        {
            String output = tool.execute(params);
            assertTrue(output, output.contains("profilingActive: false."));
            String[] lines = output.split("\n", -1);
            int tables = 0;
            for (int i = 0; i + 1 < lines.length; i++)
            {
                if (lines[i].startsWith("|") && !lines[i + 1].startsWith("|"))
                {
                    tables++;
                    assertEquals("Blank line required after table in:\n" + output, "", lines[i + 1]);
                }
            }
            assertTrue("Must inspect at least one real table", tables > 0);
        }
    }
}
