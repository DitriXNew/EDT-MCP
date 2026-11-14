/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.e1c.g5.v8.dt.check.qfix.IFixRepository;
import com.e1c.g5.v8.dt.check.settings.ICheckRepository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.ConfigurationRoot;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MarkerRows;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to get problem summary (counts by project and severity), optionally narrowed by severity,
 * check id and module paths, or aggregated into one table of groups (by check, module or object).
 * Uses EDT IMarkerManager for accessing configuration problems.
 *
 * <p>Two paths, chosen by {@link #hasAggregationArguments(Map)}. Without any of the aggregation
 * arguments the legacy path runs unchanged: it streams the markers outside any transaction because
 * it reads only {@link Marker#getProject()} and {@link Marker#getSeverity()}. With any of them every
 * marker becomes a {@link MarkerRows} row built inside the project's BM read boundary - the object
 * presentation is resolved lazily against BM - and the counts are taken over those rows.</p>
 */
public class GetProblemSummaryTool implements IMcpTool
{
    public static final String NAME = "get_problem_summary"; //$NON-NLS-1$

    /** Default number of groups shown by {@code top}. */
    static final int DEFAULT_TOP = 25;

    /** Upper bound of {@code top}. */
    static final int MAX_TOP = 1000;

    /** How many check ids a module/object group names in its Checks cell. */
    static final int CHECKS_PER_GROUP = 3;

    private static final String PARAM_GROUP_BY = "groupBy"; //$NON-NLS-1$
    private static final String PARAM_TOP = "top"; //$NON-NLS-1$
    private static final String PARAM_SEVERITY = "severity"; //$NON-NLS-1$
    private static final String PARAM_CHECK_ID = "checkId"; //$NON-NLS-1$
    private static final String PARAM_MODULE_PATHS = "modulePaths"; //$NON-NLS-1$

    /** The key of a group whose key is absent: no BSL module (module grouping), no check id, no location. */
    static final String NO_KEY = "-"; //$NON-NLS-1$

    /** The key of the group of markers whose location could not be resolved (object grouping). */
    static final String UNRESOLVED_KEY = "<unresolved>"; //$NON-NLS-1$

    /** The separator between the echoed filters. */
    private static final String FILTER_SEPARATOR = " · "; //$NON-NLS-1$

    /** What one aggregated table groups by. */
    enum GroupBy
    {
        CHECK("check"), MODULE("module"), OBJECT("object"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        final String wire;

        GroupBy(String wire)
        {
            this.wire = wire;
        }

        /** The value for a wire string (case-insensitive), or {@code null} when it names none. */
        static GroupBy of(String value)
        {
            for (GroupBy groupBy : values())
            {
                if (groupBy.wire.equalsIgnoreCase(value.trim()))
                {
                    return groupBy;
                }
            }
            return null;
        }
    }

    /** One marker row of the aggregating path: the shared row plus what the counts need. */
    static class SummaryRow extends MarkerRows.Row
    {
        /** The marker severity; a missing one counts as {@link MarkerSeverity#NONE}, as on the legacy path. */
        MarkerSeverity severity;
        /** Whether the object presentation could not be resolved (the row carries a placeholder). */
        boolean presentationUnresolved;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "See validation problem counts grouped by project, severity, check, module or object. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('get_problem_summary')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Name of the project (optional, all projects if not specified)") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty(PARAM_GROUP_BY, "Aggregate into one table of groups by this key (optional)", //$NON-NLS-1$
                GroupBy.CHECK.wire, GroupBy.MODULE.wire, GroupBy.OBJECT.wire)
            .integerProperty(PARAM_TOP, "Groups shown with groupBy; default 25, max 1000 (optional)") //$NON-NLS-1$
            .enumProperty(PARAM_SEVERITY, "Count only this exact severity (optional); NONE is the level of a marker " //$NON-NLS-1$
                + "EDT stores without a severity, not 'all levels'", //$NON-NLS-1$
                GetProjectErrorsTool.SEVERITY_VALUES.toArray(new String[0]))
            .stringProperty(PARAM_CHECK_ID, "Count only checks whose symbolic id or short UID contains this (optional)") //$NON-NLS-1$
            .stringArrayProperty(PARAM_MODULE_PATHS, "Count only these module paths (optional)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String groupByValue = trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_GROUP_BY));
        String topValue = trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_TOP));
        String severity = trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_SEVERITY));
        String checkId = trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_CHECK_ID));
        List<String> modulePaths = modulePathsOf(params);

        // Reject an out-of-set severity instead of silently widening the filter to "all".
        if (severity != null && !GetProjectErrorsTool.SEVERITY_VALUES.contains(severity.toUpperCase(Locale.ROOT)))
        {
            return ToolResult.error("Invalid severity: '" + severity + "'. Must be one of: " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", GetProjectErrorsTool.SEVERITY_VALUES)).toJson(); //$NON-NLS-1$
        }
        // A non-blank entry that normalises to nothing would silently widen the scope to every module.
        String unusablePaths = MarkerRows.unusableModulePathsError(
            JsonUtils.extractArrayArgument(params, PARAM_MODULE_PATHS));
        if (unusablePaths != null)
        {
            return ToolResult.error(unusablePaths).toJson();
        }
        GroupBy groupBy = groupByValue != null ? GroupBy.of(groupByValue) : null;
        if (groupByValue != null && groupBy == null)
        {
            return ToolResult.error("Invalid groupBy: '" + groupByValue //$NON-NLS-1$
                + "'. Must be one of: check, module, object; omit it for the per-severity totals.").toJson(); //$NON-NLS-1$
        }
        if (topValue != null && groupBy == null)
        {
            return ToolResult.error("top applies only with groupBy: add groupBy (check, module or object) " //$NON-NLS-1$
                + "or drop top.").toJson(); //$NON-NLS-1$
        }

        if (!hasAggregationArguments(params))
        {
            return getProblemSummary(projectName);
        }
        int top = Pagination.clampLimit(JsonUtils.extractIntArgument(params, PARAM_TOP, DEFAULT_TOP), MAX_TOP);
        return getAggregatedSummary(projectName, groupBy, top, severity, checkId, modulePaths);
    }

    /**
     * Whether the call uses any argument beyond {@code projectName}. Without one the legacy report is
     * returned byte for byte; blank values and an empty {@code modulePaths} count as absent.
     */
    static boolean hasAggregationArguments(Map<String, String> params)
    {
        return trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_GROUP_BY)) != null
            || trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_TOP)) != null
            || trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_SEVERITY)) != null
            || trimmedOrNull(JsonUtils.extractStringArgument(params, PARAM_CHECK_ID)) != null
            || !modulePathsOf(params).isEmpty();
    }

    /** The normalised, non-blank {@code modulePaths} of a call, in request order (never {@code null}). */
    private static List<String> modulePathsOf(Map<String, String> params)
    {
        List<String> paths = new ArrayList<>();
        List<String> raw = JsonUtils.extractArrayArgument(params, PARAM_MODULE_PATHS);
        if (raw != null)
        {
            for (String entry : raw)
            {
                String normalized = MarkerRows.normalizeModulePath(entry);
                if (normalized != null)
                {
                    paths.add(normalized);
                }
            }
        }
        return paths;
    }

    private static String trimmedOrNull(String value)
    {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * The aggregating path: narrows by severity / check id / module paths and renders either the
     * legacy totals tables over the narrowed rows ({@code groupBy == null}) or one table of groups.
     */
    private static String getAggregatedSummary(String projectName, GroupBy groupBy, int top, String severity,
        String checkId, List<String> modulePaths)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            if (markerManager == null)
            {
                return ToolResult.error("IMarkerManager service is not available").toJson(); //$NON-NLS-1$
            }
            IProject project = null;
            if (projectName != null && !projectName.isEmpty())
            {
                String building = ProjectStateChecker.buildingErrorOrNull(projectName);
                if (building != null)
                {
                    return ToolResult.error(building).toJson();
                }
                ProjectContext ctx = ProjectContext.of(projectName);
                if (!ctx.exists())
                {
                    return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
                }
                project = ctx.project();
            }

            MarkerSeverity severityFilter = severity != null
                ? MarkerSeverity.valueOf(severity.toUpperCase(Locale.ROOT)) : null;
            Map<IProject, List<Marker>> markersByProject = MarkerRows.groupMarkersByProject(markerManager, projectName);
            List<SummaryRow> rows = collectRows(markersByProject,
                Activator.getDefault().getBmModelManager(), severityFilter, checkId,
                MarkerRows.modulePathKeys(modulePaths), Activator.getDefault().getCheckRepository(),
                Activator.getDefault().getFixRepository(), groupBy == GroupBy.OBJECT);

            String filters = filterLine(projectName, severity, checkId, modulePaths);
            StringBuilder md = new StringBuilder(groupBy == null ? renderTotals(rows, filters)
                : renderGroups(rows, groupBy, top, filters));
            if (severityFilter != null && rows.isEmpty())
            {
                // A severity that leaves nothing must not read like a clean scope (#918): count what the
                // other filters keep at the other levels. Run only on an empty answer, without the model read.
                List<SummaryRow> unfiltered = collectRows(markersByProject, Activator.getDefault().getBmModelManager(),
                    null, checkId, MarkerRows.modulePathKeys(modulePaths), Activator.getDefault().getCheckRepository(),
                    null, false);
                MarkerRows.appendOtherSeveritiesNote(md, severityFilter, severityCounts(unfiltered, severityFilter));
            }
            if (project != null)
            {
                MarkerRows.appendModulePathsNotFoundWarning(md, projectName,
                    MarkerRows.missingModulePaths(project, modulePaths));
            }
            return md.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error getting problem summary", e); //$NON-NLS-1$
            return ToolResult.error("Failed to get problem summary: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Builds the rows of every marker that passes the filters, project by project, inside that
     * project's BM read boundary (the object presentation resolves lazily against BM - hard rule:
     * model access only inside a read transaction). The presentation is read only when
     * {@code resolvePresentation} asks for it (only the object grouping shows it). A project without a BM
     * model never reads it: with {@code resolvePresentation} its rows get the unresolved placeholder and
     * are flagged unresolved.
     *
     * @param markersByProject the markers grouped by project, in processing order
     * @param bmModelManager the BM model manager, may be {@code null}
     * @param severityFilter the exact severity to keep, or {@code null} for all
     * @param checkId the check-id substring filter, or {@code null}
     * @param modulePathKeys the module-path keys ({@link MarkerRows#modulePathKeys}); empty keeps all
     * @param checkRepository resolves the symbolic check id, may be {@code null}
     * @param fixRepository answers whether a check has a registered fix, may be {@code null}
     * @param resolvePresentation whether the rows need the object presentation ({@code groupBy=object})
     * @return the matching rows (never {@code null})
     */
    static List<SummaryRow> collectRows(Map<IProject, List<Marker>> markersByProject, IBmModelManager bmModelManager,
        MarkerSeverity severityFilter, String checkId, Set<String> modulePathKeys, ICheckRepository checkRepository,
        IFixRepository fixRepository, boolean resolvePresentation)
    {
        List<SummaryRow> rows = new ArrayList<>();
        for (Map.Entry<IProject, List<Marker>> entry : markersByProject.entrySet())
        {
            List<Marker> projectMarkers = entry.getValue();
            IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(entry.getKey()) : null;
            if (bmModel != null)
            {
                BmTransactions.<Void> read(bmModel, "SummarizeProjectProblems", (tx, pm) -> { //$NON-NLS-1$
                    collectProjectRows(projectMarkers, severityFilter, checkId, modulePathKeys, checkRepository,
                        fixRepository, resolvePresentation, false, rows);
                    return null;
                });
            }
            else
            {
                // No BM model: the presentation is a model read, so it is never attempted outside a read
                // boundary. Rows that need it get the unresolved placeholder and form the <unresolved> group.
                collectProjectRows(projectMarkers, severityFilter, checkId, modulePathKeys, checkRepository,
                    fixRepository, false, resolvePresentation, rows);
            }
        }
        return rows;
    }

    /**
     * Adds the rows of one project's markers that pass the filters. With {@code resolvePresentation} the
     * object presentation is read (call inside the project's BM read boundary); without it the presentation
     * is never read and the row's {@code objectPresentation} stays {@code null} - or, with
     * {@code markUnresolved}, becomes the unresolved placeholder and the row is flagged unresolved.
     */
    private static void collectProjectRows(List<Marker> projectMarkers, MarkerSeverity severityFilter,
        String checkId, Set<String> modulePathKeys, ICheckRepository checkRepository, IFixRepository fixRepository,
        boolean resolvePresentation, boolean markUnresolved, List<SummaryRow> rows)
    {
        Set<String> noObjects = Collections.emptySet();
        int[] unresolvedShown = {0};
        int[] unresolvedFilteredOut = {0};
        for (Marker marker : projectMarkers)
        {
            int unresolvedBefore = unresolvedShown[0];
            SummaryRow row = MarkerRows.buildIfMatches(marker, severityFilter, checkId, noObjects, modulePathKeys,
                checkRepository, fixRepository, unresolvedShown, unresolvedFilteredOut, false, resolvePresentation,
                SummaryRow::new);
            if (row != null)
            {
                MarkerSeverity severity = marker.getSeverity();
                row.severity = severity != null ? severity : MarkerSeverity.NONE;
                row.presentationUnresolved = unresolvedShown[0] > unresolvedBefore;
                if (markUnresolved)
                {
                    row.objectPresentation = MarkerRows.unresolvedPlaceholder(marker);
                    row.presentationUnresolved = true;
                }
                rows.add(row);
            }
        }
    }

    /**
     * Counts {@code rows} per severity, leaving out {@code excluded} (the level the call filtered on).
     *
     * @param rows the rows collected without the severity filter
     * @param excluded the severity the call asked for
     * @return severity -&gt; count (never {@code null})
     */
    static Map<MarkerSeverity, Integer> severityCounts(List<SummaryRow> rows, MarkerSeverity excluded)
    {
        Map<MarkerSeverity, Integer> counts = new EnumMap<>(MarkerSeverity.class);
        for (SummaryRow row : rows)
        {
            MarkerSeverity severity = row.severity != null ? row.severity : MarkerSeverity.NONE;
            if (severity != excluded)
            {
                counts.merge(severity, 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The one line that echoes the active filters ({@code Project: P · Severity filter: MAJOR · ...}),
     * or an empty string when there is none.
     */
    static String filterLine(String projectName, String severity, String checkId, List<String> modulePaths)
    {
        List<String> parts = new ArrayList<>();
        if (projectName != null && !projectName.isEmpty())
        {
            parts.add("Project: " + projectName); //$NON-NLS-1$
        }
        if (severity != null && !severity.isEmpty())
        {
            parts.add("Severity filter: " + severity.toUpperCase(Locale.ROOT)); //$NON-NLS-1$
        }
        if (checkId != null && !checkId.isEmpty())
        {
            parts.add("checkId filter: " + checkId); //$NON-NLS-1$
        }
        if (modulePaths != null && !modulePaths.isEmpty())
        {
            parts.add("Module paths filter: " + Pagination.boundedListing(modulePaths, 5)); //$NON-NLS-1$
        }
        return String.join(FILTER_SEPARATOR, parts);
    }

    /**
     * The legacy totals report (Overall Totals + By Project) taken over already filtered rows, with the
     * filter line between the title and the totals.
     */
    static String renderTotals(List<SummaryRow> rows, String filterLine)
    {
        Map<String, Map<MarkerSeverity, Integer>> projectSummaries = new HashMap<>();
        Map<MarkerSeverity, Integer> totals = new HashMap<>();
        for (MarkerSeverity sev : MarkerSeverity.values())
        {
            totals.put(sev, 0);
        }
        for (SummaryRow row : rows)
        {
            accumulate(row.project, row.severity, projectSummaries, totals);
        }
        int grandTotal = totals.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder md = new StringBuilder("## Problem Summary\n\n"); //$NON-NLS-1$
        appendFilterLine(md, filterLine);
        appendOverallTotals(md, totals, grandTotal);
        appendByProjectSection(md, projectSummaries);
        return md.toString();
    }

    /** One group of the aggregated table. */
    private static final class Group
    {
        final String key;
        int count;
        MarkerSeverity severity;
        boolean fixRegistered;
        final Set<String> modules = new HashSet<>();
        final Map<String, Integer> checks = new HashMap<>();

        Group(String key)
        {
            this.key = key;
        }
    }

    /**
     * Renders one table of groups: count descending, then key ascending ({@link String#compareTo} - no
     * locale collation, so the order is stable). The first {@code top} groups are printed; the rest are
     * folded into ONE {@code *(N other groups)*} row whose count is their sum, so the Count column always
     * adds up to the Total line. Nothing folded, no other-row.
     *
     * @param rows the filtered rows
     * @param groupBy the grouping key
     * @param top how many groups to print, at least 1
     * @param filterLine the echoed filters, may be empty
     * @return the Markdown report
     */
    static String renderGroups(List<SummaryRow> rows, GroupBy groupBy, int top, String filterLine)
    {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (SummaryRow row : rows)
        {
            Group group = groups.computeIfAbsent(groupKey(row, groupBy), Group::new);
            group.count++;
            MarkerSeverity severity = row.severity != null ? row.severity : MarkerSeverity.NONE;
            group.severity = group.severity == null ? severity : MarkerSeverity.moreSevere(group.severity, severity);
            group.fixRegistered |= row.hasQuickFix;
            if (row.modulePath != null)
            {
                group.modules.add(row.modulePath);
            }
            group.checks.merge(checkKey(row), 1, Integer::sum);
        }
        List<Group> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.<Group> comparingInt(g -> -g.count).thenComparing(g -> g.key));

        int shown = Math.min(top, sorted.size());
        StringBuilder md = new StringBuilder("## Problem Summary (by ").append(groupBy.wire).append(")\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        appendFilterLine(md, filterLine);
        md.append("**Total:** ").append(rows.size()).append(" problems in ").append(sorted.size()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" groups"); //$NON-NLS-1$
        if (sorted.size() > shown)
        {
            md.append(" (showing top ").append(shown).append(')'); //$NON-NLS-1$
        }
        md.append("\n\n"); //$NON-NLS-1$
        if (sorted.isEmpty())
        {
            md.append("*No problems found.*\n"); //$NON-NLS-1$
            return md.toString();
        }

        md.append(groupBy == GroupBy.CHECK
            ? MarkdownUtils.tableHeader("Check code", "Severity", "Count", "Fix registered", "Modules") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            : MarkdownUtils.tableHeader(groupBy == GroupBy.MODULE ? "Module path" : "Object", "Count", "Checks")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (Group group : sorted.subList(0, shown))
        {
            String count = String.valueOf(group.count);
            md.append(groupBy == GroupBy.CHECK
                ? MarkdownUtils.tableRow("`" + group.key + "`", group.severity.name(), count, //$NON-NLS-1$ //$NON-NLS-2$
                    group.fixRegistered ? "yes" : "", String.valueOf(group.modules.size())) //$NON-NLS-1$ //$NON-NLS-2$
                : MarkdownUtils.tableRow(group.key, count, checksCell(group)));
        }
        int foldedGroups = sorted.size() - shown;
        if (foldedGroups > 0)
        {
            int foldedCount = 0;
            for (Group group : sorted.subList(shown, sorted.size()))
            {
                foldedCount += group.count;
            }
            String label = "*(" + foldedGroups + " other groups)*"; //$NON-NLS-1$ //$NON-NLS-2$
            md.append(groupBy == GroupBy.CHECK
                ? MarkdownUtils.tableRow(label, "", String.valueOf(foldedCount), "", "") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                : MarkdownUtils.tableRow(label, String.valueOf(foldedCount), "")); //$NON-NLS-1$
        }
        return md.toString();
    }

    /** The check ids of a group, count descending then id ascending, bounded at {@link #CHECKS_PER_GROUP}. */
    private static String checksCell(Group group)
    {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(group.checks.entrySet());
        entries.sort(Map.Entry.<String, Integer> comparingByValue(Comparator.reverseOrder())
            .thenComparing(Map.Entry.<String, Integer> comparingByKey()));
        List<String> ids = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : entries)
        {
            ids.add(entry.getKey());
        }
        return Pagination.boundedListing(ids, CHECKS_PER_GROUP);
    }

    /** The key of a row under {@code groupBy}. */
    static String groupKey(SummaryRow row, GroupBy groupBy)
    {
        switch (groupBy)
        {
            case CHECK:
                return checkKey(row);
            case MODULE:
                return row.modulePath != null && !row.modulePath.isEmpty() ? row.modulePath : NO_KEY;
            default:
                return objectKey(row);
        }
    }

    /** The check a row belongs to: the symbolic id when known, else the short UID (as in get_project_errors). */
    private static String checkKey(SummaryRow row)
    {
        String id = row.checkId != null && !row.checkId.isEmpty() ? row.checkId : row.checkCode;
        return id != null && !id.isEmpty() ? id : NO_KEY;
    }

    /**
     * The top object of a row's location, in canonical English: the first two segments of the presentation
     * ({@code Справочник.X.Форма.Y.Форма} and {@code Catalog.X.Form.Y.Form} both give {@code Catalog.X}), so a
     * key is stable across a ru/en EDT UI and can be fed back to get_project_errors' {@code objects}. Only the
     * structural TYPE token is translated - the programmatic Name is kept verbatim. The configuration root's
     * token, English or Russian ({@link ConfigurationRoot#isToken}), becomes {@link ConfigurationRoot#FQN}, so
     * a ru and an en root presentation form one {@code Configuration} group; an unresolved location forms one
     * {@link #UNRESOLVED_KEY} group.
     */
    static String objectKey(SummaryRow row)
    {
        if (row.presentationUnresolved)
        {
            return UNRESOLVED_KEY;
        }
        String presentation = row.objectPresentation;
        if (presentation == null || presentation.trim().isEmpty())
        {
            return NO_KEY;
        }
        String[] segments = presentation.trim().split("\\.", -1); //$NON-NLS-1$
        String type = ConfigurationRoot.isToken(segments[0]) ? ConfigurationRoot.FQN : segments[0];
        String top = segments.length >= 2 ? type + "." + segments[1] : type; //$NON-NLS-1$
        return MetadataTypeUtils.toCanonicalEnglishFqn(top);
    }

    private static void appendFilterLine(StringBuilder md, String filterLine)
    {
        if (filterLine != null && !filterLine.isEmpty())
        {
            md.append(filterLine).append("\n\n"); //$NON-NLS-1$
        }
    }

    /**
     * Gets problem summary for project(s) using EDT IMarkerManager.
     * 
     * @param projectName specific project name or null for all projects
     * @return Markdown string with problem summary
     */
    public static String getProblemSummary(String projectName)
    {
        StringBuilder md = new StringBuilder();
        
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return ToolResult.error("IMarkerManager service is not available").toJson(); //$NON-NLS-1$
            }
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                ProjectContext ctx = ProjectContext.of(projectName);
                if (!ctx.exists())
                {
                    return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
                }
            }
            
            // Summary structure: projectName -> severity -> count
            Map<String, Map<MarkerSeverity, Integer>> projectSummaries = new HashMap<>();
            Map<MarkerSeverity, Integer> totals = new HashMap<>();
            
            // Initialize totals
            for (MarkerSeverity sev : MarkerSeverity.values())
            {
                totals.put(sev, 0);
            }
            
            // Get all markers
            Stream<Marker> markerStream = markerManager.markers();
            final String filterProject = projectName;

            markerStream.forEach(marker -> accumulateMarker(marker, filterProject, projectSummaries, totals));

            // Calculate total
            int grandTotal = totals.values().stream().mapToInt(Integer::intValue).sum();
            
            // Build Markdown response
            md.append("## Problem Summary\n\n"); //$NON-NLS-1$
            
            // Totals section
            appendOverallTotals(md, totals, grandTotal);

            // Projects section
            appendByProjectSection(md, projectSummaries);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting problem summary", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
        
        return md.toString();
    }

    /**
     * Folds a single marker into the running counters: skips markers without a
     * project, applies the optional project filter, normalises a missing severity
     * to {@link MarkerSeverity#NONE}, then increments both the per-project map and
     * the overall totals (lazily initialising the per-project entry on first sight).
     *
     * @param marker the marker to fold in
     * @param filterProject project name to keep (others skipped); {@code null}/empty keeps all
     * @param projectSummaries projectName -&gt; (severity -&gt; count); mutated in place
     * @param totals severity -&gt; count overall; mutated in place
     */
    private static void accumulateMarker(Marker marker, String filterProject,
        Map<String, Map<MarkerSeverity, Integer>> projectSummaries, Map<MarkerSeverity, Integer> totals)
    {
        IProject markerProject = marker.getProject();
        if (markerProject == null)
        {
            return;
        }

        String markerProjectName = markerProject.getName();

        // Filter by project if specified
        if (filterProject != null && !filterProject.isEmpty() &&
            !markerProjectName.equals(filterProject))
        {
            return;
        }

        MarkerSeverity severity = marker.getSeverity();
        if (severity == null)
        {
            severity = MarkerSeverity.NONE;
        }

        accumulate(markerProjectName, severity, projectSummaries, totals);
    }

    /**
     * Counts one problem of {@code severity} in {@code projectName}: increments both the per-project map
     * (lazily initialising the project's entry on first sight) and the overall totals. Shared by the legacy
     * marker path and the filtered row path, so both tables are built by the same rule.
     *
     * @param projectName the project the problem belongs to
     * @param severity its severity, never {@code null}
     * @param projectSummaries projectName -&gt; (severity -&gt; count); mutated in place
     * @param totals severity -&gt; count overall; mutated in place
     */
    private static void accumulate(String markerProjectName, MarkerSeverity severity,
        Map<String, Map<MarkerSeverity, Integer>> projectSummaries, Map<MarkerSeverity, Integer> totals)
    {
        // Update project summary
        projectSummaries.computeIfAbsent(markerProjectName, k -> {
            Map<MarkerSeverity, Integer> map = new HashMap<>();
            for (MarkerSeverity sev : MarkerSeverity.values())
            {
                map.put(sev, 0);
            }
            return map;
        });

        Map<MarkerSeverity, Integer> projectCounts = projectSummaries.get(markerProjectName);
        projectCounts.put(severity, projectCounts.get(severity) + 1);

        // Update totals
        totals.put(severity, totals.get(severity) + 1);
    }

    /**
     * Appends the "Overall Totals" table, one row per severity (NONE skipped when zero)
     * plus a TOTAL row, to {@code md}.
     *
     * @param md destination buffer (appended to)
     * @param totals severity -&gt; count overall
     * @param grandTotal the precomputed grand total across all severities
     */
    private static void appendOverallTotals(StringBuilder md, Map<MarkerSeverity, Integer> totals, int grandTotal)
    {
        md.append("### Overall Totals\n\n"); //$NON-NLS-1$
        md.append("| Severity | Count |\n"); //$NON-NLS-1$
        md.append("|----------|-------|\n"); //$NON-NLS-1$

        for (MarkerSeverity sev : MarkerSeverity.values())
        {
            if (sev == MarkerSeverity.NONE && totals.get(sev) == 0)
            {
                continue; // Skip NONE if empty
            }
            md.append("| ").append(sev.name()).append(" | ").append(totals.get(sev)).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("| **TOTAL** | **").append(grandTotal).append("** |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Appends the "By Project" table (one row per project) to {@code md}, or a
     * "No problems found" line when there are no project summaries.
     *
     * @param md destination buffer (appended to)
     * @param projectSummaries projectName -&gt; (severity -&gt; count)
     */
    private static void appendByProjectSection(StringBuilder md,
        Map<String, Map<MarkerSeverity, Integer>> projectSummaries)
    {
        if (!projectSummaries.isEmpty())
        {
            md.append("### By Project\n\n"); //$NON-NLS-1$
            md.append("| Project | Errors | Blocker | Critical | Major | Minor | Trivial | Total |\n"); //$NON-NLS-1$
            md.append("|---------|--------|---------|----------|-------|-------|---------|-------|\n"); //$NON-NLS-1$

            for (Map.Entry<String, Map<MarkerSeverity, Integer>> entry : projectSummaries.entrySet())
            {
                Map<MarkerSeverity, Integer> counts = entry.getValue();
                int projectTotal = sumDisplayedSeverities(counts);

                md.append("| "); //$NON-NLS-1$
                md.append(MarkdownUtils.escapeForTable(entry.getKey()));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.ERRORS, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.BLOCKER, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.CRITICAL, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.MAJOR, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.MINOR, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(counts.getOrDefault(MarkerSeverity.TRIVIAL, 0));
                md.append(" | "); //$NON-NLS-1$
                md.append(projectTotal);
                md.append(" |\n"); //$NON-NLS-1$
            }
        }
        else
        {
            md.append("*No problems found.*\n"); //$NON-NLS-1$
        }
    }

    /**
     * Sums only the six severities rendered as per-project columns
     * (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL).
     *
     * <p>The per-project Total must equal the sum of the displayed columns, so
     * {@link MarkerSeverity#NONE} (and any other non-displayed severity) is excluded.
     * Summing {@code counts.values()} would also count NONE and make Total exceed the
     * visible columns.</p>
     *
     * @param counts severity -&gt; count map for a single project (may omit some keys)
     * @return the sum across the six displayed severity columns
     */
    static int sumDisplayedSeverities(Map<MarkerSeverity, Integer> counts)
    {
        if (counts == null)
        {
            return 0;
        }
        return counts.getOrDefault(MarkerSeverity.ERRORS, 0)
            + counts.getOrDefault(MarkerSeverity.BLOCKER, 0)
            + counts.getOrDefault(MarkerSeverity.CRITICAL, 0)
            + counts.getOrDefault(MarkerSeverity.MAJOR, 0)
            + counts.getOrDefault(MarkerSeverity.MINOR, 0)
            + counts.getOrDefault(MarkerSeverity.TRIVIAL, 0);
    }
}
