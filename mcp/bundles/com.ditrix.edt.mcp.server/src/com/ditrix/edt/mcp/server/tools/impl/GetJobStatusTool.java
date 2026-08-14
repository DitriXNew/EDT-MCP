/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.BackgroundJobPolling;
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/** Read-only polling surface shared by every {@link BackgroundJobs} owner. */
public class GetJobStatusTool implements IMcpTool
{
    public static final String NAME = "get_job_status"; //$NON-NLS-1$

    private static final String KEY_JOB_ID = "jobId"; //$NON-NLS-1$
    private static final String KEY_WAIT_SECONDS = "waitSeconds"; //$NON-NLS-1$

    private final BackgroundJobs jobs;

    public GetJobStatusTool()
    {
        this(BackgroundJobs.shared());
    }

    GetJobStatusTool(BackgroundJobs jobs)
    {
        this.jobs = jobs;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Poll any background job by the jobId returned from its owning tool. Returns the " //$NON-NLS-1$
            + "current state, progress journal, and terminal result; optionally waits for a " //$NON-NLS-1$
            + "transport-safe interval. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('get_job_status')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_JOB_ID,
                "Background job id returned by the tool that started the job.", true) //$NON-NLS-1$
            .integerProperty(KEY_WAIT_SECONDS,
                "Maximum time this poll may wait for a terminal state, in seconds; defaults to " //$NON-NLS-1$
                    + AskWorkmateTool.DEFAULT_WAIT_SECONDS + " and accepts 0 to " //$NON-NLS-1$
                    + AskWorkmateTool.MAX_WAIT_SECONDS + ". Use 0 to return immediately.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArgument(params, KEY_JOB_ID,
            ". Pass the id returned by the tool that started the job."); //$NON-NLS-1$
        if (required != null)
        {
            return required;
        }

        String jobId = trimToNull(JsonUtils.extractStringArgument(params, KEY_JOB_ID));
        if (jobId == null)
        {
            return ToolResult.error("jobId must contain a non-empty background job id. Pass the " //$NON-NLS-1$
                + "id returned by the tool that started the job.").toJson(); //$NON-NLS-1$
        }

        Integer waitSeconds = BackgroundJobPolling.readWaitSeconds(params, KEY_WAIT_SECONDS,
            AskWorkmateTool.DEFAULT_WAIT_SECONDS, AskWorkmateTool.MAX_WAIT_SECONDS);
        if (waitSeconds == null)
        {
            return BackgroundJobPolling.waitSecondsError(KEY_WAIT_SECONDS,
                params.get(KEY_WAIT_SECONDS), AskWorkmateTool.DEFAULT_WAIT_SECONDS,
                AskWorkmateTool.MAX_WAIT_SECONDS);
        }

        JobSnapshot snapshot = BackgroundJobPolling.await(jobs, jobId, waitSeconds.intValue());
        if (snapshot == null)
        {
            return unknownJobError(jobId);
        }
        return BackgroundJobRenderer.render(snapshot);
    }

    static String unknownJobError(String jobId)
    {
        return ToolResult.error("Unknown or expired jobId '" + jobId //$NON-NLS-1$
            + "'. Start a new job with the tool that originally created it, then pass the new " //$NON-NLS-1$
            + "jobId to get_job_status.").toJson(); //$NON-NLS-1$
    }

    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
