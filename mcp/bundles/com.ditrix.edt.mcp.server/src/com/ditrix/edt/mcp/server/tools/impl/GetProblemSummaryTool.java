/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.HashMap;
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
 * Tool to get problem summary (counts by project and severity).
 * Uses EDT IMarkerManager for accessing configuration problems.
 */
public class GetProblemSummaryTool implements IMcpTool
{
    public static final String NAME = "get_problem_summary"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get problem summary with counts grouped by project and EDT severity level " + //$NON-NLS-1$
               "(ERRORS, BLOCKER, CRITICAL, MAJOR, MINOR, TRIVIAL)."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"projectName\": {\"type\": \"string\", \"description\": \"Name of the project (optional, all projects if not specified)\"}" + //$NON-NLS-1$
               "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        return getProblemSummary(projectName);
    }
    
    /**
     * Gets problem summary for project(s) using EDT IMarkerManager.
     * 
     * @param projectName specific project name or null for all projects
     * @return JSON string with problem summary
     */
    public static String getProblemSummary(String projectName)
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
            
            // Validate project if specified
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
            }
            
            // Summary structure: projectName -> severity -> count
            Map<String, Map<MarkerSeverity, Integer>> projectSummaries = new HashMap<>();
            Map<MarkerSeverity, Integer> totals = new HashMap<>();
            
            // Initialize totals
            for (MarkerSeverity sev : MarkerSeverity.values())
            {
                totals.put(sev, 0);
            }
            
            // Get all markers
            Stream<Marker> markerStream = markerManager.markers();
            final String filterProject = projectName;
            
            markerStream.forEach(marker -> {
                IProject markerProject = marker.getProject();
                if (markerProject == null)
                {
                    return;
                }
                
                String markerProjectName = markerProject.getName();
                
                // Filter by project if specified
                if (filterProject != null && !filterProject.isEmpty() && 
                    !markerProjectName.equals(filterProject))
                {
                    return;
                }
                
                MarkerSeverity severity = marker.getSeverity();
                if (severity == null)
                {
                    severity = MarkerSeverity.NONE;
                }
                
                // Update project summary
                projectSummaries.computeIfAbsent(markerProjectName, k -> {
                    Map<MarkerSeverity, Integer> map = new HashMap<>();
                    for (MarkerSeverity sev : MarkerSeverity.values())
                    {
                        map.put(sev, 0);
                    }
                    return map;
                });
                
                Map<MarkerSeverity, Integer> projectCounts = projectSummaries.get(markerProjectName);
                projectCounts.put(severity, projectCounts.get(severity) + 1);
                
                // Update totals
                totals.put(severity, totals.get(severity) + 1);
            });
            
            // Calculate total
            int grandTotal = totals.values().stream().mapToInt(Integer::intValue).sum();
            
            // Build JSON response
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"totals\": {"); //$NON-NLS-1$
            
            boolean first = true;
            for (MarkerSeverity sev : MarkerSeverity.values())
            {
                if (sev == MarkerSeverity.NONE && totals.get(sev) == 0)
                {
                    continue; // Skip NONE if empty
                }
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                json.append("\"").append(sev.name()).append("\": ").append(totals.get(sev)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            json.append(",\"total\": ").append(grandTotal); //$NON-NLS-1$
            json.append("},"); //$NON-NLS-1$
            
            json.append("\"projects\": ["); //$NON-NLS-1$
            first = true;
            for (Map.Entry<String, Map<MarkerSeverity, Integer>> entry : projectSummaries.entrySet())
            {
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                
                Map<MarkerSeverity, Integer> counts = entry.getValue();
                int projectTotal = counts.values().stream().mapToInt(Integer::intValue).sum();
                
                json.append("{"); //$NON-NLS-1$
                json.append("\"project\": \"").append(JsonUtils.escapeJson(entry.getKey())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                
                for (MarkerSeverity sev : MarkerSeverity.values())
                {
                    if (sev == MarkerSeverity.NONE && counts.get(sev) == 0)
                    {
                        continue;
                    }
                    json.append("\"").append(sev.name()).append("\": ").append(counts.get(sev)).append(","); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                json.append("\"total\": ").append(projectTotal); //$NON-NLS-1$
                json.append("}"); //$NON-NLS-1$
            }
            json.append("]"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting problem summary", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
    }
}
