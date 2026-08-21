/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.compare.core.CompareMergeProcessBatch;
import com._1c.g5.v8.dt.compare.core.CompareMergeProcessDescriptor;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessSettings;
import com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus;
import com._1c.g5.v8.dt.compare.core.ComparisonScope;
import com._1c.g5.v8.dt.compare.datasource.GitComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.datasource.V8ProjectComparisonDataSourceDescriptor;
import com._1c.g5.v8.dt.compare.matching.MatchingStrategy;
import com._1c.g5.v8.dt.compare.model.ComparisonNode;
import com._1c.g5.v8.dt.compare.model.TopComparisonNode;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.protocol.jsonrpc.ToolAnnotations;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationCapability;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CommittedCancellation;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressReporter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonEngine;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonFailures;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonScopeBuilder;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry.ComparisonSession;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonSessionRegistry.ReleaseOutcome;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonTreeReport;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonView;
import com.ditrix.edt.mcp.server.utils.git.GitRevisionResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Starts one three-way comparison — the project's working tree against two git revisions —
 * as a background job and reports the resulting tree.
 * <p>
 * Three measured constraints shape the whole design, and each one is answered here rather
 * than hidden:
 * <ul>
 * <li><b>One comparison per EDT instance.</b> The platform's comparison manager asserts that
 * no other comparison is running, so a second launch is REFUSED with the live comparison's id
 * and the way to stop it. It is never queued: a queued launch would look accepted and then sit
 * behind work the caller cannot see.</li>
 * <li><b>The call never waits for the comparison.</b> A real configuration takes minutes, far
 * past any transport-safe wait, so the call returns a {@code jobId} and the comparison keeps
 * running. Poll it with {@code get_job_status}; stop it with {@code cancel_job}.</li>
 * <li><b>Failure has no status of its own.</b> {@code ComparisonProcessStatus} has no FAILED
 * literal — a failed comparison keeps its last status forever — so the poll loop reads the
 * batch's failure cause on EVERY tick. Reading it only at the end would render a dead
 * comparison as "still running" until the job's budget expired.</li>
 * <li><b>A finished comparison stays live, and only this tool can end it.</b> Its session
 * is what {@code get_comparison_node} reads, so it outlives the job that produced the
 * report - and it keeps EDT's single slot with it. {@code cancel_job} cannot give that slot
 * back: the job registry answers a job that already published its result with
 * ALREADY_TERMINAL and never invokes the owning tool's handler at all. The ways back are
 * the {@code releaseComparisonId} form of this call and the registry's idle TTL.</li>
 * </ul>
 * <p>
 * This tool never merges and cannot: it holds no comparison manager, only the read-only
 * {@link ComparisonEngine} facade, and the merge starters are absent from the bundle
 * altogether.
 */
public class CompareConfigurationsTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "compare_configurations"; //$NON-NLS-1$

    /** Per-call wait used when the caller names none. */
    static final int DEFAULT_WAIT_SECONDS = 5;

    /**
     * Largest per-call wait. Well below the transport's own ceiling: this call is a START,
     * and a caller that wants the result polls the job instead of holding a request open.
     */
    static final int MAX_WAIT_SECONDS = 25;

    /**
     * Total budget for the background job. A full configuration comparison is measured in
     * minutes, and an unbounded job would hold one of the shared workers until EDT restarts.
     */
    static final long JOB_TIMEOUT_MS = TimeUnit.HOURS.toMillis(2);

    /** How often the job asks the engine for its status and failure cause. */
    private static final long POLL_INTERVAL_MS = 500L;

    /** How often the job writes a progress line, in poll ticks. */
    private static final int PROGRESS_EVERY_TICKS = 20;

    /**
     * How many CONSECUTIVE polls may answer "the status could not be read" before the comparison
     * is given up on.
     * <p>
     * At {@link #POLL_INTERVAL_MS} that is about three seconds, and it is deliberately short: a
     * comparison SERVICE that has gone away is already its own failure on the tick that sees it,
     * so what is ridden out here is the narrow window in which EDT still lists the handle but
     * cannot answer for its session. One such tick is evidence of nothing — failing on it ends a
     * healthy comparison — while a run of them is a comparison nobody can read, and sitting out
     * the two-hour job budget for that helps no one.
     */
    static final int MAX_UNREADABLE_TICKS = 6;

    /**
     * How many CONSECUTIVE polls may find the comparison still waiting to be STARTED before it is
     * given up on.
     * <p>
     * A separate budget from {@link #MAX_UNREADABLE_TICKS}, and much longer, because it counts a
     * different thing. {@code startComparison} SCHEDULES the launch: until Eclipse runs it, EDT
     * lists no handle and answers no status, and those readings are indistinguishable from an
     * unreadable comparison unless the session's own "EDT has listed this at least once" latch is
     * consulted. Spending the three-second unreadable budget on them meant that a scheduler busy
     * with a build or an index for a few seconds got a correctly queued comparison CANCELLED, and
     * the caller told it could not be read. At {@link #POLL_INTERVAL_MS} this is one minute -
     * long enough for a loaded workbench to get to the job, short enough that a launch the
     * platform silently dropped is not waited out for the whole two-hour job budget.
     */
    static final int MAX_STARTING_TICKS = 120;

    private static final String KEY_PROJECT_NAME = "projectName"; //$NON-NLS-1$
    private static final String KEY_OTHER_REVISION = "otherRevision"; //$NON-NLS-1$
    private static final String KEY_ANCESTOR_REVISION = "ancestorRevision"; //$NON-NLS-1$
    private static final String KEY_SCOPE = "scope"; //$NON-NLS-1$
    private static final String KEY_MERGE_RULES_FILE = "mergeRulesFile"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$
    private static final String KEY_LIMIT = "limit"; //$NON-NLS-1$
    private static final String KEY_CHANGED_ONLY = "changedOnly"; //$NON-NLS-1$
    private static final String KEY_RELEASE_COMPARISON_ID = "releaseComparisonId"; //$NON-NLS-1$

    private final Backend backend;
    private final BackgroundJobs jobs;

    /** Production wiring: the read-only engine facade and the shared job registry. */
    public CompareConfigurationsTool()
    {
        this(new EngineBackend(), BackgroundJobs.shared());
    }

    /**
     * @param backend the comparison backend (a stub in tests)
     * @param jobs the background-job registry
     */
    CompareConfigurationsTool(Backend backend, BackgroundJobs jobs)
    {
        this.backend = backend;
        this.jobs = jobs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        // The load-bearing facts live HERE, not in the parameter prose: InputSchemaCompactor
        // strips parameter descriptions that are not on its allowlist, so a fact stated only
        // there would not reach the client at all.
        return "Compare a project's working tree against two git revisions (three-way) and " //$NON-NLS-1$
            + "report which top objects differ. Read-only: it never merges and never writes " //$NON-NLS-1$
            + "the project. Returns a jobId immediately - the comparison runs in background; " //$NON-NLS-1$
            + "poll it with get_job_status and stop it with cancel_job. EDT runs ONE " //$NON-NLS-1$
            + "comparison at a time, so a second call while one is live is refused, naming " //$NON-NLS-1$
            + "the live comparison, and is never queued. Omitting scope compares the WHOLE " //$NON-NLS-1$
            + "configuration. Expand one object with get_comparison_node. A FINISHED " //$NON-NLS-1$
            + "comparison stays open and keeps the slot - cancel_job cannot end it then; " //$NON-NLS-1$
            + "free it by calling this tool with releaseComparisonId alone. Full parameters " //$NON-NLS-1$
            + "and examples: call get_tool_guide('compare_configurations')."; //$NON-NLS-1$
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public ToolAnnotations getAnnotations()
    {
        // readOnlyHint is FALSE and that is not a hedge: the call changes EDT's own state by
        // taking the single comparison slot and creating the comparison's temporary workspace.
        // Nothing in the caller's project is touched, hence destructiveHint FALSE.
        return new ToolAnnotations(null, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
            Boolean.FALSE);
    }

    @Override
    public String getInputSchema()
    {
        // The three launch parameters are NOT in 'required', and that is the contract rather
        // than an omission: this tool answers a second call shape - releaseComparisonId alone -
        // which reads none of them, and a schema-validating client obeying a required list that
        // shape cannot satisfy could never make the one call that gives a finished comparison's
        // session back. The runtime demand is unchanged: a launch without them is refused by
        // JsonUtils.requireArguments with "projectName is required".
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_PROJECT_NAME,
                "Open EDT project whose working tree is the main side. Required unless " //$NON-NLS-1$
                    + "releaseComparisonId is given.") //$NON-NLS-1$
            .stringProperty(KEY_OTHER_REVISION,
                "Git revision compared against, e.g. a branch, tag or commit id. Required " //$NON-NLS-1$
                    + "unless releaseComparisonId is given.") //$NON-NLS-1$
            .stringProperty(KEY_ANCESTOR_REVISION,
                "Git revision used as the common ancestor of the other two sides. Required " //$NON-NLS-1$
                    + "unless releaseComparisonId is given.") //$NON-NLS-1$
            .stringArrayProperty(KEY_SCOPE,
                "Qualified names to compare, e.g. Catalog.Products. Omit for everything.") //$NON-NLS-1$
            .stringProperty(KEY_MERGE_RULES_FILE,
                "Path to a merge-rules file to apply to the comparison before it starts.") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Seconds this start call may wait before returning its job snapshot; " //$NON-NLS-1$
                    + "defaults to " + DEFAULT_WAIT_SECONDS + ", accepts 0 to " //$NON-NLS-1$ //$NON-NLS-2$
                    + MAX_WAIT_SECONDS + ".") //$NON-NLS-1$
            .integerProperty(KEY_LIMIT,
                "Largest number of top objects listed in the report; counts stay whole.") //$NON-NLS-1$
            .booleanProperty(KEY_CHANGED_ONLY,
                "List only top objects that differ. Defaults to true.") //$NON-NLS-1$
            .stringProperty(KEY_RELEASE_COMPARISON_ID,
                "Close a finished comparison and free EDT's single slot instead of " //$NON-NLS-1$
                    + "starting one. Pass the comparisonId from its report; projectName, " //$NON-NLS-1$
                    + "otherRevision and ancestorRevision are neither required nor read in " //$NON-NLS-1$
                    + "this form.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        // Answered FIRST, before any launch argument is demanded: this form starts nothing,
        // and a caller whose only business is giving EDT's comparison slot back should not
        // have to invent a project and two revisions to do it.
        String releaseId =
            trimToNull(JsonUtils.extractStringArgument(params, KEY_RELEASE_COMPARISON_ID));
        if (releaseId != null)
        {
            // ...but a call that carries BOTH intents is refused rather than half-served. Answering
            // the release and dropping the launch would report a freed slot and leave the caller to
            // discover on their own that nothing was started. The sibling tools of this change
            // already refuse the same shape: get_comparison_node will not guess between objectFqn
            // and nodeId, and merge_rules refuses write-only parameters in read mode.
            String conflicting = namedArgumentsPresent(params, KEY_PROJECT_NAME, KEY_OTHER_REVISION,
                KEY_ANCESTOR_REVISION, KEY_SCOPE, KEY_MERGE_RULES_FILE, KEY_CHANGED_ONLY);
            if (conflicting != null)
            {
                return ToolResult
                    .error("Nothing was released and nothing was started: this call carries both " //$NON-NLS-1$
                        + "'" + KEY_RELEASE_COMPARISON_ID + "' and launch parameters (" + conflicting //$NON-NLS-1$ //$NON-NLS-2$
                        + "), and the tool will not guess which one you meant. Send them as two " //$NON-NLS-1$
                        + "calls: release the finished comparison first, then start the new one.") //$NON-NLS-1$
                    .toJson();
            }
            return release(releaseId);
        }

        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params != null ? params.get(KEY_WAIT_SECONDS) : null, DEFAULT_WAIT_SECONDS,
                MAX_WAIT_SECONDS);
        }

        String missing = JsonUtils.requireArguments(params, KEY_PROJECT_NAME, KEY_OTHER_REVISION,
            KEY_ANCESTOR_REVISION);
        if (missing != null)
        {
            return missing;
        }

        String projectName = trimToNull(JsonUtils.extractStringArgument(params, KEY_PROJECT_NAME));
        String otherRevision =
            trimToNull(JsonUtils.extractStringArgument(params, KEY_OTHER_REVISION));
        String ancestorRevision =
            trimToNull(JsonUtils.extractStringArgument(params, KEY_ANCESTOR_REVISION));
        if (projectName == null || otherRevision == null || ancestorRevision == null)
        {
            return ToolResult.error(
                "projectName, otherRevision and ancestorRevision must all be non-blank. Use " //$NON-NLS-1$
                    + "list_projects for the project and list_git_branches for the revisions.") //$NON-NLS-1$
                .toJson();
        }

        List<String> scope = JsonUtils.extractArrayArgument(params, KEY_SCOPE);
        String mergeRulesFile =
            trimToNull(JsonUtils.extractStringArgument(params, KEY_MERGE_RULES_FILE));
        String rulesError = validateMergeRulesFile(mergeRulesFile);
        if (rulesError != null)
        {
            return rulesError;
        }
        int limit = JsonUtils.extractIntArgument(params, KEY_LIMIT,
            ComparisonTreeReport.DEFAULT_LIMIT);
        boolean changedOnly = JsonUtils.extractBooleanArgument(params, KEY_CHANGED_ONLY, true);

        LaunchRequest request = new LaunchRequest(projectName, otherRevision, ancestorRevision,
            scope, mergeRulesFile, limit, changedOnly);
        // Answered here rather than inside the job: a caller who mistyped a project name gets
        // the same structured "project not found" every other tool gives, instead of a job
        // that took EDT's single comparison slot only to fail on it.
        String precheck = backend.precheck(request);
        if (precheck != null)
        {
            return ToolResult.error(precheck).toJson();
        }

        String liveComparison = backend.activeComparisonId();
        if (liveComparison != null)
        {
            return ComparisonFailures.alreadyRunning(liveComparison).toJson();
        }
        return start(request, waitSeconds.intValue());
    }

    /**
     * Closes a comparison the caller has finished reading, giving EDT's single slot back.
     * <p>
     * This entry point exists because {@code cancel_job} cannot do it. A comparison that
     * FINISHED has published its result, so its background job is terminal, and the registry
     * answers a terminal job with ALREADY_TERMINAL without ever invoking the owning tool's
     * cancellation handler. With no reachable release, the first successful comparison would
     * hold the slot - and its virtual project and private BM store - until the idle TTL
     * expired, or until EDT was restarted.
     *
     * @param comparisonId the comparison to close
     * @return the caller-facing text, or a structured error when nothing answers to that id
     */
    private String release(String comparisonId)
    {
        ReleaseOutcome outcome = backend.release(comparisonId);
        if (outcome == ReleaseOutcome.NOT_REGISTERED)
        {
            // Refused rather than reported as a release: "there was nothing to release" and
            // "the comparison you named is closed" are different facts, and a caller acting
            // on the second would believe a slot was freed that somebody else still holds.
            return ComparisonFailures.unknownComparison(comparisonId,
                backend.liveComparisonIds()).toJson();
        }
        if (outcome == ReleaseOutcome.ALREADY_GONE)
        {
            // EDT had already forgotten the handle, so there was nothing to stop and nothing was
            // asked of it. That IS a free slot, and it is said plainly - this case used to share
            // one warning with a failed stop, which sent a caller looking in the workbench for a
            // comparison that had ended by itself.
            return "**Released:** comparison `" + comparisonId + "` had already ended on EDT's " //$NON-NLS-1$ //$NON-NLS-2$
                + "side, so there was nothing to stop; its record here is dropped and EDT's " //$NON-NLS-1$
                + "single comparison slot is free. Its nodeIds no longer resolve; start a new " //$NON-NLS-1$
                + "comparison with " + NAME + " when you need one."; //$NON-NLS-1$
        }
        if (outcome == ReleaseOutcome.STOP_FAILED)
        {
            // The bookkeeping happened and the stop did not, so only the bookkeeping is
            // claimed. Saying "the slot is free again" here is the defect this branch exists
            // to end: it is the one sentence a caller acts on, and acting on it wrongly means
            // launching into a comparison that is still open.
            return "**Record dropped, stop NOT confirmed:** comparison `" + comparisonId //$NON-NLS-1$
                + "` is no longer registered here, EDT still held it, and the stop did not " //$NON-NLS-1$
                + "complete - the failure is in the EDT error log. Do NOT assume the slot is " //$NON-NLS-1$
                + "free: if the next " + NAME + " is refused, look for a comparison still open " //$NON-NLS-1$
                + "in the workbench and end it there. Its nodeIds no longer resolve here " //$NON-NLS-1$
                + "either way."; //$NON-NLS-1$
        }
        return "**Released:** comparison `" + comparisonId + "` is closed and EDT's single " //$NON-NLS-1$ //$NON-NLS-2$
            + "comparison slot is free again. Its nodeIds no longer resolve; start a new " //$NON-NLS-1$
            + "comparison with " + NAME + " when you need one."; //$NON-NLS-1$
    }

    /**
     * Checks a merge-rules path before anything is started, so a typo is a plain error rather
     * than a comparison that occupies the single slot and then fails.
     *
     * @param mergeRulesFile the caller's path, or {@code null}
     * @return an error result, or {@code null} when the path is usable
     */
    private static String validateMergeRulesFile(String mergeRulesFile)
    {
        if (mergeRulesFile == null)
        {
            return null;
        }
        Path path;
        try
        {
            path = Paths.get(mergeRulesFile);
        }
        catch (InvalidPathException e)
        {
            return ToolResult.error("mergeRulesFile is not a valid path: '" + mergeRulesFile //$NON-NLS-1$
                + "'. Pass an absolute path to a merge-rules file, or omit the parameter.") //$NON-NLS-1$
                .toJson();
        }
        if (!Files.isReadable(path))
        {
            return ToolResult.error("mergeRulesFile does not exist or cannot be read: '" //$NON-NLS-1$
                + mergeRulesFile + "'. Pass an absolute path to a merge-rules file, or omit " //$NON-NLS-1$
                + "the parameter to compare without pre-set rules.").toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Submits the comparison as a background job and returns whatever the bounded wait saw.
     *
     * @param request the validated request
     * @param waitSeconds this call's own bound
     * @return the rendered job snapshot, or an error result
     */
    private String start(LaunchRequest request, int waitSeconds)
    {
        Launch launch = new Launch();
        CancellationCapability capability = CancellationCapability.of(
            "Cancelling stops the running comparison and releases the temporary workspace it " //$NON-NLS-1$
                + "built. Nothing in the project is changed, but the comparison has to be " //$NON-NLS-1$
                + "started again from the beginning.", //$NON-NLS-1$
            () -> stopComparison(launch));

        JobSnapshot started;
        try
        {
            started = jobs.start(NAME, JOB_TIMEOUT_MS, "Accepted the comparison request.", //$NON-NLS-1$
                capability, progress -> runComparison(request, progress, launch));
        }
        catch (RejectedExecutionException e)
        {
            // ComparisonFailures.describe, not getMessage(): a rejection raised by the worker
            // pool itself carries that pool's toString() - "…ThreadPoolExecutor@1b6d3586[…]" -
            // and one thrown with no message renders the literal "null". describe names the
            // exception type when there is no text, and scrubs the leaked object identity when
            // there is.
            return ToolResult.error("Could not start " + NAME //$NON-NLS-1$
                + " because the background-job registry is full or stopping: " //$NON-NLS-1$
                + ComparisonFailures.describe(e)
                + ". Poll existing jobs with get_job_status and retry, or restart EDT if the " //$NON-NLS-1$
                + "bundle is stopping.").toJson(); //$NON-NLS-1$
        }
        JobSnapshot latest = BackgroundJobPolling.await(jobs, started.getId(), waitSeconds);
        if (latest == null)
        {
            return ToolResult.error("The comparison background job '" + started.getId() //$NON-NLS-1$
                + "' expired before this call could poll it. Start " + NAME + " again to " //$NON-NLS-1$ //$NON-NLS-2$
                + "create a new job.").toJson(); //$NON-NLS-1$
        }
        return renderStart(latest);
    }

    /**
     * @param job the job snapshot the bounded wait produced
     * @return the caller-facing text, saying explicitly when the work is still running
     */
    private static String renderStart(JobSnapshot job)
    {
        if (job.getStatus() == BackgroundJobs.Status.RUNNING)
        {
            return "**Pending:** the comparison continues in background job `" + job.getId() //$NON-NLS-1$
                + "`. Poll it with `get_job_status` using `jobId=\"" + job.getId() //$NON-NLS-1$
                + "\"`; do not call " + NAME + " again for this run.\n\n" //$NON-NLS-1$ //$NON-NLS-2$
                + BackgroundJobRenderer.render(job);
        }
        return BackgroundJobRenderer.render(job);
    }

    /**
     * Runs the whole launch → poll → read pipeline inside one registry job.
     * <p>
     * Package-visible for one reason that no public entry point can serve: the ownership protocol
     * with the cancellation handler is decided by WHERE a hand-over lands relative to the launch's
     * own checks, and the only way to place one between two of them deterministically is to drive
     * this method with a {@link Launch} the test itself holds.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @param launch the state shared with the cancellation handler
     * @return the rendered comparison report
     * @throws Exception when the comparison could not be started, failed, or was interrupted
     */
    Object runComparison(LaunchRequest request, ProgressReporter progress,
        Launch launch) throws Exception
    {
        progress.add("Resolving the project and the two revisions."); //$NON-NLS-1$
        // Asked again on the job thread: the check in execute() is a fast refusal, but the
        // slot can be taken between that check and this launch, and the engine would then
        // refuse with an assertion rather than a sentence.
        String live = backend.activeComparisonId();
        if (live != null)
        {
            throw new ComparisonException(refusalText(live));
        }

        // Handing a batch to EDT cannot be taken back: the platform owns the comparison from
        // that moment, and a job published as a retryable timeout would invite a second launch
        // that the engine's one-at-a-time assertion refuses. Commit FIRST, in one step with the
        // deadline, and only start if this job is still the one allowed to.
        if (!progress.tryCommit())
        {
            throw new ComparisonException("The comparison job ended before it reached EDT, so " //$NON-NLS-1$
                + "nothing was started. Call " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
        }

        if (launch.claimPendingStop())
        {
            // Cancelled in the window between the commit and the launch. Nothing has reached
            // EDT yet, so nothing does: starting a comparison the caller has already asked to
            // stop would take the single slot for work nobody wants.
            launch.armed.countDown();
            throw new ComparisonException("The comparison was cancelled before it was handed " //$NON-NLS-1$
                + "to EDT, so nothing was started. Call " + NAME + " again when you want " //$NON-NLS-1$ //$NON-NLS-2$
                + "one."); //$NON-NLS-1$
        }

        String id = null;
        try
        {
            id = backend.start(request);
        }
        finally
        {
            if (id == null)
            {
                // Released on EVERY failure, a platform RuntimeException included: a handler
                // waiting forever on a launch that never happened would block the cancellation
                // of a job that is already failing.
                launch.armed.countDown();
            }
        }
        launch.comparisonId.set(id);
        launch.armed.countDown();
        progress.add("Comparison " + id + " started."); //$NON-NLS-1$ //$NON-NLS-2$

        int ticks = 0;
        int unreadableTicks = 0;
        int startingTicks = 0;
        while (true) // NOSONAR the exits are the terminal states below and the job's own budget
        {
            if (launch.claimHandedOverStop())
            {
                // A cancellation arrived while the launch was in flight, its handler ran out of
                // time waiting for the id, and the duty was passed here. Asked on EVERY tick and
                // not once after the launch: a hand-over that lands just after a single check is
                // owed by nobody, and the report then promises a stop that never happens.
                throw new ComparisonException("Comparison '" + id + "' was cancelled: the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "cancellation ran out of time waiting for the launch, so the launch " //$NON-NLS-1$
                    + "stopped the comparison instead. " + stopSentence(backend.cancel(id), id)); //$NON-NLS-1$
            }
            Progress state = backend.poll(id);
            // Counted CONSECUTIVELY and reset by any tick that did get an answer: a status the
            // engine could not read says nothing about the comparison, so one of them must not
            // end it, and a run of them must not be waited out for two hours either.
            unreadableTicks = state.isUnknown() ? unreadableTicks + 1 : 0;
            // A SEPARATE budget, because a comparison EDT has not listed yet is not an unreadable
            // one - see MAX_STARTING_TICKS.
            startingTicks = state.isStarting() ? startingTicks + 1 : 0;
            if (state.isGone())
            {
                // The session is no longer registered. WHY decides what may be said: a
                // cancellation this launch took part in is first-hand evidence and is reported as
                // one; without it, all that is established is that the comparison can no longer
                // be read, and calling that an EDT cancellation would put words in the platform's
                // mouth.
                if (launch.stopWasRequested())
                {
                    return "**Cancelled:** comparison `" + id + "` was stopped before it " //$NON-NLS-1$ //$NON-NLS-2$
                        + "finished. " + state.getDetail(); //$NON-NLS-1$
                }
                throw new ComparisonException("Comparison '" + id + "' can no longer be read: " //$NON-NLS-1$ //$NON-NLS-2$
                    + state.getDetail() + " Nobody asked this job to stop, so the comparison " //$NON-NLS-1$
                    + "was ended outside it - in the workbench, through " //$NON-NLS-1$
                    + "releaseComparisonId, or by the idle sweep. Start " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (state.isFailed())
            {
                backend.release(id);
                throw new ComparisonException("Comparison '" + id + "' failed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + state.getDetail() + ". Check the revisions with list_git_branches and " //$NON-NLS-1$
                    + "the project state with get_project_errors, then start " + NAME //$NON-NLS-1$
                    + " again."); //$NON-NLS-1$
            }
            if (state.isCancelled())
            {
                backend.release(id);
                return "**Cancelled:** comparison `" + id + "` was stopped before it finished. " //$NON-NLS-1$ //$NON-NLS-2$
                    + state.getDetail();
            }
            if (state.isFinished())
            {
                progress.add("Comparison finished; reading the tree."); //$NON-NLS-1$
                return backend.report(id, request);
            }
            if (startingTicks >= MAX_STARTING_TICKS)
            {
                // EDT accepted the batch and then never listed the handle. Named as itself: this
                // is not an unreadable comparison, it is one the platform never began, so the
                // remedy is different too.
                throw new ComparisonException("Comparison '" + id + "' was accepted by EDT but " //$NON-NLS-1$ //$NON-NLS-2$
                    + "never started: EDT has not listed it once in " //$NON-NLS-1$
                    + TimeUnit.MILLISECONDS.toSeconds(MAX_STARTING_TICKS * POLL_INTERVAL_MS)
                    + " seconds (" + state.getDetail() + "). " //$NON-NLS-1$ //$NON-NLS-2$
                    + stopSentence(backend.cancel(id), id)
                    + " Check EDT for a stuck background task, then start " + NAME //$NON-NLS-1$
                    + " again."); //$NON-NLS-1$
            }
            if (unreadableTicks >= MAX_UNREADABLE_TICKS)
            {
                // Not "EDT said something odd" - EDT said NOTHING, several times running. The
                // message says exactly that and names what WAS observed, because quoting a
                // status here would credit the platform with a report it never made.
                throw new ComparisonException("Comparison '" + id + "' could not be read: EDT " //$NON-NLS-1$ //$NON-NLS-2$
                    + "gave no status for " + MAX_UNREADABLE_TICKS + " polls in a row (" //$NON-NLS-1$ //$NON-NLS-2$
                    + state.getDetail() + "). " + stopSentence(backend.cancel(id), id) //$NON-NLS-1$
                    + " Check the EDT error log for the failure that was logged, then start " //$NON-NLS-1$
                    + NAME + " again."); //$NON-NLS-1$
            }
            if (Thread.currentThread().isInterrupted())
            {
                throw new InterruptedException(
                    "The comparison job was interrupted while waiting for EDT."); //$NON-NLS-1$
            }
            // The job is COMMITTED, so the registry's own deadline will not fail it - a
            // committed job is left to finish on purpose. That makes this loop the only thing
            // bounding the wait, and an unbounded one would hold a shared worker until EDT
            // restarts. Spend the budget, then stop the comparison and say so.
            if (progress.remainingMillis() <= 0L)
            {
                throw new ComparisonException("Comparison '" + id + "' did not finish within " //$NON-NLS-1$ //$NON-NLS-2$
                    + TimeUnit.MILLISECONDS.toMinutes(JOB_TIMEOUT_MS) + " minutes. " //$NON-NLS-1$
                    + stopSentence(backend.cancel(id), id)
                    + " Narrow the comparison with scope, or check EDT for a stuck background " //$NON-NLS-1$
                    + "task, and start " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (++ticks % PROGRESS_EVERY_TICKS == 0)
            {
                // "Still comparing" is a claim about the comparison, and neither an unreadable
                // tick nor a launch EDT has not surfaced yet supports it: say what was actually
                // observed instead.
                progress.add(progressLine(state));
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
    }

    /**
     * Stops the live comparison on behalf of {@code cancel_job}.
     * <p>
     * The wait for the launch to publish its id carries NO bound of its own, and that is the
     * point: the latch is counted down on every path out of the launch, success and failure
     * alike, so this cannot hang, and {@code BackgroundJobs} already bounds this handler and
     * says so honestly when its own bound expires. A private bound here would expire on an
     * ordinary slow launch - two git revision resolutions, a project lookup, an optional
     * rules file - and then report a stop that never happened, while the comparison went on
     * to take EDT's single slot with nothing left able to reach it.
     *
     * @param launch the state shared with the launching job
     * @return what was actually stopped
     */
    private CommittedCancellation stopComparison(Launch launch)
    {
        // Recorded BEFORE the wait, and in ONE step that both records the request and says who
        // owes it: a launch still in flight reads this the moment it has an id, so a cancellation
        // cannot be outrun by the very launch it is cancelling, and there is no instant at which
        // the request exists while nobody owes it.
        launch.requestStop();
        try
        {
            launch.armed.await();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            if (launch.handOverStop())
            {
                // Out of time, and the launch is the only thing left that can reach the
                // comparison it is starting. The promise is deliberately weak: the launch takes
                // the duty at its next poll, and this handler cannot witness that.
                return CommittedCancellation.stopInitiated("The comparison was still being " //$NON-NLS-1$
                    + "handed to EDT when this cancellation ran out of time. The request " //$NON-NLS-1$
                    + "stands and the launch takes it at its next check. Confirm with " //$NON-NLS-1$
                    + "get_job_status.", null); //$NON-NLS-1$
            }
            return CommittedCancellation.stopInitiated("The comparison was still being handed " //$NON-NLS-1$
                + "to EDT when this cancellation ran out of time, and the stop had already " //$NON-NLS-1$
                + "been taken by the launch itself. Confirm with get_job_status.", null); //$NON-NLS-1$
        }
        String id = launch.comparisonId.get();
        if (id == null)
        {
            if (!launch.claimPendingStop())
            {
                return CommittedCancellation.stopped("The comparison had not been handed to " //$NON-NLS-1$
                    + "EDT yet and the launch saw this cancellation in time, so nothing was " //$NON-NLS-1$
                    + "started at all.", null); //$NON-NLS-1$
            }
            return CommittedCancellation.notStopped(
                "The comparison could not be started at all, so there was nothing to stop; " //$NON-NLS-1$
                    + "the job ends by itself with its own error."); //$NON-NLS-1$
        }
        if (!launch.claimPendingStop())
        {
            return CommittedCancellation.stopInitiated("Comparison '" + id + "' was started " //$NON-NLS-1$ //$NON-NLS-2$
                + "just as this cancellation arrived, and the stop was already taken. Confirm " //$NON-NLS-1$
                + "with get_job_status.", null); //$NON-NLS-1$
        }
        StopOutcome outcome = backend.cancel(id);
        if (outcome == StopOutcome.STOPPED)
        {
            return CommittedCancellation.stopped("Comparison '" + id //$NON-NLS-1$
                + "' was cancelled and its temporary workspace released.", null); //$NON-NLS-1$
        }
        // NOT stopped, and said as such: a STOPPED verdict is what the job registry turns into
        // TERMINATED, and a caller reading TERMINATED stops looking. Neither remaining case
        // reached the comparison at all, so both are reported as work this tool could not stop.
        return CommittedCancellation.notStopped(stopSentence(outcome, id));
    }

    /**
     * The progress line for one tick, saying what was OBSERVED rather than assuming the
     * comparison is running.
     *
     * @param state the tick's answer
     * @return the line to record
     */
    private static String progressLine(Progress state)
    {
        if (state.isStarting())
        {
            return "Still waiting; EDT has not started the comparison yet (" //$NON-NLS-1$
                + state.getDetail() + ")."; //$NON-NLS-1$
        }
        if (state.isUnknown())
        {
            return "Still waiting; EDT's status could not be read (" + state.getDetail() + ")."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "Still comparing (" + state.getDetail() + ")."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * The one place that turns a stop verdict into a sentence, so every caller of
     * {@link Backend#cancel} says the same thing about the same observation.
     *
     * @param outcome what the stop attempt observed
     * @param comparisonId the comparison it was aimed at
     * @return a sentence stating what really happened, and what is left to do about it
     */
    private static String stopSentence(StopOutcome outcome, String comparisonId)
    {
        if (outcome == StopOutcome.STOPPED)
        {
            return "Comparison '" + comparisonId //$NON-NLS-1$
                + "' was stopped and its temporary workspace released."; //$NON-NLS-1$
        }
        if (outcome == StopOutcome.STOPPED_NOT_RELEASED)
        {
            return "Comparison '" + comparisonId + "' was stopped, but handing its session " //$NON-NLS-1$ //$NON-NLS-2$
                + "back here did NOT complete, so its temporary workspace is not confirmed " //$NON-NLS-1$
                + "released; the failure is in the EDT error log. If the next " + NAME //$NON-NLS-1$
                + " is refused, look for a comparison still open in the workbench and end it " //$NON-NLS-1$
                + "there."; //$NON-NLS-1$
        }
        if (outcome == StopOutcome.NOTHING_TO_STOP)
        {
            return "EDT no longer held comparison '" + comparisonId //$NON-NLS-1$
                + "', so there was nothing to stop; its session record here has been dropped."; //$NON-NLS-1$
        }
        return "EDT's comparison service was not available, so comparison '" + comparisonId //$NON-NLS-1$
            + "' could NOT be stopped and may still hold EDT's single comparison slot. Its " //$NON-NLS-1$
            + "session record here has been dropped. Once EDT has finished starting, check " //$NON-NLS-1$
            + "for a comparison still open in the workbench and end it there."; //$NON-NLS-1$
    }

    /**
     * @param comparisonId the comparison that took the slot first
     * @return the refusal text used from the job thread
     */
    private static String refusalText(String comparisonId)
    {
        // The SAME sentence the synchronous refusal returns, unwrapped: the situation a caller
        // has to act on is identical, and a second wording of it would drift from the first.
        return messageOf(ComparisonFailures.alreadyRunning(comparisonId));
    }

    /**
     * Reads the message out of one of the shared error results.
     * <p>
     * A background job's failure is free text while the shared refusals are error JSON, so
     * unwrapping is what keeps the job's error the SAME sentence the synchronous path would
     * have returned rather than a second wording of it.
     *
     * @param result one of the shared refusals
     * @return its message
     */
    private static String messageOf(ToolResult result)
    {
        return messageOf(result.toJson());
    }

    /**
     * @param errorJson an error result as JSON
     * @return its message, or the JSON itself when it does not carry one
     */
    private static String messageOf(String errorJson)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(errorJson);
            if (parsed.isJsonObject())
            {
                JsonElement error = parsed.getAsJsonObject().get("error"); //$NON-NLS-1$
                if (error != null && error.isJsonPrimitive())
                {
                    return error.getAsString();
                }
            }
        }
        catch (RuntimeException e) // NOSONAR a malformed payload falls back to itself
        {
            // The raw payload is still more useful to a reader than a swallowed failure.
        }
        return errorJson;
    }

    /**
     * @param value a caller value
     * @return the trimmed value, or {@code null} when it was absent or blank
     */
    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Refuses a launch whose project does not live inside the git work tree the revisions resolved
     * to.
     * <p>
     * The platform's git data source has to locate {@code projectPath} inside the repository at the
     * given revision. When the two paths do not agree in FORM - a workspace location recorded
     * through a symlink while the work tree is canonical, or a {@code .git} discovered above a
     * linked project - that lookup finds nothing and the git side comes back EMPTY instead of
     * failing. The comparison then succeeds and reports either "no differences" or every object as
     * added on main: a wrong result presented as a good one. The MIT reference this slice is modelled
     * on guards the same thing for the same reason.
     * <p>
     * Both paths are compared in their REAL form, which is the lesson of this repo's own #366/#429:
     * decide path identity the way git does, not by string prefix.
     *
     * @param projectName the project name, for the message
     * @param projectPath the project location
     * @param workTree the work tree the revisions resolved to, may be {@code null}
     * @throws ComparisonException when the project provably lies outside the work tree
     */
    // Package-private so the guard can be pinned without an EDT workspace, the same way
    // ComparisonEngine.forTesting and GitRevisionResolver.Revision.forTest are reached.
    static void requireProjectInsideWorkTree(String projectName, Path projectPath, Path workTree)
        throws ComparisonException
    {
        if (projectPath == null || workTree == null)
        {
            return;
        }
        Path realProject = toRealForm(projectPath);
        Path realWorkTree = toRealForm(workTree);
        if (realProject.startsWith(realWorkTree))
        {
            return;
        }
        throw new ComparisonException("Project '" + projectName + "' resolves to " + realProject //$NON-NLS-1$ //$NON-NLS-2$
            + ", which is not inside the git work tree " + realWorkTree //$NON-NLS-1$
            + ". Both git sides would read nothing there, and the comparison would report " //$NON-NLS-1$
            + "differences that are an artefact of the path rather than of the revisions. Open the " //$NON-NLS-1$
            + "project from inside its clone, or check the repository with " //$NON-NLS-1$
            + "'git rev-parse --show-toplevel'."); //$NON-NLS-1$
    }

    /**
     * The real, canonical form of a path, falling back to a normalised absolute path when the file
     * system cannot answer (a path that does not exist yet, or a link that cannot be read).
     *
     * @param path the path
     * @return the most canonical form obtainable
     */
    private static Path toRealForm(Path path)
    {
        try
        {
            return path.toRealPath();
        }
        catch (IOException e)
        {
            return path.toAbsolutePath().normalize();
        }
    }

    /**
     * Names the given arguments that the caller actually supplied a value for.
     *
     * @param params the call arguments, may be {@code null}
     * @param names the argument names to look for
     * @return a comma-separated list of the names present, or {@code null} when none of them is
     */
    private static String namedArgumentsPresent(Map<String, String> params, String... names)
    {
        if (params == null)
        {
            return null;
        }
        StringBuilder present = new StringBuilder();
        for (String name : names)
        {
            if (trimToNull(params.get(name)) == null)
            {
                continue;
            }
            if (present.length() > 0)
            {
                present.append(", "); //$NON-NLS-1$
            }
            present.append(name);
        }
        return present.length() == 0 ? null : present.toString();
    }

    /**
     * Decides which comparison holds EDT's single slot, from the two independent things that
     * can know about one.
     * <p>
     * <b>The registry's answer is never discarded, and that is the whole point of this method
     * existing separately.</b> EDT's own flag is cleared the instant a comparison FINISHES -
     * its job calls {@code comparisonFinished(batch)} on both the normal and the throwing path,
     * and that sets the active batch to {@code null} - while the session keeps its virtual
     * project, its private BM store and every {@code nodeId} already handed to the caller.
     * Gating the registry on that flag therefore reported a finished-but-open comparison as no
     * comparison at all: a second launch started on top of the first, and the refusal that
     * names {@code releaseComparisonId} - the only way back once the job is terminal - could
     * never be reached.
     * <p>
     * The flag still answers one question the registry cannot: a comparison started in EDT's
     * own interface takes the slot under no id of ours. That is reported as an EMPTY id rather
     * than {@code null}, because the slot IS taken and only its name is unknown.
     *
     * @param registeredComparisonId the id the session registry holds, or {@code null}
     * @param edtReportsActiveBatch what EDT says about its own active batch
     * @return the live comparison's id, {@code ""} when the slot is taken by a comparison this
     *     server cannot name, or {@code null} when nothing holds it
     */
    static String resolveActiveComparisonId(String registeredComparisonId,
        boolean edtReportsActiveBatch)
    {
        if (registeredComparisonId != null)
        {
            return registeredComparisonId;
        }
        return edtReportsActiveBatch ? "" : null; //$NON-NLS-1$
    }

    /**
     * The verdict for a cancellation that DID reach the platform, built from what handing the
     * session back then reported.
     * <p>
     * A pure function of the second operation's answer, and separate so it can be pinned for
     * every input: the defect it replaces was not a wrong mapping but a MISSING one - the
     * hand-back's answer was assigned to nothing and {@code STOPPED} was returned regardless, so
     * no input could change the verdict.
     *
     * @param handBack what giving the session back observed
     * @return what the caller may claim about the stop as a whole
     */
    static StopOutcome stopVerdict(ReleaseOutcome handBack)
    {
        if (handBack == ReleaseOutcome.STOP_FAILED)
        {
            // EDT still held the comparison and the hand-back did not complete. The cancel
            // reached the platform, so this is not "nothing happened" - but the workspace is not
            // confirmed released, and TERMINATED plus "released" is what a caller stops reading at.
            return StopOutcome.STOPPED_NOT_RELEASED;
        }
        // RELEASED, ALREADY_GONE and NOT_REGISTERED are all stops. ALREADY_GONE is in particular
        // the ORDINARY path: the cancel is what made EDT forget the handle, so finding it gone a
        // moment later is that cancel working, not a failure to give anything back.
        return StopOutcome.STOPPED;
    }

    /**
     * What an attempt to stop a comparison actually observed.
     * <p>
     * A stop is TWO operations - cancelling the comparison on the platform and handing its
     * session back here - and the verdict is built from both. It used to be built from the first
     * alone, with the second's answer discarded: a service that disappeared between them left
     * {@code cancel_job} publishing TERMINATED and the sentence "its temporary workspace
     * released" over a hand-back that never completed.
     */
    enum StopOutcome
    {
        /** EDT was asked to stop the comparison and the session was given back. */
        STOPPED,
        /**
         * EDT was asked to stop the comparison and did not refuse, but handing the session back
         * here did NOT complete. The comparison is cancelled; the workspace is not confirmed
         * released, so the caller must not be told it is.
         */
        STOPPED_NOT_RELEASED,
        /** EDT no longer held the comparison, so there was nothing to stop. */
        NOTHING_TO_STOP,
        /** EDT's comparison service was not registered, so nothing could reach the comparison. */
        SERVICE_UNAVAILABLE
    }

    /**
     * The state one launch shares with the {@code cancel_job} handler, which can arrive at
     * any moment during it.
     * <p>
     * Three fields, and the third is the whole protocol: the id (nothing can be stopped before
     * there is one), the latch (the handler waits for the id instead of guessing how long a
     * launch takes), and ONE duty reference that says at every instant whether a cancellation is
     * outstanding and who owes it.
     *
     * <h2>Why one reference and not three flags</h2>
     * The duty used to be spread over "a cancellation was requested", "a handler is waiting for
     * the id" and "somebody has claimed the stop", each readable and writable on its own. Two
     * threads then decided one question by reading two of them in sequence, and the sequence had a
     * gap: the launch read "a handler is waiting" and skipped its own stop, and MICROSECONDS later
     * that handler ran out of time, wrote the flag back to false and returned "the launch is
     * stopping it". The duty was then owed by nobody, the report promised a stop nobody performed,
     * and the comparison kept EDT's single slot. A state with no representable "owed by nobody",
     * moved only by {@code compareAndSet}, cannot reach that.
     *
     * <h2>The hand-over needs somebody still looking</h2>
     * One atomic state is necessary and not sufficient: a hand-over that lands after the launch's
     * only look is still lost. So the launch does not look once - {@link #claimHandedOverStop()}
     * is asked at the top of EVERY poll, for as long as the comparison runs. The remaining and
     * stated limit is the last tick: a hand-over arriving after it, while the comparison is
     * already finishing, is answered by the job's own result, which is why the handler's sentence
     * promises only that the request stands and sends the caller to {@code get_job_status}.
     */
    static final class Launch
    {
        /** Who owes the comparison a stop. */
        enum StopDuty
        {
            /** No cancellation has arrived. */
            NONE,
            /** A cancellation arrived and its handler is waiting for the id; the HANDLER stops it. */
            HANDLER,
            /** The handler ran out of time and passed the duty on; the LAUNCH stops it. */
            LAUNCH,
            /** One party has taken the duty. Nobody else may, so the stop happens exactly once. */
            TAKEN
        }

        private final AtomicReference<String> comparisonId = new AtomicReference<>();
        private final CountDownLatch armed = new CountDownLatch(1);
        private final AtomicReference<StopDuty> duty = new AtomicReference<>(StopDuty.NONE);

        /** Records that a cancellation arrived and that its handler intends to perform the stop. */
        void requestStop()
        {
            duty.compareAndSet(StopDuty.NONE, StopDuty.HANDLER);
        }

        /**
         * Passes the duty from an out-of-time handler to the launch, in ONE step: there is no
         * instant at which the request exists and belongs to nobody.
         *
         * @return {@code true} when the duty is now the launch's; {@code false} when somebody had
         *     already taken it, in which case this handler must promise nothing of its own
         */
        boolean handOverStop()
        {
            return duty.compareAndSet(StopDuty.HANDLER, StopDuty.LAUNCH);
        }

        /**
         * @return {@code true} for the ONE caller that now owes the stop of an outstanding
         *     cancellation, whoever it was owed by a moment ago
         */
        boolean claimPendingStop()
        {
            return duty.compareAndSet(StopDuty.HANDLER, StopDuty.TAKEN)
                || duty.compareAndSet(StopDuty.LAUNCH, StopDuty.TAKEN);
        }

        /**
         * @return {@code true} only when the duty was HANDED to the launch and is taken here. A
         *     duty the handler still holds is left alone: it will do the stopping and can then
         *     report a verified stop, which racing it would downgrade for no gain
         */
        boolean claimHandedOverStop()
        {
            return duty.compareAndSet(StopDuty.LAUNCH, StopDuty.TAKEN);
        }

        /**
         * @return {@code true} when a cancellation was requested through this launch at all -
         *     the launch's own first-hand evidence, and the only thing that entitles it to
         *     report a vanished session as a cancellation rather than as a disappearance
         */
        boolean stopWasRequested()
        {
            return duty.get() != StopDuty.NONE;
        }
    }

    /** One validated comparison request. */
    static final class LaunchRequest
    {
        private final String projectName;
        private final String otherRevision;
        private final String ancestorRevision;
        private final List<String> scope;
        private final String mergeRulesFile;
        private final int limit;
        private final boolean changedOnly;

        /**
         * @param projectName the project whose working tree is the main side
         * @param otherRevision the revision compared against
         * @param ancestorRevision the revision used as the common ancestor
         * @param scope qualified names to compare; empty means the whole configuration
         * @param mergeRulesFile a merge-rules file to apply first, or {@code null}
         * @param limit largest number of rows in the report
         * @param changedOnly whether identical top objects are left out
         */
        LaunchRequest(String projectName, String otherRevision, String ancestorRevision,
            List<String> scope, String mergeRulesFile, int limit, boolean changedOnly)
        {
            this.projectName = projectName;
            this.otherRevision = otherRevision;
            this.ancestorRevision = ancestorRevision;
            this.scope = Collections.unmodifiableList(
                scope == null ? new ArrayList<>() : new ArrayList<>(scope));
            this.mergeRulesFile = mergeRulesFile;
            this.limit = limit;
            this.changedOnly = changedOnly;
        }

        /** @return the project whose working tree is the main side */
        String getProjectName()
        {
            return projectName;
        }

        /** @return the revision compared against */
        String getOtherRevision()
        {
            return otherRevision;
        }

        /** @return the revision used as the common ancestor */
        String getAncestorRevision()
        {
            return ancestorRevision;
        }

        /** @return qualified names to compare; empty means the whole configuration */
        List<String> getScope()
        {
            return scope;
        }

        /** @return a merge-rules file to apply first, or {@code null} */
        String getMergeRulesFile()
        {
            return mergeRulesFile;
        }

        /** @return largest number of rows in the report */
        int getLimit()
        {
            return limit;
        }

        /** @return whether identical top objects are left out */
        boolean isChangedOnly()
        {
            return changedOnly;
        }
    }

    /** One poll tick's answer: what the comparison is doing, and why it stopped. */
    static final class Progress
    {
        private final State state;
        private final String detail;

        private Progress(State state, String detail)
        {
            this.state = state;
            this.detail = detail;
        }

        /**
         * What a comparison can be doing. There is no FAILED status in the platform enum, and
         * three of these are not statuses at all: STARTING is the window before EDT has listed
         * the handle, UNKNOWN is a tick on which EDT reported nothing, and GONE is the session
         * no longer being registered here.
         */
        private enum State
        {
            /** EDT has accepted the batch but has not listed the handle yet. */
            STARTING,
            RUNNING,
            UNKNOWN,
            /**
             * The session is no longer registered, so nothing further can be read about the
             * comparison. Kept apart from {@link #CANCELLED}, which is EDT saying the comparison
             * was cancelled: a disappearance has several causes, and reporting it as EDT's own
             * cancellation attributes to the platform a verdict it never gave.
             */
            GONE,
            FINISHED,
            CANCELLED,
            FAILED
        }

        /**
         * @param detail the platform's own status text
         * @return a still-running answer
         */
        static Progress running(String detail)
        {
            return new Progress(State.RUNNING, detail);
        }

        /**
         * EDT accepted the batch and has not listed the handle yet, so it answers no status. Kept
         * apart from {@link #unknown} because it is a KNOWN state of a healthy launch rather than
         * a failure to read one, and the poll loop budgets the two differently.
         *
         * @param detail what WAS observed
         * @return an answer that says the launch has not surfaced yet
         */
        static Progress starting(String detail)
        {
            return new Progress(State.STARTING, detail);
        }

        /**
         * The session is no longer registered here, so nothing further can be read.
         *
         * @param detail what WAS observed, never a status literal
         * @return an answer that reports the disappearance as itself
         */
        static Progress gone(String detail)
        {
            return new Progress(State.GONE, detail);
        }

        /**
         * The tick answered nothing: the status read failed, or EDT no longer knows the handle.
         * Kept apart from {@link #running} and from {@link #failed} alike — it is neither a
         * reason to keep quoting a status nor, on its own, a reason to end the comparison.
         *
         * @param detail what WAS observed, never a status literal
         * @return an answer that carries an absence honestly
         */
        static Progress unknown(String detail)
        {
            return new Progress(State.UNKNOWN, detail);
        }

        /**
         * @param detail the platform's own status text
         * @return a finished answer
         */
        static Progress finished(String detail)
        {
            return new Progress(State.FINISHED, detail);
        }

        /**
         * @param detail why it stopped
         * @return a cancelled answer
         */
        static Progress cancelled(String detail)
        {
            return new Progress(State.CANCELLED, detail);
        }

        /**
         * @param detail the platform failure text
         * @return a failed answer, which the status enum alone can never express
         */
        static Progress failed(String detail)
        {
            return new Progress(State.FAILED, detail);
        }

        /** @return {@code true} when the comparison finished successfully */
        boolean isFinished()
        {
            return state == State.FINISHED;
        }

        /** @return {@code true} when EDT reported no status at all on this tick */
        boolean isUnknown()
        {
            return state == State.UNKNOWN;
        }

        /** @return {@code true} when EDT has not started the accepted comparison yet */
        boolean isStarting()
        {
            return state == State.STARTING;
        }

        /** @return {@code true} when the session is no longer registered here */
        boolean isGone()
        {
            return state == State.GONE;
        }

        /** @return {@code true} when the comparison was cancelled */
        boolean isCancelled()
        {
            return state == State.CANCELLED;
        }

        /** @return {@code true} when the batch carries a failure cause */
        boolean isFailed()
        {
            return state == State.FAILED;
        }

        /** @return the platform's own text for this tick */
        String getDetail()
        {
            return detail == null ? "no detail reported" : detail; //$NON-NLS-1$
        }
    }

    /** A comparison that could not be started, or that the platform failed. */
    static final class ComparisonException extends Exception
    {
        private static final long serialVersionUID = 1L;

        /**
         * @param message an actionable message; it becomes the job's error text verbatim
         */
        ComparisonException(String message)
        {
            super(message);
        }

        /**
         * @param message an actionable message; it becomes the job's error text verbatim
         * @param cause the platform failure
         */
        ComparisonException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /**
     * The slice of the comparison facade this tool uses.
     * <p>
     * A seam, not a second layer: the production implementation below is the only code in
     * this file that touches {@link ComparisonEngine}, and it exists so the tool's contract
     * (validation, refusal, job lifecycle, honest reporting) is testable with no EDT at all.
     * Nothing here hands a caller a comparison manager or a live session.
     */
    interface Backend
    {
        /**
         * Checks what can be checked without starting anything.
         *
         * @param request the validated request
         * @return an actionable message, or {@code null} when the request can be launched
         */
        String precheck(LaunchRequest request);

        /**
         * The comparison holding EDT's single slot, after reclaiming everything that expired.
         * <p>
         * Reclaiming is part of the question and not a chore beside it: this answer decides
         * whether a launch is refused, so a session nobody has touched past its TTL must be given
         * back before it is allowed to say "no".
         *
         * <p>
         * "Holding the slot" is OPEN, not RUNNING: a comparison that finished still owns its
         * virtual project and its private BM store until something releases it, and the ids in
         * the report the caller is reading resolve against it. See
         * {@link CompareConfigurationsTool#resolveActiveComparisonId(String, boolean)}.
         *
         * @return the live comparison's id, {@code ""} when the slot is taken by a comparison this
         *     server cannot name, or {@code null} when nothing holds it
         */
        String activeComparisonId();

        /**
         * @param request the validated request
         * @return this plugin's id for the started comparison
         * @throws ComparisonException when the comparison could not be started
         */
        String start(LaunchRequest request) throws ComparisonException;

        /**
         * @param comparisonId the started comparison
         * @return one tick's answer, reading the failure cause as well as the status
         */
        Progress poll(String comparisonId);

        /**
         * @param comparisonId the finished comparison
         * @param request the request, for the page size and the filter
         * @return the rendered Markdown report
         * @throws ComparisonException when the tree could not be read
         */
        String report(String comparisonId, LaunchRequest request) throws ComparisonException;

        /**
         * Stops the comparison and releases its session, and says which of those actually
         * happened.
         * <p>
         * It returns a verdict rather than nothing because every caller of it publishes a
         * sentence about the comparison, and two of the three things this can observe are not
         * a stop at all: EDT's comparison service can be unregistered at that moment, and the
         * handle can already be gone. A {@code void} answer left those cases indistinguishable
         * from a stop, so the tool reported a stop that had not happened.
         *
         * @param comparisonId the started comparison
         * @return what was observed; never {@code null}
         */
        StopOutcome cancel(String comparisonId);

        /**
         * Releases the session of a comparison that already stopped by itself, or one the
         * caller has finished reading.
         * <p>
         * It answers what was OBSERVED rather than merely whether a record existed, for the
         * same reason {@link #cancel(String)} does: dropping the registry entry always
         * succeeds, and the sentence the caller publishes is about EDT's single slot, which
         * only a stop frees.
         *
         * @param comparisonId the started comparison
         * @return what was observed; never {@code null}
         */
        ReleaseOutcome release(String comparisonId);

        /** @return the comparison ids a caller may still quote, oldest first */
        List<String> liveComparisonIds();
    }
    /**
     * The production backend: the read-only {@link ComparisonEngine} facade plus the session
     * registry that owns the handle.
     * <p>
     * The registry, not the job record, owns the session on purpose — the background-job
     * registry evicts completed records with no dispose hook, so a live handle parked in a job
     * result would leak the comparison's virtual project and its private BM store.
     * <p>
     * This is the ONLY class in this file that touches the facade, and it never receives the
     * platform's comparison manager: the facade does not hand it out.
     */
    static final class EngineBackend implements Backend
    {
        @Override
        public String precheck(LaunchRequest request)
        {
            if (!ProjectContext.of(request.getProjectName()).exists())
            {
                return ProjectContext.notFoundMessage(request.getProjectName());
            }
            return null;
        }

        @Override
        public String activeComparisonId()
        {
            Optional<ComparisonEngine> engine = ComparisonEngine.get();
            if (engine.isEmpty())
            {
                return null;
            }
            // The REGISTRY is asked first, and that order is load-bearing: its answer reclaims
            // every session that sat idle past its TTL, and reclaiming one hands EDT's single slot
            // back. Asking EDT first would refuse the launch on the strength of an abandoned
            // comparison this very call was entitled to release.
            // orElse(FALSE) collapses "the service could not be asked", and it is the right
            // collapse HERE precisely because this answer only ever refuses a launch: refusing on
            // a slot nobody observed taken would block a caller on a guess, while letting the
            // launch through costs nothing - it fails a moment later with the service-unavailable
            // sentence, which names what is actually wrong.
            return resolveActiveComparisonId(engine.get().sessions().activeComparisonId(),
                engine.get().hasActiveComparison().orElse(Boolean.FALSE).booleanValue());
        }

        @Override
        public String start(LaunchRequest request) throws ComparisonException
        {
            ComparisonEngine engine = ComparisonEngine.get().orElseThrow(
                () -> new ComparisonException(messageOf(ComparisonFailures.serviceUnavailable())));

            GitRevisionResolver.Revision other =
                GitRevisionResolver.resolve(request.getProjectName(), request.getOtherRevision());
            if (!other.ok())
            {
                throw new ComparisonException(messageOf(other.errorJson()));
            }
            GitRevisionResolver.Revision ancestor = GitRevisionResolver.resolve(
                request.getProjectName(), request.getAncestorRevision());
            if (!ancestor.ok())
            {
                throw new ComparisonException(messageOf(ancestor.errorJson()));
            }

            ComparisonScopeBuilder.Scoping scoping =
                ComparisonScopeBuilder.build(request.getScope());
            if (!scoping.ok())
            {
                throw new ComparisonException(messageOf(scoping.errorJson()));
            }
            return launch(engine, request, other, ancestor, scopeObject(scoping));
        }

        /**
         * Turns the builder's outcome into the object the handle demands.
         * <p>
         * The whole-configuration case has no scope object - and the handle's constructor
         * null-checks its scope, so it cannot simply be passed through. An EMPTY scope is
         * exactly how the engine spells "compare everything", so that is what it becomes here.
         * A FRESH instance, never {@code ComparisonScope.EMPTY_SCOPE}: that shared constant is
         * MUTABLE and the engine extends whatever scope it is handed, which would leave one
         * comparison's additions inside every later comparison in the workbench.
         *
         * @param scoping the builder's outcome, already known to be a success
         * @return the scope to hand to the handle
         */
        private static ComparisonScope scopeObject(ComparisonScopeBuilder.Scoping scoping)
        {
            return scoping.isGlobal()
                ? new ComparisonScope(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList())
                : scoping.scope();
        }

        /**
         * Builds the batch and hands it to the engine, registering the session first so nothing
         * can be started without something owning it.
         *
         * @param engine the read-only facade
         * @param request the validated request
         * @param other the resolved other revision
         * @param ancestor the resolved ancestor revision
         * @param scope the comparison scope
         * @return this plugin's id for the started comparison
         * @throws ComparisonException when the project is not a 1C project or the launch fails
         */
        private static String launch(ComparisonEngine engine, LaunchRequest request,
            GitRevisionResolver.Revision other, GitRevisionResolver.Revision ancestor,
            ComparisonScope scope) throws ComparisonException
        {
            IProject project = ProjectContext.of(request.getProjectName()).project();
            IV8ProjectManager projectManager = Activator.getDefault().getV8ProjectManager();
            IV8Project v8Project =
                projectManager == null || project == null ? null : projectManager.getProject(project);
            if (v8Project == null)
            {
                throw new ComparisonException("EDT does not report '" + request.getProjectName() //$NON-NLS-1$
                    + "' as a 1C project, so it cannot be the main side of a comparison. Use " //$NON-NLS-1$
                    + "list_projects to see the projects EDT has loaded."); //$NON-NLS-1$
            }

            Path projectPath = project.getLocation().toFile().toPath();
            requireProjectInsideWorkTree(request.getProjectName(), projectPath, other.workTree());
            ComparisonProcessHandle handle = new ComparisonProcessHandle(
                new V8ProjectComparisonDataSourceDescriptor(v8Project),
                new GitComparisonDataSourceDescriptor(other.workTree(), other.commitId(),
                    projectPath),
                new GitComparisonDataSourceDescriptor(ancestor.workTree(), ancestor.commitId(),
                    projectPath),
                scope);

            ComparisonProcessSettings settings =
                ComparisonProcessSettings.builder(MatchingStrategy.UUID_THEN_NAME)
                    .mergeObjectsContent(true)
                    .parseBslModuleStructure(true)
                    // No external tool: nobody is at the keyboard to answer the window one would
                    // open, and this feature never merges anyway.
                    .avoidExternalMergeToolSupport(true)
                    .build();
            if (request.getMergeRulesFile() != null)
            {
                // Applied BEFORE the launch, which is the whole point of the parameter: the
                // decisions are already in place when the comparison opens, instead of being
                // answered one dialog at a time afterwards.
                try
                {
                    settings.setRestoredMergeSettings(
                        engine.restoreMergeSettings(handle, request.getMergeRulesFile()));
                }
                catch (RuntimeException e)
                {
                    throw new ComparisonException(ComparisonFailures.describe(e), e);
                }
            }

            CompareMergeProcessBatch batch = new CompareMergeProcessBatch(
                new CompareMergeProcessDescriptor(handle, settings));
            String id = engine.sessions().register(handle, batch);
            try
            {
                engine.start(batch);
            }
            catch (ComparisonEngine.ServiceUnavailableException e)
            {
                // The service went away between the facade lookup at the top of start() and
                // this line. Nothing reached the platform, so nothing is reported as started -
                // this used to return normally and the job went on to publish "Comparison
                // cmp-N started." for a comparison that did not exist.
                engine.sessions().release(id);
                throw new ComparisonException(messageOf(ComparisonFailures.serviceUnavailable()), e);
            }
            catch (RuntimeException e)
            {
                // Registered before the launch and released here: a session that outlives a
                // failed launch would hold the slot against every later attempt.
                engine.sessions().release(id);
                throw new ComparisonException("EDT refused to start the comparison: " //$NON-NLS-1$
                    + ComparisonFailures.describe(e), e);
            }
            return id;
        }

        @Override
        public Progress poll(String comparisonId)
        {
            Optional<ComparisonEngine> engine = ComparisonEngine.get();
            if (engine.isEmpty())
            {
                return Progress.failed("EDT's comparison service disappeared while the " //$NON-NLS-1$
                    + "comparison was running."); //$NON-NLS-1$
            }
            ComparisonSessionRegistry sessions = engine.get().sessions();
            // ONE lookup, not three. Each of them re-asks EDT for the live handles and can answer
            // differently from the last, so reading the handle, the batch and the launch latch
            // separately let the session disappear BETWEEN them - and the tool then reported a
            // cancellation the platform never performed, out of two answers that disagreed.
            ComparisonSession session = sessions.find(comparisonId).orElse(null);
            if (session == null)
            {
                // Reported as a disappearance and nothing more. It has several causes - EDT
                // dropped the handle, something released the session, the idle sweep reclaimed
                // it - and the caller, not this method, holds the evidence that picks one.
                return Progress.gone("Its session is no longer registered here."); //$NON-NLS-1$
            }
            ComparisonProcessHandle handle = session.handle();
            CompareMergeProcessBatch batch = session.batch();
            if (handle == null || batch == null)
            {
                return Progress.gone("Its session carries no handle to read."); //$NON-NLS-1$
            }
            // One call answers BOTH questions, and the failure cause wins: the platform's status
            // enum has no failed literal, so a failed comparison keeps whatever status it last
            // reached and a poll that read only the status would call it "running" forever.
            ComparisonEngine.Progress progress = engine.get().progress(batch, handle);
            ComparisonProcessStatus reported = progress.status();
            switch (progress.phase())
            {
                case FAILED:
                    return Progress.failed(ComparisonFailures.describe(progress.failure()));
                case CANCELLED:
                    return Progress.cancelled("EDT reported the comparison as cancelled."); //$NON-NLS-1$
                case FINISHED:
                    return Progress.finished(reported.name());
                case UNKNOWN:
                    // EDT reported NO status this tick - the read threw, the service could not be
                    // asked, or its manager answers nothing because it no longer holds the
                    // handle's session. An absence is not a status: it is neither quoted as one
                    // nor treated as a verdict here, since a single unreadable tick is no
                    // evidence that a live comparison has died. The loop above decides how many
                    // CONSECUTIVE ones it will tolerate - unless the launch has not surfaced at
                    // all, which is a different thing on a different budget.
                    return session.seenAliveByEdt()
                        ? Progress.unknown(unreadableStatusText(progress))
                        : Progress.starting("EDT has accepted the comparison and has not " //$NON-NLS-1$
                            + "listed it yet, so it answers no status for it"); //$NON-NLS-1$
                case UNEXPECTED:
                    // Every remaining literal of the platform enum belongs to merging, which
                    // cannot happen here. Reported as a failure with the raw literal rather than
                    // folded into "running", which would spin until the budget ran out. There is
                    // always a literal to quote in this branch: the engine answers UNKNOWN, not
                    // UNEXPECTED, when there is no status at all.
                    return Progress.failed("EDT reported comparison status '" + reported.name() //$NON-NLS-1$
                        + "', which a read-only comparison never produces."); //$NON-NLS-1$
                default:
                    return Progress.running(reported.name());
            }
        }

        /**
         * What to say about a tick that got no status. Never a status literal and never the
         * word EDT would have used: the caller is told what was OBSERVED.
         * <p>
         * Three observations, not two, and the third used to be reported as the second. When the
         * comparison service is not registered at the moment of the read, EDT says nothing
         * because nobody asked it - and "EDT answered no status, which its manager does when it
         * no longer holds the session" is then a claim about a comparison this server never
         * reached.
         *
         * @param progress the facade's reading
         * @return the observation, for the poll answer's detail
         */
        private static String unreadableStatusText(ComparisonEngine.Progress progress)
        {
            if (progress.statusReadFailure() != null)
            {
                return "reading the status from EDT failed: " //$NON-NLS-1$
                    + ComparisonFailures.describe(progress.statusReadFailure());
            }
            if (!progress.statusWasAsked())
            {
                return "EDT's comparison service was not registered when the status was asked, " //$NON-NLS-1$
                    + "so nothing was asked of the platform at all"; //$NON-NLS-1$
            }
            return "EDT answered no status for this comparison, which its manager does when it " //$NON-NLS-1$
                + "no longer holds the session"; //$NON-NLS-1$
        }

        @Override
        public String report(String comparisonId, LaunchRequest request) throws ComparisonException
        {
            ComparisonEngine engine = ComparisonEngine.get().orElseThrow(
                () -> new ComparisonException(messageOf(ComparisonFailures.serviceUnavailable())));
            ComparisonProcessHandle handle = engine.sessions().handle(comparisonId);
            if (handle == null)
            {
                throw new ComparisonException(
                    messageOf(ComparisonFailures.sessionGone(comparisonId)));
            }
            ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(
                request.getLimit(), request.isChangedOnly());
            try
            {
                // orElse(null) folds "the service could not be asked" into "no view", and the
                // refusal below covers both: either way the tree cannot be read now, and the
                // caller's move - start again - is the same.
                ComparisonView view = engine.view(handle).orElse(null);
                if (view == null)
                {
                    // EDT no longer knows the handle - the comparison was ended outside this
                    // server between the lookup above and this line. Named as itself, because
                    // reading on through the null throws a NullPointerException, and
                    // "NullPointerException" is not a fact the caller can act on while "it was
                    // ended outside this server" is.
                    throw new ComparisonException(
                        messageOf(ComparisonFailures.sessionGone(comparisonId)));
                }
                // Read through the comparison's OWN transaction: the nodes are objects of the
                // comparison's private BM store, and BmTransactions.read(project, ...) would open
                // a transaction on a different store entirely (CLAUDE.md don't #1).
                engine.read(view, "Read comparison tree", (transaction, monitor) -> { //$NON-NLS-1$
                    collectTopNodes(view.rootNode(), collector);
                    return null;
                });
            }
            catch (RuntimeException e)
            {
                throw new ComparisonException(
                    messageOf(ComparisonFailures.failed("reading the comparison tree", e)), e); //$NON-NLS-1$
            }
            ComparisonTreeReport.Header header = new ComparisonTreeReport.Header(comparisonId,
                request.getProjectName(), request.getOtherRevision(),
                request.getAncestorRevision(), "finished"); //$NON-NLS-1$
            return ComparisonTreeReport.render(header, handle.getFullScope(), collector);
        }

        /**
         * Walks the WHOLE comparison tree, reporting every top node it contains.
         * <p>
         * Descent goes through {@code getChildren()} and not through {@code getTopChildren()},
         * and that is the difference between a report and a wrong report. {@code Compare.xcore}
         * gives {@code ComparisonNode} two child collections - {@code refers
         * TopComparisonNode[] topChildren} and {@code contains ContainmentComparisonNode[]
         * containmentChildren} - and only {@code getChildren()} yields both, as its own javadoc
         * says ("all node's children, containment- and bmTop ones"). A top object that hangs
         * under a containment node for its collection is invisible to the narrow walk, so a
         * scope that matched such objects collected ZERO nodes and the report said the
         * comparison found nothing.
         *
         * @param node the node to descend from (may be {@code null})
         * @param collector the report being accumulated
         */
        static void collectTopNodes(ComparisonNode node, ComparisonTreeReport.Collector collector)
        {
            if (node == null)
            {
                return;
            }
            List<ComparisonNode> children = node.<ComparisonNode> getChildren();
            if (children == null)
            {
                return;
            }
            for (ComparisonNode child : children)
            {
                if (child == null)
                {
                    continue;
                }
                if (child instanceof TopComparisonNode)
                {
                    collector.accept((TopComparisonNode)child);
                }
                // Descended into unconditionally: a containment node carries no verdict of its
                // own and exists precisely to hold the top nodes below it.
                collectTopNodes(child, collector);
            }
        }

        @Override
        public StopOutcome cancel(String comparisonId)
        {
            Optional<ComparisonEngine> engine = ComparisonEngine.get();
            if (engine.isEmpty())
            {
                // EDT's comparison service is not registered, so nothing here can reach the
                // comparison to stop it. The session is still given back - through the shared
                // registry, the same door release() uses, because a session outlives a momentary
                // service gap and would otherwise sit on EDT's single slot until its idle TTL -
                // but this is NOT a stop, and the caller is told so.
                ComparisonSessionRegistry.shared().release(comparisonId);
                return StopOutcome.SERVICE_UNAVAILABLE;
            }
            ComparisonProcessHandle handle = engine.get().sessions().handle(comparisonId);
            // Cancelling the comparison does NOT deregister it: the facade cancels, the registry
            // owns. Both have to happen, or the slot reads as taken forever.
            if (handle == null)
            {
                engine.get().sessions().release(comparisonId);
                return StopOutcome.NOTHING_TO_STOP;
            }
            try
            {
                engine.get().cancel(handle);
            }
            catch (ComparisonEngine.ServiceUnavailableException e)
            {
                // The service disappeared between the lookup above and this call. The session
                // is still given back - it would otherwise sit on EDT's single slot until its
                // idle TTL - but nothing was cancelled, and reporting STOPPED here is what let
                // cancel_job answer TERMINATED for a comparison that went on running.
                Activator.logError("Could not cancel comparison " + comparisonId, e); //$NON-NLS-1$
                ComparisonSessionRegistry.shared().release(comparisonId);
                return StopOutcome.SERVICE_UNAVAILABLE;
            }
            // A stop is BOTH operations, so the verdict is built from both. This answer used to
            // be discarded and STOPPED returned regardless: a service that went away between the
            // cancel and the hand-back left cancel_job publishing TERMINATED and "its temporary
            // workspace released" over a hand-back that never completed.
            return stopVerdict(engine.get().sessions().release(comparisonId));
        }

        @Override
        public ReleaseOutcome release(String comparisonId)
        {
            // The registry's own release stops the handle, and stopping it is what gives the
            // virtual project and the private BM store back - the same path the idle sweep
            // takes. Reached through the registry rather than ComparisonEngine.get() so that
            // a session stays releasable while EDT's service is momentarily unregistered: it
            // still owns a virtual project across that gap.
            return ComparisonSessionRegistry.shared().release(comparisonId);
        }

        @Override
        public List<String> liveComparisonIds()
        {
            return ComparisonSessionRegistry.shared().ids();
        }
    }
}
