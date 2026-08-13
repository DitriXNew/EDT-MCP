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
    }

    /** Work submitted to the registry. */
    @FunctionalInterface
    public interface JobWork
    {
        Object run(ProgressReporter progress) throws Exception; // NOSONAR arbitrary background work
    }

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
            jobs.put(record.id, record);
        }

        FutureTask<Void> task = new FutureTask<>(() -> {
            runWork(record, work);
            return null;
        });
        record.setWorkFuture(task);

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
            task.cancel(true);
            throw e;
        }
        return record.snapshot();
    }

    /** @return current job snapshot, or {@code null} when the id is unknown/evicted */
    /**
     * Counts jobs that are still running.
     * <p>
     * Exists so a caller can bound how many of ITS jobs are in flight at once. That matters
     * for work that can start more of itself: a job holds one of the shared workers for its
     * whole life, so an unbounded chain of nested starts would park the pool.
     *
     * @return number of jobs currently in {@link Status#RUNNING}
     */
    public int runningCount()
    {
        synchronized (jobsLock)
        {
            return (int)jobs.values().stream()
                .filter(record -> record.snapshot().getStatus() == Status.RUNNING)
                .count();
        }
    }

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
            record.fail("EDT-MCP is stopping; the background job was cancelled."); //$NON-NLS-1$
            record.cancelWork();
        }

        deadlines.shutdownNow();
        workers.shutdownNow();
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
            Object result = work.run(record::addProgress);
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
        if (record.fail("Job exceeded its total timeoutSeconds budget of " //$NON-NLS-1$
            + timeout + ". Start a new job with a larger timeoutSeconds value, or check " //$NON-NLS-1$
            + "Workmate and network status.")) //$NON-NLS-1$
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
        private long completedAtMs;
        private long completedAtNanos;
        private Object result;
        private String errorMessage;
        private Future<?> workFuture;
        private ScheduledFuture<?> deadlineFuture;

        JobRecord(String id, String initialProgress)
        {
            this.id = id;
            addProgress(initialProgress);
        }

        synchronized boolean isRunning()
        {
            return status == Status.RUNNING;
        }

        synchronized void setWorkFuture(Future<?> future)
        {
            workFuture = future;
            if (status != Status.RUNNING)
            {
                future.cancel(true);
            }
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
            ScheduledFuture<?> deadline;
            synchronized (this)
            {
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

        void cancelWork()
        {
            Future<?> future;
            synchronized (this)
            {
                future = workFuture;
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
