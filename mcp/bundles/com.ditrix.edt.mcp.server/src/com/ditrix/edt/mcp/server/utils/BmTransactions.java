/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.IProgressMonitor;

import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;

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
        return model.execute(new AbstractBmTask<T>(taskName)
        {
            @Override
            public T execute(IBmTransaction tx, IProgressMonitor monitor)
            {
                return operation.execute(tx, monitor);
            }
        });
    }
}
