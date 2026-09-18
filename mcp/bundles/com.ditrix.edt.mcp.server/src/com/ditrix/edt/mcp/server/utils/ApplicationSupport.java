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
 *
 * <h2>None of the three reads is side-effect-free</h2>
 * Read off EDT's own sources, not assumed. All three end in the infobase provision delegate's
 * {@code initIfNeeded}, which ASSIGNS a debug port to an application that has none: it scans up
 * to a hundred candidate ports by actually BINDING a {@code ServerSocket} on each, then stores
 * the winner through {@code setAttribute}, which flushes the preference store. The
 * default-application read adds one more — a registered default that no longer resolves is
 * cleared with an unconditional {@code setDefaultApplication(project, null)} remove.
 *
 * <p>So an abandoned read is not merely a question left unanswered: it can bind sockets, persist
 * a debug port and erase a stored default AFTER the caller has returned. Every message about an
 * expired deadline here therefore says the state MAY HAVE BEEN MUTATED — never that nothing
 * happened.
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
     * <p><b>Not side-effect-free</b> (see the class comment): the resolution runs the provision
     * delegate's {@code initIfNeeded}, which may bind up to a hundred sockets looking for a free
     * debug port and then PERSIST the one it finds. A read abandoned at the deadline can do all
     * of that after this method returned, so an expired deadline means "no answer, and the
     * application's stored port may have changed" — never "nothing happened".
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
     * <p><b>An expired deadline also leaves the STORED default indeterminate.</b> That late clear
     * is an unconditional remove, not a compare-and-set, and this plugin owns the partner write
     * ({@code create_infobase}'s {@code setDefaultApplication(project, newApp)}). A read abandoned
     * here can therefore land afterwards and erase a default the caller set in between. A caller
     * that writes a default after a non-concluded lookup must RE-READ it before trusting it —
     * and this method's own listing sibling is not a substitute, because the erase notifies with
     * the stale application.
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
     * <p><b>Not side-effect-free</b> (see the class comment): the listing initialises EVERY
     * application it returns, so one call can run the bind-a-hundred-sockets port scan once per
     * application not yet carrying a port, and persist each result. A caller that POLLS this on
     * a freshly created application — {@code create_infobase}'s read-back is exactly that — pays
     * the scan on every poll, and a poll abandoned at the deadline can still allocate and store
     * a port afterwards.
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
     * <p>A {@code timeoutMs} of zero or less does not reach the job manager at all: it returns a
     * non-concluded read straight away. Scheduling one would be a mutating read abandoned a
     * millisecond later, not a cheap no-op.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names, e.g. "the EDT application list for project 'P'"
     * @param timeoutMs how long the CALLER waits; zero or less is refused without scheduling
     * @param read the manager read to run
     * @return the bounded read, never {@code null}
     */
    public static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs,
        IApplicationRead<T> read)
    {
        return readBounded(jobName, target, timeoutMs, read, true);
    }

    /**
     * Same bounded read, with the pending-interrupt policy explicit.
     *
     * <p>{@code runWhenInterrupted=true} is what the tool entry points want: their callers do not
     * check the flag before the read, so consuming it here and letting the platform abort would
     * silently skip the work. A caller that was ALREADY written against the raw
     * {@code BoundedJob.run} — one that handles {@link BoundedJob.Outcome#INTERRUPTED} itself —
     * passes {@code false} and keeps exactly the behaviour it had, whatever the runtime's
     * {@code LockListener} makes of a pending interrupt.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names
     * @param timeoutMs how long the CALLER waits
     * @param read the manager read to run
     * @param runWhenInterrupted {@code true} to take and restore a pending interrupt so the read
     *     still runs; {@code false} to let it surface as {@link BoundedJob.Outcome#INTERRUPTED}
     * @return the bounded read, never {@code null}
     */
    public static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs,
        IApplicationRead<T> read, boolean runWhenInterrupted)
    {
        return readBounded(jobName, target, timeoutMs, read, runWhenInterrupted, McpJobs::schedule);
    }

    /**
     * Same bounded read, notifying {@code completion} when the JOB ends rather than when the
     * caller stops waiting.
     *
     * <p>For a caller holding an unattended-safety guard — an armed dialog suppressor, an
     * auto-confirmer window — that must outlive an abandoned read: the read keeps running after
     * the deadline, and a guard released at the deadline leaves the dialog it was raised for with
     * nobody to answer. Pair it with {@link BoundedJob.DeferredCleanup}, exactly as
     * {@code StandaloneServerSupport} does.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names
     * @param timeoutMs how long the CALLER waits; zero or less is refused without scheduling, and
     *     {@code completion} then runs before this returns
     * @param read the manager read to run
     * @param completion invoked exactly once when the job reaches its terminal state (or, for a
     *     read that was never scheduled, before this method returns)
     * @return the bounded read, never {@code null}
     */
    public static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs,
        IApplicationRead<T> read, Runnable completion)
    {
        return readBounded(jobName, target, timeoutMs, read, true, McpJobs::schedule, completion);
    }

    /**
     * Same bounded read with the job SCHEDULER supplied — the seam a test uses to drive the one
     * outcome a real job manager cannot be made to produce on demand: a schedule refused because
     * EDT is shutting down.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names
     * @param timeoutMs how long the CALLER waits
     * @param read the manager read to run
     * @param runWhenInterrupted whether a pending interrupt is taken so the read still runs
     * @param scheduler how the job reaches the job manager
     * @return the bounded read, never {@code null}
     */
    static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs, // NOSONAR one argument per independent concern; a parameter object would only rename them
        IApplicationRead<T> read, boolean runWhenInterrupted, BoundedJob.IJobScheduler scheduler)
    {
        return readBounded(jobName, target, timeoutMs, read, runWhenInterrupted, scheduler, null);
    }

    /**
     * The one implementation behind every {@code readBounded} overload.
     *
     * @param <T> what the read returns
     * @param jobName the job name shown in EDT's progress UI
     * @param target what the deadline sentence names
     * @param timeoutMs how long the CALLER waits
     * @param read the manager read to run
     * @param runWhenInterrupted whether a pending interrupt is taken so the read still runs
     * @param scheduler how the job reaches the job manager
     * @param completion job-terminal callback, or {@code null}
     * @return the bounded read, never {@code null}
     */
    static <T> BoundedRead<T> readBounded(String jobName, String target, long timeoutMs, // NOSONAR one argument per independent concern; a parameter object would only rename them
        IApplicationRead<T> read, boolean runWhenInterrupted, BoundedJob.IJobScheduler scheduler,
        Runnable completion)
    {
        if (timeoutMs <= 0L)
        {
            // An exhausted budget must not SCHEDULE. BoundedJob clamps its join to 1 ms, but the
            // job itself still starts, and none of these reads is side-effect-free (see the class
            // comment): a 1 ms bound would bind up to a hundred sockets looking for a debug port,
            // persist the winner, or clear a stored default - and then be abandoned. Not asking is
            // the only way to leave the state alone.
            runCompletion(jobName, completion);
            return new BoundedRead<>(null, null, BoundedJob.Outcome.NOT_RUN,
                exhaustedBudgetFailure(target));
        }
        // A caller that arrives ALREADY interrupted must still RUN the read and leave still
        // interrupted. Read off org.eclipse.core.jobs 3.15.700 as shipped with EDT 2026.2:
        // Semaphore.acquire(long) opens with `if (Thread.interrupted()) throw new
        // InterruptedException()`, and JobManager.join rethrows that only when
        // LockManager.canBlock() — with a UILockListener installed that is true on every thread
        // but the UI one, while a runtime whose listener answers false (the headless test runtime
        // is one) swallows it and waits out the deadline instead, because the semaphore has by
        // then CLEARED the flag. So what the flag decides is platform-dependent; what is NOT is
        // that taking it here guarantees the read runs and the finally guarantees the caller's own
        // interruption checks stay armed.
        boolean interruptedOnEntry = runWhenInterrupted && Thread.interrupted();
        try
        {
            AtomicReference<T> value = new AtomicReference<>();
            BoundedJob.Result result;
            try
            {
                result = BoundedJob.run(jobName, timeoutMs, monitor -> value.set(read.read()),
                    completion, scheduler);
            }
            catch (IllegalStateException e)
            {
                // JobManager.scheduleInternal refuses every schedule once the job manager has been
                // shut down, and BoundedJob rethrows it. Before the read moved onto a Job this
                // call simply worked, so it must not now come out as an unhandled throw from a
                // shutting-down EDT: it is one more read that did not conclude, with its own
                // sentence, because no retry can help until EDT is running again.
                Activator.logError("Bounded application read could not be scheduled: " + jobName, e); //$NON-NLS-1$
                return new BoundedRead<>(null, null, BoundedJob.Outcome.NOT_RUN,
                    jobManagerUnavailableFailure(target));
            }
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
     * Runs a job-terminal callback for a read that never reached the job manager, with the same
     * "a notification must not damage the caller" guard {@link BoundedJob} applies to its own.
     *
     * @param jobName the job that was not scheduled, for the log line
     * @param completion the callback, or {@code null}
     */
    private static void runCompletion(String jobName, Runnable completion)
    {
        if (completion == null)
        {
            return;
        }
        try
        {
            completion.run();
        }
        catch (RuntimeException | Error e) // NOSONAR the guard release must not replace the answer
        {
            Activator.logError("Bounded application read completion callback failed: " //$NON-NLS-1$
                + jobName, e);
        }
    }

    /**
     * The diagnosis for a read a caller asked for with NO time left. Deliberately not a deadline
     * sentence: the deadline was already gone when the call arrived, so the read was never
     * scheduled and — unlike every abandoned read here — it cannot have touched EDT's state.
     *
     * @param target what the sentence names, e.g. "the EDT application list for project 'P'"
     * @return the diagnosis sentence, never {@code null}
     */
    public static String exhaustedBudgetFailure(String target)
    {
        return target + " was not started: the caller had no time left to wait for it, and " //$NON-NLS-1$
            + "starting a read it would abandon immediately can still change EDT's stored state, " //$NON-NLS-1$
            + "so nothing was read and nothing was changed"; //$NON-NLS-1$
    }

    /**
     * The diagnosis for a read that could not even be SCHEDULED because EDT's background job
     * manager is shutting down. Kept apart from every deadline wording: no deadline expired, the
     * work never existed, and the only thing that changes the answer is EDT running again.
     *
     * @param target what the sentence names, e.g. "the EDT application list for project 'P'"
     * @return the diagnosis sentence, never {@code null}
     */
    public static String jobManagerUnavailableFailure(String target)
    {
        return target + " could not be started: EDT's background job manager has been shut down " //$NON-NLS-1$
            + "(EDT is stopping), so no application read can run until EDT is restarted"; //$NON-NLS-1$
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
            // What wedges these reads is a MONITOR inside EDT's application registry, so a repeat
            // of the same read queues behind the same holder and blocks exactly as long - and
            // parks one more Eclipse worker thread doing it. Saying only "retry" would therefore
            // send the caller into the one loop that cannot end; the restart has to be named.
            return target + " did not finish within " //$NON-NLS-1$
                + deadline + " and may still be running, so it may yet change EDT's stored state; " //$NON-NLS-1$
                + "an immediate retry queues behind the same wedge, and if it keeps expiring EDT " //$NON-NLS-1$
                + "has to be restarted"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.INTERRUPTED)
        {
            return "the wait for " + target //$NON-NLS-1$
                + " was interrupted and the lookup may still be running"; //$NON-NLS-1$
        }
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            // Cancelling it at the start line is what kept it from running, so NOTHING was read
            // and nothing is in flight. The old wording sent the caller to wait for "a responsive
            // Job queue", a condition that cannot produce this outcome for a rule-less job.
            return target + " did not start within " //$NON-NLS-1$
                + deadline + " and was cancelled before it began, so it read nothing and changed " //$NON-NLS-1$
                + "nothing; retrying is safe"; //$NON-NLS-1$
        }
        return target + " never ran: it left EDT's background job queue without entering the " //$NON-NLS-1$
            + "work, so something other than this call's deadline cancelled it and nothing was " //$NON-NLS-1$
            + "read"; //$NON-NLS-1$
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
