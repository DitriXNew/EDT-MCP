/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.profiling.core.ILineProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.ServerCallSignal;
import com._1c.g5.v8.dt.profiling.core.compare.CompareSide;
import com._1c.g5.v8.dt.profiling.core.compare.ICompareResult;
import com._1c.g5.v8.dt.profiling.core.compare.IElementCompareResult;
import com._1c.g5.v8.dt.profiling.core.compare.ILineCompareResult;
import com._1c.g5.v8.dt.profiling.core.compare.IMethodCompareResult;
import com._1c.g5.v8.dt.profiling.core.compare.IUnmatchedCompareResult;

/** Pure aggregation and Markdown rendering. EDT durability getters hold ticks, not durations. */
public final class ProfilingReport
{
    private static final String NA_NOTICE = "n/a identifies methods/source unavailable in this workspace "
        + "(platform modules or other projects); their records can aggregate under n/a. "
        + "This is not a defect of the measurement.\n\n";
    private ProfilingReport() { }

    public record Row(String moduleKey, String module, String method, int line, long calls,
        double time, double pure, double percent, double purePercent, ServerCallSignal signal,
        String target, Map<String, Double> times, Map<String, Double> pureTimes, String source, boolean unresolved) { }

    public record Rollup(String module, String method, double time, double pure, long lineCalls) { }

    public static String ms(double value) { return number(value) + " ms"; }
    public static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    public static String percentage(double value) { return String.format(Locale.ROOT, "%.2f", value); }
    private static String text(Object value) { return value == null ? "" : value.toString(); }
    private static double millis(Duration duration)
    {
        return duration == null ? 0 : duration.getSeconds() * 1000.0 + duration.getNano() / 1_000_000.0;
    }

    public static List<Row> rows(IProfilingResult result)
    {
        List<Row> rows = new ArrayList<>();
        for (ILineProfilingResult line : result.getProfilingResults())
        {
            Map<String, Double> times = new LinkedHashMap<>();
            Map<String, Double> pureTimes = new LinkedHashMap<>();
            for (DebugTargetType type : result.getDebugTargets())
            {
                times.put(type.getName(), millis(line.getTimeForDebugTarget(type)));
                pureTimes.put(type.getName(), millis(line.getPureTimeForDebugTarget(type)));
            }
            String module = moduleName(line.getModuleName(), line.getModuleID(), line.getProject());
            boolean unresolved = missingLabel(line.getModuleName());
            String project = line.getProject() == null ? "" : line.getProject().getName();
            String identity = line.getModuleID() == null ? module
                : text(line.getModuleID().getObjectID()) + ":" + text(line.getModuleID().getPropertyID());
            rows.add(new Row(project + ":" + identity, module, unresolved ? "n/a" : resolvedText(line.getMethodSignature()),
                line.getLineNo(), line.getFrequency(), times.values().stream().mapToDouble(Double::doubleValue).sum(),
                pureTimes.values().stream().mapToDouble(Double::doubleValue).sum(), line.getPercentage(),
                line.getPurePercentage(), line.getServerCallSignal(), text(line.getDebugTargetType()),
                times, pureTimes, unresolved ? "n/a" : resolvedText(line.getLine()), unresolved));
        }
        return rows;
    }

    /** EDT's unresolved labels are angle-bracket placeholders in both supported locales. */
    private static boolean missingLabel(String value)
    {
        return value == null || value.isBlank() || (value.startsWith("<") && value.endsWith(">"));
    }

    private static String resolvedText(String value) { return missingLabel(value) ? "n/a" : value; }

    private static String moduleName(String name, BSLModuleIdInternal id, IProject project)
    {
        if (!missingLabel(name)) { return name; }
        return "project=" + (project == null ? "n/a" : project.getName()) + "; objectId="
            + (id == null ? "n/a" : text(id.getObjectID())) + "; propertyId="
            + (id == null ? "n/a" : text(id.getPropertyID()));
    }

    private static String compareModule(IElementCompareResult element, IProject project)
    {
        return moduleName(element.getModuleName(), element.getModuleId(), project);
    }

    public static String unresolvedNotice(List<Row> rows)
    {
        long missing = rows.stream().filter(Row::unresolved).count();
        return missing == 0 ? "" : missing + " of " + rows.size()
            + " line records belong to modules not found in the workspace (for example, extensions installed only in the infobase); they are identified by module id.\n\n";
    }

    public static boolean matches(String value, String filter)
    {
        return filter == null || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    public static List<Row> filter(List<Row> rows, String module, String method, int frequency)
    {
        return rows.stream().filter(r -> matches(r.module(), module) && matches(r.method(), method)
            && r.calls() >= frequency).toList();
    }

    public static List<Row> byPure(List<Row> rows)
    {
        return rows.stream().sorted(Comparator.comparingDouble(Row::pure).reversed()
            .thenComparing(Row::moduleKey).thenComparing(Row::method).thenComparingInt(Row::line)
            .thenComparing(Row::target)).toList();
    }

    public static List<Rollup> rollups(List<Row> rows, boolean methods)
    {
        Map<List<String>, Rollup> sums = new LinkedHashMap<>();
        for (Row row : rows)
        {
            List<String> key = List.of(row.moduleKey(), methods ? row.method() : "");
            Rollup old = sums.getOrDefault(key, new Rollup(row.module(), methods ? row.method() : "", 0, 0, 0));
            sums.put(key, new Rollup(old.module(), old.method(), old.time() + row.time(),
                old.pure() + row.pure(), old.lineCalls() + row.calls()));
        }
        return sums.values().stream().sorted(Comparator.comparingDouble(Rollup::pure).reversed()
            .thenComparing(Rollup::module).thenComparing(Rollup::method)).toList();
    }

    public static String identity(IProfilingResult result)
    {
        return "Measurement: " + MarkdownUtils.inlineCode(result.getName()) + "; date: "
            + result.getDateOfSession() + "; id: " + result.getUuid() + ". Time unit: ms.\n\n";
    }

    public static String count(int shown, int total, String more)
    {
        String shared = Pagination.truncationNotice(shown, total);
        return (shared.isEmpty() ? " (showing " + shown + " of " + total + ")" : shared)
            + (shown < total ? ". " + more : "") + "\n\n";
    }

    public static <T> List<T> page(List<T> values, int offset, int limit)
    {
        int from = Math.min(offset, values.size());
        return values.subList(from, from + Math.min(limit, values.size() - from));
    }

    public static String pageCount(int offset, int shown, int total)
    {
        if (shown == 0) { return "Showing 0 of " + total + ".\n\n"; }
        String notice = Pagination.pageNotice(offset, shown, total);
        if (notice.isEmpty()) { return "Showing 1-" + shown + " of " + total + ".\n\n"; }
        // Turn the shared inline notice into a standalone sentence, without orphan parentheses.
        String sentence = notice.trim();
        sentence = sentence.substring(1, sentence.length() - 1);
        return Character.toUpperCase(sentence.charAt(0)) + sentence.substring(1) + ".\n\n";
    }

    private static String source(String source)
    {
        return source.length() <= 100 ? source : source.substring(0, 100) + "... [source shortened; export for full text]";
    }

    public static String lineTable(List<Row> rows, boolean detailed)
    {
        StringBuilder out = new StringBuilder(detailed
            ? MarkdownUtils.tableHeader("Module", "Method", "Line", "Target", "Calls", "Time ms", "Pure ms",
                "Avg pure/call ms", "%", "Pure %", "Server call", "Source (shortened)")
            : MarkdownUtils.tableHeader("Module", "Line", "Target", "Calls", "Time ms", "Pure ms", "Avg pure/call ms", "Server call"));
        for (Row row : rows)
        {
            String average = row.calls() == 0 ? "n/a" : number(row.pure() / row.calls());
            out.append(detailed
                ? MarkdownUtils.tableRow(row.module(), row.method(), text(row.line()), row.target(), text(row.calls()),
                    number(row.time()), number(row.pure()), average, percentage(row.percent()), percentage(row.purePercent()),
                    text(row.signal()), source(row.source()))
                : MarkdownUtils.tableRow(row.module(), text(row.line()), row.target(), text(row.calls()),
                    number(row.time()), number(row.pure()), average, text(row.signal())));
        }
        out.append('\n');
        if (rows.stream().anyMatch(r -> r.unresolved() || r.method().equals("n/a"))) out.append(NA_NOTICE);
        return out.toString();
    }

    public static String summary(IProfilingResult result, List<Row> all, List<Row> selected, int top)
    {
        StringBuilder out = new StringBuilder(identity(result)).append(unresolvedNotice(all)).append(totals(all));
        out.append("Infobase: ").append(MarkdownUtils.inlineCode(result.getConnectionString()))
            .append(". Debug targets: ").append(MarkdownUtils.inlineCode(result.getDebugTargets().toString()))
            .append(".\n\nModules: ").append(rollups(all, false).size()).append("; methods: ")
            .append(rollups(all, true).size()).append("; line records: ").append(all.size())
            .append("; selected line records: ").append(selected.size()).append(".\n\n")
            .append("Total time is the sum of inclusive line times; it can double-count calls and recursion. "
                + "Pure time is exact own time. Each line record belongs to one debug target.\n\n");
        topLines(out, "Top lines by pure time", byPure(selected), top);
        topLines(out, "Top lines by call count", selected.stream().sorted(Comparator.comparingLong(Row::calls)
            .reversed().thenComparing(Row::moduleKey).thenComparingInt(Row::line)).toList(), top);
        topLines(out, "Top lines with server calls", byPure(selected.stream().filter(r -> r.signal() != null
            && r.signal() != ServerCallSignal.NO_SERVER_CALL).toList()), top);
        topRollups(out, "Top methods by own (pure) time", rollups(selected, true), top, true);
        topRollups(out, "Top modules by own (pure) time", rollups(selected, false), top, false);
        return out.append('\n').toString();
    }

    public static String totals(List<Row> rows)
    {
        StringBuilder out = new StringBuilder(MarkdownUtils.tableHeader("Scope", "Total time ms (line sum)", "Pure time ms"));
        out.append(MarkdownUtils.tableRow("All targets", number(rows.stream().mapToDouble(Row::time).sum()),
            number(rows.stream().mapToDouble(Row::pure).sum())));
        for (String side : List.of("Client", "Server", "Other"))
        {
            out.append(MarkdownUtils.tableRow(side, number(sideTime(rows, side, false)), number(sideTime(rows, side, true))));
        }
        return out.append('\n').toString();
    }

    private static String side(String target)
    {
        return switch (target)
        {
            case "Client", "ManagedClient", "WEBClient", "COMConnector", "MobileClient", "MobileManagedClient" -> "Client";
            case "Server", "ServerEmulation", "WEBService", "HTTPService", "OData", "WebSocket",
                "WebSocketFileMode", "JOB", "JobFileMode", "MobileServer", "MobileJobFileMode", "MobileManagedServer" -> "Server";
            default -> "Other";
        };
    }

    private static double sideTime(List<Row> rows, String side, boolean pure)
    {
        return rows.stream().flatMap(r -> (pure ? r.pureTimes() : r.times()).entrySet().stream())
            .filter(e -> side(e.getKey()).equals(side)).mapToDouble(Map.Entry::getValue).sum();
    }

    private static void topLines(StringBuilder out, String title, List<Row> rows, int top)
    {
        List<Row> shown = page(rows, 0, top);
        out.append("## ").append(title).append(count(shown.size(), rows.size(),
            "Increase top (maximum 20), narrow moduleFilter/methodFilter, or use mode=lines with limit/offset."))
            .append(lineTable(shown, true));
    }

    private static void topRollups(StringBuilder out, String title, List<Rollup> rows, int top, boolean byMethod)
    {
        out.append("## ").append(title).append(count(Math.min(top, rows.size()), rows.size(),
            "Increase top (maximum 20), narrow moduleFilter/methodFilter, or export with manage_profiling_results."))
            .append(byMethod
                ? MarkdownUtils.tableHeader("Module", "Method", "Pure ms", "Inclusive line sum ms (may double-count)", "Line calls (sum, not method invocations)")
                : MarkdownUtils.tableHeader("Module", "Pure ms", "Inclusive line sum ms (may double-count)", "Line calls (sum, not method invocations)"));
        for (Rollup row : page(rows, 0, top))
        {
            out.append(byMethod
                ? MarkdownUtils.tableRow(row.module(), row.method(), number(row.pure()), number(row.time()), text(row.lineCalls()))
                : MarkdownUtils.tableRow(row.module(), number(row.pure()), number(row.time()), text(row.lineCalls())));
        }
        out.append('\n');
        if (byMethod && page(rows, 0, top).stream().anyMatch(r -> r.method().equals("n/a"))) out.append(NA_NOTICE);
    }

    public static String measurements(List<IProfilingResult> results, int offset, int limit)
    {
        List<IProfilingResult> shown = page(results, offset, limit);
        StringBuilder out = new StringBuilder("Measurements (newest first). Time unit: ms.\n\n")
            .append(pageCount(offset, shown.size(), results.size()))
            .append(MarkdownUtils.tableHeader("Id", "Name", "Date", "Infobase", "Total time ms (line sum)", "Debug targets", "Line records"));
        for (IProfilingResult result : shown)
        {
            out.append(MarkdownUtils.tableRow(text(result.getUuid()), result.getName(), text(result.getDateOfSession()),
                result.getConnectionString(), number(rows(result).stream().mapToDouble(Row::time).sum()), text(result.getDebugTargets()),
                text(result.getProfilingResults().size())));
        }
        return out.append('\n').toString();
    }

    public static String change(double base, double compared)
    {
        return number(compared - base) + " ms (" + (base == 0 ? (compared == 0 ? "0.00%" : "n/a; base is zero")
            : percentage((compared - base) * 100 / base) + "%") + ")";
    }

    public static String compare(IProfilingResult base, IProfilingResult compared, ICompareResult comparison,
        String module, String method, int frequency, int top, int offset, int limit)
    {
        List<Row> baseRows = rows(base);
        List<Row> comparedRows = rows(compared);
        StringBuilder out = new StringBuilder("Base → compared\n\n").append(identity(base)).append(unresolvedNotice(baseRows))
            .append(identity(compared)).append(unresolvedNotice(comparedRows))
            .append("Total time sums inclusive line times; pure time is own time.\n\n")
            .append(MarkdownUtils.tableHeader("Scope", "Base total ms", "Compared total ms", "Change", "Base pure ms", "Compared pure ms", "Pure change"));
        for (String scope : List.of("All targets", "Client", "Server", "Other"))
        {
            double bt = scope.equals("All targets") ? baseRows.stream().mapToDouble(Row::time).sum() : sideTime(baseRows, scope, false);
            double ct = scope.equals("All targets") ? comparedRows.stream().mapToDouble(Row::time).sum() : sideTime(comparedRows, scope, false);
            double bp = scope.equals("All targets") ? baseRows.stream().mapToDouble(Row::pure).sum() : sideTime(baseRows, scope, true);
            double cp = scope.equals("All targets") ? comparedRows.stream().mapToDouble(Row::pure).sum() : sideTime(comparedRows, scope, true);
            out.append(MarkdownUtils.tableRow(scope, number(bt), number(ct), change(bt, ct), number(bp), number(cp), change(bp, cp)));
        }
        out.append('\n');
        List<IMethodCompareResult> methods = comparison.getMethodsCompareResults();
        List<IMethodCompareResult> matched = methods.stream().filter(m -> !(m instanceof IUnmatchedCompareResult))
            .filter(m -> matches(compareModule(m, m.getProject()), module) && matches(resolvedText(m.getMethodName()), method)).toList();
        Function<IMethodCompareResult, Double> delta = m -> (m.getPureTime(CompareSide.COMPARED) - (double)m.getPureTime(CompareSide.BASE)) / 1_000_000;
        for (boolean slowed : List.of(true, false))
        {
            List<IMethodCompareResult> ranked = matched.stream().filter(m -> slowed ? delta.apply(m) > 0 : delta.apply(m) < 0)
                .sorted(slowed ? Comparator.comparing(delta).reversed() : Comparator.comparing(delta)).toList();
            out.append("\n## Top ").append(slowed ? "slowed-down" : "sped-up").append(" methods by absolute own-time change")
                .append(count(Math.min(top, ranked.size()), ranked.size(), "Increase top, narrow filters, or compare exported measurements."))
                .append(MarkdownUtils.tableHeader("Module", "Method", "Base pure ms", "Compared pure ms", "Change"));
            for (IMethodCompareResult item : page(ranked, 0, top))
            {
                double b = item.getPureTime(CompareSide.BASE) / 1_000_000.0;
                double c = item.getPureTime(CompareSide.COMPARED) / 1_000_000.0;
                out.append(MarkdownUtils.tableRow(compareModule(item, item.getProject()),
                    missingLabel(item.getModuleName()) ? "n/a" : resolvedText(item.getMethodName()), number(b), number(c), change(b, c)));
            }
            out.append('\n');
            if (page(ranked, 0, top).stream().anyMatch(m -> missingLabel(m.getModuleName()) || missingLabel(m.getMethodName())))
                out.append(NA_NOTICE);
        }
        List<ILineCompareResult> allLines = methods.stream().flatMap(m -> m.getLinesCompareResults().stream()).toList();
        out.append("\nUnmatched elements: modules=").append(comparison.getModulesCompareResults().stream().filter(m -> m instanceof IUnmatchedCompareResult).count())
            .append(", methods=").append(methods.stream().filter(m -> m instanceof IUnmatchedCompareResult).count())
            .append(", lines=").append(allLines.stream().filter(l -> l instanceof IUnmatchedCompareResult).count())
            .append(". Unmatched elements are excluded from rankings.\n\nMethods match by name; lines match by number and trimmed text, so edits can shift lines and leave them unmatched.\n\n");
        if (module != null || method != null)
        {
            List<ILineCompareResult> selected = allLines.stream().filter(l -> matches(compareModule(l, l.getProject()), module)
                && matches(resolvedText(l.getMethodName()), method)).filter(l -> l instanceof IUnmatchedCompareResult
                    || Math.max(l.getFrequency(CompareSide.BASE), l.getFrequency(CompareSide.COMPARED)) >= frequency)
                .sorted(Comparator.comparingInt(ILineCompareResult::getLineNo).thenComparing(l -> text(l.getModuleName()))).toList();
            List<ILineCompareResult> shown = page(selected, offset, limit);
            out.append("## Compared lines\n\n").append(pageCount(offset, shown.size(), selected.size()))
                .append(MarkdownUtils.tableHeader("Module", "Method", "Line", "Base calls", "Compared calls", "Base pure ms", "Compared pure ms", "Change", "Source (shortened)"));
            for (ILineCompareResult line : shown)
            {
                String moduleName = compareModule(line, line.getProject());
                String methodName = missingLabel(line.getModuleName()) ? "n/a" : resolvedText(line.getMethodName());
                String code = missingLabel(line.getModuleName()) ? "n/a" : source(resolvedText(line.getLine()));
                if (line instanceof IUnmatchedCompareResult)
                {
                    out.append(MarkdownUtils.tableRow(moduleName, methodName, text(line.getLineNo()),
                        "unmatched", "unmatched", "n/a", "n/a", "n/a", code));
                    continue;
                }
                double b = line.getPureTime(CompareSide.BASE) / 1_000_000.0;
                double c = line.getPureTime(CompareSide.COMPARED) / 1_000_000.0;
                out.append(MarkdownUtils.tableRow(moduleName, methodName, text(line.getLineNo()),
                    text(line.getFrequency(CompareSide.BASE)), text(line.getFrequency(CompareSide.COMPARED)), number(b), number(c), change(b, c), code));
            }
            out.append('\n');
            if (shown.stream().anyMatch(l -> missingLabel(l.getModuleName()) || missingLabel(l.getMethodName()) || missingLabel(l.getLine())))
                out.append(NA_NOTICE);
        }
        return out.append('\n').toString();
    }
}
