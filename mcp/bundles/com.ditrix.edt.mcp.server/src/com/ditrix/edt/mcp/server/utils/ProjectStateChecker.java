/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Utility class for checking project state and readiness.
 * Uses EDT services to determine if a project is ready for operations.
 */
public final class ProjectStateChecker
{
    /**
     * Project state enumeration.
     */
    public enum ProjectState
    {
        /** Project is ready for operations */
        READY("ready"), //$NON-NLS-1$
        
        /** Project is building or computing derived data */
        BUILDING("building"), //$NON-NLS-1$
        
        /** Project is not available (closed, not EDT project, etc.) */
        NOT_AVAILABLE("not_available"), //$NON-NLS-1$
        
        /** State cannot be determined */
        UNKNOWN("unknown"); //$NON-NLS-1$
        
        private final String value;
        
        ProjectState(String value)
        {
            this.value = value;
        }
        
        /**
         * Gets the string value for JSON serialization.
         * @return string value
         */
        public String getValue()
        {
            return value;
        }
    }
    
    /**
     * Result of project state check.
     */
    public static class ProjectStateResult
    {
        private final ProjectState state;
        private final String message;
        private final boolean ready;
        
        public ProjectStateResult(ProjectState state, String message)
        {
            this.state = state;
            this.message = message;
            this.ready = state == ProjectState.READY;
        }
        
        public ProjectState getState()
        {
            return state;
        }
        
        public String getMessage()
        {
            return message;
        }
        
        public boolean isReady()
        {
            return ready;
        }
        
        public String getStateValue()
        {
            return state.getValue();
        }
    }
    
    private ProjectStateChecker()
    {
        // Utility class
    }
    
    /**
     * Checks if a project is ready for operations.
     * A project is ready when:
     * - It exists and is open
     * - It is a valid EDT project
     * - Derived data computations are complete (not building)
     * 
     * @param project the IProject to check
     * @return ProjectStateResult with state and message
     */
    public static ProjectStateResult checkProjectState(IProject project)
    {
        if (project == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project is null");
        }
        
        if (!project.exists())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project does not exist");
        }
        
        if (!project.isOpen())
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Project is closed");
        }
        
        // Get DtProject
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return new ProjectStateResult(ProjectState.UNKNOWN, "DtProjectManager not available");
        }
        
        IDtProject dtProject = dtProjectManager.getDtProject(project);
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "Not an EDT project");
        }
        
        return checkDtProjectState(dtProject);
    }
    
    /**
     * Checks if a DT project is ready for operations.
     * 
     * @param dtProject the IDtProject to check
     * @return ProjectStateResult with state and message
     */
    public static ProjectStateResult checkDtProjectState(IDtProject dtProject)
    {
        if (dtProject == null)
        {
            return new ProjectStateResult(ProjectState.NOT_AVAILABLE, "DtProject is null");
        }
        
        // Check derived data status
        IDerivedDataManagerProvider ddProvider = Activator.getDefault().getDerivedDataManagerProvider();
        if (ddProvider == null)
        {
            // Cannot determine state without DD provider
            Activator.logInfo("DerivedDataManagerProvider not available for " + dtProject.getName());
            return new ProjectStateResult(ProjectState.UNKNOWN, "Cannot determine build state");
        }
        
        IDerivedDataManager ddManager = ddProvider.get(dtProject);
        if (ddManager == null)
        {
            Activator.logInfo("DerivedDataManager not available for " + dtProject.getName());
            return new ProjectStateResult(ProjectState.UNKNOWN, "Cannot determine build state");
        }
        
        // Check if computation pipeline is idle
        if (!ddManager.isIdle())
        {
            DerivedDataStatus status = ddManager.getDerivedDataStatus();
            String statusStr = status != null ? status.toString() : "computing";
            return new ProjectStateResult(ProjectState.BUILDING, 
                "Project is building: " + statusStr);
        }
        
        // Check if all derived data is computed
        if (!ddManager.isAllComputed())
        {
            return new ProjectStateResult(ProjectState.BUILDING, 
                "Project build in progress (derived data not complete)");
        }
        
        return new ProjectStateResult(ProjectState.READY, "Project is ready");
    }
    
    /**
     * Checks if a project is ready and returns error message if not.
     * Convenience method for tools that need to check before executing.
     * 
     * @param project the IProject to check
     * @return null if ready, error message if not ready
     */
    public static String checkReadyOrError(IProject project)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.isReady())
        {
            return null;
        }
        return result.getMessage() + ". Please wait and retry.";
    }
    
    /**
     * Checks if a project is ready and returns error message if not.
     * Convenience method for tools that need to check before executing.
     * 
     * @param projectName the project name to check
     * @return null if ready, error message if not ready
     */
    public static String checkReadyOrError(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null; // No specific project, skip check
        }
        
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);

        return checkReadyOrError(project);
    }

    /**
     * Returns a "still building" error message ONLY when the project's derived data
     * (the reference index) is actively building, otherwise {@code null}.
     * <p>
     * Unlike {@link #checkReadyOrError(IProject)} this does NOT reject a project that is
     * merely missing / closed / unknown: those are PERMANENT conditions a retry will not
     * fix, and the caller's own resolution yields a sharper, value-naming error
     * ("Project not found: X"). Use this for a model-mutating or cascade pre-flight where
     * the only state worth refusing for is a transient in-progress build (running the
     * cascade against an incomplete index would silently miss references).
     *
     * @param project the IProject to check
     * @return the building message with a retry hint, or {@code null} when not building
     */
    public static String buildingErrorOrNull(IProject project)
    {
        ProjectStateResult result = checkProjectState(project);
        if (result.getState() == ProjectState.BUILDING)
        {
            return result.getMessage() + ". Please wait and retry."; //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The pre-flight for a CASCADE operation (a rename / delete refactoring): actively waits for the
     * project's derived-data pipeline to drain, then re-checks, and returns the "still building"
     * message when it did not settle in time.
     * <p>
     * {@link #buildingErrorOrNull(IProject)} alone is an INSTANT probe, and a cascade needs more than
     * that. EDT's refactoring opens a BM batch session; a derived-data task that is still pending when
     * it does cannot run ("Unable to execute task because batch session is active") and the refactoring
     * then waits for the pipeline from INSIDE the session - measured at 301 seconds on CI, with the
     * call finally succeeding. Whoever is on the wire has long since timed out, which is what made
     * this look like a flaky test rather than what it is: an unbounded wait we walked into.
     * <p>
     * So: drain first, on the CALLER's thread (never inside the UI-thread scope - the pipeline may
     * need it), and if the pipeline is still busy afterwards, refuse with the same actionable,
     * retryable message every other tool uses instead of blocking the wire for five minutes.
     *
     * @param projectName the project the cascade will mutate (a null/empty name skips the check)
     * @param settleTimeoutMs how long to wait for the pipeline to drain
     * @return the building message with a retry hint, or {@code null} when the cascade may proceed
     */
    public static String settleBeforeCascadeOrError(String projectName, long settleTimeoutMs)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen())
        {
            // Nothing to drain, and asking anyway can block on a project EDT is still disposing.
            // A missing / closed project is not this method's error to report either - the caller's
            // own resolution names the value ("Project not found: X").
            return null;
        }
        // Drain UNCONDITIONALLY - do not gate this on the instant probe. On the CI run that
        // exposed this, the probe answered READY and a derived-data task was executing one
        // second later, so a drain that only ran when the probe said BUILDING would have been
        // skipped on the very call it exists for. With a quiet pipeline (or a name that is not
        // an EDT project at all) waitAllComputations returns immediately, so the cost is zero
        // where there is nothing to wait for.
        long deadline = System.currentTimeMillis() + settleTimeoutMs;
        BuildUtils.waitForDerivedData(project, settleTimeoutMs);
        // A cascade is not confined to the named project: a rename builds one refactoring per
        // participating project, which includes the configuration EXTENSIONS that adopt the
        // renamed object. An extension whose pipeline is still busy collides with the batch
        // session exactly like the base one, so drain the others too - they share ONE deadline,
        // so this cannot multiply the wait by the number of projects in the workspace.
        drainOtherOpenProjects(project, deadline);
        // The refusal, though, is decided on the NAMED project only. It is always a participant,
        // while an unrelated project that happens to be indexing must not be able to refuse every
        // rename in the workspace - for those the drain above is the whole benefit.
        return buildingErrorOrNull(project);
    }

    /**
     * Waits for every OTHER open EDT project's derived data, until the shared {@code deadline}.
     *
     * @param except the project already drained by the caller
     * @param deadline absolute time (ms) the whole drain must not exceed
     */
    private static void drainOtherOpenProjects(IProject except, long deadline)
    {
        IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
        if (dtProjectManager == null)
        {
            return;
        }
        for (IProject other : org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
            .getProjects())
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return;
            }
            if (other.equals(except) || !other.exists() || !other.isOpen()
                || dtProjectManager.getDtProject(other) == null)
            {
                continue;
            }
            BuildUtils.waitForDerivedData(other, remaining);
        }
    }

    /**
     * Name-based variant of {@link #buildingErrorOrNull(IProject)}. A null/empty name
     * skips the check (returns {@code null}), leaving the caller's required-argument
     * handling to produce the proper error.
     *
     * @param projectName the project name to check
     * @return the building message with a retry hint, or {@code null} when not building
     */
    public static String buildingErrorOrNull(String projectName)
    {
        if (projectName == null || projectName.isEmpty())
        {
            return null;
        }
        IProject project = org.eclipse.core.resources.ResourcesPlugin.getWorkspace()
            .getRoot().getProject(projectName);
        return buildingErrorOrNull(project);
    }
}
