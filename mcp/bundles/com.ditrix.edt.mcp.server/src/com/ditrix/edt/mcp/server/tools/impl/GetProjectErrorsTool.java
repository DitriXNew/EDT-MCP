/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.BslModuleUtils;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.FormStructureReader;
import com.ditrix.edt.mcp.server.utils.FormValidationException;
import com.ditrix.edt.mcp.server.utils.MetadataNodeResolver;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.PredefinedWriter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.ditrix.edt.mcp.server.utils.XdtoWriter;

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

    /** The loose, backward-compatible SUBSTRING filter over the reported location. */
    static final String PARAM_OBJECTS = "objects"; //$NON-NLS-1$

    /** The EXACT, resolver-backed model-address filter (mutually exclusive with {@link #PARAM_OBJECTS}). */
    static final String PARAM_OBJECT_FQNS = "objectFqns"; //$NON-NLS-1$

    /** structuredContent field: the addresses that resolved to a real model node. */
    static final String KEY_OBJECTS_RESOLVED = "objectsResolved"; //$NON-NLS-1$

    /** structuredContent field: the addresses that resolve to nothing. */
    static final String KEY_OBJECTS_NOT_FOUND = "objectsNotFound"; //$NON-NLS-1$

    /** structuredContent field: the addresses this filter cannot scope at all ({@code fqn} + {@code reason}). */
    static final String KEY_OBJECTS_UNSUPPORTED = "objectsUnsupported"; //$NON-NLS-1$

    /** structuredContent field: the human Markdown report, unchanged in shape. */
    static final String KEY_REPORT = "report"; //$NON-NLS-1$

    /** structuredContent field: how many problem rows the report carries. */
    static final String KEY_PROBLEMS_FOUND = "problemsFound"; //$NON-NLS-1$

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
               "Two MUTUALLY EXCLUSIVE object filters: 'objects' is a loose case-insensitive SUBSTRING match against the reported location (fragments welcome, nothing is reported back); 'objectFqns' takes EXACT model addresses, resolves each one and returns objectsNotFound / objectsUnsupported in structuredContent. " + //$NON-NLS-1$
               "Both accept English or Russian tokens at EVERY level, nested addresses included (e.g. 'Catalog.Products', 'Document.SalesOrder.Form.DocumentForm'). " + //$NON-NLS-1$
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
            .stringArrayProperty(PARAM_OBJECTS, "LOOSE filter: case-insensitive SUBSTRING match of each entry against the reported object location, e.g. ['Catalog.Products'] or ['Document.SalesOrder.Form.DocumentForm']; English or Russian tokens accepted at every level. Deliberate fragments are supported, so an entry that matches nothing is NOT reported back - use objectFqns when you need that. Mutually exclusive with objectFqns (optional)") //$NON-NLS-1$
            .stringArrayProperty(PARAM_OBJECT_FQNS, "EXACT filter: each entry must be the full address of one model node (top object, member, Subsystem chain, Predefined item, form, form member) and is resolved against the model; problems INSIDE the resolved node are reported. Entries that resolve to nothing come back in objectsNotFound and entries this filter cannot scope (XDTO members) in objectsUnsupported, both in structuredContent. Mutually exclusive with objects (optional)") //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT, "Max results; default 100, max 1000 (optional)") //$NON-NLS-1$
            .enumProperty("responseFormat", //$NON-NLS-1$
                "Output verbosity (optional): concise (default) = leaner table without the secondary 'Has docs' column; detailed = full table including 'Has docs'", //$NON-NLS-1$
                "concise", "detailed") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    /**
     * The exact-address call returns a machine-readable payload (the Markdown report plus the
     * {@code objectsResolved} / {@code objectsNotFound} / {@code objectsUnsupported} verdicts) in
     * {@code structuredContent}; every other call keeps the historical Markdown response byte for
     * byte. Mirrors {@code list_projects}' per-call format switch.
     */
    @Override
    public ResponseType getResponseType(Map<String, String> params)
    {
        return exactAddressesOf(params).isEmpty() ? ResponseType.MARKDOWN : ResponseType.JSON;
    }

    /** The cleaned {@code objectFqns} entries of a call (never {@code null}). */
    private static List<String> exactAddressesOf(Map<String, String> params)
    {
        return cleanedEntries(JsonUtils.extractArrayArgument(params, PARAM_OBJECT_FQNS));
    }

    /**
     * Drops {@code null}/blank entries and trims the rest, so a caller that padded the array does
     * not silently get a filter on the empty string.
     *
     * @param raw the parsed array argument, may be {@code null}
     * @return the cleaned entries in request order (never {@code null})
     */
    private static List<String> cleanedEntries(List<String> raw)
    {
        List<String> cleaned = new ArrayList<>();
        if (raw != null)
        {
            for (String entry : raw)
            {
                if (entry != null && !entry.trim().isEmpty())
                {
                    cleaned.add(entry.trim());
                }
            }
        }
        return cleaned;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$

        // Output verbosity: concise (default) trims the secondary 'Has docs' column; // NOSONAR explanatory comment, not commented-out code
        // detailed renders the full historical table. Any absent/blank/unrecognized value
        // falls back to concise (the lean default), never an error.
        String responseFormat = JsonUtils.extractStringArgument(params, "responseFormat"); //$NON-NLS-1$
        boolean detailed = responseFormat != null && responseFormat.equalsIgnoreCase("detailed"); //$NON-NLS-1$

        // Reject an out-of-set severity instead of silently widening the filter to "all".
        if (severity != null && !severity.isEmpty()
            && !SEVERITY_VALUES.contains(severity.toUpperCase()))
        {
            return ToolResult.error("Invalid severity: '" + severity + "'. Must be one of: " //$NON-NLS-1$ //$NON-NLS-2$
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
        
        // Both object filters accept a JSON array (["Catalog.Products"]) or a comma-separated
        // string, via the shared extractArrayArgument helper.
        List<String> objects = cleanedEntries(JsonUtils.extractArrayArgument(params, PARAM_OBJECTS));
        List<String> objectFqns = exactAddressesOf(params);

        // The two filters answer different questions (a fragment that may match many nodes vs one
        // exact address whose existence is asserted), and combining them would silently pick one
        // semantics for the other's entries. Refuse instead of guessing.
        if (!objects.isEmpty() && !objectFqns.isEmpty())
        {
            return ToolResult.error("Use either '" + PARAM_OBJECTS + "' or '" + PARAM_OBJECT_FQNS //$NON-NLS-1$ //$NON-NLS-2$
                + "', not both: '" + PARAM_OBJECTS + "' is a loose substring filter over the reported " //$NON-NLS-1$ //$NON-NLS-2$
                + "location, while '" + PARAM_OBJECT_FQNS + "' resolves each entry as an exact model " //$NON-NLS-1$ //$NON-NLS-2$
                + "address and reports the ones that do not exist. Received " + PARAM_OBJECTS + "=" //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", objects) + " and " + PARAM_OBJECT_FQNS + "=" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + String.join(", ", objectFqns) + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, McpKeys.LIMIT, 100);

        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, defaultLimit);
        limit = Pagination.clampLimit(limit, 1000);

        if (!objectFqns.isEmpty())
        {
            return getProjectErrorsByAddress(projectName, severity, checkId, objectFqns, limit,
                detailed);
        }
        return getProjectErrors(projectName, severity, checkId, objects, limit, detailed);
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
    /**
     * Parses a severity filter name into a {@link MarkerSeverity}. Returns {@code null} for a
     * null/empty input or an unrecognized name, in which case all severities are shown.
     *
     * @param severity the severity name (case-insensitive), may be {@code null}
     * @return the parsed {@link MarkerSeverity}, or {@code null} to apply no severity filter
     */
    private static MarkerSeverity parseSeverityFilter(String severity)
    {
        if (severity != null && !severity.isEmpty())
        {
            try
            {
                return MarkerSeverity.valueOf(severity.toUpperCase());
            }
            catch (IllegalArgumentException e)
            {
                // Invalid severity, will show all
            }
        }
        return null;
    }

    public static String getProjectErrors(String projectName, String severity, String checkId, List<String> objects, int limit, boolean detailed)
    {
        return getProjectErrors(projectName, severity, checkId, objects, limit, detailed, false);
    }

    /**
     * As {@link #getProjectErrors(String, String, String, List, int, boolean)} but with an
     * {@code exactScope} objects filter: segment-boundary matching instead of the default substring
     * (see {@link #excludedByObjectsFilter(Set, boolean, String, int[], boolean)}). Package-private -
     * only validate_xdto_package needs exact per-object scoping (a substring match across
     * prefix-sharing package names would report a sibling package's problems).
     */
    static String getProjectErrors(String projectName, String severity, String checkId,
        List<String> objects, int limit, boolean detailed, boolean exactScope)
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
            final MarkerSeverity finalSeverityFilter = parseSeverityFilter(severity);
            final String finalCheckId = checkId;

            // Validate project if specified
            String projectNotFound = projectNotFoundErrorOrNull(projectName);
            if (projectNotFound != null)
            {
                return projectNotFound;
            }

            final Set<String> finalObjects = buildObjectFilterVariants(objects);

            Map<IProject, List<Marker>> markersByProject = groupMarkersByProject(markerManager, projectName);

            // Markers whose presentation could not be resolved even inside a transaction.
            // They are NOT dropped, but they are surfaced differently depending on context,
            // so we track the two cases separately to keep the warning text honest:
            //  - unresolvedShown: reported in the table with a "<unresolved: ...>" placeholder; // NOSONAR explanatory comment, not commented-out code
            //  - unresolvedFilteredOut: excluded from the result because an explicit objects
            //    filter is active and the location could not be resolved to test membership.
            final int[] unresolvedShown = {0};
            final int[] unresolvedFilteredOut = {0};

            final CollectContext collectContext = new CollectContext(finalSeverityFilter,
                finalCheckId, finalObjects, checkRepository, limit, unresolvedShown,
                unresolvedFilteredOut, exactScope);
            final List<ErrorInfo> errors = collectErrors(markersByProject, bmModelManager,
                collectContext);

            // Build Markdown response for better readability and context efficiency
            StringBuilder md = new StringBuilder();

            if (errors.isEmpty())
            {
                appendNoErrorsSection(md, projectName, severity, objects, PARAM_OBJECTS);
            }
            else
            {
                appendProblemsTable(md, errors, limit, detailed);
            }

            // NOTE: no objectsNotFound here, on purpose. This filter is a documented SUBSTRING
            // test, so a fragment that names no object of its own is a legitimate input, and an
            // entry that matched nothing is indistinguishable from a typo. Only the exact
            // objectFqns input can answer that question - see getProjectErrorsByAddress.
            appendUnresolvedWarnings(md, unresolvedShown, unresolvedFilteredOut);

            return md.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            return ToolResult.error("Failed to get project errors: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Validates an explicit {@code projectName} filter. Returns the ready-to-return JSON error
     * payload when the project is specified but does not exist, or {@code null} when no project
     * was specified or it exists (in which case processing continues).
     *
     * @param projectName the project name filter, may be {@code null}/empty
     * @return the JSON error string to return, or {@code null} to continue
     */
    private static String projectNotFoundErrorOrNull(String projectName)
    {
        if (projectName != null && !projectName.isEmpty())
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }
        }
        return null;
    }

    /**
     * Normalizes the input object FQNs to support both English and Russian metadata type names.
     * For each input FQN, generates all variants (original + English + Russian, lowercased) so
     * markers can be matched regardless of the configuration language. A {@link Set} is used to
     * deduplicate the variants. A {@code null} input yields an empty set.
     *
     * @param objects the requested object FQN filters, may be {@code null}
     * @return the deduplicated, lowercased FQN variants (never {@code null})
     */
    private static Set<String> buildObjectFilterVariants(Collection<String> objects)
    {
        final Set<String> finalObjects = new HashSet<>();
        if (objects != null)
        {
            for (String fqn : objects)
            {
                finalObjects.addAll(MetadataTypeUtils.getAllFqnVariants(fqn));
            }
        }
        return finalObjects;
    }

    // ============================================================================================
    // objectFqns - the EXACT address filter
    // ============================================================================================

    /**
     * The per-request outcome of resolving every {@code objectFqns} entry: the entries that
     * resolved (and therefore scope the marker scan), the ones that resolve to nothing, and the
     * ones this filter cannot scope. Each list keeps the caller's request order and the caller's
     * own spelling, so a machine consumer can match a verdict back to what it sent.
     */
    static final class AddressResolution
    {
        final List<String> resolved = new ArrayList<>();
        final List<String> notFound = new ArrayList<>();
        final List<Map<String, String>> unsupported = new ArrayList<>();
        /**
         * EVERY spelling that actually resolved, across every inspected project: the requested
         * address unless a yo-fallback or a handler's other event spelling hit, in which case the
         * STORED one is here too. This is what scopes the marker scan - filtering on the caller's
         * spelling alone would match no marker even though the object exists.
         *
         * <p>A SET, deliberately not a list parallel to {@link #resolved}: with no
         * {@code projectName} the same requested address can legitimately resolve to DIFFERENT
         * stored spellings in different projects ({@code Catalog.M[yo]d} stored yo-normalized in one
         * project and verbatim in another), and keeping only the first would silently scope every
         * project by one variant. Internal only: the wire keeps the caller's spelling.</p>
         */
        final Set<String> scopeFqns = new LinkedHashSet<>();
        /** A ready JSON error payload when no verdict could be reached at all; {@code null} otherwise. */
        String error;
    }

    /**
     * The {@code objectFqns} variant of {@link #getProjectErrors}: every requested address is
     * resolved against the model FIRST, only the resolved ones scope the marker scan, and the
     * verdicts travel back in {@code structuredContent} next to the Markdown report.
     *
     * <p>Matching is SEGMENT-BOUNDARY scoped ({@code exactScope}): a marker belongs to the request
     * when its location is the resolved node itself or something strictly under it. That is
     * deliberately not string equality on the whole presentation - EDT renders a BSL problem on
     * {@code CommonModule.X} as {@code CommonModule.X.Module}, and a form problem descends into the
     * form's item tree, so equality would report zero problems for a node that clearly has them.</p>
     *
     * @param projectName the project filter, may be {@code null}/empty for all projects
     * @param severity the severity filter, already validated by {@link #execute}
     * @param checkId the check-id substring filter, may be {@code null}
     * @param objectFqns the requested exact addresses (non-empty, already cleaned)
     * @param limit the result limit
     * @param detailed whether to render the full table
     * @return the JSON payload for {@code structuredContent}
     */
    static String getProjectErrorsByAddress(String projectName, String severity, String checkId,
        List<String> objectFqns, int limit, boolean detailed)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            if (markerManager == null)
            {
                return ToolResult.error("IMarkerManager service is not available").toJson(); //$NON-NLS-1$
            }
            String projectNotFound = projectNotFoundErrorOrNull(projectName);
            if (projectNotFound != null)
            {
                return projectNotFound;
            }

            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            AddressResolution resolution =
                resolveAddresses(objectFqns, exactScopeProjects(projectName), bmModelManager);
            if (resolution.error != null)
            {
                return resolution.error;
            }

            final int[] unresolvedShown = {0};
            final int[] unresolvedFilteredOut = {0};
            List<ErrorInfo> errors = Collections.emptyList();
            if (!resolution.resolved.isEmpty())
            {
                // The SCAN is scoped by the spellings that really resolved (see
                // AddressResolution.scopeFqns), not by the caller's - a yo spelling that resolved
                // through the fallback would otherwise match no marker at all.
                CollectContext collectContext = new CollectContext(parseSeverityFilter(severity),
                    checkId, buildObjectFilterVariants(resolution.scopeFqns),
                    Activator.getDefault().getCheckRepository(), limit, unresolvedShown,
                    unresolvedFilteredOut, true);
                errors = collectErrors(groupMarkersByProject(markerManager, projectName),
                    bmModelManager, collectContext);
            }

            StringBuilder md = new StringBuilder();
            if (errors.isEmpty())
            {
                appendNoErrorsSection(md, projectName, severity, objectFqns, PARAM_OBJECT_FQNS);
            }
            else
            {
                appendProblemsTable(md, errors, limit, detailed);
            }
            // Appended AFTER either branch on purpose: a PARTIAL miss (some addresses resolved,
            // some did not) must be visible next to the results too, not only on an empty report.
            appendObjectsNotFoundWarning(md, resolution.notFound);
            appendObjectsUnsupportedWarning(md, resolution.unsupported);
            appendUnresolvedWarnings(md, unresolvedShown, unresolvedFilteredOut);

            return addressPayload(md.toString(), errors.size(), resolution);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors by address", e); //$NON-NLS-1$
            return ToolResult.error("Failed to get project errors: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Assembles the {@code structuredContent} payload of an {@code objectFqns} call: the Markdown
     * report a human reads, plus the three address verdicts a machine consumes. All three verdict
     * lists are ALWAYS emitted (empty when there is nothing to report), so a consumer never has to
     * distinguish "absent" from "none" - the consistency rule the response contract requires.
     *
     * @param report the rendered Markdown report
     * @param problemsFound how many problem rows the report carries
     * @param resolution the per-address verdicts
     * @return the JSON payload
     */
    static String addressPayload(String report, int problemsFound, AddressResolution resolution)
    {
        return ToolResult.success()
            .put(KEY_REPORT, report)
            .put(KEY_PROBLEMS_FOUND, problemsFound)
            .put(KEY_OBJECTS_RESOLVED, resolution.resolved)
            .put(KEY_OBJECTS_NOT_FOUND, resolution.notFound)
            .put(KEY_OBJECTS_UNSUPPORTED, resolution.unsupported)
            .toJson();
    }

    /**
     * The projects an {@code objectFqns} entry is resolved against: the named project alone, or
     * every OPEN workspace project. A closed project is excluded because its metadata model cannot
     * be read at all, so it could only ever contribute a false "not found".
     *
     * @param projectName the project filter, may be {@code null}/empty for all projects
     * @return the projects to resolve in (never {@code null}; possibly empty)
     */
    private static List<IProject> exactScopeProjects(String projectName)
    {
        List<IProject> scope = new ArrayList<>();
        if (projectName != null && !projectName.isEmpty())
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (ctx.isOpen())
            {
                scope.add(ctx.project());
            }
            return scope;
        }
        for (IProject project : ProjectContext.allProjects())
        {
            if (project.isOpen())
            {
                scope.add(project);
            }
        }
        return scope;
    }

    /**
     * Reaches a verdict for every requested address. An address that resolves in ANY inspected
     * project counts as resolved; one that resolves nowhere is reported as missing.
     *
     * <p>The whole answer is refused (via {@link AddressResolution#error}) when NO project in scope
     * could be inspected: without a readable model every address would be declared missing, which
     * is exactly the false verdict this input exists to avoid.</p>
     *
     * @param objectFqns the requested addresses, in request order
     * @param scope the projects to resolve in
     * @param bmModelManager the BM model manager, may be {@code null}
     * @return the resolution (never {@code null})
     */
    static AddressResolution resolveAddresses(List<String> objectFqns, List<IProject> scope,
        IBmModelManager bmModelManager)
    {
        AddressResolution resolution = new AddressResolution();

        // Shape-only verdicts first: an unsupported family needs no model at all, and taking it out
        // here keeps it out of the "could not be inspected" accounting below.
        List<String> candidates = new ArrayList<>();
        for (String fqn : objectFqns)
        {
            String unsupportedReason = unsupportedAddressReason(fqn);
            if (unsupportedReason != null)
            {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("fqn", fqn); //$NON-NLS-1$
                entry.put("reason", unsupportedReason); //$NON-NLS-1$
                resolution.unsupported.add(entry);
            }
            else
            {
                candidates.add(fqn);
            }
        }
        if (candidates.isEmpty())
        {
            return resolution;
        }

        Map<String, Set<String>> found = new LinkedHashMap<>();
        boolean inspectedAny = false;
        for (IProject project : scope)
        {
            IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
            ProjectContext.ConfigurationResult configResult =
                ProjectContext.of(project.getName()).resolveConfiguration();
            Configuration config = configResult.ok() ? configResult.configuration() : null;
            if (bmModel == null || config == null)
            {
                // Not an EDT project, or one whose model is not loaded yet: it cannot answer, and
                // it must not be counted as an inspection either.
                continue;
            }
            // Counted as inspected only when the pass really COMPLETED: a pass that threw decided
            // nothing, so treating it as an inspection would turn its undecided addresses into
            // "not found" (see resolveInProject).
            inspectedAny |= resolveInProject(project, bmModel, config, candidates, found);
        }

        if (!inspectedAny)
        {
            resolution.error = ToolResult.error("Cannot resolve " + PARAM_OBJECT_FQNS //$NON-NLS-1$
                + ": no project in scope could answer - its metadata model is not readable" //$NON-NLS-1$
                + " (still indexing, closed, or not a 1C:EDT project), or a form's content" //$NON-NLS-1$
                + " model could not be read." //$NON-NLS-1$
                + " Wait for indexing to finish, name an indexed project with projectName," //$NON-NLS-1$
                + " check the state with list_projects, or use the loose '" + PARAM_OBJECTS //$NON-NLS-1$
                + "' filter, which needs no resolution.").toJson(); //$NON-NLS-1$
            return resolution;
        }

        for (String fqn : candidates)
        {
            Set<String> resolvedAs = found.get(fqn);
            if (resolvedAs != null && !resolvedAs.isEmpty())
            {
                // The wire keeps the caller's spelling; the scan uses EVERY one that resolved.
                resolution.resolved.add(fqn);
                resolution.scopeFqns.addAll(resolvedAs);
            }
            else
            {
                resolution.notFound.add(fqn);
            }
        }
        return resolution;
    }

    /**
     * Adds to {@code found} every candidate address that resolves in this project, mapped to the
     * spelling(s) that actually resolved HERE (see {@link #addressProbes} and
     * {@link #formMemberScopeSpellings}).
     *
     * <p>The accumulator is a MULTI-map on purpose: with no {@code projectName} the same requested
     * address is offered to every project in scope, and two projects may legitimately store it under
     * different spellings, each of which must scope the scan in its own project. Resolution
     * therefore runs in every project, and only the per-project decision short-circuits.</p>
     *
     * <p>Two boundaries are used, because a form MEMBER does not live in the mdclass model: every
     * other family is decided inside ONE BM read transaction on this project's model, while a form
     * member is decided afterwards through {@link FormElementWriter#readEditableForm}, which opens
     * its own read transaction on the form CONTENT model. Nesting the two would put a read
     * transaction inside a read transaction, so the member addresses are deferred instead.</p>
     *
     * @param project the project being inspected
     * @param bmModel its BM model
     * @param config its configuration
     * @param candidates the addresses to decide
     * @param found the accumulator: requested address -&gt; the spellings that resolved
     * @return {@code true} when the project was really INSPECTED (every address reached a verdict);
     *     {@code false} when the pass threw or a form's content model could not be read, in which
     *     case the affected addresses stay undecided and this project must not count as an
     *     inspection - a project that could not answer must never turn an address into "not found"
     */
    static boolean resolveInProject(IProject project, IBmModel bmModel, Configuration config,
        List<String> candidates, Map<String, Set<String>> found)
    {
        List<DeferredMember> deferred = new ArrayList<>();
        // This project's own decisions: the cross-project accumulator must not short-circuit the
        // probe order here, or the second project's stored spelling would never be discovered.
        Map<String, Set<String>> local = new LinkedHashMap<>();
        try
        {
            BmTransactions.<Void>read(bmModel, "ResolveErrorObjectAddresses", (tx, pm) -> { //$NON-NLS-1$
                for (String fqn : candidates)
                {
                    resolveCandidate(config, fqn, local, deferred);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            // A failure here is a failure to DECIDE, never a "does not exist": leave the addresses
            // undecided so another project in scope can still answer for them, and report the
            // project as NOT inspected so a lone failure refuses the call instead of answering it.
            Activator.logError("Failed to resolve " + PARAM_OBJECT_FQNS + " in project " //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName(), e);
            return false;
        }

        // Addresses whose ONLY attempt failed to read the form content model. They are undecided,
        // exactly like the addresses of a pass that threw - never "not found".
        Set<String> undecided = new HashSet<>();
        for (DeferredMember member : deferred)
        {
            if (local.containsKey(member.requestFqn))
            {
                continue;
            }
            List<String> spellings = formMemberScopeSpellings(project, config, member);
            if (spellings == null)
            {
                undecided.add(member.requestFqn);
            }
            else if (!spellings.isEmpty())
            {
                // A later probe of the same address did decide it after all.
                undecided.remove(member.requestFqn);
                local.put(member.requestFqn, new LinkedHashSet<>(spellings));
            }
        }
        for (Map.Entry<String, Set<String>> entry : local.entrySet())
        {
            found.computeIfAbsent(entry.getKey(), k -> new LinkedHashSet<>()).addAll(entry.getValue());
        }
        return undecided.isEmpty();
    }

    /**
     * Decides ONE candidate address inside the open read transaction: the first probe spelling that
     * resolves wins, and a form-MEMBER probe is deferred out of the transaction instead (see
     * {@link #resolveInProject}).
     *
     * @param config the configuration to resolve against
     * @param fqn the requested address, as the caller wrote it
     * @param found this project's accumulator: requested address -&gt; the spellings that resolved
     * @param deferred the accumulator of form-member probes to decide after the transaction
     */
    private static void resolveCandidate(Configuration config, String fqn,
        Map<String, Set<String>> found, List<DeferredMember> deferred)
    {
        if (found.containsKey(fqn))
        {
            return;
        }
        for (String probe : addressProbes(MetadataTypeUtils.normalizeFqn(fqn)))
        {
            FormElementWriter.FormMemberRef memberRef = formMemberRefOf(probe);
            if (memberRef != null)
            {
                deferred.add(new DeferredMember(fqn, probe, memberRef));
            }
            else if (resolvesInConfiguration(config, probe))
            {
                found.put(fqn, new LinkedHashSet<>(Collections.singletonList(probe)));
                return;
            }
        }
    }

    /**
     * The spellings to try for one address, in order: the address itself and - only when it carries
     * the Russian letter yo - its yo-normalized twin.
     *
     * <p>Addressing is EXACT, but {@code create_metadata} normalizes yo (U+0451/U+0401) to ye
     * (U+0435/U+0415) in names by default, so a caller who re-types the original yo spelling would
     * miss the stored name. The write/delete paths get this from
     * {@link MetadataNodeResolver#resolveExistingWithYoFallback}; this filter applies the same
     * {@link MetadataNodeResolver#yoRetryFqn} retry around the WHOLE family dispatch, so the
     * families that resolver does not reach (forms, form members, Subsystem chains, Predefined
     * items) get the fallback too.</p>
     *
     * @param normFqn the type-normalized address
     * @return the probe spellings in resolution order (never {@code null})
     */
    private static List<String> addressProbes(String normFqn)
    {
        String retry = MetadataNodeResolver.yoRetryFqn(normFqn);
        return retry == null ? Collections.singletonList(normFqn)
            : Arrays.asList(normFqn, retry);
    }

    /**
     * One form-MEMBER probe deferred out of the read transaction (see {@link #resolveInProject}).
     * Package-private so the per-probe decision ({@link #memberScopeSpellings}) can be unit-tested
     * against a synthetic form model.
     */
    static final class DeferredMember
    {
        /** The requested address, as the caller wrote it - the key of the verdict. */
        final String requestFqn;
        /** The spelling being probed (the request, or its yo-normalized twin). */
        final String probeFqn;
        /** The member reference parsed from {@link #probeFqn}. */
        final FormElementWriter.FormMemberRef ref;

        DeferredMember(String requestFqn, String probeFqn, FormElementWriter.FormMemberRef ref)
        {
            this.requestFqn = requestFqn;
            this.probeFqn = probeFqn;
            this.ref = ref;
        }
    }

    /**
     * Whether {@code fqn} addresses a family the {@code objectFqns} filter cannot scope, and why.
     *
     * <p>The only such family today is an XDTO MEMBER. The filter can only compare against
     * {@link Marker#getObjectPresentation()}, and EDT reports every problem of an XDTO package on
     * the package content ({@code XDTOPackage.<Package>.Package}) - never on an ObjectType or a
     * Property. A member address therefore cannot match anything by construction, which is a
     * different fact from "this member does not exist" and must not be reported as one.</p>
     *
     * @param fqn the requested address, as the caller wrote it
     * @return the reason, or {@code null} when the address belongs to a supported family
     */
    static String unsupportedAddressReason(String fqn)
    {
        if (XdtoWriter.parseMemberRef(MetadataTypeUtils.normalizeFqn(fqn)) != null)
        {
            return "XDTO members cannot scope a problem query: EDT reports every problem of an XDTO" //$NON-NLS-1$
                + " package on the package itself (location 'XDTOPackage.<Package>.Package')," //$NON-NLS-1$
                + " never on an ObjectType or a Property, so this address can never match a" //$NON-NLS-1$
                + " marker. Scope to the package instead ('XDTOPackage.<Package>'), or call" //$NON-NLS-1$
                + " validate_xdto_package."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The parsed form-MEMBER reference of {@code normFqn}, or {@code null} when the address is not a
     * form member. A form OBJECT ({@code Type.Object.Form.Name} / {@code CommonForm.Name}) is NOT a
     * member: it is decided against the mdclass model like any other node.
     *
     * @param normFqn the type-normalized address
     * @return the member reference, or {@code null}
     */
    private static FormElementWriter.FormMemberRef formMemberRefOf(String normFqn)
    {
        if (FormElementWriter.parseFormPath(normFqn) != null)
        {
            return null;
        }
        return FormElementWriter.parse(normFqn);
    }

    /**
     * Whether {@code normFqn} names a node that exists in {@code config}, dispatching to the
     * specialized resolver of the address family it belongs to. Form MEMBERS are NOT decided here
     * (see {@link #formMemberExists}); every other supported family is.
     *
     * <p>Call inside a BM read transaction bound to this configuration's model.</p>
     *
     * @param config the configuration to resolve against
     * @param normFqn the type-normalized address
     * @return {@code true} when the address resolves
     */
    static boolean resolvesInConfiguration(Configuration config, String normFqn)
    {
        // A Subsystem chain nests the same kind token repeatedly, which the generic child-feature
        // navigation does not model - SubsystemUtils owns that grammar.
        if (SubsystemUtils.parseSubsystemPath(normFqn) != null)
        {
            return SubsystemUtils.resolveByFqn(config, normFqn) != null;
        }
        // A predefined item is not an mdclass child either: it lives in the owner's predefined tree.
        PredefinedWriter.PredefinedRef predefined = PredefinedWriter.parseRef(normFqn);
        if (predefined != null)
        {
            MetadataNodeResolver.MetadataNode owner =
                MetadataNodeResolver.resolveExisting(config, predefined.ownerFqn());
            return owner != null
                && PredefinedWriter.findByName(owner.object, predefined.itemName) != null;
        }
        // A FORM object: the mdclass metamodel deliberately does not lead into the form package, so
        // the shared node resolver cannot navigate the Form kind - the form reader can.
        String formPath = FormElementWriter.parseFormPath(normFqn);
        if (formPath != null)
        {
            return FormStructureReader.resolveMdForm(config, formPath) != null;
        }
        return MetadataNodeResolver.resolveExisting(config, normFqn) != null;
    }

    /**
     * The spellings that scope the marker scan for one form-MEMBER probe - the LEAF is checked, not
     * just the form containing it. The member lives in the form CONTENT model, so the form is
     * resolved first and the leaf is then looked up inside a read transaction on that content model.
     *
     * <p>The KIND is checked too ({@link FormElementWriter#matchesRequestedKind} for the leaf,
     * {@link FormElementWriter#matchesKindToken} for the OWNER of an item-level handler): both
     * lookups find an ITEM by NAME alone, so {@code ...Form.F.Button.Price} (a FIELD named
     * {@code Price}) and {@code ...Form.F.Button.Price.Handler.OnChange} would otherwise resolve -
     * and then filter the markers by a kind segment no location carries, handing the caller a clean
     * report instead of naming the typo.</p>
     *
     * <p>A HANDLER address additionally scopes by the event's OWN spellings: the lookup accepts the
     * English {@code name} and the Russian {@code nameRu} alike, so an address written
     * {@code ...Handler.[PriIzmenenii]} must not scope a scan whose locations end in
     * {@code Handler.OnChange}.</p>
     *
     * <p>Call OUTSIDE a BM transaction: {@link FormElementWriter#readEditableForm} opens its own.</p>
     *
     * @param project the project owning the form
     * @param config the project configuration
     * @param member the deferred member probe (its ref and the spelling being probed)
     * @return the scan-scoping spellings (never empty) when the form AND the addressed leaf exist;
     *     an EMPTY list when the address is PROVEN absent; and {@code null} when the form content
     *     model could not be read at all - an infrastructure failure decides nothing and must never
     *     be reported as "this address does not exist"
     */
    private static List<String> formMemberScopeSpellings(IProject project, Configuration config,
        DeferredMember member)
    {
        FormElementWriter.FormMemberRef ref = member.ref;
        FormElementWriter.FormEditContext ctx;
        try
        {
            MdObject mdForm = FormStructureReader.resolveMdForm(config, ref.formPath);
            if (mdForm == null)
            {
                // The form itself is absent from this configuration: a decided "not here".
                return Collections.emptyList();
            }
            ctx = FormElementWriter.editContextFor(project, mdForm);
        }
        catch (Exception e)
        {
            // The BM services behind the form are unavailable: nothing was decided.
            Activator.logError(memberResolveFailure(ref), e);
            return null;
        }
        try
        {
            List<String> spellings = FormElementWriter.readEditableForm(ctx,
                "ResolveErrorFormMember", (formModel, tx) -> memberScopeSpellings(formModel, member)); //$NON-NLS-1$
            return spellings != null ? spellings : Collections.<String> emptyList();
        }
        catch (Exception e)
        {
            if (FormValidationException.jsonOf(e) != null)
            {
                // The form carries no editable content model (empty / legacy / not yet built), so it
                // holds no member at all - a decided absence, not an infrastructure failure.
                return Collections.emptyList();
            }
            Activator.logError(memberResolveFailure(ref), e);
            return null;
        }
    }

    /** The log line for a form-member address that could not be decided. */
    private static String memberResolveFailure(FormElementWriter.FormMemberRef ref)
    {
        return "Failed to resolve the form member " + ref.formPath + "." + ref.kindToken + "." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ref.name;
    }

    /**
     * Decides ONE form-member probe on the open form content model: the scoping spellings when it
     * exists, an empty list when it does not.
     *
     * @param formModel the editable form content model (transaction-bound)
     * @param member the deferred member probe
     * @return the scoping spellings, or an empty list when the address addresses nothing
     */
    static List<String> memberScopeSpellings(EObject formModel, DeferredMember member)
    {
        FormElementWriter.FormMemberRef ref = member.ref;
        if (!FormElementWriter.isHandlerToken(ref.kindToken))
        {
            return FormElementWriter.matchesRequestedKind(
                FormElementWriter.resolveFormMember(formModel, ref), ref)
                    ? Collections.singletonList(member.probeFqn) : Collections.<String> emptyList();
        }
        EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
        if (container == null)
        {
            return Collections.emptyList();
        }
        // The OWNER's kind token is part of an item-level handler address. Command is a legal owner
        // and is routed by kind already (resolveHandlerContainer), so it passes this check too.
        if (ref.isItemLevel()
            && !FormElementWriter.matchesKindToken(container, ref.itemKindToken))
        {
            return Collections.emptyList();
        }
        EObject handler = FormElementWriter.findFormHandler(container, ref.name);
        if (handler == null)
        {
            return Collections.emptyList();
        }
        return handlerScopeSpellings(member.probeFqn, handler);
    }

    /**
     * The scan-scoping spellings of a resolved handler address: the probe as written PLUS the same
     * address with the leaf replaced by each spelling the matched event really carries.
     *
     * <p>{@link FormElementWriter#findFormHandler} accepts the English and the Russian event name
     * alike, while a marker location renders ONE of them; scoping by the caller's spelling alone
     * would filter out every problem on the very handler that was just proven to exist.</p>
     *
     * @param probeFqn the probed address (its last segment is the event as the caller wrote it)
     * @param handler the matched event handler
     * @return the spellings, in order, without duplicates
     */
    private static List<String> handlerScopeSpellings(String probeFqn, EObject handler)
    {
        List<String> spellings = new ArrayList<>();
        spellings.add(probeFqn);
        int lastDot = probeFqn.lastIndexOf('.');
        if (lastDot > 0)
        {
            String prefix = probeFqn.substring(0, lastDot + 1);
            for (String eventName : FormElementWriter.eventNameSpellings(handler))
            {
                String spelling = prefix + eventName;
                if (!spellings.contains(spelling))
                {
                    spellings.add(spelling);
                }
            }
        }
        return spellings;
    }

    /**
     * Groups all markers by their owning project in a single pass, honoring an optional
     * {@code projectName} filter. {@link Marker#getProject()} does not touch
     * {@code resolvedDataCache}, so this is safe outside a BM transaction. Grouping once avoids
     * re-streaming all markers per project (previously O(markers x projects)). Marker
     * presentation must still be resolved inside a BM read transaction bound to a single
     * project's model, so the subsequent processing stays project by project.
     *
     * @param markerManager the marker manager supplying the markers
     * @param projectName the project name filter, may be {@code null}/empty for all projects
     * @return markers grouped by project, in encounter order
     */
    private static Map<IProject, List<Marker>> groupMarkersByProject(IMarkerManager markerManager,
        String projectName)
    {
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
        return markersByProject;
    }

    /**
     * Collects matching {@link ErrorInfo} entries from the per-project markers, applying the
     * severity/checkId/objects filters and respecting {@code limit}. Each project's markers are
     * processed inside a BM read transaction (when a model is available) so that
     * {@link Marker#getObjectPresentation()} can resolve lazily; projects without a BM model are
     * processed best-effort. The {@code unresolvedShown}/{@code unresolvedFilteredOut} holders
     * are advanced as markers fail to resolve.
     *
     * @param markersByProject the markers grouped by project, in processing order
     * @param bmModelManager the BM model manager, may be {@code null}
     * @param context the immutable collection context (filters, repository, limit and the
     *     two unresolved-marker out-counters)
     * @return the collected errors, capped at {@code context.limit}
     */
    private static List<ErrorInfo> collectErrors(Map<IProject, List<Marker>> markersByProject,
        IBmModelManager bmModelManager, CollectContext context)
    {
        final List<ErrorInfo> errors = new ArrayList<>();
        for (Map.Entry<IProject, List<Marker>> entry : markersByProject.entrySet())
        {
            if (errors.size() >= context.limit)
            {
                break;
            }

            final List<Marker> projectMarkers = entry.getValue();
            final int remaining = context.limit - errors.size();

            // Resolve the project's BM model so getObjectPresentation() can lazily
            // resolve the marker target inside a read transaction. The getModel(IProject)
            // overload is the idiomatic path used across the plugin (FindReferencesTool,
            // CreateMetadataTool, tag tools), so no IDtProjectManager is needed.
            IBmModel bmModel = bmModelManager != null ? bmModelManager.getModel(entry.getKey()) : null;

            Runnable collector = () -> projectMarkers.stream()
                .map(marker -> buildIfMatches(marker, context.severityFilter, context.checkId,
                    context.objects, context.checkRepository, context.unresolvedShown,
                    context.unresolvedFilteredOut, context.exactScope))
                .filter(Objects::nonNull)
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
        return errors;
    }

    /**
     * Immutable holder for the per-call collection context threaded through {@link #collectErrors}:
     * the severity / checkId / objects filters, the check repository, the result {@code limit} and
     * the two unresolved-marker out-counters. The {@code int[]} counters are shared references whose
     * contents are advanced exactly as before. Bundles the parameters without changing any value.
     */
    private static final class CollectContext
    {
        final MarkerSeverity severityFilter;
        final String checkId;
        final Set<String> objects;
        final ICheckRepository checkRepository;
        final int limit;
        final int[] unresolvedShown;
        final int[] unresolvedFilteredOut;
        final boolean exactScope;

        CollectContext(MarkerSeverity severityFilter, String checkId, Set<String> objects,
            ICheckRepository checkRepository, int limit, int[] unresolvedShown,
            int[] unresolvedFilteredOut, boolean exactScope)
        {
            this.severityFilter = severityFilter;
            this.checkId = checkId;
            this.objects = objects;
            this.checkRepository = checkRepository;
            this.limit = limit;
            this.unresolvedShown = unresolvedShown;
            this.unresolvedFilteredOut = unresolvedFilteredOut;
            this.exactScope = exactScope;
        }
    }

    /**
     * Appends the "No Errors Found" Markdown section, echoing whichever filters were applied
     * (project, severity, object addresses), to {@code md}.
     *
     * @param md the Markdown builder to append to
     * @param projectName the project filter, may be {@code null}/empty
     * @param severity the severity filter, may be {@code null}/empty
     * @param objects the object filters, may be {@code null}/empty
     * @param objectsParam the name of the parameter {@code objects} came from ({@link
     *     #PARAM_OBJECTS} or {@link #PARAM_OBJECT_FQNS}), so the echoed banner names the filter the
     *     caller actually used
     */
    static void appendNoErrorsSection(StringBuilder md, String projectName, String severity,
        List<String> objects, String objectsParam)
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
            // Historical wording for `objects` (an e2e assertion and a golden depend on it); the
            // exact filter names itself so the two reports are not confusable.
            String label = PARAM_OBJECT_FQNS.equals(objectsParam) ? "objectFqns filter" : "Objects filter"; //$NON-NLS-1$ //$NON-NLS-2$
            md.append(label).append(": ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        md.append("\nNo configuration problems match the specified criteria."); //$NON-NLS-1$
    }

    /**
     * Appends the "Configuration Problems" Markdown section — the found-count header, the
     * table header and one row per error — to {@code md}.
     *
     * @param md the Markdown builder to append to
     * @param errors the collected errors (must be non-empty)
     * @param limit the result limit (drives the "limit reached" notice)
     * @param detailed when {@code true} include the secondary {@code Has docs} column
     */
    private static void appendProblemsTable(StringBuilder md, List<ErrorInfo> errors, int limit,
        boolean detailed)
    {
        md.append("# Configuration Problems\n\n"); //$NON-NLS-1$
        md.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
        if (errors.size() >= limit)
        {
            md.append(Pagination.limitReachedNotice(limit));
        }
        md.append("\n\n"); //$NON-NLS-1$

        appendProblemsTableHeader(md, detailed);
        for (ErrorInfo error : errors)
        {
            appendProblemRow(md, error, detailed);
        }
    }

    /**
     * Appends the Configuration Problems table header to {@code md}. Built via the shared
     * {@link MarkdownUtils} table builder so every cell is escaped. concise (default) drops the
     * secondary 'Has docs' column to save tokens; detailed keeps the full historical set of
     * columns. Every essential / actionable column (Description, Location, Module path, Line,
     * Check code) is present in BOTH modes.
     *
     * @param md the Markdown builder to append to
     * @param detailed when {@code true} include the secondary {@code Has docs} column
     */
    private static void appendProblemsTableHeader(StringBuilder md, boolean detailed)
    {
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
    }

    /**
     * Appends a single Configuration Problems table row for {@code error} to {@code md},
     * matching the column set selected by {@code detailed}.
     *
     * @param md the Markdown builder to append to
     * @param error the error to render
     * @param detailed when {@code true} include the secondary {@code Has docs} cell
     */
    private static void appendProblemRow(StringBuilder md, ErrorInfo error, boolean detailed)
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

    /**
     * Appends the human-readable {@code objectsNotFound} block to {@code md} when at least one
     * requested {@code objectFqns} address was PROVEN to resolve to nothing (see
     * {@link #resolveAddresses(List, List, IBmModelManager)}). The same list travels back
     * machine-readably in {@code structuredContent}; this block is the mirror for a human reader.
     * Nothing is appended for an empty list, so a report where every address resolved keeps its
     * shape exactly.
     *
     * @param md the Markdown builder to append to
     * @param objectsNotFound the requested addresses that resolve to nothing, may be
     *     {@code null}/empty
     */
    static void appendObjectsNotFoundWarning(StringBuilder md, List<String> objectsNotFound)
    {
        if (objectsNotFound == null || objectsNotFound.isEmpty())
        {
            return;
        }
        md.append("\n> ⚠️ objectsNotFound: ") //$NON-NLS-1$
          .append(String.join(", ", objectsNotFound)) //$NON-NLS-1$
          .append(" — these addresses match no object in the project(s), so they filtered nothing. ") //$NON-NLS-1$
          .append("Check the name/type token, or list objects with get_metadata_objects."); //$NON-NLS-1$
    }

    /**
     * Appends the human-readable {@code objectsUnsupported} block to {@code md} - the addresses
     * this filter cannot scope at all, each with the reason (see
     * {@link #unsupportedAddressReason(String)}). Kept apart from {@code objectsNotFound} because
     * the two are different facts: "this member does not exist" versus "no marker can ever carry
     * this address". The same entries travel back machine-readably in {@code structuredContent}.
     *
     * @param md the Markdown builder to append to
     * @param objectsUnsupported the {@code fqn} / {@code reason} entries, may be {@code null}/empty
     */
    static void appendObjectsUnsupportedWarning(StringBuilder md,
        List<Map<String, String>> objectsUnsupported)
    {
        if (objectsUnsupported == null || objectsUnsupported.isEmpty())
        {
            return;
        }
        for (Map<String, String> entry : objectsUnsupported)
        {
            md.append("\n> ⚠️ objectsUnsupported: ") //$NON-NLS-1$
              .append(entry.get("fqn")) //$NON-NLS-1$
              .append(" — ") //$NON-NLS-1$
              .append(entry.get("reason")); //$NON-NLS-1$
        }
    }

    /**
     * Surfaces unresolved markers explicitly instead of silently dropping them, appending the
     * two distinct warning blocks to {@code md} when their counters are positive. They are
     * reported separately so each warning matches reality.
     *
     * @param md the Markdown builder to append to
     * @param unresolvedShown count of markers reported with a placeholder location
     * @param unresolvedFilteredOut count of markers excluded by an active object filter
     */
    private static void appendUnresolvedWarnings(StringBuilder md, int[] unresolvedShown,
        int[] unresolvedFilteredOut)
    {
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
              .append("Run clean_project / revalidate_objects, or drop the object filter, to include them."); //$NON-NLS-1$
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
        return buildIfMatches(marker, severityFilter, checkId, objects, checkRepository, unresolvedShown,
            unresolvedFilteredOut, false);
    }

    /**
     * As {@link #buildIfMatches(Marker, MarkerSeverity, String, Set, ICheckRepository, int[], int[])}
     * but threading {@code exactScope} into the objects filter (segment-boundary vs substring - see
     * {@link #excludedByObjectsFilter(Set, boolean, String, int[], boolean)}).
     */
    static ErrorInfo buildIfMatches(Marker marker, MarkerSeverity severityFilter, String checkId,
        Set<String> objects, ICheckRepository checkRepository, int[] unresolvedShown, int[] unresolvedFilteredOut,
        boolean exactScope)
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
        if (excludedByObjectsFilter(objects, presentationResolved, objectPresentation, unresolvedFilteredOut,
            exactScope))
        {
            return null;
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
     * Decides whether the marker is excluded by an explicit objects filter, matching the
     * resolved object presentation against the FQN variants. Returns {@code false} when no
     * objects filter is active. As a side effect, increments {@code unresolvedFilteredOut}
     * for a marker whose presentation could not be resolved while a filter is active (the
     * marker is excluded but counted separately so the caller can warn about it).
     */
    static boolean excludedByObjectsFilter(Set<String> objects, boolean presentationResolved,
        String objectPresentation, int[] unresolvedFilteredOut)
    {
        return excludedByObjectsFilter(objects, presentationResolved, objectPresentation,
            unresolvedFilteredOut, false);
    }

    /**
     * As {@link #excludedByObjectsFilter(Set, boolean, String, int[])} but with an
     * {@code exactScope} mode. When {@code false} (the default get_project_errors behavior) a
     * variant matches by SUBSTRING ({@code contains}) - loose on purpose, so a caller filtering
     * by {@code Catalog.Order} also sees markers on its members. When {@code true} a variant
     * matches only at a SEGMENT BOUNDARY: the presentation must EQUAL the variant or start with
     * {@code variant + "."} (the object itself or a member strictly under it) - so
     * {@code XDTOPackage.P} no longer matches {@code XDTOPackage.P2}'s markers. Used by
     * validate_xdto_package, which needs exact per-package scoping (a substring match across
     * prefix-sharing package names is a false failure).
     */
    static boolean excludedByObjectsFilter(Set<String> objects, boolean presentationResolved,
        String objectPresentation, int[] unresolvedFilteredOut, boolean exactScope)
    {
        if (objects.isEmpty())
        {
            return false;
        }
        if (!presentationResolved)
        {
            // Cannot resolve the location, so we cannot decide membership for an
            // explicit object filter. The marker is excluded from the result; count it
            // separately so the caller is warned that it was filtered out, not shown.
            unresolvedFilteredOut[0]++;
            return true;
        }
        if (objectPresentation.isEmpty())
        {
            return true;
        }

        String presentationLower = objectPresentation.toLowerCase();
        for (String fqnVariant : objects)
        {
            boolean matches = exactScope
                ? presentationLower.equals(fqnVariant) || presentationLower.startsWith(fqnVariant + ".") //$NON-NLS-1$
                : presentationLower.contains(fqnVariant);
            if (matches)
            {
                return false;
            }
        }
        return true;
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
        return symbolicCheckId != null && symbolicCheckId.toLowerCase().contains(needle);
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
