/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com._1c.g5.v8.dt.debug.core.model.BslModuleReference;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import com._1c.g5.v8.dt.profiling.core.ILineProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IResultsStore;

/** Native EDT export and RFC 4180 CSV. Validation never creates anything. */
public final class ProfilingFiles
{
    private ProfilingFiles() { }

    public static Path absolute(String value, String parameter)
    {
        if (value == null || value.isBlank() || !Path.of(value).isAbsolute())
        {
            throw new IllegalArgumentException(parameter + " '" + value + "' must be an absolute path. Supply a full path on the EDT host.");
        }
        return Path.of(value).normalize();
    }

    /** EDT uses the name as a directory and deletes the old name when renaming. */
    public static void validateName(String name)
    {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || name.length() > 120
            || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
            || name.endsWith(".") || name.endsWith(" "))
        {
            throw new IllegalArgumentException("Invalid measurement name '" + name + "'. Use a nonblank directory name (up to 120 characters, no path separators or reserved characters).");
        }
        String stem = name.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
        if (java.util.Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9").contains(stem))
        {
            throw new IllegalArgumentException("Invalid measurement name '" + name + "'. Choose a name other than a reserved device name.");
        }
    }

    public static Path validateExport(String path, String format, IProfilingResult result)
    {
        Path target = absolute(path, "exportPath");
        if (format.equals("csv"))
        {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            {
                throw new IllegalArgumentException("exportPath '" + path + "' already exists. Choose a new CSV file; overwriting is refused.");
            }
            if (target.getParent() == null || !Files.isDirectory(target.getParent()))
            {
                throw new IllegalArgumentException("Parent of exportPath '" + path + "' is missing. Create the parent directory first.");
            }
        }
        else
        {
            validateName(result.getName());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(target))
            {
                throw new IllegalArgumentException("exportPath '" + path + "' must be a directory for edt. Choose a directory.");
            }
            if (Files.exists(target.resolve(result.getName()), LinkOption.NOFOLLOW_LINKS))
            {
                throw new IllegalArgumentException("EDT measurement target '" + target.resolve(result.getName())
                    + "' already exists. Choose another exportPath; overwriting is refused.");
            }
        }
        return target;
    }

    public static Path validateImport(String path)
    {
        Path source = absolute(path, "importPath");
        if (!Files.isDirectory(source))
        {
            throw new IllegalArgumentException("importPath '" + path + "' is not a directory. Supply the parent directory holding EDT measurement folders.");
        }
        return source;
    }

    public static String csvCell(String text)
    {
        return "\"" + (text == null ? "" : text.replace("\"", "\"\"")) + "\"";
    }

    private static String targetTimes(Map<String, Double> values)
    {
        return values.entrySet().stream().map(e -> e.getKey() + "=" + ProfilingReport.number(e.getValue()))
            .collect(Collectors.joining(";"));
    }

    public static void csv(Path path, IProfilingResult result) throws IOException
    {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            out.write('\uFEFF');
            writeCsv(out, List.of("measurementId", "module", "method", "line", "calls", "timeMs", "pureTimeMs",
                "percent", "purePercent", "serverCall", "targetTimesMs", "targetPureTimesMs", "source"));
            for (ProfilingReport.Row row : ProfilingReport.rows(result))
            {
                writeCsv(out, List.of(result.getUuid().toString(), row.module(), row.method(), Integer.toString(row.line()),
                    Long.toString(row.calls()), ProfilingReport.number(row.time()), ProfilingReport.number(row.pure()),
                    ProfilingReport.percentage(row.percent()), ProfilingReport.percentage(row.purePercent()), String.valueOf(row.signal()),
                    targetTimes(row.times()), targetTimes(row.pureTimes()), row.source()));
            }
        }
    }

    private static void writeCsv(BufferedWriter out, List<String> values) throws IOException
    {
        out.write(values.stream().map(ProfilingFiles::csvCell).collect(Collectors.joining(",")));
        out.write("\r\n");
    }

    /** Atomically reserve the measurement directory before EDT's writer can overwrite a file. */
    public static Path edt(Path directory, IProfilingResult result, IResultsStore store) throws IOException
    {
        String name = result.getName();
        validateName(name);
        Files.createDirectories(directory);
        Path child = directory.resolve(name);
        Files.createDirectory(child);
        // EDT reads the mutable name again inside saveResult. Pin it to the directory we reserved,
        // even if a UI rename occurs between reservation and writing.
        store.saveResult(namedExport(result, name), directory);
        // EDT logs some IO failures instead of throwing; never claim success without checking the files.
        for (String file : List.of("results_info.xml", "modules_info.properties"))
        {
            if (!Files.isRegularFile(child.resolve(file)) || Files.size(child.resolve(file)) == 0)
            {
                throw new IOException("EDT export is incomplete at '" + child + "'. Inspect the EDT log and retry in a new directory.");
            }
        }
        return child;
    }

    private static IProfilingResult namedExport(IProfilingResult result, String name)
    {
        return new IProfilingResult()
        {
            @Override public String getName() { return name; }
            @Override public java.util.UUID getUuid() { return result.getUuid(); }
            @Override public java.time.LocalDateTime getDateOfSession() { return result.getDateOfSession(); }
            @Override public String getConnectionString() { return result.getConnectionString(); }
            @Override public java.util.Set<DebugTargetType> getDebugTargets() { return result.getDebugTargets(); }
            @Override public List<PerformanceInfoMain> getPerformanceInfo() { return result.getPerformanceInfo(); }
            @Override public List<ILineProfilingResult> getProfilingResults() { return result.getProfilingResults(); }
            @Override public List<ILineProfilingResult> getResultsForModule(BslModuleReference reference)
            {
                return result.getResultsForModule(reference);
            }
            @Override public java.util.Set<BslModuleReference> getReferences() { return result.getReferences(); }
            @Override public Map<BSLModuleIdInternal, org.eclipse.core.resources.IProject> getProjects() { return result.getProjects(); }
            @Override public double getTotalDurability() { return result.getTotalDurability(); }
            @Override public double getTotalPureDurability() { return result.getTotalPureDurability(); }
        };
    }
}
