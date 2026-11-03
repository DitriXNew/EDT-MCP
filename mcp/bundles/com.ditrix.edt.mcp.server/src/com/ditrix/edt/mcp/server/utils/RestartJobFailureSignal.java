/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

import com._1c.g5.v8.dt.core.lifecycle.ProjectRestartJob;
import com.ditrix.edt.mcp.server.Activator;

/** Observes the first non-OK EDT restart completion while a clean is in flight. */
public class RestartJobFailureSignal implements AutoCloseable
{
    private final AtomicReference<IStatus> failure = new AtomicReference<>();
    private final CountDownLatch failed = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final IJobManager manager;
    private final Predicate<Job> isRestartJob;
    private final JobChangeAdapter listener;

    public RestartJobFailureSignal()
    {
        this(Job.getJobManager(), RestartJobFailureSignal::isRestartJob);
    }

    /** Injectable classification avoids localized job names and permits real headless marker jobs. */
    public RestartJobFailureSignal(IJobManager manager, Predicate<Job> isRestartJob)
    {
        this.manager = manager;
        this.isRestartJob = isRestartJob;
        listener = new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                IStatus result = event.getResult();
                if (isRestartJob.test(event.getJob()) && result != null && !result.isOK()
                    && failure.compareAndSet(null, result))
                {
                    failed.countDown();
                    String outcome = result.getSeverity() == IStatus.CANCEL ? "was cancelled" //$NON-NLS-1$
                        : result.getSeverity() == IStatus.ERROR ? "failed" : "did not complete"; //$NON-NLS-1$ //$NON-NLS-2$
                    Activator.logWarning("EDT project services restart " + outcome + ": " + getFailure()); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        };
        manager.addJobChangeListener(listener);
    }

    static boolean isRestartJob(Job job)
    {
        return job instanceof ProjectRestartJob;
    }

    public boolean hasFailed()
    {
        return failure.get() != null;
    }

    public String getFailure()
    {
        IStatus status = failure.get();
        return status == null ? null : describe(status);
    }

    /** Severity and text belong to the same first non-OK completion, including cancellation. */
    public int getSeverity()
    {
        IStatus status = failure.get();
        return status == null ? IStatus.OK : status.getSeverity();
    }

    /** Uses exactly the classification used by the failure listener, including restart subclasses. */
    public boolean hasPendingRestartJobs()
    {
        return hasPendingRestartJobs(manager, isRestartJob);
    }

    /**
     * Non-blocking workspace-wide snapshot; does not identify which project's context is restarting
     * and does not install a failure listener.
     */
    public static boolean anyRestartJobPending()
    {
        return hasPendingRestartJobs(Job.getJobManager(), RestartJobFailureSignal::isRestartJob);
    }

    private static boolean hasPendingRestartJobs(IJobManager manager, Predicate<Job> isRestartJob)
    {
        for (Job job : manager.find(null))
        {
            if (isRestartJob.test(job) && job.getState() != Job.NONE)
            {
                // Delayed (SLEEPING) restart jobs also still own work that must not overlap recovery.
                return true;
            }
        }
        return false;
    }

    public boolean await(long timeoutMs) throws InterruptedException
    {
        return failed.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    private static String describe(IStatus status)
    {
        StringBuilder text = new StringBuilder(status.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = status.getException(); cause != null && seen.add(cause); cause = cause.getCause())
        {
            String message = cause.getMessage();
            if (message != null && !message.isBlank() && text.indexOf(message) < 0)
            {
                text.append(": ").append(message); //$NON-NLS-1$
            }
        }
        return text.toString();
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            try
            {
                manager.removeJobChangeListener(listener);
            }
            catch (RuntimeException e)
            {
                closed.set(false);
                throw e;
            }
        }
    }
}
