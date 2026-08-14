/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Small bounded registry for long-running MCP work whose result is polled by id.
 * <p>
 * Work runs on daemon worker threads, never on the SWT UI or MCP request thread.
 * The registry retains at most a configured number of jobs and evicts the oldest
 * completed entry before accepting another one. Running jobs are never evicted;
 * when every slot is running, a new submission is rejected instead of leaking
 * an unbounded queue of results.
 */
public final class BackgroundJobs implements AutoCloseable
{
    private static final int DEFAULT_MAX_STORED_JOBS = 100;
    private static final int DEFAULT_WORKER_THREADS = 4;
    private static final long SHUTDOWN_WAIT_SECONDS = 2;

    private static final Object SHARED_LOCK = new Object();
    private static BackgroundJobs shared;

    /** Terminal/running states exposed to polling tools. */
    public enum Status
    {
        RUNNING("running"), //$NON-NLS-1$
        DONE("done"), //$NON-NLS-1$
        FAILED("failed"); //$NON-NLS-1$

        private final String value;

        Status(String value)
        {
            this.value = value;
        }

        /** @return stable lower-case value used in tool output */
        public String value()
        {
            return value;
        }
    }

    /** One timestamped progress entry. */
    public static final class ProgressEntry
    {
        private final long timestampMs;
        private final String message;

        ProgressEntry(long timestampMs, String message)
        {
            this.timestampMs = timestampMs;
            this.message = message;
        }

        public long getTimestampMs()
        {
            return timestampMs;
        }

        public String getMessage()
        {
            return message;
        }
    }

    /** Immutable point-in-time view returned to a polling caller. */
    public static final class JobSnapshot
    {
        private final String id;
        private final Status status;
        private final long startedAtMs;
        private final long completedAtMs;
        private final long elapsedMs;
        private final List<ProgressEntry> progress;
        private final Object result;
        private final String errorMessage;

        JobSnapshot(String id, Status status, long startedAtMs, long completedAtMs,
            long elapsedMs, List<ProgressEntry> progress, Object result, String errorMessage)
        {
            this.id = id;
            this.status = status;
            this.startedAtMs = startedAtMs;
            this.completedAtMs = completedAtMs;
            this.elapsedMs = elapsedMs;
            this.progress = Collections.unmodifiableList(progress);
            this.result = result;
            this.errorMessage = errorMessage;
        }

        public String getId()
        {
            return id;
        }

        public Status getStatus()
        {
            return status;
        }

        public long getStartedAtMs()
        {
            return startedAtMs;
        }

        public long getCompletedAtMs()
        {
            return completedAtMs;
        }

        public long getElapsedMs()
        {
            return elapsedMs;
        }

        public List<ProgressEntry> getProgress()
        {
            return progress;
        }

        public Object getResult()
        {
            return result;
        }

        public String getErrorMessage()
        {
            return errorMessage;
        }
    }

    /** Adds a timestamped progress message to the current job. */
    @FunctionalInterface
    public interface ProgressReporter
    {
        void add(String message);

        /**
         * Takes the job past the point where it can still be abandoned, in ONE step with the
         * deadline: either this wins and the deadline can no longer publish a retryable
         * failure, or the job is already terminal and the work must NOT go through with it.
         * <p>
         * Work that hands something to another thread - EDT's SWT thread, a remote service -
         * cannot take it back once it is handed over. Publishing "timed out, start a new job"
         * for such a job invites a retry that performs the SAME action a second time. Asking
         * first, and only then checking whether the job is still alive, has the same problem
         * from the other end: the deadline may already have failed the job. Hence one call,
         * made immediately BEFORE the irreversible step, whose answer decides whether that
         * step happens at all.
         * <p>
         * After it succeeds the total budget can still be exceeded, and the outcome is then
         * whatever the work reports; the work stays responsible for terminating on its own,
         * because nothing cancels it any more.
         *
         * @return {@code true} when the job is still running and is now committed
         */
        default boolean tryCommit()
        {
            // Work that can always be abandoned simply proceeds.
            return true;
        }
    }

    /** Who owns a job's work, and therefore its admission slot. */
    private enum WorkState
    {
        /** Submitted; neither the callable nor a canceller has claimed it yet. */
        NOT_STARTED,
        /** The callable claimed it: only the callable may release the slot. */
        STARTED,
        /** A canceller claimed it first: the callable will not run, and will not release. */
        CANCELLED_BEFORE_START
    }

    /** Work submitted to the registry. */
    @FunctionalInterface
    public interface JobWork
    {
        Object run(ProgressReporter progress) throws Exception; // NOSONAR arbitrary background work
    }

    /**
     * Jobs admitted whose work has not finished running yet.
     * <p>
     * Counted rather than derived from job STATUS on purpose: a timed-out job is published
     * as failed immediately, but its worker thread keeps the pool slot until the work
     * actually unwinds - and work that ignores interruption can hold it far longer. Admitting
     * a replacement on the strength of the status alone would hand out a slot that is still
     * occupied, which is exactly the starvation an admission limit exists to prevent.
     */
    private final AtomicInteger inFlight = new AtomicInteger();

    private final Object jobsLock = new Object();
    private final Map<String, JobRecord> jobs = new LinkedHashMap<>();
    private final int maxStoredJobs;
    private final ExecutorService workers;
    private final ScheduledExecutorService deadlines;

    private volatile boolean closed;

    /**
     * Creates an isolated registry. The public constructor is useful for tools
     * that need a separately owned lifecycle and for deterministic headless tests.
     *
     * @param maxStoredJobs maximum retained running/completed jobs
     * @param workerThreads number of non-UI worker threads
     */
    public BackgroundJobs(int maxStoredJobs, int workerThreads)
    {
        if (maxStoredJobs < 1 || workerThreads < 1)
        {
            throw new IllegalArgumentException("BackgroundJobs limits must be positive"); //$NON-NLS-1$
        }
        this.maxStoredJobs = maxStoredJobs;
        workers = Executors.newFixedThreadPool(workerThreads,
            new NamedDaemonThreadFactory("EDT-MCP background-job-worker-")); //$NON-NLS-1$
        deadlines = Executors.newSingleThreadScheduledExecutor(
            new NamedDaemonThreadFactory("EDT-MCP background-job-deadline-")); //$NON-NLS-1$
    }

    /** @return bundle-wide registry, recreated lazily after bundle restart */
    public static BackgroundJobs shared()
    {
        synchronized (SHARED_LOCK)
        {
            if (shared == null || shared.closed)
            {
                shared = new BackgroundJobs(DEFAULT_MAX_STORED_JOBS, DEFAULT_WORKER_THREADS);
            }
            return shared;
        }
    }

    /** Stops and forgets the bundle-wide registry. Safe to call repeatedly. */
    public static void shutdownShared()
    {
        BackgroundJobs toClose;
        synchronized (SHARED_LOCK)
        {
            toClose = shared;
            shared = null;
        }
        if (toClose != null)
        {
            toClose.close();
        }
    }

    /**
     * Starts a job with a total wall-clock budget measured from submission.
     *
     * @param timeoutMs total job budget in milliseconds
     * @param initialProgress first domain-specific progress message
     * @param work work to execute off the caller thread
     * @return initial snapshot, usually {@link Status#RUNNING}
     * @throws RejectedExecutionException when the registry is stopped or full of running jobs
     */
    public JobSnapshot start(long timeoutMs, String initialProgress, JobWork work)
    {
        return start(timeoutMs, Integer.MAX_VALUE, initialProgress, work);
    }

    /**
     * Starts a job only while fewer than {@code maxRunning} jobs are running, counting and
     * admitting under ONE lock.
     * <p>
     * A caller that counts first and starts afterwards has a window in which several
     * concurrent requests all see room and all start, which defeats the very reservation the
     * count was meant to enforce. Admission has to be part of the insertion, not a check
     * before it.
     *
     * @param timeoutMs total job budget in milliseconds
     * @param maxRunning admit only while strictly fewer jobs are running
     * @param initialProgress first domain-specific progress message
     * @param work work to execute off the caller thread
     * @return the initial snapshot, or {@code null} when the running limit is reached
     * @throws RejectedExecutionException when the registry is stopped or full
     */
    public JobSnapshot start(long timeoutMs, int maxRunning, String initialProgress, JobWork work)
    {
        if (work == null)
        {
            throw new IllegalArgumentException("Background job work must not be null"); //$NON-NLS-1$
        }
        long boundedTimeoutMs = Math.max(1L, timeoutMs);
        JobRecord record = new JobRecord(UUID.randomUUID().toString(), initialProgress);

        synchronized (jobsLock)
        {
            ensureOpen();
            evictOldestCompleted();
            if (jobs.size() >= maxStoredJobs)
            {
                throw new RejectedExecutionException("Background job registry is full with " //$NON-NLS-1$
                    + maxStoredJobs + " running jobs"); //$NON-NLS-1$
            }
            if (inFlight.get() >= maxRunning)
            {
                return null;
            }
            jobs.put(record.id, record);
            inFlight.incrementAndGet();
        }

        // The slot is given back by whichever side can PROVE the worker thread is free: the
        // callable when it unwinds, or the cancelling side when the callable will never run.
        // Neither alone is enough - a callable that ignores interruption keeps its thread long
        // after cancellation, and a task cancelled in the queue never reaches its callable.
        record.onSlotRelease(inFlight::decrementAndGet);

        FutureTask<Void> task = new FutureTask<>(() -> {
            if (record.beginWork())
            {
                try
                {
                    runWork(record, work);
                }
                finally
                {
                    record.releaseSlot();
                }
            }
            return null;
        });
        record.setWorkFuture(task);
        if (!record.isRunning())
        {
            // Already terminal before it could be submitted (a budget of a millisecond or two).
            record.cancelWork();
        }

        try
        {
            ScheduledFuture<?> deadline = deadlines.schedule(
                () -> timeOut(record, boundedTimeoutMs), boundedTimeoutMs,
                TimeUnit.MILLISECONDS);
            record.setDeadlineFuture(deadline);
            workers.execute(task);
        }
        catch (RejectedExecutionException e)
        {
            record.fail("Background job could not start because the registry is stopping."); //$NON-NLS-1$
            // Nothing will run this task, so the cancelling side is the one that owes the slot.
            record.cancelWork();
            throw e;
        }
        return record.snapshot();
    }

    /** @return current job snapshot, or {@code null} when the id is unknown/evicted */

    public JobSnapshot get(String jobId)
    {
        JobRecord record;
        synchronized (jobsLock)
        {
            record = jobs.get(jobId);
        }
        return record != null ? record.snapshot() : null;
    }

    /**
     * Waits at most the caller-provided budget for a job to become terminal.
     *
     * @param jobId job identifier
     * @param waitMs per-call wait budget in milliseconds
     * @return latest snapshot, or {@code null} when the id is unknown/evicted
     */
    public JobSnapshot await(String jobId, long waitMs)
    {
        JobRecord record;
        synchronized (jobsLock)
        {
            record = jobs.get(jobId);
        }
        if (record == null)
        {
            return null;
        }
        record.await(Math.max(0L, waitMs));
        return record.snapshot();
    }

    @Override
    public void close()
    {
        if (closed)
        {
            return;
        }
        closed = true;

        List<JobRecord> records;
        synchronized (jobsLock)
        {
            records = new ArrayList<>(jobs.values());
        }
        for (JobRecord record : records)
        {
            // Shutdown gets the same treatment as the deadline: a job whose request is already
            // handed over must not be published as "cancelled, try again", because the retry
            // after the restart would perform the action a second time. The pool is torn down
            // either way below - what is at stake here is only what a poller is told.
            if (record.failUnlessCommitted(
                "EDT-MCP is stopping; the background job was cancelled.", //$NON-NLS-1$
                "EDT-MCP is stopping, but this request was already handed over and cannot be " //$NON-NLS-1$
                    + "taken back.")) //$NON-NLS-1$
            {
                // Only what was actually failed gets cancelled, exactly as on the deadline path:
                // interrupting a committed job would turn into a gateway failure and publish a
                // retryable error over a request that is already on its way.
                record.cancelWork();
            }
        }

        deadlines.shutdownNow();
        // shutdown(), not shutdownNow(): a committed job was deliberately NOT cancelled above,
        // and interrupting its worker here would undo that - the gateway turns the interrupt
        // into a failure and the caller is told to retry work that is already under way. Every
        // job that COULD be abandoned has had its task cancelled, so nothing queued will run,
        // and awaitTermination still escalates to shutdownNow when the grace period is up: a
        // request that outlives the bundle cannot be saved, only reported honestly.
        workers.shutdown();
        awaitTermination(deadlines);
        awaitTermination(workers);

        synchronized (jobsLock)
        {
            jobs.clear();
        }
    }

    private void ensureOpen()
    {
        if (closed)
        {
            throw new RejectedExecutionException("Background job registry is stopped"); //$NON-NLS-1$
        }
    }

    private void evictOldestCompleted()
    {
        while (jobs.size() >= maxStoredJobs)
        {
            String oldestId = null;
            long oldestCompletion = Long.MAX_VALUE;
            for (Map.Entry<String, JobRecord> entry : jobs.entrySet())
            {
                long completedAt = entry.getValue().completedAtMs();
                if (completedAt > 0 && completedAt < oldestCompletion)
                {
                    oldestCompletion = completedAt;
                    oldestId = entry.getKey();
                }
            }
            if (oldestId == null)
            {
                return;
            }
            jobs.remove(oldestId);
        }
    }

    private static void runWork(JobRecord record, JobWork work)
    {
        if (!record.isRunning())
        {
            return;
        }
        try
        {
            Object result = work.run(new ProgressReporter()
            {
                @Override
                public void add(String message)
                {
                    record.addProgress(message);
                }

                @Override
                public boolean tryCommit()
                {
                    return record.tryCommit();
                }
            });
            record.complete(result);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            record.fail("Background job was interrupted."); //$NON-NLS-1$
        }
        catch (Exception e) // NOSONAR job failures are retained for polling
        {
            record.fail(failureMessage(e));
        }
        catch (LinkageError e)
        {
            record.fail(failureMessage(e));
        }
    }

    private static void timeOut(JobRecord record, long timeoutMs)
    {
        String timeout = formatSeconds(timeoutMs);
        // Committed-or-fail is decided under the record's own lock. Reading the flag first and
        // failing afterwards would leave a window in which the work commits in between, and
        // the job would be published as a retryable failure for a request already on its way.
        if (record.failUnlessCommitted(
            "Job exceeded its total timeoutSeconds budget of " //$NON-NLS-1$
                + timeout + ". Start a new job with a larger timeoutSeconds value, or check " //$NON-NLS-1$
                + "Workmate and network status.", //$NON-NLS-1$
            "The total timeoutSeconds budget of " + timeout //$NON-NLS-1$
                + " expired after the request was already handed over, so it is left to " //$NON-NLS-1$
                + "finish instead of being reported as failed.")) //$NON-NLS-1$
        {
            record.cancelWork();
        }
    }

    private static String formatSeconds(long timeoutMs)
    {
        if (timeoutMs % 1000L == 0)
        {
            return Long.toString(timeoutMs / 1000L) + " seconds"; //$NON-NLS-1$
        }
        return timeoutMs + " ms"; //$NON-NLS-1$
    }

    private static String failureMessage(Throwable failure)
    {
        String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName() : message;
    }

    private static void awaitTermination(ExecutorService executor)
    {
        try
        {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS))
            {
                executor.shutdownNow();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class JobRecord
    {
        private final String id;
        private final long startedAtMs = System.currentTimeMillis();
        private final long startedAtNanos = System.nanoTime();
        private final List<ProgressEntry> progress = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        private Status status = Status.RUNNING;
        /** Set by the work once its request can no longer be taken back. */
        private boolean committed;
        private long completedAtMs;
        private long completedAtNanos;
        private Object result;
        private String errorMessage;
        private Future<?> workFuture;
        private ScheduledFuture<?> deadlineFuture;
        /** Gives the admission slot back; see {@link #releaseSlot()}. */
        private Runnable slotRelease;
        private boolean slotReleased;
        /**
         * Which side owns the work, decided ONCE: the callable that starts it, or the canceller
         * that gets there first. Whoever wins the transition out of {@link WorkState#NOT_STARTED}
         * owes the admission slot, which is why this cannot be two separate flags.
         */
        private final AtomicReference<WorkState> workState =
            new AtomicReference<>(WorkState.NOT_STARTED);

        JobRecord(String id, String initialProgress)
        {
            this.id = id;
            addProgress(initialProgress);
        }

        synchronized boolean isRunning()
        {
            return status == Status.RUNNING;
        }

        synchronized boolean tryCommit()
        {
            if (status != Status.RUNNING)
            {
                // The deadline (or anything else) already published a terminal outcome, so the
                // caller must not go through with the irreversible step it was about to take.
                return false;
            }
            committed = true;
            return true;
        }

        synchronized void setWorkFuture(Future<?> future)
        {
            workFuture = future;
        }

        /** @param release runs exactly once, when the worker thread is provably free */
        void onSlotRelease(Runnable release)
        {
            synchronized (this)
            {
                slotRelease = release;
            }
        }

        /** Gives the admission slot back, at most once however many sides call it. */
        void releaseSlot()
        {
            Runnable release;
            synchronized (this)
            {
                if (slotReleased || slotRelease == null)
                {
                    return;
                }
                slotReleased = true;
                release = slotRelease;
            }
            release.run();
        }

        /**
         * Takes ownership of the work, and with it of the admission slot.
         *
         * @return {@code false} when the job was cancelled before this point, in which case the
         *         work must NOT run and must NOT release the slot - the canceller won the same
         *         compare-and-set and has already accounted for it
         */
        boolean beginWork()
        {
            return workState.compareAndSet(WorkState.NOT_STARTED, WorkState.STARTED);
        }

        synchronized void setDeadlineFuture(ScheduledFuture<?> future)
        {
            deadlineFuture = future;
            if (status != Status.RUNNING)
            {
                future.cancel(false);
            }
        }

        synchronized void addProgress(String message)
        {
            if (status == Status.RUNNING && message != null && !message.isBlank())
            {
                progress.add(new ProgressEntry(System.currentTimeMillis(), message));
            }
        }

        boolean complete(Object completedResult)
        {
            ScheduledFuture<?> deadline;
            synchronized (this)
            {
                if (status != Status.RUNNING)
                {
                    return false;
                }
                status = Status.DONE;
                result = completedResult;
                completedAtMs = System.currentTimeMillis();
                completedAtNanos = System.nanoTime();
                deadline = deadlineFuture;
            }
            if (deadline != null)
            {
                deadline.cancel(false);
            }
            completed.countDown();
            return true;
        }

        boolean fail(String message)
        {
            return fail(message, null);
        }

        /**
         * Fails the job unless the work has committed, deciding both under ONE lock.
         *
         * @param message failure to publish when the job can still be abandoned
         * @param committedNote progress line to record instead when it cannot
         * @return {@code true} when the job was actually failed
         */
        boolean failUnlessCommitted(String message, String committedNote)
        {
            return fail(message, committedNote);
        }

        private boolean fail(String message, String committedNote)
        {
            ScheduledFuture<?> deadline;
            synchronized (this)
            {
                if (committedNote != null && committed)
                {
                    // The request is already on its way and cannot be taken back, so the
                    // budget is allowed to overrun and the work publishes its own outcome.
                    addProgress(committedNote);
                    return false;
                }
                if (status != Status.RUNNING)
                {
                    return false;
                }
                progress.add(new ProgressEntry(System.currentTimeMillis(),
                    "Failed: " + message)); //$NON-NLS-1$
                status = Status.FAILED;
                errorMessage = message;
                completedAtMs = System.currentTimeMillis();
                completedAtNanos = System.nanoTime();
                deadline = deadlineFuture;
            }
            if (deadline != null)
            {
                deadline.cancel(false);
            }
            completed.countDown();
            return true;
        }

        /**
         * Stops the work, and settles who owes the admission slot in the same step.
         * <p>
         * ONE compare-and-set decides which case this is, so the two sides cannot both act:
         * work that never started is cancelled here and its slot is owed by this side, because
         * the callable that would have released it will never run. Work that HAS started is
         * left to its own callable - it still owns the pool thread, and handing its slot to a
         * replacement would promise a thread that is not free.
         * <p>
         * A started callable is stopped through its own future, never by interrupting a thread
         * this record remembers: the pool reuses threads, so a remembered thread may by then be
         * running somebody else's job, and the interrupt would land on that job instead.
         * {@code FutureTask} interrupts only while its own runner is still set, which is exactly
         * the guarantee needed here.
         */
        void cancelWork()
        {
            Future<?> future;
            synchronized (this)
            {
                future = workFuture;
            }
            if (workState.compareAndSet(WorkState.NOT_STARTED, WorkState.CANCELLED_BEFORE_START))
            {
                if (future != null)
                {
                    future.cancel(false);
                }
                releaseSlot();
                return;
            }
            if (future != null)
            {
                future.cancel(true);
            }
        }

        void await(long waitMs)
        {
            if (waitMs <= 0)
            {
                return;
            }
            try
            {
                completed.await(waitMs, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        synchronized long completedAtMs()
        {
            return completedAtMs;
        }

        synchronized JobSnapshot snapshot()
        {
            long endNanos = status == Status.RUNNING ? System.nanoTime() : completedAtNanos;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                Math.max(0L, endNanos - startedAtNanos));
            return new JobSnapshot(id, status, startedAtMs, completedAtMs, elapsedMs,
                new ArrayList<>(progress), result, errorMessage);
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory
    {
        private final String prefix;
        private final AtomicInteger sequence = new AtomicInteger();

        NamedDaemonThreadFactory(String prefix)
        {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable)
        {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
