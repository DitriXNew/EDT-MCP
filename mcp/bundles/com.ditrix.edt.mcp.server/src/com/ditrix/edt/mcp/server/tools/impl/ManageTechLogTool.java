// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
package com.ditrix.edt.mcp.server.tools.impl;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.ditrix.edt.mcp.server.preferences.ToolSettingsService;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogAnalysis;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogCapture;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogKnowledge;
import com.ditrix.edt.mcp.server.utils.techlog.TechLogReader;

/** Consent-gated local capture/configuration and non-overwriting CSV export. */
public class ManageTechLogTool implements IMcpTool
{
    public static final String NAME = "manage_tech_log";
    private final TechLogCapture capture;
    public ManageTechLogTool() { this(TechLogCapture.user()); }
    public ManageTechLogTool(TechLogCapture capture) { this.capture = capture; }
    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Preview and manage 1C JSON technological-log capture, or export CSV. Preview before start; start, stop and export require consent and refuse read-only mode. "
            + "Stop after a short reproduction to restore logcfg.xml. Full parameters and examples: call get_tool_guide('manage_tech_log').";
    }
    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("mode", "Default preview; start/stop/export write and ask consent.", "preview", "start", "status", "stop", "export")
            .enumProperty("profile", "Capture question, default errors.", "errors", "slow-calls", "db", "locks", "memory", "scenario")
            .stringProperty("location", "Absolute dedicated empty capture directory; default a new temp/edt-mcp-tech-log/<uuid>.")
            .integerProperty("thresholdMs", "Duration threshold in milliseconds; defaults by profile; errors and locks are unfiltered.")
            .stringProperty("extraEvents", "Comma-separated additional known event names.")
            .stringProperty("infobase", "Optional exact p:processName filter; may exclude client/file events.")
            .booleanProperty("plans", "Enable plansql; default false, expensive test/development diagnostics.")
            .booleanProperty("dbmsLocks", "Enable dbmslocks polling and lock annotations; default false.")
            .integerProperty("historyHours", "Retention hours, default 1, range 1..24; not capture duration.")
            .integerProperty("rotationMb", "Per-file size rotation MB, default 100, range 1..1024; not total disk cap.")
            .stringProperty("path", "Absolute input log path; required for export.")
            .stringProperty("outputPath", "New absolute CSV file; required for export, never overwritten.")
            .enumProperty("exportKind", "CSV rows, default events.", "events", "groups")
            .booleanProperty("readOnly", "Additional explicit refusal of start/stop/export; default false. Preset restrictions also apply.").build();
    }
    @Override public ResponseType getResponseType() { return ResponseType.MARKDOWN; }

    @Override public String execute(Map<String, String> params)
    {
        String warning = capture.warning();
        try
        {
            String mode = str(JsonUtils.extractStringArgument(params, "mode"), "preview");
            String profile = str(JsonUtils.extractStringArgument(params, "profile"), "errors");
            String location = JsonUtils.extractStringArgument(params, "location");
            String threshold = JsonUtils.extractStringArgument(params, "thresholdMs");
            String extra = str(JsonUtils.extractStringArgument(params, "extraEvents"), "");
            String infobase = str(JsonUtils.extractStringArgument(params, "infobase"), "");
            boolean plans = JsonUtils.extractBooleanArgument(params, "plans", false);
            boolean dbmsLocks = JsonUtils.extractBooleanArgument(params, "dbmsLocks", false);
            int history = integer(JsonUtils.extractStringArgument(params, "historyHours"), "historyHours", 1);
            int rotation = integer(JsonUtils.extractStringArgument(params, "rotationMb"), "rotationMb", 100);
            String path = JsonUtils.extractStringArgument(params, "path");
            String output = JsonUtils.extractStringArgument(params, "outputPath");
            String exportKind = str(JsonUtils.extractStringArgument(params, "exportKind"), "events");
            boolean readOnly = JsonUtils.extractBooleanArgument(params, "readOnly", false);
            if (List.of("start", "stop", "export").contains(mode) && (readOnly || isReadOnly()))
                return ToolResult.error(warning + "Read-only mode refuses manage_tech_log mode=" + mode
                    + ". Use preview/status or get_tech_log; choose a write-enabled preset for capture/export.").toJson();
            String result;
            switch (mode)
            {
                case "status" -> result = capture.status();
                case "stop" -> result = capture.stop(() -> consent("Restore original logcfg.xml", capture.target().toString()));
                case "export" -> result = export(path, output, exportKind);
                case "preview", "start" -> {
                    int ms = threshold == null ? TechLogKnowledge.entry("profiles", profile).get("thresholdMs").getAsInt()
                        : exact("thresholdMs", threshold);
                    var options = new TechLogCapture.Options(profile, location == null ? TechLogCapture.defaultLocation()
                        : Path.of(location), ms, extra.isBlank() ? List.of() : Arrays.stream(extra.split(",")).map(String::trim).toList(),
                        infobase, plans, dbmsLocks, history, rotation);
                    result = mode.equals("preview") ? capture.preview(options)
                        : capture.start(options, () -> consent("Start technological-log capture", capture.target() + " → " + options.location()));
                }
                default -> throw new IllegalArgumentException("Unknown mode '" + mode + "'; use preview, start, status, stop or export");
            }
            if (JsonUtils.isErrorResponse(result))
            {
                var error = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
                error.addProperty("error", warning + error.get("error").getAsString());
                return com.ditrix.edt.mcp.server.protocol.GsonProvider.toJson(error);
            }
            return warning + result;
        }
        catch (Exception e) { return ToolResult.error(warning + "manage_tech_log: " + e.getMessage()
            + ". Inspect mode=status; use mode=preview or get_tool_guide('manage_tech_log') before retrying writes.").toJson(); }
    }

    protected boolean isReadOnly()
    { return ToolSettingsService.getInstance().getDisabledTools().contains(NAME); }

    protected ConsentDecision requireConsent(ConsentPreview preview)
    { return DestructiveConsentGate.getInstance().requireConsent(NAME, preview); }

    private String consent(String title, String target)
    {
        ConsentDecision decision = requireConsent(ConsentPreview.withUnloggableNames(title, "Local file write", 1, List.of(target)));
        return decision == ConsentDecision.ALLOW ? null
            : ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
    }

    private String export(String path, String outputPath, String kind) throws Exception
    {
        if (path == null || outputPath == null) throw new IllegalArgumentException("export requires path and outputPath; use a new absolute CSV destination");
        Path output = Path.of(outputPath);
        if (!output.isAbsolute() || Files.exists(output))
            throw new IllegalArgumentException("outputPath '" + outputPath + "' must be a new absolute file; never overwrite existing files");
        if (!List.of("events", "groups").contains(kind)) throw new IllegalArgumentException("exportKind '" + kind + "' must be events or groups");
        var index = TechLogReader.read(Path.of(path), 64, 10, null, null);
        if (index.limited || index.rejected > 0 || index.incompleteTail > 0)
            throw new IllegalArgumentException("Export input incomplete/rejected: " + TechLogAnalysis.header(index)
                + "; narrow path or finish the capture before exporting");
        String denied = consent("Export technological log CSV", outputPath); if (denied != null) return denied;
        boolean created = false;
        try
        {
            // CREATE_NEW remains the final race-safe no-overwrite barrier after consent.
            try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                var reads = new TechLogReader.ReadSession(64, 10))
            {
                created = true; writer.write('\ufeff');
                if (kind.equals("events"))
                {
                    writer.write("occurrenceId,ts,event,durationMs,properties\r\n");
                    for (var event : index.events) row(writer, event.id(), event.end().toString(), event.name(),
                        TechLogAnalysis.ms(event.durationUs()), com.ditrix.edt.mcp.server.protocol.GsonProvider.toJson(reads.read(event.pointer())));
                }
                else
                {
                    writer.write("groupId,problemId,pattern,count,totalMs,avgMs,maxMs,memoryPeakBytes\r\n");
                    for (var group : TechLogAnalysis.ranked(index)) row(writer, group.id, group.problem, group.key,
                        "" + group.occurrences.size(), TechLogAnalysis.ms(group.totalUs),
                        TechLogAnalysis.ms(group.totalUs / group.occurrences.size()), TechLogAnalysis.ms(group.maxUs), "" + group.memoryPeak);
                }
            }
        }
        catch (Exception e) { if (created) Files.deleteIfExists(output); throw e; }
        return "CSV exported with UTF-8 BOM to " + output + ". Rows: " + (kind.equals("events") ? index.events.size() : index.groups.size()) + ".";
    }

    private static void row(BufferedWriter writer, String... cells) throws java.io.IOException
    {
        for (int i = 0; i < cells.length; i++)
        { if (i > 0) writer.write(','); writer.write('"'); writer.write(cells[i].replace("\"", "\"\"")); writer.write('"'); }
        writer.write("\r\n");
    }
    private static String str(String value, String fallback)
    { return value == null ? fallback : value; }
    private static int integer(String raw, String key, int fallback)
    { return raw == null ? fallback : exact(key, raw); }
    private static int exact(String key, String raw)
    {
        Integer value = JsonUtils.parseIntegralInt(raw);
        if (value == null) throw new IllegalArgumentException(key + "='" + raw + "' must be an integer; preview with a valid value");
        return value;
    }
}
