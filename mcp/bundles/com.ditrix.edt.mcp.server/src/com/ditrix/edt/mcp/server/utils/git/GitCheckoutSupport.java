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
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.egit.core.op.BranchOperation;
import org.eclipse.jgit.api.CheckoutResult;
import org.eclipse.jgit.lib.Repository;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Runs a headless EGit checkout ({@link BranchOperation}) on a bounded background
 * {@link Job}, then unconditionally refreshes the project - the shared mechanics
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
     * {@link #CHECKOUT_TIMEOUT_SECONDS} and was cancelled;</li>
     * <li>{@link #interrupted()} - the waiting thread was interrupted (the interrupt
     * flag has already been restored on it);</li>
     * <li>{@link #jobError()} - the checkout itself threw a {@link CoreException}.</li>
     * </ul>
     */
    public static final class CheckoutOutcome
    {
        private final CheckoutResult result;

        private final String refreshWarning;

        private final boolean timedOut;

        private final boolean interrupted;

        private final Exception jobError;

        private CheckoutOutcome(CheckoutResult result, String refreshWarning, boolean timedOut,
            boolean interrupted, Exception jobError)
        {
            this.result = result;
            this.refreshWarning = refreshWarning;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.jobError = jobError;
        }

        /**
         * @return {@code true} when the Job ran to completion (the checkout may still have a
         *         non-OK {@link CheckoutResult} - that is a normal outcome, not this flag)
         */
        public boolean ranToCompletion()
        {
            return !timedOut && !interrupted && jobError == null;
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
    }

    /**
     * Runs {@code new BranchOperation(repo, fullRef).execute(monitor)} on a bounded
     * background {@link Job} and joins it - exactly what {@code SwitchGitBranchTool}
     * did inline before this extraction. On a successful
     * ({@link CheckoutResult.Status#OK}) checkout, the SAME Job unconditionally
     * refreshes {@code project} ({@link IResource#DEPTH_INFINITE}); a refresh
     * failure is caught, logged, and surfaced via
     * {@link CheckoutOutcome#refreshWarning()} rather than failing the
     * already-successful checkout.
     *
     * @param project the project to refresh after a successful checkout
     * @param repo the repository to check out in
     * @param fullRef the full ref to check out (e.g. {@code refs/heads/feature/x})
     * @return the bounded outcome (never {@code null})
     */
    public static CheckoutOutcome checkout(IProject project, Repository repo, String fullRef)
    {
        BranchOperation op = new BranchOperation(repo, fullRef);
        AtomicReference<Exception> jobError = new AtomicReference<>();
        AtomicReference<String> refreshWarning = new AtomicReference<>();

        Job checkoutJob = new Job("Checkout git branch: " + fullRef) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    op.execute(monitor);
                    // Unconditional, same-Job refresh (see class javadoc): on the EGit-mapped
                    // path BranchOperation already refreshed affected projects, so this is a
                    // cheap near no-op; on the discovery-fallback path it is the ONLY thing
                    // that keeps the EDT model from going stale. A failure here must not fail
                    // the already-successful checkout - it is caught, logged, and surfaced as
                    // a CheckoutOutcome note instead.
                    CheckoutResult checkoutResult = op.getResult(repo);
                    if (checkoutResult != null && checkoutResult.getStatus() == CheckoutResult.Status.OK)
                    {
                        refreshAfterCheckout(project, monitor, refreshWarning);
                    }
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
        checkoutJob.schedule();

        try
        {
            boolean finished = checkoutJob.join(TimeUnit.SECONDS.toMillis(CHECKOUT_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                checkoutJob.cancel();
                return new CheckoutOutcome(null, null, true, false, null);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return new CheckoutOutcome(null, null, false, true, null);
        }

        if (jobError.get() != null)
        {
            return new CheckoutOutcome(null, null, false, false, jobError.get());
        }
        return new CheckoutOutcome(op.getResult(repo), refreshWarning.get(), false, false, null);
    }

    /**
     * Refreshes {@code project} ({@code IResource.DEPTH_INFINITE}) after a successful
     * checkout, inside the SAME bounded Job. A refresh failure is caught and logged,
     * and a short warning is stashed into {@code refreshWarning} for the caller to
     * surface via {@link CheckoutOutcome#refreshWarning()} - it never fails the
     * already-successful checkout.
     *
     * @param project the project to refresh
     * @param monitor the Job's progress monitor
     * @param refreshWarning set to a short warning note on failure, left {@code null} on success
     */
    private static void refreshAfterCheckout(IProject project, IProgressMonitor monitor,
        AtomicReference<String> refreshWarning)
    {
        try
        {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        }
        catch (CoreException e)
        {
            Activator.logError("git checkout: post-checkout workspace refresh failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            refreshWarning.set("Workspace refresh after the checkout failed (" + e.getMessage() //$NON-NLS-1$
                + "); the EDT model may be stale until a manual refresh."); //$NON-NLS-1$
        }
    }
}
