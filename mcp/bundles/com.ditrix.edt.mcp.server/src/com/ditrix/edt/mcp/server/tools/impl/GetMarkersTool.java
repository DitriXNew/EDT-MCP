/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ToolParameterSettings;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.Pagination;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to list workspace markers - bookmarks and/or task markers (TODO / FIXME /
 * XXX / HACK) - in a single report.
 *
 * <p>Consolidates the former {@code get_bookmarks} and {@code get_tasks} tools,
 * which were mirror scans of the same Eclipse workspace markers (identical
 * {@code projectName}/{@code filePath}/{@code limit} filters over
 * {@link IProject#findMarkers}). The {@code markerKind} selector chooses which
 * marker families to scan (bookmark, task, or both); {@code priority} sub-filters
 * the task family only (a bookmark has no priority).</p>
 */
public class GetMarkersTool implements IMcpTool
{
    public static final String NAME = "get_markers"; //$NON-NLS-1$

    /** Closed set of {@code markerKind} selector values. */
    static final List<String> MARKER_KINDS = Arrays.asList("bookmark", "task"); //$NON-NLS-1$ //$NON-NLS-2$

    /** Closed set of priority filter values accepted by the {@code priority} parameter. */
    static final List<String> PRIORITY_VALUES = Arrays.asList("high", "normal", "low"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String BOOKMARK_MARKER_TYPE = "org.eclipse.core.resources.bookmark"; //$NON-NLS-1$
    private static final String TASK_MARKER_TYPE = "org.eclipse.core.resources.taskmarker"; //$NON-NLS-1$
    private static final String XTEXT_TASK_MARKER_TYPE = "org.eclipse.xtext.ui.task"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List workspace markers: bookmarks and/or task markers (TODO, FIXME, XXX, HACK). " //$NON-NLS-1$
            + "Filter by markerKind (bookmark | task; omit to list both), projectName, filePath " //$NON-NLS-1$
            + "substring, and - for task markers only - priority. Returns a markdown table of " //$NON-NLS-1$
            + "Kind, Type, Priority, Message, Path and Line."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Filter by project name (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("filePath", "Filter by file path substring (optional)") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("markerKind", //$NON-NLS-1$
                "Which markers to list: 'bookmark' (manual navigation bookmarks) or 'task' " //$NON-NLS-1$
                + "(TODO/FIXME/XXX/HACK code markers). Omit to list both.", //$NON-NLS-1$
                "bookmark", "task") //$NON-NLS-1$ //$NON-NLS-2$
            .enumProperty("priority", //$NON-NLS-1$
                "Filter task markers by priority (optional). Applies to task markers only; " //$NON-NLS-1$
                + "bookmarks have no priority and are unaffected. Cannot be combined with " //$NON-NLS-1$
                + "markerKind=bookmark.", //$NON-NLS-1$
                "high", "normal", "low") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .integerProperty("limit", "Maximum number of results (default: 100, max: 1000)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String filePath = JsonUtils.extractStringArgument(params, "filePath"); //$NON-NLS-1$
        String markerKind = JsonUtils.extractStringArgument(params, "markerKind"); //$NON-NLS-1$
        String priority = JsonUtils.extractStringArgument(params, "priority"); //$NON-NLS-1$

        boolean hasKind = markerKind != null && !markerKind.isEmpty();
        boolean hasPriority = priority != null && !priority.isEmpty();

        // Validate the closed-vocabulary parameters up front (before any live
        // workspace access) so the validation branches are headless-testable.
        if (hasKind && !MARKER_KINDS.contains(markerKind.toLowerCase()))
        {
            return ToolResult.error("Invalid markerKind: '" + markerKind //$NON-NLS-1$
                + "'. Must be one of: " + String.join(", ", MARKER_KINDS)).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (hasPriority && !PRIORITY_VALUES.contains(priority.toLowerCase()))
        {
            return ToolResult.error("Invalid priority: '" + priority //$NON-NLS-1$
                + "'. Must be one of: " + String.join(", ", PRIORITY_VALUES)).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // priority sub-filters the task family only; combining it with
        // markerKind=bookmark selects no rows it could apply to, so reject the
        // contradiction with an actionable message instead of silently emptying.
        if (hasPriority && hasKind && "bookmark".equals(markerKind.toLowerCase())) //$NON-NLS-1$
        {
            return ToolResult.error("priority filter applies to task markers only; " //$NON-NLS-1$
                + "markerKind=bookmark selects none. Remove priority, or use markerKind=task " //$NON-NLS-1$
                + "(or omit markerKind to list both).").toJson(); //$NON-NLS-1$
        }

        boolean includeBookmarks = !hasKind || "bookmark".equals(markerKind.toLowerCase()); //$NON-NLS-1$
        boolean includeTasks = !hasKind || "task".equals(markerKind.toLowerCase()); //$NON-NLS-1$

        int defaultLimit = ToolParameterSettings.getInstance()
            .getParameterValue(NAME, "limit", 100); //$NON-NLS-1$

        int limit = JsonUtils.extractIntArgument(params, "limit", defaultLimit); //$NON-NLS-1$
        limit = Pagination.clampLimit(limit, 1000);

        return getMarkers(projectName, filePath, includeBookmarks, includeTasks, priority, limit);
    }

    /**
     * Collects the selected markers across the resolved projects and renders them
     * as a markdown report.
     *
     * @param projectName filter by project name (null/empty for all open projects)
     * @param filePath case-insensitive path-substring filter (null/empty = no filter)
     * @param includeBookmarks whether to scan bookmark markers
     * @param includeTasks whether to scan task markers
     * @param priority task priority filter (null/empty = no filter); ignored for bookmarks
     * @param limit maximum number of results (already clamped)
     * @return Markdown string, or a {@link ToolResult#error} JSON on a bad project
     */
    public static String getMarkers(String projectName, String filePath, boolean includeBookmarks,
        boolean includeTasks, String priority, int limit)
    {
        StringBuilder md = new StringBuilder();

        try
        {
            List<MarkerRow> rows = new ArrayList<>();
            // Dedup set shared across the two task marker types: a BSL TODO/FIXME
            // surfaces under both the base task marker and the Xtext task marker
            // subtype and must be counted once.
            Set<String> seenTasks = new HashSet<>();

            Integer priorityFilter = priorityToInt(priority);

            IProject[] projects;
            if (projectName != null && !projectName.isEmpty())
            {
                ProjectContext ctx = ProjectContext.of(projectName);
                if (!ctx.exists())
                {
                    return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
                }
                projects = new IProject[] { ctx.project() };
            }
            else
            {
                projects = ProjectContext.allProjects();
            }

            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }

                if (includeBookmarks && rows.size() < limit)
                {
                    collectBookmarks(project, rows, filePath, limit);
                }
                if (includeTasks && rows.size() < limit)
                {
                    collectTasks(project, TASK_MARKER_TYPE, rows, seenTasks, filePath, priorityFilter, limit);
                    if (rows.size() < limit)
                    {
                        collectTasks(project, XTEXT_TASK_MARKER_TYPE, rows, seenTasks, filePath, priorityFilter, limit);
                    }
                }

                if (rows.size() >= limit)
                {
                    break;
                }
            }

            md.append("## Markers\n\n"); //$NON-NLS-1$
            md.append("**Found:** ").append(rows.size()).append(" markers"); //$NON-NLS-1$ //$NON-NLS-2$
            if (rows.size() >= limit)
            {
                md.append(Pagination.limitReachedNotice(limit));
            }
            md.append("\n\n"); //$NON-NLS-1$

            if (rows.isEmpty())
            {
                md.append("*No markers found.*\n"); //$NON-NLS-1$
            }
            else
            {
                // Table header (cells escaped by MarkdownUtils.tableRow). The Path
                // already carries the project name as its first segment, so there
                // is no separate Project column.
                md.append(MarkdownUtils.tableHeader("Kind", "Type", "Priority", "Message", "Path", "Line")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$

                for (MarkerRow row : rows)
                {
                    md.append(MarkdownUtils.tableRow(
                        row.kind,
                        row.type,
                        row.priority,
                        row.message,
                        row.path,
                        String.valueOf(row.line)));
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("Error getting markers", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }

        return md.toString();
    }

    /**
     * Collects bookmark markers from a project into {@code rows}.
     */
    private static void collectBookmarks(IProject project, List<MarkerRow> rows, String filePath, int limit)
    {
        try
        {
            IMarker[] markers = project.findMarkers(BOOKMARK_MARKER_TYPE, true, IResource.DEPTH_INFINITE);

            for (IMarker marker : markers)
            {
                if (rows.size() >= limit)
                {
                    break;
                }

                String resourcePathStr = resourcePath(marker);
                if (!matchesFilePath(resourcePathStr, filePath))
                {
                    continue;
                }

                MarkerRow row = new MarkerRow();
                row.kind = "bookmark"; //$NON-NLS-1$
                row.type = "BOOKMARK"; //$NON-NLS-1$
                // A bookmark has no priority; render a placeholder so the column reads clearly.
                row.priority = "-"; //$NON-NLS-1$
                row.message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                row.path = resourcePathStr;
                row.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                rows.add(row);
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to get bookmarks for: " + project.getName(), e); //$NON-NLS-1$
        }
    }

    /**
     * Collects task markers of a specific type from a project into {@code rows}.
     * <p>
     * The {@code seen} set is shared across the calls for the different task
     * marker types so that a task surfacing under both (e.g. a BSL TODO that
     * matches both the base task marker and the Xtext task marker subtype) is
     * added once, while any task unique to either type is preserved.
     */
    private static void collectTasks(IProject project, String markerType, List<MarkerRow> rows,
        Set<String> seen, String filePath, Integer priorityFilter, int limit)
    {
        try
        {
            IMarker[] markers = project.findMarkers(markerType, true, IResource.DEPTH_INFINITE);

            for (IMarker marker : markers)
            {
                if (rows.size() >= limit)
                {
                    break;
                }

                int markerPriority = marker.getAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL);
                if (priorityFilter != null && markerPriority != priorityFilter)
                {
                    continue;
                }

                String resourcePathStr = resourcePath(marker);
                if (!matchesFilePath(resourcePathStr, filePath))
                {
                    continue;
                }

                MarkerRow row = new MarkerRow();
                row.kind = "task"; //$NON-NLS-1$
                row.message = marker.getAttribute(IMarker.MESSAGE, ""); //$NON-NLS-1$
                row.path = resourcePathStr;
                row.line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                row.priority = getPriorityString(markerPriority);
                row.type = getTaskType(row.message);

                // Skip duplicates across the two task marker types.
                if (!seen.add(taskKey(row.path, row.line, row.message, row.priority)))
                {
                    continue;
                }

                rows.add(row);
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to get tasks for: " + project.getName(), e); //$NON-NLS-1$
        }
    }

    /** Returns the marker resource's full path as a string, never null. */
    private static String resourcePath(IMarker marker)
    {
        IResource resource = marker.getResource();
        IPath resourcePath = resource.getFullPath();
        return resourcePath != null ? resourcePath.toString() : ""; //$NON-NLS-1$
    }

    /** Case-insensitive path-substring filter; an empty/null filter matches everything. */
    private static boolean matchesFilePath(String resourcePathStr, String filePath)
    {
        if (filePath == null || filePath.isEmpty())
        {
            return true;
        }
        return resourcePathStr.toLowerCase().contains(filePath.toLowerCase());
    }

    /** Maps a priority filter string to the {@link IMarker} priority constant, or null. */
    private static Integer priorityToInt(String priority)
    {
        if (priority == null || priority.isEmpty())
        {
            return null;
        }
        switch (priority.toLowerCase())
        {
            case "high": //$NON-NLS-1$
                return IMarker.PRIORITY_HIGH;
            case "normal": //$NON-NLS-1$
                return IMarker.PRIORITY_NORMAL;
            case "low": //$NON-NLS-1$
                return IMarker.PRIORITY_LOW;
            default:
                return null;
        }
    }

    /**
     * Builds a stable dedup key for a task from its resource path, line number,
     * message and priority. Pure and headless-unit-testable: identical inputs
     * yield the same key, and a change in any component yields a different key.
     * A null path or message is treated as an empty string.
     *
     * @param path the resource path (may be null)
     * @param line the line number
     * @param message the task message (may be null)
     * @param priority the priority string (may be null)
     * @return a non-null key uniquely identifying the task
     */
    static String taskKey(String path, int line, String message, String priority)
    {
        String safePath = path != null ? path : ""; //$NON-NLS-1$
        String safeMessage = message != null ? message : ""; //$NON-NLS-1$
        String safePriority = priority != null ? priority : ""; //$NON-NLS-1$
        // Use a separator that cannot appear in a line number; the textual
        // fields are compared as-is, so collisions across fields are avoided
        // by the fixed field order plus the separator.
        return safePath + "\n" + line + "\n" + safeMessage + "\n" + safePriority; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Converts a priority integer to its filter string. */
    private static String getPriorityString(int priority)
    {
        switch (priority)
        {
            case IMarker.PRIORITY_HIGH:
                return "high"; //$NON-NLS-1$
            case IMarker.PRIORITY_NORMAL:
                return "normal"; //$NON-NLS-1$
            case IMarker.PRIORITY_LOW:
            default:
                return "low"; //$NON-NLS-1$
        }
    }

    /** Extracts the task type (TODO, FIXME, XXX, HACK) from the marker message. */
    private static String getTaskType(String message)
    {
        if (message == null || message.isEmpty())
        {
            return "TASK"; //$NON-NLS-1$
        }

        String upperMessage = message.toUpperCase();
        if (upperMessage.contains("TODO")) //$NON-NLS-1$
        {
            return "TODO"; //$NON-NLS-1$
        }
        if (upperMessage.contains("FIXME")) //$NON-NLS-1$
        {
            return "FIXME"; //$NON-NLS-1$
        }
        if (upperMessage.contains("XXX")) //$NON-NLS-1$
        {
            return "XXX"; //$NON-NLS-1$
        }
        if (upperMessage.contains("HACK")) //$NON-NLS-1$
        {
            return "HACK"; //$NON-NLS-1$
        }
        return "TASK"; //$NON-NLS-1$
    }

    /** Helper class to store one marker row. */
    private static class MarkerRow
    {
        String kind;
        String type;
        String priority;
        String message;
        String path;
        int line;
    }
}
