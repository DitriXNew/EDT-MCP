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
import com.ditrix.edt.mcp.server.utils.compare.ComparisonTreeReport;
import com.ditrix.edt.mcp.server.utils.compare.ComparisonView;
import com.ditrix.edt.mcp.server.utils.compare.MergeRulesCodec;
import com.ditrix.edt.mcp.server.utils.compare.PlatformAnswer;
import com.ditrix.edt.mcp.server.utils.compare.SlotClaim;
import com.ditrix.edt.mcp.server.utils.compare.SlotHandback;
import com.ditrix.edt.mcp.server.utils.compare.SlotHandback.Ending;
import com.ditrix.edt.mcp.server.utils.git.GitRevisionResolver;
import com.google.gson.JsonArray;
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

        String scopeError =
            validateScopeArgument(params == null ? null : params.get(KEY_SCOPE));
        if (scopeError != null)
        {
            return scopeError;
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
        SlotHandback handback = backend.handBack(comparisonId, Ending.CLOSED);
        if (!handback.wasRegistered())
        {
            // Refused rather than reported as a release: "there was nothing to release" and
            // "the comparison you named is closed" are different facts, and a caller acting
            // on the second would believe a slot was freed that somebody else still holds.
            return ComparisonFailures.unknownComparison(comparisonId,
                backend.liveComparisonIds(), backend.edtHasActiveComparison()).toJson();
        }
        if (!handback.slotIsFree())
        {
            // The hand-back did not complete, or could not be attempted. Only what happened is
            // claimed - and the sentence is the hand-back's own, not a second wording of it. This
            // branch is where "the slot is free again" used to be printed over a stop that never
            // occurred, which is the one sentence a caller ACTS on.
            return "**Not released:** " + handback.sentence() //$NON-NLS-1$
                + " Comparison `" + comparisonId + "` is still registered here, so its nodeIds " //$NON-NLS-1$ //$NON-NLS-2$
                + "still resolve and this call can be repeated."; //$NON-NLS-1$
        }
        return "**Released:** " + handback.sentence() + " Its nodeIds no longer resolve; start a " //$NON-NLS-1$ //$NON-NLS-2$
            + "new comparison with " + NAME + " when you need one."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Registers the comparison under the id its claim was granted, hands the batch to EDT, and
     * rolls the registration back in the way the failure ALLOWS.
     * <p>
     * The registration comes first because the id has to exist before the platform can start
     * anything under it, and that ordering is what makes the rollback a question at all. There are
     * two failures and they are not the same fact:
     * <ul>
     *   <li>{@link ComparisonEngine.ServiceUnavailableException} is the platform's own proof that
     *       the batch never left this process - the facade throws it precisely so that a launch
     *       reaching nothing cannot be mistaken for a quiet success. There is nothing to end, so
     *       the reservation is WITHDRAWN. Sending it through a hand-back instead was wrong twice
     *       over: the hand-back is built for not knowing, and with the service gone it could not
     *       know anything either, so it answered {@link SlotHandback.Verdict#UNREACHABLE} and
     *       deliberately kept the record - which then named EDT's single slot as taken by a
     *       comparison that had never started, and refused every later launch until the idle TTL
     *       expired.</li>
     *   <li>Any other failure means EDT was REACHED and refused, and what it did with the batch on
     *       the way is not established BY THE THROW. So the platform is asked, through
     *       {@link ComparisonSessionRegistry#handBackRefusedLaunch}: on the reading where EDT
     *       answers that it is not running this comparison, the refused launch has nothing left to
     *       give back and its reservation is WITHDRAWN; on every reading that establishes nothing -
     *       EDT could not be asked, the hand-back failed, EDT had begun after all - the ordinary
     *       hand-back answer stands and the record is KEPT. A caller who could not ask still keeps
     *       the record; only being told no drops it. Refusing to ask at all is what left a
     *       comparison started from EDT's own interface between the slot check and this line
     *       holding a registration for a launch EDT had rejected, and that registration refused
     *       every later launch until the idle TTL expired.</li>
     * </ul>
     * Package-scoped so the two rollbacks can be driven against a real registry.
     *
     * @param engine the installed facade
     * @param claim the claim this launch holds on EDT's single slot; the session takes its id, so
     *     the comparison a caller quotes is the one the slot was reserved under
     * @param handle the comparison to register
     * @param batch the batch to hand over
     * @return the id the comparison was registered under
     * @throws ComparisonException when the registration or the hand-over failed; the message says
     *     what became of the registration
     */
    static String registerAndHandOver(ComparisonEngine engine, SlotClaim claim,
        ComparisonProcessHandle handle, CompareMergeProcessBatch batch) throws ComparisonException
    {
        String id;
        try
        {
            id = engine.sessions().adoptClaim(claim.comparisonId(), handle, batch);
        }
        catch (IllegalStateException e)
        {
            // Three ways here, and all three mean the same thing to a caller: the bundle was taken
            // down between the facade lookup at the top of start() and this line, or this launch
            // no longer holds the slot it claimed. Reported as a launch that did not happen -
            // which it is, because the registration comes BEFORE the batch reaches EDT and nothing
            // has reached it yet.
            throw new ComparisonException("The comparison was not started: " //$NON-NLS-1$
                + e.getMessage() + " Nothing reached EDT.", e); //$NON-NLS-1$
        }
        try
        {
            engine.start(batch);
        }
        catch (ComparisonEngine.ServiceUnavailableException e)
        {
            // The service went away between the facade lookup and this line. Nothing reached the
            // platform, so nothing is reported as started - this used to return normally and the
            // job went on to publish "Comparison ... started." for a comparison that did not
            // exist - and the reservation is withdrawn rather than handed back.
            throw new ComparisonException(messageOf(ComparisonFailures.serviceUnavailable())
                + ' ' + engine.sessions().withdrawUnstartedLaunch(id).sentence(), e);
        }
        catch (RuntimeException e)
        {
            // EDT was reached and said no. The refusal is the caller's half of the proof; the
            // other half is EDT's own answer about the comparison, which the rollback asks for -
            // see the javadoc above and handBackRefusedLaunch for why a refusal must not be filed
            // under "we do not know".
            throw new ComparisonException("EDT refused to start the comparison: " //$NON-NLS-1$
                + ComparisonFailures.describe(e) + ' '
                + engine.sessions().handBackRefusedLaunch(id, Ending.CLOSED).sentence(), e);
        }
        return id;
    }

    /**
     * Checks the RAW 'scope' argument before it is parsed, because parsing is where the danger is.
     * <p>
     * {@code JsonUtils.extractArrayArgument} keeps only the PRIMITIVE elements of a JSON array and
     * drops the rest without a word, so {@code [null]} and {@code [{}]} arrive here as an EMPTY
     * list - and an empty scope is not "nothing was asked for", it is
     * {@link ComparisonScopeBuilder}'s spelling of COMPARE THE WHOLE CONFIGURATION, the heaviest
     * run this tool can start and the one that takes EDT's single slot for minutes. A broken
     * request would therefore have been answered with the most expensive thing the tool does. The
     * same silent drop turns a comma-separated {@code ",,"} into no entries at all, which lands in
     * exactly the same place.
     * <p>
     * So every element the caller PASSED has to be a string, refused BY POSITION when it is not -
     * the shape {@code merge_rules} already uses for the segments of a decision path, and the shape
     * {@link ComparisonScopeBuilder} already uses for a blank entry. An explicitly EMPTY array
     * {@code []} is left alone: no element was dropped there, so it is read as the omitted scope it
     * looks like.
     *
     * @param raw the argument exactly as it arrived, or {@code null} when it was omitted
     * @return an error result, or {@code null} when the argument can be parsed without losing
     *         anything
     */
    private static String validateScopeArgument(String raw)
    {
        if (raw == null || raw.trim().isEmpty())
        {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("[")) //$NON-NLS-1$
        {
            JsonArray array = asJsonArray(value);
            if (array == null)
            {
                // Not parseable as JSON: extractArrayArgument falls through to comma-separated
                // parsing, so this text is checked by the branch below rather than here.
                return commaSeparatedScopeError(value);
            }
            for (int i = 0; i < array.size(); i++)
            {
                JsonElement element = array.get(i);
                if (element == null || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString())
                {
                    return nonStringScopeEntryError(i, element);
                }
            }
            return null;
        }
        return commaSeparatedScopeError(value);
    }

    /**
     * @param value the raw argument, already trimmed and known not to be a JSON array
     * @return the refusal when the comma-separated form carries no usable entry, else {@code null}
     */
    private static String commaSeparatedScopeError(String value)
    {
        for (String part : value.split(",")) //$NON-NLS-1$
        {
            if (!part.trim().isEmpty())
            {
                return null;
            }
        }
        return ToolResult.error("'" + KEY_SCOPE + "' was sent but names no object: every entry in " //$NON-NLS-1$ //$NON-NLS-2$
            + "it is empty. Nothing was started. Each entry must be a metadata full name such as " //$NON-NLS-1$
            + "'Catalog.Products'. To compare the WHOLE configuration, omit '" + KEY_SCOPE //$NON-NLS-1$
            + "' entirely - an empty scope is never read that way, because a whole-configuration " //$NON-NLS-1$
            + "comparison is the heaviest run this tool can start and has to be asked for.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * The refusal for an array element that is not a string.
     * <p>
     * It names the POSITION and the JSON KIND that was found, and quotes neither the element nor
     * the array: the caller knows what they sent, and echoing arbitrary caller text back into a
     * Markdown answer is a defect family this tree has paid for once already.
     *
     * @param index the zero-based position of the offending element
     * @param element the offending element, or {@code null}
     * @return the actionable message
     */
    private static String nonStringScopeEntryError(int index, JsonElement element)
    {
        return ToolResult.error("Scope entry #" + (index + 1) + " is " + jsonKindOf(element) //$NON-NLS-1$ //$NON-NLS-2$
            + ", not a metadata full name. Nothing was started. Every '" + KEY_SCOPE //$NON-NLS-1$
            + "' entry must be a string such as 'Catalog.Products'; an entry that is not one is " //$NON-NLS-1$
            + "dropped when the array is read, and a scope that ends up empty means COMPARE THE " //$NON-NLS-1$
            + "WHOLE CONFIGURATION - the heaviest run this tool can start. To compare everything, " //$NON-NLS-1$
            + "omit '" + KEY_SCOPE + "' entirely.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * @param value the raw argument, known to start with {@code [}
     * @return the parsed array, or {@code null} when the text is not a JSON array
     */
    private static JsonArray asJsonArray(String value)
    {
        try
        {
            JsonElement parsed = JsonParser.parseString(value);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * @param element the element to describe
     * @return the JSON kind, in words, for a message that quotes no caller text
     */
    private static String jsonKindOf(JsonElement element)
    {
        if (element == null || element.isJsonNull())
        {
            return "null"; //$NON-NLS-1$
        }
        if (element.isJsonObject())
        {
            return "an object"; //$NON-NLS-1$
        }
        if (element.isJsonArray())
        {
            return "an array"; //$NON-NLS-1$
        }
        if (element.getAsJsonPrimitive().isBoolean())
        {
            return "a boolean"; //$NON-NLS-1$
        }
        return "a number"; //$NON-NLS-1$
    }

    /**
     * Checks a merge-rules path before anything is started, so a typo is a plain error rather
     * than a comparison that occupies the single slot and then fails.
     *
     * <h2>Readable is not the same as usable</h2>
     * {@code Files.isReadable} answers one question - may this process open it - and two things
     * that are not merge-rules files answer it with "yes". A DIRECTORY is readable, and so is a
     * file with any extension at all. Both used to pass and be handed to the platform, whose own
     * {@code deserializeMergeSettings} accepts a name ending in {@code .xml} or {@code .zip} and
     * nothing else: the comparison then failed deep inside the launch, holding EDT's single
     * comparison slot, with a message about a file the caller believed had been checked.
     * <p>
     * The extension question is asked of {@link MergeRulesCodec#hasReadableExtension}, not
     * answered again here: the rule belongs to the platform's reader, {@code merge_rules} already
     * lives next to it, and a second copy of somebody else's rule is the copy that goes stale.
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
        // BEFORE readability, and that order is the whole point: a relative path IS readable
        // whenever it happens to name a file under the working directory of the EDT process, so
        // asking the filesystem first accepts it and hands the platform a spelling that resolves
        // somewhere nobody named. An MCP client resolves the same text against ITS OWN directory,
        // so the comparison would silently apply a different file's rules and the report would
        // name the caller's spelling as the one that was used.
        ToolResult relative = ComparisonFailures.relativePath(KEY_MERGE_RULES_FILE, mergeRulesFile,
            path);
        if (relative != null)
        {
            return relative.toJson();
        }
        if (!Files.isReadable(path))
        {
            return ToolResult.error("mergeRulesFile does not exist or cannot be read: '" //$NON-NLS-1$
                + mergeRulesFile + "'. Pass an absolute path to a merge-rules file, or omit " //$NON-NLS-1$
                + "the parameter to compare without pre-set rules.").toJson(); //$NON-NLS-1$
        }
        // AFTER readability, because a path that is not there has to be reported as not there:
        // isRegularFile answers "no" for a missing file just as it does for a directory, so
        // reaching this check first would tell a caller who mistyped a name that it names a
        // directory.
        if (!Files.isRegularFile(path))
        {
            return ToolResult.error(KEY_MERGE_RULES_FILE + " is readable but is not a file: '" //$NON-NLS-1$
                + mergeRulesFile + "'. A merge-rules file is the single '.xml' or '.zip' a " //$NON-NLS-1$
                + "comparison saves ('Save merge settings'), so name that file rather than the " //$NON-NLS-1$
                + "directory holding it - or omit the parameter to compare without pre-set " //$NON-NLS-1$
                + "rules.").toJson(); //$NON-NLS-1$
        }
        if (!MergeRulesCodec.hasReadableExtension(path))
        {
            return ToolResult.error(KEY_MERGE_RULES_FILE + " must end in '" //$NON-NLS-1$
                + MergeRulesCodec.XML_EXTENSION + "' or '" + MergeRulesCodec.ZIP_EXTENSION //$NON-NLS-1$
                + "', but is '" + mergeRulesFile //$NON-NLS-1$
                + "'. EDT's own merge-settings reader accepts those two names and refuses " //$NON-NLS-1$
                + "everything else, so a file with any other extension would fail inside the " //$NON-NLS-1$
                + "launch after it had taken the single comparison slot. Write one with " //$NON-NLS-1$
                + "merge_rules (mode 'write' produces '" + MergeRulesCodec.XML_EXTENSION //$NON-NLS-1$
                + "'), rename the file you have, or omit the parameter to compare without " //$NON-NLS-1$
                + "pre-set rules.").toJson(); //$NON-NLS-1$
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
        // Asked again on the job thread: the check in execute() is a fast refusal, and it cannot
        // see a comparison started between that check and this launch - a comparison opened from
        // EDT's own interface among them, which no claim of ours can rule out either.
        String live = backend.activeComparisonId();
        if (live != null)
        {
            throw new ComparisonException(refusalText(live));
        }

        // ...and then the slot is TAKEN, not merely found free. Every check above is a reading,
        // and the whole preparation - two git revisions, the project lookup, the batch - used to
        // run between the reading and the registration that acted on it. Two jobs arriving
        // together both read "free", both spent that minute, and both registered; EDT refused the
        // second batch, but its registration stood and named the slot as taken by a comparison
        // that had never started. The claim is granted under the registry's monitor, so exactly
        // one of them gets it.
        SlotClaim claim = backend.claimSlot(request);
        if (!claim.granted())
        {
            throw new ComparisonException(messageOf(claim.refusal()));
        }
        boolean handedOver = false;
        try
        {
            String id = handOver(request, progress, launch, claim);
            handedOver = true;
            return conclude(request, progress, launch, id,
                pollUntilConcluded(progress, launch, id));
        }
        finally
        {
            if (!handedOver)
            {
                // The loser of every race that can still happen after the claim - a cancelled
                // job, a revision that would not resolve, a project EDT does not know - gives up
                // its OWN claim, and nothing else. A claim names no handle, so this cannot drop
                // the record of a comparison that may be running; that record is kept precisely
                // when this server does not know, and withdrawing a claim is the case where it
                // does.
                backend.withdrawClaim(claim);
            }
        }
    }

    /**
     * Hands the prepared batch to EDT under a claim already granted, and publishes the launch to
     * the cancellation handler.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @param launch the state shared with the cancellation handler
     * @param claim this launch's claim on EDT's single slot
     * @return this plugin's id for the started comparison
     * @throws ComparisonException when the comparison could not be started
     */
    private String handOver(LaunchRequest request, ProgressReporter progress, Launch launch,
        SlotClaim claim) throws ComparisonException
    {
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
            id = backend.start(request, claim);
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
        return id;
    }

    /**
     * Watches one comparison until there is nothing left to wait for, and says WHAT it saw.
     *
     * <h2>Why it says and does not act</h2>
     * This loop used to be the place where a comparison was ended: three of its branches called
     * the stop themselves, two of those threw away what it answered, and the terminal one forgot
     * that a cancellation could still be owed. Those were three instances of one defect, and the
     * defect was that each exit decided for itself.
     * <p>
     * So the loop performs NO hand-back and publishes NO verdict. It answers a {@link Conclusion},
     * and {@link #conclude} does both, for every exit, in one place. A branch added here cannot
     * forget to give EDT's slot back, because giving it back is not something this method is able
     * to do.
     *
     * @param progress the job's reporter
     * @param launch the state shared with the cancellation handler
     * @param id this plugin's id for the started comparison
     * @return how the wait ended; never {@code null}
     * @throws InterruptedException when the job thread is interrupted while waiting for EDT
     */
    private Conclusion pollUntilConcluded(ProgressReporter progress, Launch launch, String id)
        throws InterruptedException
    {
        int ticks = 0;
        int unreadableTicks = 0;
        int startingTicks = 0;
        while (true) // NOSONAR the exits are the terminal states below and the job's own budget
        {
            if (launch.hasHandedOverStop())
            {
                // A cancellation arrived while the launch was in flight, its handler ran out of
                // time waiting for the id, and the duty was passed here. Looked at on EVERY tick
                // and not once after the launch: a hand-over that lands just after a single check
                // is owed by nobody, and the report then promises a stop that never happens. It
                // is only READ here - the atomic claim belongs to the single exit, which makes it
                // for every ending rather than for this one.
                return Conclusion.of(Conclusion.Kind.HANDED_OVER_STOP, "the cancellation ran " //$NON-NLS-1$
                    + "out of time waiting for the launch, so the launch ended the comparison " //$NON-NLS-1$
                    + "instead."); //$NON-NLS-1$
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
                return Conclusion.of(Conclusion.Kind.VANISHED, state.getDetail());
            }
            if (state.isFailed())
            {
                return Conclusion.of(Conclusion.Kind.FAILED, state.getDetail());
            }
            if (state.isCancelled())
            {
                return Conclusion.of(Conclusion.Kind.CANCELLED, state.getDetail());
            }
            if (state.isFinished())
            {
                return Conclusion.of(Conclusion.Kind.FINISHED, state.getDetail());
            }
            if (startingTicks >= MAX_STARTING_TICKS)
            {
                // EDT accepted the batch and then never listed the handle. Named as itself: this
                // is not an unreadable comparison, it is one the platform never began, so the
                // remedy is different too.
                return Conclusion.of(Conclusion.Kind.NEVER_STARTED, state.getDetail());
            }
            if (unreadableTicks >= MAX_UNREADABLE_TICKS)
            {
                // Not "EDT said something odd" - EDT said NOTHING, several times running. The
                // detail names what WAS observed, because quoting a status here would credit the
                // platform with a report it never made.
                return Conclusion.of(Conclusion.Kind.UNREADABLE, state.getDetail());
            }
            if (Thread.currentThread().isInterrupted())
            {
                throw new InterruptedException(
                    "The comparison job was interrupted while waiting for EDT."); //$NON-NLS-1$
            }
            // The job is COMMITTED, so the registry's own deadline will not fail it - a
            // committed job is left to finish on purpose. That makes this loop the only thing
            // bounding the wait, and an unbounded one would hold a shared worker until EDT
            // restarts. Spend the budget, then end the comparison and say so.
            if (progress.remainingMillis() <= 0L)
            {
                return Conclusion.of(Conclusion.Kind.OUT_OF_TIME, state.getDetail());
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
     * The ONE exit from a comparison: it claims an outstanding cancellation, gives EDT's single
     * slot back when the ending calls for it, and words the answer.
     *
     * <h2>Why one exit</h2>
     * Eight things can end a comparison, and each of them used to answer three questions on its
     * own - is a stop still owed, does the slot go back, and what may be claimed about it. Three
     * review rounds found the same mistake in a different one of them each time, because there
     * were eight places to make it. Here there is one, and a ninth ending added to
     * {@link Conclusion.Kind} inherits all three answers instead of restating them.
     *
     * <h2>The claim comes first, for every ending</h2>
     * {@link Launch#claimHandedOverStop()} is asked here and nowhere else, before the ending is
     * even looked at. The terminal branch used to skip it entirely - it read the report and
     * returned - so a cancellation handed over while the comparison was finishing was owed by
     * nobody, and the caller had been told a stop was coming that never came. Re-checking on every
     * tick cannot fix that: after the last tick there is no next tick.
     * <p>
     * An outstanding cancellation is HONOURED rather than noted, the finished ending included. The
     * handler has already told the caller "the request stands and the launch takes it at its next
     * check", so returning the report and keeping the comparison open would leave that promise
     * unkept while the slot stayed taken under an id the caller believes is closing.
     *
     * <h2>The slot goes back by default</h2>
     * Exactly one ending keeps the comparison open - a FINISHED one nobody asked to cancel -
     * because its tree is what {@code get_comparison_node} reads and its nodeIds are in the report
     * being returned. Every other ending hands the slot back, and the wording of what that
     * achieved comes from {@link SlotHandback#sentence()} rather than from this method, so no
     * ending can be described here as a stop that did not happen.
     *
     * @param request the validated request
     * @param progress the job's reporter
     * @param launch the state shared with the cancellation handler
     * @param id this plugin's id for the started comparison
     * @param conclusion how the wait ended
     * @return the rendered report, or the sentence describing what else happened
     * @throws ComparisonException when the comparison did not produce a readable tree
     */
    private Object conclude(LaunchRequest request, ProgressReporter progress, Launch launch,
        String id, Conclusion conclusion) throws ComparisonException
    {
        // Claimed ONCE, here, before anything is decided. Atomic, and only of a duty that was
        // HANDED to the launch: a duty the handler still holds is left with the handler, which
        // can then report a verified stop of its own.
        boolean owesStop = launch.claimHandedOverStop()
            || conclusion.kind() == Conclusion.Kind.HANDED_OVER_STOP;
        if (conclusion.kind() == Conclusion.Kind.FINISHED && !owesStop)
        {
            progress.add("Comparison finished; reading the tree."); //$NON-NLS-1$
            return backend.report(id, request);
        }
        SlotHandback handback = backend.handBack(id,
            owesStop || conclusion.kind().endsItEarly() ? Ending.CANCELLED : Ending.CLOSED);
        if (owesStop)
        {
            String because = conclusion.kind() == Conclusion.Kind.FINISHED
                ? "it had just finished when the cancellation was honoured, so its report was " //$NON-NLS-1$
                    + "not returned." //$NON-NLS-1$
                : conclusion.detail();
            throw new ComparisonException("Comparison '" + id + "' was cancelled: " + because //$NON-NLS-1$ //$NON-NLS-2$
                + ' ' + handback.sentence() + " Start " + NAME + " again when you want one."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        switch (conclusion.kind())
        {
            case VANISHED:
                // The session is no longer registered. WHY decides what may be said: a
                // cancellation this launch took part in is first-hand evidence and is reported as
                // one; without it, all that is established is that the comparison can no longer
                // be read, and calling that an EDT cancellation would put words in the platform's
                // mouth.
                if (launch.stopWasRequested())
                {
                    return "**Cancelled:** comparison `" + id + "` was stopped before it " //$NON-NLS-1$ //$NON-NLS-2$
                        + "finished. " + conclusion.detail() + ' ' + handback.sentence(); //$NON-NLS-1$
                }
                throw new ComparisonException("Comparison '" + id + "' can no longer be read: " //$NON-NLS-1$ //$NON-NLS-2$
                    + conclusion.detail() + " Nobody asked this job to stop, so the comparison " //$NON-NLS-1$
                    + "was ended outside it - in the workbench, through releaseComparisonId, or " //$NON-NLS-1$
                    + "by the idle sweep. " + handback.sentence() + " Start " + NAME //$NON-NLS-1$ //$NON-NLS-2$
                    + " again."); //$NON-NLS-1$
            case CANCELLED:
                return "**Cancelled:** comparison `" + id + "` was stopped before it finished. " //$NON-NLS-1$ //$NON-NLS-2$
                    + conclusion.detail() + ' ' + handback.sentence();
            case FAILED:
                throw new ComparisonException("Comparison '" + id + "' failed: " //$NON-NLS-1$ //$NON-NLS-2$
                    + conclusion.detail() + ". " + handback.sentence() //$NON-NLS-1$
                    + " Check the revisions with list_git_branches and the project state with " //$NON-NLS-1$
                    + "get_project_errors, then start " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
            case NEVER_STARTED:
                throw new ComparisonException("Comparison '" + id + "' was accepted by EDT but " //$NON-NLS-1$ //$NON-NLS-2$
                    + "never started: EDT has not listed it once in " //$NON-NLS-1$
                    + TimeUnit.MILLISECONDS.toSeconds(MAX_STARTING_TICKS * POLL_INTERVAL_MS)
                    + " seconds (" + conclusion.detail() + "). " + handback.sentence() //$NON-NLS-1$ //$NON-NLS-2$
                    + " Check EDT for a stuck background task, then start " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
            case UNREADABLE:
                throw new ComparisonException("Comparison '" + id + "' could not be read: EDT " //$NON-NLS-1$ //$NON-NLS-2$
                    + "gave no status for " + MAX_UNREADABLE_TICKS + " polls in a row (" //$NON-NLS-1$ //$NON-NLS-2$
                    + conclusion.detail() + "). " + handback.sentence() //$NON-NLS-1$
                    + " Check the EDT error log for the failure that was logged, then start " //$NON-NLS-1$
                    + NAME + " again."); //$NON-NLS-1$
            default:
                throw new ComparisonException("Comparison '" + id + "' did not finish within " //$NON-NLS-1$ //$NON-NLS-2$
                    + TimeUnit.MILLISECONDS.toMinutes(JOB_TIMEOUT_MS) + " minutes. " //$NON-NLS-1$
                    + handback.sentence()
                    + " Narrow the comparison with scope, or check EDT for a stuck background " //$NON-NLS-1$
                    + "task, and start " + NAME + " again."); //$NON-NLS-1$ //$NON-NLS-2$
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
        SlotHandback handback = backend.handBack(id, Ending.CANCELLED);
        if (handback.slotIsFree())
        {
            return CommittedCancellation.stopped(handback.sentence(), null);
        }
        // NOT stopped, and said as such: a STOPPED verdict is what the job registry turns into
        // TERMINATED, and a caller reading TERMINATED stops looking. The hand-back's own sentence
        // is used verbatim - this site used to word the outcome itself, and worded a stop that
        // had not happened.
        return CommittedCancellation.notStopped(handback.sentence());
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
     * Why a comparison tree could not be read, or {@code null} when it can be.
     *
     * <h2>Three answers, not two</h2>
     * The view used to be resolved with {@code orElse(null)}, which folded "EDT's comparison
     * service could not be asked" into "EDT says it no longer knows this handle" - and the refusal
     * then told the caller their comparison had been ended outside this server. That is a claim
     * about the comparison built out of a fact about this server's reach, and the two send the
     * caller to opposite places: one starts a new comparison, the other waits a moment and reads
     * the same one again. The comparison is still registered, still holds EDT's slot and still
     * resolves its nodeIds.
     * <p>
     * A pure function of the answer, and separate so it can be pinned for every input - the same
     * reason the launch's refusal decision is one.
     *
     * Generic in what the platform answered with, because that payload is not what it decides on:
     * the three cases are "could not ask", "asked and got nothing" and "asked and got something",
     * and only the last one is a readable tree whatever type carries it.
     *
     * @param <T> what the platform answers with
     * @param answer what the facade said when asked for the view
     * @param comparisonId the comparison the caller quoted
     * @return the refusal message, or {@code null} when {@code answer} carries a usable view
     */
    static <T> String unreadableTreeMessage(PlatformAnswer<T> answer, String comparisonId)
    {
        // The fork itself belongs to the shared vocabulary, not to this tool: get_comparison_node
        // reads a tree too and reached the same three answers, and the one place the decision was
        // written twice is the one place it was made wrongly once.
        ToolResult refusal = ComparisonFailures.unreadableTree(answer, comparisonId);
        return refusal == null ? null : messageOf(refusal);
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
     * @param path the path, may be {@code null}
     * @return the most canonical form obtainable, or {@code null} for a {@code null} path
     */
    private static Path toRealForm(Path path)
    {
        if (path == null)
        {
            return null;
        }
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
     * The three paths the two git data sources are built from, ALL in their real form, and the
     * only place they are produced.
     * <p>
     * Canonicalising them for the guard alone was half a fix. The guard decides path identity the
     * way git does, so it stops answering "outside the work tree" for a project that is inside it
     * under a different spelling - but the descriptors were then handed the ORIGINAL, mutually
     * inconsistent paths, and the platform's git data source has to derive the project's location
     * RELATIVE TO the repository out of exactly those two. When the two disagree in form - a
     * workspace location recorded through a symlink against a canonical work tree - that
     * subtraction yields nothing, both git sides read empty, and the comparison reports every
     * object as added on main: a wrong answer presented as a good one, which is the very outcome
     * the guard exists to prevent. So the canonical forms are what the descriptors get, and the
     * raw ones are consumed here and never seen again.
     * <p>
     * BOTH git sides are canonicalised, not just the one the guard looks at. The two revisions are
     * resolved independently, so they can arrive spelled differently; a comparison whose "other"
     * side reads the project and whose "ancestor" side reads nothing would report the ancestor as
     * empty and every object as added since it - a difference that is an artefact of the spelling.
     * <p>
     * And BOTH are GUARDED, for the same reason they are both canonicalised. The two revisions are
     * resolved by two independent calls, so they can answer with two DIFFERENT work trees - the
     * project's repository binding changed between them, or one revision resolved through a linked
     * worktree - and checking only the other side let the ancestor's data source be built over a
     * repository nothing had established the project lives in. The mismatch is refused first and
     * names both paths, because "the ancestor read nothing" and "the ancestor read a different
     * repository" call for different fixes and neither is visible in a comparison that quietly
     * succeeds.
     *
     * @param projectName the project name, for the message
     * @param projectPath the project location as the workspace records it
     * @param otherWorkTree the work tree the other revision resolved to, may be {@code null}
     * @param ancestorWorkTree the work tree the ancestor revision resolved to, may be {@code null}
     * @return the canonical paths to build the data sources from
     * @throws ComparisonException when the two sides resolved to different work trees, or when the
     *     project provably lies outside one of them
     */
    // Package-private so the canonicalisation can be pinned without an EDT workspace, the same way
    // requireProjectInsideWorkTree is.
    static GitSidePaths gitSidePaths(String projectName, Path projectPath, Path otherWorkTree,
        Path ancestorWorkTree) throws ComparisonException
    {
        GitSidePaths sides = new GitSidePaths(toRealForm(projectPath), toRealForm(otherWorkTree),
            toRealForm(ancestorWorkTree));
        requireOneWorkTree(projectName, sides.otherWorkTree(), sides.ancestorWorkTree());
        requireProjectInsideWorkTree(projectName, sides.projectPath(), sides.otherWorkTree());
        requireProjectInsideWorkTree(projectName, sides.projectPath(), sides.ancestorWorkTree());
        return sides;
    }

    /**
     * Refuses a launch whose two revisions resolved to DIFFERENT git work trees.
     * <p>
     * A three-way comparison is three views of one repository. The two revisions are resolved by
     * two independent calls, though, so nothing in the call chain makes them answer with the same
     * work tree: a repository binding that changed between the two resolutions, or a revision that
     * resolved through a linked worktree, produces two, and the comparison would then read its
     * "other" side out of one repository and its ancestor out of another. The result is a report
     * whose every difference is an artefact of the pairing, presented as a difference between
     * revisions.
     * <p>
     * Compared in REAL form, and only when BOTH are known: one side that could not be resolved to a
     * work tree at all is a gap in what this server could see, not a mismatch, and the per-side
     * guard already declines to claim anything about it.
     *
     * @param projectName the project name, for the message
     * @param otherWorkTree the other side's work tree in real form, may be {@code null}
     * @param ancestorWorkTree the ancestor side's work tree in real form, may be {@code null}
     * @throws ComparisonException when the two sides name two different work trees
     */
    // Package-private for the same reason requireProjectInsideWorkTree is.
    static void requireOneWorkTree(String projectName, Path otherWorkTree, Path ancestorWorkTree)
        throws ComparisonException
    {
        if (otherWorkTree == null || ancestorWorkTree == null
            || otherWorkTree.equals(ancestorWorkTree))
        {
            return;
        }
        throw new ComparisonException("The two revisions of '" + projectName //$NON-NLS-1$
            + "' resolved to DIFFERENT git work trees: the other side to " + otherWorkTree //$NON-NLS-1$
            + " and the common ancestor to " + ancestorWorkTree //$NON-NLS-1$
            + ". A three-way comparison is three views of ONE repository, so the two sides would " //$NON-NLS-1$
            + "be read out of two, and every difference in the report would be an artefact of " //$NON-NLS-1$
            + "that pairing rather than of the revisions. Name two revisions of the same " //$NON-NLS-1$
            + "repository, and check the project's binding with 'git rev-parse " //$NON-NLS-1$
            + "--show-toplevel'."); //$NON-NLS-1$
    }

    /** The canonical paths one launch hands to the platform's two git data sources. */
    static final class GitSidePaths
    {
        private final Path projectPath;

        private final Path otherWorkTree;

        private final Path ancestorWorkTree;

        GitSidePaths(Path projectPath, Path otherWorkTree, Path ancestorWorkTree)
        {
            this.projectPath = projectPath;
            this.otherWorkTree = otherWorkTree;
            this.ancestorWorkTree = ancestorWorkTree;
        }

        /**
         * @return the project location, in real form
         */
        Path projectPath()
        {
            return projectPath;
        }

        /**
         * @return the other side's work tree, in real form, or {@code null} when unknown
         */
        Path otherWorkTree()
        {
            return otherWorkTree;
        }

        /**
         * @return the ancestor side's work tree, in real form, or {@code null} when unknown
         */
        Path ancestorWorkTree()
        {
            return ancestorWorkTree;
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
     * How the wait for one comparison ended, before anything has been said about it and before
     * EDT's single slot has been touched.
     *
     * <h2>Why the endings are a type</h2>
     * They used to be eight {@code return} and {@code throw} statements scattered through the poll
     * loop, and each of them re-answered "does the slot go back", "is a cancellation still owed"
     * and "what may I claim". Three review rounds found the same defect in a different one of them
     * each time. Naming the endings turns those three questions into properties of the ending -
     * answered once, in {@link CompareConfigurationsTool#conclude} - so the loop can only REPORT
     * an ending and a new one cannot come with its own answers.
     */
    static final class Conclusion
    {
        /** The eight ways the wait can end. */
        enum Kind
        {
            /** The comparison finished; its tree can be read. */
            FINISHED(false),
            /** The comparison failed; the detail carries the platform's reason. */
            FAILED(false),
            /** EDT reported the comparison as cancelled. */
            CANCELLED(false),
            /** The session is no longer registered here. */
            VANISHED(false),
            /** EDT accepted the batch and never listed the handle; this job ends it. */
            NEVER_STARTED(true),
            /** EDT gave no status for the whole unreadable budget; this job ends it. */
            UNREADABLE(true),
            /** The job's own time budget ran out; this job ends it. */
            OUT_OF_TIME(true),
            /** A cancellation was handed to the launch; this job ends it. */
            HANDED_OVER_STOP(true);

            private final boolean endsItEarly;

            Kind(boolean endsItEarly)
            {
                this.endsItEarly = endsItEarly;
            }

            /**
             * Whether THIS JOB is ending a comparison that had not ended by itself.
             * <p>
             * It selects which of EDT's two hand-back verbs the platform records, and nothing
             * else - see {@link SlotHandback.Ending}, which carries the bytecode reading showing
             * the two are one operation. It is a property of the ending rather than an argument
             * at the call site, because "did I end this or did it end?" is exactly the kind of
             * question eight sites answered eight ways.
             *
             * @return {@code true} when the comparison was still going and this job ended it
             */
            boolean endsItEarly()
            {
                return endsItEarly;
            }
        }

        private final Kind kind;
        private final String detail;

        private Conclusion(Kind kind, String detail)
        {
            this.kind = kind;
            this.detail = detail;
        }

        /**
         * @param kind how the wait ended
         * @param detail what was observed, in the words the caller will read
         * @return the ending
         */
        static Conclusion of(Kind kind, String detail)
        {
            return new Conclusion(kind, detail);
        }

        /**
         * @return how the wait ended
         */
        Kind kind()
        {
            return kind;
        }

        /**
         * @return what was observed - never a status EDT did not give
         */
        String detail()
        {
            return detail;
        }

        @Override
        public String toString()
        {
            return kind + "(" + detail + ')'; //$NON-NLS-1$
        }
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
     * <h2>The hand-over needs somebody still looking, and looking is not taking</h2>
     * One atomic state is necessary and not sufficient: a hand-over that lands after the launch's
     * only look is still lost. So the launch does not look once - {@link #hasHandedOverStop()} is
     * asked at the top of EVERY poll, for as long as the comparison runs.
     * <p>
     * Looking is deliberately separate from taking. {@link #claimHandedOverStop()} is called from
     * exactly one place, {@link CompareConfigurationsTool#conclude}, and it is called for EVERY
     * ending rather than only for the one the loop noticed. The terminal ending is why: it used to
     * read the report and return without claiming anything, so a hand-over that landed while the
     * comparison was FINISHING - after the loop's last look, before the answer was built - was
     * owed by nobody, and the caller had been promised a stop that never came. A loop that claimed
     * as it looked could not fix that, because after the last tick there is no next tick.
     * <p>
     * The remaining and stated limit is narrower but real: a hand-over that lands after
     * {@code conclude} has made its one claim is owed by nobody, and is answered only by the job's
     * own result - which is exactly what the handler's sentence promises, no more, when it sends
     * the caller to {@code get_job_status}. Closing it needs the handler and the job to share one
     * commit point, which is a change to the background-job registry rather than to this class.
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
         * Whether a duty has been handed to the launch, WITHOUT taking it.
         * <p>
         * The poll loop looks with this and the single exit claims with
         * {@link #claimHandedOverStop()}. Splitting looking from taking is what lets the claim
         * happen exactly once, at the one place every ending passes through: a loop that claimed
         * as it looked would leave the endings it does not run through - the terminal one above
         * all - with no claim at all, which is the defect this split removes.
         *
         * @return {@code true} while the launch owes an outstanding cancellation
         */
        boolean hasHandedOverStop()
        {
            return duty.get() == StopDuty.LAUNCH;
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
         * Claims EDT's single comparison slot for this launch, in ONE indivisible step.
         * <p>
         * Distinct from {@link #activeComparisonId()} because a reading is not a reservation: the
         * whole preparation of a launch runs between the two, and two launches that both read
         * "free" both used to prepare and both used to register. See
         * {@code ComparisonSessionRegistry.claimSlot}.
         *
         * @param request the validated request
         * @return a granted claim carrying the id to start under, or a refused one carrying the
         *     owner's own sentence about what holds the slot
         */
        SlotClaim claimSlot(LaunchRequest request);

        /**
         * @param request the validated request
         * @param claim this launch's granted claim; the started comparison keeps its id
         * @return this plugin's id for the started comparison
         * @throws ComparisonException when the comparison could not be started
         */
        String start(LaunchRequest request, SlotClaim claim) throws ComparisonException;

        /**
         * Gives up a claim whose launch never reached the platform.
         * <p>
         * It touches the claim alone. A claim names no handle, so this can never drop the record
         * of a comparison that may be running - the case a hand-back keeps a record for - and once
         * the claim has been adopted it does nothing at all.
         *
         * @param claim the claim to give up
         */
        void withdrawClaim(SlotClaim claim);

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
         * Ends the comparison, gives EDT's single slot back, and says what that achieved.
         *
         * <h2>One method where there were two</h2>
         * This used to be a {@code cancel} for a running comparison and a {@code release} for one
         * that had ended, and every caller picked between them from what it believed the state
         * to be. The two were the same operation - EDT's own {@code cancel} and {@code stop}
         * differ only in what they record, see {@link SlotHandback.Ending} - and picking wrongly
         * was invisible, because both answered "done". Now the platform's answer decides what
         * happened and the caller decides only what to call it.
         *
         * <h2>Why it answers a value and not nothing</h2>
         * Every caller publishes a sentence about EDT's single slot, and three of the five things
         * this can observe are not a freed slot at all: the service can be unregistered at that
         * moment, the hand-back can fail, and the id can name nothing. A {@code void} answer left
         * those indistinguishable from a stop, and the tool reported stops that had not happened.
         * The answer carries its own sentence so no caller has to word one.
         *
         * @param comparisonId the started comparison
         * @param ending why the comparison is ending; it selects EDT's verb and nothing else
         * @return what was observed; never {@code null}
         */
        SlotHandback handBack(String comparisonId, Ending ending);

        /** @return the comparison ids a caller may still quote, oldest first */
        List<String> liveComparisonIds();

        /**
         * Whether EDT itself reports something in its single comparison slot.
         * <p>
         * Asked only to keep a refusal honest: an empty local registry proves nothing about a
         * comparison launched from the workbench, which is never registered here.
         *
         * @return EDT's answer, or {@link PlatformAnswer#unavailable()} when the comparison
         *         service could not be asked - a third case, and not a "no"
         */
        PlatformAnswer<Boolean> edtHasActiveComparison();
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
        public SlotClaim claimSlot(LaunchRequest request)
        {
            // The INSTALLED registry, like every other ownership question here: ComparisonEngine
            // .get() also reports "unavailable" while EDT's service is momentarily unregistered,
            // and the slot is this server's bookkeeping rather than the platform's.
            return ComparisonSessionRegistry.shared().claimSlot(request.getProjectName());
        }

        @Override
        public void withdrawClaim(SlotClaim claim)
        {
            ComparisonSessionRegistry.shared().withdrawClaim(claim.comparisonId());
        }

        @Override
        public String start(LaunchRequest request, SlotClaim claim) throws ComparisonException
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
            return launch(engine, request, claim, other, ancestor, scopeObject(scoping));
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
         * @param claim the claim this launch holds on EDT's single slot
         * @param other the resolved other revision
         * @param ancestor the resolved ancestor revision
         * @param scope the comparison scope
         * @return this plugin's id for the started comparison
         * @throws ComparisonException when the project is not a 1C project or the launch fails
         */
        private static String launch(ComparisonEngine engine, LaunchRequest request,
            SlotClaim claim, GitRevisionResolver.Revision other,
            GitRevisionResolver.Revision ancestor, ComparisonScope scope) throws ComparisonException
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

            GitSidePaths sides = gitSidePaths(request.getProjectName(),
                project.getLocation().toFile().toPath(), other.workTree(), ancestor.workTree());
            ComparisonProcessHandle handle = new ComparisonProcessHandle(
                new V8ProjectComparisonDataSourceDescriptor(v8Project),
                new GitComparisonDataSourceDescriptor(sides.otherWorkTree(), other.commitId(),
                    sides.projectPath()),
                new GitComparisonDataSourceDescriptor(sides.ancestorWorkTree(), ancestor.commitId(),
                    sides.projectPath()),
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
            return registerAndHandOver(engine, claim, handle, batch);
        }

        @Override
        public Progress poll(String comparisonId)
        {
            // The INSTALLED facade, not the available one. A poll that cannot reach EDT's
            // comparison service has observed nothing about the comparison, and this facade
            // already has a word for that: every reading call answers PlatformAnswer.unavailable(),
            // which becomes Phase.UNKNOWN and costs the loop one tick of its unreadable budget.
            // Going through ComparisonEngine.get() short-circuited all of it - a service
            // unregistered for a moment produced a FAILED verdict before a single question was
            // asked, and the failed branch then ended a healthy comparison and stranded it holding
            // EDT's single slot under an id nobody could quote. The one case left here is a facade
            // that was never installed, which is the bundle not being started at all.
            Optional<ComparisonEngine> engine = ComparisonEngine.attached();
            if (engine.isEmpty())
            {
                return Progress.gone("This server's comparison facade is not installed, so the " //$NON-NLS-1$
                    + "comparison cannot be reached at all."); //$NON-NLS-1$
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
            // LEASED for the whole read, not looked up for an instant at the start of it. The
            // sweep measures idleness from the last lookup, and walking a large configuration is
            // one lookup followed by minutes of BM reads: a comparison whose tree takes longer
            // than the idle TTL would be ended underneath the very read that is walking it. The
            // lease also carries the handle, so the liveness question is asked once rather than
            // twice with two answers that can disagree.
            try (ComparisonSessionRegistry.Lease lease = engine.sessions().lease(comparisonId))
            {
                if (!lease.held())
                {
                    throw new ComparisonException(
                        messageOf(ComparisonFailures.sessionGone(comparisonId)));
                }
                return renderTree(engine, lease.handle(), comparisonId, request);
            }
        }

        /**
         * Walks one leased comparison tree and renders it.
         *
         * @param engine the read-only facade
         * @param handle the leased comparison's handle
         * @param comparisonId this plugin's id for it
         * @param request the request, for the page size and the filter
         * @return the rendered Markdown report
         * @throws ComparisonException when the tree could not be read
         */
        private static String renderTree(ComparisonEngine engine, ComparisonProcessHandle handle,
            String comparisonId, LaunchRequest request) throws ComparisonException
        {
            ComparisonTreeReport.Collector collector = new ComparisonTreeReport.Collector(
                request.getLimit(), request.isChangedOnly());
            try
            {
                PlatformAnswer<ComparisonView> answer = engine.view(handle);
                String unreadable = unreadableTreeMessage(answer, comparisonId);
                if (unreadable != null)
                {
                    throw new ComparisonException(unreadable);
                }
                ComparisonView view = answer.orElse(null);
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
        public SlotHandback handBack(String comparisonId, Ending ending)
        {
            // ONE line, and it is the whole implementation on purpose. Ending a comparison and
            // dropping its record are halves of one decision, and this file used to make that
            // decision in two methods and five call sites: one branch stopped the platform and
            // let the record go on a hand-back that had failed, another dropped the record with
            // no stop attempted at all because EDT's service had blinked, and a third threw the
            // answer away. None of those is expressible now - the registry owns the decision, its
            // verdict says what happened, and the platform's two lifetime verbs cannot even be
            // named from here.
            //
            // Reached through the registry's own entry point rather than through
            // ComparisonEngine.get(): get() also reports "unavailable" while EDT's service is
            // momentarily unregistered, and a session must stay addressable across such a gap -
            // it still owns a virtual project, and the hand-back is exactly the thing that has to
            // be able to say "I could not ask" instead of dropping it.
            return ComparisonSessionRegistry.shared().handBack(comparisonId, ending);
        }

        @Override
        public List<String> liveComparisonIds()
        {
            return ComparisonSessionRegistry.shared().ids();
        }

        @Override
        public PlatformAnswer<Boolean> edtHasActiveComparison()
        {
            Optional<ComparisonEngine> engine = ComparisonEngine.attached();
            return engine.isEmpty() ? PlatformAnswer.unavailable()
                : engine.get().hasActiveComparison();
        }
    }
}
