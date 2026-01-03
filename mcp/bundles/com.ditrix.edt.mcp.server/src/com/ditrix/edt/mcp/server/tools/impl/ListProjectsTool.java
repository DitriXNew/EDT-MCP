/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

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
        return "{\"type\": \"object\", \"properties\": {}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        return listProjects();
    }
    
    /**
     * Returns list of workspace projects with their properties.
     * 
     * @return JSON string with project list
     */
    public static String listProjects()
    {
        StringBuilder json = new StringBuilder();
        json.append("["); //$NON-NLS-1$
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject[] projects = workspace.getRoot().getProjects();
            
            boolean first = true;
            for (IProject project : projects)
            {
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                
                json.append("{"); //$NON-NLS-1$
                json.append("\"name\": \"").append(JsonUtils.escapeJson(project.getName())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"path\": \"").append(JsonUtils.escapeJson(project.getLocation() != null ?  //$NON-NLS-1$
                    project.getLocation().toOSString() : "")).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"open\": ").append(project.isOpen()).append(","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"accessible\": ").append(project.isAccessible()); //$NON-NLS-1$
                
                // Try to get additional EDT-specific properties
                if (project.isOpen())
                {
                    try
                    {
                        // Check if it's a 1C:EDT project by nature
                        boolean isEdtProject = project.hasNature("com._1c.g5.v8.dt.core.V8ConfigurationNature") || //$NON-NLS-1$
                                               project.hasNature("com._1c.g5.v8.dt.core.V8ExtensionNature"); //$NON-NLS-1$
                        json.append(",\"edtProject\": ").append(isEdtProject); //$NON-NLS-1$
                        
                        // Get project description
                        String comment = project.getDescription().getComment();
                        if (comment != null && !comment.isEmpty())
                        {
                            json.append(",\"description\": \"").append(JsonUtils.escapeJson(comment)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        
                        // Get natures
                        String[] natures = project.getDescription().getNatureIds();
                        if (natures.length > 0)
                        {
                            json.append(",\"natures\": ["); //$NON-NLS-1$
                            for (int i = 0; i < natures.length; i++)
                            {
                                if (i > 0)
                                {
                                    json.append(","); //$NON-NLS-1$
                                }
                                json.append("\"").append(JsonUtils.escapeJson(natures[i])).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                            }
                            json.append("]"); //$NON-NLS-1$
                        }
                    }
                    catch (Exception e)
                    {
                        // Ignore errors for specific project
                    }
                }
                
                json.append("}"); //$NON-NLS-1$
            }
        }
        catch (Exception e)
        {
            Activator.logError("Failed to list projects", e); //$NON-NLS-1$
            return "[{\"error\": \"" + JsonUtils.escapeJson(e.getMessage()) + "\"}]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("]"); //$NON-NLS-1$
        return json.toString();
    }
}
