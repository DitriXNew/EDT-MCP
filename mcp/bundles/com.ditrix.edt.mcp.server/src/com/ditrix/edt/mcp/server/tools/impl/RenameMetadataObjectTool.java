/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
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
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.FormElementWriter;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

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

    private final MetadataRenameService service = new MetadataRenameService();

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Rename a metadata object, one of its members, or a managed-form element " + //$NON-NLS-1$
               "(attribute / command / field / button / group / decoration / table / attribute " + //$NON-NLS-1$
               "column), cascading the change across the references EDT resolves for it in BSL " + //$NON-NLS-1$
               "code, forms, and other metadata. Use the two-phase workflow: call without confirm " + //$NON-NLS-1$
               "for an indexed preview of every change point, review it, then call again with " + //$NON-NLS-1$
               "confirm=true to apply. Full parameters and examples: call " + //$NON-NLS-1$
               "get_tool_guide('rename_metadata_object')."; //$NON-NLS-1$
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
                "Comma-separated preview '#' indices of OPTIONAL change points to skip, e.g. '2,3,5'.") //$NON-NLS-1$
            .integerProperty("maxResults", //$NON-NLS-1$
                "Max change points shown in the preview (default 20; 0 = no limit).") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT,
                "How long to wait for the cascade itself, in seconds (default " //$NON-NLS-1$
                + DEFAULT_RENAME_TIMEOUT_SECONDS + ", clamped to " + MIN_RENAME_TIMEOUT_SECONDS //$NON-NLS-1$
                + ".." + MAX_RENAME_TIMEOUT_SECONDS + "). On expiry the call fails with a timeout " //$NON-NLS-1$ //$NON-NLS-2$
                + "error naming the stage it reached instead of waiting forever; EDT may still " //$NON-NLS-1$
                + "finish the rename afterwards, so verify the model. Does not cover the " //$NON-NLS-1$
                + "pre-flight index drain (a separate 60s bound).") //$NON-NLS-1$
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
        final int maxResults = Math.max(0, JsonUtils.extractIntArgument(params, "maxResults", 20)); //$NON-NLS-1$

        // Parse disable indices. An entry that is not an index is COUNTED rather than thrown away here:
        // the executed report has to be able to say that the caller asked for something which produced
        // no skip, and a value discarded at the parse no longer exists to report on (#401). The count
        // and not the text - see DisableRequest for why.
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

        // A cascade rename rewrites every reference to the object across BSL, forms and
        // metadata. If the project's derived data (the reference index) is still building,
        // the refactoring resolves an INCOMPLETE set of references: it would rename the
        // object, miss some references, and still report success — leaving dangling old
        // references (silent partial corruption). Refuse only for that transient BUILDING
        // state; a missing/closed project falls through to the value-naming error below.
        // Drain the derived-data pipeline before the cascade rather than merely asking whether it
        // is quiet. NB this narrows the window, it does not close it: EDT builds the refactoring
        // INSIDE the syncExec below (saving dirty editors and running an incremental build as it
        // goes), so fresh work can still be queued between here and perform(). Closing it properly
        // needs an EDT-supported "quiesce then open the batch session" step; doing it ourselves -
        // by draining between construction and perform - would mean releasing the UI thread in the
        // middle of a rename, which drops the serialisation that keeps a concurrent write from
        // making the built cascade stale. See issue #320.
        String building = ProjectStateChecker.settleBeforeCascadeOrError(projectName, SETTLE_TIMEOUT_MS);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        final DisableRequest finalDisableRequest = disableRequest;
        Display display = PlatformUI.getWorkbench().getDisplay();

        // The cascade runs on the UI thread, and nothing in that hand-off had an upper bound: EDT
        // wedged inside it holds the MCP request open until the CLIENT gives up, with no answer and
        // no cleanup (issue #365 - eight aborted e2e runs, each losing ~188 tests to one call).
        // Bound it here, the same shape #354 established for clean_project.
        IRenameAction action = progress -> {
            AtomicReference<String> resultRef = new AtomicReference<>();
            display.syncExec(() -> {
                try
                {
                    resultRef.set(service.rename(projectName, objectFqn, newName, confirm,
                        finalDisableRequest, maxResults, progress));
                }
                catch (Exception e)
                {
                    Activator.logError("Error in rename_metadata_object", e); //$NON-NLS-1$
                    resultRef.set(ToolResult.error(e.getMessage()).toJson());
                }
            });
            return resultRef.get();
        };
        return runRenameBounded(objectFqn, newName, confirm, resolveRenameTimeoutMs(params), action);
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
        RenameProgress progress = new RenameProgress();
        AtomicReference<String> resultRef = new AtomicReference<>();

        BoundedJob.Result result = BoundedJob.run(NAME + ": " + objectFqn, timeoutMs, //$NON-NLS-1$
            monitor -> resultRef.set(action.rename(progress)));

        switch (result.getOutcome())
        {
        case TIMED_OUT:
            return timeoutError(objectFqn, newName, confirm, timeoutMs, progress.getPhase());
        case TIMED_OUT_BEFORE_START:
            return notStartedError(objectFqn, newName, timeoutMs);
        case INTERRUPTED:
            return ToolResult.error("The rename of '" + objectFqn + "' was interrupted while " //$NON-NLS-1$ //$NON-NLS-2$
                + "waiting for it. " + stateAdvice(confirm, progress.getPhase(), //$NON-NLS-1$
                    inspectorFor(objectFqn))).toJson();
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
            return ToolResult.error("The rename of '" + objectFqn + "' ended in an unrecognised " //$NON-NLS-1$ //$NON-NLS-2$
                + "state (" + result.getOutcome() + "), so whether it applied is unknown. Check " //$NON-NLS-1$ //$NON-NLS-2$
                + "the target's name with " + inspectorFor(objectFqn) //$NON-NLS-1$
                + " before retrying.").toJson(); //$NON-NLS-1$
        }

        Throwable failure = result.getFailure();
        if (failure != null)
        {
            // The service catches its own exceptions; reaching here means the hand-off to the UI
            // thread itself failed (a disposed display, a workbench shutting down).
            Activator.logError("Error in rename_metadata_object", failure); //$NON-NLS-1$
            return ToolResult.error(failure.getMessage()).toJson();
        }
        return resultRef.get();
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

        return ToolResult.error("Renaming '" + objectFqn + "' to '" + newName + "' did not finish " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "within " + seconds + (seconds == 1 ? " second" : " seconds") + ". " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + stateAdvice(confirm, phase, inspectorFor(objectFqn)) + " " + lever).toJson(); //$NON-NLS-1$
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
                + "time - retry, or raise the bound."; //$NON-NLS-1$
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
