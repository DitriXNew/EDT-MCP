/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest.line;
import static com.ditrix.edt.mcp.server.tools.impl.GetProfilingResultsToolTest.measurement;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Before;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com._1c.g5.v8.dt.profiling.core.*;
import com.ditrix.edt.mcp.server.tools.IMcpTool.ResponseType;
import com.google.gson.JsonParser;
import com.ditrix.edt.mcp.server.utils.ProfilingSessionRegistry;

/** Drives real execute/render/file branches through typed EDT service mocks. */
public class ManageProfilingResultsToolTest
{
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private IProfilingService service;
    private IResultsStore store;
    private IProfilingResult first;
    private IProfilingResult second;
    private TestTool tool;
    private boolean readOnly;
    private boolean denied;
    private int consents;

    private class TestTool extends ManageProfilingResultsTool
    {
        @Override protected IProfilingService profilingService() { return service; }
        @Override protected IResultsStore resultsStore() { return store; }
        @Override protected boolean isReadOnlyPreset() { return readOnly; }
        @Override protected String consent(String title, String path, int count, List<String> names)
        {
            consents++;
            return denied ? com.ditrix.edt.mcp.server.protocol.ToolResult.error("Operation declined by user").toJson() : null;
        }
    }

    @Before public void setup()
    {
        ProfilingSessionRegistry.clearStateForTests();
        service = mock(IProfilingService.class);
        store = mock(IResultsStore.class);
        first = measurement("Before", 1, List.of(line("Module", "Slow()", 1, 2, 8_000_000, 4_000_000, "a | b"),
            line("Module", "Slow()", 2, 10, 6_000_000, 2_000_000, "second"),
            line("Other", "Fast()", 3, 20, 2_000_000, 1_000_000, "third")));
        second = measurement("After", 2, List.of(line("Module", "Slow()", 1, 1, 1_000_000, 1_000_000, "after")));
        when(service.getResults()).thenReturn(List.of(first, second));
        tool = new TestTool();
    }
    @After public void clear() { ProfilingSessionRegistry.clearStateForTests(); }

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

    @Test public void writeContractAndValidation()
    {
        assertEquals("manage_profiling_results", tool.getName());
        assertTrue(tool.getDescription().contains("get_tool_guide('manage_profiling_results')"));
        assertEquals(ResponseType.MARKDOWN, tool.getResponseType());
        assertNull(tool.getOutputSchema());
        assertFalse(tool.getAnnotations().getReadOnlyHint());
        assertFalse(tool.getAnnotations().getDestructiveHint());
        assertFalse(tool.getAnnotations().getIdempotentHint());
        for (String key : List.of("mode", "exportFormat"))
        {
            error(tool.execute(Map.of(key, "garbage")), key, "garbage", "Use one of");
        }
        for (String mode : List.of("list", "summary", "lines", "compare"))
        {
            error(tool.execute(Map.of("mode", mode)), mode, "Use one of");
        }
        error(tool.execute(Map.of()), "exportPath", "absolute");
        error(tool.execute(Map.of("mode", "import")), "importPath", "absolute");
        verifyZeroInteractions(service, store);
    }
    @Test public void csvIsFullBomQuotedAndNeverOverwritten() throws Exception
    {
        Path path = temp.getRoot().toPath().resolve("result.csv");
        when(first.getProfilingResults().get(0).getLine()).thenReturn("a,\"b\"\r\nc");
        String output = tool.execute(named("mode", "export", "exportPath", path.toString()));
        assertTrue(output, output.contains("3 line records"));
        byte[] bytes = Files.readAllBytes(path);
        assertArrayEquals(new byte[] {(byte)0xef, (byte)0xbb, (byte)0xbf}, java.util.Arrays.copyOf(bytes, 3));
        String csv = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv, csv.contains("\"a,\"\"b\"\"\r\nc\""));
        assertTrue(csv, csv.contains("\"8.000\",\"4.000\""));
        error(tool.execute(named("mode", "export", "exportPath", path.toString())), "already exists", "Choose");
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }
    @Test public void exportRefusalsBeforeConsent() throws Exception
    {
        error(tool.execute(named("mode", "export", "exportPath", "relative.csv")), "relative.csv", "absolute");
        Path missing = temp.getRoot().toPath().resolve("missing/out.csv");
        error(tool.execute(named("mode", "export", "exportPath", missing.toString())), "Parent", "Create");
        Path parent = temp.newFolder().toPath();
        Files.createDirectory(parent.resolve(first.getName()));
        error(tool.execute(named("mode", "export", "exportFormat", "edt", "exportPath", parent.toString())), "already exists", "another");
        assertEquals(0, consents);
    }
    @Test public void exportPathErrorsOnlyGivePathRecoveryAdvice() throws Exception
    {
        Path occupied = temp.newFile("occupied.csv").toPath();
        for (Path path : List.of(Path.of("relative.csv"), temp.getRoot().toPath().resolve("missing/out.csv"), occupied))
        {
            String output = tool.execute(named("exportPath", path.toString()));
            String message = JsonParser.parseString(output).getAsJsonObject().get("error").getAsString();
            error(output, path.getFileName().toString());
            assertTrue(message, message.contains(path.toString()));
            assertFalse(message, message.contains("mode=list") || message.contains("verify IDs"));
        }
        String missing = tool.execute(named("measurementId", UUID.randomUUID().toString(),
            "exportPath", temp.getRoot().toPath().resolve("new.csv").toString()));
        error(missing, "not found", "mode=list");
        assertEquals(0, consents);
    }
    @Test public void edtCreatesDirectoryAndVerifiesFiles() throws Exception
    {
        Path parent = temp.getRoot().toPath().resolve("native");
        doAnswer(invocation -> {
            Path dir = invocation.getArgument(1);
            Files.writeString(dir.resolve(first.getName()).resolve("results_info.xml"), "xml");
            Files.writeString(dir.resolve(first.getName()).resolve("modules_info.properties"), "properties");
            return null;
        }).when(store).saveResult(any(IProfilingResult.class), any());
        String output = tool.execute(named("mode", "export", "exportFormat", "edt", "exportPath", parent.toString()));
        assertTrue(output, output.contains("Exported complete"));
        assertTrue(Files.isRegularFile(parent.resolve("Before/results_info.xml")));
    }
    @Test public void edtSilentWriteFailureIsNotSuccess() throws Exception
    {
        Path parent = temp.newFolder().toPath();
        String output = tool.execute(named("mode", "export", "exportFormat", "edt", "exportPath", parent.toString()));
        error(output, "incomplete", "EDT log", "writable");
        assertFalse(output, output.contains("mode=list"));
        assertTrue(JsonParser.parseString(output).getAsJsonObject().get("error").getAsString().contains(parent.toString()));
    }
    @Test public void nativeExportPinsTheReservedNameAcrossConcurrentRename() throws Exception
    {
        Path parent = temp.newFolder().toPath();
        Path occupied = Files.createDirectory(parent.resolve("Occupied"));
        Files.writeString(occupied.resolve("results_info.xml"), "do not overwrite");
        doAnswer(call -> {
            IProfilingResult export = call.getArgument(0);
            when(first.getName()).thenReturn("Occupied");
            assertEquals("Before", export.getName());
            Files.writeString(parent.resolve(export.getName()).resolve("results_info.xml"), "xml");
            Files.writeString(parent.resolve(export.getName()).resolve("modules_info.properties"), "properties");
            return null;
        }).when(store).saveResult(any(IProfilingResult.class), eq(parent));
        String output = tool.execute(named("mode", "export", "exportFormat", "edt", "exportPath", parent.toString()));
        assertTrue(output, output.contains("Exported complete"));
        assertEquals("do not overwrite", Files.readString(occupied.resolve("results_info.xml")));
        assertEquals("xml", Files.readString(parent.resolve("Before/results_info.xml")));
    }
    @Test public void readOnlyRefusesBothModesBeforeServiceAccess() throws Exception
    {
        readOnly = true;
        error(tool.execute(named("mode", "export", "exportPath", temp.getRoot().toPath().resolve("out.csv").toString())), "read-only preset");
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "read-only preset");
        assertEquals(0, consents);
        verifyZeroInteractions(service, store);
    }
    @Test public void deniedConsentDoesNotWriteOrImport() throws Exception
    {
        denied = true;
        Path output = temp.getRoot().toPath().resolve("out.csv");
        error(tool.execute(named("mode", "export", "exportPath", output.toString())), "declined");
        assertFalse(Files.exists(output));
        IProfilingResult imported = measurement("New", 3, List.of());
        when(store.loadResults(any())).thenReturn(List.of(imported));
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "declined");
        verify(service, never()).importResults(any());
    }
    @Test public void importAddsAndSkipsDuplicatesWithReadback() throws Exception
    {
        IProfilingResult added = measurement("New", 3, List.of());
        when(store.loadResults(any())).thenReturn(List.of(first, added));
        doAnswer(inv -> { when(service.getResults()).thenReturn(List.of(first, second, added)); return null; })
            .when(service).importResults(List.of(added));
        String output = tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString()));
        assertTrue(output, output.contains("Added: 1; skipped (UUID already loaded): 1"));
        assertTrue(output, output.contains(added.getUuid().toString()) && output.contains(first.getUuid().toString()));
        assertEquals(1, consents);
        output = tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString()));
        assertTrue(output, output.contains("Added: 0; skipped (UUID already loaded): 2"));
        assertEquals(1, consents);
        verify(service, times(1)).importResults(any());
    }
    @Test public void importRefusesBadPathsEmptyAndConflictingName() throws Exception
    {
        error(tool.execute(Map.of("mode", "import", "importPath", "relative")), "relative", "absolute");
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toPath().resolve("absent").toString())), "not a directory", "Supply");
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "no readable", "Supply");
        IProfilingResult conflict = measurement("before", 3, List.of());
        when(conflict.getUuid()).thenReturn(UUID.randomUUID());
        when(store.loadResults(any())).thenReturn(List.of(conflict));
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "conflicts", "Rename");
        verify(service, never()).importResults(any());
    }

    @Test public void importListenerFailureAfterSaveStillReportsAddedUuids() throws Exception
    {
        IProfilingResult added = measurement("New", 3, List.of());
        when(store.loadResults(any())).thenReturn(List.of(added));
        doAnswer(inv -> {
            when(service.getResults()).thenReturn(List.of(first, second, added));
            throw new IllegalStateException("Workbench is unavailable");
        }).when(service).importResults(List.of(added));
        String output = tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString()));
        assertTrue(output, output.contains("Added: 1") && output.contains(added.getUuid().toString()));
        assertTrue(output, output.contains("profiling view may need a refresh"));
        output = tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString()));
        assertTrue(output, output.contains("Added: 0; skipped (UUID already loaded): 1"));
        verify(service, times(1)).importResults(any());
    }

    @Test public void importListenerFailureWithoutAllUuidsIsAnError() throws Exception
    {
        IProfilingResult added = measurement("New", 3, List.of());
        IProfilingResult absent = measurement("Absent", 4, List.of());
        when(store.loadResults(any())).thenReturn(List.of(added, absent));
        doAnswer(inv -> {
            when(service.getResults()).thenReturn(List.of(first, second, added));
            throw new IllegalStateException("Import aborted");
        }).when(service).importResults(List.of(added, absent));
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "not observed", "mode=list", "Import aborted");
    }

    @Test public void concurrentImportDuringConsentIsStillARefusal() throws Exception
    {
        IProfilingResult added = measurement("New", 3, List.of());
        when(store.loadResults(any())).thenReturn(List.of(added));
        tool = new TestTool()
        {
            @Override protected String consent(String title, String path, int count, List<String> names)
            {
                when(service.getResults()).thenReturn(List.of(first, second, added));
                return null;
            }
        };
        error(tool.execute(Map.of("mode", "import", "importPath", temp.getRoot().toString())), "appeared while awaiting consent", "Retry");
        verify(service, never()).importResults(any());
    }
    @Test public void nativeExportRefusesTraversalName() throws Exception
    {
        when(first.getName()).thenReturn("../escape");
        error(tool.execute(named("mode", "export", "exportFormat", "edt", "exportPath", temp.getRoot().toString())), "Invalid measurement name", "no path separators");
        verifyZeroInteractions(store);
    }
}
