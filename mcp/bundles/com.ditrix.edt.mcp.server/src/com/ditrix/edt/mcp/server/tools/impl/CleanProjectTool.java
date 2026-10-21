/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;

import com._1c.g5.v8.dt.core.lifecycle.ProjectStartType;
import com._1c.g5.v8.dt.core.lifecycle.WorkspaceProjectStartRequest;
import com._1c.g5.v8.dt.core.platform.IWorkspaceOrchestrator;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BoundedJob;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.RestartJobFailureSignal;

/**
 * Tool to clean EDT project and trigger full revalidation.
 * Uses Eclipse Project -> Clean command which triggers EDT full rebuild.
 */
public class CleanProjectTool implements IMcpTool
{
    public static final String NAME = "clean_project"; //$NON-NLS-1$

    /** Default timeout for waiting project lifecycle restart (3 minutes) */
    private static final long DEFAULT_LIFECYCLE_TIMEOUT_MS = 3L * 60 * 1000;

    /** Shared by all calls; held by the recovery worker until its platform work actually ends. */
    private static final ReentrantLock RECOVERY_LOCK = new ReentrantLock();

    /** Input key: bound on the clean-build phase, in seconds. */
    static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /**
     * Default bound on the clean-build phase (2 minutes). A healthy clean of the whole
     * phase takes seconds; the bound only has to be far above that, because its job is to
     * turn a wedged platform call into an honest error instead of an endless MCP request.
     * Raise it (parameter or preference) for very large configurations.
     */
    static final int DEFAULT_CLEAN_TIMEOUT_SECONDS = 120;

    /**
     * How long to wait for a DT project to (re)appear before refusing a named project as one EDT
     * never started.
     *
     * <p>{@code DtProjectManager} REMOVES its entry while a project context is being disposed, so
     * a project whose context is restarting - the state {@code clean_project} itself leaves a
     * project in, and therefore the state a second call lands on - answers {@code null} for a
     * moment. Refusing on that instant would turn a transient into a hard "not an EDT project".
     */
    private static final long DT_PROJECT_SETTLE_TIMEOUT_MS = 10_000;

    /** How often the settle re-asks {@code IDtProjectManager}. */
    private static final long DT_PROJECT_SETTLE_POLL_MS = 250;

    /** Smallest accepted clean-build bound, in seconds. */
    private static final int MIN_CLEAN_TIMEOUT_SECONDS = 10;

    /** Largest accepted clean-build bound, in seconds. */
    private static final int MAX_CLEAN_TIMEOUT_SECONDS = 3600;

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Rebuild an EDT project from the on-disk src/ files and revalidate everything. Direction DISK " //$NON-NLS-1$
            + "-> MODEL; slow, and it DISCARDS unsaved in-memory model edits - save first. For one " //$NON-NLS-1$
            + "externally-edited object prefer revalidate_objects; for the opposite direction see " //$NON-NLS-1$
            + "resync_to_disk. Parameters and examples: get_tool_guide('clean_project')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Name of the project to clean (optional, cleans all EDT projects if not specified)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty(KEY_TIMEOUT, "How long to wait for the clean build itself, per project, " //$NON-NLS-1$
                + "in seconds (default " //$NON-NLS-1$
                + DEFAULT_CLEAN_TIMEOUT_SECONDS + ", clamped to " + MIN_CLEAN_TIMEOUT_SECONDS + ".." //$NON-NLS-1$ //$NON-NLS-2$
                + MAX_CLEAN_TIMEOUT_SECONDS + "). On expiry the call fails with a timeout error instead of " //$NON-NLS-1$
                + "waiting forever; the clean may still be running in EDT afterwards. Does not cover the " //$NON-NLS-1$
                + "subsequent revalidation wait.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("projectsCleaned", "Number of projects that were cleaned") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("projects", "Names of the projects that were cleaned") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("notRestarted", "Projects still stopped after a failed EDT restart and recovery.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("recovered", "Dependent projects started after this call's recovery attempt following an incomplete EDT restart.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("restartFailure", "Platform restart status and exception messages.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable completion message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        
        // Refuse only the transient BUILDING state; a missing/closed project
        // falls through to the value-naming "Project not found" below.
        if (projectName != null && !projectName.isEmpty())
        {
            String building = ProjectStateChecker.buildingErrorOrNull(projectName);
            if (building != null)
            {
                return ToolResult.error(building).toJson();
            }
        }
        
        return cleanProject(projectName, resolveCleanTimeoutMs(params));
    }

    /**
     * Resolves the clean-build bound for this call: the explicit {@code timeout} argument
     * when given, else the configured per-tool default, clamped to the accepted range.
     *
     * @param params the raw tool arguments
     * @return the bound in milliseconds
     */
    static long resolveCleanTimeoutMs(Map<String, String> params)
    {
        int configuredDefault = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_TIMEOUT, DEFAULT_CLEAN_TIMEOUT_SECONDS);
        int seconds = JsonUtils.extractIntArgument(params, KEY_TIMEOUT, configuredDefault);
        return clampTimeoutSeconds(seconds) * 1000L;
    }

    /**
     * Clamps a clean-build bound to the accepted range.
     *
     * @param seconds the requested bound in seconds
     * @return the accepted bound in seconds
     */
    static int clampTimeoutSeconds(int seconds)
    {
        if (seconds < MIN_CLEAN_TIMEOUT_SECONDS)
        {
            return MIN_CLEAN_TIMEOUT_SECONDS;
        }
        return Math.min(seconds, MAX_CLEAN_TIMEOUT_SECONDS);
    }

    /**
     * Cleans project and triggers revalidation, bounding the clean build with the
     * configured per-tool default.
     *
     * @param projectName name of the project to clean (null for all projects)
     * @return JSON string with result
     */
    public static String cleanProject(String projectName)
    {
        int configured = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, KEY_TIMEOUT, DEFAULT_CLEAN_TIMEOUT_SECONDS);
        return cleanProject(projectName, clampTimeoutSeconds(configured) * 1000L);
    }

    /**
     * Cleans project and triggers revalidation.
     *
     * <p>To avoid race conditions, lifecycle listeners are registered BEFORE
     * triggering the clean build operation.
     *
     * @param projectName name of the project to clean (null for all projects)
     * @param cleanTimeoutMs bound on the clean-build phase, in milliseconds
     * @return JSON string with result
     */
    public static String cleanProject(String projectName, long cleanTimeoutMs)
    {
        try
        {
            IDtProjectManager manager = Activator.getDefault().getDtProjectManager();
            CleanCollection collection = collectProjectsToClean(projectName, manager);
            if (collection.error != null)
            {
                return collection.error;
            }
            return runCleanLifecycle(collection, cleanTimeoutMs, DEFAULT_LIFECYCLE_TIMEOUT_MS,
                (info, monitor) -> cleanSingleProject(info.project, monitor),
                new RestartSupport(manager, Activator.getDefault().getWorkspaceOrchestrator()));
        }
        catch (Exception e)
        {
            Activator.logError("Error during project clean", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }

    /** Runs the production clean phases with injectable platform operations for headless tests. */
    static String runCleanLifecycle(CleanCollection collection, long cleanTimeoutMs, long lifecycleTimeoutMs,
        ICleanAction action, RestartSupport support)
    {
        boolean cleanCommitted = false;
        long restartDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifecycleTimeoutMs);
        try (RestartJobFailureSignal failure = support.watchFailures())
        {
            RestartScope scope = registerRestartWaiters(collection.projectsToClean,
                support::dependents, support::prepare);
            try
            {
                String cleanError = runCleanPhase(collection.projectsToClean, cleanTimeoutMs, action);
                if (cleanError != null)
                {
                    return cleanError;
                }
                cleanCommitted = !collection.projectsToClean.isEmpty();

                for (RestartProject project : scope.all())
                {
                    if (project.waiter != null)
                    {
                        project.waiter.await(lifecycleTimeoutMs, failure);
                    }
                }

                // A failure can abort all project waiters while a different restart still owns them.
                // Drain those jobs within the SAME restart allowance before deciding what is stranded.
                // A failure can arrive during settlement, so drain even before one has been observed.
                boolean restartJobsFinished = LifecycleWaiter.waitForRestartJobs(failure,
                    Math.max(0L, TimeUnit.NANOSECONDS.toMillis(restartDeadline - System.nanoTime())));

                // Always ask the state as well as observing events (#706). Only an observed job
                // failure changes the verdict; a plain wait expiry remains logged-only (#349).
                List<RestartProject> stranded = notStarted(scope.all(), support);
                if (failure.hasFailed() && !stranded.isEmpty())
                {
                    List<ProjectCleanInfo> recoverable = new ArrayList<>();
                    for (RestartProject sibling : scope.siblings)
                    {
                        IProject project = sibling.info.project;
                        if (restartJobsFinished && stranded.contains(sibling)
                            && project != null && project.exists() && project.isOpen())
                        {
                            recoverable.add(sibling.info);
                        }
                    }
                    String recoveryNote;
                    if (restartJobsFinished)
                    {
                        recoveryNote = recover(recoverable, cleanTimeoutMs, support, failure);
                    }
                    else
                    {
                        recoveryNote = " Recovery was not attempted because EDT restart jobs are still pending " //$NON-NLS-1$
                            + "after the restart wait allowance. Check list_projects before retrying."; //$NON-NLS-1$
                        Activator.logWarning(recoveryNote);
                    }
                    // Never wait for derived data on a target that failed to start.
                    for (RestartProject target : scope.targets)
                    {
                        if (target.info.dtProject == null || support.isStarted(target.info.dtProject))
                        {
                            support.awaitDerivedData(target.info.project);
                        }
                    }
                    List<RestartProject> stillStopped = notStarted(scope.all(), support);
                    List<String> recovered = new ArrayList<>();
                    for (ProjectCleanInfo candidate : recoverable)
                    {
                        if (support.isStarted(candidate.dtProject))
                        {
                            recovered.add(candidate.name);
                        }
                    }
                    return restartResult(collection, failure, names(stillStopped), recovered,
                        recoveryNote);
                }

                for (ProjectCleanInfo info : collection.projectsToClean)
                {
                    support.awaitDerivedData(info.project);
                }
                return cleanCompletedResult(collection.projectNamesList, collection.skippedUnstarted);
            }
            finally
            {
                scope.cleanup();
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error during project clean", e); //$NON-NLS-1$
            return (cleanCommitted ? ToolResult.errorAfterMutation(e.getMessage())
                : ToolResult.error(e.getMessage())).toJson();
        }
    }

    /** ONE bounded recovery pass; a broken sibling must not prevent the others being started. */
    private static String recover(List<ProjectCleanInfo> projects, long timeoutMs, RestartSupport support,
        RestartJobFailureSignal failure)
    {
        if (projects.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        AtomicReference<String> admissionNote = new AtomicReference<>(""); //$NON-NLS-1$
        BoundedJob.Result result = BoundedJob.run(NAME + ": recover dependent projects", timeoutMs, //$NON-NLS-1$
            monitor -> admissionNote.set(recoverWithAdmission(projects, deadline, monitor, support, failure)));
        if (result.getOutcome() == BoundedJob.Outcome.COMPLETED && result.getFailure() == null)
        {
            if (!admissionNote.get().isEmpty())
            {
                Activator.logWarning(admissionNote.get());
            }
            return admissionNote.get();
        }
        boolean mayStillRun = result.getOutcome() == BoundedJob.Outcome.TIMED_OUT
            || result.getOutcome() == BoundedJob.Outcome.INTERRUPTED;
        String note = " Recovery attempt ended with " + result.getOutcome() //$NON-NLS-1$
            + (result.getFailure() == null ? "" : ": " + result.getFailure().getMessage()) //$NON-NLS-1$ //$NON-NLS-2$
            + (mayStillRun ? "; it may still be running in EDT." : ".") //$NON-NLS-1$ //$NON-NLS-2$
            + " Check list_projects before retrying."; //$NON-NLS-1$
        Activator.logWarning(note);
        return note;
    }

    /**
     * Admit each single-project start using live state under our process-wide recovery lock.
     * EDT's restart jobs have no scheduling rule to share with recovery, so a platform restart
     * can still be scheduled after the final pending-job check.
     */
    private static String recoverWithAdmission(List<ProjectCleanInfo> projects, long deadline,
        IProgressMonitor monitor, RestartSupport support, RestartJobFailureSignal failure) throws InterruptedException
    {
        if (!RECOVERY_LOCK.tryLock(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
        {
            return " Recovery was not attempted because another clean_project recovery is still running. " //$NON-NLS-1$
                + "Check list_projects before retrying."; //$NON-NLS-1$
        }
        try
        {
            for (ProjectCleanInfo info : projects)
            {
                if (monitor.isCanceled() || System.nanoTime() >= deadline)
                {
                    break;
                }
                IProject project = info.project;
                // Both the candidate snapshot and the earlier restart-job drain can be stale.
                if (project == null || !project.exists() || !project.isOpen() || support.isStarted(info.dtProject))
                {
                    continue;
                }
                if (failure.hasPendingRestartJobs())
                {
                    return " Recovery was not attempted for remaining dependent projects because EDT restart " //$NON-NLS-1$
                        + "jobs became pending before a recovery start. Check list_projects before retrying."; //$NON-NLS-1$
                }
                if (monitor.isCanceled() || System.nanoTime() >= deadline)
                {
                    break;
                }
                for (IDtProject issued : support.recover(Collections.singletonList(info), monitor))
                {
                    long remaining = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    if (!support.isStarted(issued))
                    {
                        support.awaitStarted(issued, remaining);
                    }
                }
            }
            return ""; //$NON-NLS-1$
        }
        finally
        {
            // A timed-out caller cannot release a lock owned by work still running in EDT.
            RECOVERY_LOCK.unlock();
        }
    }

    private static List<RestartProject> notStarted(List<RestartProject> projects, RestartSupport support)
    {
        List<RestartProject> stopped = new ArrayList<>();
        for (RestartProject project : projects)
        {
            if (project.info.dtProject != null && !support.isStarted(project.info.dtProject))
            {
                stopped.add(project);
            }
        }
        return stopped;
    }

    private static List<String> names(List<RestartProject> projects)
    {
        List<String> names = new ArrayList<>();
        for (RestartProject project : projects)
        {
            names.add(project.info.name);
        }
        return names;
    }

    private static String restartResult(CleanCollection collection, RestartJobFailureSignal failure,
        List<String> notRestarted,
        List<String> recovered, String recoveryNote)
    {
        String outcome = failure.getSeverity() == IStatus.CANCEL ? "was cancelled" //$NON-NLS-1$
            : failure.getSeverity() == IStatus.WARNING || failure.getSeverity() == IStatus.INFO
                ? "did not complete" : "failed"; //$NON-NLS-1$ //$NON-NLS-2$
        String message = "Clean build completed for " + String.join(", ", collection.projectNamesList) //$NON-NLS-1$ //$NON-NLS-2$
            + ", but EDT's 'Project services restart' " + outcome + ": " + failure.getFailure() //$NON-NLS-1$ //$NON-NLS-2$
            + ". Back after this call's recovery attempt: " //$NON-NLS-1$
            + (recovered.isEmpty() ? "none" : String.join(", ", recovered)) + ". "; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (!collection.skippedUnstarted.isEmpty())
        {
            message += "Skipped projects EDT has not started (no DtProject): " //$NON-NLS-1$
                + String.join(", ", collection.skippedUnstarted) + ". "; //$NON-NLS-1$ //$NON-NLS-2$
        }
        ToolResult result;
        if (notRestarted.isEmpty())
        {
            message += "All projects in the restart scope are started again."; //$NON-NLS-1$
            result = ToolResult.success().put("message", message + recoveryNote); //$NON-NLS-1$
        }
        else
        {
            message += "Still not started: " + String.join(", ", notRestarted) //$NON-NLS-1$ //$NON-NLS-2$
                + " - EDT stopped these projects during the restart and they are not started at the final state check. " //$NON-NLS-1$
                + "Close and reopen existing projects in EDT, or delete_project an existing broken project " //$NON-NLS-1$
                + "and clean again; recreate or re-import deleted projects. " //$NON-NLS-1$
                + "Check list_projects for current workspace project states."; //$NON-NLS-1$
            result = ToolResult.errorAfterMutation(message + recoveryNote).put("notRestarted", notRestarted); //$NON-NLS-1$
        }
        return result.put("projectsCleaned", collection.projectNamesList.size()) //$NON-NLS-1$
            .put("projects", collection.projectNamesList) //$NON-NLS-1$
            .put("recovered", recovered) //$NON-NLS-1$
            .put("restartFailure", failure.getFailure()) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Resolves the projects to clean (read-only): for a named project it validates
     * existence/open state, otherwise it collects every open EDT project. Builds the
     * parallel work lists used by the clean phases. Performs no clean build.
     *
     * @param projectName name of the project to clean (null/empty for all projects)
     * @param dtProjectManager the DT project manager (may be null)
     * @return a {@link CleanCollection} holding the work lists, or one whose
     *     {@code error} is a {@link ToolResult#error} JSON payload when the named
     *     project does not exist or is closed
     */
    private static CleanCollection collectProjectsToClean(String projectName,
        IDtProjectManager dtProjectManager)
    {
        CleanCollection collection = new CleanCollection();

        if (projectName != null && !projectName.isEmpty())
        {
            collectNamedProject(projectName, dtProjectManager, collection);
        }
        else
        {
            collectAllEdtProjects(dtProjectManager, collection);
        }

        return collection;
    }

    /**
     * Resolves a single named project into {@code collection}: validates that it exists and is open,
     * then records it with its DT project (or {@code null} when the manager is unavailable). On a
     * missing/closed project it sets {@code collection.error} to the same JSON payload as the original
     * inline block and leaves the work lists empty.
     *
     * @param projectName       the requested project name (non-empty)
     * @param dtProjectManager  the DT project manager (may be {@code null})
     * @param collection        the accumulator to populate (mutated)
     */
    private static void collectNamedProject(String projectName, IDtProjectManager dtProjectManager,
        CleanCollection collection)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        collectResolvedNamedProject(projectName, ctx.project(), ctx.exists(), ctx.isOpen(),
            dtProjectManager, platformSettle(dtProjectManager), collection);
    }

    /**
     * Validates and records one RESOLVED named project, or refuses it.
     *
     * <p>Split from {@link ProjectContext#of} above so every verdict below it - closed, missing,
     * un-started, cleanable - is exercisable headless. Only the three lines that turn a NAME into
     * a handle are outside this seam.
     *
     * @param projectName      the requested project name (non-empty)
     * @param project          the resolved project handle (may be {@code null})
     * @param exists           whether the workspace holds it
     * @param isOpen           whether it is open
     * @param dtProjectManager the DT project manager (may be {@code null})
     * @param settle           waits out a context that is restarting before a refusal
     * @param collection       the accumulator to populate (mutated)
     */
    static void collectResolvedNamedProject(String projectName, IProject project, boolean exists,
        boolean isOpen, IDtProjectManager dtProjectManager, IDtProjectSettle settle,
        CleanCollection collection)
    {
        if (!exists)
        {
            collection.error = ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            return;
        }

        if (!isOpen)
        {
            collection.error = ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            return;
        }

        IDtProject dtProject = dtProjectManager != null ?
            dtProjectManager.getDtProject(project) : null;

        collectNamedObservation(projectName, project, dtProject, dtProjectManager != null,
            ProjectStateChecker.hasBmModelProjectNature(project), settle, collection);
    }

    /**
     * Records one RESOLVED named project into {@code collection}, or refuses it because EDT never
     * started it.
     *
     * @param projectName      the requested project name (non-empty)
     * @param project          the resolved project handle
     * @param dtProject        the DT project EDT reports for it, or {@code null}
     * @param managerAvailable whether {@code IDtProjectManager} answered at all
     * @param hasV8Nature      whether the project carries a BM-model nature ({@code null} when the
     *                         natures could not be read)
     * @param settle           waits out a context that is restarting before a refusal
     * @param collection       the accumulator to populate (mutated)
     */
    static void collectNamedObservation(String projectName, IProject project, IDtProject dtProject,
        boolean managerAvailable, Boolean hasV8Nature, IDtProjectSettle settle,
        CleanCollection collection)
    {
        IDtProject resolved = dtProject;
        if (resolved == null && managerAvailable && Boolean.TRUE.equals(hasV8Nature))
        {
            // Asked again over a short window before refusing: a context being disposed and
            // restarted has no DT project for a moment, and that transient is not the permanent
            // "EDT never started this project" the refusal below describes. The settle is paid
            // ONLY on the path that would otherwise refuse - so gated on the same three facts as
            // the refusal itself: a plain Eclipse project, or one whose natures could not be
            // read, is never refused and must not pay ten seconds for a verdict it cannot get.
            resolved = settle.awaitDtProject(project);
        }

        String refusal = unstartedProjectRefusalOrNull(projectName, managerAvailable,
            resolved != null, hasV8Nature);
        if (refusal != null)
        {
            collection.error = ToolResult.error(refusal).toJson();
            return;
        }

        collection.projectsToClean.add(new ProjectCleanInfo(project, resolved, projectName));
        collection.projectNamesList.add(projectName);
    }

    /**
     * The production settle: re-asks {@code IDtProjectManager} until it answers or the bound
     * elapses.
     *
     * @param dtProjectManager the DT project manager (may be {@code null})
     * @return the settle to use
     */
    private static IDtProjectSettle platformSettle(IDtProjectManager dtProjectManager)
    {
        return project -> {
            if (dtProjectManager == null || project == null)
            {
                return null;
            }
            long deadline = System.currentTimeMillis() + DT_PROJECT_SETTLE_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline)
            {
                try
                {
                    Thread.sleep(DT_PROJECT_SETTLE_POLL_MS);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
                IDtProject dtProject = dtProjectManager.getDtProject(project);
                if (dtProject != null)
                {
                    return dtProject;
                }
            }
            return null;
        };
    }

    /**
     * The refusal for a project EDT never started, or {@code null} when the clean may proceed.
     *
     * <p>A clean of such a project does refresh its files and schedule an Eclipse CLEAN_BUILD, but
     * both the lifecycle-restart wait and the derived-data wait are SKIPPED - not exhausted,
     * skipped, because the object they wait on does not exist - so "Clean and revalidation
     * completed" would describe work that never happened (issue #647). The sibling tools already
     * refuse on exactly this predicate ({@code revalidate_objects},
     * {@code translate_configuration}).
     *
     * <p>Refused only when all three hold: the manager ANSWERED (a manager we could not ask is not
     * evidence of anything), it reported no DT project, and the project carries a BM-model nature
     * (a plain Eclipse project never had a DT project and stays out of scope exactly as before).
     *
     * @param projectName      the project the caller named
     * @param managerAvailable whether {@code IDtProjectManager} answered at all
     * @param dtProjectPresent whether it reported a DT project
     * @param hasV8Nature      whether the project carries a BM-model nature ({@code null} when the
     *                         natures could not be read)
     * @return the refusal text, or {@code null} when the clean may proceed
     */
    static String unstartedProjectRefusalOrNull(String projectName, boolean managerAvailable,
        boolean dtProjectPresent, Boolean hasV8Nature)
    {
        if (!ProjectStateChecker.isUnstartedEdtProject(managerAvailable, dtProjectPresent, hasV8Nature))
        {
            return null;
        }
        return "Not an EDT project: " + projectName //$NON-NLS-1$
            + ". EDT has not started this project right now (no DtProject), so there is nothing " //$NON-NLS-1$
            + "to rebuild " //$NON-NLS-1$
            + "and a clean would report success over work that never happened. If it was just " //$NON-NLS-1$
            + "imported, wait for list_projects to report it 'ready'; otherwise close and reopen " //$NON-NLS-1$
            + "the project in EDT, or import it again."; //$NON-NLS-1$
    }

    /**
     * Collects every open EDT project into {@code collection} (the "clean all" path). No-op when the
     * DT project manager is unavailable; otherwise each DT project whose workspace project exists and
     * is open is recorded. Behaviour-identical to the original inline {@code else} block.
     *
     * @param dtProjectManager  the DT project manager (may be {@code null})
     * @param collection        the accumulator to populate (mutated)
     */
    private static void collectAllEdtProjects(IDtProjectManager dtProjectManager, CleanCollection collection)
    {
        collectAllEdtProjects(dtProjectManager, collection, ProjectContext::allProjects);
    }

    /**
     * The "clean all" collection with the workspace enumeration supplied rather than read, so the
     * skipped-project scan is exercisable headless.
     *
     * @param dtProjectManager   the DT project manager (may be {@code null})
     * @param collection         the accumulator to populate (mutated)
     * @param workspaceProjects  every project in the workspace, open or closed
     */
    static void collectAllEdtProjects(IDtProjectManager dtProjectManager, CleanCollection collection,
        Supplier<IProject[]> workspaceProjects)
    {
        if (dtProjectManager == null)
        {
            return;
        }
        for (IDtProject dtProject : dtProjectManager.getDtProjects())
        {
            IProject project = dtProject.getWorkspaceProject();
            if (project != null && project.isOpen())
            {
                collection.projectsToClean.add(new ProjectCleanInfo(project, dtProject, project.getName()));
                collection.projectNamesList.add(project.getName());
            }
        }

        // The enumeration above is over DT projects, so an EDT project EDT never started is not in
        // it at all - and 'clean all' used to answer "completed" without ever mentioning it. Name
        // the ones it skipped instead of leaving them out of the account (issue #647).
        List<String> openV8Projects = new ArrayList<>();
        for (IProject project : workspaceProjects.get())
        {
            if (project != null && project.isOpen()
                && Boolean.TRUE.equals(ProjectStateChecker.hasBmModelProjectNature(project)))
            {
                openV8Projects.add(project.getName());
            }
        }
        collection.skippedUnstarted.addAll(
            unstartedProjectNames(collection.projectNamesList, openV8Projects));
    }

    /**
     * The open BM-model projects EDT has NOT started: present in the workspace, absent from the
     * DT-project enumeration the clean-all path works from.
     *
     * <p>Clean-all does not settle the way the named path does, so a project whose context is
     * mid-restart at the instant of the scan is listed as skipped although it would have answered
     * a moment later; that is accepted, because the listing is a report and not a refusal, and
     * the project can be cleaned by name once it has settled.
     *
     * @param startedProjectNames the names EDT reports a DT project for (the clean work list)
     * @param openV8ProjectNames  the names of every OPEN workspace project with a BM-model nature
     * @return the names to report as skipped, in workspace order (never {@code null})
     */
    static List<String> unstartedProjectNames(Collection<String> startedProjectNames,
        Collection<String> openV8ProjectNames)
    {
        List<String> skipped = new ArrayList<>();
        for (String name : openV8ProjectNames)
        {
            if (!startedProjectNames.contains(name))
            {
                skipped.add(name);
            }
        }
        return skipped;
    }

    /**
     * Builds the completion payload.
     *
     * <p>{@code projectsCleaned} counts the projects that were actually cleaned, and nothing else:
     * a project EDT never started is named in the {@code message} as skipped rather than counted,
     * because counting it would restate the very claim issue #647 is about.
     *
     * @param cleanedProjects  the projects the clean phases ran for
     * @param skippedUnstarted the open BM-model projects skipped because EDT never started them
     * @return the success JSON
     */
    static String cleanCompletedResult(List<String> cleanedProjects, List<String> skippedUnstarted)
    {
        StringBuilder message = new StringBuilder("Clean and revalidation completed."); //$NON-NLS-1$
        if (!skippedUnstarted.isEmpty())
        {
            message.append(" Skipped ").append(skippedUnstarted.size()) //$NON-NLS-1$
                .append(skippedUnstarted.size() == 1 ? " project that EDT has not started " //$NON-NLS-1$
                    : " projects that EDT has not started ") //$NON-NLS-1$
                .append("(no DtProject), so nothing was rebuilt for them: ") //$NON-NLS-1$
                .append(String.join(", ", skippedUnstarted)) //$NON-NLS-1$
                .append(". Wait for list_projects to report them 'ready', or close and reopen " //$NON-NLS-1$
                    + "them in EDT."); //$NON-NLS-1$
        }
        return ToolResult.success()
            .put("projectsCleaned", cleanedProjects.size()) //$NON-NLS-1$
            .put("projects", cleanedProjects) //$NON-NLS-1$
            .put("message", message.toString()) //$NON-NLS-1$
            .toJson();
    }

    /** Captures the same direct-dependent scope EDT will stop, before any clean build runs. */
    static RestartScope registerRestartWaiters(List<ProjectCleanInfo> projects,
        Function<IDtProject, Collection<IDtProject>> restartScope,
        Function<IDtProject, ProjectRestartWaiter> prepare)
    {
        RestartScope scope = new RestartScope();
        Map<String, ProjectCleanInfo> targets = new LinkedHashMap<>();
        Map<String, ProjectCleanInfo> siblings = new LinkedHashMap<>();
        for (ProjectCleanInfo info : projects)
        {
            targets.putIfAbsent(info.name, info);
        }
        for (ProjectCleanInfo info : targets.values())
        {
            if (info.dtProject != null)
            {
                for (IDtProject dependent : restartScope.apply(info.dtProject))
                {
                    if (!targets.containsKey(dependent.getName()))
                    {
                        siblings.putIfAbsent(dependent.getName(), new ProjectCleanInfo(
                            dependent.getWorkspaceProject(), dependent, dependent.getName()));
                    }
                }
            }
        }
        try
        {
            for (ProjectCleanInfo info : targets.values())
            {
                scope.targets.add(new RestartProject(info, info.dtProject == null ? null : prepare.apply(info.dtProject)));
            }
            for (ProjectCleanInfo info : siblings.values())
            {
                scope.siblings.add(new RestartProject(info, prepare.apply(info.dtProject)));
            }
            return scope;
        }
        catch (RuntimeException e)
        {
            scope.cleanup();
            throw e;
        }
    }

    static final class RestartProject
    {
        final ProjectCleanInfo info;
        final ProjectRestartWaiter waiter;

        RestartProject(ProjectCleanInfo info, ProjectRestartWaiter waiter)
        {
            this.info = info;
            this.waiter = waiter;
        }
    }

    static final class RestartScope
    {
        final List<RestartProject> targets = new ArrayList<>();
        final List<RestartProject> siblings = new ArrayList<>();

        List<RestartProject> all()
        {
            List<RestartProject> all = new ArrayList<>(targets);
            all.addAll(siblings);
            return all;
        }

        void cleanup()
        {
            for (RestartProject project : all())
            {
                if (project.waiter != null)
                {
                    project.waiter.cleanup();
                }
            }
        }
    }

    /** Platform adapter: the same operations are substituted by the lifecycle integration tests. */
    static class RestartSupport
    {
        private final IDtProjectManager manager;
        private final IWorkspaceOrchestrator workspace;

        RestartSupport(IDtProjectManager manager, IWorkspaceOrchestrator workspace)
        {
            this.manager = manager;
            this.workspace = workspace;
        }

        Collection<IDtProject> dependents(IDtProject project)
        {
            return manager == null ? Collections.emptyList() : manager.getDependentProjects(project);
        }

        ProjectRestartWaiter prepare(IDtProject project)
        {
            return LifecycleWaiter.prepareForRestart(project);
        }

        RestartJobFailureSignal watchFailures()
        {
            return new RestartJobFailureSignal();
        }

        boolean isStarted(IDtProject project)
        {
            try
            {
                return workspace != null && workspace.isStarted(project);
            }
            catch (RuntimeException e)
            {
                Activator.logWarning("Could not verify restart of '" + project.getName() + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }

        Collection<IDtProject> recover(List<ProjectCleanInfo> projects, IProgressMonitor monitor)
        {
            List<IDtProject> issued = new ArrayList<>();
            if (workspace == null)
            {
                return issued;
            }
            for (ProjectCleanInfo info : projects)
            {
                if (monitor.isCanceled())
                {
                    break;
                }
                try
                {
                    workspace.startWorkspaceProjects(Collections.singletonList(
                        new WorkspaceProjectStartRequest(info.project, ProjectStartType.CLEAN_IMPORT)), monitor);
                    issued.add(info.dtProject);
                }
                catch (RuntimeException e)
                {
                    Activator.logWarning("Could not recover dependent project '" + info.name + "': " //$NON-NLS-1$ //$NON-NLS-2$
                        + e.getMessage());
                }
            }
            return issued;
        }

        boolean awaitStarted(IDtProject project, long timeoutMs)
        {
            return LifecycleWaiter.waitForProjectStarted(project, timeoutMs);
        }

        void awaitDerivedData(IProject project)
        {
            BuildUtils.waitForDerivedData(project);
        }
    }

    /**
     * Phase 2: runs the clean build for every project under a single hard deadline shared by
     * the whole phase, and translates a missed deadline into an actionable error.
     *
     * <p>The work runs in a {@link BoundedJob}, so the platform calls receive a monitor that is
     * cancelled on expiry AND the caller stops waiting regardless — cancellation alone cannot
     * preempt a platform call that never polls its monitor.
     *
     * @param projectsToClean the projects to clean
     * @param timeoutMs       the bound for the whole phase, in milliseconds
     * @param action          the per-project clean action (test seam; production cleans for real)
     * @return {@code null} when the phase completed, otherwise the error JSON to return as-is
     */
    static String runCleanPhase(List<ProjectCleanInfo> projectsToClean, long timeoutMs, ICleanAction action)
    {
        // The bound is PER PROJECT, not for the whole phase: 'clean all' over several healthy
        // projects would otherwise be refused once their COMBINED time crossed the limit, with
        // nothing actually wedged. A single wedged project still fails on its own deadline, and the
        // error returns immediately - so the projects behind it are never started.
        boolean completedAny = false;
        for (ProjectCleanInfo info : projectsToClean)
        {
            String error = cleanOneBounded(info, timeoutMs, action);
            if (error != null)
            {
                return completedAny ? ToolResult.markErrorAfterMutation(error) : error;
            }
            completedAny = true;
        }
        return null;
    }

    /**
     * Cleans one project under its own deadline and turns anything but a clean completion into the
     * error JSON to return.
     *
     * @param info      the project to clean
     * @param timeoutMs the bound for THIS project, in milliseconds
     * @param action    the per-project clean action
     * @return {@code null} when the project was cleaned, otherwise the error JSON
     */
    private static String cleanOneBounded(ProjectCleanInfo info, long timeoutMs, ICleanAction action)
    {
        BoundedJob.Result result = BoundedJob.run(NAME + ": clean build " + info.name, timeoutMs, //$NON-NLS-1$
            monitor -> action.clean(info, monitor));

        switch (result.getOutcome())
        {
        case TIMED_OUT:
            return timeoutError(info.name, timeoutMs);
        case TIMED_OUT_BEFORE_START:
            return notStartedError(info.name, timeoutMs);
        case INTERRUPTED:
            return ToolResult.errorWithUnknownMutationOutcome(
                "Project clean was interrupted while waiting for the clean build" //$NON-NLS-1$
                + onProject(info.name) + ". The clean may still be running in EDT — check the " //$NON-NLS-1$
                + "project state with list_projects before retrying.").toJson(); //$NON-NLS-1$
        case NOT_RUN:
            return ToolResult.error("The clean build" + onProject(info.name) + " was cancelled before " //$NON-NLS-1$ //$NON-NLS-2$
                + "it started, so nothing was cleaned. Retry; if it keeps happening, EDT is shutting " //$NON-NLS-1$
                + "down or another operation is cancelling background jobs.").toJson(); //$NON-NLS-1$
        case COMPLETED:
            break;
        default:
            // Fail CLOSED on an outcome added to BoundedJob later. The old 'default: break' fell
            // through to the no-error path below, which would report a clean that never happened
            // as a success — the exact failure mode this switch exists to prevent.
            return ToolResult.errorWithUnknownMutationOutcome(
                "The clean build" + onProject(info.name) //$NON-NLS-1$
                + " ended in an " //$NON-NLS-1$
                + "unrecognised state (" + result.getOutcome() + "), so whether it ran is unknown. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the project with list_projects before relying on the model.").toJson(); //$NON-NLS-1$
        }

        Throwable failure = result.getFailure();
        if (failure != null)
        {
            // Same shape as the surrounding catch: the clean build failed for a real reason.
            Activator.logError("Error during project clean", failure); //$NON-NLS-1$
            return ToolResult.errorWithUnknownMutationOutcome(failure.getMessage()).toJson();
        }
        return null;
    }

    /**
     * Builds the error for a clean the deadline caught while it was still QUEUED.
     *
     * <p>Deliberately NOT the {@link #timeoutError} text: that one warns the model may be
     * mid-rebuild, which is exactly wrong here — cancelling a queued clean stops it from ever
     * starting, so the project is untouched and there is nothing to poll for.
     *
     * @param projectName the project whose clean never started (may be {@code null})
     * @param timeoutMs   the bound that elapsed, in milliseconds
     * @return the error JSON
     */
    private static String notStartedError(String projectName, long timeoutMs)
    {
        long seconds = Math.max(1, Math.round(timeoutMs / 1000.0));
        return ToolResult.error("The clean build" + onProject(projectName) + " did not START " //$NON-NLS-1$ //$NON-NLS-2$
            + "within " + seconds + (seconds == 1 ? " second" : " seconds") + ": the deadline " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            + "elapsed while it was still queued, and cancelling it kept it from starting. " //$NON-NLS-1$
            + "NOTHING was cleaned and the project is untouched. EDT's job scheduler is " //$NON-NLS-1$
            + "saturated — retry when it is less busy, or pass a larger '" + KEY_TIMEOUT //$NON-NLS-1$
            + "' (seconds).").toJson(); //$NON-NLS-1$
    }

    /**
     * Builds the timeout error: what did not finish, how long we waited, and the levers.
     *
     * @param projectName the project the clean was on when time ran out (may be {@code null})
     * @param timeoutMs   the bound that elapsed, in milliseconds
     * @return the error JSON
     */
    private static String timeoutError(String projectName, long timeoutMs)
    {
        long seconds = Math.max(1, Math.round(timeoutMs / 1000.0));
        // At the ceiling there is no larger value to suggest — advising one would be an
        // instruction the tool itself would reject.
        String lever = seconds >= MAX_CLEAN_TIMEOUT_SECONDS
            ? "This is already the largest accepted '" + KEY_TIMEOUT + "', so the clean is not merely " //$NON-NLS-1$ //$NON-NLS-2$
                + "slow — look for a stuck build or an EDT operation holding the workspace." //$NON-NLS-1$
            : "If this configuration legitimately needs longer, pass a larger '" + KEY_TIMEOUT //$NON-NLS-1$
                + "' (seconds, up to " + MAX_CLEAN_TIMEOUT_SECONDS + ") or raise the default in " //$NON-NLS-1$ //$NON-NLS-2$
                + "Preferences > MCP Server > Tools > " + NAME + "."; //$NON-NLS-1$ //$NON-NLS-2$

        return ToolResult.errorWithUnknownMutationOutcome(
            "Clean build did not finish within " + seconds //$NON-NLS-1$
            + (seconds == 1 ? " second" : " seconds") + onProject(projectName) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Cancellation was requested, but EDT may still be working on it, so the model can be " //$NON-NLS-1$
            + "mid-rebuild: check list_projects until the project reports 'ready' before relying on " //$NON-NLS-1$
            + "the model. " + lever).toJson(); //$NON-NLS-1$
    }

    /**
     * Renders the optional " on project 'X'" clause used by the phase-2 error messages.
     *
     * @param projectName the project name (may be {@code null} when the phase never started one)
     * @return the clause, or an empty string when there is no project to name
     */
    private static String onProject(String projectName)
    {
        return projectName == null || projectName.isEmpty() ? "" : " on project '" + projectName + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Waits out a project context that is being restarted before the caller concludes EDT never
     * started the project. Production polls {@code IDtProjectManager}; tests substitute an
     * immediate answer so no test ever sleeps.
     */
    @FunctionalInterface
    interface IDtProjectSettle
    {
        /**
         * Re-asks for the project's DT project over a short window.
         *
         * @param project the workspace project
         * @return the DT project once EDT registers one, or {@code null} when it never did
         */
        IDtProject awaitDtProject(IProject project);
    }

    /**
     * The per-project clean action. Production passes {@link #cleanSingleProject}; tests
     * substitute a controllable action to exercise the deadline without a live workspace.
     */
    @FunctionalInterface
    interface ICleanAction
    {
        /**
         * Cleans one project.
         *
         * @param info    the project to clean
         * @param monitor the bounded job's monitor, cancelled when the deadline elapses
         * @throws CoreException when the platform refuses the refresh or the build
         */
        void clean(ProjectCleanInfo info, IProgressMonitor monitor) throws CoreException;
    }

    /**
     * Cleans a single project using Eclipse CLEAN_BUILD.
     * This triggers EDT's full project rebuild including:
     * - CLEAN phase (stops project context)
     * - CLEAN_IMPORT phase (imports and rebuilds)
     * - LINKING, INITIALIZATION, CHECKING, etc.
     * 
     * @param project the project to clean
     * @param monitor progress monitor
     * @throws CoreException if build fails
     */
    private static void cleanSingleProject(IProject project, IProgressMonitor monitor) throws CoreException
    {
        Activator.logInfo("Cleaning project (CLEAN_BUILD): " + project.getName()); //$NON-NLS-1$
        
        // Step 1: Refresh from disk to detect external changes
        project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
        
        // Step 2: Trigger Eclipse Clean Build - this invokes EDT's clean handler
        // which stops project context, clears all data, and reimports
        project.build(IncrementalProjectBuilder.CLEAN_BUILD, monitor);
        
        Activator.logInfo("Clean build scheduled for: " + project.getName()); //$NON-NLS-1$
    }
    
    /**
     * Holder for the result of {@link #collectProjectsToClean}: the parallel work lists,
     * or an {@code error} JSON payload that the caller should return as-is.
     */
    static class CleanCollection
    {
        final List<ProjectCleanInfo> projectsToClean = new ArrayList<>();
        final List<String> projectNamesList = new ArrayList<>();
        /** Open BM-model projects skipped because EDT never started them (clean-all only). */
        final List<String> skippedUnstarted = new ArrayList<>();
        String error;
    }

    /**
     * Helper class to store project info for cleaning.
     */
    static class ProjectCleanInfo
    {
        final IProject project;
        final IDtProject dtProject;
        /**
         * Project name captured up front, so a timeout message can name the project without
         * touching a project the wedged clean may have left mid-lifecycle.
         */
        final String name;

        ProjectCleanInfo(IProject project, IDtProject dtProject, String name)
        {
            this.project = project;
            this.dtProject = dtProject;
            this.name = name;
        }
    }
}
