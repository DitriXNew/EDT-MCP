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
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;

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

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Resume a suspended debug thread or all threads of a debug target. " //$NON-NLS-1$
            + "Pass threadId (from wait_for_break) or applicationId. " //$NON-NLS-1$
            + "With no arguments, resumes the single active debug launch if exactly one exists."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("threadId", "Thread id from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application id (real or 'attach:<configName>' — resumes all threads of this target)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("resumed", "Whether the thread or target was resumed") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("scope", "Resume scope: 'thread' or 'target'") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id of the resumed debug target") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("autoResolved", "Whether the lone active launch was auto-resolved") //$NON-NLS-1$ //$NON-NLS-2$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

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
                thread.resume();
                return ToolResult.success().put("resumed", true).put("scope", "thread").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }

            String effectiveAppId = (applicationId != null && !applicationId.isEmpty())
                ? applicationId
                : DebugSessionRegistry.findLoneActiveApplicationId();

            if (effectiveAppId == null)
            {
                return ToolResult.error("Provide threadId or applicationId — no single active debug " //$NON-NLS-1$
                    + "launch available for auto-resolution. Use debug_status to list active launches.").toJson(); //$NON-NLS-1$
            }

            IDebugTarget target = DebugSessionRegistry.findActiveTarget(effectiveAppId);
            if (target == null)
            {
                return ToolResult.error("no active debug target for applicationId: " + effectiveAppId).toJson(); //$NON-NLS-1$
            }
            if (target.canResume())
            {
                target.resume();
            }
            else
            {
                // 1C debug targets resume at the THREAD granularity: the target itself
                // can report canResume()==false while one of its threads is suspended at
                // a breakpoint. Fall back to resuming each suspended thread so the
                // documented no-argument / applicationId convenience actually releases a
                // 1C session instead of dead-ending at "debug target cannot resume".
                int resumed = 0;
                for (IThread t : target.getThreads())
                {
                    if (t.canResume())
                    {
                        t.resume();
                        resumed++;
                    }
                }
                if (resumed == 0)
                {
                    return ToolResult.error("debug target cannot resume (no suspended thread to resume)").toJson(); //$NON-NLS-1$
                }
            }
            ToolResult res = ToolResult.success()
                .put("resumed", true) //$NON-NLS-1$
                .put("scope", "target") //$NON-NLS-1$ //$NON-NLS-2$
                .put("applicationId", effectiveAppId); //$NON-NLS-1$
            if (applicationId == null || applicationId.isEmpty())
            {
                res.put("autoResolved", true); //$NON-NLS-1$
            }
            return res.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in resume", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

}
