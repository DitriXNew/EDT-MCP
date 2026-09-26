/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

/**
 * Pauses a running debug target and answers from what is OBSERVED afterwards: a suspended
 * thread (registered as the session's snapshot), the target ending, or nothing within the window.
 *
 * <p>A 1C target takes the request at target level only (its threads never accept one) and sends
 * the debug server "break on the next statement": the pause lands when BSL code next runs, so an
 * idle session stays running and the request stays armed. The target flips its own
 * {@code isSuspended()} at once, so that flag is never read as "paused" here - only a suspended
 * thread is.
 */
public final class DebugPause
{
    /** Default wait window, in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    /** Hard cap on the wait window, in seconds. */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /** What the pause attempt observed. */
    public enum State
    {
        /** A thread of the session is suspended; {@link Outcome#snapshot} holds it. */
        SUSPENDED,
        /** The request went out (or was already pending) but no thread suspended in the window. */
        RUNNING,
        /** The target terminated before a suspended thread was observed. */
        TERMINATED,
        /** Neither the target nor any thread accepts a suspend request, and none is pending. */
        REFUSED
    }

    /** The result of {@link #pause}. */
    public static final class Outcome
    {
        /** The observed state. */
        public final State state;
        /** The registered snapshot; non-null exactly when {@link #state} is SUSPENDED. */
        public final DebugSessionRegistry.SuspendSnapshot snapshot;
        /** True when the session was already suspended, so no request was sent. */
        public final boolean alreadySuspended;

        private Outcome(State state, DebugSessionRegistry.SuspendSnapshot snapshot, boolean alreadySuspended)
        {
            this.state = state;
            this.snapshot = snapshot;
            this.alreadySuspended = alreadySuspended;
        }
    }

    private DebugPause()
    {
        // utility class
    }

    /**
     * Clamps a requested wait window to {@code [1, MAX_TIMEOUT_SECONDS]}.
     *
     * @param requested the requested window in seconds
     * @return the effective window in seconds
     */
    public static int clampTimeoutSeconds(int requested)
    {
        return Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, requested));
    }

    /**
     * Pauses {@code target} and waits up to {@code timeoutMs} for a suspended thread. An already
     * suspended session is returned at once without a new request; a request an earlier call left
     * pending is waited on, not re-sent.
     *
     * @param target the resolved, live debug target
     * @param applicationId the canonical session id the snapshot is registered under
     * @param registry the suspend registry shared with the other debug tools
     * @param timeoutMs the wait window in milliseconds
     * @param pollIntervalMs the pause between two observations in milliseconds
     * @return the observed outcome (never {@code null})
     * @throws DebugException if the suspend request fails on a target that is still live - the
     *     break may or may not be armed
     * @throws InterruptedException if the waiting thread is interrupted after the request
     */
    public static Outcome pause(IDebugTarget target, String applicationId, DebugSessionRegistry registry,
        long timeoutMs, long pollIntervalMs) throws DebugException, InterruptedException
    {
        if (target.isTerminated())
        {
            return new Outcome(State.TERMINATED, null, false);
        }
        DebugSessionRegistry.SuspendSnapshot current = currentSuspension(target, applicationId, registry);
        if (current != null)
        {
            return new Outcome(State.SUSPENDED, current, true);
        }
        boolean requested;
        try
        {
            requested = requestSuspend(target);
        }
        catch (DebugException e)
        {
            // A request that failed because the target ended has a known outcome.
            if (target.isTerminated())
            {
                return new Outcome(State.TERMINATED, null, false);
            }
            throw e;
        }
        if (!requested && !target.isSuspended())
        {
            return new Outcome(target.isTerminated() ? State.TERMINATED : State.REFUSED, null, false);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true)
        {
            DebugSessionRegistry.SuspendSnapshot observed = currentSuspension(target, applicationId, registry);
            if (observed != null)
            {
                return new Outcome(State.SUSPENDED, observed, false);
            }
            if (target.isTerminated())
            {
                return new Outcome(State.TERMINATED, null, false);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
            {
                return new Outcome(State.RUNNING, null, false);
            }
            Thread.sleep(Math.min(pollIntervalMs, remaining));
        }
    }

    /**
     * Returns the session's snapshot when one of its threads is suspended right now, registering
     * the thread if no live snapshot exists yet; {@code null} when nothing is suspended.
     */
    private static DebugSessionRegistry.SuspendSnapshot currentSuspension(IDebugTarget target,
        String applicationId, DebugSessionRegistry registry)
    {
        DebugSessionRegistry.SuspendSnapshot existing = registry.getSnapshot(applicationId);
        if (existing != null)
        {
            if (isSuspended(existing.thread))
            {
                return existing;
            }
            // The thread has left that stop (a 1C thread stays SUSPENDED while an expression
            // evaluates), so the stop's threadId and frameRefs go with it.
            registry.forgetApplication(applicationId);
        }
        IThread suspended = DebugServerTargetSupport.findSuspendedThread(target);
        if (suspended == null)
        {
            return null;
        }
        registry.injectSuspend(applicationId, suspended);
        DebugSessionRegistry.SuspendSnapshot registered = registry.getSnapshot(applicationId);
        // A resume racing the registration leaves nothing (or a released thread) to hand out.
        return registered != null && isSuspended(registered.thread) ? registered : null;
    }

    /**
     * Sends the suspend request: at target level when the target accepts it, else to each thread
     * that does (1C threads never do; this is the generic Eclipse contract).
     *
     * @return {@code true} when a request was sent
     */
    private static boolean requestSuspend(IDebugTarget target) throws DebugException
    {
        if (target.canSuspend())
        {
            target.suspend();
            return true;
        }
        boolean requested = false;
        for (IThread thread : target.getThreads())
        {
            if (thread != null && thread.canSuspend())
            {
                thread.suspend();
                requested = true;
            }
        }
        return requested;
    }

    private static boolean isSuspended(IThread thread)
    {
        return thread != null && thread.isSuspended();
    }
}
