/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com._1c.g5.v8.dt.validation.marker.IMarkerManager;
import com._1c.g5.v8.dt.validation.marker.MarkerSeverity;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * Tool to get detailed project errors with optional filters.
 * Uses EDT IMarkerManager for accessing configuration problems.
 */
public class GetProjectErrorsTool implements IMcpTool
{
    public static final String NAME = "get_project_errors"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed configuration problems from EDT. " + //$NON-NLS-1$
               "Returns check code, description, object location, severity level (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL). " + //$NON-NLS-1$
               "Can filter by specific objects using FQN (e.g. 'Document.SalesOrder', 'Catalog.Products')."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("severity", "Filter by severity: ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("checkId", "Filter by check ID substring (e.g. 'ql-temp-table-index') (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringArrayProperty("objects", "Filter by object FQNs (e.g. ['Document.SalesOrder', 'Catalog.Products']). Returns errors only from these objects.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String severity = JsonUtils.extractStringArgument(params, "severity"); //$NON-NLS-1$
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        String objectsJson = JsonUtils.extractStringArgument(params, "objects"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
        // Check if project is ready for operations
        if (projectName != null && !projectName.isEmpty())
        {
            String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
            if (notReadyError != null)
            {
                return ToolResult.error(notReadyError).toJson();
            }
        }
        
        // Parse objects filter
        List<String> objects = parseObjectsList(objectsJson);
        
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
        
        return getProjectErrors(projectName, severity, checkId, objects, limit);
    }
    
    /**
     * Parses the objects array from JSON string using Gson JsonParser.
     * 
     * @param objectsJson JSON array string like ["Document.SalesOrder", "Catalog.Products"]
     * @return list of object FQNs
     */
    private List<String> parseObjectsList(String objectsJson)
    {
        List<String> result = new ArrayList<>();
        if (objectsJson == null || objectsJson.isEmpty())
        {
            return result;
        }
        
        try
        {
            JsonElement element = JsonParser.parseString(objectsJson);
            if (element.isJsonArray())
            {
                JsonArray array = element.getAsJsonArray();
                for (JsonElement item : array)
                {
                    if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                    {
                        result.add(item.getAsString());
                    }
                }
            }
        }
        catch (JsonParseException e)
        {
            Activator.logError("Error parsing objects JSON: " + objectsJson, e); //$NON-NLS-1$
        }
        return result;
    }
    
    /**
     * Gets project errors with filters using EDT IMarkerManager.
     * 
     * @param projectName filter by project name (null for all)
     * @param severity filter by severity (ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)
     * @param checkId filter by check ID substring
     * @param objects filter by object FQNs (empty list for all objects)
     * @param limit maximum number of results
     * @return Markdown formatted string with error details
     */
    public static String getProjectErrors(String projectName, String severity, String checkId, List<String> objects, int limit)
    {
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return "# Error\n\nIMarkerManager service is not available"; //$NON-NLS-1$
            }
            
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            
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
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return "# Error\n\nProject not found: " + projectName; //$NON-NLS-1$
                }
            }
            
            // Collect errors from EDT MarkerManager using proper stream operations
            final MarkerSeverity finalSeverityFilter = severityFilter;
            final String finalCheckId = checkId;
            final String finalProjectName = projectName;
            final List<String> finalObjects = objects != null ? objects : new ArrayList<>();
            
            // Use filter + limit instead of forEach with early return (which doesn't work)
            List<ErrorInfo> errors = markerManager.markers()
                .filter(marker -> {
                    // Get project
                    IProject markerProject = marker.getProject();
                    if (markerProject == null)
                    {
                        return false;
                    }
                    
                    // Check project filter
                    if (finalProjectName != null && !finalProjectName.isEmpty() && 
                        !markerProject.getName().equals(finalProjectName))
                    {
                        return false;
                    }
                    
                    // Check severity filter
                    MarkerSeverity markerSeverity = marker.getSeverity();
                    if (finalSeverityFilter != null && markerSeverity != finalSeverityFilter)
                    {
                        return false;
                    }
                    
                    // Check checkId filter
                    String markerCheckId = marker.getCheckId();
                    if (finalCheckId != null && !finalCheckId.isEmpty())
                    {
                        if (markerCheckId == null || 
                            !markerCheckId.toLowerCase().contains(finalCheckId.toLowerCase()))
                        {
                            return false;
                        }
                    }
                    
                    // Check objects filter (FQN matching)
                    if (!finalObjects.isEmpty())
                    {
                        String objectPresentation = marker.getObjectPresentation();
                        if (objectPresentation == null || objectPresentation.isEmpty())
                        {
                            return false;
                        }
                        
                        // Check if any of the FQNs match the object presentation
                        boolean matchesAnyObject = false;
                        for (String fqn : finalObjects)
                        {
                            // objectPresentation typically contains path like "Document.SalesOrder / Module / Procedure"
                            // or "Catalog.Products.Attribute.Name"
                            // We check if it starts with or contains the FQN
                            if (objectPresentation.toLowerCase().contains(fqn.toLowerCase()) ||
                                objectPresentation.toLowerCase().startsWith(fqn.toLowerCase()))
                            {
                                matchesAnyObject = true;
                                break;
                            }
                        }
                        if (!matchesAnyObject)
                        {
                            return false;
                        }
                    }
                    
                    return true;
                })
                .limit(limit)
                .map(marker -> {
                    ErrorInfo error = new ErrorInfo();
                    error.checkId = marker.getCheckId() != null ? marker.getCheckId() : ""; //$NON-NLS-1$
                    error.message = marker.getMessage() != null ? marker.getMessage() : ""; //$NON-NLS-1$
                    MarkerSeverity sev = marker.getSeverity();
                    error.severity = sev != null ? sev.name() : "NONE"; //$NON-NLS-1$
                    error.objectPresentation = marker.getObjectPresentation() != null ? 
                        marker.getObjectPresentation() : ""; //$NON-NLS-1$
                    return error;
                })
                .collect(Collectors.toList());
            
            // Build Markdown response for better readability and context efficiency
            StringBuilder md = new StringBuilder();
            
            if (errors.isEmpty())
            {
                md.append("# No Errors Found\n\n"); //$NON-NLS-1$
                if (projectName != null && !projectName.isEmpty())
                {
                    md.append("Project: **").append(projectName).append("**\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (severity != null && !severity.isEmpty())
                {
                    md.append("Severity filter: ").append(severity).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (objects != null && !objects.isEmpty())
                {
                    md.append("Objects filter: ").append(String.join(", ", objects)).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                md.append("\nNo configuration problems match the specified criteria."); //$NON-NLS-1$
            }
            else
            {
                md.append("# Configuration Problems\n\n"); //$NON-NLS-1$
                md.append("**Found:** ").append(errors.size()); //$NON-NLS-1$
                if (errors.size() >= limit)
                {
                    md.append("+ (limited to ").append(limit).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                md.append("\n\n"); //$NON-NLS-1$
                
                // Group by severity for better overview
                md.append("| Severity | Check ID | Message | Location |\n"); //$NON-NLS-1$
                md.append("|----------|----------|---------|----------|\n"); //$NON-NLS-1$
                
                for (ErrorInfo error : errors)
                {
                    md.append("| ").append(error.severity); //$NON-NLS-1$
                    md.append(" | `").append(error.checkId).append("`"); //$NON-NLS-1$ //$NON-NLS-2$
                    md.append(" | ").append(MarkdownUtils.escapeForTable(error.message)); //$NON-NLS-1$
                    md.append(" | ").append(MarkdownUtils.escapeForTable(error.objectPresentation)); //$NON-NLS-1$
                    md.append(" |\n"); //$NON-NLS-1$
                }
            }
            
            return md.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Error getting project errors", e); //$NON-NLS-1$
            return "# Error\n\nFailed to get project errors: " + e.getMessage(); //$NON-NLS-1$
        }
    }
    
    /**
     * Helper class to store error info.
     */
    private static class ErrorInfo
    {
        String checkId;
        String message;
        String severity;
        String objectPresentation;
    }
}
