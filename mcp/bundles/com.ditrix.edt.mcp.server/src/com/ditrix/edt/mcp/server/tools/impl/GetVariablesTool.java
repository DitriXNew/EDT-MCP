/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.debug.core.model.IVariable;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugTargetResolver;
import com.ditrix.edt.mcp.server.utils.VariableSerializer;

/**
 * Returns variables visible in a stack frame of a suspended thread. Frames can
 * be referenced either by {@code frameRef} (preferred — returned from
 * {@code wait_for_break}) or by {@code threadId + frameIndex} (re-resolved
 * against the live IThread).
 *
 * <p>If {@code expandPath} is supplied, walks the dot-separated path from frame
 * variables and returns its children instead — used to drill into Структуры,
 * Соответствия, Массивы, etc., without exploding the response on the first call.
 */
public class GetVariablesTool implements IMcpTool
{
    public static final String NAME = "get_variables"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Read variables from a stack frame of a suspended debug thread. " //$NON-NLS-1$
            + "Pass frameRef from wait_for_break (preferred) or threadId+frameIndex. " //$NON-NLS-1$
            + "Use expandPath to drill into nested structures (dot-separated)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("frameRef", "Stable frame reference returned from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("threadId", "Thread id (alternative to frameRef)") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("frameIndex", "0-based frame index when using threadId") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("expandPath", "Dot-separated path to expand a nested variable") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("variables", "Variables in the frame or expanded node, with name/value/type") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of variables returned") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        long frameRef = JsonUtils.extractLongArgument(params, "frameRef", -1L); //$NON-NLS-1$
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        int frameIndex = JsonUtils.extractIntArgument(params, "frameIndex", 0); //$NON-NLS-1$
        String expandPath = JsonUtils.extractStringArgument(params, "expandPath"); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();

        try
        {
            FrameResolution fr = resolveFrame(registry, frameRef, threadId, frameIndex);
            if (fr.error != null)
            {
                return fr.error;
            }
            return serializeVariables(registry, fr.frame, expandPath);
        }
        catch (Exception e)
        {
            Activator.logError("Error in get_variables", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the target stack frame from (in priority order) {@code frameRef},
     * {@code threadId + frameIndex}, or the auto-resolved single active debug
     * session. Returns a holder carrying either the resolved frame or the exact
     * error JSON the original inline branch produced — the caller re-checks
     * {@code error} and returns it unchanged.
     *
     * @return a {@link FrameResolution} with a non-null {@code frame} on success,
     *         or a non-null {@code error} (same value/case as before) otherwise
     */
    private static FrameResolution resolveFrame(DebugSessionRegistry registry, long frameRef, long threadId,
        int frameIndex)
        throws org.eclipse.debug.core.DebugException
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
    private static FrameResolution resolveByFrameRef(DebugSessionRegistry registry, long frameRef)
    {
        IStackFrame frame = registry.getFrame(frameRef);
        if (frame == null)
        {
            return FrameResolution.failed(
                ToolResult.error("stale frameRef — call wait_for_break again").toJson()); //$NON-NLS-1$
        }
        return FrameResolution.of(frame);
    }

    /** Resolves the {@code frameIndex}-th frame of the live thread {@code threadId}. */
    private static FrameResolution resolveByThreadId(DebugSessionRegistry registry, long threadId, int frameIndex)
        throws org.eclipse.debug.core.DebugException
    {
        IThread thread = registry.getThread(threadId);
        if (thread == null)
        {
            return FrameResolution.failed(
                ToolResult.error("stale threadId — call wait_for_break again").toJson()); //$NON-NLS-1$
        }
        IStackFrame[] frames = thread.getStackFrames();
        if (frameIndex < 0 || frameIndex >= frames.length)
        {
            return FrameResolution.failed(ToolResult.error("frameIndex out of range (0.." //$NON-NLS-1$
                + (frames.length - 1) + ")").toJson()); //$NON-NLS-1$
        }
        return FrameResolution.of(frames[frameIndex]);
    }

    /**
     * Fallback: auto-resolve the single active debug session through the SAME
     * blank-id policy every applicationId-based tool uses (DebugTargetResolver:
     * the lone Eclipse launch, else the lone server target) and read its snapshot
     * under the canonical key — replaces a hand-rolled condensed copy of that
     * policy. The frame index is clamped into range.
     */
    private static FrameResolution resolveByAutoSession(DebugSessionRegistry registry, int frameIndex)
        throws org.eclipse.debug.core.DebugException
    {
        DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(null);
        DebugSessionRegistry.SuspendSnapshot snap =
            res != null ? registry.getSnapshot(res.canonicalId) : null;
        if (snap == null)
        {
            return FrameResolution.failed(
                ToolResult.error("Provide frameRef or threadId — no single suspended debug " //$NON-NLS-1$
                    + "session available for auto-resolution. Call wait_for_break first.").toJson()); //$NON-NLS-1$
        }
        IStackFrame[] frames = snap.thread.getStackFrames();
        if (frames.length == 0)
        {
            return FrameResolution.failed(
                ToolResult.error("suspended thread has no stack frames").toJson()); //$NON-NLS-1$
        }
        return FrameResolution.of(frames[Math.min(Math.max(frameIndex, 0), frames.length - 1)]);
    }

    /**
     * Serializes either the children of the variable at {@code expandPath} (when
     * non-empty) or the whole frame, returning the success JSON — or the exact
     * "expandPath not found" error the inline branch produced.
     */
    private static String serializeVariables(DebugSessionRegistry registry, IStackFrame frame, String expandPath)
        throws Exception
    {
        List<Map<String, Object>> vars;
        if (expandPath != null && !expandPath.isEmpty())
        {
            IVariable resolved = VariableSerializer.resolvePath(frame, expandPath);
            if (resolved == null)
            {
                return ToolResult.error("expandPath not found: " + expandPath).toJson(); //$NON-NLS-1$
            }
            vars = VariableSerializer.serializeChildren(resolved, registry);
        }
        else
        {
            vars = VariableSerializer.serializeFrame(frame, registry);
        }
        return ToolResult.success()
            .put("variables", vars) //$NON-NLS-1$
            .put("count", vars.size()) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Holder threading a frame-resolution early-return out of {@link #resolveFrame}:
     * exactly one of {@code frame} / {@code error} is non-null. {@code error} carries
     * the same error JSON (same case) the inline branches returned.
     */
    private static final class FrameResolution
    {
        final IStackFrame frame;
        final String error;

        private FrameResolution(IStackFrame frame, String error)
        {
            this.frame = frame;
            this.error = error;
        }

        static FrameResolution of(IStackFrame frame)
        {
            return new FrameResolution(frame, null);
        }

        static FrameResolution failed(String error)
        {
            return new FrameResolution(null, error);
        }
    }

}
