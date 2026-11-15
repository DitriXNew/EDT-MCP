/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.eclipse.debug.core.model.IDebugTarget;
import com._1c.g5.v8.dt.profiling.core.IProfileTarget;
import com._1c.g5.v8.dt.profiling.core.IProfilingResult;
import com._1c.g5.v8.dt.profiling.core.IProfilingService;
import com._1c.g5.wiring.ServiceAccess;

/** Typed EDT service access and bounded completion of asynchronous measurements. */
public final class ProfilingSupport
{
    private ProfilingSupport() { }

    public static IProfilingService service()
    {
        IProfilingService service = ServiceAccess.get(IProfilingService.class);
        if (service == null)
        {
            throw new IllegalStateException("IProfilingService is unavailable. Enable the EDT profiling bundle.");
        }
        return service;
    }

    /** EDT saves before notifying UI listeners. A listener failure must not hide an observed write. */
    public static String applyObserved(Runnable mutation, BooleanSupplier observed, String action)
    {
        return applyObserved(BoundedUiCall.workbenchExecutorOrNull(), mutation, observed, action, 5000);
    }

    static String applyObserved(BoundedUiCall.UiExecutor executor, Runnable mutation,
        BooleanSupplier observed, String action, long timeoutMillis)
    {
        FutureTask<Void> task = new FutureTask<>(mutation, null);
        Exception failure = null;
        try
        {
            if (executor == null) { task.run(); }
            else
            {
                var dispatch = BoundedUiCall.dispatch(executor, task, timeoutMillis,
                    BoundedUiCall.UiThreadPolicy.RUN_INLINE);
                if (dispatch.outcome() != BoundedUiCall.Outcome.COMPLETED)
                {
                    throw new IllegalStateException("EDT UI did not complete " + action
                        + " within " + timeoutMillis + " ms; check the measurement before retrying.", dispatch.failure());
                }
            }
            task.get(0, TimeUnit.MILLISECONDS);
        }
        catch (Exception e)
        {
            if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            failure = e instanceof ExecutionException && e.getCause() instanceof Exception cause ? cause : e;
        }
        finally { task.cancel(false); } // Cancel work that is still queued, without interrupting a committed save.
        if (!observed.getAsBoolean())
        {
            throw new IllegalStateException(action + " was not observed in EDT. "
                + (failure == null ? "EDT did not apply the change." : PlatformFailures.describe(failure))
                + " Use get_profiling_results mode=list to inspect the measurement before retrying.", failure);
        }
        return failure == null ? "" : " The change was observed, but the EDT profiling view may need a refresh.";
    }

    /** The caller owns the tracked on/off state: EDT exposes a toggle, not a setter. */
    public static String toggleProfiling(IDebugTarget target)
    {
        if (target == null)
        {
            return "No active debug target. Use debug_status to select a running session.";
        }
        IProfileTarget profile = target instanceof IProfileTarget direct ? direct : target.getAdapter(IProfileTarget.class);
        if (profile == null)
        {
            return "Debug target does not support profiling. Launch a 1C debug session first.";
        }
        service().toggleProfiling(profile);
        return null;
    }

    public static List<IProfilingResult> newest(List<IProfilingResult> results)
    {
        return results.stream().sorted(Comparator.comparing(IProfilingResult::getDateOfSession,
            Comparator.nullsFirst(Comparator.naturalOrder())).reversed().thenComparing(r -> r.getUuid().toString())).toList();
    }
    public static IProfilingResult select(List<IProfilingResult> results, String id)
    {
        if (id == null && !results.isEmpty()) { return results.get(0); }
        return results.stream().filter(r -> r.getUuid().toString().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Measurement id '" + id + "' not found. Use mode=list to choose a loaded UUID."));
    }

    public static Set<UUID> ids(IProfilingService service)
    {
        return service.getResults().stream().map(IProfilingResult::getUuid).collect(Collectors.toSet());
    }

    /** Runs on the calling worker, never SWT. Multiple arrivals are ambiguous and are not renamed. */
    public static IProfilingResult awaitNewResult(IProfilingService service, Set<UUID> before, long timeoutMillis)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do
        {
            List<IProfilingResult> added = service.getResults().stream()
                .filter(result -> !before.contains(result.getUuid())).toList();
            if (added.size() == 1) { return added.get(0); }
            if (added.size() > 1) { return null; }
            if (System.nanoTime() >= deadline) { return null; }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        while (true);
    }
}
