/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.base;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.utils.ProjectContext;
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
        IProject project = ProjectContext.of(projectName).project();
        return project != null && project.exists() ? BuildUtils.waitForDiskExport(project, timeoutMs)
            : DiskExportState.UNOBSERVABLE;
    };

    /**
     * {@link #PLATFORM} for a caller that waits less than {@link #DEADLINE_MS}: an export wait that never
     * returns still holds its project's slot as long as a full-deadline wait would, so the shorter caller
     * deadline never lets a wedged export pipeline collect jobs faster.
     */
    public static final Environment PLATFORM_FULL_HOLD = (projectName, timeoutMs) -> {
        IProject project = ProjectContext.of(projectName).project();
        return project != null && project.exists() ? BuildUtils.waitForDiskExport(project, timeoutMs, DEADLINE_MS)
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
        // Monotonic: a wall-clock change neither grants nor takes export time.
        long deadline = System.nanoTime() / 1_000_000L + budgetMs;
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
        long remainingMs = deadline - System.nanoTime() / 1_000_000L;
        if (project == null || project.isEmpty() || remainingMs < MIN_USEFUL_WAIT_MS)
            return DiskExportState.UNOBSERVABLE;
        return environment.waitForDiskExport(project, remainingMs);
    }

    public static String notConfirmed(String projectName)
    {
        return notConfirmedResult(projectName).toJson();
    }

    /** {@link #notConfirmed(String)} for a caller that awaited the export within its own {@code budgetMs}. */
    public static String notConfirmed(String projectName, long budgetMs)
    {
        return notConfirmedResult(projectName, budgetMs).toJson();
    }

    public static String notConfirmedAfterMutation(String projectName, DiskExportState state)
    {
        return notConfirmedResult(projectName).put("mutationCommitted", true)
            .put("exportState", state.name()).toJson();
    }

    private static ToolResult notConfirmedResult(String projectName)
    {
        return notConfirmedResult(projectName, DEADLINE_MS);
    }

    private static ToolResult notConfirmedResult(String projectName, long budgetMs)
    {
        return ToolResult.error("The operation completed, but its export to disk was not confirmed "
            + "within a " + Math.max(1L, budgetMs / 1000) + "s budget, so the files of project '"
            + projectName + "' may not be consistent on disk: an object's own .mdo can already be "
            + "written or deleted while Configuration.mdo still holds the old collection. Nothing was "
            + "rolled back. Do not commit the working tree in this state. Check the project with "
            + "list_projects, wait for it to report ready, then use resync_to_disk to write out what "
            + "is still pending.");
    }
}
