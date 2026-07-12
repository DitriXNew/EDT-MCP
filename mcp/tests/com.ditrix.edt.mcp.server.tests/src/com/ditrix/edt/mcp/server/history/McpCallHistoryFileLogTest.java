/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.google.gson.JsonObject;

/**
 * Headless suite for the JSONL file-log sink. Exercises the two pure seams the
 * slice owns — the single-line JSON formatting ({@link McpCallHistoryFileLog#toJsonLine},
 * {@link McpCallHistoryFileLog#warningHeaderLine}) and the enable/disable + null-guard
 * gating ({@link McpCallHistoryFileLog#createIfEnabled}) — plus the actual disk append
 * to a per-test temporary directory (header first, one JSON line per record, no
 * duplicate header on append, and a swallowed I/O failure). No live server, no
 * shared call-history recorder, no preference store: the async writer is drained
 * deterministically via {@link McpCallHistoryFileLog#close()}.
 */
public class McpCallHistoryFileLogTest
{
    /** Per-test scratch root, created fresh in {@link #setUp()} and removed in {@link #tearDown()}. */
    private Path tmp;

    @Before
    public void setUp() throws IOException
    {
        tmp = Files.createTempDirectory("mcp-history-filelog"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws IOException
    {
        if (tmp != null && Files.exists(tmp))
        {
            try (Stream<Path> walk = Files.walk(tmp))
            {
                walk.sorted(Comparator.reverseOrder()).forEach(McpCallHistoryFileLogTest::deleteQuietly);
            }
        }
    }

    // ---- pure JSONL line formatting ------------------------------------------------

    @Test
    public void warningHeaderLineIsSingleLineJsonMentioningRawPreRedactionData()
    {
        String header = McpCallHistoryFileLog.warningHeaderLine();

        assertFalse("header must be a single line (no embedded newline)", header.contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$
        JsonObject parsed = GsonProvider.fromJson(header, JsonObject.class);
        assertTrue("header must carry a _warning field", parsed.has("_warning")); //$NON-NLS-1$ //$NON-NLS-2$

        String warning = parsed.get("_warning").getAsString(); //$NON-NLS-1$
        assertTrue("warning must mention that the data is raw/pre-redaction", //$NON-NLS-1$
            warning.contains("RAW") && warning.contains("PRE-REDACTION")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void toJsonLineIsCompactSingleLineEvenWhenAValueContainsNewlinesAndQuotes()
    {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool", "get_variables"); //$NON-NLS-1$ //$NON-NLS-2$
        record.put("payload", "line1\nline2 with a \" quote"); //$NON-NLS-1$ //$NON-NLS-2$

        String line = McpCallHistoryFileLog.toJsonLine(record);

        assertFalse("a record must serialize to exactly one line", line.contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$
        // Round-trips as JSON with the embedded newline preserved inside the string value.
        JsonObject parsed = GsonProvider.fromJson(line, JsonObject.class);
        assertEquals("get_variables", parsed.get("tool").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("line1\nline2 with a \" quote", parsed.get("payload").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void toJsonLineReturnsNullForNullRecord()
    {
        assertNull(McpCallHistoryFileLog.toJsonLine(null));
    }

    // ---- enable/disable + null-guard gating ----------------------------------------

    @Test
    public void createIfEnabledReturnsNullWhenDisabled()
    {
        assertNull("disabled must register/create nothing", //$NON-NLS-1$
            McpCallHistoryFileLog.createIfEnabled(false, tmp.resolve("history.jsonl").toString())); //$NON-NLS-1$
    }

    @Test
    public void createIfEnabledReturnsNullForNullOrBlankPath()
    {
        assertNull("null path is guarded", McpCallHistoryFileLog.createIfEnabled(true, null)); //$NON-NLS-1$
        assertNull("blank path is guarded", McpCallHistoryFileLog.createIfEnabled(true, "   ")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createIfEnabledReturnsSinkWhenEnabledWithPath()
    {
        try (McpCallHistoryFileLog sink =
            McpCallHistoryFileLog.createIfEnabled(true, tmp.resolve("history.jsonl").toString())) //$NON-NLS-1$
        {
            assertNotNull("enabled + valid path must yield a sink", sink); //$NON-NLS-1$
        }
    }

    @Test
    public void createIfEnabledTreatsThePathAsAFolderAndWritesAJsonlFileInsideIt() throws IOException
    {
        // The preferences UI collects a DIRECTORY (DirectoryDialog stored into
        // KEY_FILE_PATH), so createIfEnabled must place a fixed log file INSIDE that
        // folder rather than opening the folder itself as the log file (which throws and
        // silently self-disables the sink). tmp is an existing directory - the normal case.
        try (McpCallHistoryFileLog sink = McpCallHistoryFileLog.createIfEnabled(true, tmp.toString()))
        {
            assertNotNull("enabled + an existing folder must yield a sink", sink); //$NON-NLS-1$
            sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 1))); //$NON-NLS-1$
        }

        Path expected = tmp.resolve(McpCallHistoryFileLog.LOG_FILE_NAME);
        assertTrue("a JSONL file must be created inside the configured folder", Files.exists(expected)); //$NON-NLS-1$
        List<String> lines = Files.readAllLines(expected, StandardCharsets.UTF_8);
        assertEquals("header + one record", 2, lines.size()); //$NON-NLS-1$
        assertTrue("first line is the warning header", //$NON-NLS-1$
            GsonProvider.fromJson(lines.get(0), JsonObject.class).has("_warning")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, GsonProvider.fromJson(lines.get(1), JsonObject.class).get("n").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void disabledSinkWritesNothingToDisk() throws IOException
    {
        Path log = tmp.resolve("history.jsonl"); //$NON-NLS-1$
        McpCallHistoryFileLog sink = McpCallHistoryFileLog.createIfEnabled(false, log.toString());
        assertNull(sink);
        assertFalse("no file may be created while disabled", Files.exists(log)); //$NON-NLS-1$
    }

    // ---- disk append -----------------------------------------------------------------

    @Test
    public void writesWarningHeaderThenOneJsonLinePerRecord() throws IOException
    {
        Path log = tmp.resolve("history.jsonl"); //$NON-NLS-1$
        try (McpCallHistoryFileLog sink = new McpCallHistoryFileLog(log))
        {
            sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 1))); //$NON-NLS-1$
            sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 2))); //$NON-NLS-1$
        }

        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals("header + two records", 3, lines.size()); //$NON-NLS-1$

        JsonObject header = GsonProvider.fromJson(lines.get(0), JsonObject.class);
        assertTrue("first line is the warning header", header.has("_warning")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, GsonProvider.fromJson(lines.get(1), JsonObject.class).get("n").getAsInt()); //$NON-NLS-1$
        assertEquals(2, GsonProvider.fromJson(lines.get(2), JsonObject.class).get("n").getAsInt()); //$NON-NLS-1$
    }

    @Test
    public void doesNotDuplicateHeaderWhenAppendingToAnExistingNonEmptyFile() throws IOException
    {
        Path log = tmp.resolve("history.jsonl"); //$NON-NLS-1$
        Files.write(log, List.of("{\"pre\":true}"), StandardCharsets.UTF_8); //$NON-NLS-1$

        try (McpCallHistoryFileLog sink = new McpCallHistoryFileLog(log))
        {
            sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 1))); //$NON-NLS-1$
        }

        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        assertEquals("pre-existing line + one record, no injected header", 2, lines.size()); //$NON-NLS-1$
        assertTrue("existing content is preserved as the first line", //$NON-NLS-1$
            GsonProvider.fromJson(lines.get(0), JsonObject.class).get("pre").getAsBoolean()); //$NON-NLS-1$
        assertFalse("no warning header is inserted when appending", //$NON-NLS-1$
            GsonProvider.fromJson(lines.get(1), JsonObject.class).has("_warning")); //$NON-NLS-1$
    }

    @Test
    public void nullAndClosedEnqueuesAreNoOps() throws IOException
    {
        Path log = tmp.resolve("history.jsonl"); //$NON-NLS-1$
        McpCallHistoryFileLog sink = new McpCallHistoryFileLog(log);
        sink.enqueue(null); // no-op, must not create the file
        sink.close();
        sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 1))); // after close: dropped //$NON-NLS-1$
        sink.close(); // idempotent

        assertFalse("nothing was ever written, so the file must not exist", Files.exists(log)); //$NON-NLS-1$
    }

    @Test
    public void diskFailureIsSwallowedAndNeverPropagates() throws IOException
    {
        // Make the parent path a regular FILE so createDirectories(...) fails: the sink
        // must swallow the I/O error, write nothing, and never throw on enqueue/close.
        Path blocker = tmp.resolve("blocker"); //$NON-NLS-1$
        Files.write(blocker, new byte[] {1}); // a regular file, not a directory
        Path log = blocker.resolve("child").resolve("history.jsonl"); //$NON-NLS-1$ //$NON-NLS-2$

        try (McpCallHistoryFileLog sink = new McpCallHistoryFileLog(log))
        {
            sink.enqueue(McpCallHistoryFileLog.toJsonLine(Map.of("n", 1))); //$NON-NLS-1$
        }
        // No exception escaping the try-with-resources is the assertion; confirm nothing
        // was written under the blocker file either.
        assertFalse("no log file may be created when the directory cannot be made", Files.exists(log)); //$NON-NLS-1$
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException e)
        {
            // Best-effort cleanup of a temp directory; ignore.
        }
    }
}
