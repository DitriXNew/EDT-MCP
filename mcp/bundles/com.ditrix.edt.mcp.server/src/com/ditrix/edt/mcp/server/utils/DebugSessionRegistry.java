/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.IDebugEventSetListener;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Singleton registry that tracks active 1C debug sessions, suspended state and
 * issues stable IDs for threads/stack frames so MCP tools can refer to them
 * across calls.
 *
 * <p>The registry installs a single {@link IDebugEventSetListener} on the
 * Eclipse {@link DebugPlugin} on first use. Suspend events are recorded per
 * launch (keyed by {@code applicationId} extracted from the launch configuration —
 * real {@code ATTR_APPLICATION_ID} for runtime-client launches, or the synthetic
 * {@code attach:<configName>} id for attach launches); resume/terminate events
 * purge the cached snapshot and any associated IDs.
 */
public final class DebugSessionRegistry
{
    private static final DebugSessionRegistry INSTANCE = new DebugSessionRegistry();

    private final AtomicBoolean listenerRegistered = new AtomicBoolean(false);
    private final AtomicLong idGenerator = new AtomicLong(1);

    /** applicationId → most recent suspend snapshot. */
    private final Map<String, SuspendSnapshot> snapshots = new ConcurrentHashMap<>();
    /** stable threadId → live IThread reference. */
    private final Map<Long, IThread> threadsById = new ConcurrentHashMap<>();
    /** stable frameRef → live IStackFrame reference. */
    private final Map<Long, IStackFrame> framesById = new ConcurrentHashMap<>();
    /** stable threadId → owning applicationId (for cleanup). */
    private final Map<Long, String> threadAppId = new ConcurrentHashMap<>();
    /** stable frameRef → owning applicationId (for cleanup). */
    private final Map<Long, String> frameAppId = new ConcurrentHashMap<>();

    private DebugSessionRegistry()
    {
    }

    public static DebugSessionRegistry get()
    {
        return INSTANCE;
    }

    /** Snapshot of a suspended thread at the moment a SUSPEND event arrived. */
    public static final class SuspendSnapshot
    {
        public final long threadId;
        public final IThread thread;
        public final long timestamp;

        SuspendSnapshot(long threadId, IThread thread)
        {
            this.threadId = threadId;
            this.thread = thread;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * Lazily installs the global debug event listener. Safe to call from any thread.
     */
    public void ensureListenerRegistered()
    {
        if (!listenerRegistered.compareAndSet(false, true))
        {
            return;
        }
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            listenerRegistered.set(false);
            return;
        }
        debugPlugin.addDebugEventListener(new IDebugEventSetListener()
        {
            @Override
            public void handleDebugEvents(DebugEvent[] events)
            {
                for (DebugEvent ev : events)
                {
                    handleEvent(ev);
                }
            }
        });
        Activator.logInfo("DebugSessionRegistry: event listener registered"); //$NON-NLS-1$
    }

    private void handleEvent(DebugEvent ev)
    {
        Object source = ev.getSource();
        switch (ev.getKind())
        {
            case DebugEvent.SUSPEND:
                if (source instanceof IThread)
                {
                    onSuspend((IThread) source);
                }
                break;
            case DebugEvent.RESUME:
                if (source instanceof IThread)
                {
                    onResumeOrTerminate(findApplicationIdFor((IThread) source));
                }
                break;
            case DebugEvent.TERMINATE:
                if (source instanceof IDebugTarget)
                {
                    onResumeOrTerminate(findApplicationIdFor((IDebugTarget) source));
                }
                else if (source instanceof IThread)
                {
                    onResumeOrTerminate(findApplicationIdFor((IThread) source));
                }
                else if (source instanceof ILaunch)
                {
                    onResumeOrTerminate(findApplicationIdFor((ILaunch) source));
                }
                break;
            default:
                break;
        }
    }

    private synchronized void onSuspend(IThread thread)
    {
        String appId = findApplicationIdFor(thread);
        if (appId == null)
        {
            return;
        }
        long threadId = idGenerator.getAndIncrement();
        threadsById.put(threadId, thread);
        threadAppId.put(threadId, appId);
        SuspendSnapshot snapshot = new SuspendSnapshot(threadId, thread);
        snapshots.put(appId, snapshot);
        // notify any waiters
        notifyAll();
    }

    private synchronized void onResumeOrTerminate(String appId)
    {
        if (appId == null)
        {
            return;
        }
        snapshots.remove(appId);
        // drop stale thread/frame references for this app
        threadAppId.entrySet().removeIf(entry -> {
            if (appId.equals(entry.getValue()))
            {
                threadsById.remove(entry.getKey());
                return true;
            }
            return false;
        });
        // drop frames owned by this application
        frameAppId.entrySet().removeIf(entry -> {
            if (appId.equals(entry.getValue()))
            {
                framesById.remove(entry.getKey());
                return true;
            }
            return false;
        });
        notifyAll();
    }

    /**
     * Blocks the calling thread until a SUSPEND snapshot for the given application
     * appears, or the timeout expires.
     *
     * @return snapshot if a suspend was observed (or already present), or {@code null} on timeout.
     */
    public synchronized SuspendSnapshot waitForSuspend(String applicationId, long timeoutMs)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        SuspendSnapshot s = snapshots.get(applicationId);
        if (s != null)
        {
            return s;
        }
        while (true)
        {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return null;
            }
            wait(remaining);
            s = snapshots.get(applicationId);
            if (s != null)
            {
                return s;
            }
        }
    }

    /** Registers an IStackFrame and returns a stable id for later lookup. */
    public synchronized long registerFrame(IStackFrame frame)
    {
        long id = idGenerator.getAndIncrement();
        framesById.put(id, frame);
        // Track owning appId so onResumeOrTerminate can clean up deterministically
        try
        {
            String appId = findApplicationIdFor(frame.getThread());
            if (appId != null)
            {
                frameAppId.put(id, appId);
            }
        }
        catch (Exception ex)
        {
            // best effort
        }
        return id;
    }

    /** Returns true if the registry already holds a suspend snapshot for the given appId. */
    public boolean hasSnapshot(String appId)
    {
        return appId != null && snapshots.containsKey(appId);
    }

    /**
     * Injects a synthetic suspend snapshot for a thread that was already suspended
     * before the listener was installed. This allows wait_for_break to pick it up
     * without requiring a new SUSPEND event.
     */
    public synchronized void injectSuspend(String appId, IThread thread)
    {
        if (appId == null || thread == null || snapshots.containsKey(appId))
        {
            return;
        }
        long threadId = idGenerator.getAndIncrement();
        threadsById.put(threadId, thread);
        threadAppId.put(threadId, appId);
        SuspendSnapshot snapshot = new SuspendSnapshot(threadId, thread);
        snapshots.put(appId, snapshot);
        notifyAll();
    }

    /**
     * Clears the snapshot for the given applicationId without touching thread/frame
     * caches. Used by StepTool to prevent waitForSuspend from returning the stale
     * pre-step snapshot.
     */
    public synchronized void clearSnapshot(String appId)
    {
        if (appId != null)
        {
            snapshots.remove(appId);
        }
    }

    public IThread getThread(long threadId)
    {
        return threadsById.get(threadId);
    }

    public IStackFrame getFrame(long frameRef)
    {
        return framesById.get(frameRef);
    }

    /** Returns the most recent suspend snapshot for the given appId, or {@code null}. */
    public SuspendSnapshot getSnapshot(String applicationId)
    {
        return applicationId != null ? snapshots.get(applicationId) : null;
    }

    /**
     * Walks the launch configuration of the given thread/target/launch and pulls
     * a stable applicationId: the real {@code ATTR_APPLICATION_ID} for runtime-client
     * launches, or the synthetic {@code attach:<configName>} id for attach launches.
     * Returns {@code null} if it can't be determined (orphan launch, unknown config type).
     */
    public static String findApplicationIdFor(IThread thread)
    {
        if (thread == null)
        {
            return null;
        }
        try
        {
            return findApplicationIdFor(thread.getDebugTarget());
        }
        catch (Exception ex)
        {
            return null;
        }
    }

    public static String findApplicationIdFor(IDebugTarget target)
    {
        if (target == null)
        {
            return null;
        }
        return findApplicationIdFor(target.getLaunch());
    }

    public static String findApplicationIdFor(ILaunch launch)
    {
        return LaunchConfigUtils.getApplicationIdFor(launch);
    }

    /**
     * Searches for an active (non-terminated) {@link IDebugTarget} whose launch
     * resolves to the given applicationId (real or synthetic).
     */
    public static IDebugTarget findActiveTarget(String applicationId)
    {
        if (applicationId == null)
        {
            return null;
        }
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            return null;
        }
        ILaunchManager mgr = debugPlugin.getLaunchManager();
        for (ILaunch launch : mgr.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            String appId = findApplicationIdFor(launch);
            if (!applicationId.equals(appId))
            {
                continue;
            }
            for (IDebugTarget target : launch.getDebugTargets())
            {
                if (target != null && !target.isTerminated())
                {
                    return target;
                }
            }
        }
        return null;
    }

    /**
     * Returns a per-application snapshot of the registry contents — used by tools
     * for diagnostic responses.
     */
    public Map<String, Object> snapshotInfo()
    {
        Map<String, Object> info = new HashMap<>();
        info.put("activeApplications", snapshots.size()); //$NON-NLS-1$
        info.put("liveThreads", threadsById.size()); //$NON-NLS-1$
        info.put("liveFrames", framesById.size()); //$NON-NLS-1$
        return info;
    }

    /**
     * Returns all applicationIds (real or synthetic) that currently have an
     * active, non-terminated debug launch in the Eclipse launch manager.
     */
    public static List<String> listActiveApplicationIds()
    {
        List<String> ids = new ArrayList<>();
        DebugPlugin debugPlugin = DebugPlugin.getDefault();
        if (debugPlugin == null)
        {
            return ids;
        }
        ILaunchManager mgr = debugPlugin.getLaunchManager();
        for (ILaunch launch : mgr.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            String appId = findApplicationIdFor(launch);
            if (appId != null && !ids.contains(appId))
            {
                ids.add(appId);
            }
        }
        return ids;
    }

    /**
     * Convenience: returns the applicationId of the single active, non-terminated
     * debug launch, or {@code null} if there is none or more than one.
     */
    public static String findLoneActiveApplicationId()
    {
        List<String> ids = listActiveApplicationIds();
        return ids.size() == 1 ? ids.get(0) : null;
    }

    /** For tests only — drops all cached state. */
    public synchronized void clear()
    {
        snapshots.clear();
        threadsById.clear();
        framesById.clear();
        threadAppId.clear();
        frameAppId.clear();
        notifyAll();
    }
}
