/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The load-bearing suite for the pure event-log parser package. Exercises the whole
 * {@link EventLogReader} seam against a committed real-shape fixture
 * ({@code resources/eventlog/1Cv8.lgf} + {@code 20260608000000.lgp}) plus synthetic
 * multi-partition logs built in a per-test temporary directory: every filter, pagination,
 * ordering, partition-range skipping, the format quirks (empty-name user, nested-tree
 * data, a skipped type-13 line, a truncated trailing record) and {@code .lgd}
 * detection. Pure and headless - no Eclipse workspace, no EDT model.
 */
public class EventLogReaderTest
{
    // Localized (Russian) fixture strings, kept as unicode escapes (see the constant
    // initialisers) so this source stays ASCII. Transliterations: Administrator /
    // seansa / ZakazKlienta / Provedenie / Zakaz N1 / Dokument.ZakazKlienta / Oshibka.
    private static final String RU_ADMIN = "\u0410\u0434\u043c\u0438\u043d\u0438\u0441\u0442\u0440\u0430\u0442\u043e\u0440";
    private static final String RU_SEANSA = "\u0441\u0435\u0430\u043d\u0441\u0430";
    private static final String RU_ZAKAZ_KLIENTA = "\u0417\u0430\u043a\u0430\u0437\u041a\u043b\u0438\u0435\u043d\u0442\u0430";
    private static final String RU_PROVEDENIE = "\u041f\u0440\u043e\u0432\u0435\u0434\u0435\u043d\u0438\u0435";
    private static final String RU_ZAKAZ_N1 = "\u0417\u0430\u043a\u0430\u0437 \u2116 1";
    private static final String RU_DOC_ZAKAZ = "\u0414\u043e\u043a\u0443\u043c\u0435\u043d\u0442.\u0417\u0430\u043a\u0430\u0437\u041a\u043b\u0438\u0435\u043d\u0442\u0430";
    private static final String RU_OSHIBKA = "\u041e\u0448\u0438\u0431\u043a\u0430";

    /** Per-test scratch root, created fresh in {@link #setUp()} and removed in {@link #tearDown()}. */
    private Path tmp;

    @Before
    public void setUp() throws IOException
    {
        tmp = Files.createTempDirectory("evlog-reader"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        if (tmp == null || !Files.exists(tmp))
        {
            return;
        }
        try (Stream<Path> walk = Files.walk(tmp))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException ignore)
                {
                    // Best-effort cleanup of a scratch directory.
                }
            });
        }
    }

    /** Creates (idempotently) a named subdirectory under the per-test scratch root. */
    private Path newFolder(String name) throws IOException
    {
        return Files.createDirectories(tmp.resolve(name));
    }

    // ----------------------------------------------------------------------------
    // Committed real-shape fixture
    // ----------------------------------------------------------------------------

    @Test
    public void readsWholeFixture_resolvingReferencesAndToleratingTruncatedTail() throws Exception
    {
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), new EventLogQuery());

        // 6 well-formed records; the 7th (truncated) is scanned but not matched.
        assertEquals(6, page.matchedTotal);
        assertEquals(6, page.records.size());
        assertEquals(7, page.scanned);
        assertFalse(page.truncated);

        // Default order is ascending by date.
        EventRecord first = page.records.get(0);
        assertEquals("2026-06-08T09:00:00", first.dateIso);
        assertEquals("_$Session$_.Start", first.event);
        assertEquals("I", first.severityCode);
        assertEquals("Information", first.severity);
        // Empty-name system user (seq 1) resolves to an empty string, not null.
        assertEquals("", first.user);
        assertEquals("BackgroundJob", first.application);
        assertEquals("WORKSTATION-01", first.computer);
        assertEquals(Integer.valueOf(1560), first.mainPort);
        assertEquals(10L, first.session);

        EventRecord last = page.records.get(5);
        assertEquals("2026-06-08T12:00:00", last.dateIso);
        assertEquals("_$Session$_.Finish", last.event);
        assertEquals("N", last.severityCode);
        assertEquals(RU_ADMIN, last.user);
    }

    @Test
    public void type13LineSkipped_referencesAfterItStillParse() throws Exception
    {
        // The metadata (type 5) and port (type 7/8) rows sit AFTER the type-13
        // housekeeping line in the .lgf; if the skip aborted parsing they would be
        // unresolved. Record 3 (Provedenie) carries metadata seq 1 and main port 1560.
        EventLogQuery q = new EventLogQuery();
        q.commentContains = RU_PROVEDENIE;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.records.size());
        EventRecord ev = page.records.get(0);
        assertEquals(RU_DOC_ZAKAZ, ev.metadata);
        assertEquals(Integer.valueOf(1560), ev.mainPort);
    }

    @Test
    public void nestedDataRecord_decodedTolerantly() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.commentContains = RU_PROVEDENIE;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.records.size());
        EventRecord ev = page.records.get(0);
        assertEquals("C", ev.txStatus);
        assertEquals("18f2a3b4c5d/1a", ev.txId);
        // data = {"R",{2,1}} -> scalar type "R", value null (nested tree, not a scalar).
        assertEquals("R", ev.dataType);
        assertNull(ev.dataValue);
        assertEquals(RU_ZAKAZ_N1, ev.dataPresentation);
    }

    @Test
    public void scalarStringData_isCaptured() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.severity(List.of("Error"));
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.records.size());
        EventRecord ev = page.records.get(0);
        assertEquals("_$User$_.AuthenticationError", ev.event);
        assertEquals("S", ev.dataType);
        assertEquals("bad password", ev.dataValue);
    }

    @Test
    public void filterBySeverity_english() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.severity(List.of("Error"));
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.matchedTotal);
        assertEquals("E", page.records.get(0).severityCode);
    }

    @Test
    public void filterBySeverity_russianWordAndLetterAgree() throws Exception
    {
        EventLogReader.Page ru =
            new EventLogReader().read(fixturesDir(), new EventLogQuery().severity(List.of(RU_OSHIBKA)));
        EventLogReader.Page letter =
            new EventLogReader().read(fixturesDir(), new EventLogQuery().severity(List.of("E")));
        assertEquals(1, ru.matchedTotal);
        assertEquals(letter.matchedTotal, ru.matchedTotal);
        assertEquals("E", ru.records.get(0).severityCode);
    }

    @Test
    public void filterByUser_exactName() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.user(List.of(RU_ADMIN));
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(5, page.matchedTotal);
    }

    @Test
    public void filterByUser_emptyNameSystemUser() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.user(List.of(""));
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.matchedTotal);
        assertEquals("2026-06-08T09:00:00", page.records.get(0).dateIso);
    }

    @Test
    public void filterByEventContains() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.eventContains = "Session";
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(3, page.matchedTotal);
    }

    @Test
    public void filterByCommentContains_cyrillicSubstring() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.commentContains = RU_SEANSA;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        // Matches "Nachalo seansa" + "Okonchanie seansa" (both contain "seansa").
        assertEquals(2, page.matchedTotal);
    }

    @Test
    public void filterByMetadataContains_cyrillicSubstring() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.metadataContains = RU_ZAKAZ_KLIENTA;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        // The two records carrying metadata seq 1 (Provedenie + Preduprezhdenie).
        assertEquals(2, page.matchedTotal);
    }

    @Test
    public void filterBySession() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.session(List.of(Long.valueOf(12L)));
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(1, page.matchedTotal);
        assertEquals("W", page.records.get(0).severityCode);
    }

    @Test
    public void filterByDateRange() throws Exception
    {
        EventLogQuery q = new EventLogQuery().from("2026-06-08T11:00:00").to("2026-06-08T23:59:59");
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(3, page.matchedTotal);
        assertEquals("2026-06-08T11:00:00", page.records.get(0).dateIso);
    }

    @Test
    public void bareToDate_isInclusiveOfWholeDay() throws Exception
    {
        // A bare "to" date must cover the WHOLE day, not stop at midnight. Every fixture event is on
        // 2026-06-08 at 09:00 or later, so to="2026-06-08" (snapped to end-of-day) keeps all 6; a
        // midnight bound (the pre-fix behaviour) would drop every event after 00:00:00 and match none.
        EventLogReader.Page page =
            new EventLogReader().read(fixturesDir(), new EventLogQuery().to("2026-06-08"));
        assertEquals(6, page.matchedTotal);
        assertEquals("2026-06-08T09:00:00", page.records.get(0).dateIso);
        assertEquals("2026-06-08T12:00:00", page.records.get(5).dateIso);
    }

    @Test
    public void pagination_offsetLimit() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.offset = 1;
        q.limit = 2;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(6, page.matchedTotal);
        assertEquals(2, page.records.size());
        assertEquals("2026-06-08T10:00:00", page.records.get(0).dateIso);
        assertEquals("2026-06-08T10:30:00", page.records.get(1).dateIso);
    }

    @Test
    public void ordering_descendingNewestFirst() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.descending = true;
        q.limit = 3;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        assertEquals(6, page.matchedTotal);
        assertEquals(3, page.records.size());
        assertEquals("2026-06-08T12:00:00", page.records.get(0).dateIso);
        assertEquals("2026-06-08T11:30:00", page.records.get(1).dateIso);
        assertEquals("2026-06-08T11:00:00", page.records.get(2).dateIso);
    }

    @Test
    public void ordering_descendingWithOffset() throws Exception
    {
        EventLogQuery q = new EventLogQuery();
        q.descending = true;
        q.offset = 1;
        q.limit = 2;
        EventLogReader.Page page = new EventLogReader().read(fixturesDir(), q);
        // Skip the newest (12:00), then take the next two going back in time.
        assertEquals(2, page.records.size());
        assertEquals("2026-06-08T11:30:00", page.records.get(0).dateIso);
        assertEquals("2026-06-08T11:00:00", page.records.get(1).dateIso);
    }

    @Test
    public void scanCap_setsTruncatedFlag() throws Exception
    {
        EventLogReader capped = new EventLogReader(new LgfParser(), 3);
        EventLogReader.Page page = capped.read(fixturesDir(), new EventLogQuery());
        assertTrue(page.truncated);
    }

    // ----------------------------------------------------------------------------
    // Synthetic multi-partition logs
    // ----------------------------------------------------------------------------

    @Test
    public void multiPartition_offsetSpansPartitions() throws Exception
    {
        Path dir = newFolder("log");
        writeMinimalLgf(dir);
        writeLgp(dir, "20260601000000.lgp", List.of(
            evt(20260601090000L, "I", 2, 1, "a", 1),
            evt(20260601100000L, "I", 2, 1, "b", 1),
            evt(20260601110000L, "I", 2, 1, "c", 1)));
        writeLgp(dir, "20260602000000.lgp", List.of(
            evt(20260602090000L, "I", 2, 1, "d", 1),
            evt(20260602100000L, "I", 2, 1, "e", 1),
            evt(20260602110000L, "I", 2, 1, "f", 1)));

        EventLogQuery q = new EventLogQuery();
        q.offset = 2;
        q.limit = 2;
        EventLogReader.Page page = new EventLogReader().read(dir, q);
        assertEquals(6, page.matchedTotal);
        assertEquals(2, page.records.size());
        // 3rd and 4th matches cross the partition boundary (last of part A, first of part B).
        assertEquals("c", page.records.get(0).comment);
        assertEquals("d", page.records.get(1).comment);
    }

    @Test
    public void descendingTruncation_keepsNewestPartitionNotOldest() throws Exception
    {
        Path dir = newFolder("log");
        writeMinimalLgf(dir);
        // An older partition (06-01) and a newer one (06-02), three records each.
        writeLgp(dir, "20260601000000.lgp", List.of(
            evt(20260601090000L, "I", 2, 1, "old-a", 1),
            evt(20260601100000L, "I", 2, 1, "old-b", 1),
            evt(20260601110000L, "I", 2, 1, "old-c", 1)));
        writeLgp(dir, "20260602000000.lgp", List.of(
            evt(20260602090000L, "I", 2, 1, "new-a", 1),
            evt(20260602100000L, "I", 2, 1, "new-b", 1),
            evt(20260602110000L, "I", 2, 1, "new-c", 1)));

        // The cap trips after the first scanned partition. A descending read walks
        // partitions newest-first, so a truncated "newest first" page must come from the
        // NEWEST partition - never the oldest events scanned (the fixed direction bug).
        EventLogReader capped = new EventLogReader(new LgfParser(), 2);
        EventLogQuery q = new EventLogQuery();
        q.descending = true;
        EventLogReader.Page page = capped.read(dir, q);

        assertTrue(page.truncated);
        assertEquals("2026-06-02T11:00:00", page.records.get(0).dateIso);
        assertEquals("new-c", page.records.get(0).comment);
        for (EventRecord ev : page.records)
        {
            assertTrue("oldest-partition record leaked into the newest page: " + ev.comment,
                ev.comment.startsWith("new-"));
        }
    }

    @Test
    public void partitionRange_skipsFarOutOfRangeFilesButKeepsCarryOver() throws Exception
    {
        Path dir = newFolder("log");
        writeMinimalLgf(dir);
        for (String name : List.of("20260321000000.lgp", "20260413000000.lgp", "20260525000000.lgp"))
        {
            writeLgp(dir, name, List.of(evt(20260525100000L, "I", 2, 1, "x", 1)));
        }
        // Window 2026-05-01 .. 2026-05-31 -> keep 04-13 (carry-over) + 05-25, drop 03-21.
        List<Path> ps =
            EventLogReader.listPartitionsInRange(dir, 20260501000000L, 20260531235959L, false);
        assertEquals(2, ps.size());
        assertEquals("20260413000000.lgp", ps.get(0).getFileName().toString());
        assertEquals("20260525000000.lgp", ps.get(1).getFileName().toString());
    }

    @Test
    public void partitionRange_descendingReversesOrder() throws Exception
    {
        Path dir = newFolder("log");
        writeMinimalLgf(dir);
        for (String name : List.of("20260601000000.lgp", "20260602000000.lgp"))
        {
            writeLgp(dir, name, List.of(evt(20260601090000L, "I", 2, 1, "x", 1)));
        }
        List<Path> ps = EventLogReader.listPartitionsInRange(dir, 0L, 99999999999999L, true);
        assertEquals(2, ps.size());
        assertEquals("20260602000000.lgp", ps.get(0).getFileName().toString());
    }

    // ----------------------------------------------------------------------------
    // Error handling
    // ----------------------------------------------------------------------------

    @Test
    public void missingLgf_actionableError() throws Exception
    {
        Path dir = newFolder("empty");
        try
        {
            new EventLogReader().read(dir, new EventLogQuery());
            fail("expected EventLogException for a directory without 1Cv8.lgf");
        }
        catch (EventLogReader.EventLogException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("1Cv8.lgf"));
        }
    }

    @Test
    public void modernSqliteLgd_reportedAsUnsupported() throws Exception
    {
        Path dir = newFolder("lgd");
        Files.writeString(dir.resolve("1Cv8.lgd"), "SQLite format 3\0", StandardCharsets.UTF_8);
        try
        {
            new EventLogReader().read(dir, new EventLogQuery());
            fail("expected EventLogException for a .lgd (SQLite) log");
        }
        catch (EventLogReader.EventLogException e)
        {
            assertTrue(e.getMessage(), e.getMessage().toLowerCase().contains("sqlite"));
            assertTrue(e.getMessage(), e.getMessage().contains(".lgd"));
        }
    }

    @Test
    public void missingDirectory_actionableError() throws Exception
    {
        Path missing = newFolder("missing-parent").resolve("does-not-exist");
        try
        {
            new EventLogReader().read(missing, new EventLogQuery());
            fail("expected EventLogException for a missing directory");
        }
        catch (EventLogReader.EventLogException e)
        {
            assertTrue(e.getMessage(), e.getMessage().contains("directory not found"));
        }
    }

    // ----------------------------------------------------------------------------
    // Pure helpers (no fixture / no I/O)
    // ----------------------------------------------------------------------------

    @Test
    public void severityCanonicalisation_isBilingualAndForgiving()
    {
        assertEquals("I", EventLogQuery.canonicalSeverity("Information"));
        assertEquals("I", EventLogQuery.canonicalSeverity("info"));
        assertEquals("I", EventLogQuery.canonicalSeverity("i"));
        assertEquals("W", EventLogQuery.canonicalSeverity("Warning"));
        assertEquals("E", EventLogQuery.canonicalSeverity("error"));
        assertEquals("E", EventLogQuery.canonicalSeverity(RU_OSHIBKA));
        assertEquals("N", EventLogQuery.canonicalSeverity("Note"));
        // Unknown passes through trimmed (fail-closed at match time).
        assertEquals("Verbose", EventLogQuery.canonicalSeverity("  Verbose "));
        assertNull(EventLogQuery.canonicalSeverity(null));
    }

    @Test
    public void isoRoundTrip()
    {
        long raw = 20260608123456L;
        assertEquals("2026-06-08T12:34:56", LgpParser.toIso(raw));
        assertEquals(raw, LgpParser.fromIso("2026-06-08T12:34:56"));
        assertEquals(20260608000000L, LgpParser.fromIso("2026-06-08"));
    }

    @Test
    public void severityDecoding()
    {
        assertEquals("Information", LgpParser.decodeSeverity("I"));
        assertEquals("Warning", LgpParser.decodeSeverity("W"));
        assertEquals("Error", LgpParser.decodeSeverity("E"));
        assertEquals("Note", LgpParser.decodeSeverity("N"));
        assertNull(LgpParser.decodeSeverity(null));
    }

    // ----------------------------------------------------------------------------
    // Fixture / synthetic-log plumbing
    // ----------------------------------------------------------------------------

    /** A minimal .lgf sufficient to resolve the synthetic {@link #evt} records. */
    private static final String MINIMAL_LGF = ""
        + "1CV8LOG(ver 2.0)\nuuid\n\n"
        + "{1,00000000-0000-0000-0000-000000000000,\"\",1},\n"
        + "{1,11111111-1111-1111-1111-111111111111,\"" + RU_ADMIN + "\",2},\n"
        + "{2,\"WORKSTATION-01\",1},\n"
        + "{3,\"1CV8C\",1},\n"
        + "{4,\"_$Session$_.Start\",1}\n";

    /** Builds one synthetic 19-field record resolving against {@link #MINIMAL_LGF}. */
    private static String evt(long date, String severity, int userSeq, int eventSeq, String comment,
        long session)
    {
        return "{" + date + ",N,{0,0}," + userSeq + ",1,1,100," + eventSeq + "," + severity + ",\""
            + comment.replace("\"", "\"\"") + "\",0,{0},\"\",1,1,1," + session + ",0,{0}}";
    }

    private void writeMinimalLgf(Path dir) throws Exception
    {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("1Cv8.lgf"), MINIMAL_LGF, StandardCharsets.UTF_8);
    }

    private void writeLgp(Path dir, String partitionName, List<String> events) throws Exception
    {
        StringBuilder sb = new StringBuilder("1CV8LOG(ver 2.0)\nuuid\n\n");
        for (int i = 0; i < events.size(); i++)
        {
            sb.append(events.get(i));
            sb.append(i < events.size() - 1 ? ",\n" : "\n");
        }
        Files.writeString(dir.resolve(partitionName), sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Locates the committed {@code resources/eventlog} fixture directory. The test bundle
     * does not put {@code resources/} on the classpath, so the fixtures are resolved on
     * the filesystem: anchored on the test class's code-source location (usually
     * {@code target/classes}) and the JVM working directory, then walked upward to the
     * bundle root. Robust across Tycho Surefire and the Eclipse IDE JUnit launcher.
     */
    private static Path fixturesDir()
    {
        List<Path> anchors = new ArrayList<>();
        try
        {
            CodeSource cs = EventLogReaderTest.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null)
            {
                anchors.add(Paths.get(cs.getLocation().toURI()));
            }
        }
        catch (Exception ignore)
        {
            // Fall back to the working directory below.
        }
        anchors.add(Paths.get(System.getProperty("user.dir")));

        for (Path anchor : anchors)
        {
            Path p = anchor.toAbsolutePath();
            for (int i = 0; i < 10 && p != null; i++, p = p.getParent())
            {
                Path hit = firstExisting(
                    p.resolve("resources").resolve("eventlog"),
                    p.resolve("com.ditrix.edt.mcp.server.tests").resolve("resources").resolve("eventlog"),
                    p.resolve("mcp").resolve("tests").resolve("com.ditrix.edt.mcp.server.tests")
                        .resolve("resources").resolve("eventlog"));
                if (hit != null)
                {
                    return hit;
                }
            }
        }
        throw new IllegalStateException(
            "event-log fixtures not found (resources/eventlog/1Cv8.lgf); anchors=" + anchors);
    }

    private static Path firstExisting(Path... candidates)
    {
        for (Path c : candidates)
        {
            if (Files.isRegularFile(c.resolve("1Cv8.lgf")))
            {
                return c;
            }
        }
        return null;
    }
}
