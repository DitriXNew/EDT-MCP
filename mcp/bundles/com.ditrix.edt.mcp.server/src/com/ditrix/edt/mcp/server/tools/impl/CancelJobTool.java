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
import com.ditrix.edt.mcp.server.utils.BackgroundJobRenderer;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationOutcome;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.CancellationResult;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;

/** Confirm-preview cancellation surface shared by every {@link BackgroundJobs} owner. */
public class CancelJobTool implements IMcpTool
{
    public static final String NAME = "cancel_job"; //$NON-NLS-1$

    private static final String KEY_JOB_ID = "jobId"; //$NON-NLS-1$
    private static final String KEY_CONFIRM = "confirm"; //$NON-NLS-1$

    private final BackgroundJobs jobs;

    public CancelJobTool()
    {
        this(BackgroundJobs.shared());
    }

    CancelJobTool(BackgroundJobs jobs)
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
        return "Preview or cancel a background job by jobId. Destructive: omitting confirm or " //$NON-NLS-1$
            + "passing confirm=false only describes the owning tool, state, and progress; " //$NON-NLS-1$
            + "confirm=true cancels uncommitted work, or invokes an owning tool's declared " //$NON-NLS-1$
            + "destructive cancellation capability. Committed jobs without that capability " //$NON-NLS-1$
            + "remain in flight. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('cancel_job')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(KEY_JOB_ID,
                "Background job id returned by the tool that started the job.", true) //$NON-NLS-1$
            .booleanProperty(KEY_CONFIRM,
                "true = request cancellation, including an owner-declared destructive stop when " //$NON-NLS-1$
                    + "available; false or omitted = preview only, with no change.") //$NON-NLS-1$
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

        boolean confirm = JsonUtils.extractBooleanArgument(params, KEY_CONFIRM, false);
        if (!confirm)
        {
            JobSnapshot snapshot = jobs.get(jobId);
            if (snapshot == null)
            {
                return unknownJobError(jobId);
            }
            String capabilityWarning = snapshot.getCancellationPreview();
            String destructivePreview = capabilityWarning == null ? "" //$NON-NLS-1$
                : "\n\n**Destructive committed-run cancellation:** " //$NON-NLS-1$
                    + capabilityWarning;
            return "# Background job cancellation: preview\n\n" //$NON-NLS-1$
                + "No change was made. This would request cancellation of job `" + jobId //$NON-NLS-1$
                + "`, owned by `" + snapshot.getOwningTool() + "`, while its current state is `" //$NON-NLS-1$ //$NON-NLS-2$
                + snapshot.getStatus().value() + "`." + destructivePreview //$NON-NLS-1$
                + "\n\nRe-call cancel_job with the same jobId and " //$NON-NLS-1$
                + "confirm=true to act.\n\n" //$NON-NLS-1$
                + BackgroundJobRenderer.render(snapshot);
        }

        CancellationResult cancellation = jobs.cancel(jobId);
        if (cancellation == null)
        {
            return unknownJobError(jobId);
        }
        return renderOutcome(cancellation);
    }

    private static String renderOutcome(CancellationResult cancellation)
    {
        JobSnapshot snapshot = cancellation.getSnapshot();
        CancellationOutcome outcome = cancellation.getOutcome();
        String message;
        if (outcome == CancellationOutcome.CANCELLED)
        {
            message = "The job was cancelled before `" + snapshot.getOwningTool() //$NON-NLS-1$
                + "` handed its work over. No external request from this job remains to be " //$NON-NLS-1$
                + "recalled."; //$NON-NLS-1$
        }
        else if (outcome == CancellationOutcome.TERMINATED)
        {
            message = "The committed job was stopped through the destructive cancellation " //$NON-NLS-1$
                + "capability declared by `" + snapshot.getOwningTool() //$NON-NLS-1$
                + "`. Read the cancellation section below for the effects and any partial " //$NON-NLS-1$
                + "result. Never treat a terminated run as a clean outcome."; //$NON-NLS-1$
            if (snapshot.getResult() == null && cancellation.getDetail() != null)
            {
                // The launch may be proven stopped before the worker exits. Its result cannot be
                // published on the non-terminal job yet, but the cancellation caller must still
                // receive the owner's partial-report/no-rollback explanation immediately.
                message += "\n\n" + cancellation.getDetail(); //$NON-NLS-1$
            }
        }
        else if (outcome == CancellationOutcome.ALREADY_COMMITTED)
        {
            message = cancellation.getDetail() != null
                ? cancellation.getDetail() + " Continue polling this job with get_job_status " //$NON-NLS-1$
                    + "and do not start a duplicate job." //$NON-NLS-1$
                : "The job was NOT cancelled: `" + snapshot.getOwningTool() //$NON-NLS-1$
                    + "` had already handed the work over, so the underlying request cannot be " //$NON-NLS-1$
                    + "recalled. Continue polling this job with get_job_status and do not start a " //$NON-NLS-1$
                    + "duplicate job."; //$NON-NLS-1$
        }
        else
        {
            message = "No cancellation was performed because the job was already terminal in " //$NON-NLS-1$
                + "state `" + snapshot.getStatus().value() + "`. Start a new job with `" //$NON-NLS-1$ //$NON-NLS-2$
                + snapshot.getOwningTool() + "` only if the work must run again."; //$NON-NLS-1$
        }

        return "# Background job cancellation: " + outcome.value() + "\n\n" //$NON-NLS-1$ //$NON-NLS-2$
            + message + "\n\n" + BackgroundJobRenderer.render(snapshot); //$NON-NLS-1$
    }

    private static String unknownJobError(String jobId)
    {
        return ToolResult.error("Unknown or expired jobId '" + jobId //$NON-NLS-1$
            + "'. Start a new job with the tool that originally created it, then pass the new " //$NON-NLS-1$
            + "jobId to cancel_job.").toJson(); //$NON-NLS-1$
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
