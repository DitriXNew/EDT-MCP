/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to get tasks (TODO, FIXME, etc.) from the workspace.
 */
public class GetTasksTool implements IMcpTool
{
    public static final String NAME = "get_tasks"; //$NON-NLS-1$
    
    // Task marker types
    private static final String TASK_MARKER_TYPE = "org.eclipse.core.resources.taskmarker"; //$NON-NLS-1$
    private static final String XTEXT_TASK_MARKER_TYPE = "org.eclipse.xtext.ui.task"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get tasks (TODO, FIXME, etc.) from the workspace. " + //$NON-NLS-1$
               "Returns task message, file path, line number, and priority."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Filter by file path substring (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("priority", "Filter by priority: high, normal, low (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String priority = JsonUtils.extractStringArgument(params, "priority"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
        int defaultLimit = Activator.getDefault().getDefaultLimit();
        int maxLimit = Activator.getDefault().getMaxLimit();
        
        int limit = defaultLimit;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min(Integer.parseInt(limitStr), maxLimit);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }
        
        return getTasks(projectName, filePath, priority, limit);
    }
    
    /**
     * Gets tasks with filters.
     * 
     * @param projectName filter by project name (null for all)
     * @param filePath filter by file path substring
     * @param priority filter by priority (high, normal, low)
     * @param limit maximum number of results
     * @return Markdown string with task details
     */
    public static String getTasks(String projectName, String filePath, String priority, int limit)
    {
        StringBuilder md = new StringBuilder();
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            List<TaskInfo> tasks = new ArrayList<>();
            
            // Determine priority filter
            Integer priorityFilter = null;
            if (priority != null && !priority.isEmpty())
            {
                switch (priority.toLowerCase())
                {
                    case "high": //$NON-NLS-1$
                        priorityFilter = IMarker.PRIORITY_HIGH;
                        break;
                    case "normal": //$NON-NLS-1$
                        priorityFilter = IMarker.PRIORITY_NORMAL;
                        break;
                    case "low": //$NON-NLS-1$
                        priorityFilter = IMarker.PRIORITY_LOW;
                        break;
                }
            }
            
            IProject[] projects;
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return "**Error:** Project not found: " + projectName; //$NON-NLS-1$
                }
                projects = new IProject[] { project };
            }
            else
            {
                projects = workspace.getRoot().getProjects();
            }
            
            // Collect tasks from projects
            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }
                
                // Collect from both task marker types
                collectTasksFromMarkers(project, TASK_MARKER_TYPE, tasks, filePath, priorityFilter, limit);
                if (tasks.size() < limit)
                {
                    collectTasksFromMarkers(project, XTEXT_TASK_MARKER_TYPE, tasks, filePath, priorityFilter, limit);
                }
                
                if (tasks.size() >= limit)
                {
                    break;
                }
            }
            
            // Build Markdown response
            md.append("## Tasks\n\n"); //$NON-NLS-1$
            md.append("**Found:** ").append(tasks.size()).append(" tasks"); //$NON-NLS-1$ //$NON-NLS-2$
            if (tasks.size() >= limit)
            {
                md.append(" (limit: ").append(limit).append(", more available)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n\n"); //$NON-NLS-1$
            
            if (tasks.isEmpty())
            {
                md.append("*No tasks found.*\n"); //$NON-NLS-1$
            }
            else
            {
                // Table header
                md.append("| Type | Priority | Message | Path | Line |\n"); //$NON-NLS-1$
                md.append("|------|----------|---------|------|------|\n"); //$NON-NLS-1$
                
                for (TaskInfo task : tasks)
                {
                    md.append("| "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(task.type));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(task.priority));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(task.message));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(task.path));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(task.line);
                    md.append(" |\n"); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting tasks", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
        
        return md.toString();
    }
    
    /**
     * Collects tasks from markers of a specific type.
     */
    private static void collectTasksFromMarkers(IProject project, String markerType, 
        List<TaskInfo> tasks, String filePath, Integer priorityFilter, int limit)
    {
        try
        {
            IMarker[] markers = project.findMarkers(markerType, true, IResource.DEPTH_INFINITE);
            
            for (IMarker marker : markers)
            {
                if (tasks.size() >= limit)
                {
                    break;
                }
                
                // Get priority
                int markerPriority = marker.getAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
                
                // Apply priority filter
                if (priorityFilter != null && markerPriority != priorityFilter)
                {
                    continue;
                }
                
                // Get resource path
                IResource resource = marker.getResource();
                IPath resourcePath = resource.getFullPath();
                String resourcePathStr = resourcePath != null ? resourcePath.toString() : ""; //$NON-NLS-1$
                
                // Apply file path filter
                if (filePath != null && !filePath.isEmpty() && 
                    !resourcePathStr.toLowerCase().contains(filePath.toLowerCase()))
                {
                    continue;
                }
                
                // Create task info
                TaskInfo task = new TaskInfo();
                task.message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                task.path = resourcePathStr;
                task.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                task.priority = getPriorityString(markerPriority);
                task.type = getTaskType(task.message);
                
                tasks.add(task);
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to get tasks for: " + project.getName(), e); //$NON-NLS-1$
        }
    }
    
    /**
     * Converts priority integer to string.
     */
    private static String getPriorityString(int priority)
    {
        switch (priority)
        {
            case IMarker.PRIORITY_HIGH:
                return "high"; //$NON-NLS-1$
            case IMarker.PRIORITY_NORMAL:
                return "normal"; //$NON-NLS-1$
            case IMarker.PRIORITY_LOW:
            default:
                return "low"; //$NON-NLS-1$
        }
    }
    
    /**
     * Extracts task type from message (TODO, FIXME, etc.)
     */
    private static String getTaskType(String message)
    {
        if (message == null || message.isEmpty())
        {
            return "TASK"; //$NON-NLS-1$
        }
        
        String upperMessage = message.toUpperCase();
        if (upperMessage.contains("TODO")) //$NON-NLS-1$
        {
            return "TODO"; //$NON-NLS-1$
        }
        if (upperMessage.contains("FIXME")) //$NON-NLS-1$
        {
            return "FIXME"; //$NON-NLS-1$
        }
        if (upperMessage.contains("XXX")) //$NON-NLS-1$
        {
            return "XXX"; //$NON-NLS-1$
        }
        if (upperMessage.contains("HACK")) //$NON-NLS-1$
        {
            return "HACK"; //$NON-NLS-1$
        }
        return "TASK"; //$NON-NLS-1$
    }
    
    /**
     * Helper class to store task info.
     */
    private static class TaskInfo
    {
        String message;
        String path;
        String priority;
        String type;
        int line;
    }
}
