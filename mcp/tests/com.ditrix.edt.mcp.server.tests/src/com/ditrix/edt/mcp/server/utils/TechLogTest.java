// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogAnalysis;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogKnowledge;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogCapture;
import com.google.gson.JsonObject;

public class TechLogTest
{
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    public static Path root()
    {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (; p != null; p = p.getParent())
            if (Files.isDirectory(p.resolve("tests/fixtures/tech-log"))) return p;
        throw new AssertionError("Cannot find tech-log fixture root");
    }

    private Path file(String text) throws IOException
    { Path p = temporary.newFile().toPath(); Files.writeString(p, text, StandardCharsets.UTF_8); return p; }

    private static String event(String name, String duration)
    { return "{\"ts\":\"2026-11-15T23:43:10.000000\",\"duration\":\"" + duration + "\",\"name\":\"" + name + "\"}"; }

    @Test public void realSampleBomCaseStringsAndEscapedMultiline() throws Exception
    {
        var index = TechLogReader.read(root().resolve("tests/fixtures/tech-log/real-8.5.1-createinfobase.log"), 1, 10, null, null);
        assertEquals(0, index.rejected); assertEquals(0, index.incompleteTail); assertTrue(index.events.size() > 10);
        var lic = TechLogReader.reread(index.events.get(0).pointer());
        assertTrue(lic.containsKey("TXT")); assertEquals("1", lic.get("DURATION"));
        var hasp = TechLogReader.reread(index.events.get(1).pointer()); assertTrue(hasp.get("txt").contains("\n"));
    }
    @Test public void headerUsesIbThenProcessNamesThenExplicitMissingIdentity() throws Exception
    {
        String base = event("EXCP", "1");
        var known = TechLogReader.read(file(base.replace("}", ",\"IB\":\"D:/ib|one\",\"p:processName\":\"other\"}") + "\n"), 1, 10, null, null);
        assertTrue(TechLogAnalysis.header(known), TechLogAnalysis.header(known).contains("infobases: " + MarkdownUtils.escapeMarkdown("[D:/ib|one]")));
        var server = TechLogReader.read(file(base.replace("}", ",\"p:processName\":\"ServerBase\"}") + "\n"
            + base.replace("}", ",\"P:PROCESSNAME\":\"SecondBase\"}") + "\n"
            + base.replace("}", ",\"p:processName\":\"ServerBase\"}") + "\n"), 1, 10, null, null);
        assertTrue(TechLogAnalysis.header(server), TechLogAnalysis.header(server).contains("p:processName: " + MarkdownUtils.escapeMarkdown("[SecondBase, ServerBase]")));
        var missing = TechLogReader.read(root().resolve("tests/fixtures/tech-log/real-8.5.1-createinfobase.log"), 1, 10, null, null);
        String header = TechLogAnalysis.header(missing);
        assertTrue(header, header.contains("not recorded (file infobases do not write IB)"));
        assertFalse(header, header.contains("infobases: []"));
    }
    @Test public void coverageRendersManifestInstantsLocallyWithOffsetWithoutChangingManifest() throws Exception
    {
        var index = TechLogReader.read(file(event("EXCP", "1") + "\n"), 1, 10, null, null);
        index.manifest = com.google.gson.JsonParser.parseString("{\"profile\":\"errors\",\"events\":[\"EXCP\"],"
            + "\"startTime\":\"2026-11-16T00:51:03Z\",\"stopTime\":\"2026-11-16T01:51:03Z\"}").getAsJsonObject();
        String original = index.manifest.toString();
        java.util.TimeZone previous = java.util.TimeZone.getDefault();
        try
        {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Kyiv"));
            String coverage = TechLogAnalysis.coverage(index);
            assertTrue(coverage, coverage.contains("startTime=2026-11-16 02:51:03 +02:00"));
            assertTrue(coverage, coverage.contains("stopTime=2026-11-16 03:51:03 +02:00"));
            assertTrue(TechLogAnalysis.header(index).contains("Event times are local to the machine that wrote them"));
            assertEquals(original, index.manifest.toString());
        }
        finally { java.util.TimeZone.setDefault(previous); }
    }
    @Test public void problemsOmitSubMillisecondSlowGroupsButKeepErrorsAndFullDetails() throws Exception
    {
        var index = TechLogReader.read(file(event("CALL", "999").replace("}", ",\"Context\":\"tiny-call\"}") + "\n"
            + event("CALL", "999").replace("}", ",\"Context\":\"tiny-call\"}") + "\n"
            + event("DBV8DBENG", "1").replace("}", ",\"Sql\":\"select tiny from data\"}") + "\n"
            + event("CALL", "1000").replace("}", ",\"Context\":\"boundary-call\"}") + "\n"
            + event("EXCP", "0") + "\n" + event("QERR", "1") + "\n"
            + event("TTIMEOUT", "0") + "\n" + event("TDEADLOCK", "0") + "\n"), 1, 10, null, null);
        String summary = TechLogAnalysis.summary(index, 100, 0);
        assertTrue(summary, summary.contains("2 slow groups under 1 ms omitted"));
        assertFalse(summary, summary.contains("tiny-call") || summary.contains("select tiny"));
        for (String retained : List.of("boundary-call", "exceptions", "query-errors", "lock-timeouts", "managed-deadlocks"))
            assertTrue(summary, summary.contains(retained));
        var tiny = TechLogAnalysis.ranked(index).stream().filter(g -> g.key.equals("tiny-call")).findFirst().orElseThrow();
        assertEquals(2, TechLogAnalysis.details(index, tiny.id).size());
        assertTrue(TechLogAnalysis.summary(index, 1, 0).contains("of 5"));
    }

    @Test public void corruptLineAndIncompleteTailAreSeparate() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "782006") + "\nnot-json\n{\"ts\":"), 1, 10, null, null);
        assertEquals(1, i.events.size()); assertEquals(1, i.rejected); assertEquals(1, i.incompleteTail);
        assertTrue(i.errors.get(0).contains("sample=not-json"));
        assertEquals("782.006", TechLogAnalysis.ms(i.events.get(0).durationUs()));
    }

    @Test public void completeLastLineWithoutNewlineIsAccepted() throws Exception
    { assertEquals(1, TechLogReader.read(file(event("CALL", "1")), 1, 10, null, null).events.size()); }

    @Test public void zipDiscoveryRefusesAboveMemberCapWithoutEnumeratingPastIt() throws Exception
    {
        Path zip = temporary.newFile("too-many.log.zip").toPath();
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for (int n = 0; n < 50_002; n++)
            { out.putNextEntry(new ZipEntry(n + ".log")); out.closeEntry(); }
        }
        try { TechLogReader.sources(zip); fail("Oversized archive must be refused"); }
        catch (IOException e) { assertTrue(e.getMessage().contains("discovery budget")); }

        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var entries = new java.util.Enumeration<ZipEntry>()
        {
            @Override public boolean hasMoreElements() { return consumed.get() < 50_002; }
            @Override public ZipEntry nextElement() { return new ZipEntry(consumed.getAndIncrement() + ".log"); }
        };
        var hook = TechLogReader.class.getDeclaredMethod("zipSources", Path.class, Path.class, long.class,
            long.class, long.class, java.util.Enumeration.class, List.class);
        hook.setAccessible(true);
        try { hook.invoke(null, zip, zip, 0L, 0L, Long.MAX_VALUE, entries, new ArrayList<>()); fail(); }
        catch (java.lang.reflect.InvocationTargetException e) { assertTrue(e.getCause() instanceof IOException); }
        assertEquals("Discovery must stop before fetching another member", 50_000, consumed.get());

        consumed.set(0);
        try { hook.invoke(null, zip, zip, 0L, 0L, System.nanoTime() - 1, entries, new ArrayList<>()); fail(); }
        catch (java.lang.reflect.InvocationTargetException e) { assertTrue(e.getCause() instanceof IOException); }
        assertEquals("Expired discovery must not fetch a member", 0, consumed.get());
    }

    @Test public void zipIgnoresManifestAndOtherNonLogMembers() throws Exception
    {
        Path zip = temporary.newFile("filtered.log.zip").toPath();
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for (String name : List.of("node/events.LOG.1", "edt-mcp-capture.json", "notes.txt"))
            {
                out.putNextEntry(new ZipEntry(name));
                out.write((name.endsWith(".1") ? event("CALL", "1") + "\n" : "{\"profile\":\"errors\"}\n")
                    .getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        var index = TechLogReader.read(zip, 1, 10, null, null);
        assertEquals(1, index.events.size()); assertEquals(0, index.rejected);
        assertEquals(1, index.sources.size());
    }

    @Test public void sqlNormalizationPreservesBinaryPlusAndMinus()
    {
        assertEquals("select amount -?", TechLogAnalysis.normalizeSql("SELECT amount -1"));
        assertEquals("select amount +?", TechLogAnalysis.normalizeSql("SELECT amount +1"));
        assertNotEquals(TechLogAnalysis.normalizeSql("SELECT amount -1"), TechLogAnalysis.normalizeSql("SELECT amount +1"));
        assertEquals(TechLogAnalysis.normalizeSql("x = 1"), TechLogAnalysis.normalizeSql("x = 2"));
    }

    @Test public void coverageDoesNotClaimAbsenceWithUnfinishedTailOrRejectedRecords() throws Exception
    {
        Files.writeString(temporary.getRoot().toPath().resolve("edt-mcp-capture.json"),
            "{\"profile\":\"errors\",\"events\":[\"CALL\",\"EXCP\"]}");
        for (String tail : List.of("{\"name\":\"EXCP\",\"ts\":", "not-json\n"))
        {
            var index = TechLogReader.read(file(event("CALL", "1") + "\n" + tail), 1, 10, null, null);
            assertEquals(1, index.events.size());
            String coverage = TechLogAnalysis.coverage(index);
            assertFalse(coverage, coverage.contains("none occurred"));
            assertTrue(coverage, coverage.contains(tail.endsWith("\n") ? "rejected records" : "unfinished tail"));
            assertTrue(coverage, coverage.contains("EXCP"));
        }
    }

    @Test public void semanticErrorOnFinalCompleteJsonIsCorruptNotIncomplete() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "-1")), 1, 10, null, null);
        assertEquals(1, i.rejected); assertEquals(0, i.incompleteTail);
    }

    @Test public void numericValuesAndDuplicateCaseInsensitiveNamesAreRejected() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "1").replace("\"duration\":\"1\"", "\"duration\":1") + "\n"
            + event("CALL", "1").replace("}", ",\"NAME\":\"EXCP\"}") + "\n"), 1, 10, null, null);
        assertEquals(2, i.rejected); assertTrue(i.events.isEmpty());
    }

    @Test public void negativeAndOverflowDurationRejectedButNegativeMemoryAllowed() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "-1") + "\n" + event("CALL", "9999999999999999999")
            + "\n" + event("CALL", "1").replace("}", ",\"Memory\":\"-42\"}") + "\n"), 1, 10, null, null);
        assertEquals(2, i.rejected); assertEquals(1, i.events.size());
    }

    @Test public void textFormatRefusesWithJsonConfigurationFix() throws Exception
    {
        try { TechLogReader.read(file("\ufeff59:44.456200-2840,CALL,5,process=rphost\n"), 1, 10, null, null); fail(); }
        catch (TechLogReader.TextFormatException e)
        { assertTrue(e.getMessage().contains("format=\"json\"")); assertTrue(e.getMessage().contains("manage_tech_log")); }
    }

    @Test public void modernIsoTextFormatAlsoRefuses() throws Exception
    {
        try { TechLogReader.read(file("2023-08-01T15:01:45.259000-14998,SCALL,0,level=INFO\n"), 1, 10, null, null); fail(); }
        catch (TechLogReader.TextFormatException e) { assertTrue(e.getMessage().contains("format=\"json\"")); }
    }

    @Test public void zipPointerSessionReusesPrefixAndEnforcesExpandedByteAndTimeBudgets() throws Exception
    {
        Path zip = temporary.newFile("prefix.log.zip").toPath();
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            out.putNextEntry(new ZipEntry("events.log"));
            out.write(((event("EXCP", "1") + "\n").repeat(20000)).getBytes(StandardCharsets.UTF_8)); out.closeEntry();
        }
        var i = TechLogReader.read(zip, 4, 10, null, null);
        try (var reads = new TechLogReader.ReadSession(1, 10))
        {
            try { reads.read(i.events.get(i.events.size() - 1).pointer()); fail(); }
            catch (IOException e) { assertTrue(e.getMessage().contains("zip prefixes included")); assertTrue(e.getMessage().contains("readBudgetMb")); }
        }
        try (var reads = new TechLogReader.ReadSession(4, 10))
        {
            for (var event : i.events) assertEquals("EXCP", reads.read(event.pointer()).get("name"));
        }
        try (var reads = new TechLogReader.ReadSession(4, 0))
        {
            try { reads.read(i.events.get(0).pointer()); fail(); }
            catch (IOException e) { assertTrue(e.getMessage().contains("No complete result")); }
        }
    }

    @Test public void unsupportedForeignEncodingRefusesBeforeWriting() throws Exception
    {
        byte[] original = "<?xml version=\"1.0\" encoding=\"windows-1251\"?><config xmlns=\"http://v8.1c.ru/v8/tech-log\"/>"
            .getBytes(java.nio.charset.Charset.forName("windows-1251"));
        try { TechLogCapture.insert(original, TechLogCapture.xml(options(temporary.getRoot().toPath()), "test")); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("UTF-8")); }
    }

    @Test public void zipIsReadInPlaceAndPointersReadIndividualMembers() throws Exception
    {
        Path zip = temporary.newFile("archive.log.zip").toPath();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for (String name : List.of("one.log", "two.log"))
            { output.putNextEntry(new ZipEntry(name)); output.write(("\ufeff" + event("EXCP", "1") + "\n").getBytes(StandardCharsets.UTF_8)); output.closeEntry(); }
        }
        var i = TechLogReader.read(zip, 1, 10, null, null);
        assertEquals(2, i.events.size()); assertEquals(1, i.files);
        assertNotEquals(i.events.get(0).id(), i.events.get(1).id());
        assertEquals("EXCP", TechLogReader.reread(i.events.get(1).pointer()).get("name"));
        assertEquals(1, zip.getParent().toFile().list().length);
    }

    @Test public void readBudgetReportsExplicitPartialProgress() throws Exception
    {
        var i = TechLogReader.read(file((event("CALL", "1") + "\n").repeat(20000)), 1, 10, null, null);
        assertTrue(i.limited); assertTrue(i.bytesRead < i.totalBytes);
        String out = TechLogAnalysis.header(i); assertTrue(out.contains("INCOMPLETE")); assertTrue(out.contains("of")); assertTrue(out.contains("subdirectory"));
    }

    @Test public void sizeRotationsAreDiscoveredAndProducerIdentitySpansFiles() throws Exception
    {
        Path directory = temporary.newFolder("rotations").toPath();
        Files.writeString(directory.resolve("rphost_42.log.1"), event("CALL", "1") + "\n");
        Path zip = directory.resolve("rphost_42.log.2.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip)))
        { out.putNextEntry(new ZipEntry("rphost_42.log.2")); out.write((event("EXCP", "1") + "\n").getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
        var i = TechLogReader.read(directory, 1, 10, null, null);
        assertEquals(2, i.events.size()); assertEquals("42", i.events.get(0).pid());
        assertEquals(i.events.get(0).scope(), i.events.get(1).scope());
    }

    @Test public void elapsedTimeBudgetIsExplicitEvenBeforeFirstEvent() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "1") + "\n"), 1, 0, null, null);
        assertTrue(i.limited); assertEquals(0, i.events.size()); assertTrue(TechLogAnalysis.header(i).contains("Read 0.000 of"));
    }

    @Test public void directoryDiscoveryIsBudgetedAndNeverInventsTotalBytes() throws Exception
    {
        Path directory = temporary.newFolder("inventory").toPath();
        Files.writeString(directory.resolve("one.log"), event("CALL", "1") + "\n");
        try { TechLogReader.read(directory, 1, 0, null, null); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("total MB is not yet known")); assertTrue(e.getMessage().contains("file/subdirectory")); }
    }

    @Test public void timeFilterRetainsIntersectingParents() throws Exception
    {
        var i = TechLogReader.read(file(event("CALL", "5000000") + "\n"), 1, 10,
            "2026-11-15T23:43:06", "2026-11-15T23:43:07");
        assertEquals(1, i.events.size());
    }

    @Test public void cacheInvalidatesOnSizeAndMtimeAndPointersRefuseStaleFile() throws Exception
    {
        Path p = file(event("CALL", "1") + "\n"); var before = TechLogReader.read(p, 1, 10, null, null);
        assertSame(before, TechLogReader.read(p, 1, 10, null, null));
        Files.writeString(p, event("EXCP", "1") + "\n" + event("CALL", "1") + "\n");
        var after = TechLogReader.read(p, 1, 10, null, null); assertNotSame(before, after); assertEquals(2, after.events.size());
        try { TechLogReader.reread(before.events.get(0).pointer()); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("Repeat summary")); }
    }

    @Test public void sqlNormalizesParametersLiteralsCommentsAndTempNumbersWithoutMergingTableRoles()
    {
        assertEquals(TechLogAnalysis.normalizeSql("SELECT * FROM #tt123 a JOIN #tt456 b ON a.x=b.x WHERE x=1 AND y='one' AND z=@P1"),
            TechLogAnalysis.normalizeSql("select * from #tt9 a join #tt10 b on a.x=b.x where x=2 and y='two' and z=@P2"));
        assertNotEquals(TechLogAnalysis.normalizeSql("select * from #tt1 a join #tt1 b on a.x=b.x"),
            TechLogAnalysis.normalizeSql("select * from #tt1 a join #tt2 b on a.x=b.x"));
        assertEquals("select ?", TechLogAnalysis.normalizeSql("SELECT 'can''t' -- comment\n/* test */\nParameters: @P1=42"));
        assertEquals("select ?", TechLogAnalysis.normalizeSql("SELECT $tag$literal 42$tag$"));
        assertNotEquals(TechLogAnalysis.normalizeSql("select \"MixedCase\""), TechLogAnalysis.normalizeSql("select \"mixedcase\""));
    }

    @Test public void groupsRankingStableIdsAndNormalProcessIsNotACrash() throws Exception
    {
        var i = TechLogReader.read(root().resolve("tests/fixtures/tech-log/synthetic.log"), 1, 10, null, null);
        assertEquals(15, i.events.size()); assertEquals(0, i.rejected);
        var ranked = TechLogAnalysis.ranked(i); assertEquals("process-crashes", ranked.get(0).problem);
        assertEquals(1, ranked.get(0).occurrences.size());
        var sql = ranked.stream().filter(g -> g.problem.equals("slow-db") && g.key.startsWith("DBV8DBENG")).findFirst().orElseThrow();
        assertEquals(2, sql.occurrences.size()); assertEquals(3000000, sql.totalUs, 0); assertEquals(2000000, sql.maxUs);
        assertEquals(2, ranked.stream().filter(g -> g.problem.equals("exceptions")).findFirst().orElseThrow().occurrences.size());
        assertTrue("Documented Sdbl property must contribute platform-query groups", ranked.stream()
            .anyMatch(g -> g.problem.equals("slow-db") && g.key.startsWith("SDBL: select")));
        assertEquals(sql.id, TechLogAnalysis.hash(sql.problem + ":" + sql.key));
        assertTrue(TechLogAnalysis.summary(i, 1, 0).contains("showing 1-1 of"));
    }

    @Test public void guidsAreOneLiteralInGroupingKeys()
    {
        assertEquals(TechLogAnalysis.normalizeSql("9db1fa37-b455-4f3f-b8dd-7de0ea7d6da3: file not found"),
            TechLogAnalysis.normalizeSql("12345678-ba49-4321-ac67-fcd6f2180121: file not found"));
        assertEquals("?: file not found", TechLogAnalysis.normalizeSql("9db1fa37-b455-4f3f-b8dd-7de0ea7d6da3: file not found"));
    }

    @Test public void coverageExplainsAbsentWithAndWithoutManifest() throws Exception
    {
        var i = TechLogReader.read(file(event("EXCP", "1") + "\n"), 1, 10, null, null);
        assertTrue(TechLogAnalysis.coverage(i).contains("capture enablement unknown"));
        JsonObject m = new JsonObject(); m.addProperty("profile", "errors");
        var events = new com.google.gson.JsonArray(); TechLogKnowledge.events("errors").forEach(events::add); m.add("events", events); i.manifest = m;
        String result = TechLogAnalysis.coverage(i);
        assertTrue(result.contains("Not enabled by profile errors")); assertTrue(result.contains("Enabled, none occurred"));
        assertTrue(result, result.contains("- Present: EXCP 1")); assertTrue(result.contains("Absent ≠ no problem"));
        i.limited = true; assertTrue(TechLogAnalysis.coverage(i).contains("before the scan stopped"));
    }

    @Test public void chainHasParentNestedSqlAndObservedDbmsSourceAndExplicitLimits() throws Exception
    {
        var i = TechLogReader.read(root().resolve("tests/fixtures/tech-log/synthetic.log"), 1, 10, null, null);
        var victim = i.events.stream().filter(e -> !e.lkpid().isBlank()).findFirst().orElseThrow();
        var chain = TechLogAnalysis.chain(i, victim.id());
        assertTrue(chain.events().stream().anyMatch(e -> e.name().equals("CALL")));
        assertTrue(chain.events().stream().anyMatch(e -> e.name().equals("SDBL")));
        assertTrue(chain.events().stream().anyMatch(e -> e.dbpid().equals("23")));
        assertTrue(chain.completeness().contains("Observed DBMS source"));
        assertTrue(chain.completeness().contains("other node not collected"));
        assertTrue(chain.completeness().contains("duration filtering"));
        assertTrue(TechLogAnalysis.renderEvents(chain.events(), 1, 1).contains("next page: offset=2"));
    }

    @Test public void chainWithoutParentAndWithoutIdentityDoesNotJoinUnrelatedEvents() throws Exception
    {
        var i = TechLogReader.read(file(event("EXCP", "1") + "\n" + event("CALL", "5000000") + "\n"), 1, 10, null, null);
        var chain = TechLogAnalysis.chain(i, i.events.get(0).id());
        assertTrue(chain.completeness().contains("missing parent"));
        assertTrue(chain.events().isEmpty() || chain.events().size() == 1);
    }

    @Test public void everyKnowledgeEntryHasSourceAndProfilesAndProblemsResolve()
    {
        for (String category : List.of("events", "properties", "profiles", "problems", "gotchas", "elements"))
            for (var item : TechLogKnowledge.entries(category).entrySet())
            {
                var entry = item.getValue().getAsJsonObject();
                assertTrue(category + ":" + item.getKey(), entry.getAsJsonArray("sources").size() > 0);
                entry.getAsJsonArray("sources").forEach(source -> {
                    String url = source.getAsJsonObject().get("url").getAsString();
                    assertTrue(url.startsWith("https://") || url.startsWith("http://"));
                    assertTrue(source.getAsJsonObject().get("tag").getAsString().startsWith("["));
                });
            }
        for (String profile : TechLogKnowledge.entries("profiles").keySet())
            for (String event : TechLogKnowledge.events(profile)) assertNotNull(TechLogKnowledge.entry("events", event));
        for (String profile : List.of("errors", "slow-calls", "db", "locks"))
            assertTrue("Scenario must include the complete " + profile + " event set",
                TechLogKnowledge.events("scenario").containsAll(TechLogKnowledge.events(profile)));
        for (String problem : TechLogAnalysis.PROBLEMS) assertNotNull(TechLogKnowledge.entry("problems", problem));
        for (String event : List.of("MEM", "LEAKS", "SCRIPTCIRCREFS"))
            assertFalse(TechLogKnowledge.entry("events", event).getAsJsonArray("generatingElements").isEmpty());
        assertTrue(TechLogKnowledge.describe("events", "mem").contains("mem"));
        try { TechLogKnowledge.entry("events", "TDEADLOK"); fail(); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().contains("TDEADLOCK")); }
    }

    private static final Map<String, List<String>> REQUIRED_KNOWLEDGE_FIELDS = Map.of(
        "events", List.of("meaning", "read", "badSigns", "traps", "fixes", "cost", "sources"),
        "properties", List.of("meaning", "read", "appearsIn", "sources"),
        "problems", List.of("meaning", "evidence", "investigate", "fixes", "traps", "profile", "sources"),
        "profiles", List.of("meaning", "read", "traps", "cost", "events", "thresholdMs", "historyHours", "sources"),
        "elements", List.of("meaning", "reason", "cost", "sources"),
        "gotchas", List.of("note", "sources"));

    private static List<String> knowledgeViolations(String category, JsonObject entries)
    {
        List<String> violations = new ArrayList<>();
        Map<String, Set<String>> owners = new HashMap<>();
        for (var item : entries.entrySet())
        {
            var entry = item.getValue().getAsJsonObject();
            String label = category + ":" + item.getKey();
            for (String field : REQUIRED_KNOWLEDGE_FIELDS.get(category))
            {
                var value = entry.get(field);
                if (value == null || value.isJsonNull()
                    || (value.isJsonPrimitive() && value.getAsString().isBlank())
                    || (value.isJsonArray() && value.getAsJsonArray().isEmpty()))
                    violations.add(label + " missing " + field);
            }
            for (var field : entry.entrySet())
                if (field.getValue().isJsonPrimitive() && field.getValue().getAsJsonPrimitive().isString())
                    owners.computeIfAbsent(field.getValue().getAsString(), key -> new HashSet<>()).add(item.getKey());
            for (String field : List.of("evidence", "read", "keyProperties"))
                if (entry.has(field) && entry.get(field).equals(entry.get("meaning")))
                    violations.add(label + " repeats meaning in " + field);
            if (entry.has("sources") && !entry.get("sources").isJsonArray())
                violations.add(label + " invalid sources");
            if (entry.has("sources") && entry.get("sources").isJsonArray())
                for (var source : entry.getAsJsonArray("sources"))
                {
                    try
                    {
                        var ref = source.getAsJsonObject();
                        URI url = URI.create(ref.get("url").getAsString());
                        if (!("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                            || url.getHost() == null || ref.get("tag").getAsString().isBlank())
                            violations.add(label + " invalid source");
                    }
                    catch (RuntimeException e) { violations.add(label + " invalid source"); }
                }
        }
        owners.forEach((value, ids) -> {
            if (ids.size() > 3) violations.add(category + " shared by " + ids.size() + ": " + value);
        });
        return violations;
    }

    @Test public void bundledKnowledgeHasSpecificSourcedRequiredFields()
    {
        List<String> violations = new ArrayList<>();
        for (String category : REQUIRED_KNOWLEDGE_FIELDS.keySet())
            violations.addAll(knowledgeViolations(category, TechLogKnowledge.entries(category)));
        assertTrue(String.join("\n", violations), violations.isEmpty());
    }

    @Test public void knowledgeRatchetRejectsBoilerplateCopiesMissingFieldsAndInvalidSources()
    {
        var entries = TechLogKnowledge.entries("events");
        for (String id : List.of("CALL", "SCALL", "SDBL", "TLOCK"))
            entries.getAsJsonObject(id).addProperty("read", "Repeated filler");
        var call = entries.getAsJsonObject("CALL");
        call.addProperty("evidence", call.get("meaning").getAsString());
        call.addProperty("keyProperties", call.get("meaning").getAsString());
        call.remove("fixes");
        call.getAsJsonArray("sources").get(0).getAsJsonObject().addProperty("url", "file:///local");
        entries.getAsJsonObject("SCALL").remove("sources");
        var violations = knowledgeViolations("events", entries);
        for (String expected : List.of("shared by 4", "repeats meaning in evidence", "repeats meaning in keyProperties",
            "missing fixes", "invalid source", "missing sources"))
            assertTrue(expected + ": " + violations, violations.stream().anyMatch(v -> v.contains(expected)));
        call.addProperty("read", call.get("meaning").getAsString());
        assertTrue(knowledgeViolations("events", entries).stream().anyMatch(v -> v.contains("repeats meaning in read")));
        call.add("sources", new com.google.gson.JsonArray());
        assertTrue(knowledgeViolations("events", entries).stream().anyMatch(v -> v.contains("CALL missing sources")));
    }

    @Test public void knowledgeDescriptionUsesOrderedParagraphsAndMarkdownSourceLinks()
    {
        String text = TechLogKnowledge.describe("events", "CALL");
        assertTrue(text.startsWith("## CALL\n\n"));
        assertTrue(text.contains("**Meaning:** "));
        assertTrue(text.indexOf("**Read:**") > text.indexOf("**Meaning:**"));
        assertTrue(text.indexOf("**Traps:**") > text.indexOf("**Fixes:**"));
        assertTrue(text.contains("- [doc](https://its.1c.ru/"));
        assertFalse(text.contains("[{\"tag\""));
        assertFalse(text.contains("| Field |"));
        assertFalse(text.contains("**Appears in:**"));
        assertTrue(TechLogKnowledge.describe("events", "MEM").contains("\\<mem/\\>"));
        assertTrue(TechLogKnowledge.describe("profiles", "db").contains("DBPOSTGRS"));
        assertTrue(TechLogKnowledge.describe("events", null).contains("JSON/placement"));
    }

    private TechLogCapture.Options options(Path location)
    { return new TechLogCapture.Options("db", location, 1234, List.of(), "A&B", true, true, 1, 100); }

    @Test public void configGenerationHasCorrectUnitsAndSiblingGeneratingElements() throws Exception
    {
        String xml = TechLogCapture.xml(options(temporary.getRoot().toPath().resolve("capture")), "test");
        assertTrue(xml.contains("property=\"durationus\" value=\"1234000\"")); assertFalse(xml.contains("property=\"duration\""));
        assertTrue(xml.contains("format=\"json\" placement=\"plain\"")); assertTrue(xml.contains("rotation=\"size\""));
        assertTrue(xml.contains("value=\"A&amp;B\"")); assertTrue(xml.indexOf("</log>") < xml.indexOf("<plansql"));
        assertTrue(xml.contains("<dbmslocks")); assertTrue(xml.contains(TechLogCapture.MARKER));
    }

    @Test public void foreignConfigInsertionAndRoundTripRestoreAreByteExactAcrossRestart() throws Exception
    {
        Path config = temporary.newFile("logcfg.xml").toPath();
        byte[] bytes = ("\ufeff<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<config xmlns=\"http://v8.1c.ru/v8/tech-log\">\r\n"
            + "<!-- foreign --> <log location=\"D:/foreign\" history=\"2\"><event><eq property=\"name\" value=\"EXCP\"/></event></log>\r\n</config>\r\n").getBytes(StandardCharsets.UTF_8);
        Files.write(config, bytes);
        Path location = temporary.getRoot().toPath().resolve("capture");
        var c = new TechLogCapture(config, List.of()); String result = c.start(options(location), () -> null);
        assertTrue(result.contains("Config written"));
        String changed = Files.readString(config); assertTrue(changed.contains("foreign")); assertTrue(changed.contains(TechLogCapture.MARKER));
        assertTrue(c.status().contains("Waiting for pickup")); assertTrue(c.warning().contains("mode=stop"));
        var restarted = new TechLogCapture(config, List.of()); assertTrue(restarted.stop(() -> null).contains("byte-exactly"));
        assertArrayEquals(bytes, Files.readAllBytes(config));
        assertTrue(Files.readString(location.resolve("edt-mcp-capture.json")).contains("stopTime"));
        assertEquals("", restarted.warning());
    }

    @Test public void originalAbsenceIsRestoredAndForeignEditsRefuseStop() throws Exception
    {
        Path config = temporary.getRoot().toPath().resolve("logcfg.xml"); var c = new TechLogCapture(config, List.of());
        c.start(options(temporary.getRoot().toPath().resolve("capture")), () -> null);
        byte[] ours = Files.readAllBytes(config); Files.writeString(config, "foreign edit");
        try { c.stop(() -> null); fail(); } catch (IOException e) { assertTrue(e.getMessage().contains("foreign edits")); }
        assertEquals("foreign edit", Files.readString(config)); Files.write(config, ours);
        c.stop(() -> null); assertFalse(Files.exists(config));
    }

    @Test public void stopRemovesTheConfigDirectoryOnlyWhenStartCreatedIt() throws Exception
    {
        Path created = temporary.getRoot().toPath().resolve("new-conf");
        var c = new TechLogCapture(created.resolve("logcfg.xml"), List.of());
        c.start(options(temporary.getRoot().toPath().resolve("capture-new")), () -> null);
        c.stop(() -> null); assertFalse(Files.exists(created));
        Path existing = Files.createDirectories(temporary.getRoot().toPath().resolve("old-conf"));
        var d = new TechLogCapture(existing.resolve("logcfg.xml"), List.of());
        d.start(options(temporary.getRoot().toPath().resolve("capture-old")), () -> null);
        d.stop(() -> null); assertTrue(Files.isDirectory(existing));
    }

    @Test public void deniedStartLeavesNoConfigDirectory() throws Exception
    {
        Path conf = temporary.getRoot().toPath().resolve("conf");
        var capture = new TechLogCapture(conf.resolve("logcfg.xml"), List.of());
        assertEquals("denied", capture.start(options(temporary.getRoot().toPath().resolve("capture")), () -> "denied"));
        assertFalse(Files.exists(conf));
    }

    @Test public void deniedThenAllowedStartAndStopRemovesConfigDirectory() throws Exception
    {
        Path conf = temporary.getRoot().toPath().resolve("conf");
        var capture = new TechLogCapture(conf.resolve("logcfg.xml"), List.of());
        var o = options(temporary.getRoot().toPath().resolve("capture"));
        capture.start(o, () -> "denied"); capture.start(o, () -> null); capture.stop(() -> null);
        assertFalse(Files.exists(conf));
    }

    @Test public void failedStartAfterCreatingConfigDirectoryRemovesItWhenEmpty() throws Exception
    {
        Path conf = temporary.getRoot().toPath().resolve("conf");
        Path location = temporary.getRoot().toPath().resolve("capture");
        var capture = new TechLogCapture(conf.resolve("logcfg.xml"), List.of());
        try
        {
            capture.start(options(location), () -> {
                try { Files.writeString(location, "Blocks capture directory creation after consent"); }
                catch (IOException e) { throw new AssertionError(e); }
                return null;
            });
            fail("Capture directory creation must fail");
        }
        catch (IOException expected) { assertTrue(Files.isRegularFile(location)); }
        assertFalse(Files.exists(conf));
    }

    @Test public void missingActiveRecordRecoversFromOwnManifestAfterCrash() throws Exception
    {
        Path config = temporary.newFile("logcfg.xml").toPath();
        byte[] original = "<config xmlns=\"http://v8.1c.ru/v8/tech-log\"/>\r\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, original); var c = new TechLogCapture(config, List.of());
        c.start(options(temporary.getRoot().toPath().resolve("capture")), () -> null);
        Files.delete(config.resolveSibling("edt-mcp-active-capture.json"));
        assertTrue(c.warning().contains("mode=stop")); assertTrue(c.stop(() -> null).contains("byte-exactly"));
        assertArrayEquals(original, Files.readAllBytes(config));
    }

    @Test public void utf16ForeignConfigIsRestoredByteExactly() throws Exception
    {
        Path config = temporary.newFile("logcfg.xml").toPath();
        byte[] original = ("\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<config xmlns=\"http://v8.1c.ru/v8/tech-log\"/>")
            .getBytes(StandardCharsets.UTF_16LE);
        Files.write(config, original); var c = new TechLogCapture(config, List.of());
        c.start(options(temporary.getRoot().toPath().resolve("capture")), () -> null); c.stop(() -> null);
        assertArrayEquals(original, Files.readAllBytes(config));
    }

    @Test public void generatingElementsAreNotDuplicatedInForeignConfig() throws Exception
    {
        byte[] original = "<config xmlns=\"http://v8.1c.ru/v8/tech-log\"><plansql/></config>".getBytes(StandardCharsets.UTF_8);
        try { TechLogCapture.insert(original, TechLogCapture.xml(options(temporary.getRoot().toPath()), "test")); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("plansql")); }
    }

    @Test public void shadowingDetectionNamesOnlyVersionsWithBinConf() throws Exception
    {
        Path root = temporary.newFolder("platforms").toPath();
        Files.createDirectories(root.resolve("8.5.1/bin/conf")); Files.writeString(root.resolve("8.5.1/bin/conf/logcfg.xml"), "test");
        Files.createDirectories(root.resolve("8.3.27/bin/conf"));
        var c = new TechLogCapture(temporary.getRoot().toPath().resolve("logcfg.xml"), List.of(root));
        assertEquals(1, c.shadowing().size()); assertTrue(c.shadowing().get(0).contains("8.5.1"));
    }

    @Test public void competingCaptureIsExcludedAndWindowsLockFileDisappears() throws Exception
    {
        Path target = temporary.getRoot().toPath().resolve("logcfg.xml");
        var c = new TechLogCapture(target, List.of());
        String result = c.start(options(temporary.getRoot().toPath().resolve("capture")), () -> {
            try
            {
                new TechLogCapture(target, List.of()).start(options(temporary.getRoot().toPath().resolve("other")), () -> null);
                fail("A second writer acquired the active config mutex");
            }
            catch (java.nio.channels.OverlappingFileLockException | IOException expected) { }
            catch (Exception e) { throw new AssertionError(e); }
            return "declined";
        });
        assertEquals("declined", result); assertFalse(Files.exists(target));
        if (System.getProperty("os.name", "").startsWith("Windows"))
            assertFalse(Files.exists(Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("edt-mcp-tech-log-" + TechLogAnalysis.hash(target.toString()) + ".lock")));
    }
}
