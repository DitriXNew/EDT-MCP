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
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter;
import com.ditrix.edt.mcp.server.utils.LifecycleWaiter.ProjectRestartWaiter;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to clean EDT project and trigger full revalidation.
 * Uses Eclipse Project -> Clean command which triggers EDT full rebuild.
 */
public class CleanProjectTool implements IMcpTool
{
    public static final String NAME = "clean_project"; //$NON-NLS-1$
    
    /** Default timeout for waiting project lifecycle restart (3 minutes) */
    private static final long DEFAULT_LIFECYCLE_TIMEOUT_MS = 3 * 60 * 1000;
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Clean EDT project and trigger full revalidation. " + //$NON-NLS-1$
               "Refreshes files from disk, clears all validation markers, " + //$NON-NLS-1$
               "and waits for EDT to complete revalidation."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Name of the project to clean (optional, cleans all EDT projects if not specified)") //$NON-NLS-1$ //$NON-NLS-2$
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
        
        // Check if project is ready for operations
        if (projectName != null && !projectName.isEmpty())
        {
            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }
        
        return cleanProject(projectName);
    }
    
    /**
     * Cleans project and triggers revalidation.
     * 
     * <p>To avoid race conditions, lifecycle listeners are registered BEFORE
     * triggering the clean build operation.
     * 
     * @param projectName name of the project to clean (null for all projects)
     * @return JSON string with result
     */
    public static String cleanProject(String projectName)
    {
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProgressMonitor monitor = new NullProgressMonitor();
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            
            List<String> projectNamesList = new ArrayList<>();
            List<ProjectCleanInfo> projectsToClean = new ArrayList<>();
            
            if (projectName != null && !projectName.isEmpty())
            {
                // Clean specific project
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
                }
                
                if (!project.isOpen())
                {
                    return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
                }
                
                IDtProject dtProject = dtProjectManager != null ? 
                    dtProjectManager.getDtProject(project) : null;
                
                projectsToClean.add(new ProjectCleanInfo(project, dtProject));
                projectNamesList.add(projectName);
            }
            else
            {
                // Clean all EDT projects
                if (dtProjectManager != null)
                {
                    for (IDtProject dtProject : dtProjectManager.getDtProjects())
                    {
                        IProject project = dtProject.getWorkspaceProject();
                        if (project != null && project.isOpen())
                        {
                            projectsToClean.add(new ProjectCleanInfo(project, dtProject));
                            projectNamesList.add(project.getName());
                        }
                    }
                }
            }
            
            // Phase 1: Register lifecycle listeners BEFORE triggering clean builds
            // This avoids race condition where STOPPED event could be missed
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
            
            // Phase 2: Trigger clean build for all projects
            for (ProjectCleanInfo info : projectsToClean)
            {
                cleanSingleProject(info.project, monitor);
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
        catch (Exception e)
        {
            Activator.logError("Error during project clean", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
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
     * Helper class to store project info for cleaning.
     */
    private static class ProjectCleanInfo
    {
        final IProject project;
        final IDtProject dtProject;
        
        ProjectCleanInfo(IProject project, IDtProject dtProject)
        {
            this.project = project;
            this.dtProject = dtProject;
        }
    }
}
