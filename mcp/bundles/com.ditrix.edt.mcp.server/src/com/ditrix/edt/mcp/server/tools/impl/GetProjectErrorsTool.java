/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;
import com.e1c.g5.v8.dt.check.settings.CheckUid;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Tool to get detailed project errors with optional filters.
 * Uses EDT IMarkerManager for accessing configuration problems.
 *
 * <p>Marker presentation ({@link Marker#getObjectPresentation()}) is resolved lazily
 * against the BM model and therefore must be read inside a BM read transaction.
 * Markers restored from the persisted marker index (e.g. right after EDT startup) have
 * a {@code null} {@code resolvedDataCache}; reading their presentation outside a
 * transaction throws a {@link NullPointerException} that aborts the whole stream.
 * To avoid this, markers are collected per project inside
 * {@link IBmModel#executeReadonlyTask(AbstractBmTask)}.</p>
 */
public class GetProjectErrorsTool implements IMcpTool
{
    public static final String NAME = "get_project_errors"; //$NON-NLS-1$

    /** Closed set of severity filter values accepted by the {@code severity} parameter. */
    static final List<String> SEVERITY_VALUES =
        Arrays.asList("ERRORS", "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL", "NONE"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "List EDT configuration problems (validation markers) with optional project / severity / check-id / object filters. " + //$NON-NLS-1$
               "Each row carries the check code, message, object location and severity; BSL-module problems also expose a structural locator (Module path + Line) you can feed straight into read_module_source or set_breakpoint. " + //$NON-NLS-1$
               "Object FQN filters accept English or Russian type names (e.g. 'Catalog.Products'). " + //$NON-NLS-1$
               "Use this for the detailed marker list; for severity totals only call get_problem_summary. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('get_project_errors')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by EDT project name; omit to scan all projects (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("severity", "Filter by severity (optional)", //$NON-NLS-1$ //$NON-NLS-2$
                "ERRORS", "BLOCKER", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL", "NONE") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
            .stringProperty("checkId", "Filter by check-id substring; matches the symbolic id (e.g. 'ql-temp-table-index') or short UID (e.g. 'SU23') (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", "Filter by object FQNs, e.g. ['Catalog.Products']; English or Russian type names accepted (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Max results; default 100, max 1000 (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("responseFormat", //$NON-NLS-1$
                "Output verbosity (optional): concise (default) = leaner table without the secondary 'Has docs' column; detailed = full table including 'Has docs'", //$NON-NLS-1$
                "concise", "detailed") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# get_project_errors\n\n" //$NON-NLS-1$
            + "Lists EDT configuration problems (validation markers: the same set EDT shows in " //$NON-NLS-1$
            + "its *Configuration Problems* view) as a Markdown table, with optional filters. " //$NON-NLS-1$
            + "All parameters are optional; with none, every problem across every project is " //$NON-NLS-1$
            + "returned (up to `limit`).\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Triage validation errors/warnings after editing code or metadata.\n" //$NON-NLS-1$
            + "- Get a structural locator (Module path + Line) for a BSL problem to feed " //$NON-NLS-1$
            + "straight into `read_module_source` or `set_breakpoint`.\n" //$NON-NLS-1$
            + "- Narrow to one object (`objects`), one check (`checkId`) or one severity band " //$NON-NLS-1$
            + "(`severity`) while iterating on a fix.\n" //$NON-NLS-1$
            + "- For just the totals (counts per severity, no detail) prefer " //$NON-NLS-1$
            + "`get_problem_summary`.\n\n" //$NON-NLS-1$

            + "## Parameters\n" //$NON-NLS-1$
            + "- `projectName` - EDT project name. Omit to scan all projects. An unknown " //$NON-NLS-1$
            + "project returns an error; a project still indexing returns a not-ready error.\n" //$NON-NLS-1$
            + "- `severity` - one of `ERRORS`, `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, " //$NON-NLS-1$
            + "`TRIVIAL`, `NONE` (case-insensitive). An out-of-set value is rejected (the " //$NON-NLS-1$
            + "filter is never silently widened to \"all\"). Matches that exact severity only " //$NON-NLS-1$
            + "(it is not a >= threshold).\n" //$NON-NLS-1$
            + "- `checkId` - case-insensitive substring matched against EITHER the symbolic " //$NON-NLS-1$
            + "check id (e.g. `ql-temp-table-index`) OR the short UID (e.g. `SU23`). The short " //$NON-NLS-1$
            + "UID alone is rarely what you want, so the symbolic id is matched too.\n" //$NON-NLS-1$
            + "- `objects` - array of object FQNs; returns problems only from these objects. " //$NON-NLS-1$
            + "Matching is a case-insensitive substring test against the resolved object " //$NON-NLS-1$
            + "presentation.\n" //$NON-NLS-1$
            + "- `limit` - max rows; default 100, max 1000. When reached, the output appends a " //$NON-NLS-1$
            + "limit-reached notice; narrow the filters to see the rest.\n" //$NON-NLS-1$
            + "- `responseFormat` - `concise` (default) or `detailed`. `concise` trims tokens " //$NON-NLS-1$
            + "by dropping the secondary `Has docs` column; every actionable column " //$NON-NLS-1$
            + "(`Description`, `Location`, `Module path`, `Line`, `Check code`) and the " //$NON-NLS-1$
            + "unresolved-marker warnings are kept. `detailed` adds the `Has docs` column back " //$NON-NLS-1$
            + "(true when `get_check_description` has detail for that check). An " //$NON-NLS-1$
            + "absent/unrecognized value defaults to `concise`.\n\n" //$NON-NLS-1$

            + "## Output columns\n" //$NON-NLS-1$
            + "`Description` | `Location` | `Module path` | `Line` | `Check code` | `Has docs`. " //$NON-NLS-1$
            + "`Module path` + `Line` are populated only for problems that resolve to a `.bsl` " //$NON-NLS-1$
            + "module under `src/` (empty for metadata-only problems). `Check code` shows the " //$NON-NLS-1$
            + "symbolic id when known, else the short UID. `Has docs=true` means " //$NON-NLS-1$
            + "`get_check_description` has detail for that check (the `Has docs` column appears " //$NON-NLS-1$
            + "only with `responseFormat: detailed`).\n\n" //$NON-NLS-1$

            + "## Bilingual (ru/en) note\n" //$NON-NLS-1$
            + "The `objects` filter accepts the TYPE token in English or Russian; each FQN is " //$NON-NLS-1$
            + "expanded to all language variants before matching, so " //$NON-NLS-1$
            + "`Document.SalesOrder` and the Russian " //$NON-NLS-1$
            + "`\u0414\u043E\u043A\u0443\u043C\u0435\u043D\u0442.\u041F\u0440\u043E\u0434\u0430\u0436\u0430\u0422\u043E\u0432\u0430\u0440\u043E\u0432` " //$NON-NLS-1$
            + "both resolve. The object NAME after the dot must still be the real " //$NON-NLS-1$
            + "programmatic name, not a synonym.\n\n" //$NON-NLS-1$

            + "## Examples\n" //$NON-NLS-1$
            + "- All problems in one project: `{projectName: \"MyConfig\"}`.\n" //$NON-NLS-1$
            + "- Errors only: `{projectName: \"MyConfig\", severity: \"ERRORS\"}`.\n" //$NON-NLS-1$
            + "- One check across all projects: `{checkId: \"ql-temp-table-index\"}`.\n" //$NON-NLS-1$
            + "- Scoped to objects: " //$NON-NLS-1$
            + "`{objects: [\"Catalog.Products\", \"Document.SalesOrder\"]}`.\n" //$NON-NLS-1$
            + "- Russian type name: " //$NON-NLS-1$
            + "`{objects: [\"\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430\"]}`.\n\n" //$NON-NLS-1$

            + "## Gotchas\n" //$NON-NLS-1$
            + "- Markers whose location cannot be resolved are NOT dropped: without an " //$NON-NLS-1$
            + "`objects` filter they appear with a `<unresolved: project>` placeholder (a " //$NON-NLS-1$
            + "trailing warning counts them); with an `objects` filter they are excluded " //$NON-NLS-1$
            + "(membership cannot be tested) and a separate warning counts them. Run " //$NON-NLS-1$
            + "`clean_project` / `revalidate_objects` to refresh stale markers.\n" //$NON-NLS-1$
            + "- `severity` matches exactly; to see everything at or above a level, omit it " //$NON-NLS-1$
            + "and read the `Check code` / severity yourself, or call once per band.\n" //$NON-NLS-1$
            + "- The `objects` match is a substring of the presentation, so an overly short " //$NON-NLS-1$
            + "FQN fragment can over-match; prefer the full `Type.Name`.\n"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String objectsJson = JsonUtils.extractStringArgument(params, "objects"); //$NON-NLS-1$

        // Output verbosity: concise (default) trims the secondary 'Has docs' column;
        // detailed renders the full historical table. Any absent/blank/unrecognized value
        // falls back to concise (the lean default), never an error.
        String responseFormat = JsonUtils.extractStringArgument(params, "responseFormat"); //$NON-NLS-1$
        boolean detailed = responseFormat != null && responseFormat.equalsIgnoreCase("detailed"); //$NON-NLS-1$

        // Reject an out-of-set severity instead of silently widening the filter to "all".
        if (severity != null && !severity.isEmpty()
            && !SEVERITY_VALUES.contains(severity.toUpperCase()))
        {
            return ToolResult.error("severity must be one of: " //$NON-NLS-1$
                + String.join(", ", SEVERITY_VALUES)).toJson(); //$NON-NLS-1$
        }

        // Refuse only the transient BUILDING state (buildingErrorOrNull skips a
        // null/empty name itself); a missing/closed project falls through to the
        // value-naming "Project not found: <name>" in getProjectErrors instead of a
        // misleading "Project does not exist. Please wait and retry."
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }
        
        // Parse objects filter
        List<String> objects = parseObjectsList(objectsJson);
        
        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$

        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Pagination.clampLimit(limit, 1000);

        return getProjectErrors(projectName, severity, checkId, objects, limit, detailed);
    }
    
    /**
     * Parses the objects array from JSON string using Gson JsonParser.
     * 
     * @param objectsJson JSON array string like ["Document.SalesOrder", "Catalog.Products"]
     * @return list of object FQNs
     */
    private List<String> parseObjectsList(String objectsJson)
    {
        List<String> result = new ArrayList<>();
        if (objectsJson == null || objectsJson.isEmpty())
        {
            return result;
        }
        
        try
        {
            JsonElement element = JsonParser.parseString(objectsJson);
            if (element.isJsonArray())
            {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array)
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Error parsing objects JSON: " + objectsJson, e); //$NON-NLS-1$
        }
        return result;
    }
    
    /**
     * Gets project errors with filters using EDT IMarkerManager.
     * 
     * @param projectName filter by project name (null for all)
     * @param severity filter by severity (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
     * @param checkId filter by check ID substring
     * @param objects filter by object FQNs (empty list for all objects)
     * @param limit maximum number of results
     * @param detailed when {@code true} render the full table (incl. the secondary
     *        {@code Has docs} column); when {@code false} (the default) render a leaner
     *        table that omits {@code Has docs}. Only the table presentation changes — the
     *        marker collection, model reads and transaction boundaries are identical.
     * @return Markdown formatted string with error details
     */
    public static String getProjectErrors(String projectName, String severity, String checkId, List<String> objects, int limit, boolean detailed)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return ToolResult.error("IMarkerManager service is not available").toJson(); //$NON-NLS-1$
            }
            
            final ICheckRepository checkRepository = Activator.getDefault().getCheckRepository();
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();

            // Parse severity filter
            MarkerSeverity severityFilter = null;
            if (severity != null && !severity.isEmpty())
            {
                try
                {
                    severityFilter = MarkerSeverity.valueOf(severity.toUpperCase());
                }
                catch (IllegalArgumentException e)
                {
                    // Invalid severity, will show all
                }
            }
            final MarkerSeverity finalSeverityFilter = severityFilter;
            final String finalCheckId = checkId;
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                ProjectContext ctx = ProjectContext.of(projectName);
                if (!ctx.exists())
                {
                    return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
                }
            }
            
            // Normalize object FQNs to support both English and Russian metadata type names.
            // For each input FQN, generate all variants (original + English + Russian, lowercased)
            // so we can match markers regardless of the configuration language.
            // Using Set for deduplication of variants.
            final Set<String> finalObjects = new HashSet<>();
            if (objects != null)
            {
                for (String fqn : objects)
                {
                    finalObjects.addAll(MetadataTypeUtils.getAllFqnVariants(fqn));
                }
            }
            
            // Group markers by project in a single pass. getProject() does not touch
            // resolvedDataCache, so this is safe outside a BM transaction. Grouping once avoids
            // re-streaming all markers per project (previously O(markers x projects)).
            // Marker presentation must still be resolved inside a BM read transaction bound to
            // a single project's model, so processing below stays project by project.
            Map<IProject, List<Marker>> markersByProject = new LinkedHashMap<>();
            markerManager.markers().forEach(marker -> {
                IProject markerProject = marker.getProject();
                if (markerProject == null || !markerProject.exists())
                {
                    return;
                }
                if (projectName != null && !projectName.isEmpty()
                    && !projectName.equals(markerProject.getName()))
                {
                    return;
                }
                markersByProject.computeIfAbsent(markerProject, k -> new ArrayList<>()).add(marker);
            });
            
            final List<ErrorInfo> errors = new ArrayList<>();
            // Markers whose presentation could not be resolved even inside a transaction.
            // They are NOT dropped, but they are surfaced differently depending on context,
            // so we track the two cases separately to keep the warning text honest:
            //  - unresolvedShown: reported in the table with a "<unresolved: ...>" placeholder;
            //  - unresolvedFilteredOut: excluded from the result because an explicit objects
            //    filter is active and the location could not be resolved to test membership.
            final int[] unresolvedShown = {0};
            final int[] unresolvedFilteredOut = {0};
            
            for (Map.Entry<IProject, List<Marker>> entry : markersByProject.entrySet())
            {
                if (errors.size() >= limit)
                {
                    break;
                }
                
                final List<Marker> projectMarkers = entry.getValue();
                final int remaining = limit - errors.size();
                
                // Resolve the project's BM model so getObjectPresentation() can lazily
                // resolve the marker target inside a read transaction. The getModel(IProject)
                // overload is the idiomatic path used across the plugin (FindReferencesTool,
                // AddMetadataAttributeTool, tag tools), so no IDtProjectManager is needed.
                IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(entry.getKey()) : null;
                
                Runnable collector = () -> projectMarkers.stream()
                    .map(marker -> buildIfMatches(marker, finalSeverityFilter, finalCheckId,
                        finalObjects, checkRepository, unresolvedShown, unresolvedFilteredOut))
                    .filter(error -> error != null)
                    .limit(remaining)
                    .forEach(errors::add);
                
                if (bmModel != null)
                {
                    BmTransactions.<Void>read(bmModel, "CollectProjectErrors", (tx, pm) -> { //$NON-NLS-1$
                        collector.run();
                        return null;
                    });
                }
                else
                {
                    // Not an EDT project (no BM model): best effort. Per-marker access is
                    // still guarded, so an unresolved marker is reported, never dropped.
                    collector.run();
                }
            }
            
            // Build Markdown response for better readability and context efficiency
            StringBuilder md = new StringBuilder();
            
            if (errors.isEmpty())
            {
                md.append("# No Errors Found\n\n"); //$NON-NLS-1$
                if (projectName != null && !projectName.isEmpty())
                {
                    md.append("Project: **").append(projectName).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (severity != null && !severity.isEmpty())
                {
                    md.append("Severity filter: ").append(severity).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (objects != null && !objects.isEmpty())
                {
                    md.append("Objects filter: ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                md.append("\nNo configuration problems match the specified criteria."); //$NON-NLS-1$
            }
            else
            {
                md.append("# Configuration Problems\n\n"); //$NON-NLS-1$
                md.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
                if (errors.size() >= limit)
                {
                    md.append(Pagination.limitReachedNotice(limit));
                }
                md.append("\n\n"); //$NON-NLS-1$
                
                // Build table matching EDT's Configuration Problems view, plus a structural
                // locator (Module path + Line) that feeds read_module_source / set_breakpoint.
                // Built via the shared MarkdownUtils table builder so every cell is escaped.
                // concise (default) drops the secondary 'Has docs' column to save tokens;
                // detailed keeps the full historical set of columns. Every essential /
                // actionable column (Description, Location, Module path, Line, Check code)
                // is present in BOTH modes.
                if (detailed)
                {
                    md.append(MarkdownUtils.tableHeader("Description", "Location", //$NON-NLS-1$ //$NON-NLS-2$
                        "Module path", "Line", "Check code", "Has docs")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                }
                else
                {
                    md.append(MarkdownUtils.tableHeader("Description", "Location", //$NON-NLS-1$ //$NON-NLS-2$
                        "Module path", "Line", "Check code")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }

                for (ErrorInfo error : errors)
                {
                    // Show symbolic check ID if available, otherwise show check code
                    String displayCheckId = error.checkId != null && !error.checkId.isEmpty()
                        ? error.checkId
                        : error.checkCode;
                    // Wrap the check code in backticks; tableRow escapes the cell, so do NOT
                    // pre-escape here (double-escaping would mangle a pipe in the id).
                    String checkCell = "`" + (displayCheckId != null ? displayCheckId : "") + "`"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    String modulePathCell = error.modulePath != null ? error.modulePath : ""; //$NON-NLS-1$
                    String lineCell = error.line != null ? error.line.toString() : ""; //$NON-NLS-1$

                    if (detailed)
                    {
                        md.append(MarkdownUtils.tableRow(error.message, error.objectPresentation,
                            modulePathCell, lineCell, checkCell,
                            error.hasDocumentation ? "true" : "false")); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    else
                    {
                        md.append(MarkdownUtils.tableRow(error.message, error.objectPresentation,
                            modulePathCell, lineCell, checkCell));
                    }
                }
            }
            
            // Surface unresolved markers explicitly instead of silently dropping them.
            // Two distinct cases, reported separately so each warning matches reality.
            if (unresolvedShown[0] > 0)
            {
                md.append("\n> ⚠️ ").append(unresolvedShown[0]) //$NON-NLS-1$
                  .append(" marker(s) could not be resolved and are shown with a placeholder location. ") //$NON-NLS-1$
                  .append("Run clean_project / revalidate_objects to refresh them."); //$NON-NLS-1$
            }
            if (unresolvedFilteredOut[0] > 0)
            {
                md.append("\n> ⚠️ ").append(unresolvedFilteredOut[0]) //$NON-NLS-1$
                  .append(" marker(s) were excluded from the object filter because their location could not be resolved. ") //$NON-NLS-1$
                  .append("Run clean_project / revalidate_objects, or remove the objects filter, to include them."); //$NON-NLS-1$
            }
            
            return md.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            return ToolResult.error("Failed to get project errors: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
    
    /**
     * Applies the severity/checkId/objects filters to a single marker and, if it passes,
     * builds its {@link ErrorInfo}. Returns {@code null} when the marker is filtered out.
     *
     * <p>Must be called inside a BM read transaction so that
     * {@link Marker#getObjectPresentation()} can resolve. The symbolic check id is resolved
     * exactly once here and reused for both the checkId filter and the resulting
     * {@link ErrorInfo}, avoiding a second {@link ICheckRepository#getUidForShortUid} call.
     * The filter order (severity -> checkId -> objects) is preserved so the
     * {@code unresolvedFilteredOut} counter keeps the same semantics.</p>
     */
    static ErrorInfo buildIfMatches(Marker marker, MarkerSeverity severityFilter, String checkId,
        Set<String> objects, ICheckRepository checkRepository, int[] unresolvedShown, int[] unresolvedFilteredOut)
    {
        // Severity filter
        if (severityFilter != null && marker.getSeverity() != severityFilter)
        {
            return null;
        }
        
        // Resolve the symbolic check id once; reused below for the checkId filter and display.
        String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
        String symbolicCheckId = resolveSymbolicCheckId(marker, shortUid, checkRepository);
        
        // checkId filter: match either the short UID (e.g. "SU23") or the symbolic id
        // (e.g. "semicolon-missing"). The short UID alone is rarely what callers type.
        if (checkId != null && !checkId.isEmpty() && !checkIdMatches(shortUid, symbolicCheckId, checkId))
        {
            return null;
        }
        
        // Resolve the object presentation once; reused for the objects filter and the ErrorInfo.
        // Failure handling differs by context (see below), so we only record the outcome here.
        String objectPresentation = null;
        boolean presentationResolved;
        try
        {
            String p = marker.getObjectPresentation();
            objectPresentation = p != null ? p : ""; //$NON-NLS-1$
            presentationResolved = true;
        }
        catch (Exception e)
        {
            presentationResolved = false;
        }
        
        // Objects filter (FQN matching against the resolved object presentation)
        if (!objects.isEmpty())
        {
            if (!presentationResolved)
            {
                // Cannot resolve the location, so we cannot decide membership for an
                // explicit object filter. The marker is excluded from the result; count it
                // separately so the caller is warned that it was filtered out, not shown.
                unresolvedFilteredOut[0]++;
                return null;
            }
            if (objectPresentation.isEmpty())
            {
                return null;
            }
            
            String presentationLower = objectPresentation.toLowerCase();
            boolean matched = false;
            for (String fqnVariant : objects)
            {
                if (presentationLower.contains(fqnVariant))
                {
                    matched = true;
                    break;
                }
            }
            if (!matched)
            {
                return null;
            }
        }
        
        // Build the ErrorInfo, reusing the already resolved symbolic check id and presentation.
        ErrorInfo error = new ErrorInfo();
        error.checkCode = shortUid;
        error.checkId = symbolicCheckId;
        error.hasDocumentation = symbolicCheckId != null && !symbolicCheckId.isEmpty()
            && GetCheckDescriptionTool.hasCheckDocumentation(symbolicCheckId);
        error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$

        // Structural locator: for a marker that points at a BSL text position the
        // module path + 1-based line live in the marker's own extraInfo map (no model
        // read), so they are safe to read regardless of the transaction boundary. Both
        // stay null for markers that do not resolve to a BSL module location.
        populateModuleLocation(marker, error);
        if (presentationResolved)
        {
            error.objectPresentation = objectPresentation;
        }
        else
        {
            // No objects filter was active (otherwise we would have returned above): keep the
            // marker with a placeholder location instead of dropping it, and count it.
            unresolvedShown[0]++;
            error.objectPresentation = unresolvedPlaceholder(marker);
        }
        return error;
    }
    
    /**
     * Resolves the symbolic check id (e.g. "bsl-legacy-check-expression-type") for a marker's
     * short UID (e.g. "SU23") exactly once. Returns {@code null} when it cannot be resolved.
     */
    static String resolveSymbolicCheckId(Marker marker, String shortUid, ICheckRepository checkRepository)
    {
        if (checkRepository == null || shortUid == null || shortUid.isEmpty() || marker.getProject() == null)
        {
            return null;
        }
        try
        {
            CheckUid uid = checkRepository.getUidForShortUid(shortUid, marker.getProject());
            return uid != null ? uid.getCheckId() : null;
        }
        catch (Exception e)
        {
            // Ignore - caller falls back to the short UID
            return null;
        }
    }
    
    /**
     * Returns true when the user supplied checkId substring matches either the marker
     * short UID or its already resolved symbolic check id.
     */
    static boolean checkIdMatches(String shortUid, String symbolicCheckId, String checkId)
    {
        String needle = checkId.toLowerCase();
        if (shortUid != null && shortUid.toLowerCase().contains(needle))
        {
            return true;
        }
        if (symbolicCheckId != null && symbolicCheckId.toLowerCase().contains(needle))
        {
            return true;
        }
        return false;
    }
    
    /**
     * Placeholder location for a marker whose {@link Marker#getObjectPresentation()} could not
     * be resolved, so the marker is reported instead of being dropped.
     */
    static String unresolvedPlaceholder(Marker marker)
    {
        IProject project = marker.getProject();
        return "<unresolved: " + (project != null ? project.getName() : "?") + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
    
    /**
     * Populates the structural BSL locator ({@code modulePath} + {@code line}) on the
     * {@link ErrorInfo} when the marker points at a position inside a BSL module, leaving
     * both {@code null} otherwise.
     *
     * <p>The locator is read straight from the marker's {@link Marker#getExtraInfo()} map —
     * {@link StandardExtraInfo#TEXT_URI_TO_PROBLEM} (the EMF platform URI of the problem) and
     * {@link StandardExtraInfo#TEXT_LINE} (1-based line). EDT fills these for text/Xtext
     * issues (e.g. BSL editor markers; see {@code BmAwareResourceValidatorListener}). Because
     * the values are plain strings already stored on the marker, reading them touches NO
     * model state and is therefore safe with respect to the BM read-transaction boundary.</p>
     *
     * <p>The module path is only set when the URI genuinely resolves to a {@code .bsl} module
     * under the source folder, so it matches the {@code modulePath} shape accepted by
     * {@code read_module_source} / {@code set_breakpoint}. The line is only set when the path
     * is set, so a caller never gets a line without a module to apply it to.</p>
     */
    static void populateModuleLocation(Marker marker, ErrorInfo error)
    {
        IExtraInfoMap extraInfo = marker.getExtraInfo();
        if (extraInfo == null)
        {
            return;
        }

        String uriToProblem = extraInfo.get(StandardExtraInfo.TEXT_URI_TO_PROBLEM);
        String modulePath = resolveBslModulePath(uriToProblem);
        if (modulePath == null)
        {
            // No BSL module location: leave both null rather than inventing a path.
            return;
        }
        error.modulePath = modulePath;

        Integer line = extraInfo.get(StandardExtraInfo.TEXT_LINE);
        if (line != null && line.intValue() >= 1)
        {
            error.line = line;
        }
    }

    /**
     * Derives a source-folder-relative BSL module path (the shape
     * {@code read_module_source} / {@code set_breakpoint} accept, e.g.
     * {@code "CommonModules/MyModule/Module.bsl"}) from an EMF problem URI string, or
     * {@code null} when the URI is absent, unparseable, or does not point at a {@code .bsl}
     * module under the source folder.
     *
     * <p>The URI is a platform resource URI like
     * {@code platform:/resource/<Project>/src/<modulePath>.bsl#<fragment>}. The fragment is
     * trimmed and the {@code <Project>/src/} prefix is stripped via
     * {@link BslModuleUtils#extractModulePath(String)} (the single source of truth for the
     * {@code /src/} assumption). A URI whose platform path contains no {@code /src/} segment,
     * or whose file extension is not {@code bsl}, yields {@code null} — never a guessed path.</p>
     */
    static String resolveBslModulePath(String uriToProblem)
    {
        if (uriToProblem == null || uriToProblem.isEmpty())
        {
            return null;
        }
        try
        {
            URI uri = URI.createURI(uriToProblem).trimFragment();
            // Only BSL module problems carry a path read_module_source/set_breakpoint can use.
            if (!"bsl".equalsIgnoreCase(uri.fileExtension())) //$NON-NLS-1$
            {
                return null;
            }
            // platform:/resource/<Project>/src/<modulePath>.bsl -> <Project>/src/<modulePath>.bsl
            String platformString = uri.isPlatformResource() ? uri.toPlatformString(true) : null;
            if (platformString == null || platformString.isEmpty())
            {
                return null;
            }
            // extractModulePath returns the part after "/src/"; require the segment to be
            // present so we never hand back a project-relative or unrelated path.
            String marker = "/" + BslModuleUtils.SOURCE_FOLDER + "/"; //$NON-NLS-1$ //$NON-NLS-2$
            if (!platformString.contains(marker))
            {
                return null;
            }
            String modulePath = BslModuleUtils.extractModulePath(platformString);
            return modulePath != null && !modulePath.isEmpty() ? modulePath : null;
        }
        catch (Exception e)
        {
            // A malformed URI is not actionable as a locator; fall back to no location.
            return null;
        }
    }

    /**
     * Helper class to store error info.
     */
    static class ErrorInfo
    {
        String checkCode;          // Short UID like "SU23"
        String checkId;            // Symbolic ID like "bsl-legacy-check-expression-type"
        String message;
        String objectPresentation;
        boolean hasDocumentation;  // Whether documentation exists for this check
        String modulePath;         // Source-folder-relative BSL module path, or null
        Integer line;              // 1-based line inside the module, or null
    }
}
