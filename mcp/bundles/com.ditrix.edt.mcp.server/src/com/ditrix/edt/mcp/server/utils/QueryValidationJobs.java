/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.eclipse.xtext.util.CancelIndicator;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HeadlessLifetime.Lease;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressEntry;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.Status;

/**
 * Dedicated, admission-bounded executor for the read tool. Ordinary calls own no job record visible to
 * the polling tools. Detachment and callback exit are serialized; only detached callbacks use
 * this separate registry, and their slot stays occupied until their Java runnable actually exits.
 */
public final class QueryValidationJobs implements AutoCloseable
{
    public static final String TOOL = "validate_query"; //$NON-NLS-1$
    private static final long INTERRUPT_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static QueryValidationJobs shared = new QueryValidationJobs(2, 32);

    private final ThreadPoolExecutor workers;
    private final int limit;
    private final int maxCompleted;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<String, Task> detached = new LinkedHashMap<>();
    private volatile boolean closed;
    private int inFlight;

    public record Result(String response, String jobId) { }

    public QueryValidationJobs(int threadCount, int maxCompleted)
    {
        this(threadCount, maxCompleted, System::nanoTime);
    }

    QueryValidationJobs(int threadCount, int maxCompleted, LongSupplier nanoTime)
    {
        limit = threadCount;
        this.maxCompleted = Math.max(1, maxCompleted);
        this.nanoTime = nanoTime;
        // The queue covers executor handoff after a logical slot is released but before the
        // underlying worker asks for its next task. It does not add admission capacity.
        workers = new ThreadPoolExecutor(threadCount, threadCount, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(threadCount), task -> {
            Thread thread = new Thread(task, "MCP-Query-Validation"); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        });
        workers.allowCoreThreadTimeOut(true);
    }

    public static synchronized QueryValidationJobs shared()
    {
        return shared;
    }

    /** Only bundle activation can reopen this lane; late calls during stop see the closed instance. */
    public static synchronized void startShared()
    {
        if (shared.closed)
        {
            shared = new QueryValidationJobs(2, 32);
        }
    }

    public static synchronized void shutdownShared()
    {
        shared.close();
    }

    /** Returns the original response, or the same id the polling tools see if work is still active. */
    public Result run(String projectName, long timeoutMs, BooleanSupplier responseDelivered,
        Function<CancelIndicator, String> action)
    {
        if (Thread.currentThread().isInterrupted() || responseDelivered.getAsBoolean())
        {
            return new Result(null, null);
        }
        long startNanos = nanoTime.getAsLong();
        long startMs = System.currentTimeMillis();
        var activator = Activator.getDefault();
        var lifetime = activator == null ? null : activator.getHeadlessLifetime();
        var lease = lifetime == null ? null : lifetime.tool();
        if (lifetime != null && lease == null)
        {
            throw new RejectedExecutionException("Headless EDT is shutting down."); //$NON-NLS-1$
        }
        Task task = new Task(projectName, startNanos, startMs, TimeUnit.MILLISECONDS.toNanos(timeoutMs), action,
            lease);
        try
        {
            synchronized (this)
            {
                if (closed)
                {
                    throw new RejectedExecutionException("Query validation is shutting down."); //$NON-NLS-1$
                }
                if (inFlight >= limit)
                {
                    throw new RejectedExecutionException("Query validation capacity is occupied."); //$NON-NLS-1$
                }
                inFlight++;
                try
                {
                    workers.execute(task);
                }
                catch (RuntimeException | Error e)
                {
                    inFlight--;
                    throw e;
                }
            }
        }
        catch (RuntimeException | Error e)
        {
            if (lease != null)
            {
                lease.close();
            }
            throw e;
        }
        boolean interrupted = false;
        try
        {
            synchronized (this)
            {
                while (!task.finished() && !closed && !responseDelivered.getAsBoolean())
                {
                    long remaining = task.remaining();
                    if (remaining <= 0)
                    {
                        break;
                    }
                    try
                    {
                        TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining, INTERRUPT_POLL_NANOS));
                    }
                    catch (InterruptedException e)
                    {
                        interrupted = true;
                        break;
                    }
                }
                if (!interrupted && !closed && task.finished() && task.finishedInTime)
                {
                    if (task.failure != null)
                    {
                        throw task.failure;
                    }
                    return new Result(task.response, null);
                }
                if (closed || task.finished())
                {
                    return new Result(null, null);
                }
                // An early reply is committed here, under the same lock used at callback exit.
                // A worker already done is not promoted. Once published, the id never changes.
                if (!task.started)
                {
                    task.cancel();
                    return new Result(null, null);
                }
                detached.put(task.id, task);
                task.cancel();
                return new Result(null, task.id);
            }
        }
        finally
        {
            if (interrupted)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized JobSnapshot await(String id, long waitMs)
    {
        Task task = detached.get(id);
        if (task == null)
        {
            return null;
        }
        long start = nanoTime.getAsLong();
        long budget = TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!task.finished() && !closed)
        {
            long remaining = budget - (nanoTime.getAsLong() - start);
            if (remaining <= 0)
            {
                break;
            }
            try
            {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return task.snapshot();
    }

    public synchronized CancellationResult cancel(String id)
    {
        Task task = detached.get(id);
        if (task == null)
        {
            return null;
        }
        if (task.finished())
        {
            return new CancellationResult(CancellationOutcome.ALREADY_TERMINAL, task.snapshot(), null);
        }
        task.cancel();
        return new CancellationResult(CancellationOutcome.CANCELLED, task.snapshot(), null);
    }

    public synchronized List<JobSnapshot> running()
    {
        return detached.values().stream().filter(t -> !t.finished()).map(Task::snapshot).toList();
    }

    public synchronized JobSnapshot runningForProject(String projectName)
    {
        return detached.values().stream().filter(t -> !t.finished() && t.projectName.equals(projectName))
            .findFirst().map(Task::snapshot).orElse(null);
    }

    @Override
    public synchronized void close()
    {
        closed = true;
        for (Runnable queued : workers.shutdownNow())
        {
            ((Task)queued).discard();
        }
        notifyAll();
    }

    /** Lifecycle is guarded by the registry lock; result fields are handed off at worker exit. */
    private final class Task implements Runnable
    {
        private final String id = UUID.randomUUID().toString();
        private final String projectName;
        private final long startedMs;
        private final long startedNanos;
        private final Lease lease;
        private final long budgetNanos;
        private volatile boolean cancellationRequested;
        private final FutureTask<Void> invocation;
        private boolean started;
        private boolean exited;
        private long cancellationAtMs;
        private long completedAtMs;
        private long elapsedAtExit;
        private boolean finishedInTime;
        private String response;
        private RuntimeException failure;

        Task(String projectName, long startedNanos, long startedMs, long budgetNanos,
            Function<CancelIndicator, String> action, Lease lease)
        {
            this.projectName = projectName;
            this.startedNanos = startedNanos;
            this.startedMs = startedMs;
            this.budgetNanos = budgetNanos;
            this.lease = lease;
            invocation = new FutureTask<>(() -> {
                try
                {
                    CancelIndicator cancel = () -> closed || cancellationRequested || remaining() <= 0
                        || Thread.currentThread().isInterrupted();
                    if (!cancel.isCanceled())
                    {
                        response = action.apply(cancel);
                    }
                }
                catch (RuntimeException e)
                {
                    failure = e;
                }
                catch (Error e)
                {
                    failure = new IllegalStateException("EDT validator failed: " + e.getClass().getSimpleName(), e); //$NON-NLS-1$
                    Activator.logError("Native query validation failed", e); //$NON-NLS-1$
                }
                return null;
            });
        }

        long remaining()
        {
            return budgetNanos - (nanoTime.getAsLong() - startedNanos);
        }

        boolean finished()
        {
            return exited;
        }

        @Override
        public void run()
        {
            try (lease)
            {
                synchronized (QueryValidationJobs.this)
                {
                    if (closed)
                    {
                        cancel();
                    }
                    started = !cancellationRequested;
                }
                invocation.run(); // Future.cancel can be terminal while this call remains inside EDT.
            }
            finally
            {
                finish();
            }
        }

        /** Called only on a queued task returned by shutdownNow, so its runnable never runs. */
        void discard()
        {
            try (lease)
            {
                cancel();
            }
            finally
            {
                finish();
            }
        }

        private void finish()
        {
            long elapsed = nanoTime.getAsLong() - startedNanos;
            synchronized (QueryValidationJobs.this)
            {
                exited = true;
                inFlight--;
                elapsedAtExit = elapsed;
                completedAtMs = System.currentTimeMillis();
                finishedInTime = elapsed < budgetNanos;
                if (cancellationRequested || !finishedInTime)
                {
                    response = null;
                    failure = null;
                }
                trimCompleted();
                QueryValidationJobs.this.notifyAll();
            }
        }

        void cancel()
        {
            if (!cancellationRequested)
            {
                cancellationAtMs = System.currentTimeMillis();
                cancellationRequested = true;
                invocation.cancel(true); // Only interrupts this invocation, never a recycled worker.
            }
        }

        JobSnapshot snapshot()
        {
            List<ProgressEntry> progress = new ArrayList<>();
            progress.add(new ProgressEntry(startedMs, "Validate queries in project '" + projectName + "'.")); //$NON-NLS-1$ //$NON-NLS-2$
            progress.add(new ProgressEntry(cancellationAtMs, "Cancellation requested; wait for native EDT work to exit.")); //$NON-NLS-1$
            long elapsed = exited ? elapsedAtExit : nanoTime.getAsLong() - startedNanos;
            return new JobSnapshot(id, TOOL, exited ? Status.CANCELLED : Status.RUNNING, startedMs,
                completedAtMs, TimeUnit.NANOSECONDS.toMillis(elapsed), progress, null, null, null, null,
                !exited, false);
        }
    }

    private void trimCompleted()
    {
        long completed = detached.values().stream().filter(Task::finished).count();
        var entries = detached.values().iterator();
        while (completed > maxCompleted && entries.hasNext())
        {
            if (entries.next().finished())
            {
                entries.remove();
                completed--;
            }
        }
    }
}
