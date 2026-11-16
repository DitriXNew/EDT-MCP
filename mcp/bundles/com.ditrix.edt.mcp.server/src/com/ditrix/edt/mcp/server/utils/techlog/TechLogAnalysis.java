// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.utils.techlog;

import static com.ditrix.edt.mcp.server.utils.techlog.TechLogReader.get;
import static com.ditrix.edt.mcp.server.utils.techlog.TechLogReader.number;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader.Event;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader.Index;

/** Aggregate, coverage and conservative interval/identity correlation; never presents a heuristic as a cause. */
public final class TechLogAnalysis
{
    public static final Set<String> PROBLEMS = Set.of("exceptions", "query-errors", "process-crashes", "lock-timeouts",
        "managed-deadlocks", "lock-waits", "dbms-locks", "slow-db", "slow-calls", "memory-peaks");
    private static final java.util.regex.Pattern CRASH = java.util.regex.Pattern.compile(
        "crash|abnormal|fatal|unexpected|exception|\\u0430\\u0432\\u0430\\u0440|\\u043d\\u0435\\u0448\\u0442\\u0430\\u0442");
    private static final Set<String> DB = Set.of("DBMSSQL", "DBPOSTGRS", "DBV8DBENG", "SDBL");
    private static final Pattern SQL_TOKEN = Pattern.compile(
        "--[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[NEXB]?'(?:''|\\\\.|[^'\\\\])*'|"
        + "\\$([A-Za-z_][A-Za-z_0-9]*|)\\$[\\s\\S]*?\\$\\1\\$|\"(?:\"\"|[^\"])*\"|\\[(?:]]|[^]])*]|"
        + "#t(?:t\\d+|[0-9a-f]{32})(?![\\w])|(?<![\\w])\\d+:[0-9a-f]{32}(?![\\w])|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|(?:@|&)\\w+|0x[0-9a-f]+|(?<![\\w])\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?(?![\\w])",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern PARAMETERS = Pattern.compile("(?is)\\n\\s*(?:Parameters|Params)\\s*:.*$");
    private static final Pattern QUOTED_OR_SPACE = Pattern.compile("\"(?:\"\"|[^\"])*\"|\\[(?:]]|[^]])*]|\\s+");

    private TechLogAnalysis() { }

    public static String normalizeSql(String sql)
    {
        // SQL parameter dumps follow the statement; retain original in the pointed event.
        var parameters = PARAMETERS.matcher(sql);
        if (parameters.find()) sql = sql.substring(0, parameters.start());
        // lookingAt only where a token can start: find() would try every alternative at every position.
        var matcher = SQL_TOKEN.matcher(sql).useTransparentBounds(true).useAnchoringBounds(false);
        StringBuilder out = new StringBuilder(Math.min(sql.length(), 1 << 16));
        Map<String, String> tables = new LinkedHashMap<>();
        int last = 0;
        for (int at = 0; at < sql.length();)
        {
            if (!tokenMayStart(sql, at) || !matcher.region(at, sql.length()).lookingAt()) { at++; continue; }
            appendCollapsed(out, sql, last, matcher.start());
            String token = matcher.group(), replacement;
            if (token.startsWith("--") || token.startsWith("/*")) replacement = " ";
            else if (token.startsWith("\"") || token.startsWith("[")) replacement = token;
            // MSSQL #ttN and file-infobase #t<32 hex> temp tables; numbering keeps self-joins apart.
            else if (token.startsWith("#"))
                replacement = tables.computeIfAbsent(token.toLowerCase(Locale.ROOT), k -> "#temp" + (tables.size() + 1));
            else replacement = "?";
            out.append(replacement);
            last = at = matcher.end();
        }
        appendCollapsed(out, sql, last, sql.length());
        return QUOTED_OR_SPACE.matcher(out.toString())
            .replaceAll(m -> m.group().isBlank() ? " " : java.util.regex.Matcher.quoteReplacement(m.group())).trim();
    }

    /** A superset of the first characters SQL_TOKEN can match at, honouring its look-behinds. */
    static boolean tokenMayStart(String s, int i)
    {
        char c = s.charAt(i);
        if ("-/'$\"[#@&".indexOf(c) >= 0) return true;
        int n = s.length();
        boolean uuid = i + 8 < n && s.charAt(i + 8) == '-';
        if (c >= '0' && c <= '9')
            return uuid || i == 0 || !isWord(s.charAt(i - 1)) || c == '0' && i + 1 < n && (s.charAt(i + 1) | 0x20) == 'x';
        char lower = (char)(c | 0x20);
        if ("nexb".indexOf(lower) >= 0 && i + 1 < n && s.charAt(i + 1) == '\'') return true;
        return lower >= 'a' && lower <= 'f' && uuid;
    }

    private static boolean isWord(char c)
    { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_'; }

    /** Lower-cases and collapses \s runs (the ASCII regex class) to one space, without a regex per segment. */
    private static void appendCollapsed(StringBuilder out, String s, int from, int to)
    {
        if (from >= to) return;
        String segment = s.substring(from, to).toLowerCase(Locale.ROOT);
        boolean space = false;
        for (int i = 0; i < segment.length(); i++)
        {
            char c = segment.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r') { space = true; continue; }
            if (space) { out.append(' '); space = false; }
            out.append(c);
        }
        if (space) out.append(' ');
    }

    public static String hash(String key)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static final class Group
    {
        public final String id, problem, key;
        public final int severity;
        public final List<Event> occurrences = new ArrayList<>();
        public double totalUs;
        public long maxUs, memoryPeak;
        Group(String problem, String key, int severity)
        { this.problem = problem; this.key = key; this.severity = severity; id = hash(problem + ":" + key); }
    }

    static void aggregate(Index index, Event event, Map<String, String> p)
    {
        String name = event.name();
        switch (name)
        {
            case "EXCP" -> add(index, event, "exceptions", normalizeSql(get(p, "Descr")), 90, 0);
            case "QERR" -> add(index, event, "query-errors", normalizeSql(get(p, "Descr")), 85, 0);
            case "PROC" -> {
                String state = get(p, "Func") + " " + get(p, "Descr") + " " + get(p, "Txt") + " " + get(p, "Event");
                if (CRASH.matcher(state.toLowerCase(Locale.ROOT)).find())
                    add(index, event, "process-crashes", normalizeSql(state), 100, 0);
            }
            case "TTIMEOUT" -> add(index, event, "lock-timeouts", event.regions(), 95, 0);
            case "TDEADLOCK" -> add(index, event, "managed-deadlocks", get(p, "DeadlockConnectionIntersections"), 98, 0);
            case "TLOCK" -> {
                if (!event.waits().isBlank()) add(index, event, "lock-waits", event.regions(), 75, 0);
            }
            case "CALL" -> {
                add(index, event, "slow-calls", get(p, "Context"), 50, 0);
                long memory = number(p, "MemoryPeak");
                if (memory > 0) add(index, event, "memory-peaks", get(p, "Context"), 40, memory);
            }
            default -> { }
        }
        String statement = name.equals("SDBL") && !get(p, "Sdbl").isBlank() ? get(p, "Sdbl") : get(p, "Sql");
        if (DB.contains(name) && !statement.isBlank())
            add(index, event, "slow-db", name + ": " + normalizeSql(statement), 60, 0);
        if (get(p, "lka").equals("1") || get(p, "lkp").equals("1"))
            add(index, event, "dbms-locks", "source=" + get(p, "lka") + "; victim=" + get(p, "lkp")
                + "; source connection=" + get(p, "lksrc"), 80, 0);
    }

    private static void add(Index index, Event e, String problem, String key, int severity, long memory)
    {
        String id = hash(problem + ":" + key);
        Group g = index.groups.computeIfAbsent(id, k -> new Group(problem, key, severity));
        g.occurrences.add(e); g.totalUs += e.durationUs(); g.maxUs = Math.max(g.maxUs, e.durationUs());
        g.memoryPeak = Math.max(g.memoryPeak, memory);
        index.weight += 16;
        if (g.occurrences.size() == 1) index.weight += key.length() * 2L + 256;
    }

    public static List<Group> ranked(Index index)
    {
        return index.groups.values().stream().sorted(Comparator.comparingInt((Group g) -> g.severity).reversed()
            .thenComparing(Comparator.comparingDouble((Group g) -> g.problem.equals("memory-peaks")
                ? g.memoryPeak : g.totalUs).reversed()).thenComparing(g -> g.id)).toList();
    }

    public static String summary(Index index, int limit, int offset)
    {
        StringBuilder out = new StringBuilder("# Technological log summary\n\n");
        out.append(header(index));
        List<Group> ranked = ranked(index);
        List<Group> all = ranked.stream().filter(g -> g.maxUs >= 1000
            || !List.of("slow-calls", "slow-db").contains(g.problem)).toList();
        int omitted = ranked.size() - all.size();
        int start = Math.min(offset, all.size()), end = Math.min(all.size(), start + limit);
        out.append("\n## Problems\n\nRanked by incident severity, then cumulative elapsed time (memory by peak). "
            + "Slow groups are candidates, not proven faults; nested DB/SDBL/CALL times overlap.\n\n")
            .append("Groups: ").append(all.size()).append(Pagination.pageNotice(start, end - start, all.size())).append("\n\n")
            .append(MarkdownUtils.tableHeader("groupId", "problemId (describe)", "Pattern / context", "Count", "Total ms", "Avg ms", "Max ms", "Peak bytes"));
        for (Group g : all.subList(start, end))
            out.append(MarkdownUtils.tableRow(g.id, g.problem, clip(g.key, MAX_PATTERN_CHARS), "" + g.occurrences.size(), ms(g.totalUs),
                ms(g.totalUs / g.occurrences.size()), ms(g.maxUs), "" + g.memoryPeak));
        out.append("\n");
        if (omitted > 0) out.append(omitted).append(" slow groups under 1 ms omitted; manage_tech_log mode=export exportKind=groups lists every group.\n\n");
        out.append(coverage(index));
        return out.toString();
    }

    public static String header(Index index)
    {
        TreeSet<String> processes = new TreeSet<>();
        LocalDateTime first = null, last = null;
        for (Event e : index.events)
        {
            processes.add(e.process());
            if (first == null || e.start().isBefore(first)) first = e.start();
            if (last == null || e.end().isAfter(last)) last = e.end();
        }
        String text = "Files: " + index.files + "; events read: " + index.readEvents + "; events indexed: " + index.events.size() + "; rejected: " + index.rejected
            + "; incomplete-tail: " + index.incompleteTail + ". Read " + mb(index.bytesRead) + " of " + mb(index.totalBytes)
            + " MB (expanded JSON bytes, including zip members)"
            + (index.skippedFiles > 0 ? "; " + index.skippedFiles + " file(s) ending before from skipped unread" : "")
            + ".\n\nTime span: " + first + " → " + last
            + ". Event times are local to the machine that wrote them.\n\nProcesses: " + MarkdownUtils.escapeMarkdown(processes.toString())
            + (index.infobases.isEmpty() ? index.processNames.isEmpty()
                ? "; infobase: not recorded (file infobases do not write IB)"
                : "; IB not recorded; p:processName: " + MarkdownUtils.escapeMarkdown(index.processNames.toString())
                    + " (infobase names for server processes)"
                : "; infobases: " + MarkdownUtils.escapeMarkdown(index.infobases.toString())) + ".\n";
        if (index.limited) text += "\nINCOMPLETE: " + index.limitReason + ".\n";
        if (index.rejected > 0) text += "\nCorrupt samples (showing " + index.errors.size() + " of " + index.rejected
            + "): " + MarkdownUtils.escapeMarkdown(String.join("; ", index.errors)) + "\n";
        return text;
    }

    public static String coverage(Index index)
    {
        Set<String> enabled = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String profile = "unknown";
        boolean known = false;
        try
        {
            if (index.manifest != null)
            {
                profile = index.manifest.get("profile").getAsString();
                index.manifest.getAsJsonArray("events").forEach(e -> enabled.add(e.getAsString()));
                known = true;
            }
        }
        catch (RuntimeException e) { /* Foreign/old manifest: do not invent enablement. */ }
        StringBuilder out = new StringBuilder("## Coverage\n\n" + index.manifestNote
            + "\n\nAbsent ≠ no problem. Duration/infobase filters, unfinished operations, generating elements, version and missing nodes can hide evidence.\n\n");
        if (index.manifest != null) out.append("Capture settings: ").append(MarkdownUtils.escapeMarkdown(captureSettings(index.manifest))).append("\n\n");
        Set<String> names = new TreeSet<>(TechLogKnowledge.entries("events").keySet()); names.addAll(index.kinds.keySet());
        List<String> present = new ArrayList<>(), silent = new ArrayList<>(), off = new ArrayList<>();
        for (String kind : names)
        {
            int count = index.kinds.getOrDefault(kind, 0);
            if (count > 0) present.add(kind + " " + count);
            else if (known && !enabled.contains(kind)) off.add(kind);
            else silent.add(kind);
        }
        out.append("- Present: ").append(present.isEmpty() ? "none" : String.join(", ", present)).append("\n");
        List<String> incomplete = new ArrayList<>();
        if (index.limited) incomplete.add("before the scan stopped");
        if (index.incompleteTail > 0) incomplete.add("unfinished tail");
        if (index.rejected > 0) incomplete.add("rejected records");
        out.append("- ").append(!known ? "Absent, capture enablement unknown: "
            : !incomplete.isEmpty() ? "Enabled, none seen in incomplete input (" + String.join("; ", incomplete) + "): "
                : "Enabled, none occurred in the collected data: ")
            .append(silent.isEmpty() ? "none" : String.join(", ", silent)).append("\n");
        if (known) out.append("- Not enabled by profile ").append(profile).append(": ")
            .append(off.isEmpty() ? "none" : String.join(", ", off)).append("\n");
        return out.toString();
    }

    public static String captureSettings(com.google.gson.JsonObject m)
    {
        StringBuilder out = new StringBuilder();
        for (String key : List.of("profile", "startTime", "stopTime", "status"))
            if (m.has(key)) out.append(key).append('=').append(key.endsWith("Time")
                ? localCaptureTime(m.get(key).getAsString()) : m.get(key).getAsString()).append("; ");
        if (m.has("overrides")) m.getAsJsonObject("overrides").entrySet()
            .forEach(e -> out.append(e.getKey()).append('=').append(e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : e.getValue().toString()).append("; "));
        return out.toString().trim();
    }

    private static String localCaptureTime(String instant)
    {
        try
        {
            return java.time.Instant.parse(instant).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT));
        }
        catch (java.time.format.DateTimeParseException e) { return instant + " (unrecognized capture time)"; }
    }

    public static List<Event> details(Index index, String groupId)
    {
        Group group = index.groups.get(groupId);
        if (group == null) throw new IllegalArgumentException("Unknown groupId '" + groupId + "'. Repeat summary for this path and filters.");
        return group.occurrences;
    }

    public record Chain(List<Event> events, String completeness) { }

    public static Chain chain(Index index, String occurrenceId) throws IOException
    {
        Event selected = index.events.stream().filter(e -> e.id().equals(occurrenceId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown occurrenceId '" + occurrenceId + "'. Use details or events first."));
        List<Event> parents = index.events.stream().filter(e -> e.name().equals("CALL") && (e.equals(selected) || sameLane(e, selected))
            && !e.start().isAfter(selected.start()) && !e.end().isBefore(selected.end()))
            .sorted(Comparator.comparingLong(Event::durationUs).reversed()).toList();
        Event root = parents.isEmpty() ? selected : parents.get(0);
        List<Event> result = new ArrayList<>();
        result.add(selected);
        for (Event e : index.events)
            if (!e.equals(selected) && sameLane(e, root) && !e.end().isBefore(root.start()) && !e.start().isAfter(root.end())) result.add(e);
        StringBuilder note = new StringBuilder("Correlation is an interval/identity candidate, not a proven distributed trace. ");
        note.append("Node scope is inferred from source directories; keep nodes separate when copying logs. ");
        if (parents.isEmpty()) note.append("INCOMPLETE: missing parent CALL. ");
        if (selected.pid().equals(selected.pointer().source().file().getFileName().toString()))
            note.append("INCOMPLETE: explicit process identity unavailable; file-scoped correlation cannot bridge rotation. ");
        if (selected.thread().isBlank()) note.append("INCOMPLETE: thread missing; session/connection candidates may include concurrent work. ");
        if (index.limited || index.rejected > 0 || index.incompleteTail > 0) note.append("INCOMPLETE: partial or rejected input. ");
        note.append("INCOMPLETE: other node not collected/coverage unproven; duration filtering may remove parents and lock holders. ");
        // Index links once; rescanning every event for every waiter is quadratic on a busy capture.
        Map<List<String>, List<Event>> dbSources = new LinkedHashMap<>(), managed = new LinkedHashMap<>();
        for (Event e : index.events)
        {
            if (e.end().isBefore(root.start()) || e.start().isAfter(root.end())) continue;
            if (!e.infobase().isBlank() && !e.dbpid().isBlank())
                for (String poll : tokens(e.lkaid()))
                    if (!poll.isBlank()) dbSources.computeIfAbsent(List.of(e.node(), e.infobase(), e.dbpid(), poll), k -> new ArrayList<>()).add(e);
            if (!e.connection().isBlank() && !e.regions().isBlank())
                managed.computeIfAbsent(List.of(e.scope(), e.connection(), e.regions()), k -> new ArrayList<>()).add(e);
        }
        Set<List<String>> seenDb = new java.util.HashSet<>(), seenManaged = new java.util.HashSet<>();
        Set<Event> linked = new java.util.LinkedHashSet<>(result);
        Set<String> dbIds = new TreeSet<>(), managedIds = new TreeSet<>();
        int missingDb = 0, missingManaged = 0;
        for (Event victim : result)
        {
            if (!victim.lkpid().isBlank() && !victim.lksrc().isBlank())
            {
                List<String> key = List.of(victim.node(), victim.infobase(), victim.lksrc(), victim.lkpid());
                if (seenDb.add(key))
                {
                    List<Event> sources = dbSources.getOrDefault(key, List.of());
                    if (sources.isEmpty()) missingDb++;
                    for (Event e : sources) { linked.add(e); dbIds.add(e.id()); }
                }
            }
            for (String connection : tokens(victim.waits()))
            {
                if (connection.isBlank()) continue;
                List<String> key = List.of(victim.scope(), connection, victim.regions());
                if (seenManaged.add(key))
                {
                    List<Event> sources = managed.getOrDefault(key, List.of());
                    if (sources.isEmpty()) missingManaged++;
                    for (Event e : sources) { linked.add(e); managedIds.add(e.id()); }
                }
            }
        }
        if (!dbIds.isEmpty()) note.append("Observed DBMS sources ").append(idList(dbIds))
            .append(" via lkpid/lkaid and lksrc/dbpid; carrier need not be acquiring SQL. ");
        if (!managedIds.isEmpty()) note.append("Managed holder candidates ").append(idList(managedIds))
            .append("; resource values, modes and transaction lifetime still require verification. ");
        if (missingDb > 0 || missingManaged > 0) note.append("INCOMPLETE: unresolved holder links: DBMS=")
            .append(missingDb).append(", managed=").append(missingManaged).append(". ");
        result = new ArrayList<>(linked);
        result.sort(Comparator.comparing(Event::start).thenComparing(Comparator.comparingLong(Event::durationUs).reversed()));
        return new Chain(List.copyOf(result), note.toString());
    }

    private static Set<String> tokens(String value)
    { return new java.util.HashSet<>(List.of(value.split("[,;\\s]+"))); }

    private static String idList(Set<String> ids)
    {
        List<String> all = new ArrayList<>(ids);
        int shown = Math.min(20, all.size());
        return String.join(", ", all.subList(0, shown)) + Pagination.truncationNotice(shown, all.size());
    }

    private static boolean sameLane(Event a, Event b)
    {
        if (!a.scope().equals(b.scope())) return false;
        if (!a.connection().isBlank() && !b.connection().isBlank() && !a.connection().equals(b.connection())) return false;
        if (!a.session().isBlank() && !b.session().isBlank() && !a.session().equals(b.session())) return false;
        if (!a.thread().isBlank() && !b.thread().isBlank()) return a.thread().equals(b.thread());
        return !a.connection().isBlank() && a.connection().equals(b.connection())
            || !a.session().isBlank() && a.session().equals(b.session());
    }

    public static String renderEvents(List<Event> events, int limit, int offset) throws IOException
    {
        try (var session = new TechLogReader.ReadSession(64, 10)) { return renderEvents(events, limit, offset, session); }
    }

    public static String renderEvents(List<Event> events, int limit, int offset, TechLogReader.ReadSession session) throws IOException
    {
        return renderEvents(events, limit, offset, session, null);
    }

    public static String renderEvents(List<Event> events, int limit, int offset, TechLogReader.ReadSession session,
        LocalDateTime anchorStart) throws IOException
    {
        int start = Math.min(offset, events.size()), end = Math.min(events.size(), start + limit);
        StringBuilder out = new StringBuilder("Occurrences: " + events.size()
            + Pagination.pageNotice(start, end - start, events.size()) + "\n\n");
        List<String> columns = new ArrayList<>(List.of("occurrenceId", "End time", "Duration ms", "Event", "Process", "Session", "User", "Connection", "Context", "SQL / query", "Plan", "All properties / provenance"));
        if (anchorStart != null) columns.add(1, "Offset from anchor start ms");
        out.append(MarkdownUtils.tableHeader(columns.toArray(String[]::new)));
        int rendered = 0;
        for (Event e : events.subList(start, end))
        {
            if (rendered > 0 && out.length() > MAX_OUTPUT_CHARS)
            {
                out.append("\nOutput budget reached after ").append(rendered).append(" rows; continue with offset=")
                    .append(start + rendered).append(".\n");
                break;
            }
            Map<String, String> p = session.read(e.pointer());
            Map<String, String> clipped = new java.util.LinkedHashMap<>();
            p.forEach((key, value) -> clipped.put(key, clip(value, MAX_PROPERTY_CHARS)));
            List<String> cells = new ArrayList<>(List.of(e.id(), e.end().toString(), ms(e.durationUs()), e.name(), e.process(),
                e.session(), get(p, "Usr"), e.connection(), clip(get(p, "Context"), MAX_TEXT_CHARS),
                clip(get(p, "Sql") + get(p, "Sdbl") + get(p, "Query"), MAX_TEXT_CHARS),
                clip(get(p, "planSQLText"), MAX_TEXT_CHARS), clipped + " @ " + e.pointer().source().file().getFileName()
                + (e.pointer().source().member().isEmpty() ? "" : "!" + e.pointer().source().member()) + "#" + e.pointer().offset()));
            if (anchorStart != null)
            {
                java.time.Duration delta = java.time.Duration.between(anchorStart, e.start());
                double microseconds = delta.getSeconds() * 1_000_000.0 + delta.getNano() / 1000.0;
                cells.add(1, (delta.isNegative() ? "" : "+") + ms(microseconds) + " ms");
            }
            out.append(MarkdownUtils.tableRow(cells.toArray(String[]::new)));
            rendered++;
        }
        return out.toString();
    }

    /** Real queries run to tens of KB; cells keep a prefix and the length, the CSV export keeps the full text. */
    public static final int MAX_PATTERN_CHARS = 400, MAX_TEXT_CHARS = 4000, MAX_PROPERTY_CHARS = 300, MAX_OUTPUT_CHARS = 80_000;

    static String clip(String text, int max)
    {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, max) + "… [" + text.length() + " chars; full text: manage_tech_log mode=export]";
    }

    public static String ms(double microseconds) { return String.format(Locale.ROOT, "%.3f", microseconds / 1000); }
    private static String mb(long bytes) { return String.format(Locale.ROOT, "%.3f", bytes / 1048576.0); }
}
