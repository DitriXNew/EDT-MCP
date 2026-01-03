/**
 * Copyright (c) 2025 DitriX
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
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to get tasks (TODO, FIXME, etc.) from the workspace.
 */
public class GetTasksTool implements IMcpTool
{
    public static final String NAME = "get_tasks"; //$NON-NLS-1$
    
    // Task marker types
    private static final String TASK_MARKER_TYPE = "org.eclipse.core.resources.taskmarker"; //$NON-NLS-1$
    private static final String XTEXT_TASK_MARKER_TYPE = "org.eclipse.xtext.ui.task"; //$NON-NLS-1$
    
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;
    
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
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"projectName\": {\"type\": \"string\", \"description\": \"Filter by project name (optional)\"}," + //$NON-NLS-1$
               "\"filePath\": {\"type\": \"string\", \"description\": \"Filter by file path substring (optional)\"}," + //$NON-NLS-1$
               "\"priority\": {\"type\": \"string\", \"description\": \"Filter by priority: high, normal, low (optional)\"}," + //$NON-NLS-1$
               "\"limit\": {\"type\": \"integer\", \"description\": \"Maximum number of results (default: 100, max: 1000)\"}" + //$NON-NLS-1$
               "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String priority = JsonUtils.extractStringArgument(params, "priority"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
        int limit = DEFAULT_LIMIT;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min(Integer.parseInt(limitStr), MAX_LIMIT);
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
     * @return JSON string with task details
     */
    public static String getTasks(String projectName, String filePath, String priority, int limit)
    {
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
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
                    json.append("\"success\": false,"); //$NON-NLS-1$
                    json.append("\"error\": \"Project not found: ").append(JsonUtils.escapeJson(projectName)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                    json.append("}"); //$NON-NLS-1$
                    return json.toString();
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
            
            // Build JSON response
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"count\": ").append(tasks.size()).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"limit\": ").append(limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"hasMore\": ").append(tasks.size() >= limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"tasks\": ["); //$NON-NLS-1$
            
            boolean first = true;
            for (TaskInfo task : tasks)
            {
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                
                json.append("{"); //$NON-NLS-1$
                json.append("\"project\": \"").append(JsonUtils.escapeJson(task.project)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"message\": \"").append(JsonUtils.escapeJson(task.message)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"path\": \"").append(JsonUtils.escapeJson(task.path)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"line\": ").append(task.line).append(","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"priority\": \"").append(task.priority).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"type\": \"").append(JsonUtils.escapeJson(task.type)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                
                if (task.charStart >= 0)
                {
                    json.append(",\"charStart\": ").append(task.charStart); //$NON-NLS-1$
                }
                if (task.charEnd >= 0)
                {
                    json.append(",\"charEnd\": ").append(task.charEnd); //$NON-NLS-1$
                }
                
                json.append("}"); //$NON-NLS-1$
            }
            
            json.append("]"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting tasks", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
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
                task.project = project.getName();
                task.message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                task.path = resourcePathStr;
                task.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                task.charStart = marker.getAttribute(IMarker.CHAR_START, -1);
                task.charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
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
        String project;
        String message;
        String path;
        String priority;
        String type;
        int line;
        int charStart;
        int charEnd;
    }
}
