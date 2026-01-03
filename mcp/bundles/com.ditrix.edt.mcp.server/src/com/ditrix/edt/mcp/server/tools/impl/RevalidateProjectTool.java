/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to trigger project revalidation.
 * This is useful when validation gets stuck or needs to be refreshed.
 */
public class RevalidateProjectTool implements IMcpTool
{
    public static final String NAME = "revalidate_project"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Trigger full project revalidation. If projectName is not specified, revalidates all projects. " + //$NON-NLS-1$
               "Returns status of the revalidation trigger."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"projectName\": {\"type\": \"string\", \"description\": \"Name of the project to revalidate (optional, revalidates all if not specified)\"}," + //$NON-NLS-1$
               "\"clean\": {\"type\": \"boolean\", \"description\": \"If true, performs clean build (default: false)\"}" + //$NON-NLS-1$
               "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String cleanStr = JsonUtils.extractStringArgument(params, "clean"); //$NON-NLS-1$
        boolean clean = "true".equalsIgnoreCase(cleanStr); //$NON-NLS-1$
        
        return revalidateProject(projectName, clean);
    }
    
    /**
     * Triggers project revalidation.
     * 
     * @param projectName name of the project to revalidate (null for all projects)
     * @param clean if true, performs clean build
     * @return JSON string with result
     */
    public static String revalidateProject(String projectName, boolean clean)
    {
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            int projectsTriggered = 0;
            StringBuilder projectNames = new StringBuilder();
            
            if (projectName != null && !projectName.isEmpty())
            {
                // Revalidate specific project
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    json.append("\"success\": false,"); //$NON-NLS-1$
                    json.append("\"error\": \"Project not found: ").append(JsonUtils.escapeJson(projectName)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                    json.append("}"); //$NON-NLS-1$
                    return json.toString();
                }
                
                if (!project.isOpen())
                {
                    json.append("\"success\": false,"); //$NON-NLS-1$
                    json.append("\"error\": \"Project is closed: ").append(JsonUtils.escapeJson(projectName)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                    json.append("}"); //$NON-NLS-1$
                    return json.toString();
                }
                
                triggerBuild(project, clean);
                projectsTriggered = 1;
                projectNames.append(projectName);
            }
            else
            {
                // Revalidate all open projects
                IProject[] projects = workspace.getRoot().getProjects();
                for (IProject project : projects)
                {
                    if (project.isOpen())
                    {
                        try
                        {
                            // Check if it's a 1C:EDT project
                            if (project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature") || //$NON-NLS-1$
                                project.hasNature("com._1c.g5.v8.dt.core.V8ExtensionNature")) //$NON-NLS-1$
                            {
                                triggerBuild(project, clean);
                                if (projectsTriggered > 0)
                                {
                                    projectNames.append(", "); //$NON-NLS-1$
                                }
                                projectNames.append(project.getName());
                                projectsTriggered++;
                            }
                        }
                        catch (CoreException e)
                        {
                            // Skip projects with nature check errors
                            Activator.logError("Failed to check nature for: " + project.getName(), e); //$NON-NLS-1$
                        }
                    }
                }
            }
            
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"projectsTriggered\": ").append(projectsTriggered).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"projects\": \"").append(JsonUtils.escapeJson(projectNames.toString())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"buildType\": \"").append(clean ? "CLEAN" : "INCREMENTAL").append("\","); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            json.append("\"message\": \"Revalidation triggered. Check project markers for results.\""); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error during revalidation", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
    }
    
    /**
     * Triggers a build for the project in a background job.
     * 
     * @param project the project to build
     * @param clean if true, performs clean build
     */
    private static void triggerBuild(IProject project, boolean clean)
    {
        Job buildJob = Job.create("Revalidate: " + project.getName(), monitor -> { //$NON-NLS-1$
            try
            {
                IProgressMonitor progressMonitor = monitor != null ? monitor : new NullProgressMonitor();
                
                if (clean)
                {
                    // Clean build
                    project.build(IncrementalProjectBuilder.CLEAN_BUILD, progressMonitor);
                    project.build(IncrementalProjectBuilder.FULL_BUILD, progressMonitor);
                }
                else
                {
                    // Incremental build
                    project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, progressMonitor);
                }
                
                Activator.logInfo("Revalidation completed for: " + project.getName()); //$NON-NLS-1$
            }
            catch (CoreException e)
            {
                Activator.logError("Build failed for: " + project.getName(), e); //$NON-NLS-1$
            }
        });
        
        buildJob.schedule();
    }
}
