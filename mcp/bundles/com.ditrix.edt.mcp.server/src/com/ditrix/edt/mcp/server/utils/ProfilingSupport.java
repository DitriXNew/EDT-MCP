/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
import com.ditrix.edt.mcp.server.Activator;

/** Typed EDT service access and bounded completion of asynchronous measurements. */
public final class ProfilingSupport
{
    private static final java.util.concurrent.atomic.AtomicBoolean STATE_WARNING =
        new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * Serializes this plugin's check-then-write changes to the measurement store (a rename checks that
     * the name is free, an import that no UUID or name is taken). Our own lock, not EDT's: the service's
     * monitor is also held by EDT's {@code toggleProfiling} across a debug-server command, so a change
     * that synchronized on the service would wait - on the UI thread - for that command to return.
     */
    private static final Object STORE_CHANGE_LOCK = new Object();

    private ProfilingSupport() { }

    private static Boolean unknownState(String reason)
    {
        if (STATE_WARNING.compareAndSet(false, true))
        {
            Activator.logWarning("ProfilingService.states is unreadable: " + reason
                + "; profiling state is unknown.");
        }
        return null;
    }

    public static IProfilingService service()
    {
        IProfilingService service = ServiceAccess.get(IProfilingService.class);
        if (service == null)
        {
            throw new IllegalStateException("IProfilingService is unavailable. Enable the EDT profiling bundle.");
        }
        return service;
    }

    /**
     * EDT saves before notifying UI listeners. A listener failure must not hide an observed write. The
     * mutation - a check and the write it guards - runs under {@link #STORE_CHANGE_LOCK} on the thread that
     * executes it, never across the UI dispatch.
     */
    public static String applyObserved(Runnable mutation, BooleanSupplier observed, String action)
    {
        return applyObserved(BoundedUiCall.workbenchExecutorOrNull(), mutation, observed, action, 5000);
    }

    static String applyObserved(BoundedUiCall.UiExecutor executor, Runnable mutation,
        BooleanSupplier observed, String action, long timeoutMillis)
    {
        FutureTask<Void> task = new FutureTask<>(() -> {
            synchronized (STORE_CHANGE_LOCK)
            {
                mutation.run();
            }
        }, null);
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

    static IProfileTarget profileTarget(IDebugTarget target)
    {
        if (target == null) { return null; }
        return target instanceof IProfileTarget direct ? direct : target.getAdapter(IProfileTarget.class);
    }

    /**
     * The measurement UUID EDT sent to the debug server for this target. EDT keeps it only in the
     * service's private {@code targets} map, so it is read reflectively; null when it cannot be read.
     */
    public static UUID sessionId(IProfilingService service, IDebugTarget target)
    {
        IProfileTarget profile = profileTarget(target);
        if (profile == null || service == null) { return null; }
        for (Class<?> type = service.getClass(); type != null; type = type.getSuperclass())
        {
            try
            {
                Field field = type.getDeclaredField("targets"); //$NON-NLS-1$
                field.setAccessible(true);
                if (!(field.get(service) instanceof Map<?, ?> targets)) { break; }
                for (Map.Entry<?, ?> entry : new ArrayList<>(targets.entrySet()))
                {
                    if (entry.getValue() == profile && entry.getKey() instanceof UUID id) { return id; }
                }
                return null;
            }
            catch (NoSuchFieldException e) { /* not declared here: look in the superclass */ }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                Activator.logWarning("Profiling session id is unreadable (" + PlatformFailures.describe(e) //$NON-NLS-1$
                    + "); stop_profiling will not name measurements."); //$NON-NLS-1$
                return null;
            }
        }
        Activator.logWarning("IProfilingService has no session map in this EDT build; stop_profiling will not name measurements."); //$NON-NLS-1$
        return null;
    }

    /**
     * EDT's own on/off state for this target: {@code ProfilingService.states} maps a profiled target to ENABLED and
     * drops it on stop. Reflective, as the service has no getter; null when it cannot be read.
     */
    public static Boolean enabled(IProfilingService service, IDebugTarget target)
    {
        IProfileTarget profile = profileTarget(target);
        if (profile == null || service == null) { return null; }
        for (Class<?> type = service.getClass(); type != null; type = type.getSuperclass())
        {
            try
            {
                Field field = type.getDeclaredField("states"); //$NON-NLS-1$
                field.setAccessible(true);
                if (!(field.get(service) instanceof Map<?, ?> states)) { return unknownState("not a map"); }
                Object state = states.get(profile);
                return state != null && "ENABLED".equals(String.valueOf(state)); //$NON-NLS-1$
            }
            catch (NoSuchFieldException e) { /* not declared here: look in the superclass */ }
            catch (ReflectiveOperationException | RuntimeException e)
            {
                return unknownState(PlatformFailures.describe(e));
            }
        }
        return unknownState("member not found");
    }

    /** Refuses an unsafe start toggle when EDT's current state cannot be established. */
    public static String startProfilingIfKnown(IProfilingService service, IDebugTarget target)
    {
        Boolean state = enabled(service, target);
        if (state == null)
        {
            return "Profiling state is unknown; inspect the EDT profiling view before retrying. No toggle was sent.";
        }
        if (!state) { service.toggleProfiling(profileTarget(target)); }
        return null;
    }

    /** The caller owns the tracked on/off state: EDT exposes a toggle, not a setter. */
    public static String toggleProfiling(IDebugTarget target)
    {
        if (target == null)
        {
            return "No active debug target. Use debug_status to select a running session.";
        }
        IProfileTarget profile = profileTarget(target);
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

    /**
     * Runs on the calling worker, never SWT. With a known session id only that measurement matches;
     * without one, a single new arrival is reported and several are ambiguous.
     */
    public static IProfilingResult awaitNewResult(IProfilingService service, Set<UUID> before, UUID sessionId,
        long timeoutMillis) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do
        {
            List<IProfilingResult> added = service.getResults().stream()
                .filter(result -> !before.contains(result.getUuid())).toList();
            if (sessionId != null)
            {
                IProfilingResult own = added.stream().filter(r -> sessionId.equals(r.getUuid())).findFirst().orElse(null);
                if (own != null) { return own; }
            }
            else if (added.size() == 1) { return added.get(0); }
            else if (added.size() > 1) { return null; }
            if (System.nanoTime() >= deadline) { return null; }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        while (true);
    }
}
