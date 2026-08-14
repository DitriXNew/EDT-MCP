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
        FAILED("failed"), //$NON-NLS-1$
        CANCELLED("cancelled"); //$NON-NLS-1$

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

    /** Result of an explicit cancellation request. */
    public enum CancellationOutcome
    {
        /** The job was stopped before its work crossed the commit handshake. */
        CANCELLED("cancelled"), //$NON-NLS-1$
        /** The owning tool stopped committed work through its declared cancellation capability. */
        TERMINATED("terminated"), //$NON-NLS-1$
        /** The owning tool already handed the request over, so it cannot be recalled. */
        ALREADY_COMMITTED("alreadyCommitted"), //$NON-NLS-1$
        /** The job was already done, failed, or cancelled when the request arrived. */
        ALREADY_TERMINAL("alreadyTerminal"); //$NON-NLS-1$

        private final String value;

        CancellationOutcome(String value)
        {
            this.value = value;
        }

        /** @return stable value used in tool output */
        public String value()
        {
            return value;
        }
    }

    /** Result returned by an owning tool's committed-work cancellation handler. */
    public static final class CommittedCancellation
    {
        private final boolean stopped;
        private final String message;
        private final Object result;

        private CommittedCancellation(boolean stopped, String message, Object result)
        {
            this.stopped = stopped;
            this.message = message;
            this.result = result;
        }

        /** Reports that the owning tool really stopped the committed work. */
        public static CommittedCancellation stopped(String message, Object result)
        {
            return new CommittedCancellation(true, message, result);
        }

        /** Reports that the owning tool could not stop the committed work. */
        public static CommittedCancellation notStopped(String message)
        {
            return new CommittedCancellation(false, message, null);
        }
    }

    /** Stops work that has crossed the generic commit handshake, when the owner supports it. */
    @FunctionalInterface
    public interface CommittedCancellationHandler
    {
        CommittedCancellation cancel() throws Exception; // NOSONAR owner-specific boundary
    }

    /**
     * Explicit opt-in capability supplied by a job owner at start time.
     * <p>
     * Most committed work cannot be recalled and supplies no capability. An owner whose committed
     * operation can still be stopped destructively supplies both the consent-preview warning and
     * the handler that performs and verifies that stop. The registry never infers capability from
     * a tool name.
     */
    public static final class CancellationCapability
    {
        private final String previewWarning;
        private final CommittedCancellationHandler handler;

        private CancellationCapability(String previewWarning,
            CommittedCancellationHandler handler)
        {
            this.previewWarning = previewWarning;
            this.handler = handler;
        }

        public static CancellationCapability of(String previewWarning,
            CommittedCancellationHandler handler)
        {
            if (previewWarning == null || previewWarning.isBlank())
            {
                throw new IllegalArgumentException(
                    "Cancellation capability preview must not be blank"); //$NON-NLS-1$
            }
            if (handler == null)
            {
                throw new IllegalArgumentException(
                    "Cancellation capability handler must not be null"); //$NON-NLS-1$
            }
            return new CancellationCapability(previewWarning, handler);
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
        private final String owningTool;
        private final Status status;
        private final long startedAtMs;
        private final long completedAtMs;
        private final long elapsedMs;
        private final List<ProgressEntry> progress;
        private final Object result;
        private final String errorMessage;
        private final String cancellationPreview;

        JobSnapshot(String id, String owningTool, Status status, long startedAtMs, long completedAtMs,
            long elapsedMs, List<ProgressEntry> progress, Object result, String errorMessage,
            String cancellationPreview)
        {
            this.id = id;
            this.owningTool = owningTool;
            this.status = status;
            this.startedAtMs = startedAtMs;
            this.completedAtMs = completedAtMs;
            this.elapsedMs = elapsedMs;
            this.progress = Collections.unmodifiableList(progress);
            this.result = result;
            this.errorMessage = errorMessage;
            this.cancellationPreview = cancellationPreview;
        }

        public String getId()
        {
            return id;
        }

        /** @return MCP tool that created and owns this job */
        public String getOwningTool()
        {
            return owningTool;
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

        /** @return destructive committed-cancellation warning, or {@code null} when unsupported */
        public String getCancellationPreview()
        {
            return cancellationPreview;
        }
    }

    /** Atomic cancellation decision together with the resulting job snapshot. */
    public static final class CancellationResult
    {
        private final CancellationOutcome outcome;
        private final JobSnapshot snapshot;
        private final String detail;

        CancellationResult(CancellationOutcome outcome, JobSnapshot snapshot, String detail)
        {
            this.outcome = outcome;
            this.snapshot = snapshot;
            this.detail = detail;
        }

        public CancellationOutcome getOutcome()
        {
            return outcome;
        }

        public JobSnapshot getSnapshot()
        {
            return snapshot;
        }

        /** @return owner-specific explanation when committed cancellation was attempted */
        public String getDetail()
        {
            return detail;
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
         * because generic deadline/interruption cancellation no longer acts on it. A job owner
         * may separately declare a {@link CancellationCapability} at start time for committed
         * work that can still be stopped destructively after explicit user consent. That opt-in
         * does not weaken this handshake for jobs without the capability, and the registry never
         * guesses it from a tool name.
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

    /** Registry-internal cancellation decision plus any owner-specific explanation. */
    private static final class CancellationAttempt
    {
        final CancellationOutcome outcome;
        final String detail;
        final CommittedCancellationHandler handler;

        CancellationAttempt(CancellationOutcome outcome, String detail)
        {
            this.outcome = outcome;
            this.detail = detail;
            handler = null;
        }

        CancellationAttempt(CommittedCancellationHandler handler)
        {
            outcome = null;
            detail = null;
            this.handler = handler;
        }
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
     * @param owningTool MCP tool that creates and owns the job
     * @param timeoutMs total job budget in milliseconds
     * @param initialProgress first domain-specific progress message
     * @param work work to execute off the caller thread
     * @return initial snapshot, usually {@link Status#RUNNING}
     * @throws RejectedExecutionException when the registry is stopped or full of running jobs
     */
    public JobSnapshot start(String owningTool, long timeoutMs, String initialProgress, JobWork work)
    {
        return start(owningTool, timeoutMs, Integer.MAX_VALUE, initialProgress, null, work);
    }

    /**
     * Starts a job whose owner explicitly supports destructive cancellation after commit.
     *
     * @param owningTool MCP tool that creates and owns the job
     * @param timeoutMs total job budget in milliseconds
     * @param initialProgress first domain-specific progress message
     * @param cancellation owner-declared committed-work cancellation capability
     * @param work work to execute off the caller thread
     * @return initial snapshot, usually {@link Status#RUNNING}
     */
    public JobSnapshot start(String owningTool, long timeoutMs, String initialProgress,
        CancellationCapability cancellation, JobWork work)
    {
        return start(owningTool, timeoutMs, Integer.MAX_VALUE, initialProgress, cancellation, work);
    }

    /** Package-local compatibility overload for lifecycle tests; production callers name an owner. */
    JobSnapshot start(long timeoutMs, String initialProgress, JobWork work)
    {
        return start("background_jobs_test", timeoutMs, initialProgress, work); //$NON-NLS-1$
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
     * @param owningTool MCP tool that creates and owns the job
     * @param timeoutMs total job budget in milliseconds
     * @param maxRunning admit only while strictly fewer jobs are running
     * @param initialProgress first domain-specific progress message
     * @param work work to execute off the caller thread
     * @return the initial snapshot, or {@code null} when the running limit is reached
     * @throws RejectedExecutionException when the registry is stopped or full
     */
    public JobSnapshot start(String owningTool, long timeoutMs, int maxRunning,
        String initialProgress, JobWork work)
    {
        return start(owningTool, timeoutMs, maxRunning, initialProgress, null, work);
    }

    private JobSnapshot start(String owningTool, long timeoutMs, int maxRunning,
        String initialProgress, CancellationCapability cancellation, JobWork work)
    {
        if (owningTool == null || owningTool.isBlank())
        {
            throw new IllegalArgumentException("Background job owning tool must not be blank"); //$NON-NLS-1$
        }
        if (work == null)
        {
            throw new IllegalArgumentException("Background job work must not be null"); //$NON-NLS-1$
        }
        long boundedTimeoutMs = Math.max(1L, timeoutMs);
        JobRecord record = new JobRecord(UUID.randomUUID().toString(), owningTool,
            initialProgress, cancellation);

        synchronized (jobsLock)
        {
            ensureOpen();
            // The running limit is checked FIRST, before anything is discarded: eviction makes
            // room for a job that is about to be stored, and a start rejected here stores
            // nothing. Evicting on the way to a refusal would throw away a completed job's
            // result for nobody, and its owner - still polling by id - would be told the job
            // never existed.
            if (inFlight.get() >= maxRunning)
            {
                return null;
            }
            evictOldestCompleted();
            if (jobs.size() >= maxStoredJobs)
            {
                throw new RejectedExecutionException("Background job registry is full with " //$NON-NLS-1$
                    + maxStoredJobs + " running jobs"); //$NON-NLS-1$
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

    /** Package-local compatibility overload for lifecycle tests; production callers name an owner. */
    JobSnapshot start(long timeoutMs, int maxRunning, String initialProgress, JobWork work)
    {
        return start("background_jobs_test", timeoutMs, maxRunning, initialProgress, work); //$NON-NLS-1$
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

    /**
     * Requests cancellation through the same commit handshake used by deadlines.
     * <p>
     * The decision is atomic with {@link ProgressReporter#tryCommit()}: either cancellation
     * wins while the work can still be abandoned, or the work has already been handed to an
     * external service and this method reports {@link CancellationOutcome#ALREADY_COMMITTED}
     * without interrupting it. The only exception is an explicit
     * {@link CancellationCapability} declared by the owner when the job starts: after consent,
     * that handler may stop committed work and produce {@link CancellationOutcome#TERMINATED}.
     * The registry never infers this from the owning tool's name. Reporting unsupported committed
     * work as cancelled would invite a retry of work that is already in flight.
     *
     * @param jobId job identifier
     * @return the cancellation decision and latest snapshot, or {@code null} for an unknown id
     */
    public CancellationResult cancel(String jobId)
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

        CancellationAttempt attempt = record.cancel();
        if (attempt.handler != null)
        {
            // Throwable, not Exception. Claiming the cancellation deferred the worker's terminal
            // outcome, so this side now OWES a publish: without one the flag stays raised, the
            // deferred outcome is never released, the job is stuck at RUNNING for good and its
            // completion latch never opens - every later await burns its whole budget. That is
            // strictly worse than the failure it would be reporting. An Error is reachable here
            // and not theoretical: this is OSGi with lazy classloading and the owner's handler
            // reads a report off disk, which is why runJob already catches LinkageError on the
            // neighbouring path. Rethrowing after publishing keeps a real VM error visible
            // instead of dressing it up as an ordinary handler failure.
            try
            {
                attempt = record.publishCommittedCancellation(attempt.handler.cancel(), null);
            }
            catch (Exception e) // NOSONAR owner cancellation failure must remain an honest outcome
            {
                String detail = "The owning tool's cancellation handler failed, so the committed " //$NON-NLS-1$
                    + "work was NOT reported as stopped: " + failureMessage(e); //$NON-NLS-1$
                attempt = record.publishCommittedCancellation(null, detail);
            }
            catch (Throwable t) // NOSONAR the claim must be released even for an Error
            {
                record.publishCommittedCancellation(null,
                    "The owning tool's cancellation handler failed, so the committed " //$NON-NLS-1$
                        + "work was NOT reported as stopped: " + failureMessage(t)); //$NON-NLS-1$
                throw t;
            }
        }
        if (attempt.outcome == CancellationOutcome.CANCELLED
            || attempt.outcome == CancellationOutcome.TERMINATED)
        {
            record.cancelWork();
        }
        return new CancellationResult(attempt.outcome, record.snapshot(), attempt.detail);
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
        private final String owningTool;
        private final CancellationCapability cancellation;
        private final long startedAtMs = System.currentTimeMillis();
        private final long startedAtNanos = System.nanoTime();
        private final List<ProgressEntry> progress = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);

        private Status status = Status.RUNNING;
        /** Set by the work once its request can no longer be taken back. */
        private boolean committed;
        /** Owner cancellation is claimed; its handler is running without this record's monitor. */
        private boolean cancellationInProgress;
        /** Terminal worker outcome held until an owner cancellation attempt is published. */
        private PendingTerminalOutcome pendingTerminalOutcome;
        private Object pendingResult;
        private String pendingErrorMessage;
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

        JobRecord(String id, String owningTool, String initialProgress,
            CancellationCapability cancellation)
        {
            this.id = id;
            this.owningTool = owningTool;
            this.cancellation = cancellation;
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
                if (cancellationInProgress)
                {
                    pendingTerminalOutcome = PendingTerminalOutcome.DONE;
                    pendingResult = completedResult;
                    return true;
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

        /**
         * Atomically cancels uncommitted work, or claims the owner's explicit destructive
         * cancellation capability for committed work. The caller invokes a claimed handler
         * without this record's monitor and publishes its outcome through
         * {@link #publishCommittedCancellation(CommittedCancellation, String)}.
         *
         * @return the honest outcome of this cancellation request
         */
        synchronized CancellationAttempt cancel()
        {
            if (status != Status.RUNNING)
            {
                return new CancellationAttempt(CancellationOutcome.ALREADY_TERMINAL, null);
            }
            if (committed)
            {
                if (cancellation != null)
                {
                    if (cancellationInProgress)
                    {
                        // Its own wording, not the generic one: another cancellation is running
                        // RIGHT NOW and may yet stop this job, so telling the caller the work
                        // "cannot be recalled" would be false. This request did nothing, which
                        // is all it may claim.
                        return new CancellationAttempt(CancellationOutcome.ALREADY_COMMITTED,
                            "A cancellation of this job is already in progress and has not " //$NON-NLS-1$
                                + "finished, so this request did nothing and did not start a " //$NON-NLS-1$
                                + "second one."); //$NON-NLS-1$
                    }
                    cancellationInProgress = true;
                    return new CancellationAttempt(cancellation.handler);
                }
                addProgress("Cancellation was requested, but the owning tool had already " //$NON-NLS-1$
                    + "handed the work over and it cannot be recalled."); //$NON-NLS-1$
                return new CancellationAttempt(CancellationOutcome.ALREADY_COMMITTED, null);
            }

            progress.add(new ProgressEntry(System.currentTimeMillis(),
                "Cancelled before the owning tool handed the work over.")); //$NON-NLS-1$
            markCancelled(null);
            return new CancellationAttempt(CancellationOutcome.CANCELLED, null);
        }

        /** Publishes a claimed owner cancellation after its handler ran without this monitor. */
        synchronized CancellationAttempt publishCommittedCancellation(
            CommittedCancellation stopped, String failureDetail)
        {
            cancellationInProgress = false;
            if (failureDetail != null)
            {
                addProgress(failureDetail);
                publishPendingTerminalOutcome();
                return new CancellationAttempt(CancellationOutcome.ALREADY_COMMITTED,
                    failureDetail);
            }
            if (stopped == null)
            {
                String detail = "The owning tool's cancellation handler returned no outcome, so " //$NON-NLS-1$
                    + "the committed work was NOT reported as stopped."; //$NON-NLS-1$
                addProgress(detail);
                publishPendingTerminalOutcome();
                return new CancellationAttempt(CancellationOutcome.ALREADY_COMMITTED, detail);
            }
            if (!stopped.stopped)
            {
                addProgress(stopped.message);
                publishPendingTerminalOutcome();
                return new CancellationAttempt(CancellationOutcome.ALREADY_COMMITTED,
                    stopped.message);
            }

            progress.add(new ProgressEntry(System.currentTimeMillis(),
                "The owning tool stopped the committed work through its declared cancellation " //$NON-NLS-1$
                    + "capability.")); //$NON-NLS-1$
            markCancelled(stopped.result);
            return new CancellationAttempt(CancellationOutcome.TERMINATED, stopped.message);
        }

        private void markCancelled(Object cancellationResult)
        {
            clearPendingTerminalOutcome();
            status = Status.CANCELLED;
            result = cancellationResult;
            completedAtMs = System.currentTimeMillis();
            completedAtNanos = System.nanoTime();
            if (deadlineFuture != null)
            {
                deadlineFuture.cancel(false);
            }
            completed.countDown();
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
                if (cancellationInProgress)
                {
                    pendingTerminalOutcome = PendingTerminalOutcome.FAILED;
                    pendingErrorMessage = message;
                    return true;
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

        /** Publishes work that finished while the owner cancellation handler was running. */
        private void publishPendingTerminalOutcome()
        {
            if (pendingTerminalOutcome == null)
            {
                return;
            }
            if (pendingTerminalOutcome == PendingTerminalOutcome.DONE)
            {
                status = Status.DONE;
                result = pendingResult;
            }
            else
            {
                progress.add(new ProgressEntry(System.currentTimeMillis(),
                    "Failed: " + pendingErrorMessage)); //$NON-NLS-1$
                status = Status.FAILED;
                errorMessage = pendingErrorMessage;
            }
            completedAtMs = System.currentTimeMillis();
            completedAtNanos = System.nanoTime();
            if (deadlineFuture != null)
            {
                deadlineFuture.cancel(false);
            }
            clearPendingTerminalOutcome();
            completed.countDown();
        }

        private void clearPendingTerminalOutcome()
        {
            pendingTerminalOutcome = null;
            pendingResult = null;
            pendingErrorMessage = null;
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
            return new JobSnapshot(id, owningTool, status, startedAtMs, completedAtMs, elapsedMs,
                new ArrayList<>(progress), result, errorMessage,
                cancellation != null ? cancellation.previewWarning : null);
        }
    }

    /** Worker terminal state deferred while committed cancellation is being attempted. */
    private enum PendingTerminalOutcome
    {
        DONE,
        FAILED
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
