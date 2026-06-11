/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ditrix.edt.mcp.server.Activator;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;

/**
 * Runs a synchronous {@code IApplicationManager.update(...)} call with a bounded
 * wall-clock timeout so a stuck update cannot hang the MCP worker thread until
 * the transport-level timeout severs the connection.
 *
 * <p>The update is not cancellable, so on timeout we do <b>not</b> interrupt it:
 * the call keeps running on a daemon background thread (the infobase eventually
 * settles), and the watchdog returns {@link ApplicationUpdateState#BEING_UPDATED}
 * — which {@link ApplicationUpdatePolicy} maps to "poll get_applications". The
 * MCP call therefore returns a clear, actionable result instead of blocking.
 *
 * <p>This is a backstop. The primary defences against a hanging update are
 * terminating the sessions that hold the infobase and auto-confirming EDT's
 * blocking "Application update" modal (see {@code LaunchLifecycleUtils} /
 * {@code EdtDialogAutoConfirmer}).
 */
public final class UpdateWatchdog
{
    private static final ThreadFactory DAEMON_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "edt-mcp-update-watchdog"); //$NON-NLS-1$
        thread.setDaemon(true);
        return thread;
    };

    private UpdateWatchdog()
    {
        // Utility class
    }

    /**
     * Executes {@code updateCall} on a daemon thread, waiting at most
     * {@code timeoutSeconds} for it to return.
     *
     * @param updateCall     the {@code appManager.update(...)} invocation wrapped
     *                       as a {@link Callable} (decoupled from EDT so it is
     *                       unit-testable)
     * @param timeoutSeconds wall-clock limit; values below 1 are clamped to 1
     * @return the state returned by the update, or
     *         {@link ApplicationUpdateState#BEING_UPDATED} on timeout
     * @throws ApplicationException if the update call itself failed with one
     * @throws InterruptedException if the waiting thread was interrupted
     */
    public static ApplicationUpdateState runWithTimeout(Callable<ApplicationUpdateState> updateCall,
            int timeoutSeconds) throws ApplicationException, InterruptedException
    {
        return runWithTimeout(updateCall, timeoutSeconds, null, null);
    }

    /**
     * As {@link #runWithTimeout(Callable, int)}, but brackets the update with
     * {@code onStart} / {@code onComplete} so a guard (e.g. a dialog
     * auto-confirmer) stays active for the <b>entire</b> life of the update —
     * including after a timeout, while the abandoned update keeps running in the
     * background. This matters for a long full update (a multi-minute reload that
     * pops a restructurization modal well after the watchdog already returned
     * {@code BEING_UPDATED}): the guard must still be armed to auto-confirm it.
     *
     * <p>{@code onComplete} runs exactly once, on whichever thread finishes the
     * update: the caller thread for a fast return, or the daemon thread after a
     * timeout (i.e. AFTER this method returned {@code BEING_UPDATED}).
     *
     * @param updateCall     the {@code appManager.update(...)} invocation wrapped
     *                       as a {@link Callable} (decoupled from EDT so it is
     *                       unit-testable)
     * @param timeoutSeconds wall-clock limit; values below 1 are clamped to 1
     * @param onStart        run on the calling thread before the update starts
     *                       (may be {@code null})
     * @param onComplete     run when the update actually finishes, success or
     *                       failure (may be {@code null})
     * @return the state returned by the update, or
     *         {@link ApplicationUpdateState#BEING_UPDATED} on timeout
     * @throws ApplicationException if the update call itself failed with one
     * @throws InterruptedException if the waiting thread was interrupted
     */
    public static ApplicationUpdateState runWithTimeout(Callable<ApplicationUpdateState> updateCall,
            int timeoutSeconds, Runnable onStart, Runnable onComplete)
            throws ApplicationException, InterruptedException
    {
        int seconds = Math.max(1, timeoutSeconds);
        if (onStart != null)
        {
            onStart.run();
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(DAEMON_FACTORY);
        try
        {
            Future<ApplicationUpdateState> future = executor.submit(() -> {
                try
                {
                    return updateCall.call();
                }
                finally
                {
                    // Runs when the update TRULY finishes. On a timeout this fires on
                    // the daemon thread after runWithTimeout already returned
                    // BEING_UPDATED, so the guard outlives the abandoned update.
                    if (onComplete != null)
                    {
                        onComplete.run();
                    }
                }
            });
            try
            {
                return future.get(seconds, TimeUnit.SECONDS);
            }
            catch (TimeoutException e)
            {
                Activator.logInfo("update watchdog: appManager.update did not return within " //$NON-NLS-1$
                    + seconds + "s — it continues in the background; reporting BEING_UPDATED so the " //$NON-NLS-1$
                    + "caller can poll get_applications."); //$NON-NLS-1$
                // Do NOT cancel: the update is not interruptible and cancelling would
                // only abort the waiting thread, not the EDT operation.
                return ApplicationUpdateState.BEING_UPDATED;
            }
            catch (ExecutionException e)
            {
                throw unwrap(e);
            }
        }
        finally
        {
            // shutdown(), not shutdownNow(): let an abandoned (timed-out) update
            // run to completion on its daemon thread (and fire onComplete there).
            executor.shutdown();
        }
    }

    /**
     * Rethrows the real cause of an {@link ExecutionException}. The wrapped
     * {@code update()} only declares {@link ApplicationException}; unchecked
     * throwables are propagated as-is.
     */
    private static ApplicationException unwrap(ExecutionException e) throws ApplicationException
    {
        Throwable cause = e.getCause();
        if (cause instanceof ApplicationException)
        {
            return (ApplicationException)cause;
        }
        if (cause instanceof RuntimeException)
        {
            throw (RuntimeException)cause;
        }
        if (cause instanceof Error)
        {
            throw (Error)cause;
        }
        // No other checked type is possible from update(); be defensive anyway.
        throw new IllegalStateException("Database update failed", //$NON-NLS-1$
            cause != null ? cause : e);
    }
}
