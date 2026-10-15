/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobChangeListener;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BoundedJob.Outcome;

/**
 * Tests for {@link BoundedJob}: the deadline that keeps a wedged platform call from holding an
 * unattended MCP request open forever (issue #349).
 * <p>
 * The "wedged" work in these tests waits on a latch with its OWN finite ceiling, so an
 * implementation that lost the bound fails the elapsed-time assertion instead of hanging the
 * whole suite — a test for a timeout must itself terminate when the timeout is gone.
 */
public class BoundedJobTest
{
    /**
     * Deadline for the timeout tests. The deadline starts at schedule time, so it must stay well
     * above the job-start latency: a job that had not started yet when the deadline elapsed is
     * cancelled before it ever runs, and the start-dependent assertions below would have nothing
     * to observe. Jobs start in single-digit milliseconds here, so 2s is a large margin.
     */
    private static final long SHORT_TIMEOUT_MS = 2000;

    /** Ceiling on the wedged work, well above {@link #SHORT_TIMEOUT_MS} but still finite. */
    private static final long WEDGE_CEILING_MS = 60_000;

    /** Bound that comfortably exceeds any real scheduling delay yet stays far below the wedge ceiling. */
    private static final long SANE_RETURN_MS = 30_000;

    /** Deadline for work that is expected to finish on its own. */
    private static final long GENEROUS_TIMEOUT_MS = 60_000;

    @Test
    public void testWorkThatReturnsIsReportedAsSuccess()
    {
        AtomicBoolean ran = new AtomicBoolean(false);

        BoundedJob.Result result = BoundedJob.run("test: quick work", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> ran.set(true));

        assertTrue("the work must have run", ran.get()); //$NON-NLS-1$
        assertEquals(Outcome.COMPLETED, result.getOutcome());
        assertTrue("work that returned without raising is a success", result.isSuccess()); //$NON-NLS-1$
        assertNull("nothing was raised", result.getFailure()); //$NON-NLS-1$
    }

    @Test
    public void testWorkFailureIsCapturedNotPropagated()
    {
        BoundedJob.Result result = BoundedJob.run("test: failing work", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                throw new IllegalStateException("boom"); //$NON-NLS-1$
            });

        assertEquals("a raising work still returned before the deadline", //$NON-NLS-1$
            Outcome.COMPLETED, result.getOutcome());
        assertFalse("a raising work is not a success", result.isSuccess()); //$NON-NLS-1$
        assertEquals("boom", result.getFailure().getMessage()); //$NON-NLS-1$
    }

    @Test
    public void testWorkThatDoesNotReturnTimesOutInsteadOfWaitingForIt() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            BoundedJob.Result result = BoundedJob.run("test: wedged work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
                monitor -> {
                    started.countDown();
                    release.await(WEDGE_CEILING_MS, TimeUnit.MILLISECONDS);
                });

            assertTrue("the work must have started", started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS)); //$NON-NLS-1$
            assertEquals(Outcome.TIMED_OUT, result.getOutcome());
            assertFalse("a timed-out run is not a success", result.isSuccess()); //$NON-NLS-1$
            assertNull("the work never returned, so it never reported a failure", result.getFailure()); //$NON-NLS-1$
            // The real assertion: the caller stopped waiting. Without the bound this would be
            // the work's own ceiling (WEDGE_CEILING_MS), not a fraction of it.
            assertTrue("the call must return on its deadline, not on the work's ceiling (waited " //$NON-NLS-1$
                + result.getElapsedMs() + "ms)", result.getElapsedMs() < SANE_RETURN_MS); //$NON-NLS-1$
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    public void testTimeoutCancelsTheMonitorHandedToTheWork() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch observedCancel = new CountDownLatch(1);

        BoundedJob.Result result = BoundedJob.run("test: cancellable work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                started.countDown();
                long ceiling = System.currentTimeMillis() + WEDGE_CEILING_MS;
                while (System.currentTimeMillis() < ceiling)
                {
                    if (monitor.isCanceled())
                    {
                        observedCancel.countDown();
                        return;
                    }
                    Thread.sleep(20);
                }
            });

        assertEquals(Outcome.TIMED_OUT, result.getOutcome());
        // Asserted separately so a stalled scheduler reports as "the work never started" rather
        // than masquerading as "cancellation was not propagated".
        assertTrue("the work must have started for this test to say anything about cancellation", //$NON-NLS-1$
            started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        assertTrue("work polling its monitor must see the cancellation the deadline raises", //$NON-NLS-1$
            observedCancel.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
    }

    /**
     * A deadline that elapses while the job is still QUEUED must NOT be reported as an ordinary
     * timeout: cancelling a queued job is what stops it from ever starting, so telling the caller
     * the work "may still be running" would be the exact opposite of the truth — and that sentence
     * is what an agent uses to decide whether its configuration is damaged (issue #365 review).
     *
     * <p>The scenario is produced the way the platform itself allows: an {@code aboutToRun}
     * listener puts THIS job (matched by name, so no other job in the JVM is touched) to sleep, so
     * the scheduler holds it without ever entering the work while {@code join} keeps blocking.
     * {@code IJobManager.suspend()} would NOT do — its contract makes {@code join} on a waiting job
     * return immediately, which produces {@code NOT_RUN}, a different branch entirely.
     */
    @Test
    public void testDeadlineOnAStillQueuedJobIsNotReportedAsWorkStillRunning()
    {
        String jobName = "test: never-started work " + System.nanoTime(); //$NON-NLS-1$
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean held = new AtomicBoolean(false);
        IJobChangeListener sleeper = new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                if (jobName.equals(event.getJob().getName()))
                {
                    held.set(event.getJob().sleep());
                }
            }
        };
        Job.getJobManager().addJobChangeListener(sleeper);
        BoundedJob.Result result;
        try
        {
            result = BoundedJob.run(jobName, SHORT_TIMEOUT_MS, monitor -> ran.set(true));
        }
        finally
        {
            Job.getJobManager().removeJobChangeListener(sleeper);
        }

        // Asserted first, and about the LISTENER rather than only the effect: without this, an
        // ambient scheduler stall would satisfy "the work did not run" and the test would pass
        // having never produced the scenario it claims to judge.
        assertTrue("this test must be the reason the job was held, not ambient scheduler luck", //$NON-NLS-1$
            held.get());
        assertFalse("the job must have been held before the work for this test to say anything", //$NON-NLS-1$
            ran.get());
        assertEquals("a deadline that caught the job still queued is not an ordinary timeout", //$NON-NLS-1$
            Outcome.TIMED_OUT_BEFORE_START, result.getOutcome());
        assertFalse("a run that never started is not a success", result.isSuccess()); //$NON-NLS-1$
        assertTrue("the call must still return on its deadline (waited " + result.getElapsedMs() //$NON-NLS-1$
            + "ms)", result.getElapsedMs() < SANE_RETURN_MS); //$NON-NLS-1$
    }

    /**
     * The counterpart: work that DID start and then wedged must stay an ordinary {@code TIMED_OUT}.
     * Without this, a discriminator that answered "never started" too eagerly would silently tell
     * callers their half-finished operation never happened — the same lie, mirrored.
     */
    @Test
    public void testWedgedWorkThatDidStartStaysAnOrdinaryTimeout() throws Exception
    {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        try
        {
            BoundedJob.Result result = BoundedJob.run("test: started then wedged", //$NON-NLS-1$
                SHORT_TIMEOUT_MS, monitor -> {
                    started.countDown();
                    release.await(WEDGE_CEILING_MS, TimeUnit.MILLISECONDS);
                });

            assertTrue("the work must have started", //$NON-NLS-1$
                started.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
            assertEquals("work that started and wedged is still in flight, not never-started", //$NON-NLS-1$
                Outcome.TIMED_OUT, result.getOutcome());
        }
        finally
        {
            release.countDown();
        }
    }

    /**
     * The NOT_RUN guard distinguishes "the job left the queue" from "the work ran". Its own branch
     * needs a job cancelled by a third party before it starts, which cannot be produced
     * deterministically from here; what IS pinned is that adding the guard did not make the normal
     * COMPLETED path unreachable — the failure mode a "did it really run?" flag invites.
     */
    @Test
    public void testTheDidItRunGuardLeavesTheNormalCompletedPathReachable()
    {
        BoundedJob.Result result = BoundedJob.run("test: ordinary work", SHORT_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> {
                // Reached only if the job actually runs.
            });

        assertEquals(Outcome.COMPLETED, result.getOutcome());
        assertTrue("work that ran and returned is a success", result.isSuccess()); //$NON-NLS-1$
    }

    @Test
    public void testCompletionListenerIsDetachedAfterNormalCompletion() throws Exception
    {
        CountDownLatch completionRan = new CountDownLatch(1);
        Job job = quickJob("test: listener detachment"); //$NON-NLS-1$
        CountingCompletionListener listener =
            new CountingCompletionListener(job.getName(), completionRan::countDown);
        listener.attach(job);

        scheduleAndAwaitCompletion(job);
        assertTrue("the normal terminal notification must invoke the completion", //$NON-NLS-1$
            completionRan.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        scheduleAndAwaitCompletion(job);
        assertEquals("the listener must be absent from the job's second terminal notification", //$NON-NLS-1$
            1, listener.getDoneCalls());
    }

    @Test
    public void testCompletionRunsExactlyOnceWhenSchedulingThrows() throws Exception
    {
        AtomicInteger completionCalls = new AtomicInteger();
        AtomicReference<Job> rejectedJob = new AtomicReference<>();
        IllegalStateException schedulingFailure = new IllegalStateException("cannot schedule"); //$NON-NLS-1$

        try
        {
            BoundedJob.run("test: scheduling failure", GENEROUS_TIMEOUT_MS, monitor -> { //$NON-NLS-1$
                // The scheduler fails before work can run.
            }, completionCalls::incrementAndGet, job -> {
                rejectedJob.set(job);
                throw schedulingFailure;
            });
            fail("the scheduling failure must be propagated"); //$NON-NLS-1$
        }
        catch (IllegalStateException e)
        {
            assertSame("the original scheduling failure must be propagated", schedulingFailure, e); //$NON-NLS-1$
        }

        assertEquals("a scheduling failure definitively completes the operation once", //$NON-NLS-1$
            1, completionCalls.get());
        scheduleAndAwaitCompletion(rejectedJob.get());
        assertEquals("a later terminal notification must not invoke the completion again", //$NON-NLS-1$
            1, completionCalls.get());
    }

    @Test
    public void testThrowingCompletionDoesNotEscapeOrPreventDetachment() throws Exception
    {
        Job job = quickJob("test: throwing completion"); //$NON-NLS-1$
        CountingCompletionListener listener = new CountingCompletionListener(job.getName(),
            () -> {
                throw new AssertionError("callback boom"); //$NON-NLS-1$
            });
        listener.attach(job);

        listener.done(null);
        scheduleAndAwaitCompletion(job);

        assertEquals("a throwing callback must not leave the listener on the job", //$NON-NLS-1$
            1, listener.getDoneCalls());
    }

    /**
     * The completion is the job's terminal notification, and EDT sends that also for a job cancelled before it
     * entered the work (#743 review, round 9): cancelling a WAITING or SLEEPING job moves it to NONE and notifies done
     * inside the cancel (org.eclipse.core.jobs 3.15.100 and 3.15.700, JobManager.cancel). The cancel here comes from
     * the scheduler, before the caller joins, so the completion has run by the time the run answers NOT_RUN.
     */
    @Test
    public void testCompletionRunsWhenTheJobIsCancelledBeforeItRuns()
    {
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();
        BoundedJob.Result result = BoundedJob.run("test: cancelled before it ran", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> ran.set(true), completions::incrementAndGet, job -> {
                job.schedule(GENEROUS_TIMEOUT_MS); // waiting to run...
                job.cancel(); // ...and cancelled before it does
            });

        assertEquals(Outcome.NOT_RUN, result.getOutcome());
        assertFalse("the work never ran", ran.get()); //$NON-NLS-1$
        assertEquals("the job's end without running runs the completion, once, before the run answers", //$NON-NLS-1$
            1, completions.get());
    }

    /**
     * The same when ANOTHER thread cancels the waiting job while the caller waits for it: the run answers NOT_RUN, and
     * the completion runs once - at the latest a moment after the run answered, on the thread EDT delivers done on.
     */
    @Test
    public void testCompletionRunsWhenAnotherThreadCancelsTheWaitingJob() throws Exception
    {
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);
        BoundedJob.Result result = BoundedJob.run("test: cancelled by another thread", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
            monitor -> ran.set(true), () -> {
                completions.incrementAndGet();
                completed.countDown();
            }, job -> {
                job.schedule(GENEROUS_TIMEOUT_MS); // waiting to run
                Thread canceller = new Thread(() -> {
                    try
                    {
                        Thread.sleep(200);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    job.cancel();
                }, "test: canceller"); //$NON-NLS-1$
                canceller.setDaemon(true);
                canceller.start();
            });

        assertEquals(Outcome.NOT_RUN, result.getOutcome());
        assertTrue("the caller waited for the cancel, not for its deadline (waited " + result.getElapsedMs() + "ms)", //$NON-NLS-1$ //$NON-NLS-2$
            result.getElapsedMs() < SANE_RETURN_MS);
        assertTrue("the job's end without running runs the completion", //$NON-NLS-1$
            completed.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        assertFalse("the work never ran", ran.get()); //$NON-NLS-1$
        assertEquals("exactly once", 1, completions.get()); //$NON-NLS-1$
    }

    /**
     * A job a SUSPENDED job manager still holds has not ended: join returns at once and the run answers NOT_RUN, but
     * EDT sends no terminal notification - the job is still WAITING. So the completion waits until the job ends - here
     * by running, once the manager resumes; a caller that took NOT_RUN for the job's end would be wrong.
     */
    @Test
    public void testCompletionWaitsForAJobASuspendedManagerStillHolds() throws Exception
    {
        IJobManager manager = Job.getJobManager();
        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(1);
        manager.suspend();
        try
        {
            BoundedJob.Result result = BoundedJob.run("test: held by a suspended manager", GENEROUS_TIMEOUT_MS, //$NON-NLS-1$
                monitor -> ran.set(true), completed::countDown);
            assertEquals("join returns at once for a waiting job of a suspended manager", //$NON-NLS-1$
                Outcome.NOT_RUN, result.getOutcome());
            assertFalse("the work has not run", ran.get()); //$NON-NLS-1$
            assertEquals("and the job has not ended: no completion yet", 1L, completed.getCount()); //$NON-NLS-1$
        }
        finally
        {
            manager.resume();
        }
        assertTrue("once the manager resumes, the job runs and ends, and the completion runs", //$NON-NLS-1$
            completed.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        assertTrue("the work ran", ran.get()); //$NON-NLS-1$
    }

    /**
     * A job a SUSPENDED job manager still holds also ends when something cancels it before the manager resumes, and
     * EDT then sends its terminal notification at once, suspended or not: cancelling a WAITING job moves it to NONE
     * and notifies done inside the cancel (org.eclipse.core.jobs 3.15.100 and 3.15.700, JobManager.cancel). So the
     * completion comes for a job that never ran - the run answered NOT_RUN, and the job does not run after the resume.
     */
    @Test
    public void testCompletionRunsWhenAJobASuspendedManagerHoldsIsCancelled()
    {
        IJobManager manager = Job.getJobManager();
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();
        AtomicReference<Job> held = new AtomicReference<>();
        manager.suspend();
        try
        {
            BoundedJob.Result result = BoundedJob.run("test: cancelled while held", //$NON-NLS-1$
                GENEROUS_TIMEOUT_MS, monitor -> ran.set(true), completions::incrementAndGet, job -> {
                    held.set(job);
                    McpJobs.schedule(job);
                });
            assertEquals("join returns at once for a waiting job of a suspended manager", //$NON-NLS-1$
                Outcome.NOT_RUN, result.getOutcome());
            assertEquals("the job has not ended: no completion yet", 0, completions.get()); //$NON-NLS-1$
            held.get().cancel(); // something cancels it while the manager is still suspended
            assertEquals("the cancel ended the job and ran the completion inside it, still suspended", //$NON-NLS-1$
                1, completions.get());
        }
        finally
        {
            manager.resume();
        }
        assertEquals("the job is off the job manager, so it does not run after the resume", Job.NONE, //$NON-NLS-1$
            held.get().getState());
        assertFalse("the work never ran", ran.get()); //$NON-NLS-1$
        assertEquals("exactly once", 1, completions.get()); //$NON-NLS-1$
    }

    /**
     * When OUR deadline keeps a queued job from starting, our cancel ends it, and the completion runs before the run
     * answers TIMED_OUT_BEFORE_START: the job is held asleep the way the never-started test holds it, and cancelling
     * a sleeping job notifies done inside the cancel.
     */
    @Test
    public void testCompletionRunsWhenOurDeadlineKeptTheJobFromStarting()
    {
        String jobName = "test: never-started work with a completion " + System.nanoTime(); //$NON-NLS-1$
        AtomicBoolean ran = new AtomicBoolean(false);
        AtomicBoolean held = new AtomicBoolean(false);
        AtomicInteger completions = new AtomicInteger();
        IJobChangeListener sleeper = new JobChangeAdapter()
        {
            @Override
            public void aboutToRun(IJobChangeEvent event)
            {
                if (jobName.equals(event.getJob().getName()))
                {
                    held.set(event.getJob().sleep());
                }
            }
        };
        Job.getJobManager().addJobChangeListener(sleeper);
        BoundedJob.Result result;
        try
        {
            result = BoundedJob.run(jobName, SHORT_TIMEOUT_MS, monitor -> ran.set(true), completions::incrementAndGet);
        }
        finally
        {
            Job.getJobManager().removeJobChangeListener(sleeper);
        }

        assertTrue("this test must be the reason the job was held", held.get()); //$NON-NLS-1$
        assertFalse("the work never ran", ran.get()); //$NON-NLS-1$
        assertEquals(Outcome.TIMED_OUT_BEFORE_START, result.getOutcome());
        assertEquals("our cancel ended the job and ran the completion, once, before the run answered", //$NON-NLS-1$
            1, completions.get());
    }

    private static Job quickJob(String name)
    {
        return new Job(name)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                return Status.OK_STATUS;
            }
        };
    }

    private static void scheduleAndAwaitCompletion(Job job) throws Exception
    {
        CountDownLatch done = new CountDownLatch(1);
        IJobChangeListener observer = new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                done.countDown();
            }
        };
        job.addJobChangeListener(observer);
        try
        {
            McpJobs.schedule(job);
            assertTrue("the test job must reach its terminal notification", //$NON-NLS-1$
                done.await(SANE_RETURN_MS, TimeUnit.MILLISECONDS));
        }
        finally
        {
            job.removeJobChangeListener(observer);
        }
    }

    private static final class CountingCompletionListener extends BoundedJob.CompletionListener
    {
        private final AtomicInteger doneCalls = new AtomicInteger();

        CountingCompletionListener(String jobName, Runnable completion)
        {
            super(jobName, completion);
        }

        @Override
        public void done(IJobChangeEvent event)
        {
            doneCalls.incrementAndGet();
            super.done(event);
        }

        int getDoneCalls()
        {
            return doneCalls.get();
        }
    }
}
