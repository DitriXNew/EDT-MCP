/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerFilter;
import com._1c.g5.v8.dt.validation.marker.MarkerIndex;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The EDT validation markers of the top objects a metadata write exported, with an honest
 * {@code markersIncomplete} (issue #643).
 * <p>
 * Per object, not per project: completion is EDT's own per-object record of pending validation
 * segments ({@link BuildUtils#waitForObjectValidation}), and the slice is a marker-index query by
 * {@code PROJECT + TOP_OBJECT_ID} - the Problems view's "current object" scope. BSL-module markers
 * (URI-keyed) and markers the write caused on OTHER objects are outside the slice.
 * <p>
 * {@code markersIncomplete:false} only when every object resolved, EDT confirmed its validation
 * within the budget, the read succeeded and checks are not disabled globally. Truncation never sets
 * the flag; it shows as {@code markerCount > markers.length}.
 */
public final class WrittenObjectMarkers
{
    /** Lower bound of the validation budget. */
    static final long MIN_BUDGET_MS = 5_000L;

    /** Upper bound of the validation budget. */
    static final long MAX_BUDGET_MS = 15_000L;

    /**
     * The validation-wait budget of one write call, shared by all its objects. The single tuning
     * knob: set from the live per-object wait timing, always kept within 5-15 s.
     */
    public static final long VALIDATION_BUDGET_MS = clampBudgetMs(10_000L);

    /** At most this many marker rows are returned, most severe first. */
    public static final int MAX_ROWS = 20;

    /** Below this a slice of the shared budget cannot observe a drain; not started. */
    private static final long MIN_USEFUL_WAIT_MS = 1_000L;

    /** The least a bounded BM read gets, so a spent budget still reads the snapshot. */
    static final long MIN_READ_MS = 2_000L;

    /** The configuration's own top-object FQN. */
    static final String CONFIGURATION_FQN = "Configuration"; //$NON-NLS-1$

    /** The EDT system property that makes the check framework produce no markers. */
    private static final String DISABLE_CHECKS_PROPERTY = "disableProjectChecks"; //$NON-NLS-1$

    /** Result member: whether the list is NOT confirmed complete. */
    public static final String KEY_INCOMPLETE = "markersIncomplete"; //$NON-NLS-1$
    /** Result member: why the list is incomplete. */
    public static final String KEY_REASON = "markersIncompleteReason"; //$NON-NLS-1$
    /** Result member: total markers in the slice, before the cap. */
    public static final String KEY_COUNT = "markerCount"; //$NON-NLS-1$
    /** Result member: the capped, severity-sorted rows. */
    public static final String KEY_MARKERS = "markers"; //$NON-NLS-1$

    /** Output-schema prose for {@link #KEY_INCOMPLETE}. */
    public static final String SCHEMA_INCOMPLETE = "true = EDT validation of the written objects was not " //$NON-NLS-1$
        + "confirmed finished: an empty or short marker list is NOT clean - re-check with " //$NON-NLS-1$
        + "get_project_errors objectFqns. Covers the written top objects only, not BSL modules or " //$NON-NLS-1$
        + "other objects the write affected. Absent when the call wrote no object"; //$NON-NLS-1$
    /** Output-schema prose for {@link #KEY_REASON}. */
    public static final String SCHEMA_REASON = "Why markersIncomplete is true; absent when it is false"; //$NON-NLS-1$
    /** Output-schema prose for {@link #KEY_COUNT}. */
    public static final String SCHEMA_COUNT = "Markers on the written objects before the cap; more than " //$NON-NLS-1$
        + "markers.length means the list was truncated"; //$NON-NLS-1$
    /** Output-schema prose for {@link #KEY_MARKERS}. */
    public static final String SCHEMA_MARKERS = "At most 20 rows, most severe first: {object, severity, " //$NON-NLS-1$
        + "checkId, message, location, hasQuickFix}"; //$NON-NLS-1$

    /** Severities that must be fixed before a write is reported as done. */
    private static final Set<MarkerSeverity> ERROR_CLASS =
        Collections.unmodifiableSet(new LinkedHashSet<>(List.of(MarkerSeverity.ERRORS, MarkerSeverity.BLOCKER,
            MarkerSeverity.CRITICAL)));

    private WrittenObjectMarkers()
    {
        // Utility class
    }

    /** Why the list is incomplete. Declaration order is the precedence when several apply. */
    public enum Reason
    {
        /** The marker read (or the object lookup) threw. */
        READ_FAILED("readFailed"), //$NON-NLS-1$
        /** A written FQN did not resolve to a BM top object. */
        OBJECT_UNRESOLVED("objectUnresolved"), //$NON-NLS-1$
        /** The validation state could not be observed. */
        VALIDATION_UNOBSERVABLE("validationUnobservable"), //$NON-NLS-1$
        /** Validation not confirmed within the budget, or a previous wait is still outstanding. */
        VALIDATION_PENDING("validationPending"), //$NON-NLS-1$
        /** EDT runs with {@code -DdisableProjectChecks=true}. */
        CHECKS_DISABLED("checksDisabled"); //$NON-NLS-1$

        private final String wire;

        Reason(String wire)
        {
            this.wire = wire;
        }

        /** @return the value published in {@code markersIncompleteReason} */
        public String wire()
        {
            return wire;
        }

        /** @return every wire value, in precedence order */
        public static String[] wireValues()
        {
            Reason[] all = values();
            String[] wires = new String[all.length];
            for (int i = 0; i < all.length; i++)
            {
                wires[i] = all[i].wire;
            }
            return wires;
        }
    }

    /** One marker row. */
    public static final class Row
    {
        final String object;
        final MarkerSeverity severity;
        final String checkId;
        final String message;
        final String location;
        final boolean hasQuickFix;

        /**
         * @param object the written top FQN the marker belongs to
         * @param severity the marker severity, may be {@code null}
         * @param checkId the symbolic check id, else the short UID
         * @param message the marker message
         * @param location the object presentation, or the unresolved placeholder
         * @param hasQuickFix whether the check has an EDT quick fix
         */
        public Row(String object, MarkerSeverity severity, String checkId, String message, String location,
            boolean hasQuickFix)
        {
            this.object = object;
            this.severity = severity;
            this.checkId = checkId;
            this.message = message;
            this.location = location;
            this.hasQuickFix = hasQuickFix;
        }
    }

    /** One project's read: the total count and at most the requested number of rows. */
    public static final class ProjectMarkers
    {
        final int total;
        final List<Row> rows;

        /**
         * @param total markers in the project's slice, before any cap
         * @param rows the kept rows
         */
        public ProjectMarkers(int total, List<Row> rows)
        {
            this.total = total;
            this.rows = rows;
        }
    }

    /**
     * A bounded BM read did not finish in time (or a previous one for the project is still out):
     * the report is {@link Reason#VALIDATION_PENDING}, not a failure.
     */
    public static final class ReadTimedOut extends RuntimeException
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param message what timed out
         */
        public ReadTimedOut(String message)
        {
            super(message);
        }
    }

    /**
     * The platform calls, behind a seam so the decision can be tested without a live EDT. The two
     * BM reads are bounded: past {@code timeoutMs} they throw {@link ReadTimedOut}.
     */
    public interface Environment
    {
        /**
         * Resolves written top FQNs to BM ids in a read transaction; an unresolved FQN is absent.
         *
         * @param projectName the project
         * @param fqns the top-object FQNs
         * @param timeoutMs the deadline, positive
         * @return FQN to BM top-object id
         * @throws ReadTimedOut when not resolved in time
         */
        Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs);

        /**
         * Waits, outside any transaction, for EDT to validate the objects.
         *
         * @param projectName the project
         * @param topObjectIds the BM ids
         * @param timeoutMs the deadline, positive
         * @return how the wait ended
         */
        BuildUtils.ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds,
            long timeoutMs);

        /**
         * Reads the markers of the objects, building at most {@code maxRows} rows.
         *
         * @param projectName the project
         * @param topObjectIds FQN to BM top-object id
         * @param maxRows the cap
         * @param timeoutMs the deadline, positive
         * @return the total and the kept rows
         * @throws ReadTimedOut when not read in time
         */
        ProjectMarkers read(String projectName, Map<String, Long> topObjectIds, int maxRows, long timeoutMs);

        /** @return whether the check framework is disabled globally */
        boolean checksDisabled();
    }

    /** What a write call reports about its objects' markers. */
    public static final class Report
    {
        final Reason reason;
        final int count;
        final List<Row> rows;
        final List<String> objects;
        final long budgetMs;

        Report(Reason reason, int count, List<Row> rows, List<String> objects, long budgetMs)
        {
            this.reason = reason;
            this.count = count;
            this.rows = rows;
            this.objects = objects;
            this.budgetMs = budgetMs;
        }

        /** @return whether the list is not confirmed complete */
        public boolean incomplete()
        {
            return reason != null;
        }

        /** @return the reason, or {@code null} when complete */
        public Reason reason()
        {
            return reason;
        }
    }

    /**
     * Declares the four members in a write tool's output schema.
     *
     * @param schema the tool's output-schema builder
     * @return the same builder
     */
    public static JsonSchemaBuilder declareOutput(JsonSchemaBuilder schema)
    {
        return schema.booleanProperty(KEY_INCOMPLETE, SCHEMA_INCOMPLETE)
            .enumProperty(KEY_REASON, SCHEMA_REASON, Reason.wireValues())
            .integerProperty(KEY_COUNT, SCHEMA_COUNT)
            .objectArrayProperty(KEY_MARKERS, SCHEMA_MARKERS);
    }

    /**
     * Keeps a budget inside 5-15 s.
     *
     * @param budgetMs the requested budget
     * @return the clamped budget
     */
    static long clampBudgetMs(long budgetMs)
    {
        return Math.max(MIN_BUDGET_MS, Math.min(MAX_BUDGET_MS, budgetMs));
    }

    /**
     * The objects to report for one project: its exported top FQNs minus {@code Configuration} when
     * that was only the owner of a changed collection - kept when the call addressed the
     * configuration itself, or when it is the only object written.
     *
     * @param exportedFqns the top FQNs the call exported in the project
     * @param targetFqn the {@code fqn} the call addressed, may be {@code null}
     * @return the slice, in export order
     */
    public static List<String> slice(List<String> exportedFqns, String targetFqn)
    {
        List<String> slice = new ArrayList<>(exportedFqns);
        if (slice.size() > 1 && !addressesConfiguration(targetFqn))
        {
            slice.remove(CONFIGURATION_FQN);
        }
        return slice;
    }

    private static boolean addressesConfiguration(String targetFqn)
    {
        if (targetFqn == null)
        {
            return false;
        }
        int dot = targetFqn.indexOf('.');
        return CommandInterfaceAddress.isConfigurationToken(dot < 0 ? targetFqn : targetFqn.substring(0, dot));
    }

    /**
     * Waits for and reads the markers of the written objects under one shared budget. Never throws:
     * a failure becomes {@link Reason#READ_FAILED}.
     *
     * @param env the platform seam
     * @param sliceByProject per written project, the top FQNs to report; must not be empty
     * @param budgetMs the shared validation budget
     * @return the report
     */
    public static Report collect(Environment env, Map<String, List<String>> sliceByProject, long budgetMs)
    {
        boolean readFailed = false;
        boolean unresolved = false;
        boolean unobservable = false;
        boolean pending = false;
        int count = 0;
        List<Row> rows = new ArrayList<>();
        List<String> objects = new ArrayList<>();
        long deadlineAtMs = System.currentTimeMillis() + budgetMs;
        for (Map.Entry<String, List<String>> entry : sliceByProject.entrySet())
        {
            String projectName = entry.getKey();
            List<String> fqns = entry.getValue();
            objects.addAll(fqns);
            try
            {
                Map<String, Long> ids = env.resolveTopObjects(projectName, fqns,
                    readTimeoutMs(deadlineAtMs - System.currentTimeMillis()));
                unresolved |= ids.size() < fqns.size();
                if (ids.isEmpty())
                {
                    continue;
                }
                long startedAtMs = System.currentTimeMillis();
                long remainingMs = deadlineAtMs - startedAtMs;
                BuildUtils.ValidationState state = remainingMs < MIN_USEFUL_WAIT_MS
                    ? BuildUtils.ValidationState.PENDING
                    : env.awaitValidation(projectName, ids.values(), remainingMs);
                unobservable |= state == BuildUtils.ValidationState.UNOBSERVABLE;
                pending |= state == BuildUtils.ValidationState.PENDING;
                long waitedMs = System.currentTimeMillis() - startedAtMs;
                ProjectMarkers read = env.read(projectName, ids, MAX_ROWS,
                    readTimeoutMs(deadlineAtMs - System.currentTimeMillis()));
                count += read.total;
                rows.addAll(read.rows);
                // The timing line the validation budget is tuned from.
                Activator.logInfo("Written-object validation in " + projectName + ": " + state + " after " //$NON-NLS-1$ //$NON-NLS-2$
                    + waitedMs + "ms (budget " + budgetMs + "ms, " + ids.size() + " object(s), " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + read.total + " marker(s))"); //$NON-NLS-1$
            }
            catch (ReadTimedOut e)
            {
                // The model is busy (a service operation holds new transactions): not confirmed.
                pending = true;
                Activator.logInfo("Markers of the objects written in " + projectName + " not read in time: " //$NON-NLS-1$ //$NON-NLS-2$
                    + e.getMessage());
            }
            catch (RuntimeException e)
            {
                // A degraded report, not a failed write: never an ERROR, never propagated.
                readFailed = true;
                Activator.logWarning("Could not read the markers of the objects written in " + projectName //$NON-NLS-1$
                    + ": " + e); //$NON-NLS-1$
            }
        }
        Reason reason = reasonOf(readFailed, unresolved, unobservable, pending, checksDisabled(env));
        rows.sort(ROW_ORDER);
        List<Row> kept = rows.size() > MAX_ROWS ? new ArrayList<>(rows.subList(0, MAX_ROWS)) : rows;
        return new Report(reason, count, kept, objects, budgetMs);
    }

    /**
     * The deadline of one bounded BM read: what is left of the budget, but at least
     * {@link #MIN_READ_MS}, so a spent budget still reads the snapshot and a wedged read still
     * returns promptly.
     *
     * @param remainingMs what is left of the shared budget, may be negative
     * @return the read deadline
     */
    static long readTimeoutMs(long remainingMs)
    {
        return Math.max(MIN_READ_MS, remainingMs);
    }

    /**
     * The report for a collection that failed outright: incomplete, nothing read.
     *
     * @param objects the objects that were to be reported
     * @param budgetMs the budget
     * @return a {@link Reason#READ_FAILED} report
     */
    public static Report readFailed(List<String> objects, long budgetMs)
    {
        return new Report(Reason.READ_FAILED, 0, new ArrayList<>(), new ArrayList<>(objects), budgetMs);
    }

    private static boolean checksDisabled(Environment env)
    {
        try
        {
            return env.checksDisabled();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /**
     * The first applicable reason in precedence order, or {@code null} when the list is complete.
     */
    static Reason reasonOf(boolean readFailed, boolean unresolved, boolean unobservable, boolean pending,
        boolean checksDisabled)
    {
        if (readFailed)
        {
            return Reason.READ_FAILED;
        }
        if (unresolved)
        {
            return Reason.OBJECT_UNRESOLVED;
        }
        if (unobservable)
        {
            return Reason.VALIDATION_UNOBSERVABLE;
        }
        if (pending)
        {
            return Reason.VALIDATION_PENDING;
        }
        return checksDisabled ? Reason.CHECKS_DISABLED : null;
    }

    /** Most severe first (unknown severity last), then object, then location, then message. */
    static final Comparator<Row> ROW_ORDER = Comparator
        .comparingInt((Row r) -> r.severity == null ? Integer.MAX_VALUE : r.severity.ordinal())
        .thenComparing(r -> nullToEmpty(r.object))
        .thenComparing(r -> nullToEmpty(r.location))
        .thenComparing(r -> nullToEmpty(r.message));

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    /**
     * Adds the four members to a success object and appends one sentence to its {@code message}.
     *
     * @param success the success JSON object, mutated
     * @param report the report
     */
    public static void attach(JsonObject success, Report report)
    {
        success.addProperty(KEY_INCOMPLETE, report.incomplete());
        if (report.incomplete())
        {
            success.addProperty(KEY_REASON, report.reason.wire());
        }
        success.addProperty(KEY_COUNT, report.count);
        JsonArray array = new JsonArray();
        for (Row row : report.rows)
        {
            JsonObject item = new JsonObject();
            item.addProperty("object", row.object); //$NON-NLS-1$
            item.addProperty("severity", row.severity == null ? null : row.severity.name()); //$NON-NLS-1$
            item.addProperty("checkId", row.checkId); //$NON-NLS-1$
            item.addProperty("message", row.message); //$NON-NLS-1$
            item.addProperty("location", row.location); //$NON-NLS-1$
            item.addProperty("hasQuickFix", row.hasQuickFix); //$NON-NLS-1$
            array.add(item);
        }
        success.add(KEY_MARKERS, array);

        String existing = success.has(McpKeys.MESSAGE) && success.get(McpKeys.MESSAGE).isJsonPrimitive()
            ? success.get(McpKeys.MESSAGE).getAsString() : null;
        success.addProperty(McpKeys.MESSAGE, join(existing, sentence(report)));
    }

    /**
     * Appends a sentence to a message, closing the message with a period unless it already ends
     * with sentence punctuation.
     *
     * @param existing the message, may be {@code null} or blank
     * @param sentence the sentence to append
     * @return the joined message
     */
    static String join(String existing, String sentence)
    {
        if (existing == null || existing.isBlank())
        {
            return sentence;
        }
        String base = existing.stripTrailing();
        char last = base.charAt(base.length() - 1);
        return (last == '.' || last == '!' || last == '?' ? base : base + '.') + ' ' + sentence;
    }

    /**
     * The one sentence the agent reads: clean, dirty, or not-confirmed with the re-check to run.
     */
    static String sentence(Report report)
    {
        String truncation = report.count > report.rows.size()
            ? " Showing " + report.rows.size() + " of " + report.count + ", most severe first." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : ""; //$NON-NLS-1$
        if (report.incomplete())
        {
            return "EDT validation of the written object(s) " + whyIncomplete(report) + ", so " //$NON-NLS-1$ //$NON-NLS-2$
                + (report.count == 0 ? "the empty marker list" : "this marker list") //$NON-NLS-1$ //$NON-NLS-2$
                + " may be stale or incomplete - do not treat it as clean; re-check with get_project_errors " //$NON-NLS-1$
                + "objectFqns=" + report.objects + '.' + truncation; //$NON-NLS-1$
        }
        if (report.count == 0)
        {
            return "EDT validation of the written object(s) finished: no markers."; //$NON-NLS-1$
        }
        long errors = 0;
        for (Row row : report.rows)
        {
            if (row.severity != null && ERROR_CLASS.contains(row.severity))
            {
                errors++;
            }
        }
        // Sorted most severe first, so a full page of error-class rows may hide more of them.
        String errorCount = errors == report.rows.size() && report.count > report.rows.size()
            ? "at least " + errors : String.valueOf(errors); //$NON-NLS-1$
        return "EDT reports " + report.count + " marker(s) on the written object(s), " + errorCount //$NON-NLS-1$ //$NON-NLS-2$
            + " at CRITICAL or above" //$NON-NLS-1$
            + (errors > 0 ? " - fix them before reporting success." : ".") + truncation; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String whyIncomplete(Report report)
    {
        switch (report.reason)
        {
        case VALIDATION_PENDING:
            return "had not finished within " + Math.max(1L, report.budgetMs / 1000) + "s"; //$NON-NLS-1$ //$NON-NLS-2$
        case VALIDATION_UNOBSERVABLE:
            return "could not be observed"; //$NON-NLS-1$
        case OBJECT_UNRESOLVED:
            return "could not be scoped to every written object"; //$NON-NLS-1$
        case CHECKS_DISABLED:
            return "ran with project checks disabled (-DdisableProjectChecks)"; //$NON-NLS-1$
        case READ_FAILED:
        default:
            return "could not be read"; //$NON-NLS-1$
        }
    }

    /** The production environment. */
    public static final Environment PLATFORM = new Environment()
    {
        @Override
        public Map<String, Long> resolveTopObjects(String projectName, List<String> fqns, long timeoutMs)
        {
            return bounded(projectName, "Resolving the written objects", timeoutMs, () -> { //$NON-NLS-1$
                IBmModel model = modelOf(projectName);
                return BmTransactions.read(model, "ResolveWrittenTopObjects", (tx, pm) -> { //$NON-NLS-1$
                    Map<String, Long> ids = new LinkedHashMap<>();
                    for (String fqn : fqns)
                    {
                        IBmObject object = tx.getTopObjectByFqn(fqn);
                        if (object != null)
                        {
                            ids.put(fqn, Long.valueOf(object.bmGetId()));
                        }
                    }
                    return ids;
                });
            });
        }

        @Override
        public BuildUtils.ValidationState awaitValidation(String projectName, Collection<Long> topObjectIds,
            long timeoutMs)
        {
            ProjectContext context = ProjectContext.of(projectName);
            return context.exists()
                ? BuildUtils.waitForObjectValidation(context.project(), topObjectIds, timeoutMs)
                : BuildUtils.ValidationState.UNOBSERVABLE;
        }

        @Override
        public ProjectMarkers read(String projectName, Map<String, Long> topObjectIds, int maxRows,
            long timeoutMs)
        {
            return bounded(projectName, "Reading the markers of the written objects", timeoutMs, //$NON-NLS-1$
                () -> readNow(projectName, topObjectIds, maxRows));
        }

        private ProjectMarkers readNow(String projectName, Map<String, Long> topObjectIds, int maxRows)
        {
            ProjectContext context = ProjectContext.of(projectName);
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            if (!context.exists() || markerManager == null)
            {
                throw new IllegalStateException("marker manager or project unavailable"); //$NON-NLS-1$
            }
            IProject project = context.project();
            // Index queries outside a transaction: severity needs no model access.
            List<String> owners = new ArrayList<>();
            List<Marker> markers = new ArrayList<>();
            for (Map.Entry<String, Long> entry : topObjectIds.entrySet())
            {
                MarkerFilter filter = MarkerFilter.createProjectFilter(project)
                    .addValue(MarkerIndex.TOP_OBJECT_ID, entry.getValue());
                markerManager.markers(filter).forEach(marker -> {
                    owners.add(entry.getKey());
                    markers.add(marker);
                });
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < markers.size(); i++)
            {
                order.add(Integer.valueOf(i));
            }
            order.sort(Comparator.comparingInt((Integer i) -> {
                MarkerSeverity severity = markers.get(i).getSeverity();
                return severity == null ? Integer.MAX_VALUE : severity.ordinal();
            }).thenComparing(owners::get));
            List<Integer> kept = order.size() > maxRows ? order.subList(0, maxRows) : order;
            // Presentation resolves lazily against BM: only for the kept rows, inside a read.
            IBmModel model = modelOf(projectName);
            List<Row> rows = BmTransactions.read(model, "ReadWrittenObjectMarkers", (tx, pm) -> { //$NON-NLS-1$
                List<Row> built = new ArrayList<>();
                for (Integer i : kept)
                {
                    Marker marker = markers.get(i);
                    MarkerRows.Row row = MarkerRows.buildIfMatches(marker, null, null, Collections.emptySet(),
                        Activator.getDefault().getCheckRepository(), Activator.getDefault().getFixRepository(),
                        new int[1], new int[1], false, MarkerRows.Row::new);
                    String checkId = row.checkId != null && !row.checkId.isEmpty() ? row.checkId : row.checkCode;
                    built.add(new Row(owners.get(i), marker.getSeverity(), checkId, row.message,
                        row.objectPresentation, row.hasQuickFix));
                }
                return built;
            });
            return new ProjectMarkers(markers.size(), rows);
        }

        @Override
        public boolean checksDisabled()
        {
            return Boolean.getBoolean(DISABLE_CHECKS_PROPERTY);
        }

        private IBmModel modelOf(String projectName)
        {
            BmModelResolver.Resolution resolution = BmModelResolver.resolve(ProjectContext.of(projectName).project());
            if (!resolution.isAvailable())
            {
                throw new IllegalStateException("no BM model for project " + projectName); //$NON-NLS-1$
            }
            return resolution.getModel();
        }
    };

    /**
     * Runs a read-only BM step in a {@link BoundedJob}: opening a transaction waits without a timeout
     * while a service operation holds the model, so the caller must not. At most one such read per
     * project is outstanding; an abandoned one only reads and its result is dropped.
     *
     * @param projectName the project, the slot key
     * @param what the job name
     * @param timeoutMs the deadline, positive
     * @param work the read
     * @return the read's result
     * @throws ReadTimedOut when the deadline elapsed or a previous read is still out
     */
    @SuppressWarnings("unchecked")
    private static <T> T bounded(String projectName, String what, long timeoutMs, Supplier<T> work)
    {
        AtomicBoolean returned = BuildUtils.beginMarkerRead(projectName, timeoutMs);
        if (returned == null)
        {
            throw new ReadTimedOut("a previous marker read for " + projectName + " has not returned yet"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        Object[] value = new Object[1];
        BoundedJob.Result result = BoundedJob.run(what + " in " + projectName, timeoutMs, monitor -> { //$NON-NLS-1$
            try
            {
                value[0] = work.get();
            }
            finally
            {
                returned.set(true);
            }
        });
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            // Never ran and never will: reopen the slot at once.
            returned.set(true);
        }
        if (result.getOutcome() == BoundedJob.Outcome.COMPLETED && result.getFailure() == null)
        {
            return (T) value[0];
        }
        if (result.getFailure() != null)
        {
            throw new IllegalStateException(what + " failed: " + result.getFailure(), result.getFailure()); //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.NOT_RUN)
        {
            throw new IllegalStateException(what + " never ran"); //$NON-NLS-1$
        }
        throw new ReadTimedOut(what + ": " + result.getOutcome() + " after " + result.getElapsedMs() + "ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
