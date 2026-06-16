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
public final class DebugSessionRegistry // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
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

    /** Package-visible for headless unit testing (a {@link DebugEvent} is a plain DTO). */
    void handleEvent(DebugEvent ev)
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
                    // Launch-attribute lookup above can NEVER produce a minted
                    // ServerApplication.<app> key, so snapshots injected under such
                    // keys survived their own resume — purge them by
                    // reverse thread lookup. Evaluation resumes are skipped: the
                    // thread returns to the very same suspend location, and for a
                    // minted key no follow-up SUSPEND event would re-create the
                    // snapshot it would destroy.
                    if (!ev.isEvaluation())
                    {
                        purgeByEventSource(source, true);
                    }
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
                // Same minted-key blind spot as RESUME — purge whatever
                // the terminated thread/target/launch still owns, unconditionally.
                purgeByEventSource(source, false);
                break;
            default:
                break;
        }
    }

    /**
     * Purges every cached application whose REGISTERED thread matches the given
     * debug-event source — reverse lookup through the {@code threadAppId}/
     * {@code threadsById} maps (every snapshot's thread is registered there, both by
     * {@link #onSuspend} and {@link #injectSuspend}). This is the only event-driven
     * cleanup path for snapshots keyed by ids the launch-attribute lookup cannot
     * produce — i.e. minted {@code ServerApplication.<app>} keys.
     *
     * <p>A thread matches when it IS the source, or its debug target / launch is the
     * source (TERMINATE events may carry any of the three).
     *
     * <p>{@code onlyIfNotSuspended} guards RESUME events against asynchronous event
     * dispatch: a LATE resume event — dispatched after a poll-based tool (step /
     * wait_for_break) already observed the NEXT suspend and injected a fresh
     * snapshot for the same thread — must not destroy that fresh snapshot. If the
     * source thread still reports suspended, the resume is stale news; skip.
     *
     * <p>Package-visible for headless unit testing.
     *
     * @param source the debug-event source ({@link IThread}, {@link IDebugTarget}
     *     or {@link ILaunch}; anything else is ignored)
     * @param onlyIfNotSuspended when {@code true} and the source is a thread that
     *     still reports suspended, the purge is skipped (RESUME semantics)
     */
    synchronized void purgeByEventSource(Object source, boolean onlyIfNotSuspended)
    {
        if (source == null)
        {
            return;
        }
        if (onlyIfNotSuspended && source instanceof IThread)
        {
            try
            {
                if (((IThread) source).isSuspended())
                {
                    return; // late event — the thread is already suspended again
                }
            }
            catch (Exception ex)
            {
                // state unknown — fall through and purge (resume was reported)
            }
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<Long, String> entry : threadAppId.entrySet())
        {
            IThread thread = threadsById.get(entry.getKey());
            if (thread != null && matchesEventSource(thread, source) && !stale.contains(entry.getValue()))
            {
                stale.add(entry.getValue());
            }
        }
        for (String appId : stale)
        {
            forgetApplication(appId);
        }
    }

    /**
     * @return {@code true} when the registered thread belongs to the debug-event
     *     source: it IS the source thread, or its debug target / launch is the
     *     source. Identity comparison only; best-effort, never throws.
     */
    private static boolean matchesEventSource(IThread thread, Object source)
    {
        if (thread == source)
        {
            return true;
        }
        try
        {
            if (source instanceof IDebugTarget)
            {
                return thread.getDebugTarget() == source;
            }
            if (source instanceof ILaunch)
            {
                return thread.getLaunch() == source;
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return false;
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
        forgetApplication(appId);
    }

    /**
     * Purges every cached entry the registry holds for the given applicationId —
     * the suspend snapshot plus all thread/frame references owned by that app —
     * and wakes any waiters. This is exactly the cleanup a RESUME/TERMINATE debug
     * event performs, exposed so {@code terminate_launch} can clear the registry
     * for a launch it removes from {@link ILaunchManager} even when no TERMINATE
     * event ever fired (the stale/orphaned-launch case).
     *
     * <p>No-op for a {@code null} appId or an appId with no cached state.
     *
     * @param appId the applicationId (real or synthetic) to forget
     */
    public synchronized void forgetApplication(String appId)
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

    /**
     * Returns the applicationId under which the given stable threadId was
     * registered (by {@link #injectSuspend} or the SUSPEND event listener) —
     * i.e. the canonical key the thread's suspend snapshot lives under. This is
     * the authoritative key for tools that operate by {@code threadId} (step,
     * resume): re-deriving the key from the launch/server views could mint a
     * DIFFERENT id form for the same session and clear/inject the wrong snapshot.
     *
     * @param threadId stable thread id from a snapshot response
     * @return the owning applicationId, or {@code null} if unknown/stale
     */
    public String getThreadApplicationId(long threadId)
    {
        return threadAppId.get(threadId);
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
        for (ILaunch launch : mgr.getLaunches()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
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
     * Returns all applicationIds (real or synthetic) that currently have an active,
     * non-terminated <b>debug-mode</b> launch in the Eclipse launch manager.
     * <p>
     * RUN-mode launches are deliberately excluded: they never produce a debug target
     * or suspend events, so auto-resolving a debug operation onto a RUN launch could
     * never succeed - it would resolve to a session that can never break. (audit A13)
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
            if (!isActiveDebugLaunch(launch))
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
     * @param launch the launch to test
     * @return {@code true} when the launch is non-terminated and was started in
     *         {@link ILaunchManager#DEBUG_MODE} (not run mode). Package-visible and
     *         static for headless unit testing.
     */
    static boolean isActiveDebugLaunch(ILaunch launch)
    {
        return launch != null
            && !launch.isTerminated()
            && ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode());
    }

    /**
     * Returns the first active (non-terminated) {@link ILaunch} whose launch resolves
     * to the given applicationId, <b>regardless of mode</b> (run or debug).
     * <p>
     * Unlike {@link #findActiveTarget}, this does not require a debug target, so it
     * also detects a RUN-mode session (which carries no debug target). Used by
     * debug_launch to avoid starting a second client over an already-running one.
     * (audit A12)
     *
     * @param applicationId the application id (real or synthetic)
     * @return the active launch, or {@code null} if none matches
     */
    public static ILaunch findActiveLaunch(String applicationId)
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
            if (applicationId.equals(findApplicationIdFor(launch)))
            {
                return launch;
            }
        }
        return null;
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
