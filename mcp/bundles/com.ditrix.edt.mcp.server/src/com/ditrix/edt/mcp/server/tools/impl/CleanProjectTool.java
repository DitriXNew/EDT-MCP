/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

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

/**
 * Tool to clean EDT project and trigger full revalidation.
 * Uses Eclipse Project -> Clean command which triggers EDT full rebuild.
 */
public class CleanProjectTool implements IMcpTool
{
    public static final String NAME = "clean_project"; //$NON-NLS-1$

    /** Default timeout for waiting project lifecycle restart (3 minutes) */
    private static final long DEFAULT_LIFECYCLE_TIMEOUT_MS = 3L * 60 * 1000;

    /** Input key: bound on the clean-build phase, in seconds. */
    static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /**
     * Default bound on the clean-build phase (2 minutes). A healthy clean of the whole
     * phase takes seconds; the bound only has to be far above that, because its job is to
     * turn a wedged platform call into an honest error instead of an endless MCP request.
     * Raise it (parameter or preference) for very large configurations.
     */
    static final int DEFAULT_CLEAN_TIMEOUT_SECONDS = 120;

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
        return "Clean EDT project and trigger full revalidation. " + //$NON-NLS-1$
               "Direction: DISK -> MODEL - re-imports the on-disk src/ .mdo files into the in-memory model. " + //$NON-NLS-1$
               "Refreshes files from disk, clears all validation markers, " + //$NON-NLS-1$
               "and waits for EDT to complete revalidation. " + //$NON-NLS-1$
               "Full rebuild of the ENTIRE configuration — slow on large projects. " + //$NON-NLS-1$
               "Discards UNSAVED in-memory model edits (they are recomputed from disk); save pending changes first. " + //$NON-NLS-1$
               "For a single externally-edited object prefer revalidate_objects([FQN]). " + //$NON-NLS-1$
               "Reverse direction (MODEL -> DISK, write the model out to .mdo) is resync_to_disk. " + //$NON-NLS-1$
               "Full parameters and examples: call get_tool_guide('clean_project')."; //$NON-NLS-1$
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
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();

            // Resolve the set of projects to clean (read-only: validates state and
            // builds the work lists; performs no clean build itself).
            CleanCollection collection = collectProjectsToClean(projectName, dtProjectManager);
            if (collection.error != null)
            {
                return collection.error;
            }
            List<ProjectCleanInfo> projectsToClean = collection.projectsToClean;
            List<String> projectNamesList = collection.projectNamesList;

            // Phase 1: Register lifecycle listeners BEFORE triggering clean builds
            // This avoids race condition where STOPPED event could be missed
            List<ProjectRestartWaiter> waiters = registerRestartWaiters(projectsToClean);

            try
            {
                // Phase 2: Trigger clean build for all projects, under a hard deadline. Without it
                // a single wedged platform call holds the MCP request open forever (#349): the
                // caller eventually dies on its own transport timeout with no answer, no cleanup.
                String cleanError = runCleanPhase(projectsToClean, cleanTimeoutMs,
                    (info, monitor) -> cleanSingleProject(info.project, monitor));
                if (cleanError != null)
                {
                    return cleanError;
                }

                // Phase 3: Wait for lifecycle restarts (STOPPED -> STARTED)
                for (ProjectRestartWaiter waiter : waiters)
                {
                    waiter.await(DEFAULT_LIFECYCLE_TIMEOUT_MS);
                }

                // Phase 4: Wait for derived data computations
                for (ProjectCleanInfo info : projectsToClean)
                {
                    BuildUtils.waitForDerivedData(info.project);
                }

                return ToolResult.success()
                    .put("projectsCleaned", projectNamesList.size()) //$NON-NLS-1$
                    .put("projects", projectNamesList) //$NON-NLS-1$
                    .put("message", "Clean and revalidation completed.") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            finally
            {
                // A path that never reaches await() (clean timeout, clean failure, an early return
                // added later) would otherwise leave the lifecycle listener registered for the rest
                // of the session. cleanup() is idempotent, so the normal await() path is unaffected.
                for (ProjectRestartWaiter waiter : waiters)
                {
                    waiter.cleanup();
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error during project clean", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
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
        if (!ctx.exists())
        {
            collection.error = ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            return;
        }

        if (!ctx.isOpen())
        {
            collection.error = ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            return;
        }

        IProject project = ctx.project();

        IDtProject dtProject = dtProjectManager != null ?
            dtProjectManager.getDtProject(project) : null;

        collection.projectsToClean.add(new ProjectCleanInfo(project, dtProject, projectName));
        collection.projectNamesList.add(projectName);
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
    }

    /**
     * Phase 1 helper: registers a lifecycle restart listener for every project that has
     * a DT project, BEFORE any clean build is triggered, so the STOPPED event cannot be
     * missed. Returns the waiters to {@code await} after the clean builds are scheduled.
     *
     * @param projectsToClean the projects being cleaned
     * @return the registered restart waiters (one per DT project that produced a waiter)
     */
    private static List<ProjectRestartWaiter> registerRestartWaiters(
        List<ProjectCleanInfo> projectsToClean)
    {
        List<ProjectRestartWaiter> waiters = new ArrayList<>();
        for (ProjectCleanInfo info : projectsToClean)
        {
            if (info.dtProject != null)
            {
                ProjectRestartWaiter waiter = LifecycleWaiter.prepareForRestart(info.dtProject);
                if (waiter != null)
                {
                    waiters.add(waiter);
                }
            }
        }
        return waiters;
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
        for (ProjectCleanInfo info : projectsToClean)
        {
            String error = cleanOneBounded(info, timeoutMs, action);
            if (error != null)
            {
                return error;
            }
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
            return ToolResult.error("Project clean was interrupted while waiting for the clean build" //$NON-NLS-1$
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
            return ToolResult.error("The clean build" + onProject(info.name) + " ended in an " //$NON-NLS-1$ //$NON-NLS-2$
                + "unrecognised state (" + result.getOutcome() + "), so whether it ran is unknown. " //$NON-NLS-1$ //$NON-NLS-2$
                + "Check the project with list_projects before relying on the model.").toJson(); //$NON-NLS-1$
        }

        Throwable failure = result.getFailure();
        if (failure != null)
        {
            // Same shape as the surrounding catch: the clean build failed for a real reason.
            Activator.logError("Error during project clean", failure); //$NON-NLS-1$
            return ToolResult.error(failure.getMessage()).toJson();
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

        return ToolResult.error("Clean build did not finish within " + seconds //$NON-NLS-1$
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
    private static class CleanCollection
    {
        final List<ProjectCleanInfo> projectsToClean = new ArrayList<>();
        final List<String> projectNamesList = new ArrayList<>();
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
