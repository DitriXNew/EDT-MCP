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
            IProgressMonitor monitor = new NullProgressMonitor();
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

        collection.projectsToClean.add(new ProjectCleanInfo(project, dtProject));
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
                collection.projectsToClean.add(new ProjectCleanInfo(project, dtProject));
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
