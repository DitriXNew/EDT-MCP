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
import com.ditrix.edt.mcp.server.tools.rename.MetadataRenameService;
import com.ditrix.edt.mcp.server.tools.rename.RenameProgress;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to rename a metadata object or attribute with full refactoring support.
 *
 * Two-phase workflow:
 * 1. Preview mode (confirm=false, default): Returns list of affected refactoring items and problems.
 * 2. Execute mode (confirm=true): Performs the rename with all cascading code updates.
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
        return "Rename a metadata object or attribute, cascading the change across all references in " + //$NON-NLS-1$
               "BSL code, forms, and other metadata. Use the two-phase workflow: call without confirm " + //$NON-NLS-1$
               "for an indexed preview of every change point, review it, then call again with " + //$NON-NLS-1$
               "confirm=true to apply. Full parameters and examples: call get_tool_guide('rename_metadata_object')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name.", true) //$NON-NLS-1$
            .stringProperty(KEY_OBJECT_FQN,
                "FQN of the object to rename, e.g. 'Catalog.Products' or " + //$NON-NLS-1$
                "'Document.SalesOrder.Attribute.Amount' (Russian type names also accepted).", true) //$NON-NLS-1$
            .stringProperty(KEY_NEW_NAME,
                "New programmatic Name for the object.", true) //$NON-NLS-1$
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

        // Parse disable indices
        java.util.Set<Integer> disableIndices = new java.util.HashSet<>();
        if (disableIndicesStr != null && !disableIndicesStr.isEmpty())
        {
            for (String part : disableIndicesStr.split(",")) //$NON-NLS-1$
            {
                try
                {
                    disableIndices.add(Integer.parseInt(part.trim()));
                }
                catch (NumberFormatException e)
                {
                    // ignore invalid entries
                }
            }
        }

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME,
            ". Usage: {projectName: 'MyProject', objectFqn: 'Catalog.Products', newName: 'Goods'}"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }
        err = JsonUtils.requireArgument(params, KEY_OBJECT_FQN,
            ". Examples: 'Catalog.Products', 'Document.SalesOrder.Attribute.Amount', " //$NON-NLS-1$
            + "'Catalog.Products.TabularSection.Prices'"); //$NON-NLS-1$
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

        final java.util.Set<Integer> finalDisableIndices = disableIndices;
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
                        finalDisableIndices, maxResults, progress));
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
        case INTERRUPTED:
            return ToolResult.error("The rename of '" + objectFqn + "' was interrupted while " //$NON-NLS-1$ //$NON-NLS-2$
                + "waiting for it. " + stateAdvice(confirm, progress.getPhase())).toJson(); //$NON-NLS-1$
        case NOT_RUN:
            return ToolResult.error("The rename of '" + objectFqn + "' was cancelled before it " //$NON-NLS-1$ //$NON-NLS-2$
                + "started, so nothing was renamed. Retry; if it keeps happening, EDT is shutting " //$NON-NLS-1$
                + "down or another operation is cancelling background jobs.").toJson(); //$NON-NLS-1$
        default:
            break;
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
            + stateAdvice(confirm, phase) + " " + lever).toJson(); //$NON-NLS-1$
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
     * @return the state sentence, ending in a full stop
     */
    private static String stateAdvice(boolean confirm, RenameProgress.Phase phase)
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
            return "The rename never reached EDT's UI thread - the background job had not started, " //$NON-NLS-1$
                + "or something else is holding that thread - so nothing was renamed. It is not " //$NON-NLS-1$
                + "cancelled and may still apply: check the object's name with " //$NON-NLS-1$
                + "get_metadata_objects before retrying."; //$NON-NLS-1$
        case AWAITING_CONSENT:
            return "The rename was at the destructive-operation consent gate, so nothing had been " //$NON-NLS-1$
                + "rewritten - but an answer arriving later still starts it. Set " //$NON-NLS-1$
                + "EDT_MCP_DESTRUCTIVE_CONSENT=allow for unattended use, and check the object's " //$NON-NLS-1$
                + "name with get_metadata_objects before retrying."; //$NON-NLS-1$
        case APPLYING:
            return "The rename had passed the consent gate into its apply phase, so the " //$NON-NLS-1$
                + "configuration may be PARTIALLY renamed - do not treat it as unchanged. Inspect " //$NON-NLS-1$
                + "it with get_metadata_objects / get_project_errors, and use clean_project to " //$NON-NLS-1$
                + "reload the model from disk (or revert in version control) before renaming again."; //$NON-NLS-1$
        case APPLIED:
            return "The apply phase had finished, so the rename is in the model except for any " //$NON-NLS-1$
                + "change point that failed or was skipped - the report that would have listed " //$NON-NLS-1$
                + "those is what was lost. Confirm with get_metadata_objects / get_project_errors " //$NON-NLS-1$
                + "rather than repeating the rename."; //$NON-NLS-1$
        case PREPARING:
        default:
            return "EDT had not got past building the refactoring, so the cascade had not started " //$NON-NLS-1$
                + "rewriting the model - but it is not cancelled and may still apply. Check the " //$NON-NLS-1$
                + "object's name with get_metadata_objects before retrying."; //$NON-NLS-1$
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
