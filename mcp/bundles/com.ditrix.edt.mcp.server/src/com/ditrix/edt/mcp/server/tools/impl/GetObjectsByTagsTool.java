/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

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
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringArrayProperty("tags", //$NON-NLS-1$
                "Array of tag names to filter by (e.g. ['Important', 'NeedsReview']). " + //$NON-NLS-1$
                "Returns objects that have ANY of these tags. Required.") //$NON-NLS-1$
            .integerProperty("limit", //$NON-NLS-1$
                "Maximum number of objects to return per tag. Default: 100") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String tagsJson = JsonUtils.extractStringArgument(params, "tags"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
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
        
        // Parse tags array
        List<String> tagNames = parseTagsList(tagsJson);
        if (tagNames.isEmpty())
        {
            return ToolResult.error("Tags array is required. Example: [\"Important\", \"NeedsReview\"]").toJson(); //$NON-NLS-1$
        }
        
        int limit = 100;
        if (limitStr != null && !limitStr.isEmpty())
        {
            try
            {
                limit = Math.min(Integer.parseInt(limitStr), 1000);
            }
            catch (NumberFormatException e)
            {
                // Use default
            }
        }
        
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        
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
     * Parses the tags array from JSON string.
     * 
     * @param tagsJson JSON array string like ["Important", "NeedsReview"]
     * @return list of tag names
     */
    private List<String> parseTagsList(String tagsJson)
    {
        List<String> result = new ArrayList<>();
        if (tagsJson == null || tagsJson.isEmpty())
        {
            return result;
        }
        
        try
        {
            JsonElement element = JsonParser.parseString(tagsJson);
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
            Activator.logError("Error parsing tags JSON: " + tagsJson, e); //$NON-NLS-1$
        }
        return result;
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
                sb.append("| # | Object FQN |\n"); //$NON-NLS-1$
                sb.append("|---|------------|\n"); //$NON-NLS-1$
                
                int count = 0;
                for (String fqn : objects)
                {
                    if (count >= limit)
                    {
                        sb.append("| ... | *").append(objects.size() - limit) //$NON-NLS-1$
                          .append(" more objects (limit reached)* |\n"); //$NON-NLS-1$
                        break;
                    }
                    sb.append("| ").append(++count).append(" | ").append(fqn).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
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
