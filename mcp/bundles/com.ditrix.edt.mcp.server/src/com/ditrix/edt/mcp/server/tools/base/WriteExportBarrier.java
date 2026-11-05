/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.base;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.BuildUtils;
import com.ditrix.edt.mcp.server.utils.BuildUtils.DiskExportState;

/** Shared bounded export drain; call only after leaving the UI thread and BM transaction. */
public final class WriteExportBarrier
{
    public static final long DEADLINE_MS = 60_000L;
    private static final long MIN_USEFUL_WAIT_MS = 1_000L;

    @FunctionalInterface
    public interface Environment
    {
        DiskExportState waitForDiskExport(String projectName, long timeoutMs);
    }

    public static final Environment PLATFORM = (projectName, timeoutMs) -> {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        return project.exists() ? BuildUtils.waitForDiskExport(project, timeoutMs)
            : DiskExportState.UNOBSERVABLE;
    };

    public record Outcome(boolean drained, String pendingProject) { }

    private WriteExportBarrier() { }

    public static Outcome await(WriteScope scope, long budgetMs, Environment environment)
    {
        return await(scope.verdict(java.util.List.of()), budgetMs, environment);
    }

    public static Outcome await(WriteScope.Verdict verdict, long budgetMs, Environment environment)
    {
        long deadline = System.currentTimeMillis() + budgetMs;
        boolean drained = true;
        for (String project : verdict.written())
        {
            DiskExportState state = waitWithin(deadline, project, environment);
            if (state == DiskExportState.PENDING) return new Outcome(false, project);
            drained &= state == DiskExportState.DRAINED;
        }
        for (String project : verdict.cascaded())
        {
            drained &= waitWithin(deadline, project, environment) == DiskExportState.DRAINED;
        }
        return new Outcome(drained, null);
    }

    private static DiskExportState waitWithin(long deadline, String project, Environment environment)
    {
        long remainingMs = deadline - System.currentTimeMillis();
        if (project == null || project.isEmpty() || remainingMs < MIN_USEFUL_WAIT_MS)
            return DiskExportState.UNOBSERVABLE;
        return environment.waitForDiskExport(project, remainingMs);
    }

    public static String notConfirmed(String projectName)
    {
        return notConfirmedResult(projectName).toJson();
    }

    public static String notConfirmedAfterMutation(String projectName, DiskExportState state)
    {
        return notConfirmedResult(projectName).put("mutationCommitted", true)
            .put("exportState", state.name()).toJson();
    }

    private static ToolResult notConfirmedResult(String projectName)
    {
        return ToolResult.error("The operation completed, but its export to disk was not confirmed "
            + "within a " + (DEADLINE_MS / 1000) + "s budget, so the files of project '"
            + projectName + "' may not be consistent on disk: an object's own .mdo can already be "
            + "written or deleted while Configuration.mdo still holds the old collection. Nothing was "
            + "rolled back. Do not commit the working tree in this state. Check the project with "
            + "list_projects, wait for it to report ready, then use resync_to_disk to write out what "
            + "is still pending.");
    }
}
