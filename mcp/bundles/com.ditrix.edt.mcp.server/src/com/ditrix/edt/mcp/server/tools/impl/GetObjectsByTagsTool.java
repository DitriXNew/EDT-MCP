/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tags.TagService;
import com.ditrix.edt.mcp.server.tags.model.Tag;
import com.ditrix.edt.mcp.server.tags.model.TagStorage;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;

/**
 * Tool to get metadata objects filtered by tags.
 * Returns objects that have any of the specified tags,
 * along with tag descriptions and object FQNs.
 */
public class GetObjectsByTagsTool implements IMcpTool
{
    public static final String NAME = "get_objects_by_tags"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get metadata objects filtered by tags. " + //$NON-NLS-1$
               "Returns objects that have any of the specified tags, " + //$NON-NLS-1$
               "including tag descriptions and object FQNs."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringArrayProperty("tags", //$NON-NLS-1$
                "Array of tag names to filter by (e.g. ['Important', 'NeedsReview']). " + //$NON-NLS-1$
                "Returns objects that have ANY of these tags. Required.", true) //$NON-NLS-1$
            .integerProperty(McpKeys.LIMIT,
                "Maximum number of objects to return per tag. Default: 100") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);

        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        
        // Refuse only the transient BUILDING state; a missing/closed project falls
        // through to the value-naming "Project not found: <name>" below instead of a
        // misleading "Project does not exist. Please wait and retry."
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        // Tags list: accepts a JSON array (["Important","NeedsReview"]) or a
        // comma-separated string, via the shared extractArrayArgument helper.
        List<String> tagNames = JsonUtils.extractArrayArgument(params, "tags"); //$NON-NLS-1$
        if (tagNames == null || tagNames.isEmpty())
        {
            return ToolResult.error("Tags array is required. Example: [\"Important\", \"NeedsReview\"]").toJson(); //$NON-NLS-1$
        }
        
        int limit = JsonUtils.extractIntArgument(params, McpKeys.LIMIT, 100);
        limit = Pagination.clampLimit(limit, 1000);

        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        IProject project = ctx.project();

        try
        {
            return getObjectsByTags(project, tagNames, limit);
        }
        catch (Exception e)
        {
            Activator.logError("Error getting objects by tags for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Error getting objects by tags: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
    
    
    /**
     * Gets objects filtered by tags.
     * 
     * @param project the project
     * @param tagNames list of tag names to filter by
     * @param limit maximum objects per tag
     * @return Markdown formatted string with results
     */
    private String getObjectsByTags(IProject project, List<String> tagNames, int limit)
    {
        TagService tagService = TagService.getInstance();
        TagStorage storage = tagService.getTagStorage(project);
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Objects by Tags in project: ").append(project.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        
        int totalObjects = 0;
        List<String> notFoundTags = new ArrayList<>();
        
        for (String tagName : tagNames)
        {
            Tag tag = storage.getTagByName(tagName);
            if (tag == null)
            {
                notFoundTags.add(tagName);
                continue;
            }
            
            Set<String> objects = storage.getObjectsByTag(tagName);
            
            // Tag header with description
            sb.append("## Tag: ").append(tag.getName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("- **Color:** ").append(tag.getColor()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            String description = tag.getDescription();
            if (description != null && !description.isEmpty())
            {
                sb.append("- **Description:** ").append(description).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            sb.append("- **Objects count:** ").append(objects.size()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            
            if (objects.isEmpty())
            {
                sb.append("*No objects assigned to this tag*\n\n"); //$NON-NLS-1$
            }
            else
            {
                sb.append(MarkdownUtils.tableHeader("#", "Object FQN")); //$NON-NLS-1$ //$NON-NLS-2$

                int count = 0;
                for (String fqn : objects)
                {
                    if (count >= limit)
                    {
                        sb.append(MarkdownUtils.tableRow("...", //$NON-NLS-1$
                            "*" + (objects.size() - limit) + " more objects (limit reached)*")); //$NON-NLS-1$ //$NON-NLS-2$
                        break;
                    }
                    sb.append(MarkdownUtils.tableRow(String.valueOf(++count), fqn));
                }
                sb.append("\n"); //$NON-NLS-1$
                totalObjects += Math.min(objects.size(), limit);
            }
        }
        
        // Report not found tags
        if (!notFoundTags.isEmpty())
        {
            sb.append("## ⚠️ Tags not found\n\n"); //$NON-NLS-1$
            for (String tagName : notFoundTags)
            {
                sb.append("- ").append(tagName).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        
        // Summary
        sb.append("---\n"); //$NON-NLS-1$
        sb.append("**Summary:** Found ").append(totalObjects) //$NON-NLS-1$
          .append(" objects across ").append(tagNames.size() - notFoundTags.size()) //$NON-NLS-1$
          .append(" tags"); //$NON-NLS-1$
        
        return sb.toString();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
}
