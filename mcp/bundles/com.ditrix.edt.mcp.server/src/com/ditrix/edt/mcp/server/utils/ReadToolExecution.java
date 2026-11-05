// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Shared host dispatch and BM read boundary; UI linkage stays in the windowed branch. */
public final class ReadToolExecution
{
    private static final ThreadLocal<Map<IProject, IBmModel>> READ_SCOPES = new ThreadLocal<>();
    private ReadToolExecution() { }

    public static void dispatch(Runnable work)
    {
        dispatch(HostKind.current(), work, Windowed::dispatch);
    }

    public static Consumer<Runnable> workbenchExecutor()
    {
        return HostKind.current() == HostKind.HEADLESS ? Runnable::run : Windowed.workbenchExecutor();
    }

    public static void dispatchOnDefaultDisplay(Runnable work)
    {
        dispatch(HostKind.current(), work, Windowed::dispatchOnDefaultDisplay);
    }

    public static void dispatchOnCurrentOrDefaultDisplay(Runnable work)
    {
        dispatch(HostKind.current(), work, Windowed::dispatchOnCurrentOrDefaultDisplay);
    }

    public static Consumer<Runnable> defaultDisplayExecutor()
    {
        return HostKind.current() == HostKind.HEADLESS ? Runnable::run : Windowed.defaultDisplayExecutor();
    }

    public static boolean defaultDisplayAvailable()
    {
        return HostKind.current() == HostKind.HEADLESS || Windowed.defaultDisplayAvailable();
    }

    /** Renderers stay windowed; direct tool calls must not create an unpumped SWT display. */
    public static boolean dispatchRendering(Runnable work)
    {
        return HostKind.current() != HostKind.HEADLESS && Windowed.dispatchRendering(work);
    }

    /** Returns true for inline completion, false when the windowed work was queued. */
    public static boolean dispatchAsync(Runnable work)
    {
        if (HostKind.current() == HostKind.HEADLESS) { work.run(); return true; }
        return Windowed.dispatchAsync(work);
    }

    static void dispatch(HostKind kind, Runnable work, Consumer<Runnable> windowed)
    {
        if (kind == HostKind.HEADLESS) { work.run(); }
        else { windowed.accept(work); }
    }

    /** Worker reads retain a BM boundary; windowed dispatch keeps the existing transaction path. */
    public static void dispatchRead(String projectName, String taskName, Runnable work)
    {
        dispatch(() -> readOnHost(projectName, taskName, () -> { work.run(); return null; }));
    }

    public static <T> T readOnHost(String projectName, String taskName, Supplier<T> work)
    {
        if (HostKind.current() == HostKind.HEADLESS)
        {
            ProjectContext context = ProjectContext.of(projectName);
            if (context.exists()) return readModel(context.project(), taskName, work);
        }
        return work.get();
    }

    public static <T> T readOnHost(IProject project, String taskName, Supplier<T> work)
    {
        return HostKind.current() == HostKind.HEADLESS && project != null && project.exists()
            ? readModel(project, taskName, work) : work.get();
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
        boolean headless = HostKind.current() == HostKind.HEADLESS;
        Map<IProject, IBmModel> active = headless ? READ_SCOPES.get() : null;
        IBmModel captured = active == null ? null : active.get(project);
        if (captured != null) return work.apply(captured);
        var manager = Activator.getDefault().getBmModelManager();
        var model = manager == null ? null : manager.getModel(project);
        if (model == null)
        {
            throw Refusals.state("BM model is unavailable for project '" + project.getName()
                + "'. Check list_projects, wait for project loading and retry.");
        }
        return BmTransactions.read(model, taskName, (tx, monitor) -> {
            if (!headless) return work.apply(model);
            Map<IProject, IBmModel> scope = READ_SCOPES.get();
            if (scope == null)
            {
                scope = new HashMap<>();
                READ_SCOPES.set(scope);
            }
            scope.put(project, model);
            try { return work.apply(model); }
            finally
            {
                scope.remove(project);
                if (scope.isEmpty()) READ_SCOPES.remove();
            }
        });
    }

    private static final class Windowed
    {
        private static Consumer<Runnable> workbenchExecutor()
        {
            var display = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
            return display::syncExec;
        }

        private static void dispatch(Runnable work)
        {
            org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(work);
        }

        private static void dispatchOnDefaultDisplay(Runnable work)
        {
            var display = org.eclipse.swt.widgets.Display.getDefault();
            if (display.getThread() == Thread.currentThread()) work.run();
            else display.syncExec(work);
        }

        private static void dispatchOnCurrentOrDefaultDisplay(Runnable work)
        {
            if (org.eclipse.swt.widgets.Display.getCurrent() != null) work.run();
            else org.eclipse.swt.widgets.Display.getDefault().syncExec(work);
        }

        private static Consumer<Runnable> defaultDisplayExecutor()
        {
            var display = org.eclipse.swt.widgets.Display.getDefault();
            return display == null || display.isDisposed() ? null : display::syncExec;
        }

        private static boolean defaultDisplayAvailable()
        {
            var display = org.eclipse.swt.widgets.Display.getDefault();
            return display != null && !display.isDisposed();
        }

        private static boolean dispatchRendering(Runnable work)
        {
            var display = org.eclipse.swt.widgets.Display.getDefault();
            if (display == null || display.isDisposed()) return false;
            display.syncExec(work);
            return true;
        }

        private static boolean dispatchAsync(Runnable work)
        {
            var display = org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
            if (display.getThread() == Thread.currentThread()) { work.run(); return true; }
            display.asyncExec(work);
            return false;
        }
    }
}
