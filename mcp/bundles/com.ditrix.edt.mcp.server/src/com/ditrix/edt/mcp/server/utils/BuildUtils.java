/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for build-related operations.
 */
public final class BuildUtils
{
    /** Default timeout for waiting derived data computations (5 minutes) */
    private static final long DEFAULT_DD_TIMEOUT_MS = 5L * 60 * 1000;

    /**
     * The derived-data segments the {@code .mdo} export runs under: objects and their external
     * blobs. Mirrored, not imported: EDT declares them as public constants
     * ({@code CoreDerivedDataContributor.EXPORT_OBJECTS_SEGMENT_ID} /
     * {@code EXPORT_BLOBS_SEGMENT_ID}, verified in com._1c.g5.v8.dt.core 28.0.0), but the class
     * sits in an {@code internal} package the core bundle does not export, so importing it would
     * be an illegal OSGi dependency.
     * <p>
     * The pair is EDT's own: its synchronous save waits on exactly these two and no others. The
     * third export segment, {@code EXP_CL}, only removes emptied resource folders afterwards and
     * is deliberately not waited on here - the platform does not wait for it either.
     */
    private static final String EXPORT_OBJECTS_SEGMENT = "EXP_O"; //$NON-NLS-1$

    /** @see #EXPORT_OBJECTS_SEGMENT */
    private static final String EXPORT_BLOBS_SEGMENT = "EXP_B"; //$NON-NLS-1$

    /**
     * Per project: the most recently STARTED export wait. Keyed by project name and never larger
     * than the number of projects in the workspace.
     * <p>
     * This is the accumulation limit described on {@link #waitForDiskExport}: while a slot is
     * unreturned and unexpired, the platform call from a previous request is still outstanding, and
     * starting a second one would add another Job nobody can stop.
     */
    private static final ConcurrentMap<String, ExportWaitSlot> EXPORT_WAIT_RETURNED = new ConcurrentHashMap<>();


    private BuildUtils()
    {
        // Utility class
    }
    
    /**
     * Waits for all build jobs to complete.
     * Joins both auto-build and manual-build job families.
     * 
     * @param monitor progress monitor
     */
    public static void waitForBuildJobs(IProgressMonitor monitor)
    {
        try
        {
            IJobManager jobManager = Job.getJobManager();
            jobManager.join(ResourcesPlugin.FAMILY_AUTO_BUILD, monitor);
            jobManager.join(ResourcesPlugin.FAMILY_MANUAL_BUILD, monitor);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            Activator.logError("Wait for build jobs interrupted", e); //$NON-NLS-1$
        }
    }
    
    /**
     * Waits for build jobs and derived data computations to complete.
     * 
     * <p>Note: This method does NOT wait for lifecycle events. If you need to wait for
     * project restart during clean build, use {@link LifecycleWaiter#prepareForRestart(IDtProject)}
     * BEFORE triggering the build, then call {@link LifecycleWaiter.ProjectRestartWaiter#await(long)}.
     * 
     * @param project the IProject to wait for
     * @param monitor progress monitor
     */
    public static void waitForBuildAndDerivedData(IProject project, IProgressMonitor monitor)
    {
        waitForBuildAndDerivedData(project, DEFAULT_DD_TIMEOUT_MS, monitor);
    }
    
    /**
     * Waits for build jobs and derived data computations to complete.
     * 
     * <p>Note: This method does NOT wait for lifecycle events. If you need to wait for
     * project restart during clean build, use {@link LifecycleWaiter#prepareForRestart(IDtProject)}
     * BEFORE triggering the build, then call {@link LifecycleWaiter.ProjectRestartWaiter#await(long)}.
     * 
     * @param project the IProject to wait for
     * @param timeoutMs timeout in milliseconds for derived data wait
     * @param monitor progress monitor
     */
    public static void waitForBuildAndDerivedData(IProject project, long timeoutMs, IProgressMonitor monitor)
    {
        // Step 1: Wait for standard build jobs to complete scheduling
        waitForBuildJobs(monitor);
        
        // Step 2: Wait for derived data computations (validation, form dd, etc.)
        if (project != null)
        {
            waitForDerivedData(project, timeoutMs);
        }
    }
    
    /**
     * Waits for derived data computations to complete for a project.
     * Uses default timeout of 5 minutes.
     * 
     * @param project the IProject to wait for
     */
    public static void waitForDerivedData(IProject project)
    {
        waitForDerivedData(project, DEFAULT_DD_TIMEOUT_MS);
    }
    
    /**
     * Waits for derived data computations to complete for a project.
     * This includes validation, managed form computations, and other EDT-specific processing.
     * 
     * @param project the IProject to wait for
     * @param timeoutMs timeout in milliseconds
     */
    public static void waitForDerivedData(IProject project, long timeoutMs)
    {
        try
        {
            IDerivedDataManager ddManager = resolveDerivedDataManager(project);
            if (ddManager == null)
            {
                return;
            }

            // Wait for the derived data the MODEL needs, not for validation.
            // waitAllComputations() also waits for the check segments, which EDT registers in the
            // post-build stage and which run for HOURS on a large configuration - so every caller
            // that merely wants to edit metadata sat out its whole timeout while a background
            // validation it does not depend on kept the pipeline busy (issue #495).
            // waitImportantDataComputations() waits on the platform's OWN set of segments to wait
            // for during the incremental phase, so the distinction stays EDT's to maintain.
            Activator.logInfo("Waiting for derived data computations for: " + project.getName()); //$NON-NLS-1$
            boolean completed = ddManager.waitImportantDataComputations(timeoutMs);
            
            if (completed)
            {
                Activator.logInfo("Derived data computations completed for: " + project.getName()); //$NON-NLS-1$
            }
            else
            {
                Activator.logInfo("Derived data wait timed out for: " + project.getName()); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error waiting for derived data", e); //$NON-NLS-1$
        }
    }

    /**
     * How a bounded {@link #waitForDiskExport} ended. Three states, not a boolean: a predicate
     * whose failure mode is indistinguishable from its negative answer cannot carry a verdict, so
     * "the queue did not drain" and "we could not even ask" must not collapse onto the same value.
     */
    public enum DiskExportState
    {
        /** The export queue drained within the deadline. See the caveat on {@link #waitForDiskExport}. */
        DRAINED,
        /** The queue did not drain within the deadline, so the files on disk may still be stale. */
        PENDING,
        /** The export state could not be observed at all (the project is not a DT project, or a
         *  required EDT service is unavailable). NOT evidence that anything is wrong. */
        UNOBSERVABLE
    }

    /**
     * Waits until the project's pending {@code .mdo} export has drained off the derived-data
     * pipeline, so what the caller reports about disk is true when it says it.
     * <p>
     * <b>Why this is needed at all.</b> {@link BmTransactions#forceExportToDisk} and the metadata
     * refactorings do not write files; they hand save tasks to the derived-data pipeline and
     * return. The platform's own synchronous save (its {@code saveNow}) differs from the
     * asynchronous one by exactly this wait, on exactly these two segments, so this is the same
     * barrier EDT uses on itself - not an approximation of one.
     * <p>
     * <b>What DRAINED does and does not prove.</b> It proves the export work finished. It does NOT
     * prove the bytes are correct: the platform's export task processor catches {@code Throwable}
     * around each file, logs it, and lets the computation complete normally, so a write that failed
     * (file locked, disk full, permissions) still drains. Treat DRAINED as "nothing is queued any
     * more", never as "the file is known good".
     * <p>
     * <b>Why the work is wrapped in a {@link BoundedJob}.</b> The platform's own timeout argument is
     * not a bound: its wait spends the full timeout draining accumulated contexts and only then
     * starts a FRESH deadline for the segment wait, and one leg of the context drain calls
     * {@code Object.wait()} with no argument at all. Passing a timeout therefore buys an advisory,
     * not a limit, and unattended safety needs a real one.
     * <p>
     * <b>What the deadline does NOT do.</b> {@code waitComputation} takes no progress monitor, so
     * cancelling the job cannot stop the wait - the deadline frees the CALLER, and the platform
     * call may still be running afterwards. That is why at most ONE export wait per project is
     * outstanding at a time: while a previous one has not come back, this returns
     * {@link DiskExportState#PENDING} immediately instead of scheduling a second unstoppable Job.
     * A wedged pipeline therefore costs one Job per project, not one per request.
     * <p>
     * That claim is itself bounded: a wait that never comes back would otherwise shut its project
     * out for the rest of the session, so the claim also lapses on its own deadline. The honest
     * statement is "at most one outstanding export wait per project at a time", not "ever".
     *
     * @param project the workspace project whose export queue to drain
     * @param timeoutMs the hard deadline in milliseconds
     * @return how the wait ended; never {@code null}
     */
    public static DiskExportState waitForDiskExport(IProject project, long timeoutMs)
    {
        IDerivedDataManager ddManager;
        try
        {
            ddManager = resolveDerivedDataManager(project);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Error resolving the derived data manager for disk export", e); //$NON-NLS-1$
            return DiskExportState.UNOBSERVABLE;
        }
        if (ddManager == null)
        {
            return DiskExportState.UNOBSERVABLE;
        }

        // One outstanding waiter per project, at most. The platform's waitComputation takes no
        // progress monitor, so the deadline below can free the CALLER but cannot stop the WAIT -
        // and this barrier runs after every successful metadata write, so a wedged pipeline would
        // otherwise get a fresh unkillable Job per call until the workbench ran out of them.
        // Pretending we can cancel it would be the dishonest fix; this is the honest limit.
        String key = project.getName();
        AtomicBoolean returned = beginExportWait(key, timeoutMs);
        if (returned == null)
        {
            Activator.logInfo("A previous .mdo export wait for " + key //$NON-NLS-1$
                + " has not returned yet; not starting another one"); //$NON-NLS-1$
            return DiskExportState.PENDING;
        }

        boolean[] drained = new boolean[1];
        BoundedJob.Result result =
            BoundedJob.run("Waiting for the .mdo export of " + key, timeoutMs, //$NON-NLS-1$
                monitor -> {
                    try
                    {
                        drained[0] = ddManager.waitComputation(timeoutMs, true,
                            EXPORT_OBJECTS_SEGMENT, EXPORT_BLOBS_SEGMENT);
                    }
                    finally
                    {
                        // Reopens the gate for this project. Set from the JOB thread, because the
                        // whole point is to know when the platform call came back - which, on the
                        // timeout path, is after the caller has already gone.
                        returned.set(true);
                    }
                });
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            // The ONLY outcome that proves the work neither ran nor ever will: cancelling it is
            // what kept it from starting. Nothing is outstanding, so reopen at once.
            //
            // NOT_RUN deliberately does NOT join this: there the job left the queue without our
            // cancel, which happens with a SUSPENDED job manager - the job is still scheduled and
            // will run when the manager resumes, so reopening now would let a second one be
            // scheduled on top of it. INTERRUPTED is left closed for the same reason. Neither can
            // strand the slot, because the slot also reopens on its own deadline.
            returned.set(true);
        }

        if (result.getOutcome() == BoundedJob.Outcome.COMPLETED && result.getFailure() == null)
        {
            return drained[0] ? DiskExportState.DRAINED : DiskExportState.PENDING;
        }
        if (result.getFailure() != null)
        {
            // Includes an InterruptedException raised INSIDE the job: that interrupted the job's
            // own thread, not this one, so the caller's interrupt flag must be left alone. The one
            // outcome where the CALLER was interrupted is INTERRUPTED, and BoundedJob has already
            // restored the flag for it before returning.
            Activator.logError("Error waiting for the .mdo export of " + project.getName(), //$NON-NLS-1$
                result.getFailure());
        }
        Activator.logInfo("Disk export did not drain for " + project.getName() + " (" //$NON-NLS-1$ //$NON-NLS-2$
            + result.getOutcome() + " after " + result.getElapsedMs() + "ms)"); //$NON-NLS-1$ //$NON-NLS-2$
        if (result.getOutcome() == BoundedJob.Outcome.NOT_RUN)
        {
            // Something OTHER than our deadline kept the wait from ever running, so the pipeline
            // was never asked anything - and this can happen in milliseconds. That is "not
            // observed", not "still pending": refusing here would fail a healthy call while
            // claiming a 60s export timeout that never elapsed.
            //
            // TIMED_OUT_BEFORE_START deliberately does NOT join this branch. There the work also
            // never ran, but it was OUR deadline that elapsed - 60s passed and the export is still
            // unaccounted for, which is exactly the state the refusal exists to report.
            return DiskExportState.UNOBSERVABLE;
        }
        return DiskExportState.PENDING;
    }

    /**
     * Claims the single export-wait slot for a project.
     * <p>
     * Package-visible so the accumulation limit can be pinned without a live pipeline: it is the
     * whole answer to "the deadline frees the caller but cannot stop the platform call", and a
     * limit nobody tests is a limit that quietly stops holding.
     *
     * @param projectName the project whose slot to claim
     * @param timeoutMs the wait's deadline, which also sizes how long an unreturned claim holds
     * @return the flag the wait must set when it returns, or {@code null} when a previous wait for
     *     this project has not come back yet and no new one may be started
     */
    static AtomicBoolean beginExportWait(String projectName, long timeoutMs)
    {
        AtomicBoolean[] granted = new AtomicBoolean[1];
        long reopenAtMs = System.currentTimeMillis() + slotHoldMs(timeoutMs);
        // compute(), not get()+put(): the map holds the bin lock across the whole decision, so two
        // writes finishing together cannot both see a free slot and both schedule a Job. A
        // check-then-act here would have made the limit hold only when it was not needed.
        EXPORT_WAIT_RETURNED.compute(projectName, (name, prior) -> {
            if (prior != null && !prior.returned.get() && System.currentTimeMillis() < prior.reopenAtMs)
            {
                return prior;
            }
            granted[0] = new AtomicBoolean();
            return new ExportWaitSlot(granted[0], reopenAtMs);
        });
        return granted[0];
    }

    /**
     * How long a slot stays claimed by a wait that never came back.
     * <p>
     * A stuck waiter must not shut a project out for the rest of the session, so the claim expires
     * even when nothing ever sets the flag. The platform wait can legitimately outlive its own
     * timeout argument by roughly double (it spends the budget draining contexts and then starts a
     * fresh deadline), so the hold is three times the deadline: comfortably past any wait that is
     * merely slow, and still bounded for one that is wedged.
     *
     * @param timeoutMs the wait's deadline
     * @return the hold in milliseconds
     */
    private static long slotHoldMs(long timeoutMs)
    {
        return 3 * Math.max(1L, timeoutMs);
    }

    /** A project's export-wait claim: the flag its job sets, and when the claim lapses anyway. */
    private static final class ExportWaitSlot
    {
        private final AtomicBoolean returned;
        private final long reopenAtMs;

        ExportWaitSlot(AtomicBoolean returned, long reopenAtMs)
        {
            this.returned = returned;
            this.reopenAtMs = reopenAtMs;
        }
    }

    /** Drops every recorded export-wait slot; for tests, which must not inherit each other's state. */
    static void forgetExportWaits()
    {
        EXPORT_WAIT_RETURNED.clear();
    }

    /**
     * Resolves the project's derived-data manager, logging which link of the chain was missing.
     *
     * @param project the workspace project
     * @return the manager, or {@code null} when the project or a service is unavailable
     */
    private static IDerivedDataManager resolveDerivedDataManager(IProject project)
    {
        IDerivedDataManagerProvider ddProvider = Activator.getDefault().getDerivedDataManagerProvider();
        if (ddProvider == null)
        {
            Activator.logInfo("IDerivedDataManagerProvider not available, skipping DD wait"); //$NON-NLS-1$
            return null;
        }

        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            Activator.logInfo("IDtProjectManager not available, skipping DD wait"); //$NON-NLS-1$
            return null;
        }

        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            Activator.logInfo("Not a DtProject, skipping DD wait: " + project.getName()); //$NON-NLS-1$
            return null;
        }

        IDerivedDataManager ddManager = ddProvider.get(dtProject);
        if (ddManager == null)
        {
            Activator.logInfo("IDerivedDataManager not available for project: " + project.getName()); //$NON-NLS-1$
        }
        return ddManager;
    }
}
