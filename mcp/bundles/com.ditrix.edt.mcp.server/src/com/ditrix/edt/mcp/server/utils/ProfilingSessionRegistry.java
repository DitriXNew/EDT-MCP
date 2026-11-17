/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.debug.core.model.IDebugTarget;

/** Shared profiling ownership and serialized start/stop transitions. */
public final class ProfilingSessionRegistry
{
    private ProfilingSessionRegistry() { }

    /** Profiling ownership keyed by application id, shared by start, stop and result reads. */
    private static final Map<String, Object> ACTIVE_APPLICATION_IDS = new ConcurrentHashMap<>();

    /** Serializes each start/stop lookup, toggle and ownership change. */
    public static final Object TRANSITIONS = new Object();

    /** Owner of a flag set without a known target (tests, legacy callers). */
    private static final Object UNKNOWN_TARGET = new Object();

    /** Whether this application has a tracked profiling owner. */
    public static boolean isProfilingActive(String applicationId)
    {
        return applicationId != null && ACTIVE_APPLICATION_IDS.containsKey(applicationId);
    }

    /** Reconciles EDT state while retaining a newer live owner across stale lookups. */
    public static void reconcile(String applicationId, IDebugTarget target)
    {
        if (applicationId == null) { return; }
        if (target == null)
        {
            // A lookup miss proves nothing by itself (a relaunch may not be registered yet): drop the flag
            // only when its own target has ended.
            if (ACTIVE_APPLICATION_IDS.get(applicationId) instanceof IDebugTarget owner && ended(owner))
            {
                // Only the owner checked: a concurrent start may have replaced it meanwhile.
                ACTIVE_APPLICATION_IDS.remove(applicationId, owner);
            }
            return;
        }
        Boolean enabled;
        try { enabled = ProfilingSupport.enabled(ProfilingSupport.service(), target); }
        catch (RuntimeException e) { enabled = null; }
        if (enabled != null)
        {
            boolean on = enabled;
            ACTIVE_APPLICATION_IDS.compute(applicationId,
                (key, current) -> ownerAfterReading(current, target, on, ProfilingSessionRegistry::ended));
            return;
        }
        // Another owner is dropped only once it has ended: a stale lookup may predate a newer live owner.
        if (ACTIVE_APPLICATION_IDS.get(applicationId) instanceof IDebugTarget owner && owner != target && ended(owner))
        {
            ACTIVE_APPLICATION_IDS.remove(applicationId, owner);
        }
    }

    /** Prefers the live target owning the flag over a stale lookup. */
    public static IDebugTarget profiledTarget(String applicationId, IDebugTarget lookedUp)
    {
        return profiledTarget(ACTIVE_APPLICATION_IDS.get(applicationId), lookedUp, ProfilingSessionRegistry::ended);
    }

    public static IDebugTarget profiledTarget(Object owner, IDebugTarget lookedUp,
        java.util.function.Predicate<IDebugTarget> ended)
    {
        return owner instanceof IDebugTarget target && !ended.test(target) ? target : lookedUp;
    }

    /** Replaces only the same or an ended owner after reading EDT state. */
    public static Object ownerAfterReading(Object current, IDebugTarget target, boolean enabled,
        java.util.function.Predicate<IDebugTarget> ended)
    {
        boolean replaceable = current == null || current == target || !(current instanceof IDebugTarget owner)
            || ended.test(owner);
        if (!replaceable)
        {
            return current;
        }
        return enabled && !ended.test(target) ? target : null;
    }

    /** Checks target, launch and client process liveness before dropping ownership. */
    public static boolean ended(IDebugTarget target)
    {
        org.eclipse.debug.core.DebugPlugin debug = org.eclipse.debug.core.DebugPlugin.getDefault();
        return ended(target, launch -> debug != null
            && java.util.Arrays.asList(debug.getLaunchManager().getLaunches()).contains(launch));
    }

    public static boolean ended(IDebugTarget target, java.util.function.Predicate<org.eclipse.debug.core.ILaunch> registered)
    {
        if (target.isTerminated()) { return true; }
        org.eclipse.debug.core.ILaunch launch = target.getLaunch();
        return launch == null || launch.isTerminated()
            || !registered.test(launch) && com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.clientExited(launch);
    }

    /** Whether any application has a tracked profiling owner. */
    public static boolean isAnyProfilingActive()
    {
        return !ACTIVE_APPLICATION_IDS.isEmpty();
    }

    /** Marks profiling active for the given application id. */
    public static void markActive(String applicationId)
    {
        markActive(applicationId, null);
    }

    public static void markActive(String applicationId, IDebugTarget target)
    {
        if (applicationId != null)
        {
            ACTIVE_APPLICATION_IDS.put(applicationId, target == null ? UNKNOWN_TARGET : target);
        }
    }

    /** Marks profiling inactive for the given application id. */
    public static void markInactive(String applicationId)
    {
        if (applicationId != null)
        {
            ACTIVE_APPLICATION_IDS.remove(applicationId);
        }
    }

    /** For tests only — drops all tracked profiling state. */
    public static void clearStateForTests()
    {
        ACTIVE_APPLICATION_IDS.clear();
    }

}
