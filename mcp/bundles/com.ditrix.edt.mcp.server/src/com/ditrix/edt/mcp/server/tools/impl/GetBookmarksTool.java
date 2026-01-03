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
 * Tool to get bookmarks from the workspace.
 */
public class GetBookmarksTool implements IMcpTool
{
    public static final String NAME = "get_bookmarks"; //$NON-NLS-1$
    
    private static final String BOOKMARK_MARKER_TYPE = "org.eclipse.core.resources.bookmark"; //$NON-NLS-1$
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
        return "Get bookmarks from the workspace. " + //$NON-NLS-1$
               "Returns bookmark message, file path, and line number."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"projectName\": {\"type\": \"string\", \"description\": \"Filter by project name (optional)\"}," + //$NON-NLS-1$
               "\"filePath\": {\"type\": \"string\", \"description\": \"Filter by file path substring (optional)\"}," + //$NON-NLS-1$
               "\"limit\": {\"type\": \"integer\", \"description\": \"Maximum number of results (default: 100, max: 1000)\"}" + //$NON-NLS-1$
               "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
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
        
        return getBookmarks(projectName, filePath, limit);
    }
    
    /**
     * Gets bookmarks with filters.
     * 
     * @param projectName filter by project name (null for all)
     * @param filePath filter by file path substring
     * @param limit maximum number of results
     * @return JSON string with bookmark details
     */
    public static String getBookmarks(String projectName, String filePath, int limit)
    {
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            List<BookmarkInfo> bookmarks = new ArrayList<>();
            
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
            
            // Collect bookmarks from projects
            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }
                
                try
                {
                    IMarker[] markers = project.findMarkers(BOOKMARK_MARKER_TYPE, true, IResource.DEPTH_INFINITE);
                    
                    for (IMarker marker : markers)
                    {
                        if (bookmarks.size() >= limit)
                        {
                            break;
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
                        
                        // Create bookmark info
                        BookmarkInfo bookmark = new BookmarkInfo();
                        bookmark.project = project.getName();
                        bookmark.message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                        bookmark.path = resourcePathStr;
                        bookmark.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                        bookmark.charStart = marker.getAttribute(IMarker.CHAR_START, -1);
                        bookmark.charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
                        
                        bookmarks.add(bookmark);
                    }
                }
                catch (CoreException e)
                {
                    Activator.logError("Failed to get bookmarks for: " + project.getName(), e); //$NON-NLS-1$
                }
                
                if (bookmarks.size() >= limit)
                {
                    break;
                }
            }
            
            // Build JSON response
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"count\": ").append(bookmarks.size()).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"limit\": ").append(limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"hasMore\": ").append(bookmarks.size() >= limit).append(","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"bookmarks\": ["); //$NON-NLS-1$
            
            boolean first = true;
            for (BookmarkInfo bookmark : bookmarks)
            {
                if (!first)
                {
                    json.append(","); //$NON-NLS-1$
                }
                first = false;
                
                json.append("{"); //$NON-NLS-1$
                json.append("\"project\": \"").append(JsonUtils.escapeJson(bookmark.project)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"message\": \"").append(JsonUtils.escapeJson(bookmark.message)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"path\": \"").append(JsonUtils.escapeJson(bookmark.path)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
                json.append("\"line\": ").append(bookmark.line); //$NON-NLS-1$
                
                if (bookmark.charStart >= 0)
                {
                    json.append(",\"charStart\": ").append(bookmark.charStart); //$NON-NLS-1$
                }
                if (bookmark.charEnd >= 0)
                {
                    json.append(",\"charEnd\": ").append(bookmark.charEnd); //$NON-NLS-1$
                }
                
                json.append("}"); //$NON-NLS-1$
            }
            
            json.append("]"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting bookmarks", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
    }
    
    /**
     * Helper class to store bookmark info.
     */
    private static class BookmarkInfo
    {
        String project;
        String message;
        String path;
        int line;
        int charStart;
        int charEnd;
    }
}
