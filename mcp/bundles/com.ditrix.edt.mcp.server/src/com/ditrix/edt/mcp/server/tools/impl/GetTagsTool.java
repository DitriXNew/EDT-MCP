/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to get all tags defined in a project with their descriptions.
 * Tags are user-defined labels that can be applied to metadata objects
 * for organization and filtering purposes.
 */
public class GetTagsTool implements IMcpTool
{
    public static final String NAME = "get_tags"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get list of all tags defined in the project. " + //$NON-NLS-1$
               "Tags are user-defined labels for organizing metadata objects. " + //$NON-NLS-1$
               "Returns tag name, color, description, and number of assigned objects."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("Project name is required").toJson(); //$NON-NLS-1$
        }
        
        // Check if project is ready for operations
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }
        
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        
        try
        {
            return getTags(project);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting tags for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Error getting tags: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
    
    /**
     * Gets all tags from the project.
     * 
     * @param project the project
     * @return Markdown formatted string with tag details
     */
    private String getTags(IProject project)
    {
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);
        List<Tag> tags = storage.getTags();
        
        if (tags.isEmpty())
        {
            return "No tags defined in project: " + project.getName(); //$NON-NLS-1$
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Tags in project: ").append(project.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| # | Name | Color | Description | Objects |\n"); //$NON-NLS-1$
        sb.append("|---|------|-------|-------------|--------|\n"); //$NON-NLS-1$
        
        int index = 1;
        for (Tag tag : tags)
        {
            int objectCount = storage.getObjectsByTag(tag.getName()).size();
            String description = tag.getDescription();
            if (description == null || description.isEmpty())
            {
                description = "-"; //$NON-NLS-1$
            }
            else
            {
                // Escape pipe characters for markdown table
                description = description.replace("|", "\\|"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            sb.append("| ").append(index++).append(" | "); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(tag.getName()).append(" | "); //$NON-NLS-1$
            sb.append(tag.getColor()).append(" | "); //$NON-NLS-1$
            sb.append(description).append(" | "); //$NON-NLS-1$
            sb.append(objectCount).append(" |\n"); //$NON-NLS-1$
        }
        
        sb.append("\n**Total tags:** ").append(tags.size()); //$NON-NLS-1$
        
        return sb.toString();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
}
