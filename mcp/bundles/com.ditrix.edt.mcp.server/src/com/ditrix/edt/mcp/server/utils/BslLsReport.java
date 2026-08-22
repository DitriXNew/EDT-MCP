/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parsed model of a BSL Language Server JSON report (the {@code --reporter json}
 * output, one {@code bsl-json.json}), plus the {@link #parse(String)} that builds
 * it. Kept free of any process/IO so it is unit-testable against a captured report
 * string (see {@code BslLsReportTest}); the subprocess side lives in
 * {@link BslLsRunner}.
 * <p>
 * The engine emits diagnostics with LSP conventions: <b>0-based</b> line/character
 * and a {@code file://} URI that may carry {@code ../} segments relative to the
 * process working directory. This model normalizes both — lines/columns are
 * converted to <b>1-based</b> (to match the rest of the tools and BSL AST helpers)
 * and the path is collapsed to an absolute filesystem path. Diagnostic messages are
 * left verbatim (they are engine data, localized by the engine's configured
 * {@code diagnosticLanguage}).
 */
public final class BslLsReport
{
    /** LSP diagnostic severity as emitted by BSL LS ({@code Error/Warning/Information/Hint}). */
    public enum Severity
    {
        ERROR, WARNING, INFORMATION, HINT;

        /**
         * Maps the engine's severity token to this enum; anything unrecognized (or
         * {@code null}) falls back to {@link #INFORMATION} so an unexpected token never
         * throws.
         *
         * @param token the BSL LS {@code severity} string
         * @return the matching severity (never {@code null})
         */
        public static Severity fromToken(String token)
        {
            if (token == null)
            {
                return INFORMATION;
            }
            switch (token)
            {
            case "Error": //$NON-NLS-1$
                return ERROR;
            case "Warning": //$NON-NLS-1$
                return WARNING;
            case "Hint": //$NON-NLS-1$
                return HINT;
            case "Information": //$NON-NLS-1$
            default:
                return INFORMATION;
            }
        }
    }

    /** One diagnostic (a defect to review/fix), located and typed. */
    public static final class Finding
    {
        private final String mdoRef;
        private final String path;
        private final int line;
        private final int column;
        private final String code;
        private final Severity severity;
        private final String message;
        private final String href;
        private final List<String> tags;

        Finding(String mdoRef, String path, int line, int column, String code, Severity severity,
            String message, String href, List<String> tags)
        {
            this.mdoRef = mdoRef;
            this.path = path;
            this.line = line;
            this.column = column;
            this.code = code;
            this.severity = severity;
            this.message = message;
            this.href = href;
            this.tags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(tags);
        }

        /** @return the metadata reference of the host module, e.g. {@code CommonModule.Calc} (may be {@code null}). */
        public String mdoRef()
        {
            return mdoRef;
        }

        /** @return the absolute, normalized filesystem path of the module (may be {@code null}). */
        public String path()
        {
            return path;
        }

        /** @return 1-based line number of the diagnostic. */
        public int line()
        {
            return line;
        }

        /** @return 1-based column number of the diagnostic start. */
        public int column()
        {
            return column;
        }

        /** @return the rule identifier, e.g. {@code MagicNumber} (may be {@code null}). */
        public String code()
        {
            return code;
        }

        /** @return the diagnostic severity (never {@code null}). */
        public Severity severity()
        {
            return severity;
        }

        /** @return the diagnostic message, verbatim from the engine (may be {@code null}). */
        public String message()
        {
            return message;
        }

        /** @return the rule's documentation URL, usable as a remediation hint (may be {@code null}). */
        public String href()
        {
            return href;
        }

        /** @return the LSP tags ({@code Unnecessary}/{@code Deprecated}); never {@code null}. */
        public List<String> tags()
        {
            return tags;
        }
    }

    /** Per-module code metrics (the {@code metrics} block of a file entry). */
    public static final class FileMetrics
    {
        private final String mdoRef;
        private final String path;
        private final int cyclomaticComplexity;
        private final int cognitiveComplexity;
        private final int ncloc;
        private final int statements;
        private final int procedures;
        private final int functions;

        FileMetrics(String mdoRef, String path, int cyclomaticComplexity, int cognitiveComplexity, int ncloc,
            int statements, int procedures, int functions)
        {
            this.mdoRef = mdoRef;
            this.path = path;
            this.cyclomaticComplexity = cyclomaticComplexity;
            this.cognitiveComplexity = cognitiveComplexity;
            this.ncloc = ncloc;
            this.statements = statements;
            this.procedures = procedures;
            this.functions = functions;
        }

        /** @return the metadata reference of the module. */
        public String mdoRef()
        {
            return mdoRef;
        }

        /** @return the absolute, normalized filesystem path of the module. */
        public String path()
        {
            return path;
        }

        /** @return cyclomatic complexity of the module. */
        public int cyclomaticComplexity()
        {
            return cyclomaticComplexity;
        }

        /** @return cognitive complexity of the module. */
        public int cognitiveComplexity()
        {
            return cognitiveComplexity;
        }

        /** @return non-comment lines of code. */
        public int ncloc()
        {
            return ncloc;
        }

        /** @return number of statements. */
        public int statements()
        {
            return statements;
        }

        /** @return number of procedures. */
        public int procedures()
        {
            return procedures;
        }

        /** @return number of functions. */
        public int functions()
        {
            return functions;
        }
    }

    private final List<Finding> findings;
    private final List<FileMetrics> metrics;
    private final Map<Severity, Integer> severityCounts;

    private BslLsReport(List<Finding> findings, List<FileMetrics> metrics)
    {
        this.findings = Collections.unmodifiableList(findings);
        this.metrics = Collections.unmodifiableList(metrics);
        Map<Severity, Integer> counts = new LinkedHashMap<>();
        for (Severity s : Severity.values())
        {
            counts.put(s, 0);
        }
        for (Finding f : findings)
        {
            counts.merge(f.severity(), 1, Integer::sum);
        }
        this.severityCounts = Collections.unmodifiableMap(counts);
    }

    /** @return all diagnostics across every analyzed module (never {@code null}). */
    public List<Finding> findings()
    {
        return findings;
    }

    /** @return per-module metrics (never {@code null}). */
    public List<FileMetrics> metrics()
    {
        return metrics;
    }

    /** @return total number of diagnostics. */
    public int total()
    {
        return findings.size();
    }

    /**
     * @param severity the severity to count
     * @return how many findings carry that severity
     */
    public int count(Severity severity)
    {
        return severityCounts.getOrDefault(severity, 0);
    }

    /**
     * Parses a BSL LS JSON report string into this model. Tolerant of missing/null
     * fields (a malformed entry contributes nothing rather than throwing); the only
     * hard failure is a string that is not a JSON object.
     *
     * @param json the {@code bsl-json.json} content
     * @return the parsed report (never {@code null}; may be empty)
     * @throws IllegalArgumentException if {@code json} is not a JSON object
     */
    public static BslLsReport parse(String json)
    {
        List<Finding> findings = new ArrayList<>();
        List<FileMetrics> metrics = new ArrayList<>();

        JsonElement rootEl;
        try
        {
            rootEl = JsonParser.parseString(json == null ? "" : json); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("BSL LS report is not valid JSON", e); //$NON-NLS-1$
        }
        if (rootEl == null || !rootEl.isJsonObject())
        {
            throw new IllegalArgumentException("BSL LS report is not a JSON object"); //$NON-NLS-1$
        }
        JsonObject root = rootEl.getAsJsonObject();
        // `fileinfos` is REQUIRED, not optional-with-an-empty-default. An engine of the wrong
        // version, or an EDT_MCP_BSL_LS_JAR wrapper, can exit 0 and still write a well-formed JSON
        // object that is not a report at all ({} or a status/error object). Treating that as "zero
        // findings" reports a CLEAN project for a run that never analysed anything - the same
        // false-clean this class already refuses for a non-zero exit. Absent or wrongly typed, it
        // is a report-format failure and must say so.
        if (!root.has("fileinfos") || !root.get("fileinfos").isJsonArray()) //$NON-NLS-1$ //$NON-NLS-2$
        {
            throw new IllegalArgumentException("BSL LS report has no 'fileinfos' array - the engine " //$NON-NLS-1$
                + "produced JSON that is not an analysis report (a wrong engine version, or a " //$NON-NLS-1$
                + "wrapper writing its own output). Reporting it as 'no issues found' would hide " //$NON-NLS-1$
                + "that nothing was actually analysed."); //$NON-NLS-1$
        }
        JsonArray fileInfos = root.get("fileinfos").getAsJsonArray(); //$NON-NLS-1$

        for (JsonElement fiEl : fileInfos)
        {
            if (fiEl == null || !fiEl.isJsonObject())
            {
                continue;
            }
            JsonObject fi = fiEl.getAsJsonObject();
            String mdoRef = optString(fi, "mdoRef"); //$NON-NLS-1$
            String path = normalizePath(optString(fi, "path")); //$NON-NLS-1$

            JsonArray diags = asArray(fi, "diagnostics"); //$NON-NLS-1$
            if (diags != null)
            {
                for (JsonElement dEl : diags)
                {
                    if (dEl == null || !dEl.isJsonObject())
                    {
                        continue;
                    }
                    findings.add(toFinding(dEl.getAsJsonObject(), mdoRef, path));
                }
            }

            FileMetrics m = toMetrics(fi, mdoRef, path);
            if (m != null)
            {
                metrics.add(m);
            }
        }
        return new BslLsReport(findings, metrics);
    }

    private static Finding toFinding(JsonObject d, String mdoRef, String path)
    {
        String code = optString(d, "code"); //$NON-NLS-1$
        String message = optString(d, "message"); //$NON-NLS-1$
        Severity severity = Severity.fromToken(optString(d, "severity")); //$NON-NLS-1$

        // range.start.{line,character} are 0-based (LSP) -> convert to 1-based.
        int line = 1;
        int column = 1;
        JsonObject range = optObject(d, "range"); //$NON-NLS-1$
        if (range != null)
        {
            JsonObject start = optObject(range, "start"); //$NON-NLS-1$
            if (start != null)
            {
                line = optInt(start, "line", 0) + 1; //$NON-NLS-1$
                column = optInt(start, "character", 0) + 1; //$NON-NLS-1$
            }
        }

        String href = null;
        JsonObject codeDescription = optObject(d, "codeDescription"); //$NON-NLS-1$
        if (codeDescription != null)
        {
            href = optString(codeDescription, "href"); //$NON-NLS-1$
        }

        List<String> tags = new ArrayList<>();
        JsonArray tagArr = asArray(d, "tags"); //$NON-NLS-1$
        if (tagArr != null)
        {
            for (JsonElement t : tagArr)
            {
                if (t != null && t.isJsonPrimitive())
                {
                    tags.add(t.getAsString());
                }
            }
        }

        return new Finding(mdoRef, path, line, column, code, severity, message, href, tags);
    }

    private static FileMetrics toMetrics(JsonObject fi, String mdoRef, String path)
    {
        JsonObject m = optObject(fi, "metrics"); //$NON-NLS-1$
        if (m == null)
        {
            return null;
        }
        return new FileMetrics(mdoRef, path,
            optInt(m, "cyclomaticComplexity", 0), //$NON-NLS-1$
            optInt(m, "cognitiveComplexity", 0), //$NON-NLS-1$
            optInt(m, "ncloc", 0), //$NON-NLS-1$
            optInt(m, "statements", 0), //$NON-NLS-1$
            optInt(m, "procedures", 0), //$NON-NLS-1$
            optInt(m, "functions", 0)); //$NON-NLS-1$
    }

    /**
     * Collapses a {@code file://} URI (possibly carrying {@code ../} segments relative
     * to the engine's working directory) to an absolute, normalized filesystem path.
     * Falls back to the raw value on any parse failure so a client always sees
     * <i>something</i> locatable.
     */
    private static String normalizePath(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return raw;
        }
        try
        {
            if (raw.startsWith("file:")) //$NON-NLS-1$
            {
                Path p = Paths.get(new URI(raw)).normalize().toAbsolutePath();
                return p.toString();
            }
            return Paths.get(raw).normalize().toAbsolutePath().toString();
        }
        catch (RuntimeException | java.net.URISyntaxException e)
        {
            return raw;
        }
    }

    private static JsonArray asArray(JsonObject o, String key)
    {
        JsonElement el = o.get(key);
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    private static JsonObject optObject(JsonObject o, String key)
    {
        JsonElement el = o.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String optString(JsonObject o, String key)
    {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static int optInt(JsonObject o, String key, int fallback)
    {
        JsonElement el = o.get(key);
        try
        {
            return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }
}
