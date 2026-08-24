/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tools.base.WriteScope;

/**
 * Runs work against the 1C BM (business model) inside an explicit read or write
 * transaction boundary.
 * <p>
 * Every BM-touching tool otherwise repeats the same boilerplate: wrap the work in
 * an inline {@code new AbstractBmTask<T>(name){ execute(tx, pm){...} }} and pass it
 * to either {@link IBmModel#executeReadonlyTask} (reads) or
 * {@link IBmModel#execute} (writes). This helper collapses that to a lambda and,
 * crucially, makes the read/write choice explicit at the call site:
 * <ul>
 * <li>{@link #read} -&gt; {@link IBmModel#executeReadonlyTask} (a read MUST NOT run
 * in a write-capable transaction - that is exactly the class of bug fixed in
 * {@code 25d7851});</li>
 * <li>{@link #write} -&gt; {@link IBmModel#execute} (the only place a mutation is
 * allowed).</li>
 * </ul>
 * Behaviour is identical to the inline form - the same underlying BM call with the
 * same task name and body; only the wrapping is shared.
 * <p>
 * Callers keep resolving the {@link IBmModel} themselves (their null-checks and
 * error messages differ, and some resolve via {@code IDtProject} rather than
 * {@code IProject}); centralising the manager/model acquisition is a separate
 * increment (card {@code introduce-bm-transactions-helper}).
 */
public final class BmTransactions
{
    private BmTransactions()
    {
        // Utility class
    }

    /**
     * A unit of work executed inside a BM transaction. Mirrors the body of an
     * {@link AbstractBmTask}: it receives the active {@link IBmTransaction} and a
     * progress monitor and returns a result (use {@link Void} / {@code return null}
     * for side-effecting work).
     *
     * @param <T> the result type
     */
    @FunctionalInterface
    public interface BmOperation<T>
    {
        /**
         * @param tx the active BM transaction (read-only for {@link #read}, writable
         *            for {@link #write})
         * @param monitor the progress monitor supplied by the BM engine
         * @return the operation result
         */
        T execute(IBmTransaction tx, IProgressMonitor monitor);
    }

    /**
     * Runs {@code operation} inside a <b>read-only</b> BM transaction
     * ({@link IBmModel#executeReadonlyTask}). The model must not be mutated here.
     *
     * @param model the BM model (must be non-null; resolved by the caller)
     * @param taskName a short task name for diagnostics
     * @param operation the read work
     * @param <T> the result type
     * @return the operation result
     */
    public static <T> T read(IBmModel model, String taskName, BmOperation<T> operation)
    {
        return model.executeReadonlyTask(new AbstractBmTask<T>(taskName)
        {
            @Override
            public T execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                return operation.execute(tx, monitor);
            }
        });
    }

    /**
     * Runs {@code operation} inside a <b>writable</b> BM transaction
     * ({@link IBmModel#execute}). This is the only sanctioned place to mutate the
     * model.
     *
     * @param model the BM model (must be non-null; resolved by the caller)
     * @param taskName a short task name for diagnostics
     * @param operation the write work
     * @param <T> the result type
     * @return the operation result
     */
    public static <T> T write(IBmModel model, String taskName, BmOperation<T> operation)
    {
        T result = model.execute(new AbstractBmTask<T>(taskName)
        {
            @Override
            public T execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                return operation.execute(tx, monitor);
            }
        });
        // execute() returned, so the writable BM boundary committed. Record that fact BEFORE any
        // caller-side identity/render/export work can throw. Project identity is intentionally a
        // separate signal, supplied by recordWrite/forceExport for the export barrier.
        WriteScope.recordMutationCommitted();
        return result;
    }

    /**
     * Runs {@code operation} inside a <b>write-capable but auto-rolled-back</b> BM transaction
     * ({@link IBmModel#executeAndRollback}): every model modification the operation makes is
     * <b>discarded</b> when it returns. Use this for a render/computation that must MUTATE the model
     * transiently but must NOT persist anything - e.g. rasterizing a spreadsheet template, where the
     * platform's print pipeline lazily initializes derived features (headers/footers, print settings)
     * as a side effect of painting. This is the same sandbox EDT's form render uses
     * ({@code HippoLayoutService} inside {@code executeAndRollback}); it renders the real model without
     * dirtying it. The operation must not have non-model side effects it expects to keep beyond what it
     * returns (the returned value - e.g. an SWT image - survives; model edits do not).
     *
     * @param model the BM model (must be non-null; resolved by the caller)
     * @param taskName a short task name for diagnostics
     * @param operation the transient work whose model edits are rolled back
     * @param <T> the result type
     * @return the operation result
     */
    public static <T> T executeAndRollback(IBmModel model, String taskName, BmOperation<T> operation)
    {
        return model.executeAndRollback(new AbstractBmTask<T>(taskName)
        {
            @Override
            public T execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                return operation.execute(tx, monitor);
            }
        });
    }

    /**
     * Forces the BM model's pending serialization of a top object to its {@code .mdo}
     * file on disk, AFTER the {@link #write} transaction that mutated it has committed.
     * <p>
     * A bare {@link #write} commit updates the in-memory model and leaves the {@code .mdo}
     * export to the reactor's own post-commit scheduling; calling this makes the export of the
     * named top objects explicit rather than incidental, which is what a tool needs when it is
     * about to report on those specific files.
     * <p>
     * <b>It does not write the file.</b> {@link IBmModelManager#forceExport} builds one save
     * task per FQN and hands them to the platform's ASYNCHRONOUS save; the platform's own
     * synchronous variant differs from it by a wait this path does not perform. The same is
     * true of the rename/delete refactorings, which schedule their save the same way rather
     * than draining it. To establish that nothing is still queued, wait afterwards -
     * {@link BuildUtils#waitForDiskExport} is that wait. Note what it settles: the queue is
     * empty, NOT that the bytes are right. The platform logs a per-file write failure and lets
     * the computation complete, so a failed write drains like a successful one.
     * <p>
     * {@code AbstractMetadataWriteTool} applies it to every tool that EXTENDS it, which is not
     * the same as every metadata writer: {@code rename_metadata_object} and
     * {@code build_external_objects} implement {@code IMcpTool} directly and are therefore still
     * uncovered. A new caller of this method outside that base class has to wait for itself.
     * <p>
     * MUST be called OUTSIDE the {@link #write} boundary (it starts its own task).
     * {@code topObjectFqn} must be a TOP object FQN: for a nested change (e.g. a new
     * attribute) pass the PARENT object's FQN, not the child's.
     *
     * @param project the workspace project owning the object
     * @param topObjectFqn the FQN of the top object whose {@code .mdo} to queue
     * @return {@code true} if the platform accepted a save task for the object; {@code false}
     *         if the services/project/FQN could not be resolved or the submission threw. A
     *         {@code true} says the export was SCHEDULED, not that the file was written - the
     *         write happens later, and a failure inside it is logged by the platform and never
     *         surfaces here
     */
    public static boolean forceExportToDisk(IProject project, String topObjectFqn)
    {
        return forceExportToDisk(project, Collections.singletonList(topObjectFqn));
    }

    /**
     * List overload of {@link #forceExportToDisk(IProject, String)} for a mutation
     * that dirtied MORE THAN ONE top object - e.g. creating an object dirties both the
     * new object AND the {@code Configuration} (its child collection changed), so BOTH
     * must be queued or the {@code Configuration.mdo} reference lags behind (the
     * new object would be orphaned on a restart before the async export drains).
     * <p>
     * Passing them together does NOT make them land together: the platform turns each FQN into
     * its own save task with no ordering between them, which is why a half-exported tree can
     * show an object's own {@code .mdo} already written or deleted while {@code Configuration.mdo}
     * still holds the previous collection.
     *
     * @param project the workspace project owning the objects
     * @param topObjectFqns the FQNs of every top object whose {@code .mdo} to queue
     * @return {@code true} if the platform accepted a save task for at least one object;
     *         {@code false} if the services/project could not be resolved or the submission
     *         threw. See {@link #forceExportToDisk(IProject, String)} for what {@code true}
     *         does and does not promise
     */
    public static boolean forceExportToDisk(IProject project, List<String> topObjectFqns)
    {
        // The single place this plugin hands save tasks to the platform, and therefore the single
        // place a write tool's own account of WHERE it wrote can be taken without the tool having to
        // remember to give one (issue #408). Recorded before the attempt, not after a successful
        // one: a refused submission is not evidence that this call did not write - the model change
        // stands - and for a list submission that threw part way through it is not even evidence
        // that nothing was queued. No-op outside a write tool's call.
        WriteScope.recordExportSubmission(project);
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (dtProjectManager == null || bmModelManager == null)
            {
                return false;
            }
            IDtProject dtProject = dtProjectManager.getDtProject(project);
            if (dtProject == null)
            {
                return false;
            }
            return bmModelManager.forceExport(dtProject, topObjectFqns);
        }
        catch (RuntimeException e)
        {
            Activator.logError("forceExport to disk failed for " + topObjectFqns, e); //$NON-NLS-1$
            return false;
        }
    }
}
