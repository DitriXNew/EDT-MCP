/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Resolves the target stack frame for a suspended-debug tool from (in priority
 * order) a stable {@code frameRef}, a {@code threadId + frameIndex}, or the
 * single auto-resolved active debug session. Shared by every tool that operates
 * on a stack frame (get_variables, set_variable, …) so the frame-resolution
 * policy and its exact error messages live in exactly one place.
 *
 * <p>Pure and headless: it touches only the in-memory {@link DebugSessionRegistry}
 * and the shared {@link DebugTargetResolver}; it re-implements neither the
 * OSGi/service lookup (that stays inside {@link DebugTargetResolver} /
 * {@link DebugSessionRegistry}) nor any SWT/UI access.
 */
public final class DebugFrameResolver
{
    private DebugFrameResolver()
    {
        // utility class
    }

    /**
     * Resolves the target stack frame from (in priority order) {@code frameRef},
     * {@code threadId + frameIndex}, or the auto-resolved single active debug
     * session. Returns a holder carrying either the resolved frame or the exact
     * error JSON to return to the caller unchanged.
     *
     * @param registry the debug session registry (non-null)
     * @param frameRef a stable frame reference returned from wait_for_break, or a
     *            non-positive value when unused
     * @param threadId a stable thread id, or a non-positive value when unused
     * @param frameIndex the 0-based frame index used with {@code threadId} or the
     *            auto-resolved session
     * @return a {@link Resolution} with a non-null {@code frame} on success, or a
     *         non-null {@code error} (the same error JSON the inline branches
     *         produced) otherwise
     */
    public static Resolution resolve(DebugSessionRegistry registry, long frameRef, long threadId, int frameIndex)
        throws DebugException
    {
        if (frameRef > 0)
        {
            return resolveByFrameRef(registry, frameRef);
        }
        if (threadId > 0)
        {
            return resolveByThreadId(registry, threadId, frameIndex);
        }
        return resolveByAutoSession(registry, frameIndex);
    }

    /** Resolves the frame stored under a stable {@code frameRef} from wait_for_break. */
    private static Resolution resolveByFrameRef(DebugSessionRegistry registry, long frameRef)
    {
        IStackFrame frame = registry.getFrame(frameRef);
        if (frame == null)
        {
            return Resolution.failed(
                ToolResult.error("stale frameRef — call wait_for_break again").toJson()); //$NON-NLS-1$
        }
        return Resolution.of(frame);
    }

    /** Resolves the {@code frameIndex}-th frame of the live thread {@code threadId}. */
    private static Resolution resolveByThreadId(DebugSessionRegistry registry, long threadId, int frameIndex)
        throws DebugException
    {
        IThread thread = registry.getThread(threadId);
        if (thread == null)
        {
            return Resolution.failed(
                ToolResult.error("stale threadId — call wait_for_break again").toJson()); //$NON-NLS-1$
        }
        IStackFrame[] frames = thread.getStackFrames();
        if (frameIndex < 0 || frameIndex >= frames.length)
        {
            return Resolution.failed(ToolResult.error("frameIndex out of range (0.." //$NON-NLS-1$
                + (frames.length - 1) + ")").toJson()); //$NON-NLS-1$
        }
        return Resolution.of(frames[frameIndex]);
    }

    /**
     * Fallback: auto-resolve the single active debug session through the SAME
     * blank-id policy every applicationId-based tool uses (DebugTargetResolver:
     * the lone Eclipse launch, else the lone server target) and read its snapshot
     * under the canonical key — replaces a hand-rolled condensed copy of that
     * policy. The frame index is clamped into range.
     */
    private static Resolution resolveByAutoSession(DebugSessionRegistry registry, int frameIndex)
        throws DebugException
    {
        DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(null);
        DebugSessionRegistry.SuspendSnapshot snap =
            res != null ? registry.getSnapshot(res.canonicalId) : null;
        if (snap == null)
        {
            return Resolution.failed(
                ToolResult.error("Provide frameRef or threadId — no single suspended debug " //$NON-NLS-1$
                    + "session available for auto-resolution. Call wait_for_break first.").toJson()); //$NON-NLS-1$
        }
        IStackFrame[] frames = snap.thread.getStackFrames();
        if (frames.length == 0)
        {
            return Resolution.failed(
                ToolResult.error("suspended thread has no stack frames").toJson()); //$NON-NLS-1$
        }
        return Resolution.of(frames[Math.min(Math.max(frameIndex, 0), frames.length - 1)]);
    }

    /**
     * Holder threading a frame-resolution early-return out of {@link #resolve}:
     * exactly one of {@code frame} / {@code error} is non-null. {@code error}
     * carries the ready-to-return error JSON produced by
     * {@link ToolResult#error(String)} — the exact same value (same case) the
     * inline branches returned.
     */
    public static final class Resolution
    {
        public final IStackFrame frame;
        public final String error;

        private Resolution(IStackFrame frame, String error)
        {
            this.frame = frame;
            this.error = error;
        }

        static Resolution of(IStackFrame frame)
        {
            return new Resolution(frame, null);
        }

        static Resolution failed(String error)
        {
            return new Resolution(null, error);
        }
    }
}
