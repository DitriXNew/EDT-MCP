/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.atomic.AtomicBoolean;

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
 * <p><b>A timed-out job usually keeps running.</b> Cancelling only asks it to stop; the caller
 * must report the timeout honestly rather than pretend the work was undone. The one exception is
 * a job the deadline caught while it was still QUEUED: cancelling it there stops it from ever
 * starting, and reporting THAT as "it may still be running" is the opposite of the truth — hence
 * the separate {@link Outcome#TIMED_OUT_BEFORE_START}.
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
        /**
         * The deadline elapsed while the job was still QUEUED, and cancelling it kept it from ever
         * starting. The work did NOT run and will not run — the opposite of {@link #TIMED_OUT},
         * where it is still in flight, and the distinction the caller's message turns on.
         */
        TIMED_OUT_BEFORE_START,
        /** The waiting thread was interrupted; the job was cancelled but may still be running. */
        INTERRUPTED,
        /**
         * The job left the queue without ever entering the work — something else cancelled it
         * before it started (or the job manager was suspended, in which case {@code join} returns
         * at once). Distinguished from {@link #COMPLETED} because a job that never ran did NOT do
         * the work, and reporting that as success is a false green; distinguished from
         * {@link #TIMED_OUT_BEFORE_START} because OUR deadline was not what stopped it, so
         * "retry with a larger timeout" would be the wrong advice.
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

    /**
     * How long to wait, after cancelling a timed-out job, for it to leave the scheduler.
     * <p>
     * Paid only when the job has not entered the work yet, which for a job that IS running is a
     * window of microseconds (the flag is the first statement of the job body) — so in the normal
     * wedged case this costs nothing, and in the queued case it buys a definite answer.
     */
    private static final long NOT_STARTED_GRACE_MS = 500;

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
     * @param timeoutMs the deadline in milliseconds (values below 1 are treated as 1). A run that
     *     expires WITHOUT the work having started may exceed it by up to
     *     {@value #NOT_STARTED_GRACE_MS} ms while it establishes that fact — the alternative is
     *     answering "it may still be running" about work that never began
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
        // Atomic, not a boolean[]: on the TIMEOUT path this is read by the waiting thread with no
        // join() edge in front of it, so it needs to be safely published on its own.
        final AtomicBoolean enteredWork = new AtomicBoolean(false);

        Job job = new Job(jobName)
        {
            /** Consumed by the one and only schedule this job is allowed. */
            private final AtomicBoolean scheduledOnce = new AtomicBoolean(false);

            @Override
            public boolean shouldSchedule()
            {
                // The classification below reads "did the work start?" AFTER the job left the
                // scheduler, and the failure holder is a plain array — both are sound only for a
                // job that runs AT MOST ONCE. Every global IJobChangeListener is handed this Job
                // object and could re-schedule it; refusing every schedule after our own turns
                // "unlikely" into "impossible". The platform asks this exactly once per schedule
                // (InternalJob.schedule), and its own self-reschedule path is short-circuited
                // before the question is put (JobManager.endJob), so a normal run never spends
                // the token.
                return scheduledOnce.compareAndSet(false, true);
            }

            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                enteredWork.set(true);
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
            if (!enteredWork.get() && leftTheSchedulerWithoutStarting(job, enteredWork))
            {
                // Our own cancel() is what kept it from starting, so the work did not happen and
                // will not. Saying "it may still be running" here would be a lie in the one
                // sentence the caller uses to decide whether its state is damaged.
                return new Result(Outcome.TIMED_OUT_BEFORE_START,
                    System.currentTimeMillis() - startMs, null);
            }
            return new Result(Outcome.TIMED_OUT, System.currentTimeMillis() - startMs, null);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (!enteredWork.get())
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

    /**
     * Whether the just-cancelled {@code job} left the scheduler WITHOUT ever entering the work.
     *
     * <p>Deliberately NOT decided by {@link Job#cancel()}'s return value, which cannot answer this:
     * a job in {@code ABOUT_TO_RUN} is flagged {@code aboutToRunCanceled} and then never runs, yet
     * {@code cancel()} reports {@code false} for it exactly as it does for a job that is genuinely
     * running (see {@code JobManager.cancel} / {@code startJob} in org.eclipse.core.jobs). Using
     * that boolean would classify a rename that never began as one still in flight — the mistake
     * this method exists to avoid.
     *
     * <p>What DOES answer it is the scheduler itself: after {@code cancel()}, a job that was queued
     * (or refused at the start line) settles to {@code NONE} promptly, so a short {@code join}
     * returns {@code true}; a job that is really running keeps the join busy for the whole grace.
     * The work flag is then re-read to separate that from a job which finished in the race window
     * between the deadline and the cancel.
     *
     * @param job the cancelled job
     * @param enteredWork the flag the job body sets as its first statement
     * @return {@code true} only when the job is off the scheduler AND never entered the work
     */
    private static boolean leftTheSchedulerWithoutStarting(Job job, AtomicBoolean enteredWork)
    {
        try
        {
            if (!job.join(NOT_STARTED_GRACE_MS, new NullProgressMonitor()))
            {
                return false;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return false;
        }
        return !enteredWork.get();
    }
}
