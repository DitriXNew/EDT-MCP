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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;

import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.PredefinedItem;
import com._1c.g5.v8.dt.validation.marker.IExtraInfoMap;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com._1c.g5.v8.dt.validation.marker.StandardExtraInfo;
import com.e1c.g5.v8.dt.check.qfix.IFixRepository;
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
               "Both accept English or Russian tokens for the TYPE and for every nested KIND segment of an mdclass / form / Subsystem / Predefined address (e.g. 'Catalog.Products', 'Document.SalesOrder.Form.DocumentForm'); the XDTO grammar is the documented exception - English-only, and an XDTO MEMBER is not a filter address at all (objectFqns answers objectsUnsupported). " + //$NON-NLS-1$
               "A 'Fix registered' column flags rows whose CHECK TYPE has an official EDT auto-fix registered (not a promise this exact marker will produce an applicable fix - apply_quick_fix may still report none available for it); pass that row's Check code (+ Module path + Line) to apply_quick_fix to try applying it. " + //$NON-NLS-1$
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
            .stringArrayProperty(PARAM_OBJECTS, "LOOSE filter: case-insensitive SUBSTRING match of each entry against the reported object location, e.g. ['Catalog.Products'] or ['Document.SalesOrder.Form.DocumentForm']; English or Russian type/kind tokens accepted throughout an mdclass, form, Subsystem or Predefined address (the XDTO grammar is English-only). Deliberate fragments are supported, so an entry that matches nothing is NOT reported back - use objectFqns when you need that. Mutually exclusive with objectFqns (optional)") //$NON-NLS-1$
            .stringArrayProperty(PARAM_OBJECT_FQNS, "EXACT filter: each entry must be the full address of one model node (top object, member, Subsystem chain, Predefined item, form, form member) and is resolved against the model; problems INSIDE the resolved node are reported. An address that resolves AS TYPED scopes exactly that node; only when it resolves to nothing is the yo (e/yo) reading tried, and if several such readings are real the scan covers all of them rather than guessing one. A MEMBER address reports its owner's problems (EDT indexes a marker on the owning object - an attribute's problem on 'Catalog.Products', a form item's on 'Catalog.Products.Form.ItemForm.Form' - never on the member), so the answer is wider than the address, never silently empty. Entries that resolve to nothing come back in objectsNotFound and entries this filter cannot scope (XDTO members) in objectsUnsupported, both in structuredContent. Mutually exclusive with objects (optional)") //$NON-NLS-1$
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
    static List<String> exactAddressesOf(Map<String, String> params)
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
        // Entries are NOT deduplicated here: the verdict lists echo the caller's entries back, so
        // collapsing them would change an externally visible list - and it would miss the real case
        // anyway, since two differently spaced spellings of one address are distinct strings. The
        // expensive work is bounded where it is actually done instead (see resolveInProject: one
        // form-content read per DISTINCT probe, however many entries ask for it).
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
            // Optional: when the fix repository is available, each marker is flagged with
            // hasQuickFix (its check has a registered EDT auto-fix) so the caller knows which
            // rows apply_quick_fix can act on. A null repository simply leaves every row unflagged.
            final IFixRepository fixRepository = Activator.getDefault().getFixRepository();
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

            final Set<String> finalObjects = scanFilterVariants(objects, exactScope);

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
                finalCheckId, finalObjects, checkRepository, fixRepository, limit, unresolvedShown,
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
     * The variants ONE marker scan compares against, derived from {@code exactScope}.
     *
     * <p>THE single producer of filter variants - there is deliberately no second helper and no
     * defaulting overload. A caller that asked for segment-boundary matching gave a full ADDRESS,
     * whose offset is known, so only the one parity may be expanded; a loose entry is a FRAGMENT
     * whose offset is unknown, so both are. {@code validate_xdto_package} is an exact caller, and
     * when this choice was a hardcoded literal at the call site it gave {@code XDTOPackage.Package}
     * the variant {@code xdtopackage.<Paket>}, which matches the markers of a DIFFERENT package
     * named {@code <Paket>} - exact validation reporting a sibling's problems. Taking
     * {@code exactScope} itself, rather than an inverted "fragments" flag, is what keeps a caller
     * from picking the wrong polarity.</p>
     *
     * @param objects the requested entries, may be {@code null}
     * @param exactScope whether the caller asked for segment-boundary matching (a full address)
     * @return the deduplicated, lowercased variants (never {@code null})
     */
    static Set<String> scanFilterVariants(Collection<String> objects, boolean exactScope)
    {
        final Set<String> finalObjects = new HashSet<>();
        if (objects != null)
        {
            for (String fqn : objects)
            {
                finalObjects.addAll(exactScope ? MetadataTypeUtils.getAllFqnVariants(fqn)
                    : MetadataTypeUtils.getAllFragmentVariants(fqn));
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
         * The spellings that scope the marker scan, kept PER PROJECT: project name -&gt; every
         * spelling that resolved THERE (the requested address unless a yo-fallback, a handler's
         * other event spelling or a predefined item's stored name applied, in which case the STORED
         * one is here too, plus the owner node EDT actually reports a member's problems on - see
         * {@link #markerOwnerFqn}).
         *
         * <p>Per PROJECT, deliberately not one global set: with no {@code projectName} the same
         * requested address can legitimately resolve to DIFFERENT stored spellings in different
         * projects ({@code Catalog.M[yo]d} stored yo-normalized in one project and verbatim in
         * another). A single merged set would apply project A's spelling to project B's markers,
         * silently dropping every problem B stores under its own spelling. A project that resolved
         * NOTHING is absent from this map and contributes no marker at all: an exact address is a
         * claim about one node, and a project the node does not live in cannot own its problems.</p>
         *
         * <p>Internal only: the wire keeps the caller's spelling.</p>
         */
        final Map<String, Set<String>> scopeByProject = new LinkedHashMap<>();
        /**
         * Addresses that DID resolve but whose report cannot be complete: requested address -&gt; the
         * projects that could not be consulted about it (closed, still indexing, unreadable). Their
         * problems on that address, if any, are missing from the report, and that has to be said -
         * a partial answer must not read as a full one.
         */
        final Map<String, Set<String>> incompleteFor = new LinkedHashMap<>();
        /** A ready JSON error payload when no verdict could be reached at all; {@code null} otherwise. */
        String error;
    }

    /**
     * What ONE project could decide about the requested addresses: the spellings that resolved
     * HERE, the addresses this project could not decide at all, and whether its resolve pass ran to
     * the end.
     *
     * <p>The undecided set is per ADDRESS on purpose. A single "was anything inspected" flag cannot
     * separate "inspected and absent" from "nobody could look": with no {@code projectName} one
     * project whose form content model is unreadable leaves its addresses undecided while another
     * project completes normally, and a request-wide flag would then let those undecided addresses
     * be reported as {@code objectsNotFound} - the very false verdict this input exists to
     * prevent.</p>
     */
    static final class ProjectResolution
    {
        /** The name of the project this decision belongs to (the key of the per-project scope). */
        final String projectName;
        /** Requested address -&gt; the spellings that resolved in THIS project. */
        final Map<String, Set<String>> resolved = new LinkedHashMap<>();
        /** Requested addresses this project could not decide (infrastructure failure, never absence). */
        final Set<String> undecided = new LinkedHashSet<>();
        /** Whether the read pass ran to the end; {@code false} when it threw and decided nothing. */
        boolean passCompleted;

        ProjectResolution(String projectName)
        {
            this.projectName = projectName;
        }
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
                // The SCAN is scoped by the spellings that really resolved, PER PROJECT (see
                // AddressResolution.scopeByProject) - not by the caller's spelling (a yo spelling
                // that resolved through the fallback would match no marker at all) and not by one
                // merged set (that would apply one project's spelling to another's markers).
                CollectContext collectContext = new CollectContext(parseSeverityFilter(severity),
                    checkId, Collections.<String> emptySet(),
                    Activator.getDefault().getCheckRepository(), Activator.getDefault().getFixRepository(),
                    limit, unresolvedShown, unresolvedFilteredOut, true,
                    filterVariantsByProject(resolution.scopeByProject));
                errors = collectErrors(groupMarkersByProject(markerManager, projectName),
                    bmModelManager, collectContext);
            }

            return addressPayload(assembleAddressReport(errors, projectName, severity, objectFqns,
                limit, detailed, resolution, unresolvedShown, unresolvedFilteredOut),
                errors.size(), resolution);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors by address", e); //$NON-NLS-1$
            return ToolResult.error("Failed to get project errors: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Expands each project's resolved spellings into the lowercased bilingual variants the marker
     * scan compares against, keeping the per-project separation intact.
     *
     * @param scopeByProject project name -&gt; the spellings that resolved there
     * @return project name -&gt; its own filter variants (never {@code null})
     */
    static Map<String, Set<String>> filterVariantsByProject(Map<String, Set<String>> scopeByProject)
    {
        Map<String, Set<String>> variants = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : scopeByProject.entrySet())
        {
            // Full ADDRESSES: these spellings came back from a resolver, so the parity is known.
            variants.put(entry.getKey(), scanFilterVariants(entry.getValue(), true));
        }
        return variants;
    }


    /**
     * Assembles the Markdown an {@code objectFqns} call returns: the table or the empty-report
     * banner, then EVERY caveat that applies.
     *
     * <p>Extracted so a test can pin that each warning is really EMITTED, not merely that its
     * renderer works when called by hand. A revert sweep found this gap the hard way: dropping the
     * partial-answer warning from the report reddened nothing, because the only test called
     * {@link #appendIncompleteScopeWarning} directly.</p>
     *
     * @param errors the collected problem rows
     * @param projectName the project filter, may be {@code null}/empty
     * @param severity the severity filter, may be {@code null}/empty
     * @param objectFqns the requested addresses, echoed in the banner
     * @param limit the result limit
     * @param detailed whether to render the full table
     * @param resolution the per-address verdicts, including the partial-answer map
     * @param unresolvedShown out-counter of markers shown with a placeholder location
     * @param unresolvedFilteredOut out-counter of markers excluded because their location was unknown
     * @return the assembled Markdown
     */
    static String assembleAddressReport(List<ErrorInfo> errors, String projectName, String severity,
        List<String> objectFqns, int limit, boolean detailed, AddressResolution resolution,
        int[] unresolvedShown, int[] unresolvedFilteredOut)
    {
        StringBuilder md = new StringBuilder();
        if (errors.isEmpty())
        {
            appendNoErrorsSection(md, projectName, severity, objectFqns, PARAM_OBJECT_FQNS);
        }
        else
        {
            appendProblemsTable(md, errors, limit, detailed);
        }
        // Appended AFTER either branch on purpose: a PARTIAL miss (some addresses resolved, some did
        // not) must be visible next to the results too, not only on an empty report.
        appendObjectsNotFoundWarning(md, resolution.notFound);
        appendObjectsUnsupportedWarning(md, resolution.unsupported);
        appendIncompleteScopeWarning(md, resolution.incompleteFor);
        appendUnresolvedWarnings(md, unresolvedShown, unresolvedFilteredOut);
        return md.toString();
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
     * The UNIVERSE of projects an {@code objectFqns} entry is judged against: the named project, or
     * every workspace project - CLOSED ones included.
     *
     * <p>A closed project used to be dropped here, which quietly made it invisible to the whole
     * verdict: it was never asked, so it never disagreed, and an address that lives only in it came
     * back as {@code objectsNotFound} while its persisted markers were dropped from the scan. Being
     * unable to consult a project is not the same fact as that project not holding the address, so
     * it stays in the universe and is classified as UNDECIDED like any other unreadable project (see
     * {@link #projectDecision}).</p>
     *
     * @param projectName the project filter, may be {@code null}/empty for all projects
     * @return the projects to judge against (never {@code null}; possibly empty)
     */
    private static List<IProject> exactScopeProjects(String projectName)
    {
        if (projectName != null && !projectName.isEmpty())
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            return ctx.exists() ? Collections.singletonList(ctx.project())
                : Collections.<IProject> emptyList();
        }
        return exactScopeProjects(ProjectContext.allProjects());
    }

    /**
     * The workspace half of {@link #exactScopeProjects(String)}, taking the project list so the
     * membership rule is testable without a live workspace.
     *
     * <p>The rule is deliberately "everything": OPEN or CLOSED, EDT or not. Filtering here is what
     * made a closed project invisible to the verdict; the classification into
     * OWNS / ABSENT / UNKNOWN belongs to {@link #projectDecision}, which is the single place that
     * may decide a project contributes nothing.</p>
     *
     * @param allProjects every project in the workspace
     * @return the universe to judge against (never {@code null})
     */
    static List<IProject> exactScopeProjects(IProject[] allProjects)
    {
        List<IProject> scope = new ArrayList<>();
        if (allProjects != null)
        {
            Collections.addAll(scope, allProjects);
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

        List<String> candidates = new ArrayList<>();
        List<String> resolvable = classifyRequestedAddresses(objectFqns, resolution, candidates);
        if (candidates.isEmpty())
        {
            return resolution;
        }

        List<ProjectResolution> perProject = new ArrayList<>();
        for (IProject project : scope)
        {
            IBmModel bmModel = null;
            Configuration config = null;
            if (project.isOpen())
            {
                // Only an OPEN project can be asked; a closed one goes straight to the unreadable
                // branch instead of being probed (and instead of being silently dropped).
                bmModel = bmModelManager != null ? bmModelManager.getModel(project) : null;
                ProjectContext.ConfigurationResult configResult =
                    ProjectContext.of(project.getName()).resolveConfiguration();
                config = configResult.ok() ? configResult.configuration() : null;
            }
            ProjectResolution decided = projectDecision(project, bmModel, config, resolvable);
            if (decided != null)
            {
                perProject.add(decided);
            }
        }
        foldProjectDecisions(resolution, candidates, resolvable, perProject);
        return resolution;
    }


    /**
     * Splits the requested addresses by what can be decided WITHOUT a model, before any project is
     * consulted.
     *
     * <p>Three outcomes, and the order between them is the point:</p>
     * <ul>
     *   <li>an XDTO MEMBER is {@code objectsUnsupported} - a family this filter cannot scope at
     *       all;</li>
     *   <li>an address whose SHAPE is impossible ({@link #possibleAddressShape}) stays a candidate,
     *       so it still gets a verdict and keeps its place in request order, but is NOT offered to
     *       any project. It is already settled: no configuration anywhere could hold it;</li>
     *   <li>everything else is resolvable and goes to the projects.</li>
     * </ul>
     *
     * <p>Withholding the impossible ones is what keeps model-INDEPENDENT knowledge above
     * model-DEPENDENT uncertainty. Handing them to the projects meant one closed or still-indexing
     * project marked them "undecided", and a call that should have named the typo was refused
     * instead - the same inversion that once made an external-objects project look unreadable
     * rather than incapable.</p>
     *
     * @param objectFqns the requested addresses, in request order
     * @param resolution the resolution being filled (its {@code unsupported} list is populated here)
     * @param candidates out-parameter: every address that still needs a verdict, in request order
     * @return the subset the projects are actually asked about
     */
    static List<String> classifyRequestedAddresses(List<String> objectFqns,
        AddressResolution resolution, List<String> candidates)
    {
        List<String> resolvable = new ArrayList<>();
        for (String fqn : objectFqns)
        {
            // A MALFORMED address is never "unsupported" - it addresses nothing at all, so it must
            // reach the ordinary not-found verdict rather than a family-level explanation.
            String canonical = canonicalAddress(fqn);
            String unsupportedReason = canonical == null ? null : unsupportedAddressReason(canonical);
            if (unsupportedReason != null)
            {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("fqn", fqn); //$NON-NLS-1$
                entry.put("reason", unsupportedReason); //$NON-NLS-1$
                resolution.unsupported.add(entry);
                continue;
            }
            candidates.add(fqn);
            if (possibleAddressShape(fqn))
            {
                resolvable.add(fqn);
            }
        }
        return resolvable;
    }

    /**
     * The three project natures 1C:EDT gives a project that can hold metadata. Declared by
     * {@code com._1c.g5.v8.dt.core} (verified against the installed EDT 2026.1) - a workspace
     * project carrying none of them is an ordinary Eclipse/Java/Maven project.
     *
     * <p>The nature is read from the project description, NOT from the model, on purpose: an EDT
     * project that is still indexing - or one that is CLOSED - has no readable BM model but already
     * carries its nature, which is exactly the case that must be told apart from a non-EDT
     * project.</p>
     */
    private static final List<String> V8_CONFIGURATION_NATURES = Arrays.asList(
        "com._1c.g5.v8.dt.core.V8ConfigurationNature", //$NON-NLS-1$
        "com._1c.g5.v8.dt.core.V8ExtensionNature"); //$NON-NLS-1$

    /**
     * The EDT nature of a project that holds EXTERNAL objects (external reports / data processors).
     *
     * <p>It is a 1C:EDT project, but it has no {@link Configuration} BY DESIGN - not "not yet". So a
     * missing configuration here is knowledge, not a failure to look: such a project can never own an
     * mdclass / form / Subsystem / Predefined address, which is exactly what {@code objectFqns}
     * addresses. Classifying it as unreadable (its nature IS a V8 one) turned ordinary misses into
     * {@code Cannot decide} across a workspace-wide scan.</p>
     */
    private static final List<String> V8_EXTERNAL_OBJECTS_NATURE = Collections.singletonList(
        "com._1c.g5.v8.dt.core.V8ExternalObjectsNature"); //$NON-NLS-1$

    /**
     * What ONE project in scope contributes to the request: a real resolve pass when its model is
     * readable, an UNDECIDED verdict when it is a 1C:EDT project whose model is not (yet) readable,
     * and {@code null} when it is not an EDT project at all and is legitimately skipped.
     *
     * <p>The whole per-project branch lives here, and not inline in the scope loop, so the choice
     * between "skip" and "undecided" is exercised by tests rather than only reasoned about.</p>
     *
     * @param project the project in scope
     * @param bmModel its BM model, or {@code null} when it could not be obtained
     * @param config its configuration, or {@code null} when it could not be resolved
     * @param candidates the addresses this request is asking about
     * @return this project's decision, or {@code null} when it contributes nothing at all
     */
    static ProjectResolution projectDecision(IProject project, IBmModel bmModel, Configuration config,
        List<String> candidates)
    {
        if (bmModel == null || config == null)
        {
            // It cannot answer - but WHY decides everything (see unreadableProjectDecision).
            return unreadableProjectDecision(project, candidates);
        }
        return resolveInProject(project, bmModel, config, candidates);
    }

    /**
     * What a project whose configuration could NOT be read contributes to the request.
     *
     * <p>"No configuration" has THREE causes and they are three different facts. Collapsing any two
     * of them is what produced this defect twice:</p>
     * <ul>
     *   <li><b>Not a 1C:EDT project</b> (ordinary Eclipse/Java/Maven): it has no BM model BY
     *       DEFINITION and cannot hold 1C metadata. It leaves the universe ({@code null}) - treating
     *       it as undecidable would let ONE such project mute the missing-address report for the
     *       whole workspace.</li>
     *   <li><b>An EDT project that holds NO configuration by design</b> - an EXTERNAL OBJECTS
     *       project. Its nature is a V8 one, but it structurally cannot own an mdclass / form /
     *       Subsystem / Predefined address, and that is KNOWLEDGE, not a failure to look. It answers
     *       ABSENT: a completed pass that resolves nothing. Calling it unreadable (its nature is
     *       V8!) turned ordinary misses into {@code Cannot decide} on every workspace-wide scan.</li>
     *   <li><b>An EDT project that DOES hold a configuration but is not readable now</b> - still
     *       indexing, or closed. It could perfectly well hold the address, so it answers UNDECIDED:
     *       skipping it lets another project's completed pass stand in as the inspection, and the
     *       address is then reported in {@code objectsNotFound} - absence "proved" by a project
     *       nobody looked at.</li>
     * </ul>
     *
     * <p>Natures that cannot be determined at all fall into the LAST bucket: unknowable is never
     * evidence that a project holds nothing.</p>
     *
     * @param project the in-scope project whose configuration could not be read
     * @param candidates the addresses this request is asking about
     * @return an UNDECIDED resolution, an ABSENT (completed, empty) one, or {@code null} to leave
     *     the universe entirely
     */
    static ProjectResolution unreadableProjectDecision(IProject project, List<String> candidates)
    {
        Set<String> natures = ProjectContext.naturesOf(project);
        if (natures != null && !containsAny(natures, V8_CONFIGURATION_NATURES))
        {
            if (!containsAny(natures, V8_EXTERNAL_OBJECTS_NATURE))
            {
                // Not a 1C:EDT project at all.
                return null;
            }
            // An external-objects project: KNOWN to hold no addressable metadata, so this is a
            // decided ABSENCE - a completed pass that resolves nothing - not an inability to look.
            ProjectResolution absent = new ProjectResolution(project.getName());
            absent.passCompleted = true;
            return absent;
        }
        // Holds a configuration but could not be read now, or its natures are unknowable.
        ProjectResolution unreadable = new ProjectResolution(project.getName());
        unreadable.undecided.addAll(candidates);
        return unreadable;
    }

    /** Whether {@code natures} carries any of {@code wanted}. */
    private static boolean containsAny(Set<String> natures, List<String> wanted)
    {
        for (String nature : wanted)
        {
            if (natures.contains(nature))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The state of ONE (address, project) pair - the real unit of this filter's knowledge.
     *
     * <p>Everything the tool answers is an aggregation of these. Tracking the state per ADDRESS
     * alone (or per RUN) is what produced three separate defects in a row: a project that could not
     * be consulted disappeared from the verdict, from the scan scope, or from both, and the answer
     * still looked complete.</p>
     */
    enum AddressState
    {
        /** The project was inspected and HOLDS the address. */
        OWNS,
        /** The project was inspected and does NOT hold the address. */
        ABSENT,
        /** The project could not be consulted at all - never evidence of absence. */
        UNKNOWN
    }

    /**
     * The aggregated knowledge about ONE address across every project in the universe.
     *
     * <p>Deliberately separate from the wire shape: {@link #applyWireContract} is the ONLY place
     * that turns this into {@code objectsResolved} / {@code objectsNotFound} / a refusal, so the
     * open question of a fourth {@code objectsUndecided} bucket is a change to that one method and
     * to nothing else.</p>
     */
    static final class AddressKnowledge
    {
        /** Names of the projects that HOLD the address. */
        final Set<String> owners = new LinkedHashSet<>();
        /** Names of the projects that could not be consulted about it. */
        final Set<String> unknown = new LinkedHashSet<>();
        /**
         * The address was decided WITHOUT any model - its shape belongs to no supported grammar, so
         * no configuration anywhere could hold it.
         *
         * <p>A TERMINAL verdict, and that is the point of storing it here rather than re-deriving it
         * at each site. This same ordering - knowledge that needs no model outranks the state of the
         * inspection - was got wrong three times in a row, each time in a different place that had
         * to remember it: the project classification, the withholding from projects, and the two
         * inspection guards. As a property of the verdict it cannot be forgotten: every predicate
         * below already accounts for it, so a new consumer inherits the rule instead of re-stating
         * it.</p>
         */
        boolean settledWithoutModel;

        /** The address exists somewhere we could look. */
        boolean isFound()
        {
            return !settledWithoutModel && !owners.isEmpty();
        }

        /**
         * Nobody could decide it: no owner, and at least one project that could not answer. NOT the
         * same as absent - the difference this whole input exists to preserve.
         */
        boolean isUndecided()
        {
            return !settledWithoutModel && owners.isEmpty() && !unknown.isEmpty();
        }

        /** Proven absent: every project in the universe was consulted and none holds it. */
        boolean isAbsent()
        {
            return settledWithoutModel || (owners.isEmpty() && unknown.isEmpty());
        }

        /**
         * Found, but the answer about it cannot be complete: some project could not be consulted, so
         * its problems on this address (if any) are missing from the report. Saying nothing here is
         * how a partial answer used to pass for a full one.
         */
        boolean isIncomplete()
        {
            return !settledWithoutModel && !owners.isEmpty() && !unknown.isEmpty();
        }
    }

    /**
     * Folds every project's own decision into the request-level verdicts.
     *
     * <p>Two stages, on purpose. First the (address, project) states are collected into one
     * {@link AddressKnowledge} per address - the model. Then {@link #applyWireContract} maps that
     * model onto the response. Every completeness decision this tool makes is taken in the second
     * stage and nowhere else: the marker scan is scoped purely from {@link
     * AddressResolution#scopeByProject}, which is filled here, so a project can no longer be dropped
     * from the verdict and from the scan independently.</p>
     *
     * @param resolution the resolution being filled (its {@code unsupported} entries are already in)
     * @param candidates the addresses that need resolution, in request order
     * @param perProject what each project in the universe decided, in scope order
     */
    static void foldProjectDecisions(AddressResolution resolution, List<String> candidates,
        List<String> resolvable, List<ProjectResolution> perProject)
    {
        Map<String, AddressKnowledge> knowledge = new LinkedHashMap<>();
        for (String fqn : candidates)
        {
            AddressKnowledge known = new AddressKnowledge();
            // Settled by shape alone - see classifyRequestedAddresses. Recorded ONCE, here, and
            // honoured by every predicate from now on.
            known.settledWithoutModel = !resolvable.contains(fqn);
            knowledge.put(fqn, known);
        }

        boolean inspectedAny = false;
        for (ProjectResolution decided : perProject)
        {
            // Counted as inspected only when the pass really COMPLETED: a pass that threw decided
            // nothing, so treating it as an inspection would turn its undecided addresses into
            // "not found" (see resolveInProject).
            inspectedAny |= decided.passCompleted;
            for (String fqn : candidates)
            {
                AddressKnowledge known = knowledge.get(fqn);
                // Independent, NOT else-if: owning and being unable to decide can hold at the
                // same time in the same project, and the answer is then a PARTIAL one.
                if (decided.resolved.containsKey(fqn))
                {
                    known.owners.add(decided.projectName);
                }
                if (decided.undecided.contains(fqn))
                {
                    known.unknown.add(decided.projectName);
                }
                // else: inspected and ABSENT here - it contributes nothing but its own silence.
            }
            if (decided.resolved.isEmpty())
            {
                continue;
            }
            Set<String> projectScope = new LinkedHashSet<>();
            for (Set<String> spellings : decided.resolved.values())
            {
                projectScope.addAll(spellings);
            }
            resolution.scopeByProject.put(decided.projectName, projectScope);
        }

        applyWireContract(resolution, candidates, knowledge, inspectedAny);
    }

    /**
     * The ONLY mapping from the {@link AddressKnowledge} model onto the response contract.
     *
     * <p>Today: {@code OWNS somewhere} -&gt; {@code objectsResolved}; {@code ABSENT everywhere} -&gt;
     * {@code objectsNotFound}; {@code UNDECIDED} -&gt; the call is REFUSED naming those addresses;
     * {@code INCOMPLETE} (found, but some project could not be consulted) -&gt; reported as a warning
     * next to the results, because the report about that address is a partial one.</p>
     *
     * <p>The undecided verdict has no wire field of its own yet - whether it should become a fourth
     * {@code objectsUndecided} bucket is an open question for the tool's owner. Keeping the mapping
     * in ONE method is what makes that decision a local change: the model above already distinguishes
     * all four outcomes regardless of how they are published.</p>
     *
     * @param resolution the resolution being filled
     * @param candidates the addresses that need resolution, in request order
     * @param knowledge what is known about each address across the whole universe
     * @param inspectedAny whether ANY project completed a resolve pass
     */
    private static void applyWireContract(AddressResolution resolution, List<String> candidates,
        Map<String, AddressKnowledge> knowledge, boolean inspectedAny)
    {
        // Both guards below are about what INSPECTION could not settle, so neither may speak for an
        // address that never needed one. They ask the VERDICT rather than re-deriving the rule:
        // an address settled without a model is neither found nor undecided by construction
        // (AddressKnowledge), so it can no longer be swept up by a guard that forgot about it.
        boolean anythingNeededAModel = false;
        for (String fqn : candidates)
        {
            anythingNeededAModel |= !knowledge.get(fqn).settledWithoutModel;
        }
        if (!inspectedAny && anythingNeededAModel)
        {
            resolution.error = ToolResult.error("Cannot resolve " + PARAM_OBJECT_FQNS //$NON-NLS-1$
                + ": no project in scope could answer - its metadata model is not readable" //$NON-NLS-1$
                + " (still indexing, closed, or not a 1C:EDT project), or a form's content" //$NON-NLS-1$
                + " model could not be read." //$NON-NLS-1$
                + " Wait for indexing to finish, name an indexed project with projectName," //$NON-NLS-1$
                + " check the state with list_projects, or use the loose '" + PARAM_OBJECTS //$NON-NLS-1$
                + "' filter, which needs no resolution.").toJson(); //$NON-NLS-1$
            return;
        }

        // An address that resolved NOWHERE but stayed UNDECIDED somewhere is not missing - nobody
        // could look. Reporting it as objectsNotFound is the false verdict this input exists to
        // prevent, so the call is refused instead, exactly as an entirely uninspectable scope is.
        List<String> undecidedMisses = new ArrayList<>();
        for (String fqn : candidates)
        {
            if (knowledge.get(fqn).isUndecided())
            {
                undecidedMisses.add(fqn);
            }
        }
        if (!undecidedMisses.isEmpty())
        {
            resolution.error = ToolResult.error("Cannot decide " + PARAM_OBJECT_FQNS + ": " //$NON-NLS-1$ //$NON-NLS-2$
                + String.join(", ", undecidedMisses) //$NON-NLS-1$
                + " - a project in scope could not be read (a form's content model, or the whole" //$NON-NLS-1$
                + " resolve pass), so nothing can be said about these addresses. They are NOT" //$NON-NLS-1$
                + " reported as missing, because no project ever decided them." //$NON-NLS-1$
                + " Wait for indexing to finish, name the project that owns them with projectName," //$NON-NLS-1$
                + " check the state with list_projects, or use the loose '" + PARAM_OBJECTS //$NON-NLS-1$
                + "' filter, which needs no resolution.").toJson(); //$NON-NLS-1$
            return;
        }

        for (String fqn : candidates)
        {
            AddressKnowledge known = knowledge.get(fqn);
            if (known.isFound())
            {
                // The wire keeps the caller's spelling; the scan uses the per-project spellings.
                resolution.resolved.add(fqn);
                if (known.isIncomplete())
                {
                    // Found, but a project that could hold it too was never consulted: its rows are
                    // missing from the report. Say so instead of letting a partial answer read as a
                    // complete one.
                    resolution.incompleteFor.put(fqn, new LinkedHashSet<>(known.unknown));
                }
            }
            else
            {
                resolution.notFound.add(fqn);
            }
        }
    }

    /**
     * Decides every candidate address against THIS project, mapping the ones that resolve to the
     * spelling(s) that actually resolved here (see {@link #addressProbes},
     * {@link #scopeSpellingsOf} and {@link #formMemberScopeSpellings}).
     *
     * <p>The decision is kept per project on purpose: with no {@code projectName} the same requested
     * address is offered to every project in scope, and two projects may legitimately store it under
     * different spellings, each of which must scope the scan IN ITS OWN project. Merging them would
     * let one project's spelling select the other's markers.</p>
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
     * @return what this project decided: the spellings that resolved HERE, the addresses it could
     *     not decide at all (the pass threw, or a form's content model could not be read - never a
     *     "does not exist"), and whether the pass ran to the end
     */
    static ProjectResolution resolveInProject(IProject project, IBmModel bmModel,
        Configuration config, List<String> candidates)
    {
        ProjectResolution decided = new ProjectResolution(project.getName());
        List<DeferredMember> deferred = new ArrayList<>();
        try
        {
            BmTransactions.<Void>read(bmModel, "ResolveErrorObjectAddresses", (tx, pm) -> { //$NON-NLS-1$
                for (String fqn : candidates)
                {
                    resolveCandidate(config, fqn, decided.resolved, deferred);
                }
                return null;
            });
        }
        catch (Exception e)
        {
            // A failure here is a failure to DECIDE, never a "does not exist": every address this
            // project was asked about stays undecided, so another project in scope can still answer
            // for it and a lone failure refuses the call instead of answering it.
            Activator.logError("Failed to resolve " + PARAM_OBJECT_FQNS + " in project " //$NON-NLS-1$ //$NON-NLS-2$
                + project.getName(), e);
            decided.resolved.clear();
            decided.undecided.addAll(candidates);
            return decided;
        }

        // Addresses whose ONLY attempt failed to read the form content model. They are undecided,
        // exactly like the addresses of a pass that threw - never "not found".
        resolveDeferredMembers(deferred, decided,
            member -> formMemberScopeSpellings(project, config, member));
        decided.passCompleted = true;
        return decided;
    }

    /**
     * Decides the deferred form-MEMBER probes, running the resolver ONCE per distinct probe.
     *
     * <p>The array comes off the wire, and a form member that resolves to nothing is never recorded
     * in {@code resolved}, so without the memo N entries naming the same missing member opened N
     * content-model read transactions - and N differently spaced spellings of one address still
     * would, because they are distinct strings until canonicalization. The memo is keyed on the
     * PROBE, which is already canonical and type-normalized, so the work is bounded by the number of
     * real addresses asked about while the verdict lists still echo every entry the caller sent.</p>
     *
     * <p>The resolver is injected so a test can COUNT the reads: the bound is the whole point of
     * this method, and a test that could not see the call count would be pinning nothing.</p>
     *
     * @param deferred the member probes to decide, in encounter order
     * @param decided this project's accumulating decision
     * @param resolver decides one probe: the scoping spellings, an empty list for a proven absence,
     *     or {@code null} when the content model could not be read at all
     */
    static void resolveDeferredMembers(List<DeferredMember> deferred, ProjectResolution decided,
        java.util.function.Function<DeferredMember, List<String>> resolver)
    {
        Map<String, List<String>> decidedProbes = new HashMap<>();
        Set<String> asTypedResolved = new HashSet<>();
        Set<String> asTypedUndecided = new HashSet<>();
        for (DeferredMember member : deferred)
        {
            if (asTypedResolved.contains(member.requestFqn))
            {
                // Settled EXACTLY - later yo readings are irrelevant.
                continue;
            }
            // The memo key is case-INSENSITIVE because the member lookup is: four casings of one
            // attribute name are one node, and keying on the raw probe let external input multiply
            // the reads just by varying the case.
            // Locale-INDEPENDENT folding: the member lookup uses equalsIgnoreCase, but
            // toLowerCase() without a locale maps 'I' to a dotless i under tr-TR, so the memo key
            // and the lookup would disagree exactly where they must not.
            String memoKey = member.probeFqn.toLowerCase(Locale.ROOT);
            List<String> spellings = decidedProbes.containsKey(memoKey)
                ? decidedProbes.get(memoKey) : resolver.apply(member);
            decidedProbes.put(memoKey, spellings);
            if (spellings == null)
            {
                decided.undecided.add(member.requestFqn);
                if (member.asTyped)
                {
                    // The address AS TYPED could not be DECIDED. A yo reading that happens to
                    // resolve must not overwrite that: it would answer about a DIFFERENT node while
                    // the one the caller named was never looked at, which is the false verdict this
                    // whole input exists to prevent. Undecided wins, and the call is refused.
                    asTypedUndecided.add(member.requestFqn);
                }
            }
            else if (!spellings.isEmpty() && !asTypedUndecided.contains(member.requestFqn))
            {
                // NOT removed from `undecided`: within ONE project a requested address can be
                // both OWNED (one spelling resolved) and UNDECIDED (another spelling's form content
                // could not be read). Letting ownership erase that lost the third state at this
                // seam - the scan was scoped only by the readable spelling, and the markers stored
                // under the unreadable one vanished with no warning and no refusal.
                if (member.asTyped)
                {
                    // EXACT-FIRST: as typed it exists, so no yo reading may widen the scope.
                    decided.resolved.put(member.requestFqn, new LinkedHashSet<>(spellings));
                    asTypedResolved.add(member.requestFqn);
                }
                else
                {
                    // Ambiguous: as typed it resolved to nothing, so EVERY yo reading that resolves
                    // contributes. Stopping at the first scoped the scan to one of two real forms
                    // and called the other's problems absent.
                    decided.resolved.computeIfAbsent(member.requestFqn, k -> new LinkedHashSet<>())
                        .addAll(spellings);
                }
            }
        }
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
        // Canonicalize BEFORE the type normalization, not after: normalizeFqn only rewrites the
        // leading TYPE token and copies the rest verbatim, so a segment with stray whitespace would
        // otherwise reach the resolvers untrimmed - and some of them trim internally, which is how a
        // spaced address could resolve while the scan stayed scoped by the spaced spelling.
        String canonical = canonicalAddress(fqn);
        if (canonical == null)
        {
            // Malformed: it addresses nothing, and must not be repaired into a neighbouring node.
            return;
        }
        List<String> probes = addressProbes(MetadataTypeUtils.normalizeFqn(canonical));
        for (int i = 0; i < probes.size(); i++)
        {
            String probe = probes.get(i);
            // addressProbes puts the address AS TYPED first; the rest are yo readings of it.
            boolean asTyped = i == 0;
            FormElementWriter.FormMemberRef memberRef = formMemberRefOf(probe);
            if (memberRef != null)
            {
                deferred.add(new DeferredMember(fqn, probe, memberRef, asTyped));
            }
            else
            {
                Set<String> storedSet = resolvedSpellings(config, probe);
                if (!storedSet.isEmpty())
                {
                    if (asTyped)
                    {
                        // EXACT-FIRST, exactly like the write/delete resolver
                        // (MetadataNodeResolver.resolveExistingWithYoFallback): when the address AS
                        // TYPED resolves, that IS the answer and no yo reading is considered.
                        // Widening here scoped an unambiguous 'Catalog.M[yo]d' onto a sibling
                        // 'Catalog.M[ye]d' the caller never asked about.
                        found.put(fqn, scopeSpellingsOf(storedSet));
                        return;
                    }
                    // As typed it resolved to NOTHING, so the yo readings are all that is left - and
                    // more than one can be real: with both 'Catalog.M[yo]d' (holding 'V[ye]s') and
                    // 'Catalog.M[ye]d' (holding 'V[yo]s'), 'Catalog.M[yo]d.Attribute.V[yo]s' matches
                    // one by normalizing the ancestor and the other the leaf. Taking whichever came
                    // first scoped the scan to one and called the other's problems absent - a false
                    // clean decided by probe order. Where the input is genuinely ambiguous, every
                    // reading counts.
                    found.computeIfAbsent(fqn, k -> new LinkedHashSet<>())
                        .addAll(scopeSpellingsOf(storedSet));
                }
            }
        }
    }

    /**
     * The scan-scoping spellings of ONE resolved non-form address: the spelling that really
     * resolved, plus the node EDT actually reports its problems on when the address names a MEMBER
     * (see {@link #markerOwnerFqn}).
     *
     * @param resolvedFqn the spelling that resolved
     * @return the spellings, in order, without duplicates
     */
    private static Set<String> scopeSpellingsOf(Set<String> resolvedFqns)
    {
        Set<String> spellings = new LinkedHashSet<>();
        for (String resolvedFqn : resolvedFqns)
        {
            spellings.add(resolvedFqn);
            String owner = markerOwnerFqn(resolvedFqn);
            if (owner != null)
            {
                spellings.add(owner);
            }
        }
        return spellings;
    }

    /**
     * The node a MEMBER address' problems are really reported on, or {@code null} when the address
     * already names that node itself.
     *
     * <p>{@link Marker#getObjectPresentation()} - the ONLY thing this filter can compare against -
     * names the object EDT indexed the problem under, and that is never a member inside it. Verified
     * live on EDT 2026.1: an attribute with no type produces two {@code md-legacy-emf-check} markers
     * whose presentation is the OWNING catalog ({@code Catalog.Catalog}), and a form item's dangling
     * event handler produces a {@code form-legacy-check-event-handler} marker on the form CONTENT
     * object ({@code Catalog.Catalog.Form.ItemForm.Form}) - in neither case is the member itself in
     * the presentation. The member detail lives in {@code Marker#getLocation()}, which this filter
     * deliberately does not read (matching it is a separate feature - see the XDTO note in
     * {@link #unsupportedAddressReason}).</p>
     *
     * <p>So a member address scoped by its own spelling alone matches NOTHING and hands the caller
     * {@code objectsResolved} next to a clean report - the false-clean this input exists to
     * prevent. Scoping by the owning node instead widens the answer to that node's problems, which
     * is visible in every row's Location and never hides one.</p>
     *
     * @param normFqn the type-normalized address that resolved
     * @return the owning node's address, or {@code null} when {@code normFqn} is already it
     */
    static String markerOwnerFqn(String normFqn)
    {
        // A Subsystem chain IS the addressed node - a nested subsystem is a top object of its own.
        if (SubsystemUtils.parseSubsystemPath(normFqn) != null)
        {
            return null;
        }
        // A FORM object's own presentation already starts with its address ("....ItemForm.Form").
        if (FormElementWriter.parseFormPath(normFqn) != null)
        {
            return null;
        }
        String[] parts = normFqn.split("\\."); //$NON-NLS-1$
        if (parts.length <= 2)
        {
            // Type.Name is the object itself, and a one-segment address names nothing narrower.
            return null;
        }
        return parts[0] + "." + parts[1]; //$NON-NLS-1$
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
     * <p>The retry is applied PER NAME SEGMENT, not to the whole address: that normalization is a
     * per-name default of {@code create_metadata}, not a configuration-wide rule, so an ancestor may
     * legitimately keep its yo while a descendant was stored normalized. Rewriting every segment at
     * once resolves neither spelling in that case and reports an existing node as missing. Structural
     * tokens (the even indexes) are never touched - they are canon, not stored values.</p>
     *
     * @param normFqn the type-normalized address
     * @return the probe spellings in resolution order, the address as typed first (never {@code null})
     */
    static List<String> addressProbes(String normFqn)
    {
        String canonical = canonicalAddress(normFqn);
        if (canonical == null)
        {
            // A malformed address resolves to NOTHING: see canonicalAddress for why it must not be
            // repaired into a neighbouring node.
            return Collections.emptyList();
        }
        normFqn = canonical;

        String[] segments = normFqn.split("\\.", -1); //$NON-NLS-1$
        List<Integer> yoSegments = new ArrayList<>();
        for (int i = 0; i < segments.length; i++)
        {
            // Only NAME segments (odd indexes) are stored values; a structural token is already
            // canonical and must not be rewritten by a name-normalization rule.
            if (i % 2 == 1 && MetadataNodeResolver.yoRetryFqn(segments[i]) != null)
            {
                yoSegments.add(i);
            }
        }
        // THE GATE, and it comes BEFORE any enumeration on purpose. The input is external, so the
        // shape must be judged before a single probe is materialized: this used to build 2^n strings
        // first and let the family parse reject the shape afterwards, so ~25-30 yo-bearing segments
        // meant millions of strings and 31+ overflowed `1 << n` outright.
        //
        // A Subsystem chain is excluded because it is resolved LEVEL BY LEVEL instead (see
        // resolvedSpelling) - linear where probing spellings would be exponential.
        if (yoSegments.isEmpty() || SubsystemUtils.parseSubsystemPath(normFqn) != null
            || !enumerableAddressShape(normFqn, yoSegments.size()))
        {
            return Collections.singletonList(normFqn);
        }

        // One probe per SUBSET of the yo-bearing name segments, "as typed" first. Normalizing the
        // WHOLE address instead is wrong whenever the spellings differ per segment: with a catalog
        // stored yo-verbatim and an attribute stored yo-normalized, neither the address as typed nor
        // its fully normalized twin resolves, and an attribute that plainly exists is reported
        // missing. create_metadata's normalization is a per-name DEFAULT, not a configuration-wide
        // rule, so the spellings genuinely mix.
        List<String> probes = new ArrayList<>();
        for (int mask = 0; mask < (1 << yoSegments.size()); mask++)
        {
            String[] probe = segments.clone();
            for (int bit = 0; bit < yoSegments.size(); bit++)
            {
                if ((mask & (1 << bit)) != 0)
                {
                    int index = yoSegments.get(bit);
                    probe[index] = MetadataNodeResolver.yoRetryFqn(segments[index]);
                }
            }
            String candidate = String.join(".", probe); //$NON-NLS-1$
            if (!probes.contains(candidate))
            {
                probes.add(candidate);
            }
        }
        return probes;
    }


    /**
     * The most yo-bearing NAME segments the subset enumeration will ever expand.
     *
     * <p>Four is the deepest a real 1C address goes: {@code Type.Name.TabularSection.Name.
     * Attribute.Name} is three names, and an item-level form handler
     * ({@code Type.Name.Form.Name.Field.Name.Handler.Event}) is four. So the enumeration below is
     * bounded by 2^4 = 16 for every address the documented grammars can produce, and the bound is
     * enforced BEFORE any string is built rather than asserted in a comment.</p>
     */
    private static final int MAX_ENUMERATED_YO_SEGMENTS = 4;

    /**
     * Whether {@code normFqn} is a shape whose yo spellings may be ENUMERATED at all.
     *
     * <p>Two conditions, both checked before a single probe exists. The address must parse as one of
     * the supported grammars - garbage from the wire is rejected here instead of after 2^n strings
     * were built - and its yo-bearing name count must be within
     * {@link #MAX_ENUMERATED_YO_SEGMENTS}.</p>
     *
     * <p>When this says no, the caller probes the address AS TYPED and nothing else. That is a
     * different thing from the depth cap this replaces: that one fell back to the whole-address
     * retry, which resolves a DIFFERENT address and reported a node that exists as missing. Probing
     * only what the caller wrote can never resolve the wrong node - it just adds no spelling, for an
     * address no documented grammar can produce.</p>
     *
     * @param normFqn the type-normalized, canonical address
     * @param yoSegmentCount how many of its NAME segments carry yo
     * @return {@code true} when the subset enumeration may run
     */
    static boolean enumerableAddressShape(String normFqn, int yoSegmentCount)
    {
        return yoSegmentCount <= MAX_ENUMERATED_YO_SEGMENTS && possibleAddressShape(normFqn);
    }

    /**
     * Whether {@code fqn} could name a node in ANY configuration - a decision that needs no model.
     *
     * <p>Every grammar this filter documents is tried; a string that fits none of them addresses
     * nothing anywhere. That is KNOWLEDGE, not a gap: an empty segment, an unknown leading TYPE
     * token, an unrecognized nested KIND and an odd segment count are impossible whatever any
     * project contains, and no amount of reading a model could turn them into a hit.</p>
     *
     * <p>Each grammar is asked in its STRICT form. Several of the parsers below are deliberately
     * lenient for their own callers - they answer "close enough to report on" rather than "could
     * exist" - and a lenient answer HERE is a false gap: the address gets carried into the model as
     * if a project might hold it, and an impossible string ends up undecided instead of absent.</p>
     *
     * <p><b>This filter is SOUND but deliberately NOT COMPLETE, and the direction is not a
     * compromise.</b> Saying "possible" about something impossible costs only the WORDING of the
     * answer when no project could be read: the caller is told "could not decide" instead of "no
     * such address". Saying "impossible" about something real costs a confident "No Errors Found"
     * for an address that has problems - the false all-clear this whole filter exists to prevent.
     * So every question here errs towards possible, and gaps are left open on purpose.</p>
     *
     * <p>Concretely: the owner question below is asked of the mdclass metamodel, which answers for
     * mdclass containment only. Combinations forbidden INSIDE a form - an attribute owning an event
     * handler, a command carrying an event other than its single Action slot - are NOT ruled out
     * here. The form metamodel cannot be imported (the form layer is reflective by canon), so that
     * knowledge is not available without a model, and inventing a parallel copy of it is exactly the
     * duplicated grammar this design refuses. Those addresses stay possible and the model decides
     * them, correctly, as soon as any project can be read.</p>
     *
     *
     * <p>Single source for the shape question: the enumeration gate above asks it too, so the two
     * cannot drift into disagreeing about what a supported address looks like.</p>
     *
     * @param fqn the requested address, as the caller wrote it
     * @return {@code true} when some configuration could hold it
     */
    static boolean possibleAddressShape(String fqn)
    {
        String canonical = canonicalAddress(fqn);
        if (canonical == null)
        {
            return false;
        }
        String normFqn = MetadataTypeUtils.normalizeFqn(canonical);
        return SubsystemUtils.parseSubsystemPath(normFqn) != null
            || predefinedOwnerCanHoldItems(normFqn)
            || (FormElementWriter.parseFormPath(normFqn) != null && formOwnerCanHoldForms(normFqn))
            // The STRICT form question, not the lenient parse: parse accepts any Kind.Name tail, so
            // asking it would call a misspelled kind a shape some configuration might hold.
            || (FormElementWriter.addressesKnownKinds(FormElementWriter.parse(normFqn))
                && formOwnerCanHoldForms(normFqn))
            || isMdclassChain(normFqn);
    }

    /**
     * Whether {@code normFqn} is a predefined-item address whose OWNER type can hold predefined
     * items at all. Predefined items are a containment of only a few types, and the metamodel says
     * which - so {@code Document.Invoice.Predefined.Sample} names nothing anywhere, however well it
     * parses.
     *
     * @param normFqn the type-normalized, canonical address
     * @return {@code true} when the address is a predefined reference on a type that can hold them
     */
    private static boolean predefinedOwnerCanHoldItems(String normFqn)
    {
        PredefinedWriter.PredefinedRef ref = PredefinedWriter.parseRef(normFqn);
        if (ref == null)
        {
            return false;
        }
        String ownerFqn = ref.ownerFqn();
        int dot = ownerFqn.indexOf('.');
        String ownerType = dot < 0 ? ownerFqn : ownerFqn.substring(0, dot);
        return ownerTypeCanContain(ownerType, PREDEFINED_FEATURE);
    }

    /**
     * THE owner question, asked as one thing: {@code typeToken} must name a metadata type that
     * exists at all, AND that type must really carry {@code featureName}.
     *
     * <p>Two separate pieces of model-independent knowledge, deliberately joined here because every
     * caller needs both and none needs one alone. {@link MetadataTypeUtils#typeCanContain} is
     * permissive on tokens it does not recognize - on purpose, so it can never turn a real address
     * into a false miss - which means it cannot rule out an unknown leading type. That second half
     * lived inline at two of the three call sites, in two different spellings, and was simply
     * missing at the third: {@code NoSuchType.X.Predefined.Item} passed the gate and was carried
     * into the projects, where a closed or still-indexing one made it undecided instead of absent.
     * A single entry means a fourth caller inherits both halves instead of remembering them.</p>
     *
     * @param typeToken the OWNER's metadata type token (English/Russian, singular/plural, any case)
     * @param featureName the EMF containment feature the address needs on that owner
     * @return {@code true} only when the type is known and can hold that feature
     */
    private static boolean ownerTypeCanContain(String typeToken, String featureName)
    {
        return MetadataTypeUtils.resolve(typeToken) != null
            && MetadataTypeUtils.typeCanContain(typeToken, featureName);
    }

    /** The EMF containment feature holding a type's predefined items. */
    private static final String PREDEFINED_FEATURE = "predefined"; //$NON-NLS-1$

    /** The EMF containment feature holding a type's owned forms. */
    private static final String FORMS_FEATURE = "forms"; //$NON-NLS-1$

    /**
     * Whether an OWNED-form address names an owner type that can hold forms. The form grammars look
     * at the {@code .Form.} segment and the member kind but never at the leading TYPE, so
     * {@code NoSuchType.X.Form.F} parsed cleanly.
     *
     * <p>A {@code CommonForm.Name} address has no owner to ask - the form IS the top object - so it
     * is passed through untouched.</p>
     *
     * @param normFqn the type-normalized, canonical address
     * @return {@code true} unless the leading type is modelled and provably cannot own forms
     */
    private static boolean formOwnerCanHoldForms(String normFqn)
    {
        String[] p = normFqn.split("\\.", -1); //$NON-NLS-1$
        MetadataTypeUtils.NestedKindInfo kind =
            p.length >= 4 ? MetadataTypeUtils.resolveNestedKind(p[2]) : null;
        if (kind == null || !"Form".equals(kind.getEnglish())) //$NON-NLS-1$
        {
            return true;
        }
        // An owned form needs a real owner TYPE: the form grammars never looked at the leading
        // token, so 'NoSuchType.X.Form.F' parsed cleanly.
        return ownerTypeCanContain(p[0], FORMS_FEATURE);
    }

    /**
     * Whether {@code normFqn} has the {@code Type.Name(.Kind.Name)*} shape with EVERY structural
     * token recognized - the generic mdclass grammar. An unrecognized token means the address
     * addresses nothing, so it never earns an enumeration.
     *
     * @param normFqn the type-normalized, canonical address
     * @return {@code true} when every structural segment is a known type / navigable nested kind
     */
    private static boolean isMdclassChain(String normFqn)
    {
        String[] parts = normFqn.split("\\.", -1); //$NON-NLS-1$
        if (parts.length < 2 || parts.length % 2 != 0
            || MetadataTypeUtils.resolve(parts[0]) == null)
        {
            return false;
        }
        for (int i = 2; i < parts.length; i += 2)
        {
            // The NAVIGABLE-child catalogue, not the segment-alias one. The alias catalogue also
            // holds kinds that exist only INSIDE a form (Field, Group, Button, ...), so asking it
            // let 'Catalog.Products.Field.Code' - a form kind hung directly off a mdclass object -
            // pass as a shape some configuration might hold. Nothing can: the mdclass metamodel has
            // no such containment. Form / Handler are absent here on purpose; they lead out of the
            // mdclass model and the two form grammars above own them.
            String feature = MetadataNodeResolver.featureNameForKind(parts[i]);
            if (feature == null)
            {
                return false;
            }
            // ...and, at the FIRST level, whether THIS owner can hold that child at all: 'Column' is
            // a real kind but a containment of DocumentJournal, so 'Catalog.Products.Column.Number'
            // names nothing in any configuration. Only the first level is checked because deeper
            // owners are not named by a type token (the owner of an Attribute under a TabularSection
            // is the tabular section, which no segment types), and a gap here is the safe direction.
            if (i == 2 && !ownerTypeCanContain(parts[0], feature))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code fqn} with each segment trimmed, or {@code null} when it is not a well-formed address.
     *
     * <p>Two different kinds of sloppiness, answered differently on purpose - {@code objectFqns} is
     * the EXACT input, so it may normalize a SPELLING but must never guess an INTENT:</p>
     * <ul>
     *   <li><b>Whitespace around a segment</b> ({@code "Catalog. Products"}) is normalized. There is
     *       exactly ONE reading, nothing is being guessed, and the whole entry is already trimmed by
     *       {@link #cleanedEntries} - trimming the outside but not the inside would be arbitrary.
     *       It also matters for correctness: {@code SubsystemUtils} and {@code PredefinedWriter}
     *       trim internally, so an untrimmed address could RESOLVE while the scan was scoped by the
     *       spaced spelling, which matches no marker - a clean report for a node that has
     *       problems.</li>
     *   <li><b>An EMPTY segment</b> ({@code "Catalog.Products."}, {@code ".Catalog.Products"},
     *       {@code "Catalog..Products"}, {@code "."}) is REFUSED. It has no single reading:
     *       {@code Catalog.Products.} could be the object, or a member whose name the caller failed
     *       to type. {@code MetadataNodeResolver} drops a trailing empty segment when it splits, so
     *       the address resolved to the NEIGHBOURING node {@code Catalog.Products} while the scan
     *       stayed scoped by {@code catalog.products.} - which matches neither
     *       {@code Catalog.Products} nor {@code Catalog.Products.Module}. The caller got
     *       {@code objectsResolved} next to "# No Errors Found": the false all-clear this input
     *       exists to prevent, produced by a typo.</li>
     * </ul>
     *
     * <p>Refused means it resolves to nothing and is reported in {@code objectsNotFound} - the same
     * verdict a misspelt token gets. It is deliberately NOT a call-level error: the array supports
     * partial success (one good address plus one typo returns the good one's problems AND names the
     * typo), and failing the whole call over one bad entry would throw that away.</p>
     *
     * @param fqn the requested address (already trimmed as a whole)
     * @return the segment-trimmed address, or {@code null} when any segment is empty
     */
    static String canonicalAddress(String fqn)
    {
        if (fqn == null || fqn.isEmpty())
        {
            return null;
        }
        String[] segments = fqn.split("\\.", -1); //$NON-NLS-1$
        StringBuilder canonical = new StringBuilder(fqn.length());
        for (int i = 0; i < segments.length; i++)
        {
            String segment = segments[i].trim();
            if (segment.isEmpty())
            {
                return null;
            }
            if (i > 0)
            {
                canonical.append('.');
            }
            canonical.append(segment);
        }
        return canonical.toString();
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
        /** Whether this probe is the address AS TYPED (exact) rather than a yo reading of it. */
        final boolean asTyped;

        DeferredMember(String requestFqn, String probeFqn, FormElementWriter.FormMemberRef ref)
        {
            this(requestFqn, probeFqn, ref, true);
        }

        DeferredMember(String requestFqn, String probeFqn, FormElementWriter.FormMemberRef ref,
            boolean asTyped)
        {
            this.requestFqn = requestFqn;
            this.probeFqn = probeFqn;
            this.ref = ref;
            this.asTyped = asTyped;
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
     * The spelling {@code normFqn} really resolved to in {@code config}, dispatching to the
     * specialized resolver of the address family it belongs to, or {@code null} when it resolves to
     * nothing. Form MEMBERS are NOT decided here (see {@link #formMemberScopeSpellings}); every
     * other supported family is.
     *
     * <p>The STORED spelling is returned, not the probed one, because it is what scopes the marker
     * scan. Every family here is asked EXACTLY, so the probe IS the stored spelling (case aside,
     * which the lowercased filter variants already absorb) - except a Subsystem chain, whose own
     * resolver reports the stored names of every level it walked.</p>
     *
     * <p>Yo (U+0451) tolerance belongs to the CALLER, which enumerates the spellings and probes each
     * one exactly. A resolver that retried internally would answer the as-typed probe with a
     * differently spelled node; the caller would stop enumerating on that hit, scope the scan by a
     * name the model does not store, and never look for the other nodes the address can mean.</p>
     *
     * <p>Call inside a BM read transaction bound to this configuration's model.</p>
     *
     * @param config the configuration to resolve against
     * @param normFqn the type-normalized address
     * @return the resolved (stored) spelling, or {@code null} when the address resolves to nothing
     */
    static Set<String> resolvedSpellings(Configuration config, String normFqn)
    {
        // A Subsystem chain nests the same kind token repeatedly, which the generic child-feature
        // navigation does not model - SubsystemUtils owns that grammar. It is also the only family
        // whose depth is UNBOUNDED, so its yo fallback is applied level by level (linear, and it
        // never builds a combination) rather than by probing whole-address spellings.
        if (SubsystemUtils.parseSubsystemPath(normFqn) != null)
        {
            Set<String> chains = new LinkedHashSet<>();
            for (String[] stored : SubsystemUtils.resolveStoredChain(config, normFqn))
            {
                // EVERY real chain the address can mean scopes the scan (see resolveStoredChain).
                chains.add(storedSubsystemFqn(normFqn, stored));
            }
            return chains;
        }
        // A predefined item is not an mdclass child either: it lives in the owner's predefined tree.
        PredefinedWriter.PredefinedRef predefined = PredefinedWriter.parseRef(normFqn);
        if (predefined != null)
        {
            MetadataNodeResolver.MetadataNode owner =
                MetadataNodeResolver.resolveExisting(config, predefined.ownerFqn());
            if (owner == null)
            {
                return Collections.emptySet();
            }
            // EXACT: the lenient findByName would answer the as-typed probe for an item stored
            // under the other yo spelling, ending the caller's enumeration on the first owner and
            // hiding the problems of every other item that address can mean.
            PredefinedItem item = PredefinedWriter.findByNameExact(owner.object, predefined.itemName);
            return item == null ? Collections.<String> emptySet()
                : Collections.singleton(normFqn);
        }
        // A FORM object: the mdclass metamodel deliberately does not lead into the form package, so
        // the shared node resolver cannot navigate the Form kind - the form reader can.
        String formPath = FormElementWriter.parseFormPath(normFqn);
        if (formPath != null)
        {
            return FormStructureReader.resolveMdForm(config, formPath) != null
                ? Collections.singleton(normFqn) : Collections.<String> emptySet();
        }
        return MetadataNodeResolver.resolveExisting(config, normFqn) != null
            ? Collections.singleton(normFqn) : Collections.<String> emptySet();
    }

    /**
     * Rebuilds a subsystem chain address with the STORED names, keeping the caller's own kind tokens
     * (the bilingual expansion translates those later anyway).
     *
     * @param normFqn the requested chain address
     * @param storedNames the names as the model really stores them, one per level
     * @return the address with every name replaced by its stored spelling
     */
    private static String storedSubsystemFqn(String normFqn, String[] storedNames)
    {
        String[] segments = normFqn.split("\\.", -1); //$NON-NLS-1$
        for (int level = 0; level < storedNames.length; level++)
        {
            segments[level * 2 + 1] = storedNames[level];
        }
        return String.join(".", segments); //$NON-NLS-1$
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
            return addressesTheRequestedKind(
                FormElementWriter.resolveFormMember(formModel, ref), ref.kindToken)
                    ? scopedByOwningForm(member, Collections.singletonList(member.probeFqn))
                    : Collections.<String> emptyList();
        }
        EObject container = FormElementWriter.resolveHandlerContainer(formModel, ref);
        if (container == null)
        {
            return Collections.emptyList();
        }
        // The OWNER's kind token is part of an item-level handler address. Command is a legal owner
        // and is routed by kind already (resolveHandlerContainer), so it passes this check too.
        if (ref.isItemLevel() && !addressesTheRequestedKind(container, ref.itemKindToken))
        {
            return Collections.emptyList();
        }
        EObject handler = FormElementWriter.findFormHandler(container, ref.name);
        if (handler == null)
        {
            return Collections.emptyList();
        }
        return scopedByOwningForm(member, handlerScopeSpellings(member.probeFqn,
            FormElementWriter.handlerEventSpellings(container, handler)));
    }

    /**
     * Whether {@code element} really is of the kind {@code kindToken} names - the EXACT filter's
     * stricter reading of {@link FormElementWriter#matchesKindToken}.
     *
     * <p>The shared predicate accepts ANY requested kind for an element whose class carries no
     * addressable kind token, so that such elements stay reachable for the write tools. For an ITEM
     * kind that is a hole: {@code findFormItem} finds a form's root {@code AutoCommandBar} by name,
     * and {@code ...Button.FormCommandBar} was therefore reported as a resolved address - the scan
     * then filtered by a kind segment no location carries, handing back a clean report for an
     * address that does not exist. So an item kind must match EXACTLY here.</p>
     *
     * <p>{@code Attribute} and {@code Command} keep the lenient reading on purpose: they are not
     * item kinds at all. {@link FormElementWriter#resolveFormMember} routes them into their own
     * containment ({@code FormAttribute} / {@code FormCommand}), whose classes carry no item kind
     * either - demanding one would make every attribute and command address unresolvable.</p>
     *
     * <p>Scoped to this tool deliberately: what a tokenless class SHOULD answer to in general (and
     * how {@code AutoCommandBar} / {@code ContextMenu} / {@code ExtendedTooltip} stay addressable
     * for delete/modify) is being settled in issue #343, which reworks the shared resolver. Fixing
     * it here too would give one question two different answers.</p>
     *
     * @param element the resolved element (may be {@code null})
     * @param kindToken the kind token the caller addressed it with
     * @return {@code true} when the element answers to that kind
     */
    private static boolean addressesTheRequestedKind(EObject element, String kindToken)
    {
        // ONE question, asked in ONE place. This used to split: Attribute / Command went to
        // matchesKindToken (which accepted any token for a class carrying no ITEM kind) and everything
        // else to a strict comparison, because the shared predicate could not classify the
        // attribute / command containments. Since issue #343 it can - and the resolvers refuse a
        // foreign kind themselves - so the exact filter and the write tools now share one verdict
        // instead of two that could drift.
        return FormElementWriter.matchesKindToken(element, kindToken);
    }

    /**
     * Adds the OWNING FORM to a resolved member's scan scope - the node EDT really reports the
     * problem on.
     *
     * <p>A form's markers are indexed on the form CONTENT object, whose presentation is
     * {@code <formPath>.Form}; nothing below it ever appears there. Verified live on EDT 2026.1: a
     * form item bound to a missing handler procedure produces a {@code form-legacy-check-event-handler}
     * marker located at {@code Catalog.Catalog.Form.ItemForm.Form}, with no trace of the item. So a
     * member address scoped by the member alone matches nothing, and the caller gets
     * {@code objectsResolved} next to "# No Errors Found" - a false all-clear on an element that
     * demonstrably has a problem. The form path is a PREFIX of that presentation, so adding it
     * selects the form's problems (see {@link #markerOwnerFqn} for the same rule elsewhere).</p>
     *
     * <p>The prefix is cut off the PROBED address, not taken from
     * {@link FormElementWriter.FormMemberRef#formPath}: that field is normalized to the
     * {@code Type.Object.forms.FormName} shape {@code resolveMdForm} needs (plural, lowercase), while
     * a marker renders the singular {@code Form} - and the bilingual expansion would faithfully keep
     * the plural, matching nothing. How many segments to cut is taken from
     * {@link FormElementWriter.FormMemberRef#tailSegments}, i.e. from the shape {@code parse} actually
     * recognized, not re-derived here: reading it off {@code isItemLevel()} answered 2 for an
     * attribute COLUMN, whose tail is 4 ({@code Attribute.Rows.Column.Price}), so the scope became
     * the ATTRIBUTE instead of the form. Since EDT publishes a form member's markers on the content
     * FORM, that scope matches nothing and the caller gets a false "No Errors Found" - the very
     * failure issue #312 exists to prevent (issue #295 review).</p>
     *
     * @param member the deferred member probe (its {@code probeFqn} carries the caller's own tokens)
     * @param spellings the member's own scoping spellings
     * @return the spellings plus the owning form path, without duplicates
     */
    private static List<String> scopedByOwningForm(DeferredMember member, List<String> spellings)
    {
        List<String> scoped = new ArrayList<>(spellings);
        String[] parts = member.probeFqn.split("\\."); //$NON-NLS-1$
        int tail = member.ref.tailSegments;
        if (parts.length > tail)
        {
            String formPrefix = String.join(".", //$NON-NLS-1$
                Arrays.copyOfRange(parts, 0, parts.length - tail));
            if (!scoped.contains(formPrefix))
            {
                scoped.add(formPrefix);
            }
        }
        return scoped;
    }

    /**
     * The scan-scoping spellings of a resolved handler address: the probe as written PLUS the same
     * address with the leaf replaced by each spelling the matched event really carries.
     *
     * <p>{@link FormElementWriter#findFormHandler} accepts the English and the Russian event name
     * alike, while a marker location renders ONE of them; scoping by the caller's spelling alone
     * would filter out every problem on the very handler that was just proven to exist. The leaf
     * spellings come from {@link FormElementWriter#handlerEventSpellings}, which covers a form
     * COMMAND's fixed {@code Action} leaf as well as an ordinary element's bound event.</p>
     *
     * @param probeFqn the probed address (its last segment is the event as the caller wrote it)
     * @param eventNames the leaf spellings the matched handler is addressable by
     * @return the spellings, in order, without duplicates
     */
    private static List<String> handlerScopeSpellings(String probeFqn, List<String> eventNames)
    {
        List<String> spellings = new ArrayList<>();
        spellings.add(probeFqn);
        int lastDot = probeFqn.lastIndexOf('.');
        if (lastDot > 0)
        {
            String prefix = probeFqn.substring(0, lastDot + 1);
            for (String eventName : eventNames)
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

            // The objects filter of THIS project. An exact-address call scopes each project by the
            // spellings that resolved THERE, so a project that resolved nothing contributes no
            // marker: an exact address is a claim about one node, and a project the node does not
            // live in cannot own its problems. (This is also what keeps a CLOSED project - whose
            // markers are in the index but whose model cannot be resolved against - from being
            // matched by another project's spelling.)
            final Set<String> projectObjects = context.objectsFor(entry.getKey());
            if (projectObjects == null)
            {
                continue;
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
                    projectObjects, context.checkRepository, context.fixRepository,
                    context.unresolvedShown, context.unresolvedFilteredOut, context.exactScope))
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
        final IFixRepository fixRepository;
        final int limit;
        final int[] unresolvedShown;
        final int[] unresolvedFilteredOut;
        final boolean exactScope;
        /**
         * PER-PROJECT object filters, or {@code null} when {@link #objects} applies to every
         * project. Only an exact-address call sets it: each project is scoped by the spellings that
         * resolved THERE, and a project that is absent resolved nothing and must contribute no
         * marker at all.
         */
        final Map<String, Set<String>> objectsByProject;

        CollectContext(MarkerSeverity severityFilter, String checkId, Set<String> objects,
            ICheckRepository checkRepository, IFixRepository fixRepository, int limit,
            int[] unresolvedShown, int[] unresolvedFilteredOut, boolean exactScope)
        {
            this(severityFilter, checkId, objects, checkRepository, fixRepository, limit,
                unresolvedShown, unresolvedFilteredOut, exactScope, null);
        }

        CollectContext(MarkerSeverity severityFilter, String checkId, Set<String> objects,
            ICheckRepository checkRepository, IFixRepository fixRepository, int limit,
            int[] unresolvedShown, int[] unresolvedFilteredOut, boolean exactScope,
            Map<String, Set<String>> objectsByProject)
        {
            this.severityFilter = severityFilter;
            this.checkId = checkId;
            this.objects = objects;
            this.checkRepository = checkRepository;
            this.fixRepository = fixRepository;
            this.limit = limit;
            this.unresolvedShown = unresolvedShown;
            this.unresolvedFilteredOut = unresolvedFilteredOut;
            this.exactScope = exactScope;
            this.objectsByProject = objectsByProject;
        }

        /**
         * The object filter to apply to {@code project}'s markers, or {@code null} when the project
         * must contribute nothing at all (an exact-address call whose addresses resolved in some
         * OTHER project).
         *
         * @param project the project whose markers are about to be scanned
         * @return the filter variants, or {@code null} to skip the project entirely
         */
        Set<String> objectsFor(IProject project)
        {
            return objectsForProject(objectsByProject, objects, project.getName());
        }
    }

    /**
     * The object filter that applies to one project's markers.
     *
     * @param objectsByProject the per-project filters of an exact-address call, or {@code null} when
     *     one filter applies to every project (the loose {@code objects} call)
     * @param objects the single filter to use when {@code objectsByProject} is {@code null}
     * @param projectName the project whose markers are about to be scanned
     * @return the filter variants, or {@code null} when the project must contribute nothing at all
     */
    static Set<String> objectsForProject(Map<String, Set<String>> objectsByProject,
        Set<String> objects, String projectName)
    {
        if (objectsByProject == null)
        {
            return objects;
        }
        Set<String> scoped = objectsByProject.get(projectName);
        return scoped == null || scoped.isEmpty() ? null : scoped;
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
                "Module path", "Line", "Check code", "Fix registered", "Has docs")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        }
        else
        {
            md.append(MarkdownUtils.tableHeader("Description", "Location", //$NON-NLS-1$ //$NON-NLS-2$
                "Module path", "Line", "Check code", "Fix registered")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
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
        // "Fix registered" flag: "yes" when this CHECK TYPE has a fix registered with
        // IFixRepository - NOT a promise that THIS specific marker will actually produce an
        // applicable fix. apply_quick_fix's own context-specific filtering (prepareFix ->
        // getApplicableFixVariants) can still yield zero variants for an individual marker even
        // when its check type is registered here; try apply_quick_fix and read its error if a
        // registered check turns out not to be applicable to this particular occurrence. Plain
        // ASCII so the Tycho build stays encoding-safe.
        String fixCell = error.hasQuickFix ? "yes" : ""; //$NON-NLS-1$ //$NON-NLS-2$

        if (detailed)
        {
            md.append(MarkdownUtils.tableRow(error.message, error.objectPresentation,
                modulePathCell, lineCell, checkCell, fixCell,
                error.hasDocumentation ? "true" : "false")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            md.append(MarkdownUtils.tableRow(error.message, error.objectPresentation,
                modulePathCell, lineCell, checkCell, fixCell));
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
     * Appends the PARTIAL-ANSWER warning: addresses that resolved while some project that could hold
     * them could not be consulted, so the rows below are not the whole story.
     *
     * <p>Without this the caller cannot tell a complete report from one missing a whole project's
     * problems - and "looks complete" is precisely the failure this input exists to remove.</p>
     *
     * @param md the Markdown builder to append to
     * @param incompleteFor address -&gt; the projects that could not be consulted about it
     */
    static void appendIncompleteScopeWarning(StringBuilder md, Map<String, Set<String>> incompleteFor)
    {
        if (incompleteFor == null || incompleteFor.isEmpty())
        {
            return;
        }
        for (Map.Entry<String, Set<String>> entry : incompleteFor.entrySet())
        {
            md.append("\n> ⚠️ Partial answer for ") //$NON-NLS-1$
              .append(entry.getKey())
              .append(": could not consult ") //$NON-NLS-1$
              .append(String.join(", ", entry.getValue())) //$NON-NLS-1$
              .append(" (closed, still indexing, or unreadable), so problems this address may have") //$NON-NLS-1$
              .append(" there are NOT in this report. Open/await that project, or name an indexed") //$NON-NLS-1$
              .append(" one with projectName, to get a complete answer."); //$NON-NLS-1$
        }
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
        Set<String> objects, ICheckRepository checkRepository, IFixRepository fixRepository,
        int[] unresolvedShown, int[] unresolvedFilteredOut)
    {
        return buildIfMatches(marker, severityFilter, checkId, objects, checkRepository, fixRepository,
            unresolvedShown, unresolvedFilteredOut, false);
    }

    /**
     * As {@link #buildIfMatches(Marker, MarkerSeverity, String, Set, ICheckRepository, IFixRepository, int[], int[])}
     * but threading {@code exactScope} into the objects filter (segment-boundary vs substring - see
     * {@link #excludedByObjectsFilter(Set, boolean, String, int[], boolean)}).
     */
    static ErrorInfo buildIfMatches(Marker marker, MarkerSeverity severityFilter, String checkId,
        Set<String> objects, ICheckRepository checkRepository, IFixRepository fixRepository,
        int[] unresolvedShown, int[] unresolvedFilteredOut, boolean exactScope)
    {
        // Severity filter
        if (severityFilter != null && marker.getSeverity() != severityFilter)
        {
            return null;
        }

        // Resolve the check UID once; reused for the symbolic check id (display + checkId filter)
        // AND for the hasQuickFix flag (does this check have a registered EDT auto-fix?).
        String shortUid = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
        CheckUid checkUid = resolveCheckUid(marker, shortUid, checkRepository);
        String symbolicCheckId = checkUid != null ? checkUid.getCheckId() : null;
        // Best-effort, like the object-presentation resolution below: a repository hiccup on ONE
        // marker (stale registration, transient service state) must not abort the whole listing -
        // just leave this row's flag unset.
        boolean hasQuickFix = false;
        if (checkUid != null && fixRepository != null)
        {
            try
            {
                hasQuickFix = fixRepository.hasFixes(checkUid);
            }
            catch (Exception e)
            {
                hasQuickFix = false;
            }
        }

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
        error.hasQuickFix = hasQuickFix;
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

        String presentationLower = objectPresentation.toLowerCase(Locale.ROOT);
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
        CheckUid uid = resolveCheckUid(marker, shortUid, checkRepository);
        return uid != null ? uid.getCheckId() : null;
    }

    /**
     * Resolves a marker's full {@link CheckUid} (e.g. {@code semicolon-missing}) from its short UID
     * (e.g. {@code SU23}) via the check repository, or {@code null} when it cannot be resolved. The
     * UID is the key both for the symbolic check id (display) and for {@code IFixRepository.hasFixes}
     * (the quick-fix flag), so it is resolved once and reused.
     */
    static CheckUid resolveCheckUid(Marker marker, String shortUid, ICheckRepository checkRepository)
    {
        if (checkRepository == null || shortUid == null || shortUid.isEmpty() || marker.getProject() == null)
        {
            return null;
        }
        try
        {
            return checkRepository.getUidForShortUid(shortUid, marker.getProject());
        }
        catch (Exception e)
        {
            // Ignore - caller falls back to the short UID / no fix flag
            return null;
        }
    }

    /**
     * Returns true when the user supplied checkId substring matches either the marker
     * short UID or its already resolved symbolic check id.
     */
    static boolean checkIdMatches(String shortUid, String symbolicCheckId, String checkId)
    {
        String needle = checkId.toLowerCase(Locale.ROOT);
        if (shortUid != null && shortUid.toLowerCase(Locale.ROOT).contains(needle))
        {
            return true;
        }
        return symbolicCheckId != null && symbolicCheckId.toLowerCase(Locale.ROOT).contains(needle);
    }

    /**
     * Returns true when {@code checkId} is EXACTLY (case-insensitively) either the marker short
     * UID or its resolved symbolic check id. Unlike {@link #checkIdMatches}, this is NOT a
     * substring match: a read-only filter (get_project_errors) can afford to over-match and show
     * extra rows, but a mutation locator (apply_quick_fix) cannot - a loose needle like "doc"
     * would substring-match several unrelated checks, and if only one marker among them currently
     * exists the tool would silently auto-fix it without ever surfacing the ambiguity.
     */
    static boolean checkIdMatchesExact(String shortUid, String symbolicCheckId, String checkId)
    {
        String needle = checkId.toLowerCase(Locale.ROOT);
        if (shortUid != null && shortUid.toLowerCase(Locale.ROOT).equals(needle))
        {
            return true;
        }
        return symbolicCheckId != null && symbolicCheckId.toLowerCase(Locale.ROOT).equals(needle);
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
        boolean hasQuickFix;       // Whether this check has an EDT auto-fix (apply via apply_quick_fix)
    }
}
