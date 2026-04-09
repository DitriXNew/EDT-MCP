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
            + "Either pass threadId (from wait_for_break) or applicationId."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty("threadId", "Thread id from wait_for_break") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id (resumes all threads of this target)") //$NON-NLS-1$ //$NON-NLS-2$
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
        long threadId = parseLong(params.get("threadId")); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();

        try
        {
            if (threadId > 0)
            {
                IThread thread = registry.getThread(threadId);
                if (thread == null)
                {
                    return ToolResult.error("stale threadId").toJson(); //$NON-NLS-1$
                }
                if (!thread.canResume())
                {
                    return ToolResult.error("thread cannot resume (state: " //$NON-NLS-1$
                            + (thread.isSuspended() ? "suspended" : "running") + ")").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                }
                thread.resume();
                return ToolResult.success().put("resumed", true).put("scope", "thread").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            if (applicationId != null && !applicationId.isEmpty())
            {
                IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
                if (target == null)
                {
                    return ToolResult.error("no active debug target for applicationId: " + applicationId).toJson(); //$NON-NLS-1$
                }
                if (!target.canResume())
                {
                    return ToolResult.error("debug target cannot resume").toJson(); //$NON-NLS-1$
                }
                target.resume();
                return ToolResult.success().put("resumed", true).put("scope", "target").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
            return ToolResult.error("Provide either threadId or applicationId").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in resume", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static long parseLong(String s)
    {
        if (s == null || s.isEmpty()) return -1L;
        try {
            double d = Double.parseDouble(s.trim());
            if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE) return -1L;
            return (long) d;
        } catch (NumberFormatException nfe) { return -1L; }
    }
}
