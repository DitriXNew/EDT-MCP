// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Shared dispatch and BM boundary for workspace reads; UI linkage stays in the windowed branch. */
public final class ReadToolExecution
{
    private ReadToolExecution() { }

    public static void dispatch(Runnable work)
    {
        dispatch(HostKind.current(), work, Windowed::dispatch);
    }

    static void dispatch(HostKind kind, Runnable work, Consumer<Runnable> windowed)
    {
        if (kind == HostKind.HEADLESS) { work.run(); }
        else { windowed.accept(work); }
    }

    public static String read(String projectName, String taskName, Supplier<String> work)
    {
        ProjectContext context = ProjectContext.of(projectName);
        if (!context.exists()) { return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson(); }
        return read(context.project(), taskName, work);
    }

    public static String read(IProject project, String taskName, Supplier<String> work)
    {
        return readModel(project, taskName, work);
    }

    public static <T> T readModel(IProject project, String taskName, Supplier<T> work)
    {
        return readModel(project, taskName, model -> work.get());
    }

    public static <T> T readModel(IProject project, String taskName, Function<IBmModel, T> work)
    {
        var manager = Activator.getDefault().getBmModelManager();
        var model = manager == null ? null : manager.getModel(project);
        if (model == null)
        {
            throw Refusals.state("BM model is unavailable for project '" + project.getName()
                + "'. Check list_projects, wait for project loading and retry.");
        }
        return BmTransactions.read(model, taskName, (tx, monitor) -> work.apply(model));
    }

    private static final class Windowed
    {
        private static void dispatch(Runnable work)
        {
            org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(work);
        }
    }
}
