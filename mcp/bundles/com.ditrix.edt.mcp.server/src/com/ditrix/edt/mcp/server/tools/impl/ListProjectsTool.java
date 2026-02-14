/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker.ProjectStateResult;

/**
 * Tool to list all workspace projects.
 */
public class ListProjectsTool implements IMcpTool
{
    public static final String NAME = "list_projects"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "List all workspace projects with properties (name, path, type, natures)"; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object().build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        return listProjects();
    }
    
    /**
     * Returns list of workspace projects with their properties.
     * 
     * @return Markdown string with project list
     */
    public static String listProjects()
    {
        StringBuilder md = new StringBuilder();
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject[] projects = workspace.getRoot().getProjects();
            
            md.append("## Workspace Projects\n\n"); //$NON-NLS-1$
            md.append("**Total:** ").append(projects.length).append(" projects\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            if (projects.length == 0)
            {
                md.append("*No projects found.*\n"); //$NON-NLS-1$
            }
            else
            {
                // Table header - added State column
                md.append("| Name | State | Path | Open | EDT Project | Natures |\n"); //$NON-NLS-1$
                md.append("|------|-------|------|------|-------------|--------|\n"); //$NON-NLS-1$
                
                for (IProject project : projects)
                {
                    md.append("| "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(project.getName()));
                    md.append(" | "); //$NON-NLS-1$
                    
                    // Project state
                    ProjectStateResult stateResult = ProjectStateChecker.checkProjectState(project);
                    md.append(stateResult.getStateValue());
                    md.append(" | "); //$NON-NLS-1$
                    
                    md.append(MarkdownUtils.escapeForTable(project.getLocation() != null ? 
                        project.getLocation().toOSString() : "")); //$NON-NLS-1$
                    md.append(" | "); //$NON-NLS-1$
                    md.append(project.isOpen() ? "Yes" : "No"); //$NON-NLS-1$ //$NON-NLS-2$
                    md.append(" | "); //$NON-NLS-1$
                    
                    // EDT project check and natures
                    String edtStatus = "-"; //$NON-NLS-1$
                    String naturesStr = "-"; //$NON-NLS-1$
                    
                    if (project.isOpen())
                    {
                        try
                        {
                            boolean isEdtProject = project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature") || //$NON-NLS-1$
                                                   project.hasNature("com._1c.g5.v8.dt.core.V8ExtensionNature"); //$NON-NLS-1$
                            edtStatus = isEdtProject ? "Yes" : "No"; //$NON-NLS-1$ //$NON-NLS-2$
                            
                            String[] natures = project.getDescription().getNatureIds();
                            if (natures.length > 0)
                            {
                                // Show abbreviated natures
                                StringBuilder naturesBuilder = new StringBuilder();
                                for (int i = 0; i < Math.min(natures.length, 3); i++)
                                {
                                    if (i > 0)
                                    {
                                        naturesBuilder.append(", "); //$NON-NLS-1$
                                    }
                                    // Get short nature name
                                    String nature = natures[i];
                                    int lastDot = nature.lastIndexOf('.');
                                    naturesBuilder.append(lastDot > 0 ? nature.substring(lastDot + 1) : nature);
                                }
                                if (natures.length > 3)
                                {
                                    naturesBuilder.append("...+").append(natures.length - 3); //$NON-NLS-1$
                                }
                                naturesStr = naturesBuilder.toString();
                            }
                        }
                        catch (Exception e)
                        {
                            // Ignore errors for specific project
                        }
                    }
                    
                    md.append(edtStatus);
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(naturesStr));
                    md.append(" |\n"); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to list projects", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
        
        return md.toString();
    }
}
