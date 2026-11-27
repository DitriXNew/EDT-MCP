// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 DitriX
package com.ditrix.edt.mcp.server.utils;

import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.bm.integration.IBmModel;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Shared host dispatch and BM read boundary; UI linkage stays in the windowed branch. */
public final class ReadToolExecution
{
    /** Settle budget before the one retry of a read that hit an EDT context restart. */
    static final long RESTART_SETTLE_TIMEOUT_MS = 10_000L;

    /**
     * Minimum pause before that retry: the settle may return at once when the project snapshot
     * already reads ready, yet EDT may still be wiring the restarted context.
     */
    static final long RESTART_RETRY_PAUSE_MS = 750L;

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

    /**
     * Runs a tool read on the host (windowed: UI thread) and maps its failure. A failure that
     * {@link PlatformFailures#isContextRestartArtifact} recognises is retried once after a bounded
     * wait for the project and its dependents (off the UI thread only); a second one answers the
     * retryable {@link #restartingError}. Every other failure is logged through
     * {@link Refusals#log} and mapped by the tool's own {@code failureJson}, exactly as before.
     *
     * @param projectName the project the read targets (used for the settle and the message)
     * @param logContext the {@link Refusals#log} context, e.g. "Error getting metadata objects"
     * @param read the read itself; it opens its own BM boundary
     * @param failureJson maps any other failure to the tool's error JSON
     * @return the read's JSON, or the mapped error
     */
    public static String dispatchReadTool(String projectName, String logContext,
        Supplier<String> read, Function<Exception, String> failureJson)
    {
        return dispatchReadTool(projectName, projectName, logContext, read, failureJson);
    }

    /**
     * As {@link #dispatchReadTool(String, String, Supplier, Function)}, for a read that can cross
     * into another project (an extension's adopted module is read from its base project): the
     * restart artifact may come from that other project, so the settle waits for
     * {@code settleProjectName} instead. The settle treats it as a base and includes its
     * dependents, so passing the base project also covers the extension. The retryable answer
     * still names {@code projectName}: the advice must target the project the caller asked about.
     *
     * @param projectName the project the read was requested for
     * @param settleProjectName the project to settle (its scope must cover {@code projectName})
     *            (falls back to {@code projectName} when {@code null} or empty)
     * @param logContext the {@link Refusals#log} context
     * @param read the read itself; it opens its own BM boundary
     * @param failureJson maps any other failure to the tool's error JSON
     * @return the read's JSON, or the mapped error
     */
    public static String dispatchReadTool(String projectName, String settleProjectName,
        String logContext, Supplier<String> read, Function<Exception, String> failureJson)
    {
        // Captured here, on the executor thread: the probe must stay valid inside the runnable
        // that the UI thread runs later, where the thread-local lookup finds no call.
        BooleanSupplier responseDelivered = ExecutingToolCall.capture();
        return dispatchReadTool(projectName, settleProjectName, logContext, read, failureJson,
            work -> dispatch(work),
            (name, budgetMs) -> ProjectStateChecker.awaitSearchScopeReady(name, budgetMs)
                .getNotReadyProjectNames(),
            () -> HostKind.current() == HostKind.HEADLESS || Windowed.notOnUiThread(),
            InterruptiblePause::pause, responseDelivered);
    }

    /**
     * Seam. {@code host} is how the read is dispatched, {@code settle} maps (projectName,
     * budgetMs) to the not-ready project names, {@code mayWait} is false on the UI thread,
     * {@code pause} sleeps the given milliseconds and returns false when interrupted,
     * {@code responseDelivered} tells whether the executing call was already answered.
     */
    static String dispatchReadTool(String projectName, String logContext, Supplier<String> read,
        Function<Exception, String> failureJson, Consumer<Runnable> host,
        BiFunction<String, Long, List<String>> settle, BooleanSupplier mayWait,
        LongPredicate pause, BooleanSupplier responseDelivered)
    {
        return dispatchReadTool(projectName, projectName, logContext, read, failureJson, host,
            settle, mayWait, pause, responseDelivered);
    }

    /**
     * Seam with a separate project to settle; see the public overload. The retryable answer
     * always names the REQUESTED project: the settle scope may be wider (a base whose scope
     * covers the extension), but the advice must target the project the caller asked about.
     */
    static String dispatchReadTool(String requestedProject, String settleProjectName,
        String logContext, Supplier<String> read, Function<Exception, String> failureJson,
        Consumer<Runnable> host, BiFunction<String, Long, List<String>> settle,
        BooleanSupplier mayWait, LongPredicate pause, BooleanSupplier responseDelivered)
    {
        String settleProject = settleProjectName == null || settleProjectName.isEmpty()
            ? requestedProject : settleProjectName;
        ReadAttempt first = attemptRead(logContext, read, failureJson, host, null);
        if (first.restartArtifact == null)
        {
            return first.json;
        }
        if (!mayWait.getAsBoolean())
        {
            return restartingError(requestedProject, first.restartArtifact);
        }
        // Outside the host dispatch and any BM transaction, bounded: the retry gives EDT the
        // chance to finish wiring the restarted context. Pause first (the settle can return at
        // once), interruptibly; a caller that interrupted us gets the retryable error, no retry.
        if (!pause.test(RESTART_RETRY_PAUSE_MS))
        {
            return restartingError(requestedProject, first.restartArtifact);
        }
        settle.apply(settleProject, RESTART_SETTLE_TIMEOUT_MS);
        // The wait took time: a caller that was cancelled or already answered meanwhile gets the
        // retryable error, not a second read.
        if (Thread.currentThread().isInterrupted() || responseDelivered.getAsBoolean())
        {
            return restartingError(requestedProject, first.restartArtifact);
        }
        // The host may queue the runnable behind a busy UI thread, so the delivered check is
        // repeated inside it, right before the read (the captured probe works on any thread).
        ReadAttempt second = attemptRead(logContext, read, failureJson, host, responseDelivered);
        if (second.skipped)
        {
            return restartingError(requestedProject, first.restartArtifact);
        }
        return second.restartArtifact == null
            ? second.json : restartingError(requestedProject, second.restartArtifact);
    }

    /** The retryable answer for a read that EDT failed while restarting the context. */
    public static String restartingError(String projectName, Throwable failure)
    {
        return ToolResult.error("EDT was restarting the context of project '" + projectName //$NON-NLS-1$
            + "' and failed the read inside EDT (" + PlatformFailures.describe(failure) //$NON-NLS-1$
            + "). The request itself is fine: wait until list_projects reads 'ready' for '" //$NON-NLS-1$
            + projectName + "' and retry. If it keeps failing once 'ready', run clean_project on '" //$NON-NLS-1$
            + projectName + "'.").toJson(); //$NON-NLS-1$
    }

    /** Outcome of one read attempt: the JSON, or the restart artifact that needs a retry. */
    private static final class ReadAttempt
    {
        volatile String json;
        volatile Exception restartArtifact;
        volatile boolean skipped;
    }

    private static ReadAttempt attemptRead(String logContext, Supplier<String> read,
        Function<Exception, String> failureJson, Consumer<Runnable> host,
        BooleanSupplier skipWhen)
    {
        ReadAttempt attempt = new ReadAttempt();
        host.accept(() -> {
            // Only the probe is consulted here: this may be the UI thread, whose interrupt
            // flag is not the caller's.
            if (skipWhen != null && skipWhen.getAsBoolean())
            {
                attempt.skipped = true;
                return;
            }
            try
            {
                attempt.json = read.get();
            }
            catch (Exception e)
            {
                Refusals.log(logContext, e);
                if (PlatformFailures.isContextRestartArtifact(e))
                {
                    attempt.restartArtifact = e;
                }
                else
                {
                    attempt.json = failureJson.apply(e);
                }
            }
        });
        return attempt;
    }

    /** Dispatches reads inside a BM boundary on either host. */
    public static void dispatchRead(String projectName, String taskName, Runnable work)
    {
        dispatch(() -> readOnHost(projectName, taskName, () -> { work.run(); return null; }));
    }

    public static <T> T readOnHost(String projectName, String taskName, Supplier<T> work)
    {
        ProjectContext context = ProjectContext.of(projectName);
        if (context.exists()) return readModel(context.project(), taskName, work);
        return work.get();
    }

    public static <T> T readOnHost(IProject project, String taskName, Supplier<T> work)
    {
        return project != null && project.exists()
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
        Map<IProject, IBmModel> active = READ_SCOPES.get();
        IBmModel captured = active == null ? null : active.get(project);
        if (captured != null) return work.apply(captured);
        var manager = Activator.getDefault().getBmModelManager();
        var model = BmModelResolver.resolve(project, manager).getModel();
        if (model == null)
        {
            throw Refusals.state("BM model is unavailable for project '" + project.getName()
                + "'. Check list_projects, wait for project loading and retry.");
        }
        return BmTransactions.read(model, taskName, (tx, monitor) -> {
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
        private static boolean notOnUiThread()
        {
            return org.eclipse.swt.widgets.Display.getCurrent() == null;
        }

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
