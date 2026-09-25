/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;
import org.eclipse.emf.common.util.URI;

import com._1c.g5.v8.dt.debug.core.model.IBslStackFrame;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * The suspended-thread snapshot shared by every debug tool that hands out a stop point
 * ({@code wait_for_break}, {@code step}, {@code debug_pause}): thread id and name, the stack
 * frames with stable {@code frameRef}s, and {@code topFrameRef}.
 */
public final class DebugSnapshotResponse
{
    private static final String KEY_AUTO_RESOLVED = "autoResolved"; //$NON-NLS-1$

    private static final String KEY_SERVER_TARGET = "serverTarget"; //$NON-NLS-1$

    private DebugSnapshotResponse()
    {
        // utility class
    }

    /**
     * Adds the snapshot of a suspended thread to {@code result}. Every stack frame is registered
     * in the registry so follow-up tools (get_variables, evaluate_expression, step) can address it
     * by {@code frameRef}. {@code autoResolved} and {@code serverTarget} are emitted only when true.
     *
     * @param result the result to extend (its own outcome keys are left untouched)
     * @param snapshot the suspend snapshot to render
     * @param registry the registry that issues the frame references
     * @param applicationId the canonical id of the debug session
     * @param autoResolved whether the session was auto-resolved from a blank id
     * @param serverTarget whether the session was resolved via the 1C debug-server bridge
     * @return {@code result}, for chaining
     * @throws DebugException if the thread's stack cannot be read
     */
    public static ToolResult appendSnapshot(ToolResult result, DebugSessionRegistry.SuspendSnapshot snapshot,
        DebugSessionRegistry registry, String applicationId, boolean autoResolved, boolean serverTarget)
        throws DebugException
    {
        IThread thread = snapshot.thread;
        List<Map<String, Object>> frames = new ArrayList<>();
        IStackFrame[] stackFrames = thread.getStackFrames();
        for (int i = 0; i < stackFrames.length; i++)
        {
            IStackFrame f = stackFrames[i];
            long frameRef = registry.registerFrame(f);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("frameIndex", i); //$NON-NLS-1$
            dto.put("frameRef", frameRef); //$NON-NLS-1$
            dto.put("name", f.getName()); //$NON-NLS-1$
            try
            {
                dto.put("line", f.getLineNumber()); //$NON-NLS-1$
            }
            catch (Exception ex)
            {
                // ignore
            }
            putSourceLocation(dto, f);
            frames.add(dto);
        }
        result.put("threadId", snapshot.threadId) //$NON-NLS-1$
            .put("threadName", thread.getName()) //$NON-NLS-1$
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put("frames", frames); //$NON-NLS-1$
        if (autoResolved)
        {
            result.put(KEY_AUTO_RESOLVED, true);
        }
        if (serverTarget)
        {
            result.put(KEY_SERVER_TARGET, true);
        }
        if (!frames.isEmpty())
        {
            result.put("topFrameRef", frames.get(0).get("frameRef")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return result;
    }

    /**
     * Best-effort: resolves a BSL frame's source file and adds {@code modulePath}
     * (relative to {@code src/}) and {@code project} to the DTO so the caller can
     * chain straight into {@code set_breakpoint} / {@code read_module_source}. A
     * non-BSL frame, a null source or a source outside {@code src/} is silently
     * skipped — the {@code line}/{@code name} fields still describe the frame.
     */
    private static void putSourceLocation(Map<String, Object> dto, IStackFrame f)
    {
        if (!(f instanceof IBslStackFrame))
        {
            return;
        }
        URI source;
        try
        {
            source = ((IBslStackFrame) f).getSource();
        }
        catch (Exception ex)
        {
            return;
        }
        if (source == null || !source.isPlatformResource())
        {
            return;
        }
        // EDT module sources are platform-resource URIs laid out as
        // /<project>/src/<modulePath>. Decode via toPlatformString(true): a plain
        // URI.toString() leaves segments percent-encoded, which would corrupt the
        // Cyrillic project/module names that set_breakpoint / read_module_source
        // expect decoded.
        String platformPath = source.toPlatformString(true);
        if (platformPath == null)
        {
            return;
        }
        String marker = "/" + BslModuleUtils.SOURCE_FOLDER + "/"; //$NON-NLS-1$ //$NON-NLS-2$
        int idx = platformPath.indexOf(marker);
        if (idx <= 0)
        {
            // No project segment before /src/ — not a resolvable workspace module.
            return;
        }
        dto.put("modulePath", platformPath.substring(idx + marker.length())); //$NON-NLS-1$
        String project = platformPath.substring(0, idx);
        if (project.startsWith("/")) //$NON-NLS-1$
        {
            project = project.substring(1);
        }
        if (!project.isEmpty())
        {
            dto.put("project", project); //$NON-NLS-1$
        }
    }
}
