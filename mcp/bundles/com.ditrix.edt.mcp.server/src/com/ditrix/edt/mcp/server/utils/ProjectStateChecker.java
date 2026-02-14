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
}
