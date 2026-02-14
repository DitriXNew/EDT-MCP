/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
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
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;

/**
 * Tool to get bookmarks from the workspace.
 */
public class GetBookmarksTool implements IMcpTool
{
    public static final String NAME = "get_bookmarks"; //$NON-NLS-1$
    
    private static final String BOOKMARK_MARKER_TYPE = "org.eclipse.core.resources.bookmark"; //$NON-NLS-1$
    
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
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Filter by file path substring (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String limitStr = JsonUtils.extractStringArgument(params, "limit"); //$NON-NLS-1$
        
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
        
        return getBookmarks(projectName, filePath, limit);
    }
    
    /**
     * Gets bookmarks with filters.
     * 
     * @param projectName filter by project name (null for all)
     * @param filePath filter by file path substring
     * @param limit maximum number of results
     * @return Markdown string with bookmark details
     */
    public static String getBookmarks(String projectName, String filePath, int limit)
    {
        StringBuilder md = new StringBuilder();
        
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
                    return "**Error:** Project not found: " + projectName; //$NON-NLS-1$
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
            
            // Build Markdown response
            md.append("## Bookmarks\n\n"); //$NON-NLS-1$
            md.append("**Found:** ").append(bookmarks.size()).append(" bookmarks"); //$NON-NLS-1$ //$NON-NLS-2$
            if (bookmarks.size() >= limit)
            {
                md.append(" (limit: ").append(limit).append(", more available)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            md.append("\n\n"); //$NON-NLS-1$
            
            if (bookmarks.isEmpty())
            {
                md.append("*No bookmarks found.*\n"); //$NON-NLS-1$
            }
            else
            {
                // Table header
                md.append("| Project | Message | Path | Line |\n"); //$NON-NLS-1$
                md.append("|---------|---------|------|------|\n"); //$NON-NLS-1$
                
                for (BookmarkInfo bookmark : bookmarks)
                {
                    md.append("| "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(bookmark.project));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(bookmark.message));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(MarkdownUtils.escapeForTable(bookmark.path));
                    md.append(" | "); //$NON-NLS-1$
                    md.append(bookmark.line);
                    md.append(" |\n"); //$NON-NLS-1$
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting bookmarks", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
        
        return md.toString();
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
    }
}
