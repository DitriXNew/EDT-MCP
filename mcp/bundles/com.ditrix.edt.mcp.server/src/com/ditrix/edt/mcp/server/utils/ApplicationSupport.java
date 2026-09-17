/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Shared resolution for the application/infobase tools ({@code get_applications},
 * {@code update_database}, …): resolves an OPEN project plus the EDT
 * {@code IApplicationManager}, returning the same actionable errors those tools
 * used inline. The application-domain counterpart to
 * {@link ProjectContext#resolveConfiguration(String)}.
 *
 * <h2>Bounded application reads</h2>
 * {@code getApplication} / {@code getDefaultApplication} / {@code getApplications} have NO
 * overload taking an {@link org.eclipse.core.runtime.IProgressMonitor} or a timeout, and all
 * three funnel into {@code ApplicationManager.getApplications}, which loops the provision
 * delegates; the WST delegate reaches {@code ServerCore.getServers()} →
 * {@code ResourceManager.init()}, a {@code synchronized} method that runs arbitrary
 * {@code IStartup} contributions. A slow contribution parks that monitor and every later read
 * blocks on it UNINTERRUPTIBLY. The only way to bound such a read is therefore to move it off
 * the caller's thread — see {@link #readBounded}.
 */
public final class ApplicationSupport
{
    /**
     * Deadline for one application read on a caller that has no larger bounded phase of its own.
     *
     * <p>The same value {@code set_infobase_credentials} already bounds its
     * {@code getApplication} + store with: long enough that a healthy EDT — including one that
     * is still bringing its provision delegates up — always answers inside it, short enough that
     * an unattended call still gets an answer. Callers with a different contract (a tool that
     * advertises an answer in seconds, a best-effort cosmetic read) pass their own.
     */
    public static final long LOOKUP_TIMEOUT_MS = 30_000L;

    private ApplicationSupport()
    {
    }

    /**
     * Resolves an open project and the {@link IApplicationManager} service.
     *
     * @param projectName the MCP project name argument
     * @return a result carrying the project + manager on success, or the first
     *         matching error JSON (not found / closed / manager-unavailable)
     */
    public static ManagerResult resolveManager(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return new ManagerResult(null, null, ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return new ManagerResult(null, null,
                ToolResult.error("Project is closed: " + projectName).toJson()); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        IApplicationManager manager = Activator.getDefault().getApplicationManager();
        if (manager == null)
        {
            return new ManagerResult(project, null,
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }
        return new ManagerResult(project, manager, null);
    }

    /**
     * One {@link IApplicationManager} read, for {@link #readBounded} to run off the caller's
     * thread.
     *
     * @param <T> what the read returns
     */
    @FunctionalInterface
    public interface IApplicationRead<T>
    {
        /**
         * Performs the read.
         *
         * @return the manager's answer
         * @throws ApplicationException what the manager itself raises — reported as the read's
         *     failure, never converted into a deadline
         */
        T read() throws ApplicationException;
    }

    /**
     * Resolves one application by id under a caller-side deadline.
     *
     * @param manager the application manager (never {@code null}) — taken as a parameter, not
     *     looked up here, so a caller's test can drive this with a mock
     * @param project the project to resolve in
     * @param applicationId the application id
     * @param timeoutMs how long the CALLER waits
     * @return the bounded read; {@link BoundedRead#concluded()} first
     */
    public static BoundedRead<Optional<IApplication>> getApplicationBounded(IApplicationManager manager,
        IProject project, String applicationId, long timeoutMs)
    {
        return readBounded("Resolve EDT application: " + applicationId, //$NON-NLS-1$
            applicationTarget(applicationId), timeoutMs,
            () -> manager.getApplication(project, applicationId));
    }

    /**
     * Resolves the project's DEFAULT application under a caller-side deadline.
     *
     * <p><b>This read is not side-effect-free.</b> When the registered default is stale, EDT logs a
     * warning and calls {@code setDefaultApplication(project, null)}, notifying its listeners — so a
     * resolution this method abandoned at the deadline can still change the stored preference
     * afterwards. That is why an expired deadline means "unknown", never "the project has no
     * default application": the caller must not turn it into a not-found.
     *
     * @param manager the application manager (never {@code null})
     * @param project the project whose default application is resolved
     * @param timeoutMs how long the CALLER waits
     * @return the bounded read; {@link BoundedRead#concluded()} first
     */
    public static BoundedRead<Optional<IApplication>> getDefaultApplicationBounded(IApplicationManager manager,
        IProject project, long timeoutMs)
    {
        String projectName = projectName(project);
        return readBounded("Resolve default EDT application: " + projectName, //$NON-NLS-1$
            "the EDT default-application lookup for project '" + projectName + "'", //$NON-NLS-1$ //$NON-NLS-2$
            timeoutMs, () -> manager.getDefaultApplication(project));
    }

    /**
     * Lists the project's applications under a caller-side deadline.
     *
     * @param manager the application manager (never {@code null})
     * @param project the project to list
     * @param timeoutMs how long the CALLER waits
     * @return the bounded read; {@link BoundedRead#concluded()} first
     */
    public static BoundedRead<List<IApplication>> getApplicationsBounded(IApplicationManager manager,
        IProject project, long timeoutMs)
    {
        String projectName = projectName(project);
        return readBounded("List EDT applications: " + projectName, //$NON-NLS-1$
            "the EDT application list for project '" + projectName + "'", //$NON-NLS-1$ //$NON-NLS-2$
            timeoutMs, () -> manager.getApplications(project));
    }

    /**
     * Runs one application-manager read in a background {@link BoundedJob} and waits at most
     * {@code timeoutMs} for it.
     *
     * <p><b>The bound is on the CALLER, not on the work.</b> The platform blocks inside a
     * {@code synchronized} that no cancellation can preempt, so a read this method abandons keeps
     * running — possibly to completion, possibly with its own side effects. The caller gets an
     * answer on time; the read does not stop.
     *
     * <p>The caller's interrupt status is preserved: a thread that was already interrupted runs
     * the read exactly as the unbounded call did and comes back still interrupted, and a thread
     * interrupted while WAITING gets {@link BoundedJob.Outcome#INTERRUPTED} — which
     * {@link BoundedRead#interrupted()} separates from an expired deadline.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names, e.g. "the EDT application list for project 'P'"
     * @param timeoutMs how long the CALLER waits
     * @param read the manager read to run
     * @return the bounded read, never {@code null}
     */
    public static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs,
        IApplicationRead<T> read)
    {
        // A caller that arrives ALREADY interrupted must leave still interrupted. Measured, not
        // assumed: Job.join consumes a pending interrupt instead of raising, so without taking
        // and restoring the flag here, moving the read off this thread would silently disarm
        // every interruption check the caller makes afterwards.
        boolean interruptedOnEntry = Thread.interrupted();
        try
        {
            AtomicReference<T> value = new AtomicReference<>();
            BoundedJob.Result result =
                BoundedJob.run(jobName, timeoutMs, monitor -> value.set(read.read()));
            if (result.getOutcome() != BoundedJob.Outcome.COMPLETED)
            {
                // NOT_RUN lands here too: a read that never entered the work measured nothing.
                return new BoundedRead<>(null, null, result.getOutcome(),
                    lookupDeadlineFailure(target, timeoutMs, result));
            }
            return new BoundedRead<>(value.get(), result.getFailure(), result.getOutcome(), null);
        }
        finally
        {
            if (interruptedOnEntry)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The established per-{@link BoundedJob.Outcome} wording for an application read that did not
     * conclude. Shared so that every bounded application read diagnoses itself the same way — and
     * so that none of them can word a deadline as a measured "not found".
     *
     * @param target what the sentence names, e.g. "the EDT application lookup for application 'X'"
     * @param timeoutMs the deadline that expired
     * @param result the bounded outcome (never {@link BoundedJob.Outcome#COMPLETED} here)
     * @return the diagnosis sentence, never {@code null}
     */
    public static String lookupDeadlineFailure(String target, long timeoutMs, BoundedJob.Result result)
    {
        String deadline = timeoutMs % 1000L == 0L
            ? (timeoutMs / 1000L) + "s" : timeoutMs + "ms"; //$NON-NLS-1$ //$NON-NLS-2$
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT)
        {
            return target + " did not finish within " //$NON-NLS-1$
                + deadline + " and may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
        {
            return "the wait for " + target //$NON-NLS-1$
                + " was interrupted and the lookup may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            return target + " did not start within " //$NON-NLS-1$
                + deadline + "; retry when EDT's background Job queue is responsive"; //$NON-NLS-1$
        }
        return target + " never ran (" //$NON-NLS-1$
            + result.getOutcome() + ")"; //$NON-NLS-1$
    }

    /**
     * The {@code target} phrase naming one application lookup.
     *
     * @param applicationId the application id being looked up
     * @return the phrase for {@link #lookupDeadlineFailure}
     */
    public static String applicationTarget(String applicationId)
    {
        return "the EDT application lookup for application '" + applicationId + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String projectName(IProject project)
    {
        return project == null ? "<null>" : project.getName(); //$NON-NLS-1$
    }

    /**
     * Outcome of one bounded {@link IApplicationManager} read: the manager's answer, the failure it
     * raised, or the sentence saying the caller's deadline expired first.
     *
     * <p>Check {@link #concluded()} first. A read that did NOT conclude says nothing about the
     * application — it is "unknown", not "absent" — and {@link #valueOrRethrow()} refuses to hand
     * out a value for it rather than let a deadline pass for a measured not-found.
     *
     * @param <T> what the read returns
     */
    public static final class BoundedRead<T>
    {
        private final T value;
        private final Throwable failure;
        private final BoundedJob.Outcome outcome;
        private final String deadlineFailure;

        BoundedRead(T value, Throwable failure, BoundedJob.Outcome outcome, String deadlineFailure)
        {
            this.value = value;
            this.failure = failure;
            this.outcome = outcome;
            this.deadlineFailure = deadlineFailure;
        }

        /** @return {@code true} when the read returned or raised before the deadline. */
        public boolean concluded()
        {
            return deadlineFailure == null;
        }

        /** @return how the bounded run ended; {@code COMPLETED} exactly when {@link #concluded()}. */
        public BoundedJob.Outcome outcome()
        {
            return outcome;
        }

        /**
         * Whether the caller's WAIT was interrupted rather than its deadline expiring.
         *
         * <p>A caller that already distinguishes "cut short" from "failed" needs this: an
         * interrupted wait is the caller's own doing and nothing failed, so blaming it on an
         * EDT failure would send the reader to a log entry nobody wrote.
         *
         * @return {@code true} only for {@link BoundedJob.Outcome#INTERRUPTED}
         */
        public boolean interrupted()
        {
            return outcome == BoundedJob.Outcome.INTERRUPTED;
        }

        /** @return the deadline diagnosis, or {@code null} when the read {@link #concluded()}. */
        public String deadlineFailure()
        {
            return deadlineFailure;
        }

        /** @return what the read raised, or {@code null} when it returned or did not conclude. */
        public Throwable failure()
        {
            return failure;
        }

        /**
         * @return the manager's answer
         * @throws IllegalStateException when the read did not conclude — the caller must branch on
         *     {@link #concluded()} first, because there is no value to stand in for a deadline
         */
        public T valueOrRethrow()
        {
            if (deadlineFailure != null)
            {
                throw new IllegalStateException(deadlineFailure);
            }
            if (failure instanceof RuntimeException)
            {
                // The manager's own ApplicationException, surfaced exactly as the unbounded call
                // surfaced it — a raised read is a conclusion, not a deadline.
                throw (RuntimeException)failure;
            }
            if (failure != null)
            {
                throw new IllegalStateException(failure);
            }
            return value;
        }
    }

    /**
     * Outcome of resolving an open project + its {@link IApplicationManager}: either
     * the project and manager, or an actionable error JSON. Check {@link #ok()} first;
     * on failure return {@link #errorJson()} verbatim.
     */
    public static final class ManagerResult
    {
        private final IProject project;
        private final IApplicationManager manager;
        private final String errorJson;

        private ManagerResult(IProject project, IApplicationManager manager, String errorJson)
        {
            this.project = project;
            this.manager = manager;
            this.errorJson = errorJson;
        }

        /** @return {@code true} when the project and manager resolved (no error). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /** @return the resolved project (may be {@code null} on a not-found/closed error). */
        public IProject project()
        {
            return project;
        }

        /** @return the resolved application manager, or {@code null} on error. */
        public IApplicationManager manager()
        {
            return manager;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }
    }
}
