/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.lib.Repository;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.utils.CheckoutReloads;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Runs a headless EGit checkout ({@link BranchOperation}) on a bounded background
 * {@link Job}, then, in a second bounded Job, unconditionally refreshes the project and the
 * other projects checked out with the repository's work tree ({@link #projectsToRefresh}) - the shared mechanics
 * behind {@code switch_git_branch} and {@code create_git_branch}'s optional
 * checkout (issue #281 phase 2). Extracted from the original {@code
 * SwitchGitBranchTool} implementation so both callers run the SAME Job /
 * {@link CheckoutResult} / refresh logic instead of duplicating it - each caller
 * keeps its own {@link CheckoutResult#getStatus()}-to-error-message mapping (the
 * two tools need different wording: a switch failure leaves the tree on the
 * ORIGINAL branch, a create-then-checkout failure leaves a freshly created branch
 * that was simply never checked out), only the Job/refresh mechanics are shared
 * here.
 * <p>
 * The checkout runs under {@link BranchBindingLock}, the lock {@code set_branch_infobase}'s binding changes take
 * (#656), so no checkout of this server lands between a detach's re-check and EDT's write; a call that waits
 * {@link BranchBindingLock#WAIT_SECONDS} for it checks nothing out ({@link CheckoutOutcome#busy()}). A checkout
 * Job that outlives its bounded wait is recorded as still running ({@link BranchBindingLock#awaitOrTrack}) before
 * the lock is released, for every project in the repository's work tree, until the Job itself ends: a detach for
 * one of them refuses meanwhile, instead of the lock being held across a Job that may never end.
 * <p>
 * Never touches the UI thread; bounded by {@link #CHECKOUT_TIMEOUT_SECONDS}
 * (mirrors {@code CreateInfobaseTool}'s Job pattern). The unconditional
 * post-checkout refresh exists because on the {@link GitRepositoryResolver}
 * discovery-fallback path (project not EGit-shared) there is no other guarantee
 * the workspace notices the checkout at all - native-hook workspace auto-refresh
 * defaults OFF; on the EGit-mapped resolution path {@link BranchOperation}
 * already refreshes affected projects itself, so the refresh here is then a cheap
 * near no-op.
 */
public final class GitCheckoutSupport
{
    /** Background-Job timeout for the checkout (mirrors {@code CreateInfobaseTool}). */
    public static final long CHECKOUT_TIMEOUT_SECONDS = 120;

    /** How long the post-checkout workspace refresh is waited for before it becomes a note (#922). */
    public static final long REFRESH_TIMEOUT_SECONDS = 120;

    /**
     * How long a successful checkout waits for EDT to load the checked-out files into the refreshed projects'
     * models before it answers (#922) - the export barrier's own budget ({@code WriteExportBarrier.DEADLINE_MS}).
     */
    public static final long MODEL_RELOAD_WAIT_SECONDS = 60;

    private GitCheckoutSupport()
    {
        // Utility class
    }

    /**
     * The outcome of a bounded checkout attempt. Exactly one of the following holds:
     * <ul>
     * <li>the Job ran to completion - {@link #result()} carries JGit's
     * {@link CheckoutResult} (map its {@link CheckoutResult#getStatus()} to a
     * caller-specific message) and {@link #refreshWarning()} an optional
     * post-checkout-refresh-failure note (only possible when the status is
     * {@link CheckoutResult.Status#OK});</li>
     * <li>{@link #timedOut()} - the Job did not finish within
     * {@link #CHECKOUT_TIMEOUT_SECONDS} and was asked to cancel; it may still be running, and stays recorded
     * as running until it ends ({@link BranchBindingLock#awaitOrTrack});</li>
     * <li>{@link #interrupted()} - the waiting thread was interrupted (the interrupt
     * flag has already been restored on it); the Job may still be running, recorded as above;</li>
     * <li>{@link #jobError()} - the checkout itself threw a {@link CoreException};</li>
     * <li>{@link #busy()} - another infobase-binding change or checkout held
     * {@link BranchBindingLock} for the whole wait, so nothing was checked out.</li>
     * </ul>
     */
    public static final class CheckoutOutcome
    {
        private final CheckoutResult result;

        private final String refreshWarning;

        private final boolean timedOut;

        private final boolean interrupted;

        private final Exception jobError;

        private final boolean busy;

        /** The projects the post-checkout refresh covered (empty unless the checkout ran and was OK). */
        private List<IProject> refreshedProjects = List.of();

        private CheckoutOutcome(CheckoutResult result, String refreshWarning, boolean timedOut,
            boolean interrupted, Exception jobError, boolean busy)
        {
            this.result = result;
            this.refreshWarning = refreshWarning;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.jobError = jobError;
            this.busy = busy;
        }

        /**
         * Package-private test-only factory: builds a {@link CheckoutOutcome} directly, bypassing
         * {@link GitCheckoutSupport#checkout(IProject, Repository, String)}'s live background {@link Job},
         * so the pure accessors are unit-testable (issue #171 coverage). No behaviour change - mirrors the
         * private constructor exactly.
         */
        static CheckoutOutcome forTest(CheckoutResult result, String refreshWarning, boolean timedOut,
            boolean interrupted, Exception jobError)
        {
            return new CheckoutOutcome(result, refreshWarning, timedOut, interrupted, jobError, false);
        }

        /**
         * @return {@code true} when the Job ran to completion (the checkout may still have a
         *         non-OK {@link CheckoutResult} - that is a normal outcome, not this flag)
         */
        public boolean ranToCompletion()
        {
            return !timedOut && !interrupted && jobError == null && !busy;
        }

        /** @return JGit's checkout result, or {@code null} unless {@link #ranToCompletion()}. */
        public CheckoutResult result()
        {
            return result;
        }

        /**
         * @return a post-checkout workspace-refresh-failure warning, or {@code null} (either no
         *         failure, or the checkout status was not OK so no refresh was attempted)
         */
        public String refreshWarning()
        {
            return refreshWarning;
        }

        private CheckoutOutcome withRefreshedProjects(List<IProject> projects)
        {
            this.refreshedProjects = projects == null ? List.of() : List.copyOf(projects);
            return this;
        }

        /**
         * @return the projects the post-checkout refresh covered - the addressed one and every other project in the
         *         repository's work tree ({@link GitCheckoutSupport#projectsToRefresh}); empty when no refresh ran
         */
        public List<IProject> refreshedProjects()
        {
            return refreshedProjects;
        }

        /** @return {@code true} if the Job did not finish within {@link #CHECKOUT_TIMEOUT_SECONDS}. */
        public boolean timedOut()
        {
            return timedOut;
        }

        /** @return {@code true} if the waiting thread was interrupted while awaiting the Job. */
        public boolean interrupted()
        {
            return interrupted;
        }

        /** @return the exception the checkout Job threw, or {@code null}. */
        public Exception jobError()
        {
            return jobError;
        }

        /**
         * @return {@code true} if another infobase-binding change or checkout held {@link BranchBindingLock} for
         *         the whole {@link BranchBindingLock#WAIT_SECONDS}: nothing was checked out
         *         ({@link BranchBindingLock#busyReason()} words it)
         */
        public boolean busy()
        {
            return busy;
        }
    }

    /**
     * Runs {@code new BranchOperation(repo, fullRef).execute(monitor)} on a bounded
     * background {@link Job} and joins it - exactly what {@code SwitchGitBranchTool}
     * did inline before this extraction. On a successful
     * ({@link CheckoutResult.Status#OK}) checkout, a SEPARATE bounded Job
     * ({@link #REFRESH_TIMEOUT_SECONDS}) unconditionally refreshes {@code project} and every
     * other project checked out with the repository's work tree ({@link #projectsToRefresh},
     * {@link WorkTreeProjects#checkedOutWith}, {@link IResource#DEPTH_INFINITE}), after those
     * projects are registered as reloading ({@link CheckoutReloads}); a refresh failure, or a
     * refresh that outlives its wait, is surfaced via {@link CheckoutOutcome#refreshWarning()}
     * rather than failing the already-successful checkout.
     *
     * <p>
     * Runs under {@link BranchBindingLock} (see the class javadoc), taken before anything is built and released
     * when the join returns: a Job that outlived {@link #CHECKOUT_TIMEOUT_SECONDS} (or whose wait was
     * interrupted) may still be finishing after that, and is recorded as running until it ends
     * ({@link #awaitCheckout}).
     *
     * @param project the project to refresh after a successful checkout
     * @param repo the repository to check out in
     * @param fullRef the full ref to check out (e.g. {@code refs/heads/feature/x})
     * @return the bounded outcome (never {@code null})
     */
    public static CheckoutOutcome checkout(IProject project, Repository repo, String fullRef)
    {
        if (!BranchBindingLock.tryLock())
        {
            boolean interrupted = Thread.currentThread().isInterrupted();
            return new CheckoutOutcome(null, null, false, interrupted, null, !interrupted);
        }
        try
        {
            return checkoutLocked(project, repo, fullRef);
        }
        finally
        {
            BranchBindingLock.unlock();
        }
    }

    /** {@link #checkout} under {@link BranchBindingLock}. */
    private static CheckoutOutcome checkoutLocked(IProject project, Repository repo, String fullRef)
    {
        BranchOperation op = new BranchOperation(repo, fullRef);
        AtomicReference<Exception> jobError = new AtomicReference<>();

        Job checkoutJob = new Job("Checkout git branch: " + fullRef) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    op.execute(monitor);
                }
                catch (CoreException e)
                {
                    jobError.set(e);
                }
                return Status.OK_STATUS;
            }
        };
        checkoutJob.setUser(false);
        checkoutJob.setSystem(true);
        McpJobs.schedule(checkoutJob);

        CheckoutOutcome unfinished = awaitCheckout(checkoutJob, TimeUnit.SECONDS.toMillis(CHECKOUT_TIMEOUT_SECONDS),
            () -> affectedProjects(project, repo));
        if (unfinished != null)
        {
            return unfinished;
        }

        if (jobError.get() != null)
        {
            return new CheckoutOutcome(null, null, false, false, jobError.get(), false);
        }
        CheckoutResult result = op.getResult(repo);
        if (result == null || result.getStatus() != CheckoutResult.Status.OK)
        {
            return new CheckoutOutcome(result, null, false, false, null, false);
        }
        // Unconditional refresh (see class javadoc): on the EGit-mapped path BranchOperation already refreshed the
        // projects it maps, so this is a cheap near no-op; on the discovery-fallback path it is the ONLY thing that
        // keeps the EDT model from going stale. It runs in its OWN bounded Job after the checkout Job has ended
        // (#922): a slow refresh must not turn a checkout that already succeeded into a timed-out one. A refresh that
        // fails or outlives its wait is a note on the successful outcome, never a failure of it.
        List<IProject> projects = projectsToRefresh(project, WorkTreeProjects.checkedOutWith(repo.getWorkTree()));
        String refreshWarning = refreshAfterCheckout(projects,
            TimeUnit.SECONDS.toMillis(REFRESH_TIMEOUT_SECONDS));
        return new CheckoutOutcome(result, refreshWarning, false, false, null, false)
            .withRefreshedProjects(projects);
    }

    /**
     * Joins the scheduled checkout Job for at most {@code timeoutMillis}, under {@link BranchBindingLock}. A Job that
     * has not ended by then is asked to cancel - cooperatively: it may go on running - and a Job whose wait was
     * interrupted is left running; either way it is recorded as still running for {@code affected} until it ends
     * ({@link BranchBindingLock#awaitOrTrack}), before the caller releases the lock.
     *
     * @param affected names the projects whose branch the checkout moves
     * @return {@code null} when the Job ended within the wait, otherwise the timed-out or interrupted outcome
     */
    static CheckoutOutcome awaitCheckout(Job checkoutJob, long timeoutMillis,
        Supplier<? extends Collection<String>> affected)
    {
        try
        {
            if (BranchBindingLock.awaitOrTrack(checkoutJob, timeoutMillis, affected))
            {
                return null;
            }
            checkoutJob.cancel();
            return new CheckoutOutcome(null, null, true, false, null, false);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new CheckoutOutcome(null, null, false, true, null, false);
        }
    }

    /**
     * The projects a checkout in {@code repo} moves to another branch: {@code project} and every workspace project in
     * the repository's work tree.
     */
    private static List<String> affectedProjects(IProject project, Repository repo)
    {
        List<String> names = new ArrayList<>();
        names.add(project.getName());
        names.addAll(WorkTreeProjects.names(WorkTreeProjects.inside(repo.getWorkTree())));
        return names;
    }

    /**
     * After a successful checkout: waits, bounded by {@link #MODEL_RELOAD_WAIT_SECONDS}, until EDT has loaded the
     * checked-out files into the model of every refreshed project ({@link ProjectStateChecker#awaitModelReload}),
     * and words what is still loading.
     * <p>
     * Issue #922: the checkout returned while Eclipse's automatic refresh was still taking up the rewritten files -
     * EDT registers their import inside that refresh, and imports them after it - so a metadata write sent right
     * after the switch landed in the previous branch's model, answered {@code persisted: true}, and its export wrote
     * that model back over the checked-out file. The write gate now refuses until the import is done; this wait
     * makes the switch itself answer only once the new branch is in the model, or say plainly that it is not yet.
     * Runs outside {@link BranchBindingLock}.
     *
     * @param outcome the checkout outcome ({@link CheckoutOutcome#refreshedProjects()} names the projects)
     * @return a short note naming the projects still loading, or {@code null} when every project settled
     */
    public static String awaitModelReload(CheckoutOutcome outcome)
    {
        return modelReloadNote(ProjectStateChecker.awaitModelReload(outcome.refreshedProjects(),
            TimeUnit.SECONDS.toMillis(MODEL_RELOAD_WAIT_SECONDS)));
    }

    /**
     * The note for projects whose model had not taken in the checkout when the wait ended.
     *
     * @param stillLoading the names of those projects
     * @return the note, or {@code null} when there are none
     */
    static String modelReloadNote(List<String> stillLoading)
    {
        if (stillLoading == null || stillLoading.isEmpty())
        {
            return null;
        }
        return "EDT had not finished loading the checked-out files into the model of " //$NON-NLS-1$
            + String.join(", ", stillLoading) + " within " + MODEL_RELOAD_WAIT_SECONDS //$NON-NLS-1$ //$NON-NLS-2$
            + " seconds: until it has, reads may still show the previous branch and metadata writes are " //$NON-NLS-1$
            + "refused as 'Project is building'. Retry when list_projects shows the project ready."; //$NON-NLS-1$
    }

    /**
     * Joins two optional notes with a space.
     *
     * @return {@code null} when both are {@code null}
     */
    public static String joinNotes(String first, String second)
    {
        if (first == null)
        {
            return second;
        }
        return second == null ? first : first + " " + second; //$NON-NLS-1$
    }

    /**
     * The projects a successful checkout must refresh: the addressed {@code project} first, then every other
     * project in the repository's work tree, each once.
     * <p>
     * Issue #922: a checkout runs from the work tree's ROOT, so it rewrites the files of every project in it. Only
     * the addressed project used to be refreshed. A sibling that is not shared with EGit (its
     * {@link BranchOperation} then refreshes nothing) depended on Eclipse's automatic refresh to notice the
     * change at all - which EDT turns on but a bare Eclipse leaves off - and kept the previous branch in its model
     * until then; the write gate cannot see a change no refresh has reported. The {@code git} tool refreshes the
     * same set after its own branch-changing commands ({@link WorkTreeProjects}).
     *
     * @param addressed the project the checkout was requested for
     * @param inWorkTree the open projects inside the repository's work tree
     * @return the projects to refresh, the addressed one first, without duplicates (never {@code null})
     */
    static List<IProject> projectsToRefresh(IProject addressed, Collection<IProject> inWorkTree)
    {
        List<IProject> projects = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (addressed != null)
        {
            projects.add(addressed);
            names.add(addressed.getName());
        }
        for (IProject project : inWorkTree)
        {
            if (project != null && !names.contains(project.getName()))
            {
                projects.add(project);
                names.add(project.getName());
            }
        }
        return projects;
    }

    /**
     * Refreshes {@code projects} ({@code IResource.DEPTH_INFINITE}) after a successful checkout, in its own Job that
     * the caller - holding {@link BranchBindingLock} - waits for at most {@code timeoutMillis}. Never fails the
     * checkout: a refresh failure is caught, logged and worded; a refresh that outlives the wait is recorded as a
     * branch change still running ({@link BranchBindingLock#awaitOrTrack}) and worded as well.
     *
     * @param projects the projects to refresh ({@link #projectsToRefresh})
     * @param timeoutMillis how long to wait for the refresh
     * @return a short note for {@link CheckoutOutcome#refreshWarning()}, or {@code null} when the refresh ended cleanly
     */
    private static String refreshAfterCheckout(List<IProject> projects, long timeoutMillis)
    {
        AtomicReference<String> failure = new AtomicReference<>();
        Job refresh = new Job("Refresh projects after a git checkout") //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                failure.set(refreshAll(projects, monitor));
                return Status.OK_STATUS;
            }
        };
        refresh.setUser(false);
        refresh.setSystem(true);
        // The projects count as reloading until this refresh has ENDED, not merely until its wait ran out: a refresh
        // that is still scanning has not reported the rewritten files yet, so no other signal shows the reload (#922).
        AtomicBoolean ended = new AtomicBoolean();
        refresh.addJobChangeListener(new JobChangeAdapter()
        {
            @Override
            public void done(IJobChangeEvent event)
            {
                ended.set(true);
            }
        });
        CheckoutReloads.register(WorkTreeProjects.names(projects), () -> !ended.get());
        McpJobs.schedule(refresh);
        return awaitRefresh(refresh, timeoutMillis, () -> WorkTreeProjects.names(projects), failure);
    }

    /**
     * Waits for the post-checkout refresh Job and words its end (#922). Package-visible for tests.
     *
     * @param refresh the scheduled refresh Job
     * @param timeoutMillis how long to wait for it
     * @param projects names the projects it refreshes, recorded while it outlives the wait
     * @param failure the refresh's own failure note, read once it has ended
     * @return the note, or {@code null} when the refresh ended within the wait without a failure
     */
    static String awaitRefresh(Job refresh, long timeoutMillis, Supplier<? extends Collection<String>> projects,
        AtomicReference<String> failure)
    {
        try
        {
            if (BranchBindingLock.awaitOrTrack(refresh, timeoutMillis, projects))
            {
                return failure.get();
            }
            return "The workspace refresh after the checkout did not finish within " //$NON-NLS-1$
                + TimeUnit.MILLISECONDS.toSeconds(timeoutMillis) + " seconds and is still running; until it ends " //$NON-NLS-1$
                + "the EDT model may still show the previous branch. The checkout itself succeeded."; //$NON-NLS-1$
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return "Waiting for the workspace refresh after the checkout was interrupted; it is still running, " //$NON-NLS-1$
                + "and until it ends the EDT model may still show the previous branch. The checkout itself " //$NON-NLS-1$
                + "succeeded."; //$NON-NLS-1$
        }
    }

    /** Refreshes each existing project; returns a failure note or {@code null}. */
    private static String refreshAll(List<IProject> projects, IProgressMonitor monitor)
    {
        List<String> failed = new ArrayList<>();
        String firstFailure = null;
        for (IProject project : projects)
        {
            if (!project.exists())
            {
                continue;
            }
            try
            {
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            }
            catch (CoreException | RuntimeException e) // NOSONAR logged here, or it is lost in the Job
            {
                Activator.logError("git checkout: post-checkout workspace refresh failed for project '" //$NON-NLS-1$
                    + project.getName() + "'", e); //$NON-NLS-1$
                failed.add(project.getName());
                if (firstFailure == null)
                {
                    firstFailure = e.getMessage();
                }
            }
        }
        if (failed.isEmpty())
        {
            return null;
        }
        return "Workspace refresh after the checkout failed for " + String.join(", ", failed) //$NON-NLS-1$ //$NON-NLS-2$
            + " (" + firstFailure + "); the EDT model may be stale until a manual refresh."; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Whether the repository has moved away from {@code fullRef} - read again after the unlocked reload wait, during
     * which another branch change of this server may have checked out something else (#922).
     *
     * @param repo the repository
     * @param fullRef the ref the checkout put the repository on
     * @return {@code null} when it is still checked out (or the state cannot be read), otherwise what is checked out
     *         now: a short branch name, or the commit of a detached HEAD
     */
    public static String movedAwayFrom(Repository repo, String fullRef)
    {
        String now;
        try
        {
            now = repo.getFullBranch();
        }
        catch (java.io.IOException | RuntimeException e)
        {
            return null;
        }
        if (now == null || now.equals(fullRef))
        {
            return null;
        }
        return now.startsWith("refs/heads/") ? now.substring("refs/heads/".length()) : now; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
