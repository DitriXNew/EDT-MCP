/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Runs a unit of platform work in a background {@link Job} and waits for it with a hard
 * deadline, so an unattended MCP call can never be held open indefinitely by a wedged
 * platform operation.
 *
 * <p>Two layers of protection, deliberately combined:
 * <ul>
 * <li>the work receives the {@link Job}'s own {@link IProgressMonitor}, which is cancelled
 * when the deadline elapses — a platform call that polls its monitor (notably one waiting
 * for a conflicting scheduling rule) unwinds with {@code OperationCanceledException};</li>
 * <li>the caller stops waiting at the deadline regardless — cancellation is cooperative and
 * cannot preempt code that never polls, so the bound on the CALLER is what actually
 * guarantees an answer.</li>
 * </ul>
 *
 * <p><b>A timed-out job keeps running.</b> Cancelling only asks it to stop; the caller must
 * report the timeout honestly rather than pretend the work was undone.
 *
 * <p>The job is joined synchronously by the calling thread, so an unattended-safety
 * suppressor armed around the call (auth dialogs, launch auto-confirm) still sees the
 * request in flight and keeps covering modals raised from the job thread.
 */
public final class BoundedJob
{
    /** How a bounded run ended. */
    public enum Outcome
    {
        /** The work returned before the deadline (it may still have failed — see the failure). */
        COMPLETED,
        /** The deadline elapsed first; the job was cancelled but may still be running. */
        TIMED_OUT,
        /** The waiting thread was interrupted; the job was cancelled but may still be running. */
        INTERRUPTED,
        /**
         * The job left the queue without ever entering the work — it was cancelled before it
         * started. Distinguished from {@link #COMPLETED} because a job that never ran did NOT do
         * the work, and reporting that as success is a false green.
         */
        NOT_RUN
    }

    /**
     * The work to run under a deadline.
     */
    @FunctionalInterface
    public interface IBoundedWork
    {
        /**
         * Performs the work.
         *
         * @param monitor the job's monitor; cancelled when the deadline elapses
         * @throws Exception any failure — captured into {@link Result#getFailure()}, never
         *     propagated out of the job thread
         */
        void run(IProgressMonitor monitor) throws Exception; // NOSONAR the work is arbitrary platform code
    }

    /**
     * The outcome of a bounded run.
     */
    public static final class Result
    {
        private final Outcome outcome;
        private final long elapsedMs;
        private final Throwable failure;

        Result(Outcome outcome, long elapsedMs, Throwable failure)
        {
            this.outcome = outcome;
            this.elapsedMs = elapsedMs;
            this.failure = failure;
        }

        /**
         * @return how the run ended
         */
        public Outcome getOutcome()
        {
            return outcome;
        }

        /**
         * @return wall-clock milliseconds the caller waited
         */
        public long getElapsedMs()
        {
            return elapsedMs;
        }

        /**
         * @return the throwable the work raised, or {@code null} when it did not raise one
         *     (always {@code null} for {@link Outcome#TIMED_OUT}, where the work never returned)
         */
        public Throwable getFailure()
        {
            return failure;
        }

        /**
         * @return {@code true} when the work returned before the deadline WITHOUT raising
         */
        public boolean isSuccess()
        {
            return outcome == Outcome.COMPLETED && failure == null;
        }
    }

    private BoundedJob()
    {
        // Utility
    }

    /**
     * Runs {@code work} in a background job and waits at most {@code timeoutMs} for it.
     *
     * <p>An {@link Error} raised by the work is NOT captured as a result: it is rethrown on the
     * calling thread, exactly as it would have propagated before the work moved off that thread.
     * Only {@link Exception}s are reportable outcomes.
     *
     * @param jobName   the job name shown in EDT's progress UI
     * @param timeoutMs the deadline in milliseconds (values below 1 are treated as 1)
     * @param work      the work to run
     * @return the outcome — never {@code null}, never throws for a work that raised an Exception
     */
    public static Result run(String jobName, long timeoutMs, IBoundedWork work)
    {
        long startMs = System.currentTimeMillis();
        // Written by the job thread, read by the calling thread only after join() reports the job
        // finished — that report is the happens-before edge. On the TIMED_OUT path they are not
        // read at all, precisely because the job may still be writing them.
        final Throwable[] failureHolder = new Throwable[1];
        final boolean[] enteredWork = new boolean[1];

        Job job = new Job(jobName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                enteredWork[0] = true;
                try
                {
                    work.run(monitor);
                }
                catch (Throwable t) // NOSONAR captured here, but an Error is rethrown by the caller
                {
                    failureHolder[0] = t;
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(false);
        job.schedule();

        boolean finished;
        try
        {
            finished = job.join(Math.max(1L, timeoutMs), new NullProgressMonitor());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            job.cancel();
            return new Result(Outcome.INTERRUPTED, System.currentTimeMillis() - startMs, e);
        }

        if (!finished)
        {
            // Ask the platform call to unwind at its next monitor poll. It may never poll —
            // hence the caller already stopped waiting, and the job may outlive this call.
            job.cancel();
            return new Result(Outcome.TIMED_OUT, System.currentTimeMillis() - startMs, null);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (!enteredWork[0])
        {
            // The job left the queue without running (cancelled before it started). It did NOT do
            // the work, so it must not be reported as a completed one.
            return new Result(Outcome.NOT_RUN, elapsedMs, null);
        }
        if (failureHolder[0] instanceof Error)
        {
            // Preserve pre-#349 semantics: an Error was never a reportable tool outcome, it
            // propagated. Moving the work to a job thread must not turn that into a JSON error.
            throw (Error)failureHolder[0];
        }
        return new Result(Outcome.COMPLETED, elapsedMs, failureHolder[0]);
    }
}
