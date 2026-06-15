/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugTargetResolver;

/**
 * Resumes a suspended debug thread (or, if {@code applicationId} is given,
 * resumes all threads of the matching debug target).
 *
 * <p>If neither parameter is given and there is exactly one active debug launch,
 * that launch is used as a fallback — useful for Attach configurations whose
 * synthetic applicationId is not known to the caller, and for the common
 * one-session workflow.
 */
public class ResumeTool implements IMcpTool
{
    public static final String NAME = "resume"; //$NON-NLS-1$

    /** Output key: resume scope ('thread' or 'target'). */
    private static final String KEY_SCOPE = "scope"; //$NON-NLS-1$

    /** Output key: whether the thread or target was resumed. */
    private static final String KEY_RESUMED = "resumed"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Resume a suspended debug thread or all threads of a debug target. " //$NON-NLS-1$
            + "Pass threadId (from wait_for_break) or applicationId. applicationId accepts ANY id " //$NON-NLS-1$
            + "form for the session: the real id, 'attach:<name>', 'launch:<name>', or " //$NON-NLS-1$
            + "'ServerApplication.<app>'. For a server-side suspend, resume targets the suspended " //$NON-NLS-1$
            + "thread directly. With no arguments, resumes the single active debug session (launch " //$NON-NLS-1$
            + "or server target) if exactly one exists. NOTE: if resume of a server-side suspend " //$NON-NLS-1$
            + "does not take effect, the breakpoint can also be released from the EDT UI."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("threadId", "Thread id from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id (real, 'attach:<configName>', 'launch:<configName>', or " //$NON-NLS-1$
                    + "'ServerApplication.<app>'). Resumes this session's target/suspended thread. " //$NON-NLS-1$
                    + "Optional if exactly one debug session is active.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_RESUMED, "Whether the thread or target was resumed") //$NON-NLS-1$
            .stringProperty(KEY_SCOPE, "Resume scope: 'thread' or 'target'") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the resumed debug target") //$NON-NLS-1$
            .booleanProperty("autoResolved", "Whether the lone active session was auto-resolved") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("serverTarget", "True if resolved via the 1C debug-server target bridge") //$NON-NLS-1$ //$NON-NLS-2$
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
        long threadId = JsonUtils.extractLongArgument(params, "threadId", -1L); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        try
        {
            if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("stale threadId — call wait_for_break again").toJson(); //$NON-NLS-1$
                }
                if (!thread.canResume())
                {
                    return ToolResult.error("thread cannot resume (state: " //$NON-NLS-1$
                            + (thread.isSuspended() ? "suspended" : "running") + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                // Canonical snapshot key for this thread's session: the registry's
                // thread→appId mapping (set when the snapshot was created), falling
                // back to the launch-attribute id. Computed BEFORE resuming — the
                // async RESUME event may purge the mapping.
                String threadAppId = registry.getThreadApplicationId(threadId);
                if (threadAppId == null)
                {
                    threadAppId = DebugSessionRegistry.findApplicationIdFor(thread);
                }
                thread.resume();
                // Drop the now-stale pre-resume snapshot (this branch
                // previously resumed WITHOUT any clearSnapshot, so the snapshot
                // outlived its suspend and the next wait_for_break returned it as a
                // fresh hit). clearSnapshot(null) is a safe no-op.
                registry.clearSnapshot(threadAppId);
                return ToolResult.success().put(KEY_RESUMED, true).put(KEY_SCOPE, "thread").toJson(); //$NON-NLS-1$
            }

            // Unified resolution: accept ANY id form for the same session —
            // real ATTR_APPLICATION_ID, attach:<name>, launch:<name>,
            // ServerApplication.<app>, the bare app name, the debug server URL.
            // A blank id auto-resolves the single active session (launch or server).
            DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(applicationId);
            if (res == null)
            {
                if (applicationId != null && !applicationId.isEmpty())
                {
                    return ToolResult.error("no active debug target for applicationId: " + applicationId).toJson(); //$NON-NLS-1$
                }
                return ToolResult.error("Provide threadId or applicationId — no single active debug " //$NON-NLS-1$
                    + "session available for auto-resolution. Use debug_status to list active sessions.").toJson(); //$NON-NLS-1$
            }

            IDebugTarget target = res.target;
            String effectiveAppId = res.canonicalId;

            // Prefer resuming at the TARGET level when it accepts it (thin-client and
            // launch targets do). A 1C debug-server target, however, reports
            // canResume()==false at the target level even while a thread is genuinely
            // suspended on the breakpoint — so fall back to resuming each suspended
            // thread (IRuntimeDebugTargetThread implements ISuspendResume), which is the
            // SAME thread wait_for_break suspends on.
            boolean resumed = false;
            String scope = null;
            if (!target.isTerminated() && target.canResume())
            {
                target.resume();
                resumed = true;
                scope = "target"; //$NON-NLS-1$
            }
            else
            {
                int count = 0;
                for (IThread t : target.getThreads())
                {
                    if (t != null && t.isSuspended() && t.canResume())
                    {
                        t.resume();
                        count++;
                    }
                }
                if (count > 0)
                {
                    resumed = true;
                    scope = "thread"; //$NON-NLS-1$
                }
            }

            if (!resumed)
            {
                return ToolResult.error(res.isServerTarget()
                    ? "server debug target cannot resume (no suspended thread to resume). " //$NON-NLS-1$
                        + "If a breakpoint is still held, it can also be released from the EDT UI."
                    : "debug target cannot resume (no suspended thread to resume)").toJson(); //$NON-NLS-1$
            }

            // Drop the cached snapshot so a subsequent wait_for_break does not return
            // the now-stale pre-resume frame.
            registry.clearSnapshot(effectiveAppId);
            ToolResult out = ToolResult.success()
                .put(KEY_RESUMED, true)
                .put(KEY_SCOPE, scope)
                .put(McpKeys.APPLICATION_ID, effectiveAppId);
            if (res.isServerTarget())
            {
                out.put("serverTarget", true); //$NON-NLS-1$
            }
            if (res.autoResolved)
            {
                out.put("autoResolved", true); //$NON-NLS-1$
            }
            return out.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in resume", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

}
