/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
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
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

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
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Name of the project (optional, all projects if not specified)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
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
     * @return Markdown string with problem summary
     */
    public static String getProblemSummary(String projectName)
    {
        StringBuilder md = new StringBuilder();
        
        try
        {
            IMarkerManager markerManager = Activator.getDefault().getMarkerManager();
            
            if (markerManager == null)
            {
                return "**Error:** IMarkerManager service is not available"; //$NON-NLS-1$
            }
            
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            
            // Validate project if specified
            if (projectName != null && !projectName.isEmpty())
            {
                IProject project = workspace.getRoot().getProject(projectName);
                if (project == null || !project.exists())
                {
                    return "**Error:** Project not found: " + projectName; //$NON-NLS-1$
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
            
            // Build Markdown response
            md.append("## Problem Summary\n\n"); //$NON-NLS-1$
            
            // Totals section
            md.append("### Overall Totals\n\n"); //$NON-NLS-1$
            md.append("| Severity | Count |\n"); //$NON-NLS-1$
            md.append("|----------|-------|\n"); //$NON-NLS-1$
            
            for (MarkerSeverity sev : MarkerSeverity.values())
            {
                if (sev == MarkerSeverity.NONE && totals.get(sev) == 0)
                {
                    continue; // Skip NONE if empty
                }
                md.append("| ").append(sev.name()).append(" | ").append(totals.get(sev)).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            md.append("| **TOTAL** | **").append(grandTotal).append("** |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Projects section
            if (!projectSummaries.isEmpty())
            {
                md.append("### By Project\n\n"); //$NON-NLS-1$
                md.append("| Project | Errors | Blocker | Critical | Major | Minor | Trivial | Total |\n"); //$NON-NLS-1$
                md.append("|---------|--------|---------|----------|-------|-------|---------|-------|\n"); //$NON-NLS-1$
                
                for (Map.Entry<String, Map<MarkerSeverity, Integer>> entry : projectSummaries.entrySet())
                {
                    Map<MarkerSeverity, Integer> counts = entry.getValue();
                    int projectTotal = counts.values().stream().mapToInt(Integer::intValue).sum();
                    
                    md.append("| "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(entry.getKey()));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.ERRORS, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.BLOCKER, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.CRITICAL, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.MAJOR, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.MINOR, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(counts.getOrDefault(MarkerSeverity.TRIVIAL, 0));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(projectTotal);
                    md.append(" |\n"); //$NON-NLS-1$
                }
            }
            else
            {
                md.append("*No problems found.*\n"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting problem summary", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
        
        return md.toString();
    }
}
