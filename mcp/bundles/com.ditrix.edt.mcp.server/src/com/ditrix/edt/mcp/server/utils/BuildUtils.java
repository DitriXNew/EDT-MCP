/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Slot-key prefix of the per-object validation wait, kept apart from the export slot. */
    private static final String VALIDATION_SLOT_PREFIX = "validation:"; //$NON-NLS-1$

    /** Slot-key prefix of the bounded marker read of a write, apart from both waits. */
    private static final String MARKER_READ_SLOT_PREFIX = "markers:"; //$NON-NLS-1$

    /**
     * The derived-data segments EDT's validation runs under: the check framework's model checks
     * (critical-data-integrity, normal, complex) and the validator segments. Mirrored, not imported,
     * for the same reason as {@link #EXPORT_OBJECTS_SEGMENT}: the declaring classes are internal.
     * The BSL language checks are left out - their markers are keyed by module URI, not by a BM top
     * object. A segment that does not apply to an object is never pending for it, so one list serves
     * every object kind. Only the ids a project's pipeline registers are ever sent (see
     * {@link #registeredSegments}).
     */
    static final List<String> VALIDATION_SEGMENTS = List.of("CDI_CHECKS_SEGMENT", //$NON-NLS-1$
        "M_CHECKS_SEGMENT", "CM_CHECKS_SEGMENT", "XDTO_VAL", "STYLE_VAL", "CMI_VAL", "AGGR_VAL"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$


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

            // Wait for ALL derived data. Callers of this method depend on that: the cascade
            // pre-flight opens a BM batch session afterwards, and RevalidateObjectsTool reports
            // "Revalidation completed" on the strength of it. Waiting only for the model would let
            // both claim something that has not happened. The model-only probe lives in
            // ProjectStateChecker.isModelDataComputed, bounded and non-blocking, for the write gate.
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

    /** How a bounded {@link #waitForObjectValidation} ended. */
    public enum ValidationState
    {
        /** EDT confirmed the validation segments computed for every named object. */
        COMPLETE,
        /** Not confirmed within the deadline, or a previous wait for the project is still out. */
        PENDING,
        /**
         * Could not be asked or answered: no derived-data service, not a DT project, no registered
         * validation segment, a failed (broken) record, or the job never ran.
         */
        UNOBSERVABLE
    }

    /**
     * The derived-data calls one validation wait makes; a seam so the segment guard is testable
     * without a live pipeline.
     */
    interface ValidationPipeline
    {
        /**
         * @param segmentId a segment id
         * @return whether this project's pipeline registers it; {@code false} when unprovable
         */
        boolean registers(String segmentId);

        /**
         * {@code IDerivedDataManager.waitComputation(Map, long)}.
         *
         * @param scope per top-object id, registered segment ids only
         * @param timeoutMs the platform timeout, positive
         * @return the platform's answer
         * @throws InterruptedException when the waiting thread is interrupted
         */
        boolean waitComputation(Map<Long, Collection<String>> scope, long timeoutMs) throws InterruptedException;

        /**
         * {@code IDerivedDataManager.isComputed(long, Collection)}: none of the segments is still
         * pending on the object's record.
         *
         * @param topObjectId the BM top-object id
         * @param segmentIds registered segment ids, not empty
         * @return whether the object's record has none of them pending
         */
        boolean isComputed(long topObjectId, Collection<String> segmentIds);
    }

    /**
     * Waits until EDT has validated the given top objects - per object, not project-wide.
     * <p>
     * {@code IDerivedDataManager.waitComputation(Map, long)} first drains the accumulated change
     * contexts, then waits until none of the registered {@link #VALIDATION_SEGMENTS} is pending for
     * the named objects, raising their pipeline priority meanwhile. Its timeout is advisory (it
     * retries once), so it runs inside a {@link BoundedJob}, with at most one outstanding validation
     * wait per project, exactly like {@link #waitForDiskExport}. Must not be called inside a BM
     * transaction.
     *
     * @param project the workspace project owning the objects
     * @param topObjectIds BM ids of the top objects to wait for; empty yields UNOBSERVABLE
     * @param timeoutMs the hard deadline in milliseconds
     * @return how the wait ended; never {@code null}
     */
    public static ValidationState waitForObjectValidation(IProject project, Collection<Long> topObjectIds,
        long timeoutMs)
    {
        if (topObjectIds == null || topObjectIds.isEmpty())
        {
            // The platform rejects an empty scope; there is nothing to observe.
            return ValidationState.UNOBSERVABLE;
        }
        IDerivedDataManager ddManager;
        try
        {
            ddManager = resolveDerivedDataManager(project);
        }
        catch (RuntimeException e)
        {
            Activator.logWarning("Could not resolve the derived data manager for validation: " + e); //$NON-NLS-1$
            return ValidationState.UNOBSERVABLE;
        }
        if (ddManager == null)
        {
            return ValidationState.UNOBSERVABLE;
        }
        return waitForObjectValidation(pipelineOf(ddManager), project.getName(), topObjectIds, timeoutMs);
    }

    /**
     * The bounded wait over a {@link ValidationPipeline}: slot, job, outcome mapping.
     *
     * @param pipeline the project's derived-data calls
     * @param key the project name, the slot key
     * @param topObjectIds BM ids of the top objects, not empty
     * @param timeoutMs the hard deadline in milliseconds
     * @return how the wait ended; never {@code null}
     */
    static ValidationState waitForObjectValidation(ValidationPipeline pipeline, String key,
        Collection<Long> topObjectIds, long timeoutMs)
    {
        long platformTimeoutMs = platformTimeoutMs(timeoutMs);
        AtomicBoolean returned = beginValidationWait(key, platformTimeoutMs);
        if (returned == null)
        {
            Activator.logInfo("A previous validation wait for " + key //$NON-NLS-1$
                + " has not returned yet; not starting another one"); //$NON-NLS-1$
            return ValidationState.PENDING;
        }

        ValidationState[] state = { ValidationState.PENDING };
        BoundedJob.Result result = BoundedJob.run("Waiting for the validation of written objects in " + key, //$NON-NLS-1$
            platformTimeoutMs, monitor -> {
                try
                {
                    state[0] = validate(pipeline, topObjectIds, platformTimeoutMs);
                }
                finally
                {
                    returned.set(true);
                }
            });
        if (result.getOutcome() == BoundedJob.Outcome.TIMED_OUT_BEFORE_START)
        {
            // Never ran and never will: nothing outstanding, reopen at once (see waitForDiskExport).
            returned.set(true);
        }
        if (result.getOutcome() == BoundedJob.Outcome.COMPLETED && result.getFailure() == null)
        {
            if (state[0] == ValidationState.UNOBSERVABLE)
            {
                Activator.logInfo("Validation of the written objects in " + key //$NON-NLS-1$
                    + " is not observable: no registered validation segment, or a failed record"); //$NON-NLS-1$
            }
            return state[0];
        }
        if (result.getFailure() != null || result.getOutcome() == BoundedJob.Outcome.NOT_RUN)
        {
            // The pipeline was never successfully asked: unobserved, not pending. Not an ERROR -
            // it degrades a write's marker report, it does not fail the write.
            Activator.logWarning("Validation wait for " + key + " was not observable (" //$NON-NLS-1$ //$NON-NLS-2$
                + result.getOutcome() + ", " + result.getFailure() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
            return ValidationState.UNOBSERVABLE;
        }
        return ValidationState.PENDING;
    }

    /**
     * One wait, run inside the bounded job: registered segments only, then the platform wait, then
     * the record re-check.
     * <p>
     * An unregistered id must never reach the platform: on EDT 2026.2.1
     * {@code AsyncProcessingPipeline.increasePriority(Map)} queues it in the priority task queue
     * even without a segment group, and {@code PriorityTaskQueue.hasAllowedPriorityTasks} then
     * dereferences its missing dependency set on every scheduling pass.
     * <p>
     * {@code waitComputation} counts a BROKEN record (a validator failed three times) as done
     * while the failed segment stays unconfirmed on it, so a {@code true} is re-checked per object
     * with {@code isComputed(long, Collection)}, which ignores the broken flag.
     *
     * @param pipeline the project's derived-data calls
     * @param topObjectIds BM ids of the top objects, not empty
     * @param platformTimeoutMs the platform timeout, positive
     * @return how the wait ended
     * @throws InterruptedException when the job thread is interrupted
     */
    static ValidationState validate(ValidationPipeline pipeline, Collection<Long> topObjectIds,
        long platformTimeoutMs) throws InterruptedException
    {
        List<String> segments = registeredSegments(pipeline);
        if (segments.isEmpty())
        {
            return ValidationState.UNOBSERVABLE;
        }
        Map<Long, Collection<String>> scope = new LinkedHashMap<>();
        for (Long id : topObjectIds)
        {
            scope.put(id, segments);
        }
        if (!pipeline.waitComputation(scope, platformTimeoutMs))
        {
            return ValidationState.PENDING;
        }
        for (Long id : topObjectIds)
        {
            if (!pipeline.isComputed(id.longValue(), segments))
            {
                return ValidationState.UNOBSERVABLE;
            }
        }
        return ValidationState.COMPLETE;
    }

    /**
     * The {@link #VALIDATION_SEGMENTS} this project's pipeline registers.
     *
     * @param pipeline the project's derived-data calls
     * @return the registered ids, in list order; empty when none is provably registered
     */
    static List<String> registeredSegments(ValidationPipeline pipeline)
    {
        List<String> registered = new ArrayList<>();
        for (String segmentId : VALIDATION_SEGMENTS)
        {
            if (pipeline.registers(segmentId))
            {
                registered.add(segmentId);
            }
        }
        return registered;
    }

    /**
     * The production {@link ValidationPipeline}.
     * <p>
     * EDT has no API that lists a pipeline's segments. {@code IDerivedDataManager.isComputed(String...)}
     * reaches {@code AsyncProcessingPipeline.isSegmentComputed}, which asserts "Unsupported segment
     * is specified" on the same map whose entries are added together with the segment group and
     * the dependency set; so a probe that returns proves the id is registered, and a throw of any
     * kind counts as unregistered.
     */
    private static ValidationPipeline pipelineOf(IDerivedDataManager ddManager)
    {
        return new ValidationPipeline()
        {
            @Override
            public boolean registers(String segmentId)
            {
                try
                {
                    ddManager.isComputed(new String[] { segmentId });
                    return true;
                }
                catch (RuntimeException e)
                {
                    return false;
                }
            }

            @Override
            public boolean waitComputation(Map<Long, Collection<String>> scope, long timeoutMs)
                throws InterruptedException
            {
                return ddManager.waitComputation(scope, timeoutMs);
            }

            @Override
            public boolean isComputed(long topObjectId, Collection<String> segmentIds)
            {
                return ddManager.isComputed(topObjectId, segmentIds);
            }
        };
    }

    /**
     * The timeout handed to the platform: always positive, because EDT 2026.1.1 asserts
     * {@code timeout > 0} in {@code waitComputationExt}.
     *
     * @param timeoutMs the requested deadline
     * @return a deadline of at least one millisecond
     */
    static long platformTimeoutMs(long timeoutMs)
    {
        return Math.max(1L, timeoutMs);
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
        return beginWait(projectName, timeoutMs);
    }

    /**
     * Claims the single validation-wait slot for a project. A separate key from the export slot, so
     * the two waits of one write never block each other.
     *
     * @param projectName the project whose slot to claim
     * @param timeoutMs the wait's deadline, which also sizes how long an unreturned claim holds
     * @return the flag the wait must set when it returns, or {@code null} when a previous
     *     validation wait for this project has not come back yet
     */
    static AtomicBoolean beginValidationWait(String projectName, long timeoutMs)
    {
        return beginWait(VALIDATION_SLOT_PREFIX + projectName, timeoutMs);
    }

    /**
     * Claims the single bounded-marker-read slot for a project, so reads stuck behind a model
     * service operation do not pile up one job per write.
     *
     * @param projectName the project whose slot to claim
     * @param timeoutMs the read's deadline, which also sizes how long an unreturned claim holds
     * @return the flag the read must set when it returns, or {@code null} when a previous read for
     *     this project has not come back yet
     */
    static AtomicBoolean beginMarkerRead(String projectName, long timeoutMs)
    {
        return beginWait(MARKER_READ_SLOT_PREFIX + projectName, timeoutMs);
    }

    private static AtomicBoolean beginWait(String slotKey, long timeoutMs)
    {
        AtomicBoolean[] granted = new AtomicBoolean[1];
        long reopenAtMs = System.currentTimeMillis() + slotHoldMs(timeoutMs);
        // compute(), not get()+put(): the map holds the bin lock across the whole decision, so two
        // writes finishing together cannot both see a free slot and both schedule a Job. A
        // check-then-act here would have made the limit hold only when it was not needed.
        EXPORT_WAIT_RETURNED.compute(slotKey, (name, prior) -> {
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
