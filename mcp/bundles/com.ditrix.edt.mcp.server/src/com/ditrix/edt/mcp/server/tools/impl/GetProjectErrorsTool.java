/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.Marker;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to get detailed project errors with optional filters.
 * Uses EDT IMarkerManager for accessing configuration problems.
 */
public class GetProjectErrorsTool implements IMcpTool
{
    public static final String NAME = "get_project_errors"; //$NON-NLS-1$
    
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
        return "Get detailed configuration problems from EDT. " + //$NON-NLS-1$
               "Returns check code, description, object location, severity level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"projectName\": {\"type\": \"string\", \"description\": \"Filter by project name (optional)\"}," + //$NON-NLS-1$
               "\"severity\": {\"type\": \"string\", \"description\": \"Filter by severity: ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL (optional)\"}," + //$NON-NLS-1$
               "\"checkId\": {\"type\": \"string\", \"description\": \"Filter by check ID substring (e.g. 'ql-temp-table-index') (optional)\"}," + //$NON-NLS-1$
               "\"limit\": {\"type\": \"integer\", \"description\": \"Maximum number of results (default: 100, max: 1000)\"}" + //$NON-NLS-1$
               "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
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
        
        return getProjectErrors(projectName, severity, checkId, limit);
    }
    
    /**
     * Gets project errors with filters using EDT IMarkerManager.
     * 
     * @param projectName filter by project name (null for all)
     * @param severity filter by severity (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
     * @param checkId filter by check ID substring
     * @param limit maximum number of results
     * @return JSON string with error details
     */
    public static String getProjectErrors(String projectName, String severity, String checkId, int limit)
    {
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                json.append("\"success\": false,"); //$NON-NLS-1$
                json.append("\"error\": \"IMarkerManager service is not available\""); //$NON-NLS-1$
                json.append("}"); //$NON-NLS-1$
                return json.toString();
            }
            
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            List<ErrorInfo> errors = new ArrayList<>();
            
            // Parse severity filter
            MarkerSeverity severityFilter = null;
            if (severity != null && !severity.isEmpty())
            {
                try
                {
                    severityFilter = MarkerSeverity.valueOf(severity.toUpperCase());
                }
                catch (IllegalArgumentException e)
                {
                    // Invalid severity, will show all
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
            
            // Collect errors from EDT MarkerManager
            final MarkerSeverity finalSeverityFilter = severityFilter;
            final String finalCheckId = checkId;
            
            // Get all markers via stream and filter
            Stream<Marker> markerStream = markerManager.markers();
            
            markerStream.forEach(marker -> {
                if (errors.size() >= limit)
                {
                    return;
                }
                
                // Get project
                IProject markerProject = marker.getProject();
                if (markerProject == null)
                {
                    return;
                }
                
                // Check project filter
                if (projectName != null && !projectName.isEmpty() && 
                    !markerProject.getName().equals(projectName))
                {
                    return;
                }
                
                // Check severity filter
                MarkerSeverity markerSeverity = marker.getSeverity();
                if (finalSeverityFilter != null && markerSeverity != finalSeverityFilter)
                {
                    return;
                }
                
                // Check checkId filter
                String markerCheckId = marker.getCheckId();
                if (finalCheckId != null && !finalCheckId.isEmpty())
                {
                    if (markerCheckId == null || 
                        !markerCheckId.toLowerCase().contains(finalCheckId.toLowerCase()))
                    {
                        return;
                    }
                }
                
                // Create error info
                ErrorInfo error = new ErrorInfo();
                error.project = markerProject.getName();
                error.checkId = markerCheckId != null ? markerCheckId : ""; //$NON-NLS-1$
                error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$
                error.severity = markerSeverity != null ? markerSeverity.name() : "NONE"; //$NON-NLS-1$
                error.location = marker.getLocation() != null ? marker.getLocation() : ""; //$NON-NLS-1$
                error.objectPresentation = marker.getObjectPresentation() != null ? 
                    marker.getObjectPresentation() : ""; //$NON-NLS-1$
                
                errors.add(error);
            });
            
            // Build JSON response
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"count\": ").append(errors.size()).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"limit\": ").append(limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"hasMore\": ").append(errors.size() >= limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"errors\": ["); //$NON-NLS-1$
            
            boolean first = true;
            for (ErrorInfo error : errors)
            {
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                
                json.append("{"); //$NON-NLS-1$
                json.append("\"project\": \"").append(JsonUtils.escapeJson(error.project)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"checkId\": \"").append(JsonUtils.escapeJson(error.checkId)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"message\": \"").append(JsonUtils.escapeJson(error.message)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"severity\": \"").append(error.severity).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"location\": \"").append(JsonUtils.escapeJson(error.location)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"object\": \"").append(JsonUtils.escapeJson(error.objectPresentation)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("}"); //$NON-NLS-1$
            }
            
            json.append("]"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
    }
    
    /**
     * Helper class to store error info.
     */
    private static class ErrorInfo
    {
        String project;
        String checkId;
        String message;
        String severity;
        String location;
        String objectPresentation;
    }
}
