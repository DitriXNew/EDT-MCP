/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared mechanics for the git <em>network</em> tools ({@code push_git_branch},
 * {@code pull_git_branch}, and {@code create_git_repository}'s clone path) - the
 * sibling of {@link GitCheckoutSupport} for operations that reach a remote. Two
 * responsibilities, both aimed at the same invariant (unattended-safe: never on the
 * UI thread, always time-bounded, never a modal dialog):
 * <ol>
 * <li><b>A bounded background-{@link Job} runner</b> ({@link #run(String, IProject, RemoteOp, Runnable)}):
 * runs a caller-supplied {@link RemoteOp} on a system/non-user {@link Job}, joins it
 * for at most {@link #REMOTE_TIMEOUT_SECONDS} seconds (cancelling on timeout), and -
 * when a non-null project is passed and the op did not throw - refreshes that project
 * ({@link IResource#DEPTH_INFINITE}) inside the SAME Job so the EDT model is not left
 * stale after a pull/clone. It never touches the UI thread (Eclipse {@link Job}s run
 * on worker threads; the join runs on the calling MCP handler thread). The outcome is
 * a pure {@link RemoteOutcome} value object; each caller captures its OWN operation
 * result (a JGit {@code Iterable<PushResult>} / {@code PullResult} / {@code Git}) into
 * its own holder inside the op, then maps it to an actionable result.</li>
 * <li><b>A non-interactive {@link CredentialsProvider} factory</b>
 * ({@link #credentialsProvider(String, String)}): builds a
 * {@link UsernamePasswordCredentialsProvider} from optional username/token tool
 * params when supplied, otherwise a fail-fast provider that reports
 * {@link CredentialsProvider#isInteractive() isInteractive()==false} and answers every
 * {@link CredentialsProvider#get get(...)} with {@code false}. Both branches are
 * non-interactive, so no EGit login/passphrase modal can ever open on the unattended
 * MCP path - a missing/invalid HTTPS credential surfaces as a normal JGit transport
 * failure the tool maps to an actionable error, never a hung dialog. (SSH remotes need
 * no provider at all: EGit's process-global {@code SshSessionFactory} - ssh-agent +
 * {@code ~/.ssh} - authenticates transparently.)</li>
 * </ol>
 * Lives in {@code utils/git/} (shared helper), never in {@code tools/impl/}.
 */
public final class GitRemoteSupport
{
    /** Background-Job timeout for a network op (mirrors {@link GitCheckoutSupport#CHECKOUT_TIMEOUT_SECONDS}). */
    public static final long REMOTE_TIMEOUT_SECONDS = 120;

    /**
     * The shared fail-fast, non-interactive credentials provider: it advertises
     * {@link CredentialsProvider#isInteractive() isInteractive()==false} and returns
     * {@code false} from every {@link CredentialsProvider#get get(...)} /
     * {@link CredentialsProvider#supports supports(...)}, so JGit gives up (a normal
     * transport failure) instead of prompting. Stateless, hence a singleton.
     */
    private static final CredentialsProvider NON_INTERACTIVE = new NonInteractiveCredentialsProvider();

    private GitRemoteSupport()
    {
        // Utility class
    }

    /**
     * A network operation to run on the bounded background {@link Job}: a push, pull,
     * or clone. The op performs the JGit call and captures its OWN result (into a
     * holder the caller owns) - the runner only bounds it, catches a thrown exception
     * into {@link RemoteOutcome#jobError()}, and optionally refreshes afterwards.
     */
    @FunctionalInterface
    public interface RemoteOp
    {
        /**
         * Runs the network operation.
         *
         * @param monitor the Job's progress monitor
         * @throws Exception any JGit/transport failure; captured into
         *             {@link RemoteOutcome#jobError()} rather than escaping the Job
         */
        void run(IProgressMonitor monitor) throws Exception; // NOSONAR broad by design: any JGit/transport failure is captured, never escapes
    }

    /**
     * The outcome of a bounded network op. Exactly one of the following holds:
     * <ul>
     * <li>the Job ran to completion - {@link #ranToCompletion()} is {@code true} and
     * {@link #refreshWarning()} carries an optional post-op-refresh-failure note (only
     * possible when a project was passed to {@link #run(String, IProject, RemoteOp, Runnable)}
     * and its refresh failed); the caller then inspects its OWN captured op result;</li>
     * <li>{@link #timedOut()} - the Job did not finish within
     * {@link #REMOTE_TIMEOUT_SECONDS} and was cancelled;</li>
     * <li>{@link #interrupted()} - the waiting thread was interrupted (the interrupt
     * flag has already been restored on it);</li>
     * <li>{@link #jobError()} - the op itself threw.</li>
     * </ul>
     */
    public static final class RemoteOutcome
    {
        private final String refreshWarning;

        private final boolean timedOut;

        private final boolean interrupted;

        private final Exception jobError;

        private RemoteOutcome(String refreshWarning, boolean timedOut, boolean interrupted, Exception jobError)
        {
            this.refreshWarning = refreshWarning;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.jobError = jobError;
        }

        /**
         * Package-private test-only factory: builds a {@link RemoteOutcome} directly, bypassing
         * {@link GitRemoteSupport#run(String, IProject, RemoteOp, Runnable)}'s live background {@link Job}, so the
         * pure accessors are unit-testable (mirrors {@link GitCheckoutSupport.CheckoutOutcome#forTest}). No
         * behaviour change - mirrors the private constructor exactly.
         */
        static RemoteOutcome forTest(String refreshWarning, boolean timedOut, boolean interrupted,
            Exception jobError)
        {
            return new RemoteOutcome(refreshWarning, timedOut, interrupted, jobError);
        }

        /**
         * @return {@code true} when the Job ran to completion without a timeout, interrupt, or thrown
         *         exception (the network op may still have produced a non-OK JGit result - a rejected push
         *         or a conflicting pull - that is a normal outcome the caller inspects, not this flag)
         */
        public boolean ranToCompletion()
        {
            return !timedOut && !interrupted && jobError == null;
        }

        /**
         * @return a post-op workspace-refresh-failure warning, or {@code null} (either no failure, no
         *         project was passed to refresh, or the op threw so no refresh was attempted)
         */
        public String refreshWarning()
        {
            return refreshWarning;
        }

        /** @return {@code true} if the Job did not finish within {@link #REMOTE_TIMEOUT_SECONDS}. */
        public boolean timedOut()
        {
            return timedOut;
        }

        /** @return {@code true} if the waiting thread was interrupted while awaiting the Job. */
        public boolean interrupted()
        {
            return interrupted;
        }

        /** @return the exception the op threw, or {@code null}. */
        public Exception jobError()
        {
            return jobError;
        }
    }

    /**
     * Runs {@code op} on a bounded background {@link Job} and joins it - the network
     * analogue of {@link GitCheckoutSupport#checkout(IProject, org.eclipse.jgit.lib.Repository, String)}.
     * The Job is {@code system}/non-{@code user} and never runs on the UI thread. On a
     * successful op (no thrown exception) and a non-null {@code projectToRefresh}, the
     * SAME Job refreshes that project ({@link IResource#DEPTH_INFINITE}) so a pull/clone
     * is reflected in the EDT model; a refresh failure is caught, logged, and surfaced
     * via {@link RemoteOutcome#refreshWarning()} rather than failing the op. Pass
     * {@code null} for {@code projectToRefresh} when nothing needs refreshing (e.g. a
     * push, which does not change the working tree).
     * <p>
     * {@code afterJobDone} (nullable) is run <em>once</em> when the background Job's
     * thread actually terminates - which, on a {@link #REMOTE_TIMEOUT_SECONDS} timeout,
     * is <em>after</em> this method has already returned {@link RemoteOutcome#timedOut()}
     * (the cancel is cooperative, so the op may still be in flight). It is the sanctioned
     * hook for releasing a resource the op borrows - notably closing a caller-<em>owned</em>
     * {@link org.eclipse.jgit.lib.Repository} ({@code resolution::closeIfOwned}): closing it
     * from the caller's own {@code finally} would race the timed-out Job and could close the
     * object database out from under the still-running push/pull. Registered via a Job
     * change listener before scheduling, so the completion is never missed.
     *
     * @param jobName a human-readable name for the background Job
     * @param projectToRefresh the project to refresh after a successful op, or {@code null} for none
     * @param op the network operation (captures its own result); must not be {@code null}
     * @param afterJobDone run once when the Job thread ends (before OR after a timeout), or {@code null}
     * @return the bounded outcome (never {@code null})
     */
    public static RemoteOutcome run(String jobName, IProject projectToRefresh, RemoteOp op, Runnable afterJobDone)
    {
        AtomicReference<Exception> jobError = new AtomicReference<>();
        AtomicReference<String> refreshWarning = new AtomicReference<>();

        Job remoteJob = new Job(jobName)
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    op.run(monitor);
                    // Refresh only after a successful op (a thrown op sets jobError and skips this):
                    // a pull/clone rewrote files on disk, so without this the EDT model would go stale
                    // (native-hook workspace auto-refresh defaults OFF). A refresh failure must not fail
                    // the already-completed network op - it is caught, logged, and surfaced as a note.
                    if (projectToRefresh != null)
                    {
                        refreshAfterRemoteOp(projectToRefresh, monitor, refreshWarning);
                    }
                }
                catch (Exception e) // NOSONAR unattended-safety: no failure may escape the Job; captured for the caller to map
                {
                    jobError.set(e);
                }
                return Status.OK_STATUS;
            }
        };
        if (afterJobDone != null)
        {
            // Release the borrowed resource (e.g. close an owned Repository) only once the Job's
            // thread has genuinely finished - safe even on the timeout path where run() has already
            // returned while the cancelled-but-cooperative op is still touching the repository.
            remoteJob.addJobChangeListener(new JobChangeAdapter()
            {
                @Override
                public void done(IJobChangeEvent event)
                {
                    try
                    {
                        afterJobDone.run();
                    }
                    catch (RuntimeException e) // NOSONAR post-op cleanup must never propagate off the Job thread
                    {
                        Activator.logError("git remote op: post-job cleanup failed for '" + jobName + "'", e); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            });
        }
        remoteJob.setUser(false);
        remoteJob.setSystem(true);
        remoteJob.schedule();

        try
        {
            boolean finished = remoteJob.join(TimeUnit.SECONDS.toMillis(REMOTE_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                remoteJob.cancel();
                return new RemoteOutcome(null, true, false, null);
            }
        }
        catch (InterruptedException e)
        {
            // Cooperatively cancel the still-running Job (as the timeout path does) so it is not left
            // orphaned; afterJobDone still fires when its thread actually ends, releasing the resource.
            remoteJob.cancel();
            Thread.currentThread().interrupt();
            return new RemoteOutcome(null, false, true, null);
        }

        if (jobError.get() != null)
        {
            return new RemoteOutcome(null, false, false, jobError.get());
        }
        return new RemoteOutcome(refreshWarning.get(), false, false, null);
    }

    /**
     * Refreshes {@code project} ({@code IResource.DEPTH_INFINITE}) after a successful
     * network op, inside the SAME bounded Job. A refresh failure is caught and logged,
     * and a short warning is stashed into {@code refreshWarning} for the caller to
     * surface via {@link RemoteOutcome#refreshWarning()} - it never fails the
     * already-completed op.
     *
     * @param project the project to refresh
     * @param monitor the Job's progress monitor
     * @param refreshWarning set to a short warning note on failure, left {@code null} on success
     */
    private static void refreshAfterRemoteOp(IProject project, IProgressMonitor monitor,
        AtomicReference<String> refreshWarning)
    {
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
        catch (CoreException e)
        {
            Activator.logError("git remote op: post-op workspace refresh failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            refreshWarning.set("Workspace refresh after the git operation failed (" + e.getMessage() //$NON-NLS-1$
                + "); the EDT model may be stale until a manual refresh."); //$NON-NLS-1$
        }
    }

    /**
     * Builds the {@link CredentialsProvider} for a network op, per the Option-A auth
     * decision (no secret storage, no dialog):
     * <ul>
     * <li>when a {@code username} and/or {@code token} is supplied, a
     * {@link UsernamePasswordCredentialsProvider} carrying them (itself non-interactive
     * - it returns stored values, it never prompts) - typically for an HTTPS remote with
     * a personal access token;</li>
     * <li>otherwise the shared fail-fast {@link #NON_INTERACTIVE} provider, so an HTTPS
     * remote that needs credentials fails cleanly instead of raising EGit's modal login
     * dialog (an unattended hang). SSH remotes authenticate transparently via EGit's
     * process-global session factory and reach neither branch's prompt.</li>
     * </ul>
     * Either way the returned provider is non-interactive
     * ({@link CredentialsProvider#isInteractive()} is {@code false}).
     *
     * @param username optional git username (blank/{@code null} treated as absent)
     * @param token optional git password/token (blank/{@code null} treated as absent)
     * @return a non-interactive credentials provider (never {@code null})
     */
    public static CredentialsProvider credentialsProvider(String username, String token)
    {
        boolean hasUser = username != null && !username.isBlank();
        boolean hasToken = token != null && !token.isBlank();
        if (hasUser || hasToken)
        {
            return new UsernamePasswordCredentialsProvider(hasUser ? username : "", //$NON-NLS-1$
                hasToken ? token : ""); //$NON-NLS-1$
        }
        return NON_INTERACTIVE;
    }

    /**
     * A {@link CredentialsProvider} that never prompts: it declares itself
     * non-interactive and refuses to supply any credential item, so JGit treats the
     * remote as having no credentials available and fails with a normal transport
     * error instead of opening a modal dialog.
     */
    private static final class NonInteractiveCredentialsProvider extends CredentialsProvider
    {
        @Override
        public boolean isInteractive()
        {
            return false;
        }

        @Override
        public boolean supports(CredentialItem... items)
        {
            return false;
        }

        @Override
        public boolean get(URIish uri, CredentialItem... items)
        {
            return false;
        }
    }
}
