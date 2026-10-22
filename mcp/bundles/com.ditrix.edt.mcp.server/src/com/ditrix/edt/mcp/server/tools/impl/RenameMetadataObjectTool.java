/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.rename.DisableRequest;
import com.ditrix.edt.mcp.server.tools.rename.MetadataRenameService;
import com.ditrix.edt.mcp.server.tools.rename.RenameProgress;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ReferencedTopObjects;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonParser;

/**
 * Tool to rename a metadata object, one of its members, or a managed-form element, with
 * full refactoring support.
 *
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected refactoring items and problems.
 * 2. Execute mode (confirm=true): Performs the rename with all cascading code updates.
 * <p>
 * A form-element FQN ({@code Type.Object.Form.FormName.<Kind>.Name}, or its
 * {@code CommonForm.FormName.<Kind>.Name} variant) is dispatched to its own branch in the
 * service and renamed through EDT's own form refactoring; every other FQN takes the mdclass
 * path. Both branches produce the same preview / apply contract, so nothing in this adapter
 * distinguishes them - see {@link MetadataRenameService} for what each cascade covers and
 * which form shapes it refuses.
 * <p>
 * Thin adapter: parameter parsing, the required-argument guards, the UI-thread
 * {@code Display.syncExec} boundary and the deadline that keeps a wedged cascade from holding the
 * MCP call open forever live here; all domain logic lives in {@link MetadataRenameService}.
 */
public class RenameMetadataObjectTool implements IMcpTool
{
    /**
     * How long the pre-flight waits for the derived-data pipeline to drain before refusing.
     * <p>
     * Sized against what the alternative costs: entering the cascade with the pipeline still busy
     * makes EDT wait for it from INSIDE its own batch session, which took 301 SECONDS on CI. Waiting
     * here is the same wall-clock in the worst case, but it is OUR wait - bounded, logged, and
     * ending in an actionable error instead of a silent block on the wire.
     */
    private static final long SETTLE_TIMEOUT_MS = 60_000L;

    public static final String NAME = "rename_metadata_object"; //$NON-NLS-1$

    /** Input param: FQN of the metadata object to rename. */
    private static final String KEY_OBJECT_FQN = "objectFqn"; //$NON-NLS-1$

    /** Input param: new programmatic Name for the object. */
    private static final String KEY_NEW_NAME = "newName"; //$NON-NLS-1$

    /** Input key: bound on the cascade itself, in seconds. */
    static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /**
     * Default bound on the cascade (7 minutes).
     * <p>
     * Sized ABOVE the worst LEGITIMATE case rather than above the healthy one, because a bound that
     * expires on a rename which would have succeeded is the dangerous direction: the work is not
     * stopped by the deadline, so we would report failure and the rename would land anyway. The
     * measured pathological-but-completing case is #320's 301 SECONDS - EDT waiting out its own
     * five-minute derived-data timeout from inside the refactoring's batch session - against 6-8
     * seconds for a healthy rename in the same run. 420s clears that by two minutes while staying
     * well inside the 600s per-call budget the e2e matrix uses, so a genuinely wedged rename
     * (issue #365) answers the client instead of being killed by it.
     */
    static final int DEFAULT_RENAME_TIMEOUT_SECONDS = 420;

    /**
     * Smallest accepted cascade bound, in seconds. Deliberately far above {@code clean_project}'s
     * 10s floor: a value that cuts a healthy cascade off mid-flight would MANUFACTURE the
     * half-renamed configuration this bound exists to report on.
     */
    private static final int MIN_RENAME_TIMEOUT_SECONDS = 60;

    /** Largest accepted cascade bound, in seconds. */
    private static final int MAX_RENAME_TIMEOUT_SECONDS = 3600;

    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$
    private static final String KEY_TEXT_OCCURRENCES = "textOccurrences"; //$NON-NLS-1$
    /**
     * Default preview wait: the transport-safe maximum the other job tools use. A preview that
     * finishes within it is answered inline exactly as before the job path existed, so existing
     * callers that read the preview from the answer keep working on a slow machine.
     */
    private static final int DEFAULT_WAIT_SECONDS = 45;
    private static final int MAX_WAIT_SECONDS = 45;
    /** Rename previews running at once; counted among rename previews only, not other tools' jobs. */
    private static final int MAX_RUNNING_PREVIEWS = 2;
    /**
     * Slack between the preview's own stage bounds and the registry deadline, so the stage-named
     * timeout error is what a caller sees, never the registry's generic budget text.
     */
    static final long PREVIEW_JOB_MARGIN_MS = 30_000L;
    /** Smallest bound handed to the bounded UI-thread hand-off when the settle used up the budget. */
    private static final long MIN_PREVIEW_RENAME_BOUND_MS = 1_000L;
    private static final Object PREVIEW_JOBS_LOCK = new Object();
    private static final Map<String, PreviewSubmission> SUBMISSION_JOBS = new ConcurrentHashMap<>();

    private final BackgroundJobs jobs;

    /**
     * One rename preview's place among the {@value #MAX_RUNNING_PREVIEWS}, and its attach entry.
     * <p>
     * The place is held until BOTH the registry job has left RUNNING and every UI-thread hand-off
     * it started has actually ended. A hand-off timeout only stops the WAITING: EDT keeps building
     * the preview on its UI thread, so freeing the place together with the FAILED job would let
     * retries stack new previews on top of ones EDT is still building. There is no time cap on
     * that hold: a hand-off whose work never ends means EDT's UI thread is wedged, and a new
     * preview would only queue behind it, so refusing it until EDT is restarted is the honest
     * answer. Options are retained so
     * attaching cannot silently return another requested plan or slice; the target (the key
     * without the timeout) is retained so such a still-building preview refuses a new preview of
     * the same rename whatever its timeout.
     */
    static final class PreviewSubmission
    {
        private final BackgroundJobs owner;
        private final String target;
        private final boolean excludeText;
        private final int maxResults;
        private String jobId; // guarded by PREVIEW_JOBS_LOCK; set right after the job is admitted
        private int handOffsInFlight; // guarded by this
        private boolean released; // guarded by this

        PreviewSubmission(BackgroundJobs owner, String target, boolean excludeText, int maxResults)
        {
            this.owner = owner;
            this.target = target;
            this.excludeText = excludeText;
            this.maxResults = maxResults;
        }

        /**
         * Starts a hand-off only while the job is still RUNNING in its registry, and commits the
         * job in the same step. The released flag alone is not enough: the registry deadline or
         * {@link BackgroundJobs#close()} can end the job while nobody calls
         * {@link #releaseIfIdle()}, and a hand-off begun then would build a preview after its
         * FAILED outcome was already published. The status is read under
         * {@link #PREVIEW_JOBS_LOCK} and this submission's monitor - the locks the release takes,
         * in the same order - so the check and the count are one step against the release, and
         * {@link #jobId} (set under that lock right after admission) is visible.
         * <p>
         * Work handed to EDT's UI thread cannot be recalled, so the registry's commit handshake
         * ({@link BackgroundJobs.ProgressReporter#tryCommit()}) is taken under the same locks
         * before the count: a {@code cancel_job} that already won it aborts the hand-off, and the
         * job ends cancelled without the UI-thread work ever running; once the commit wins, a
         * later {@code cancel_job} reports {@code alreadyCommitted} instead of a false stop.
         *
         * @param progress the job's reporter, whose {@code tryCommit} is the registry handshake
         * @return {@code false} when the job already left RUNNING, the place was given back, or a
         *     cancellation won the commit handshake, so no hand-off may start
         */
        boolean beginHandOff(BackgroundJobs.ProgressReporter progress)
        {
            synchronized (PREVIEW_JOBS_LOCK)
            {
                synchronized (this)
                {
                    if (released || !jobRunning() || !progress.tryCommit())
                    {
                        return false;
                    }
                    handOffsInFlight++;
                    return true;
                }
            }
        }

        /** Called exactly once per begun hand-off, when its work has actually ended. */
        synchronized void endHandOff()
        {
            handOffsInFlight--;
        }

        /** Gives the place back once the job is no longer running and no hand-off is in flight. */
        synchronized boolean releaseIfIdle()
        {
            if (!released && !jobRunning() && handOffsInFlight == 0)
            {
                released = true;
            }
            return released;
        }

        /** @return whether the job already ended but a hand-off it started is still running */
        synchronized boolean isOrphaned()
        {
            return !released && handOffsInFlight > 0 && !jobRunning();
        }

        private boolean jobRunning()
        {
            JobSnapshot job = jobId == null ? null : owner.get(jobId);
            return job != null && job.getStatus() == BackgroundJobs.Status.RUNNING;
        }
    }

    /** Derived-data settle seam, used inside a preview job and synchronously for confirm. */
    @FunctionalInterface
    interface CascadeSettler
    {
        String settle(String projectName, long timeoutMs);
    }

    private final CascadeSettler cascadeSettler;

    /** Production instance: settle through the live EDT-backed project-state checker. */
    public RenameMetadataObjectTool()
    {
        this((projectName, timeoutMs) -> ProjectStateChecker.settleBeforeCascadeOrError(projectName,
            timeoutMs, NAME, "Nothing was renamed.")); //$NON-NLS-1$
    }

    /** Package-visible test seam for settling before the UI-thread hand-off. */
    RenameMetadataObjectTool(CascadeSettler cascadeSettler)
    {
        this(cascadeSettler, BackgroundJobs.shared());
    }

    /** Isolated registry seam for headless job lifecycle tests. */
    RenameMetadataObjectTool(CascadeSettler cascadeSettler, BackgroundJobs jobs)
    {
        this.cascadeSettler = cascadeSettler;
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
        return "Rename a metadata object or member and rewrite the references EDT RESOLVES for it. " //$NON-NLS-1$
            + "CASCADES ACROSS THE WHOLE CONFIGURATION - BSL, forms, roles, subsystems - but a reference " //$NON-NLS-1$
            + "the refactoring cannot resolve (a dynamically built name) is left pointing at the old " //$NON-NLS-1$
            + "name. Two-phase: call once WITHOUT confirm to " //$NON-NLS-1$
            + "see the edit scope, then again with confirm=true to apply. A long preview continues " //$NON-NLS-1$
            + "as a background job - poll get_job_status. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('rename_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the rename target: an object ('Catalog.Products'), a member " + //$NON-NLS-1$
                "('Document.SalesOrder.Attribute.Amount'), or a managed-form element " + //$NON-NLS-1$
                "('Catalog.Products.Form.ItemForm.Field.Price', " + //$NON-NLS-1$
                "'CommonForm.Settings.Group.Main', " + //$NON-NLS-1$
                "'Catalog.Products.Form.ItemForm.Attribute.Rows.Column.Price'). Russian type " + //$NON-NLS-1$
                "and kind tokens are also accepted.", true) //$NON-NLS-1$
            .stringProperty(KEY_NEW_NAME,
                "New programmatic Name for the rename target (the object, member or " + //$NON-NLS-1$
                "form element addressed by objectFqn).", true) //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = apply the rename; default false = preview only.") //$NON-NLS-1$
            .stringProperty("disableIndices", //$NON-NLS-1$
                "Comma-separated preview '#' indices of OPTIONAL change points to skip, e.g. '2,3,5'. " //$NON-NLS-1$
                + "Entries that cannot be an index at all - not whole numbers, or negative - are " //$NON-NLS-1$
                + "refused before anything runs; an index the current preview simply does not have " //$NON-NLS-1$
                + "is reported back as unknown instead.") //$NON-NLS-1$
            .stringProperty("expectedHash", //$NON-NLS-1$
                "Optimistic-lock token from the preview's contentHash; required when confirm=true " //$NON-NLS-1$
                + "and disableIndices is non-empty.") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Max change points shown in the preview (default 20; 0 = no limit).") //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Preview only: how long this call waits before returning a jobId (default " //$NON-NLS-1$
                + DEFAULT_WAIT_SECONDS + ", accepts 0.." + MAX_WAIT_SECONDS //$NON-NLS-1$
                + "; 0 returns immediately). Poll get_job_status for the preview and contentHash. " //$NON-NLS-1$
                + "Refused with confirm=true; confirm runs synchronously under timeout.") //$NON-NLS-1$
            .enumProperty(KEY_TEXT_OCCURRENCES,
                "include (default) keeps EDT's whole-word text matches enabled; exclude disables " //$NON-NLS-1$
                + "module-file text leaves in both preview and confirm. Use the same value for " //$NON-NLS-1$
                + "both calls so contentHash matches.", "include", "exclude") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .integerProperty(KEY_TIMEOUT,
                "Bounds the server-side computation, not the HTTP call (default " //$NON-NLS-1$
                + DEFAULT_RENAME_TIMEOUT_SECONDS + ", clamped to " + MIN_RENAME_TIMEOUT_SECONDS //$NON-NLS-1$
                + ".." + MAX_RENAME_TIMEOUT_SECONDS + " seconds); it cannot keep the call alive. " //$NON-NLS-1$ //$NON-NLS-2$
                + "waitSeconds bounds the preview call. A preview timeout produces a FAILED job " //$NON-NLS-1$
                + "naming the stage. Confirm stays synchronous and EDT may still finish the rename " //$NON-NLS-1$
                + "after its timeout; verify the model. The pre-flight index drain has a separate " //$NON-NLS-1$
                + "60s bound inside the preview job or before confirm.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        if (projectName != null && !projectName.isEmpty())
        {
            return "rename-refactoring-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "rename-refactoring.md"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String objectFqn = JsonUtils.extractStringArgument(params, KEY_OBJECT_FQN);
        String newName = JsonUtils.extractStringArgument(params, KEY_NEW_NAME);
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        String disableIndicesStr = JsonUtils.extractStringArgument(params, "disableIndices"); //$NON-NLS-1$
        String expectedHash = JsonUtils.extractStringArgument(params, "expectedHash"); //$NON-NLS-1$
        final int maxResults = Math.max(0, JsonUtils.extractIntArgument(params, "maxResults", 20)); //$NON-NLS-1$

        // Parse disable indices. An entry that is not an index is KEPT as a fact rather than thrown
        // away at the split, because the refusal below has to be able to count it: a value discarded
        // where it is parsed no longer exists to refuse over (#401).
        DisableRequest disableRequest = DisableRequest.parse(disableIndicesStr);

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME,
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_OBJECT_FQN,
            ". Examples: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "'Catalog.Products.TabularSection.Prices', " //$NON-NLS-1$
            + "'Catalog.Products.Form.ItemForm.Field.Price'"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_NEW_NAME,
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String memberRefusal = ReferencedTopObjects.memberOperationRefusal(NAME, objectFqn);
        if (memberRefusal != null)
        {
            return ToolResult.error(memberRefusal).toJson();
        }

        // Whether the new name is even an identifier is decided HERE, among the other argument
        // guards - before the settle below. It costs nothing, depends on no project state, and the
        // answer cannot change by waiting; behind the settle it would either be delayed by up to a
        // minute or replaced entirely by a BUILDING refusal, sending the caller off to retry a call
        // that was malformed to begin with. The predicate is the service's, not a second copy.
        String badName = MetadataRenameService.invalidNewNameError(newName);
        if (badName != null)
        {
            return ToolResult.error(badName).toJson();
        }

        // Same place and the same reason as the name check above: an entry that cannot be an index
        // under ANY tree is a defect of the REQUEST, and no amount of waiting or project state can
        // turn it into one. Refusing here keeps a configuration-wide cascade from running on a call
        // the caller demonstrably did not mean, and costs the caller only a corrected retry.
        String badIndices = disableRequest.validationError();
        if (badIndices != null)
        {
            return ToolResult.error(badIndices).toJson();
        }

        if (confirm && !disableRequest.isEmpty()
            && (expectedHash == null || expectedHash.isBlank()))
        {
            return ToolResult.error(MetadataRenameService.missingExpectedHashError()).toJson();
        }

        if (confirm && params.containsKey(KEY_WAIT_SECONDS))
        {
            return ToolResult.error("waitSeconds applies to the preview only; a confirm runs " //$NON-NLS-1$
                + "synchronously under timeout. Omit waitSeconds when confirm=true.").toJson(); //$NON-NLS-1$
        }
        Integer waitSeconds = confirm ? Integer.valueOf(0)
            : BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
                DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params.get(KEY_WAIT_SECONDS), DEFAULT_WAIT_SECONDS, MAX_WAIT_SECONDS);
        }
        String textOccurrences = params.containsKey(KEY_TEXT_OCCURRENCES)
            ? JsonUtils.extractStringArgument(params, KEY_TEXT_OCCURRENCES) : "include"; //$NON-NLS-1$
        if (!"include".equals(textOccurrences) && !"exclude".equals(textOccurrences)) //$NON-NLS-1$ //$NON-NLS-2$
        {
            return ToolResult.error("textOccurrences must be 'include' or 'exclude' " //$NON-NLS-1$
                + "(default 'include'), but was '" + textOccurrences //$NON-NLS-1$
                + "'. Nothing was renamed.").toJson(); //$NON-NLS-1$ //$NON-NLS-1$
        }
        boolean excludeText = "exclude".equals(textOccurrences); //$NON-NLS-1$
        long timeoutMs = resolveRenameTimeoutMs(params);

        // Resolve the workbench only INSIDE the bounded action. Preview settling, the hand-off and
        // the unchanged EDT pipeline all belong to the registry worker, never the HTTP caller.
        IRenameAction action = progress -> {
            Display display = PlatformUI.getWorkbench().getDisplay();
            AtomicReference<String> resultRef = new AtomicReference<>();
            display.syncExec(() -> {
                try
                {
                    resultRef.set(new MetadataRenameService(excludeText).rename(projectName,
                        objectFqn, newName, confirm, disableRequest, expectedHash, maxResults, progress));
                }
                catch (Exception e)
                {
                    Activator.logError("Error in rename_metadata_object", e); //$NON-NLS-1$
                    resultRef.set(ToolResult.error(e.getMessage()).toJson());
                }
            });
            return resultRef.get();
        };
        if (!confirm)
        {
            return startOrAttachJob(projectName, objectFqn, newName, excludeText, maxResults,
                waitSeconds.intValue(), timeoutMs, action);
        }

        // Drain before confirm too: a busy reference index can produce an incomplete cascade.
        // This narrows the pre-flight window; EDT may queue fresh derived work while preparing.
        String building = cascadeSettler.settle(projectName, SETTLE_TIMEOUT_MS);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }
        return runRenameBounded(objectFqn, newName, true, timeoutMs, action);
    }

    /** Admission and attachment share a lock; terminal previews are retrieved only by jobId. */
    String startOrAttachJob(String projectName, String objectFqn, String newName, boolean excludeText,
        int maxResults, int waitSeconds, long timeoutMs, IRenameAction action)
    {
        String target = previewTarget(projectName, objectFqn, newName);
        String key = previewKey(projectName, objectFqn, newName, timeoutMs);
        JobSnapshot started;
        try
        {
            synchronized (PREVIEW_JOBS_LOCK)
            {
                // After this purge the map holds the rename previews that still hold a place: a
                // running job, or a finished one whose UI-thread hand-off EDT is still building.
                SUBMISSION_JOBS.entrySet().removeIf(entry -> entry.getValue().releaseIfIdle());
                PreviewSubmission submission = SUBMISSION_JOBS.get(key);
                if (submission != null && submission.owner != jobs)
                {
                    submission = null; // another registry's preview (a restarted registry, a test)
                }
                started = submission == null ? null : findRunningJob(submission.jobId);
                if (started != null && (submission.excludeText != excludeText
                    || submission.maxResults != maxResults))
                {
                    return ToolResult.error("A preview of this rename is already running with " //$NON-NLS-1$
                        + "different textOccurrences or maxResults in job '" + started.getId() //$NON-NLS-1$
                        + "'. Poll get_job_status with that jobId, then retry with the desired " //$NON-NLS-1$
                        + "options when it finishes.").toJson(); //$NON-NLS-1$
                }
                if (started == null)
                {
                    // Keyed WITHOUT the timeout: a retry with a larger timeout - what the timeout
                    // error suggests - would otherwise queue a second build of the same preview
                    // behind the one EDT is still building on its UI thread.
                    PreviewSubmission orphan = orphanedPreviewOf(target);
                    if (orphan != null)
                    {
                        return ToolResult.error(orphanedPreviewRefusal(objectFqn, newName,
                            orphan.jobId)).toJson();
                    }
                    int held = 0;
                    int orphaned = 0;
                    for (PreviewSubmission other : SUBMISSION_JOBS.values())
                    {
                        if (other.owner == jobs)
                        {
                            held++;
                            orphaned += other.isOrphaned() ? 1 : 0;
                        }
                    }
                    if (held >= MAX_RUNNING_PREVIEWS)
                    {
                        return ToolResult.error(previewLimitRefusal(orphaned)).toJson();
                    }
                }
                if (started == null)
                {
                    PreviewSubmission placed = new PreviewSubmission(jobs, target, excludeText,
                        maxResults);
                    long jobBudgetMs = SETTLE_TIMEOUT_MS + timeoutMs + PREVIEW_JOB_MARGIN_MS;
                    long jobDeadlineNanos = System.nanoTime() + jobBudgetMs * 1_000_000L;
                    // Placed under the admission lock before start, so the limit count and the
                    // attach entry exist the moment the job can run; removed below if start
                    // refused it.
                    SUBMISSION_JOBS.put(key, placed);
                    try
                    {
                        // Capped at the worker count, not at the preview limit: other tools' jobs
                        // then never refuse a preview while a worker is free, and a preview is never
                        // admitted to wait in the queue while its registry deadline runs down.
                        started = jobs.start(NAME, jobBudgetMs, jobs.getWorkerThreads(),
                            "Accepted the rename preview for " //$NON-NLS-1$
                                + MetadataTypeUtils.normalizeFqn(objectFqn) // shared by attached calls
                                + " -> " + newName + ".", progress -> { //$NON-NLS-1$ //$NON-NLS-2$
                                    progress.add("Draining the derived-data pipeline"); //$NON-NLS-1$
                                    String building = cascadeSettler.settle(projectName, SETTLE_TIMEOUT_MS);
                                    if (building != null)
                                    {
                                        throw new BackgroundJobs.MarkedFailure(building, null);
                                    }
                                    if (!placed.beginHandOff(progress))
                                    {
                                        // The job already left RUNNING (its registry deadline, or
                                        // EDT-MCP stopping), whether or not a later request gave
                                        // its place back, or a cancel_job won the commit handshake:
                                        // building the preview now would run after its FAILED or
                                        // cancelled outcome. That outcome stands, so this failure
                                        // only stops the work; its text is a fallback.
                                        throw new BackgroundJobs.MarkedFailure("The rename preview " //$NON-NLS-1$
                                            + "ended before its hand-off to EDT's UI thread began. " //$NON-NLS-1$
                                            + "Nothing was renamed. Start a new preview.", null); //$NON-NLS-1$
                                    }
                                    progress.add("Building the refactoring on EDT's UI thread"); //$NON-NLS-1$
                                    String result = runRenameBounded(objectFqn, newName, false,
                                        previewRenameBoundMs(jobDeadlineNanos, System.nanoTime(), timeoutMs),
                                        action, placed::endHandOff);
                                    if (ToolResult.isErrorPayload(result))
                                    {
                                        String errorText = JsonParser.parseString(result).getAsJsonObject()
                                            .get("error").getAsString(); //$NON-NLS-1$
                                        throw new BackgroundJobs.MarkedFailure(errorText, null);
                                    }
                                    return result;
                                });
                    }
                    finally
                    {
                        if (started == null)
                        {
                            SUBMISSION_JOBS.remove(key, placed);
                        }
                    }
                    if (started == null)
                    {
                        return ToolResult.error("All " + jobs.getWorkerThreads() //$NON-NLS-1$
                            + " background-job workers are busy with other jobs, so the rename " //$NON-NLS-1$
                            + "preview was not started. Nothing was renamed. Retry when one of " //$NON-NLS-1$
                            + "them finishes (get_job_status shows a job's state).").toJson(); //$NON-NLS-1$
                    }
                    placed.jobId = started.getId();
                }
            }
        }
        catch (RejectedExecutionException e)
        {
            return ToolResult.error("Could not start the rename preview because the background-job " //$NON-NLS-1$
                + "registry is full or stopping: " + e.getMessage() //$NON-NLS-1$
                + ". Poll existing jobs with get_job_status and retry.").toJson(); //$NON-NLS-1$
        }
        JobSnapshot latest = BackgroundJobPolling.await(jobs, started.getId(), waitSeconds);
        if (latest == null)
        {
            return ToolResult.error("The rename preview job '" + started.getId() //$NON-NLS-1$
                + "' expired before this call could poll it. Start a new preview.").toJson(); //$NON-NLS-1$
        }
        return renderSnapshot(latest, objectFqn, newName);
    }

    /**
     * How many rename previews of this registry hold a place right now, after giving back the
     * places whose job and UI-thread hand-off have both ended.
     *
     * @return the number of held places
     */
    int heldPreviewPlaces()
    {
        synchronized (PREVIEW_JOBS_LOCK)
        {
            SUBMISSION_JOBS.entrySet().removeIf(entry -> entry.getValue().releaseIfIdle());
            int held = 0;
            for (PreviewSubmission submission : SUBMISSION_JOBS.values())
            {
                held += submission.owner == jobs ? 1 : 0;
            }
            return held;
        }
    }

    /**
     * A preview of the same rename (project, target, new name - any timeout) of this registry whose
     * job already ended while its UI-thread hand-off is still running. Called under
     * {@link #PREVIEW_JOBS_LOCK}.
     */
    private PreviewSubmission orphanedPreviewOf(String target)
    {
        for (PreviewSubmission other : SUBMISSION_JOBS.values())
        {
            if (other.owner == jobs && other.target.equals(target) && other.isOrphaned())
            {
                return other;
            }
        }
        return null;
    }

    private static String previewLimitRefusal(int orphaned)
    {
        String text = MAX_RUNNING_PREVIEWS + " other rename previews are already running, the limit " //$NON-NLS-1$
            + "for rename previews. "; //$NON-NLS-1$
        if (orphaned == 0)
        {
            return text + "Poll them with get_job_status and retry when one finishes."; //$NON-NLS-1$
        }
        return text + orphaned + " of them already ended (typically a timeout), but EDT is " //$NON-NLS-1$
            + "still building " + (orphaned == 1 ? "that preview" : "those previews") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + " on its UI thread: ending a job stops the waiting, not EDT's work, so such a place " //$NON-NLS-1$
            + "is freed only when EDT finishes. Nothing was renamed. Retry after EDT finishes; " //$NON-NLS-1$
            + "get_job_status shows the state of the others."; //$NON-NLS-1$
    }

    private static String orphanedPreviewRefusal(String objectFqn, String newName, String jobId)
    {
        return "A previous preview of '" + objectFqn + "' -> '" + newName + "' (job '" + jobId //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "') already ended (typically a timeout), but EDT is still building it on its UI " //$NON-NLS-1$
            + "thread: ending the job stops the waiting, not EDT's work. A new preview of the same " //$NON-NLS-1$
            + "rename is not started on top of it, whatever its timeout. Nothing was renamed. Retry " //$NON-NLS-1$
            + "after EDT finishes that work; this refusal stops then."; //$NON-NLS-1$
    }

    /**
     * The bound for the UI-thread hand-off inside a preview job: the caller's {@code timeout}, cut
     * down when the settle ran long so the stage-named timeout still fires
     * {@link #PREVIEW_JOB_MARGIN_MS} before the registry deadline would fail the job with its
     * generic budget text.
     *
     * @param jobDeadlineNanos the registry deadline of the job, on the {@code nanoTime} clock
     * @param nowNanos the current {@code nanoTime}
     * @param timeoutMs the caller's cascade bound
     * @return the bound in milliseconds, never below {@value #MIN_PREVIEW_RENAME_BOUND_MS}
     */
    static long previewRenameBoundMs(long jobDeadlineNanos, long nowNanos, long timeoutMs)
    {
        long remainingMs = (jobDeadlineNanos - nowNanos) / 1_000_000L - PREVIEW_JOB_MARGIN_MS;
        return Math.max(MIN_PREVIEW_RENAME_BOUND_MS, Math.min(timeoutMs, remainingMs));
    }

    /**
     * The attachment key: project, the target exactly as the preview echoes it, the new name and
     * the effective cascade bound.
     * <p>
     * The target is only {@link MetadataTypeUtils#normalizeFqn normalized} - the one normalization
     * the service applies before it echoes {@code objectFqn} - so an attached caller receives
     * exactly the answer its own call would have produced. Spellings the service echoes
     * differently (a form element written with Russian form or kind tokens) are different keys
     * and run their own previews. The bound is part of the key because an attached call would
     * otherwise run under another caller's {@code timeout}; it is NOT part of the
     * {@link #previewTarget target} the still-building refusal is keyed by.
     */
    static String previewKey(String projectName, String objectFqn, String newName, long timeoutMs)
    {
        return previewTarget(projectName, objectFqn, newName) + "|" + timeoutMs; //$NON-NLS-1$
    }

    /**
     * The rename a preview computes, whatever its bound: project, the target as the preview echoes
     * it and the new name. A preview whose job ended while EDT still builds it refuses every new
     * preview of the same target.
     */
    static String previewTarget(String projectName, String objectFqn, String newName)
    {
        return projectName + "|" + MetadataTypeUtils.normalizeFqn(objectFqn) + "|" + newName; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private JobSnapshot findRunningJob(String jobId)
    {
        JobSnapshot job = jobId == null ? null : jobs.get(jobId);
        return job != null && job.getStatus() == BackgroundJobs.Status.RUNNING ? job : null;
    }

    private static String renderSnapshot(JobSnapshot job, String objectFqn, String newName)
    {
        switch (job.getStatus())
        {
        case DONE:
            return (String)job.getResult();
        case FAILED:
            return ToolResult.error(job.getErrorMessage()).toJson();
        case RUNNING:
            return "**Pending:** the preview of '" + objectFqn + "' -> '" + newName //$NON-NLS-1$ //$NON-NLS-2$
                + "' continues in background job `" + job.getId() //$NON-NLS-1$
                + "`. Nothing was or will be renamed by it. Poll `get_job_status` with " //$NON-NLS-1$
                + "`jobId=\"" + job.getId() + "\"`; its Result is the preview - take contentHash " //$NON-NLS-1$ //$NON-NLS-2$
                + "and the '#' indices from there, then call rename_metadata_object again with " //$NON-NLS-1$
                + "confirm=true (a synchronous call bounded by timeout).\n\n" //$NON-NLS-1$
                + BackgroundJobRenderer.render(job);
        default:
            return BackgroundJobRenderer.render(job);
        }
    }

    /**
     * Resolves the cascade bound for this call: the explicit {@code timeout} argument when given,
     * else the configured per-tool default, clamped to the accepted range.
     *
     * @param params the raw tool arguments
     * @return the bound in milliseconds
     */
    static long resolveRenameTimeoutMs(Map<String, String> params)
    {
        int configuredDefault = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_TIMEOUT, DEFAULT_RENAME_TIMEOUT_SECONDS);
        int seconds = JsonUtils.extractIntArgument(params, KEY_TIMEOUT, configuredDefault);
        return clampTimeoutSeconds(seconds) * 1000L;
    }

    /**
     * Clamps a cascade bound to the accepted range.
     *
     * @param seconds the requested bound in seconds
     * @return the accepted bound in seconds
     */
    static int clampTimeoutSeconds(int seconds)
    {
        if (seconds < MIN_RENAME_TIMEOUT_SECONDS)
        {
            return MIN_RENAME_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_RENAME_TIMEOUT_SECONDS);
    }

    /**
     * Runs the rename under a hard deadline and translates anything but a completed run into an
     * actionable error.
     *
     * <p>The work runs in a {@link BoundedJob}, so the caller stops waiting when the deadline
     * elapses even though the UI thread cannot be preempted. That is the whole guarantee: the job
     * is asked to cancel, but a rename already inside EDT's refactoring polls nothing and WILL run
     * to completion on its own. The error therefore never claims the rename was undone - it reports
     * the {@link RenameProgress.Phase} the work had reached, which is the difference between "the
     * model is untouched" and "the model may be half renamed".
     *
     * @param objectFqn the rename target, for the message
     * @param newName the requested new Name, for the message
     * @param confirm whether this call was allowed to apply anything at all - a preview cannot
     *     reach the apply path, so no phase it times out in may be reported as possibly-applied
     * @param timeoutMs the bound, in milliseconds
     * @param action the rename action (production drives the service through the UI thread; tests
     *     substitute a controllable action to exercise the deadline without a workbench)
     * @return the action's own payload when it completed, otherwise the error JSON
     */
    static String runRenameBounded(String objectFqn, String newName, boolean confirm, long timeoutMs,
        IRenameAction action)
    {
        return runRenameBounded(objectFqn, newName, confirm, timeoutMs, action, null);
    }

    /**
     * {@link #runRenameBounded(String, String, boolean, long, IRenameAction)} that also reports when
     * the bounded work has ACTUALLY ended, which after a timeout or an interruption is later than
     * this method's return: the deadline stops the waiting, not the work on EDT's UI thread.
     *
     * @param workEnded run exactly once when the work has ended - at once after a conclusive
     *     outcome, else ONLY on the Job's terminal notification. There is no time cap: work that
     *     never ends is a wedged UI thread, and its place stays held until EDT is restarted;
     *     {@code null} for none
     */
    static String runRenameBounded(String objectFqn, String newName, boolean confirm, long timeoutMs,
        IRenameAction action, Runnable workEnded)
    {
        RenameProgress progress = new RenameProgress();
        AtomicReference<String> resultRef = new AtomicReference<>();

        Runnable ended = workEnded == null ? null : onceOnly(workEnded);
        BoundedJob.Result result = null;
        try
        {
            // The Job manager delivers done() for every scheduled job once it ends - ran, raised,
            // or was cancelled before it started - and BoundedJob runs the callback itself when
            // scheduling raises. So the done notification is the release for every inconclusive
            // outcome; no timer stands in for it.
            result = BoundedJob.run(NAME + ": " + objectFqn, timeoutMs, //$NON-NLS-1$
                monitor -> resultRef.set(action.rename(progress)), ended);
        }
        finally
        {
            // Released at once only when the work provably ended or never started: COMPLETED,
            // TIMED_OUT_BEFORE_START (our cancel kept it from starting), or run() raising - it
            // raises only before the job is scheduled, or from the scheduler after running the
            // callback itself. TIMED_OUT, INTERRUPTED and NOT_RUN (a suspended Job manager may
            // still run the job) wait for the done notification.
            boolean conclusive = result == null
                || result.getOutcome() == BoundedJob.Outcome.COMPLETED
                || result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START;
            if (ended != null && conclusive)
            {
                ended.run();
            }
        }

        switch (result.getOutcome())
        {
        case TIMED_OUT:
            return timeoutError(objectFqn, newName, confirm, timeoutMs, progress.getPhase());
        case TIMED_OUT_BEFORE_START:
            return notStartedError(objectFqn, newName, timeoutMs);
        case INTERRUPTED:
            return inFlightMutationError("The rename of '" + objectFqn + "' was interrupted while " //$NON-NLS-1$ //$NON-NLS-2$
                + "waiting for it. " + stateAdvice(confirm, progress.getPhase(), //$NON-NLS-1$
                    inspectorFor(objectFqn)), confirm, progress.getPhase());
        case NOT_RUN:
            return ToolResult.error("The rename of '" + objectFqn + "' was cancelled before it " //$NON-NLS-1$ //$NON-NLS-2$
                + "started, so nothing was renamed. Retry; if it keeps happening, EDT is shutting " //$NON-NLS-1$
                + "down or another operation is cancelling background jobs.").toJson(); //$NON-NLS-1$
        case COMPLETED:
            break;
        default:
            // Fail CLOSED on an outcome added to BoundedJob later. The old 'default: break' fell
            // through and returned the (possibly null) payload below, which for a cascade rename
            // means answering an agent with silence about a model it may have changed.
            return inFlightMutationError("The rename of '" + objectFqn + "' ended in an unrecognised " //$NON-NLS-1$ //$NON-NLS-2$
                + "state (" + result.getOutcome() + "), so whether it applied is unknown. Check " //$NON-NLS-1$ //$NON-NLS-2$
                + "the target's name with " + inspectorFor(objectFqn) //$NON-NLS-1$
                + " before retrying.", confirm, progress.getPhase()); //$NON-NLS-1$
        }

        Throwable failure = result.getFailure();
        if (failure != null)
        {
            // The service catches its own exceptions; reaching here means the hand-off to the UI
            // thread itself failed (a disposed display, a workbench shutting down).
            Activator.logError("Error in rename_metadata_object", failure); //$NON-NLS-1$
            return completedMutationError(failure.getMessage(), confirm, progress.getPhase());
        }
        String answer = resultRef.get();
        return markCompletedMutationError(answer, confirm, progress.getPhase());
    }

    /**
     * Wraps the place release so the done notification and a conclusive outcome, which can both
     * arrive, release it exactly once.
     *
     * @param release the place release itself
     * @return the release to run any number of times, acting on the first call only
     */
    static Runnable onceOnly(Runnable release)
    {
        AtomicBoolean done = new AtomicBoolean();
        return () -> {
            if (done.compareAndSet(false, true))
            {
                release.run();
            }
        };
    }

    /**
     * Builds the error for a rename the deadline caught while it was still QUEUED.
     *
     * <p>Deliberately NOT {@link #timeoutError}: every branch there ends in "it is not cancelled
     * and may still apply", and here the opposite is true — OUR cancel is what kept the rename
     * from starting. Telling an agent a cascade may still land when it never began would send it
     * checking, or worse re-renaming, for nothing.
     *
     * @param objectFqn the rename target
     * @param newName the requested new Name
     * @param timeoutMs the bound that elapsed, in milliseconds
     * @return the error JSON
     */
    private static String notStartedError(String objectFqn, String newName, long timeoutMs)
    {
        long seconds = Math.max(1, Math.round(timeoutMs / 1000.0));
        return ToolResult.error("Renaming '" + objectFqn + "' to '" + newName + "' did not START " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "within " + seconds + (seconds == 1 ? " second" : " seconds") + ": the deadline " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "elapsed while the work was still queued, and cancelling it kept it from starting. " //$NON-NLS-1$
            + "NOTHING was renamed and the model is untouched - no check or cleanup is needed. " //$NON-NLS-1$
            + "EDT's job scheduler is saturated - retry when it is less busy, or pass a larger '" //$NON-NLS-1$
            + KEY_TIMEOUT + "' (seconds).").toJson(); //$NON-NLS-1$
    }

    /**
     * Builds the timeout error: what did not finish, how long we waited, what the model is in, and
     * the lever that raises the bound.
     *
     * @param objectFqn the rename target
     * @param newName the requested new Name
     * @param confirm whether this call was allowed to apply anything
     * @param timeoutMs the bound that elapsed, in milliseconds
     * @param phase the last phase the rename reported entering
     * @return the error JSON
     */
    private static String timeoutError(String objectFqn, String newName, boolean confirm,
        long timeoutMs, RenameProgress.Phase phase)
    {
        long seconds = Math.max(1, Math.round(timeoutMs / 1000.0));
        // At the ceiling there is no larger value to suggest - advising one would be an
        // instruction the tool itself would reject.
        String lever = seconds >= MAX_RENAME_TIMEOUT_SECONDS
            ? "This is already the largest accepted '" + KEY_TIMEOUT + "', so mere slowness is an " //$NON-NLS-1$ //$NON-NLS-2$
                + "unlikely explanation - look for a stuck build or an EDT operation holding the " //$NON-NLS-1$
                + "workspace." //$NON-NLS-1$
            : "If this configuration legitimately needs longer, pass a larger '" + KEY_TIMEOUT //$NON-NLS-1$
                + "' (seconds, up to " + MAX_RENAME_TIMEOUT_SECONDS + ") or raise the default in " //$NON-NLS-1$ //$NON-NLS-2$
                + "Preferences > MCP Server > Tools > " + NAME + "."; //$NON-NLS-1$ //$NON-NLS-2$

        String message = "Renaming '" + objectFqn + "' to '" + newName + "' did not finish " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "within " + seconds + (seconds == 1 ? " second" : " seconds") + ". " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + stateAdvice(confirm, phase, inspectorFor(objectFqn)) + " " + lever; //$NON-NLS-1$
        return inFlightMutationError(message, confirm, phase);
    }

    /**
     * A timed-out/interrupted rename is still running. APPLIED proves a mutation boundary; every
     * earlier confirmed phase is unknown because the Job may advance immediately after we sample
     * it. A preview can never apply and remains an ordinary error.
     */
    private static String inFlightMutationError(String message, boolean confirm,
        RenameProgress.Phase phase)
    {
        if (!confirm)
        {
            return ToolResult.error(message).toJson();
        }
        return (phase == RenameProgress.Phase.APPLIED
            ? ToolResult.errorAfterMutation(message)
            : ToolResult.errorWithUnknownMutationOutcome(message)).toJson();
    }

    /** The Job ended: its final phase can distinguish pre-apply, applying and applied failures. */
    private static String completedMutationError(String message, boolean confirm,
        RenameProgress.Phase phase)
    {
        return markCompletedMutationError(ToolResult.error(message).toJson(), confirm, phase);
    }

    private static String markCompletedMutationError(String answer, boolean confirm,
        RenameProgress.Phase phase)
    {
        if (!confirm)
        {
            return answer;
        }
        if (phase == RenameProgress.Phase.APPLIED)
        {
            return ToolResult.markErrorAfterMutation(answer);
        }
        return phase == RenameProgress.Phase.APPLYING
            ? ToolResult.markErrorWithUnknownMutationOutcome(answer) : answer;
    }

    /**
     * How the caller should check, after a timeout, whether the old or the new name is the one that
     * now exists - phrased for what the FQN actually addresses.
     * <p>
     * It used to say {@code get_metadata_objects} for everything, and that is wrong for most of
     * this tool's targets: that tool enumerates top-level metadata COLLECTIONS, and it has
     * collectors for sixteen of them. A managed-form element is in none (it lives on the form's
     * content model), a MEMBER is not a collection entry either, and even a top object of an
     * unlisted type is invisible to it. At the one moment the caller most needs a straight answer -
     * after a cascade that may have half-applied - being sent to a listing that cannot contain the
     * target either way is what turns into a repeat of a destructive call.
     * <p>
     * {@code get_metadata_details} is the one that answers for every target, which is why both
     * branches name it; only WHERE to point it differs. The FORM/mdclass question is put to
     * {@link FormElementWriter#parse}, the same parser the service dispatches on, so the advice and
     * the branch that will actually run cannot disagree about what counts as a form address
     * (issue #381).
     *
     * @param objectFqn the rename target as the caller wrote it
     * @return the inspector phrase to embed in the advice
     */
    private static String inspectorFor(String objectFqn)
    {
        return FormElementWriter.parse(MetadataTypeUtils.normalizeFqn(objectFqn)) != null
            ? "get_metadata_details on its form" //$NON-NLS-1$
            : "get_metadata_details on it (on its owner for a member)"; //$NON-NLS-1$
    }

    /**
     * Says what the configuration was left in, per the stage the rename had reached, and what to do
     * about it.
     *
     * <p>Every branch is worded to stay true if the work advanced a stage the instant after the
     * phase was read: cancellation does not stop a rename already inside EDT's refactoring, so the
     * only honest claim is about what has ALREADY been touched, never about what will not be.
     *
     * @param confirm whether this call was allowed to apply anything at all
     * @param phase the last phase the rename reported entering
     * @param inspector the tool that can actually show this target - see {@link #inspectorFor}
     * @return the state sentence, ending in a full stop
     */
    private static String stateAdvice(boolean confirm, RenameProgress.Phase phase, String inspector)
    {
        if (!confirm)
        {
            // A preview never reaches the apply path, so NO phase it can be in is able to rewrite
            // the model - and warning that it "may still apply" would be plainly false.
            return "This was a PREVIEW (confirm was not set): nothing was renamed and this call " //$NON-NLS-1$
                + "cannot rename anything. EDT did not finish computing the change points in " //$NON-NLS-1$
                + "time and keeps building them on its UI thread, so a new preview of this rename, " //$NON-NLS-1$
                + "with any timeout, is refused until EDT finishes. Then retry, or raise the bound."; //$NON-NLS-1$
        }
        switch (phase)
        {
        case QUEUED:
            // A job that never started is reported as TIMED_OUT_BEFORE_START and never reaches
            // here, so this phase means the work IS running and waiting on the UI thread.
            return "The rename was still waiting for EDT's UI thread - something else is holding " //$NON-NLS-1$
                + "it - so nothing was renamed yet. It is not cancelled and may still apply once " //$NON-NLS-1$
                + "that thread frees up: check the target's name with " + inspector //$NON-NLS-1$
                + " before retrying."; //$NON-NLS-1$
        case AWAITING_CONSENT:
            return "The rename was at the destructive-operation consent gate, so nothing had been " //$NON-NLS-1$
                + "rewritten - but an answer arriving later still starts it. Set " //$NON-NLS-1$
                + "EDT_MCP_DESTRUCTIVE_CONSENT=allow for unattended use, and check the target's " //$NON-NLS-1$
                + "name with " + inspector + " before retrying."; //$NON-NLS-1$ //$NON-NLS-2$
        case APPLYING:
            return "The rename had passed the consent gate into its apply phase, so the " //$NON-NLS-1$
                + "configuration may be PARTIALLY renamed - do not treat it as unchanged. Inspect " //$NON-NLS-1$
                + "it with " + inspector + " / get_project_errors, and use clean_project to " //$NON-NLS-1$ //$NON-NLS-2$
                + "reload the model from disk (or revert in version control) before renaming again."; //$NON-NLS-1$
        case APPLIED:
            return "The apply phase had finished, so the rename is in the model except for any " //$NON-NLS-1$
                + "change point that failed or was skipped - the report that would have listed " //$NON-NLS-1$
                + "those is what was lost. Confirm with " + inspector + " / get_project_errors " //$NON-NLS-1$ //$NON-NLS-2$
                + "rather than repeating the rename."; //$NON-NLS-1$
        case PREPARING:
        default:
            return "EDT had not got past building the refactoring, so the cascade had not started " //$NON-NLS-1$
                + "rewriting the model - but it is not cancelled and may still apply. Check the " //$NON-NLS-1$
                + "target's name with " + inspector + " before retrying."; //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The rename action run under the deadline. Production hands the service to the UI thread;
     * tests substitute a controllable action to exercise the deadline without a live workbench.
     */
    @FunctionalInterface
    interface IRenameAction
    {
        /**
         * Performs the rename.
         *
         * @param progress the sink the work publishes its phase to
         * @return the tool's response payload
         * @throws Exception any failure - captured by {@link BoundedJob}, never propagated out of
         *     the job thread
         */
        String rename(RenameProgress progress) throws Exception; // NOSONAR the work is arbitrary platform code
    }
}
