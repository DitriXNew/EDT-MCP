// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils.techlog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;

/** JSON-lines reader with bounded expanded-byte/time budgets and physical occurrence pointers. */
public final class TechLogReader
{
    public static final int MAX_EVENTS = 200_000;
    private static final int MAX_LINE = 4 * 1024 * 1024;
    private static final long CACHE_WEIGHT = 256L * 1024 * 1024;
    private static final Map<String, Index> CACHE = new LinkedHashMap<>(8, .75f, true);

    private TechLogReader() { }

    public record Source(Path file, String member, long size, long mtime, long expanded) { }
    public record Pointer(Source source, long offset, int length)
    {
        public String id() { return TechLogAnalysis.hash(source.file() + "!" + source.member() + ":" + offset); }
    }

    /** Only correlation metadata stays resident; SQL, plans and full payload are re-read on demand. */
    public record Event(Pointer pointer, LocalDateTime end, long durationUs, String name, String process,
        String node, String pid, String infobase, String session, String connection, String thread,
        String regions, String waits, String dbpid, String lkaid, String lkpid, String lksrc, int depth)
    {
        public LocalDateTime start() { return end.minusNanos(Math.multiplyExact(durationUs, 1000)); }
        public String id() { return pointer.id(); }
        public String scope() { return node + ":" + process + ":" + pid + ":" + infobase; }
    }

    public static final class Index
    {
        public final List<Event> events = new ArrayList<>();
        public final Map<String, TechLogAnalysis.Group> groups = new LinkedHashMap<>();
        public final Map<String, Integer> kinds = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        public final List<String> errors = new ArrayList<>();
        public long bytesRead, totalBytes, weight;
        public int files, readEvents, rejected, incompleteTail;
        public boolean limited;
        public String limitReason = "";
        public JsonObject manifest;
        public String manifestNote = "No capture manifest: enabled events, thresholds and other nodes are unknown.";
        public List<Source> sources;
    }

    public static LocalDateTime time(String value)
    {
        try { return OffsetDateTime.parse(value).toLocalDateTime(); }
        catch (java.time.format.DateTimeParseException e) { return LocalDateTime.parse(value); }
    }

    public static Map<String, String> parse(String line)
    {
        if (line.startsWith("\ufeff")) line = line.substring(1);
        if (line.matches("\\d{2}:\\d{2}\\..*") || line.matches("\\d{4}-\\d{2}-\\d{2}T[^,]+,.*"))
            throw new TextFormatException();
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (JsonReader reader = new JsonReader(new StringReader(line)))
        {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext())
            {
                String key = reader.nextName();
                if (values.containsKey(key)) throw new IllegalArgumentException("Duplicate property " + key);
                if (reader.peek() != JsonToken.STRING)
                    throw new IllegalArgumentException("Expected string value for " + key);
                values.put(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("Trailing JSON data");
        }
        catch (IOException e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e); }
        if (!values.containsKey("ts") || get(values, "name").isBlank())
            throw new IllegalArgumentException("Expected JSON ts and name properties");
        time(get(values, "ts"));
        long duration = number(values, "duration");
        if (duration < 0 || duration > Long.MAX_VALUE / 1000)
            throw new IllegalArgumentException("Invalid duration in microseconds");
        // Validate aggregation fields before publishing the event or any group mutations.
        if (get(values, "name").equalsIgnoreCase("CALL")) number(values, "MemoryPeak");
        number(values, "depth");
        return values;
    }

    public static String get(Map<String, String> values, String name) { return values.getOrDefault(name, ""); }
    public static long number(Map<String, String> values, String name)
    {
        String value = get(values, name);
        return value.isBlank() ? 0 : Long.parseLong(value);
    }

    public static final class TextFormatException extends IllegalArgumentException
    {
        private static final long serialVersionUID = 1L;
        TextFormatException()
        {
            super("Text-format technological log is unsupported. Capture again using <log format=\"json\" "
                + "placement=\"plain\" location=\"...\" history=\"1\"> in logcfg.xml; use manage_tech_log mode=preview.");
        }
    }

    public static List<Source> sources(Path path) throws IOException
    { return sources(path, Long.MAX_VALUE); }

    private static List<Source> sources(Path path, long deadline) throws IOException
    {
        if (!path.isAbsolute() || !Files.exists(path))
            throw new IOException("Path '" + path + "' must be an existing absolute file or directory; pass path again.");
        List<Path> files;
        if (Files.isDirectory(path))
        {
            try (var walk = Files.walk(path))
            {
                List<Path> found = new ArrayList<>();
                var iterator = walk.iterator();
                while (iterator.hasNext())
                {
                    checkDiscovery(path, deadline, found.size());
                    Path entry = iterator.next();
                    if (Files.isRegularFile(entry) && isLog(entry)) found.add(entry);
                }
                found.sort(Comparator.naturalOrder()); files = found;
            }
        }
        else files = List.of(path);
        List<Source> result = new ArrayList<>();
        for (Path file : files)
        {
            if (Files.isDirectory(path)) checkDiscovery(path, deadline, result.size());
            long size = Files.size(file), mtime = Files.getLastModifiedTime(file).toMillis();
            if (file.toString().endsWith(".zip"))
            {
                try (ZipFile zip = new ZipFile(file.toFile()))
                {
                    zipSources(path, file, size, mtime, deadline, zip.entries(), result);
                }
            }
            else result.add(new Source(file, "", size, mtime, size));
        }
        result.sort(Comparator.comparing(s -> s.file() + "!" + s.member()));
        return result;
    }

    private static void zipSources(Path path, Path file, long size, long mtime, long deadline,
        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries, List<Source> result) throws IOException
    {
        int members = 0;
        while (entries.hasMoreElements())
        {
            // Bound enumeration itself, including directories and non-log members, before fetching another entry.
            checkDiscovery(path, deadline, Math.max(members, result.size()));
            var entry = entries.nextElement();
            members++;
            if (!entry.isDirectory() && isLog(entry.getName()))
                result.add(new Source(file, entry.getName(), size, mtime, entry.getSize()));
        }
    }

    private static void checkDiscovery(Path path, long deadline, int found) throws IOException
    {
        if (System.nanoTime() >= deadline || found >= 50_000 || Thread.currentThread().isInterrupted())
            throw new IOException("File discovery budget reached at '" + path + "' after " + found
                + " log files/members; total MB is not yet known and no complete index was produced. "
                + "Use a file/subdirectory or a larger readBudgetSeconds (up to 30).");
    }

    private static boolean isLog(Path path)
    { return isLog(path.getFileName().toString()); }

    private static boolean isLog(String fileName)
    {
        String name = fileName.toLowerCase(java.util.Locale.ROOT);
        return name.matches(".*\\.log(?:\\.\\d+)?(?:\\.zip)?");
    }

    public static Index read(Path path, int budgetMb, int budgetSeconds, String from, String to) throws IOException
    {
        long deadline = System.nanoTime() + budgetSeconds * 1_000_000_000L;
        List<Source> sources = sources(path, deadline);
        LocalDateTime lower = from == null || from.isBlank() ? LocalDateTime.MIN : time(from);
        LocalDateTime upper = to == null || to.isBlank() ? LocalDateTime.MAX : time(to);
        if (lower.isAfter(upper)) throw new IllegalArgumentException("from must be <= to; use ISO date-times.");
        String signature = path + sources.toString() + budgetMb + ":" + budgetSeconds + ":" + from + ":" + to;
        synchronized (CACHE)
        {
            Index cached = CACHE.get(signature);
            if (cached != null) { loadManifest(cached, path); return cached; }
        }
        Index index = new Index(); index.sources = List.copyOf(sources);
        index.weight = sources.size() * 1024L;
        index.totalBytes = sources.stream().mapToLong(Source::expanded).sum();
        index.files = (int)sources.stream().map(Source::file).distinct().count();
        long budget = budgetMb * 1024L * 1024;
        for (Source source : sources)
        {
            if (index.limited) break;
            try (var zip = source.member().isEmpty() ? null : new ZipFile(source.file().toFile());
                InputStream input = new BufferedInputStream(zip == null ? Files.newInputStream(source.file())
                    : zip.getInputStream(zip.getEntry(source.member()))))
            {
                scan(input, source, index, budget, deadline, lower, upper);
            }
        }
        if (!index.limited && !sources.equals(sources(path, deadline)))
        {
            index.limited = true;
            index.limitReason = "Log files changed during the scan; repeat summary after stopping capture";
        }
        loadManifest(index, path);
        synchronized (CACHE)
        {
            // Partial scans are not cached: a subsequent call can use its full time budget.
            if (!index.limited) CACHE.put(signature, index);
            long weight = CACHE.values().stream().mapToLong(i -> i.weight).sum();
            while (CACHE.size() > 4 || weight > CACHE_WEIGHT)
            {
                var first = CACHE.entrySet().iterator();
                weight -= first.next().getValue().weight; first.remove();
            }
        }
        return index;
    }

    private static void loadManifest(Index index, Path path)
    {
        Path manifest = (Files.isDirectory(path) ? path : path.getParent()).resolve("edt-mcp-capture.json");
        index.manifest = null;
        index.manifestNote = "No capture manifest: enabled events, thresholds and other nodes are unknown.";
        if (Files.exists(manifest))
        {
            try
            {
                if (Files.size(manifest) > 1024 * 1024) throw new IOException("Manifest exceeds 1 MB");
                index.manifest = com.google.gson.JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
                index.manifestNote = "Capture manifest: " + manifest;
            }
            catch (Exception e) { index.manifestNote = "Unreadable capture manifest: " + e.getMessage() + "; coverage unknown."; }
        }
    }

    private static void scan(InputStream input, Source source, Index index, long budget, long deadline,
        LocalDateTime lower, LocalDateTime upper) throws IOException
    {
        long position = 0;
        while (true)
        {
            if (index.bytesRead >= budget || System.nanoTime() >= deadline || index.events.size() >= MAX_EVENTS
                || index.weight >= CACHE_WEIGHT / 2 || Thread.currentThread().isInterrupted())
            {
                index.limited = true;
                index.limitReason = "Read byte/time/index-memory budget reached or request interrupted";
                return;
            }
            long offset = position;
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean newline = false, oversized = false;
            int value;
            while ((value = input.read()) != -1)
            {
                position++; index.bytesRead++;
                if (value == '\n') { newline = true; break; }
                if (line.size() < MAX_LINE) line.write(value); else oversized = true;
                if ((position & 4095) == 0 && (index.bytesRead >= budget || System.nanoTime() >= deadline
                    || Thread.currentThread().isInterrupted()))
                { index.limited = true; index.limitReason = "Read byte/time budget reached within a line"; return; }
            }
            if (position == offset) break;
            String text = line.toString(StandardCharsets.UTF_8);
            if (text.isBlank()) continue;
            try
            {
                if (oversized) throw new IllegalArgumentException("Line exceeds 4 MB; narrow capture properties");
                Map<String, String> props = parse(text);
                index.readEvents++;
                LocalDateTime end = time(get(props, "ts"));
                // Include intersecting intervals, including long parents ending after 'to'.
                LocalDateTime start = end.minusNanos(Math.multiplyExact(number(props, "duration"), 1000));
                if (end.isBefore(lower) || start.isAfter(upper)) continue;
                Event event = new Event(new Pointer(source, offset, line.size()), end, number(props, "duration"),
                    get(props, "name").toUpperCase(java.util.Locale.ROOT), get(props, "process"),
                    nodeScope(source), processId(source, props),
                    get(props, "p:processName"), get(props, "SessionID"), get(props, "t:connectID"),
                    get(props, "OSThread"), get(props, "Regions"), get(props, "WaitConnections"), get(props, "dbpid"),
                    get(props, "lkaid"), get(props, "lkpid"), get(props, "lksrc"), (int)number(props, "depth"));
                index.events.add(event);
                index.kinds.merge(event.name(), 1, Integer::sum);
                index.weight += 512L + text.length() / 8;
                TechLogAnalysis.aggregate(index, event, props);
            }
            catch (TextFormatException e) { throw e; }
            catch (RuntimeException e)
            {
                if (!newline && !oversized && isIncomplete(e)) index.incompleteTail++;
                else
                {
                    index.rejected++;
                    if (index.errors.size() < 3) index.errors.add(source.file() + ":" + offset + " "
                        + e.getMessage() + "; sample=" + text.substring(0, Math.min(160, text.length())));
                }
            }
        }
    }

    private static boolean isIncomplete(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof java.io.EOFException || cause.getMessage() != null
                && (cause.getMessage().contains("Unterminated") || cause.getMessage().contains("End of input"))) return true;
        return false;
    }

    private static String nodeScope(Source source)
    {
        Path parent = source.file().getParent();
        if (parent.getFileName() != null && parent.getFileName().toString().matches("[A-Za-z0-9]+_\\d+") && parent.getParent() != null)
            parent = parent.getParent();
        return parent.toString();
    }

    private static String processId(Source source, Map<String, String> properties)
    {
        // Plain files and size rotations encode producer PID; folders do so in the parent name.
        for (String name : List.of(source.file().getFileName().toString(), source.file().getParent().toString().replace('\\', '/').replaceAll(".*/", "")))
        {
            var match = java.util.regex.Pattern.compile("^[A-Za-z0-9]+_(\\d+)(?:_|\\.log|$)").matcher(name);
            if (match.find()) return match.group(1);
        }
        return !get(properties, "PID").isBlank() ? get(properties, "PID") : source.file().getFileName().toString();
    }

    public static Map<String, String> reread(Pointer pointer) throws IOException
    {
        try (ReadSession session = new ReadSession(512, 30)) { return session.read(pointer); }
    }

    /** Keeps one zip member open while walking pointers; decompressed prefixes count against the budget. */
    public static final class ReadSession implements AutoCloseable
    {
        private final long maximum, deadline;
        private long bytes;
        private Source source;
        private RandomAccessFile plain;
        private ZipFile zip;
        private InputStream input;
        private long position;
        public ReadSession(int mb, int seconds)
        { maximum = mb * 1048576L; deadline = System.nanoTime() + seconds * 1_000_000_000L; }

        public Map<String, String> read(Pointer pointer) throws IOException
        {
            check(0);
            Source next = pointer.source();
            if (Files.size(next.file()) != next.size() || Files.getLastModifiedTime(next.file()).toMillis() != next.mtime())
                throw new IOException("Log changed: " + next.file() + ". Repeat summary and use its current occurrence ids.");
            if (!next.equals(source) || !next.member().isEmpty() && pointer.offset() < position)
            {
                close(); source = next; position = 0;
                if (source.member().isEmpty()) plain = new RandomAccessFile(source.file().toFile(), "r");
                else { zip = new ZipFile(source.file().toFile()); input = new BufferedInputStream(zip.getInputStream(zip.getEntry(source.member()))); }
            }
            byte[] buffer = new byte[pointer.length()];
            if (plain != null)
            { check(buffer.length); plain.seek(pointer.offset()); plain.readFully(buffer); bytes += buffer.length; }
            else
            {
                byte[] discard = new byte[8192];
                while (position < pointer.offset())
                {
                    int want = (int)Math.min(discard.length, pointer.offset() - position); check(want);
                    int read = input.read(discard, 0, want);
                    if (read < 0) throw new IOException("Truncated zip member; repeat summary: " + source.file());
                    position += read; bytes += read;
                }
                for (int at = 0; at < buffer.length;)
                {
                    int want = Math.min(8192, buffer.length - at); check(want);
                    int read = input.read(buffer, at, want);
                    if (read < 0) throw new IOException("Truncated pointed line; repeat summary: " + source.file());
                    at += read; position += read; bytes += read;
                }
            }
            return parse(new String(buffer, StandardCharsets.UTF_8));
        }

        private void check(int next) throws IOException
        {
            if (bytes + next > maximum || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted())
                throw new IOException(String.format(java.util.Locale.ROOT,
                    "Detail/filter read budget reached: read %.3f of %.3f MB budget (zip prefixes included). "
                    + "No complete result. Narrow from/to, a subdirectory or page limit/offset, or increase readBudgetMb/readBudgetSeconds",
                    bytes / 1048576.0, maximum / 1048576.0));
        }

        @Override public void close() throws IOException
        {
            try { if (plain != null) plain.close(); if (input != null) input.close(); }
            finally { if (zip != null) zip.close(); plain = null; input = null; zip = null; source = null; }
        }
    }
}
