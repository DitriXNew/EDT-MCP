/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.base;

import org.eclipse.emf.ecore.EObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.utils.ReadToolExecution;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.MetadataScope;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.VendorSupportGuard;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared host dispatch, project resolution and post-write export reporting. */
public abstract class AbstractMetadataWriteTool implements IMcpTool
{
    protected static <T> T readPreparation(IProject project, java.util.function.Supplier<T> work)
    {
        return ReadToolExecution.readOnHost(project, "Prepare metadata write", work);
    }

    /** Resolve a fresh root inside the caller's preparation read; retain the windowed context. */
    protected static MetadataScope preparationScope(ProjectContext context)
    {
        return HostKind.current() == HostKind.HEADLESS
            ? com.ditrix.edt.mcp.server.utils.ProjectContext.of(context.project.getName()).resolveMetadataRootInTransaction().scope()
            : context.scope;
    }

    protected static MetadataScope transactionScope(ProjectContext context,
        com._1c.g5.v8.bm.core.IBmTransaction transaction)
    {
        if (HostKind.current() != HostKind.HEADLESS) return context.scope;
        Configuration config = context.scope.isExternalObjects()
            ? com.ditrix.edt.mcp.server.utils.ProjectContext.of(context.project.getName())
                .resolveMetadataRootInTransaction().configuration()
            : (Configuration)transaction.getTopObjectByFqn("Configuration");
        return MetadataScope.of(context.project, config);
    }

    /** Finish preparation in a read before invoking the ID-based write continuation. */
    protected static String prepareWrite(IProject project,
        java.util.function.Supplier<java.util.function.Supplier<String>> preparation)
    {
        return readPreparation(project, preparation).get();
    }

    private static void logExecutionFailure(String context, Throwable failure)
    {
        if (HostKind.current() == HostKind.HEADLESS) Refusals.log(context, failure);
        else Activator.logError(context, failure);
    }

    /**
     * Whether this tool's result depends on VALIDATION having finished, not just on the model.
     * <p>
     * The default is {@code false}: a create or a modify needs the metadata model, and MD is
     * AFTER_SYNC, so the model gate proves what they use. Issue #495 is precisely about not making
     * those wait hours for the validation checks.
     *
     * @return {@code true} to gate on the strict project state instead of the model state
     */
    protected boolean requiresFullDerivedData()
    {
        return false;
    }

    /**
     * Optional caller-side bound for this tool's UI-thread work.
     * <p>
     * A value greater than zero runs the existing {@link Display#syncExec(Runnable)} hand-off in a
     * {@link BoundedJob}. The bound limits only how long the MCP request waits: SWT work that has
     * already entered the UI thread cannot be preempted and may finish after the request returns.
     * Consequently, an opting-in tool must also override
     * {@link #uiThreadBoundError(Map, long, BoundedJob.Outcome)} when it can give more precise state
     * and recovery advice than the conservative base message.
     * <p>
     * The default is zero (unbounded) deliberately: every existing metadata writer used a direct
     * {@code syncExec} before this seam existed, and enabling a deadline without tool-specific
     * timeout semantics could both change its behaviour and misreport a mutation that EDT continues
     * applying. Returning zero therefore preserves the original hand-off and export-wait ordering.
     *
     * @param params the raw tool arguments
     * @return the caller-side bound in milliseconds, or zero to keep the direct unbounded hand-off
     */
    protected long uiThreadBoundMs(Map<String, String> params)
    {
        return 0L;
    }

    /**
     * Whether an unfinished bounded UI-thread hand-off may have mutated the model.
     * <p>
     * The default fails closed for work that started and cannot be preempted: a timeout or an
     * interrupted wait is uncertain even when this request's {@link WriteScope} has not yet observed
     * a commit. It returns {@code false} only when the bounded job proves the work never ran. This
     * deliberately favours uncertainty because under-reporting can hide a mutation from a structured
     * caller, whereas over-reporting costs only a redundant re-read.
     *
     * @param params the raw tool arguments
     * @param outcome how the bounded job stopped waiting
     * @return {@code true} when the returned error must conservatively report a possible mutation
     */
    protected boolean uiThreadBoundOutcomeMayHaveMutated(Map<String, String> params,
        BoundedJob.Outcome outcome)
    {
        switch (outcome)
        {
        case TIMED_OUT_BEFORE_START:
        case NOT_RUN:
        case COMPLETED:
            return false;
        case TIMED_OUT:
        case INTERRUPTED:
        default:
            return true;
        }
    }

    /**
     * Translates a bounded UI hand-off that did not complete into an error result.
     * <p>
     * The final executor marks this result according to
     * {@link #uiThreadBoundOutcomeMayHaveMutated(Map, BoundedJob.Outcome)}, preserving a recorded
     * commit as the strongest known state. A queued job that our deadline kept from starting is
     * reported separately because no UI work can later appear in that case.
     *
     * @param params the raw tool arguments
     * @param timeoutMs the configured caller-side bound
     * @param outcome how the bounded job stopped waiting
     * @return a {@link ToolResult} error JSON
     */
    protected String uiThreadBoundError(Map<String, String> params, long timeoutMs,
        BoundedJob.Outcome outcome)
    {
        long seconds = Math.max(1L, Math.round(timeoutMs / 1000.0));
        switch (outcome)
        {
        case TIMED_OUT_BEFORE_START:
            return ToolResult.error("The UI-thread work for '" + getName() + "' did not start within " //$NON-NLS-1$ //$NON-NLS-2$
                + seconds + " seconds; cancelling the queued job kept it from starting, so no " //$NON-NLS-1$
                + "cleanup is needed. Retry when EDT's job scheduler is less busy.").toJson(); //$NON-NLS-1$
        case NOT_RUN:
            return ToolResult.error("The UI-thread work for '" + getName() + "' was cancelled before " //$NON-NLS-1$ //$NON-NLS-2$
                + "it started. Retry; if it keeps happening, EDT is shutting down or another " //$NON-NLS-1$
                + "operation is cancelling background jobs.").toJson(); //$NON-NLS-1$
        case INTERRUPTED:
            return ToolResult.error("Waiting for the UI-thread work for '" + getName() //$NON-NLS-1$
                + "' was interrupted. The wait ended, but UI-thread work cannot be preempted and " //$NON-NLS-1$
                + "may still finish; inspect the tool's target before retrying.").toJson(); //$NON-NLS-1$
        case TIMED_OUT:
            return ToolResult.error("The UI-thread work for '" + getName() + "' did not finish within " //$NON-NLS-1$ //$NON-NLS-2$
                + seconds + " seconds. The wait ended, but UI-thread work cannot be preempted and " //$NON-NLS-1$
                + "may still finish; inspect the tool's target before retrying.").toJson(); //$NON-NLS-1$
        case COMPLETED:
        default:
            return ToolResult.error("The UI-thread work for '" + getName() + "' ended in an " //$NON-NLS-1$ //$NON-NLS-2$
                + "unrecognised bounded state (" + outcome + "). Inspect the tool's target before " //$NON-NLS-1$ //$NON-NLS-2$
                + "retrying.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Applies the structural mutation contract to an unfinished bounded hand-off.
     * <p>
     * Package-visible and independent of SWT so headless tests can drive the return-path decision.
     * The scope stamp runs first: {@link ToolResult#markErrorWithUnknownMutationOutcome(String)}
     * preserves an existing {@code mutationCommitted:true}, so a recorded write outranks the
     * conservative uncertainty required for in-flight work. When the work provably never ran, the
     * original error is returned without either marker.
     *
     * @param scope the request's write scope
     * @param error the bounded-outcome error JSON
     * @param outcomeMayHaveMutated whether the unfinished work may have mutated the model
     * @return the structurally marked error JSON
     */
    static String markUiThreadBoundOutcomeError(WriteScope scope, String error,
        boolean outcomeMayHaveMutated)
    {
        if (!outcomeMayHaveMutated)
        {
            return error;
        }
        return ToolResult.markErrorWithUnknownMutationOutcome(
            scope.markErrorAfterRecordedWrite(error));
    }

    /**
     * How long to wait for a write's {@code .mdo} export to reach disk before refusing.
     * <p>
     * The same value {@code rename_metadata_object} already uses to let the pipeline settle, and
     * the headroom is measured rather than guessed: on a healthy workspace the bytes land 0.02s
     * (create) to 0.15s (delete) after the tool returns, so this is ~400x the observed worst case,
     * and it is 6x the 10s the e2e suite polls for - a window CI has been seen to exceed.
     */
    private static final long EXPORT_DEADLINE_MS = WriteExportBarrier.DEADLINE_MS;

    /** The result member every tool sets to say whether it succeeded. */
    private static final String KEY_SUCCESS = "success"; //$NON-NLS-1$


    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public final String execute(Map<String, String> params)
    {
        String validationError = validateRequest(params);
        if (validationError != null)
        {
            return validationError;
        }
        String preUiError = beforeUiThreadOrError(params);
        if (preUiError != null)
        {
            return ToolResult.error(preUiError).toJson();
        }

        // Refuse to mutate the model while the project's derived data is still building:
        // a delete cascade would resolve an incomplete reference set (silently missing
        // affected references), and a create/add would see a stale duplicate/parent
        // lookup. Only the transient BUILDING state is refused here; a missing/closed
        // project falls through to resolveProjectAndConfig's value-naming error. Checked
        // on the calling thread before marshalling onto the UI thread.
        String building = requiresFullDerivedData()
            ? ProjectStateChecker.buildingErrorOrNull(params.get("projectName")) //$NON-NLS-1$
            : ProjectStateChecker.modelBuildingErrorOrNull(params.get("projectName")); //$NON-NLS-1$
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        AtomicReference<String> resultRef = new AtomicReference<>();
        WriteScope scope = new WriteScope();
        var display = ReadToolExecution.workbenchExecutor();
        try
        {
            Runnable uiThreadWork = () -> WriteScope.runWithScope(scope, () -> {
                // Bound around the tool's own work so that submitting an export IS declaring one:
                // the single place this plugin hands save tasks to the platform records into
                // whatever scope is bound.
                try
                {
                    resultRef.set(executeOnUiThread(params));
                }
                catch (Exception e)
                {
                    logExecutionFailure("Error in " + getName(), e); //$NON-NLS-1$
                    resultRef.set(ToolResult.error(e.getMessage()).toJson());
                }
            });

            long boundMs = uiThreadBoundMs(params);
            if (boundMs > 0L)
            {
                BoundedJob.Result bounded = BoundedJob.run(getName() + ": UI-thread work", boundMs, //$NON-NLS-1$
                    monitor -> display.accept(uiThreadWork));
                if (bounded.getOutcome() != BoundedJob.Outcome.COMPLETED)
                {
                    // The caller is bounded, not SWT. The UI runnable may still record a commit or
                    // finish later, so never proceed to the export barrier and never claim rollback.
                    boolean outcomeMayHaveMutated =
                        uiThreadBoundOutcomeMayHaveMutated(params, bounded.getOutcome());
                    return markUiThreadBoundOutcomeError(scope,
                        uiThreadBoundError(params, boundMs, bounded.getOutcome()),
                        outcomeMayHaveMutated);
                }
                if (bounded.getFailure() != null)
                {
                    logExecutionFailure("Error finishing " + getName(), bounded.getFailure()); //$NON-NLS-1$
                    return scope.markErrorAfterRecordedWrite(
                        ToolResult.error(bounded.getFailure().getMessage()).toJson());
                }
            }
            else
            {
                display.accept(uiThreadWork);
            }

            // Deliberately AFTER syncExec returns, i.e. off the UI thread: the export runs on EDT's
            // derived-data pipeline, and waiting for it while holding the UI thread is how a
            // headless MCP call turns into a hung workbench.
            return awaitDiskExport(params, resultRef.get(), scope);
        }
        catch (RuntimeException e)
        {
            // Covers failures in the UI hand-off itself and in every post-commit export/refresh
            // step. The scope, not this catch's position or wording, decides whether the mutation
            // marker belongs on the answer.
            logExecutionFailure("Error finishing " + getName(), e); //$NON-NLS-1$
            return scope.markErrorAfterRecordedWrite(ToolResult.error(e.getMessage()).toJson());
        }
    }

    /**
     * Turns a model change whose export has not reached disk into a refusal.
     * <p>
     * A write tool that answers "done" while its {@code .mdo} export is still queued makes the
     * caller's next step unsafe: the two files a top-object change touches (the object's own
     * {@code .mdo} and the owning {@code Configuration.mdo}) are separate export tasks with no
     * ordering between them, so the working tree passes through a state where the configuration
     * references an object whose file is already gone. An agent that commits there commits a
     * broken configuration.
     * <p>
     * The refusal is not a false alarm: it fires only once the wait has actually been made and has
     * failed to establish that the queue drained, so the disk is not known to be what the response
     * would have claimed. It says outright that nothing was undone, because the alternative -
     * staying silent about work that already happened - is the failure this whole path exists to
     * prevent.
     *
     * @param params the tool parameters
     * @param result the JSON the tool produced
     * @param scope what the call itself said about where it wrote
     * @return {@code result} unchanged, or an actionable error when the export is still pending
     */
    // Protected, not package-visible: a subclass's own test drives the barrier through this entry
    // to pin that its post-barrier work is ordered AFTER the drain, and that ordering is not
    // observable from anywhere else.
    protected String awaitDiskExport(Map<String, String> params, String result, WriteScope scope)
    {
        JsonObject success = successObject(result);
        boolean committedError = success == null && ToolResult.isErrorPayload(result) && scope.hasRecordedWrite();
        if (success == null && !committedError)
        {
            return scope.markErrorAfterRecordedWrite(result);
        }
        WriteScope.Verdict verdict = scope.verdict(defaultProjectsToAwait(params));
        if (verdict.written().isEmpty() && verdict.cascaded().isEmpty())
        {
            if (committedError) return scope.markErrorAfterRecordedWrite(result);
            // Nothing to WAIT for, but the post-wait step still runs: a tool may have work that
            // must not start until the barrier is behind it, and skipping it here would silently
            // drop that work for exactly the calls that queued nothing. Reported as established:
            // this call put nothing in the queue, so there is nothing about it left unfinished.
            return scope.markErrorAfterRecordedWrite(
                publish(verdict, refreshAfterExportAwait(params, result, true, scope)));
        }
        WriteExportBarrier.Outcome export;
        try
        {
            export = WriteExportBarrier.await(verdict, exportDeadlineMs(), exportEnvironment()::waitForDiskExport);
        }
        catch (RuntimeException e)
        {
            if (!committedError) throw e;
            Activator.logWarning("Could not observe disk export after a failed metadata write: " + e); //$NON-NLS-1$
            export = new WriteExportBarrier.Outcome(false, null);
        }
        if (committedError)
        {
            // Draining must preserve the original failure and never run success-only refresh work.
            String marked = scope.markErrorAfterRecordedWrite(result);
            if (export.drained()) return marked;
            JsonObject error = JsonParser.parseString(marked).getAsJsonObject();
            error.addProperty("exportState", (export.pendingProject() == null //$NON-NLS-1$
                ? BuildUtils.DiskExportState.UNOBSERVABLE : BuildUtils.DiskExportState.PENDING).name());
            return GsonProvider.toJson(error);
        }
        if (export.pendingProject() != null)
        {
            return scope.markErrorAfterRecordedWrite(WriteExportBarrier.notConfirmed(export.pendingProject()));
        }
        boolean drainEstablished = export.drained();
        return scope.markErrorAfterRecordedWrite(attachWrittenObjectMarkers(params, verdict, scope,
            publish(verdict, refreshAfterExportAwait(params, result, drainEstablished, scope))));
    }

    /** The FQN a metadata write addresses; decides whether {@code Configuration} is its target. */
    private static final String KEY_FQN = "fqn"; //$NON-NLS-1$

    /**
     * Whether a successful write reports the EDT markers of the top objects it exported (#643).
     *
     * @return {@code true} to add {@code markersIncomplete} / {@code markerCount} / {@code markers}
     */
    protected boolean reportsWrittenObjectMarkers()
    {
        return false;
    }

    /** Allows a tool with multiple request modes to opt into the shared marker report per call. */
    protected boolean reportsWrittenObjectMarkers(Map<String, String> params)
    {
        return reportsWrittenObjectMarkers();
    }

    /** @return the validation budget of the marker report; overridden only by tests */
    protected long markerDeadlineMs()
    {
        return WrittenObjectMarkers.VALIDATION_BUDGET_MS;
    }

    /** @return the platform seam of the marker report; overridden only by tests */
    protected WrittenObjectMarkers.Environment markerEnvironment()
    {
        return WrittenObjectMarkers.PLATFORM;
    }

    /**
     * Adds the written objects' markers to a success, after the export barrier and off the UI
     * thread. Only the projects this call wrote (never cascade participants), only the top objects
     * it exported; a call that exported nothing gets none of the fields. Never turns a success into
     * an error.
     */
    private String attachWrittenObjectMarkers(Map<String, String> params, WriteScope.Verdict verdict,
        WriteScope scope, String result)
    {
        if (!reportsWrittenObjectMarkers(params))
        {
            return result;
        }
        JsonObject success = successObject(result);
        if (success == null)
        {
            return result;
        }
        Map<String, List<String>> slice = new LinkedHashMap<>();
        for (String projectName : verdict.written())
        {
            List<String> fqns = WrittenObjectMarkers.slice(scope.exportedTopObjects(projectName),
                params.get(KEY_FQN));
            if (!fqns.isEmpty())
            {
                slice.put(projectName, fqns);
            }
        }
        if (slice.isEmpty())
        {
            return result;
        }
        long budgetMs = markerDeadlineMs();
        WrittenObjectMarkers.Report report;
        try
        {
            report = WrittenObjectMarkers.collect(markerEnvironment(), slice, budgetMs);
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("Could not collect the markers of the written objects: " + e); //$NON-NLS-1$
            List<String> objects = new ArrayList<>();
            slice.values().forEach(objects::addAll);
            report = WrittenObjectMarkers.readFailed(objects, budgetMs);
        }
        WrittenObjectMarkers.attach(success, report);
        return GsonProvider.toJson(success);
    }

    /**
     * @return the shared budget for the whole awaited set; overridden only by tests, so the
     *     "a slice too small to be worth starting" guard can be reached without a 60-second test
     */
    protected long exportDeadlineMs()
    {
        return EXPORT_DEADLINE_MS;
    }

    /**
     * Adds the call's own account of where it wrote to the response it is about to return.
     * <p>
     * Done in ONE place, from the very value the barrier waited on, so what the caller is told and
     * what the tool waited for cannot drift apart. Left out entirely - rather than published as an
     * empty list - when the call did not state its scope: an empty list is a finding ("this call
     * wrote in no project of its own"), and showing it for a call that merely could not tell would
     * be the same over-claim this whole path exists to remove.
     *
     * @param verdict the barrier's reading of the call
     * @param result the JSON about to be returned
     * @return the JSON to return
     */
    private static String publish(WriteScope.Verdict verdict, String result)
    {
        Collection<String> projects = verdict.publishable();
        if (projects == null)
        {
            return result;
        }
        JsonObject success = successObject(result);
        if (success == null)
        {
            // The post-wait hook may have turned the success into a refusal; a refusal has no write
            // scope to report.
            return result;
        }
        JsonArray array = new JsonArray();
        for (String project : projects)
        {
            array.add(project);
        }
        success.add(WriteScope.RESULT_MEMBER, array);
        return GsonProvider.toJson(success);
    }

    /**
     * What to wait for when the call said nothing about where it wrote: the project it was asked
     * about. Kept so a tool that declares nothing behaves exactly as it did before #408 rather than
     * silently starting to wait for everything or for nothing.
     *
     * @param params the tool parameters
     * @return the default wait set
     */
    private static Collection<String> defaultProjectsToAwait(Map<String, String> params)
    {
        String projectName = params.get(McpKeys.PROJECT_NAME);
        return projectName == null || projectName.isEmpty() ? Collections.emptyList()
            : Collections.singletonList(projectName);
    }

    /**
     * Lets a tool restate anything in its result that the export wait has just made out of date.
     * <p>
     * A tool that reports on DISK state samples it inside {@code executeOnUiThread}, which is
     * before the barrier ran - so a field like "these files are still missing" can describe a
     * moment that no longer exists by the time the caller reads it. The default changes nothing;
     * only a tool that reports disk state has anything to restate.
     *
     * @param params the tool parameters
     * @param result the JSON the tool produced
     * @param drainEstablished whether the export was actually observed to finish. {@code false}
     *     means the barrier could not observe the export state at all - NOT that anything failed,
     *     and NOT that the disk is current. Work that only makes sense on exported bytes must
     *     check this rather than assume the wait proved something.
     * @return the result to return, possibly updated
     */
    protected String refreshAfterExportAwait(Map<String, String> params, String result,
        boolean drainEstablished)
    {
        return result;
    }

    protected String refreshAfterExportAwait(Map<String, String> params, String result,
        boolean drainEstablished, WriteScope scope)
    {
        return refreshAfterExportAwait(params, result, drainEstablished);
    }

    /**
     * Reads a string member of a result, for a subclass restating part of its own answer.
     *
     * @param result the tool's own result
     * @param member the member to read
     * @return the value, or {@code null} when absent or not a primitive
     */
    protected static String resultString(JsonObject result, String member)
    {
        JsonElement value = result.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /**
     * Parses a tool result and returns it only when it is a SUCCESS.
     * <p>
     * Success is decided by the explicit {@code success} boolean rather than by "the payload
     * parsed", because an error is a well-formed JSON object too.
     *
     * @param result the JSON the tool produced
     * @return the parsed object, or {@code null} when it is not a successful JSON object
     */
    private static JsonObject successObject(String result)
    {
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonElement parsed = JsonParser.parseString(result);
            if (!parsed.isJsonObject())
            {
                return null;
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement success = object.get(KEY_SUCCESS);
            boolean ok = success != null && success.isJsonPrimitive()
                && success.getAsJsonPrimitive().isBoolean() && success.getAsBoolean();
            return ok ? object : null;
        }
        catch (RuntimeException e)
        {
            // A payload we cannot read is not evidence of a disk problem, and the work already
            // happened - degrade to "do not gate", never to a refusal built on a guess.
            Activator.logError("Could not read a metadata write result while checking its export", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Seam so the export barrier can be exercised without a live derived-data pipeline. It takes
     * the project NAME rather than an {@link IProject} so the workspace lookup lives behind it too
     * - otherwise the decision could not be tested without a running workspace, which is exactly
     * the part worth pinning.
     */
    @FunctionalInterface
    protected interface IExportEnvironment
    {
        /**
         * @param projectName the name of the project whose export queue to drain
         * @param timeoutMs the deadline in milliseconds
         * @return how the wait ended
         */
        BuildUtils.DiskExportState waitForDiskExport(String projectName, long timeoutMs);
    }

    /**
     * @return the environment the export barrier runs against; overridden only by tests. Protected
     *     rather than package-visible so a subclass's OWN test can observe the barrier - pinning
     *     that a tool's post-barrier work really happens after the drain needs both ends visible
     *     from one place.
     */
    protected IExportEnvironment exportEnvironment()
    {
        return WriteExportBarrier.PLATFORM::waitForDiskExport;
    }

    /**
     * Optional bounded pre-flight that must run before the SWT UI-thread handoff. Most metadata
     * writes need no additional work here; cascade tools may wait on EDT services that themselves
     * need the UI thread while settling.
     *
     * @param params the tool parameters
     * @return an error message, or {@code null} to continue
     */
    protected String beforeUiThreadOrError(Map<String, String> params)
    {
        return null;
    }

    /** Returns a ready error JSON for input refused before project access, or null. */
    protected String validateRequest(Map<String, String> params)
    {
        return null;
    }

    /**
     * Runs on SWT in windowed EDT and on the caller in headless EDT.
     * Model reads and writes retain their own transaction boundaries.
     *
     * @param params the tool parameters
     * @return the JSON result string
     * @throws Exception on unexpected failure
     */
    protected abstract String executeOnUiThread(Map<String, String> params) throws Exception; // NOSONAR propagates checked exceptions across the reflective boundary by design

    /**
     * Holds the resolved project and configuration, or a ready-to-return JSON
     * error string when resolution failed.
     */
    protected static final class ProjectContext
    {
        /** Resolved project; non-null only when {@link #error} is null. */
        public IProject project;
        /**
         * Resolved configuration; non-null when {@link #error} is null - EXCEPT for an
         * external-objects project linked to no base configuration, which resolves successfully
         * with a null configuration and a non-null {@link #scope} (issue #309).
         */
        public Configuration config;
        /**
         * The ROOT a metadata FQN resolves against for this project: the configuration, or an
         * external-objects project's own root objects. Non-null whenever {@link #error} is null;
         * use it - not {@link #config} - to resolve an FQN.
         */
        public MetadataScope scope;
        /** Non-null when resolution failed: a JSON error to return verbatim. */
        public String error;

        public boolean hasError()
        {
            return error != null;
        }
    }

    /**
     * Resolves the EDT project and its configuration, applying the same
     * validation and error messages used across the metadata write tools.
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndConfig(String projectName)
    {
        return resolveProjectRoot(projectName, false);
    }

    /**
     * Resolves the project and the ROOT a metadata FQN resolves against for it - the configuration,
     * or an external-objects project's own root objects (issue #309).
     *
     * <p>The difference from {@link #resolveProjectAndConfig(String)} is one case: an
     * external-objects project linked to NO base configuration resolves successfully here, with a
     * null {@code config} and a usable {@code scope}. Only a tool that resolves everything through
     * the scope may use this entry; one that dereferences {@code config} must keep the other, which
     * still refuses that case rather than handing it a null.</p>
     *
     * @param projectName the project name from the tool parameters
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndScope(String projectName)
    {
        return resolveProjectRoot(projectName, true);
    }

    /**
     * {@link #resolveProjectAndScope(String)} plus the TYPE/ROOT check: refuses when {@code fqn}
     * names a type this project kind cannot hold at all - a configuration type addressed at an
     * external-objects project, or a standalone external type addressed at a configuration.
     *
     * <p>Bound to context resolution ON PURPOSE. Every write tool has specialized dispatches
     * that resolve their own context and read the {@code Configuration} directly, and each one
     * that lacked this check answered about the WRONG project: a real owner found in the LINKED
     * base configuration and then "Owner object not found in transaction" when its BM id was
     * used in this project's model, or advice to "create it first" for something this project
     * can never hold. Making the check part of GETTING the context is what stops the next such
     * branch from having to remember it (issue #309).</p>
     *
     * @param projectName the project name from the tool parameters
     * @param fqn the FQN this call addresses; {@code null}/empty checks nothing extra
     * @return a {@link ProjectContext}; check {@link ProjectContext#error} first
     */
    protected ProjectContext resolveProjectAndScope(String projectName, String fqn)
    {
        ProjectContext ctx = resolveProjectAndScope(projectName);
        if (ctx.hasError() || fqn == null || fqn.isEmpty())
        {
            return ctx;
        }
        String hint = readPreparation(ctx.project, () -> preparationScope(ctx).addressingHint(fqn));
        if (!hint.isEmpty())
        {
            ctx.error = ToolResult.error("'" + fqn + "' cannot be addressed in project " //$NON-NLS-1$ //$NON-NLS-2$
                + "'" + projectName + "'." + hint).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            return ctx;
        }
        // Vendor support, checked here for the same reason as the hint: every FQN branch of a
        // writer gets its context from this call, before it writes anything (issue #642).
        ctx.error = vendorSupportRefusal(ctx.scope, fqn);
        return ctx;
    }

    /**
     * What this tool's FQN-addressed call does to its target, for the vendor-support check.
     * The default is {@link VendorSupportGuard.Intent#NONE}: not checked.
     *
     * @return the write intent
     */
    protected VendorSupportGuard.Intent writeIntent()
    {
        return VendorSupportGuard.Intent.NONE;
    }

    /**
     * The vendor-support refusal for this tool's write to {@code fqn}, as ready JSON.
     * Structural tokens use canonical English; programmatic Names keep their spelling and case.
     *
     * @param scope the project's resolution root
     * @param fqn the addressed FQN
     * @return the error JSON, or {@code null} when the write may proceed
     */
    final String vendorSupportRefusal(MetadataScope scope, String fqn)
    {
        String refusal = ReadToolExecution.readOnHost(scope.project(), getName() + ": support preflight",
            () -> VendorSupportGuard.refusalForFqn(HostKind.current() == HostKind.HEADLESS && scope.project() != null
                ? com.ditrix.edt.mcp.server.utils.ProjectContext.of(scope.project().getName())
                    .resolveMetadataRootInTransaction().scope() : scope,
                MetadataTypeUtils.toCanonicalEnglishFqn(fqn), writeIntent()));
        return refusal == null ? null : ToolResult.error(refusal).toJson();
    }

    /** The same rendering for an object already loaded inside the caller's transaction. */
    protected final String vendorSupportRefusal(EObject object, MetadataScope scope, String label,
        String callerAddress, String verb)
    {
        String refusal = VendorSupportGuard.refusalFor(object, scope,
            MetadataTypeUtils.toCanonicalEnglishFqn(label),
            MetadataTypeUtils.toCanonicalEnglishFqn(callerAddress), verb);
        return refusal == null ? null : ToolResult.error(refusal).toJson();
    }

    private ProjectContext resolveProjectRoot(String projectName, boolean allowNoConfiguration)
    {
        return resolveProjectRoot(projectName,
            ResourcesPlugin.getWorkspace().getRoot().getProject(projectName), allowNoConfiguration);
    }

    /** The same write gate with an already-resolved project, for lifecycle snapshot tests. */
    ProjectContext resolveProjectRoot(String projectName, IProject project, boolean allowNoConfiguration)
    {
        return ReadToolExecution.readOnHost(project, getName() + ": resolve root",
            () -> resolveProjectRootInBoundary(projectName, project, allowNoConfiguration));
    }

    private ProjectContext resolveProjectRootInBoundary(String projectName, IProject project,
        boolean allowNoConfiguration)
    {
        ProjectContext ctx = new ProjectContext();

        if (project == null || !project.exists())
        {
            // FQN: this class has its own nested ProjectContext, so the shared resolver's
            // standard not-found message is referenced fully-qualified.
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.notFoundMessage(projectName)).toJson();
            return ctx;
        }

        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            ctx.error = ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
            return ctx;
        }

        Configuration config = configProvider.getConfiguration(project);
        MetadataScope scope = MetadataScope.of(project, config);
        // The project HAS no readable root (EDT never started it), so every FQN below would come
        // back "not found" about a project that is simply not up. Refused on BOTH paths - see
        // ProjectContext.unreadableExternalRootMessage.
        if (scope.externalRootUnavailable())
        {
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.unreadableExternalRootMessage(
                    projectName)).toJson();
            return ctx;
        }
        // An EXTERNAL-OBJECTS project has no configuration of its own - its roots are its external
        // data processors / reports, and the provider answers with the linked BASE configuration
        // (null when there is none). A scope-driven caller can work without one; a caller that
        // dereferences the configuration cannot, and is refused with the reason. Issue #309.
        if (config == null && !(allowNoConfiguration && scope.isExternalObjects()))
        {
            ctx.error = ToolResult.error(
                com.ditrix.edt.mcp.server.utils.ProjectContext.noConfigurationMessage(
                    projectName, project, scope))
                    .toJson();
            return ctx;
        }

        ctx.project = project;
        ctx.config = config;
        ctx.scope = scope;
        return ctx;
    }

    /**
     * Returns the most specific failure message from an exception thrown by a BM
     * write task: the cause message when present, otherwise the exception's own.
     *
     * @param e the caught exception
     * @return the resolved message
     */
    protected static String unwrapCauseMessage(Exception e)
    {
        String msg = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null)
        {
            msg = e.getCause().getMessage();
        }
        return msg;
    }
}
