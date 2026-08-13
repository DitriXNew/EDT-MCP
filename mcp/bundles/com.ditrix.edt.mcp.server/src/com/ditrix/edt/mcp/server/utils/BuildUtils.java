/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

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

            // Wait for all derived data computations
            Activator.logInfo("Waiting for derived data computations for: " + project.getName()); //$NON-NLS-1$
            boolean completed = ddManager.waitAllComputations(timeoutMs);
            
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

        boolean[] drained = new boolean[1];
        BoundedJob.Result result =
            BoundedJob.run("Waiting for the .mdo export of " + project.getName(), timeoutMs, //$NON-NLS-1$
                monitor -> drained[0] = ddManager.waitComputation(timeoutMs, true,
                    EXPORT_OBJECTS_SEGMENT, EXPORT_BLOBS_SEGMENT));

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
