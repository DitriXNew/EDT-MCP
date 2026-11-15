/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;
import com._1c.g5.v8.dt.profiling.core.IResultsStore;
import com._1c.g5.wiring.ServiceAccess;
import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.preferences.ToolSettingsService;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProfilingFiles;
import com.ditrix.edt.mcp.server.utils.ProfilingReport;
import com.ditrix.edt.mcp.server.utils.ProfilingSupport;

/** Exchange persisted EDT measurements through a separate write permission. */
public class ManageProfilingResultsTool implements IMcpTool
{
    public static final String NAME = "manage_profiling_results";

    @Override public String getName() { return NAME; }
    @Override public String getDescription()
    {
        return "Export BSL performance measurements or import native EDT measurements. "
            + "Requires consent and a writable preset; use absolute paths on the EDT host. Never overwrites. "
            + "Parameters and examples: get_tool_guide('manage_profiling_results').";
    }

    @Override public ToolAnnotations getAnnotations()
    {
        return new ToolAnnotations(null, false, false, false, false);
    }

    @Override public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("mode", "Default: export.", "export", "import")
            .stringProperty("measurementId", "Export UUID; default: latest by session date. Use get_profiling_results mode=list.")
            .stringProperty("exportPath", "Export only, required: absolute new CSV file (parent must exist) or EDT directory.")
            .enumProperty("exportFormat", "Default: csv. Never overwrites.", "csv", "edt")
            .stringProperty("importPath", "Import only, required: absolute directory holding EDT measurement folders.")
            .build();
    }

    @Override public String execute(Map<String, String> params)
    {
        try
        {
            String mode = choice(JsonUtils.extractStringArgument(params, "mode"), "mode", "export", "export", "import");
            String measurementId = JsonUtils.extractStringArgument(params, "measurementId");
            String exportPath = JsonUtils.extractStringArgument(params, "exportPath");
            String format = choice(JsonUtils.extractStringArgument(params, "exportFormat"), "exportFormat", "csv", "csv", "edt");
            String importPath = JsonUtils.extractStringArgument(params, "importPath");
            // Validate parameters before EDT access or a consent dialog.
            Path importing = mode.equals("import") ? ProfilingFiles.validateImport(importPath) : null;
            if (mode.equals("export")) { ProfilingFiles.absolute(exportPath, "exportPath"); }
            if (isReadOnlyPreset())
            {
                return ToolResult.error(NAME + " writes and is unavailable in a read-only preset. "
                    + "Select a writable Tools preset in EDT preferences; use get_profiling_results to read measurements.").toJson();
            }
            IProfilingService service = profilingService();
            List<IProfilingResult> results = ProfilingSupport.newest(service.getResults());
            if (mode.equals("import")) { return importMeasurements(importing, service, results); }
            IProfilingResult result = ProfilingSupport.select(results, measurementId);
            Path target = ProfilingFiles.validateExport(exportPath, format, result);
            String denied = consent("Export profiling measurement", target.toString(), 1, List.of(result.getName()));
            if (denied != null) { return denied; }
            // Repeat validation after a human may have spent time answering the dialog.
            ProfilingFiles.validateExport(exportPath, format, result);
            Path written = target;
            if (format.equals("csv")) { ProfilingFiles.csv(target, result); }
            else { written = ProfilingFiles.edt(target, result, resultsStore()); }
            return ProfilingReport.identity(result) + "Exported complete measurement (" + result.getProfilingResults().size()
                + " line records), format " + format + ", to " + MarkdownUtils.inlineCode(written.toString()) + ".\n\n";
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return ToolResult.error("Cannot manage profiling results: " + PlatformFailures.describe(e)
                + " Use get_profiling_results mode=list to verify IDs and check the requested mode/paths.").toJson();
        }
    }

    protected IProfilingService profilingService() { return ProfilingSupport.service(); }
    protected IResultsStore resultsStore()
    {
        IResultsStore store = ServiceAccess.get(IResultsStore.class);
        if (store == null) { throw new IllegalStateException("IResultsStore is unavailable. Enable EDT profiling."); }
        return store;
    }
    protected boolean isReadOnlyPreset()
    {
        ToolSettingsService settings = ToolSettingsService.getInstance();
        ToolPreset session = settings.getSessionPreset();
        return (session == null ? ToolPreset.fromId(settings.getStoredPresetId()) : session).isReadOnly();
    }
    protected String consent(String title, String path, int count, List<String> names)
    {
        ConsentDecision decision = DestructiveConsentGate.getInstance().requireConsent(NAME,
            new ConsentPreview(title, path, count, names));
        return decision == ConsentDecision.ALLOW ? null
            : ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME)).toJson();
    }

    private String importMeasurements(Path path, IProfilingService service, List<IProfilingResult> before)
    {
        // A legacy sourceless store is migrated/deleted by loadResults. Require modern native folders.
        if (java.nio.file.Files.exists(path.resolve("results_cache.xml")))
        {
            throw new IllegalArgumentException("importPath '" + path + "' contains a legacy cache. Export it with EDT first, then import the native measurement folders.");
        }
        List<IProfilingResult> loaded = resultsStore().loadResults(path);
        List<IProfilingResult> added = new ArrayList<>();
        List<IProfilingResult> skipped = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        before.forEach(r -> { ids.add(r.getUuid()); names.add(r.getName().toLowerCase(java.util.Locale.ROOT)); });
        for (IProfilingResult result : loaded)
        {
            if (!ids.add(result.getUuid())) { skipped.add(result); continue; }
            ProfilingFiles.validateName(result.getName());
            if (!names.add(result.getName().toLowerCase(java.util.Locale.ROOT)))
            {
                throw new IllegalArgumentException("Imported measurement name '" + result.getName()
                    + "' conflicts with another UUID. Rename it in EDT and export again; existing measurements are never overwritten.");
            }
            added.add(result);
        }
        if (loaded.isEmpty())
        {
            throw new IllegalArgumentException("importPath '" + path + "' has no readable EDT measurements. Supply the parent directory of native measurement folders and inspect the EDT log.");
        }
        String viewNote = "";
        if (!added.isEmpty())
        {
            String denied = consent("Import profiling measurements", path.toString(), added.size(), added.stream().map(IProfilingResult::getName).toList());
            if (denied != null) { return denied; }
            // Another call can import while consent is pending. Recheck both UUID and name before writing.
            var attempted = new java.util.concurrent.atomic.AtomicBoolean();
            viewNote = ProfilingSupport.applyObserved(() -> {
                synchronized (service)
                {
                    for (IProfilingResult current : service.getResults())
                    {
                        for (IProfilingResult candidate : added)
                        {
                            if (current.getUuid().equals(candidate.getUuid()) || current.getName().equalsIgnoreCase(candidate.getName()))
                            {
                                throw new IllegalArgumentException("Measurement '" + current.getName() + "' appeared while awaiting consent. Retry mode=import to skip loaded UUIDs.");
                            }
                        }
                    }
                    attempted.set(true);
                    service.importResults(added);
                }
            }, () -> attempted.get() && ProfilingSupport.ids(service).containsAll(added.stream().map(IProfilingResult::getUuid).toList()),
                "Import of " + added.size() + " measurement(s)");
        }
        StringBuilder out = new StringBuilder("Imported measurements. Time unit: ms. Added: ").append(added.size())
            .append("; skipped (UUID already loaded): ").append(skipped.size()).append('.').append(viewNote).append("\n\n");
        // Import may contain arbitrarily many results; report a bounded page and point to list for IDs.
        List<IProfilingResult> report = new ArrayList<>(added);
        report.addAll(skipped);
        for (IProfilingResult result : ProfilingReport.page(report, 0, 50))
        {
            out.append(ProfilingReport.identity(result)).append(added.contains(result) ? "Added.\n\n" : "Skipped: UUID already loaded.\n\n");
        }
        return out.append(ProfilingReport.pageCount(0, Math.min(50, report.size()), report.size()))
            .append(report.size() > 50 ? "Use get_profiling_results mode=list with limit/offset to see all loaded IDs.\n\n" : "")
            .toString();
    }

    private static String choice(String raw, String name, String fallback, String... choices)
    {
        if (raw == null) { return fallback; }
        if (!List.of(choices).contains(raw))
        {
            throw new IllegalArgumentException(name + " '" + raw + "' is invalid. Use one of: " + String.join(", ", choices) + ".");
        }
        return raw;
    }
}
