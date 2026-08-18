/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.ditrix.edt.mcp.server.protocol.GsonProvider;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.JobSnapshot;
import com.ditrix.edt.mcp.server.utils.BackgroundJobs.ProgressEntry;
import com.ditrix.edt.mcp.server.utils.WorkmateGateway.WorkmateResponse;

/** Renders the canonical Markdown snapshot shared by job starters and polling tools. */
public final class BackgroundJobRenderer
{
    private BackgroundJobRenderer()
    {
        // Utility class
    }

    /**
     * Renders state, ownership, timing, progress, and the terminal result of any background job.
     *
     * @param job point-in-time snapshot
     * @return Markdown report
     */
    public static String render(JobSnapshot job)
    {
        WorkmateResponse workmateResponse = job.getResult() instanceof WorkmateResponse
            ? (WorkmateResponse)job.getResult() : null;
        StringBuilder result = new StringBuilder("# Background job: ") //$NON-NLS-1$
            .append(job.getStatus().value()).append("\n\n"); //$NON-NLS-1$

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("jobId", job.getId()); //$NON-NLS-1$
        summary.put("owningTool", job.getOwningTool()); //$NON-NLS-1$
        summary.put("status", job.getStatus().value()); //$NON-NLS-1$
        summary.put("elapsed", formatElapsed(job.getElapsedMs())); //$NON-NLS-1$
        summary.put("startedAt", Instant.ofEpochMilli(job.getStartedAtMs()).toString()); //$NON-NLS-1$
        if (job.getCompletedAtMs() > 0)
        {
            summary.put("completedAt", //$NON-NLS-1$
                Instant.ofEpochMilli(job.getCompletedAtMs()).toString());
        }
        if (workmateResponse != null && workmateResponse.getAssistantMessageCount() != null)
        {
            summary.put("assistantMessages", //$NON-NLS-1$
                workmateResponse.getAssistantMessageCount().toString());
        }
        result.append(MarkdownUtils.keyValueTable("Field", "Value", summary)); //$NON-NLS-1$ //$NON-NLS-2$

        result.append("\n## Progress\n\n"); //$NON-NLS-1$
        for (ProgressEntry entry : job.getProgress())
        {
            result.append("- `") //$NON-NLS-1$
                .append(Instant.ofEpochMilli(entry.getTimestampMs()).toString())
                .append("` — ") //$NON-NLS-1$
                .append(MarkdownUtils.escapeMarkdown(entry.getMessage()))
                .append('\n');
        }

        if (job.getStatus() == BackgroundJobs.Status.DONE)
        {
            appendResult(result, job.getResult(), workmateResponse);
        }
        else if (job.getStatus() == BackgroundJobs.Status.FAILED)
        {
            result.append("\n## Error\n\n") //$NON-NLS-1$
                .append(job.getErrorMessage());
        }
        else if (job.getStatus() == BackgroundJobs.Status.CANCELLED)
        {
            result.append("\n## Cancellation\n\n") //$NON-NLS-1$
                .append(job.getResult() == null
                    ? "The job was cancelled before its owning tool handed the work over." //$NON-NLS-1$
                    : job.getResult());
        }
        return result.toString();
    }

    private static void appendResult(StringBuilder target, Object rawResult,
        WorkmateResponse workmateResponse)
    {
        if (workmateResponse != null)
        {
            // Said BEFORE the answer, not after it: whether Workmate declared itself finished
            // changes how the text should be read, and a note under a long answer is missed.
            if (!workmateResponse.isDeclaredFinal())
            {
                target.append("\n> **Completion not confirmed.** ") //$NON-NLS-1$
                    .append(workmateResponse.wentQuiet()
                        ? "Workmate stopped answering and the conversation was wound up " //$NON-NLS-1$
                            + "without its end-of-answer marker" //$NON-NLS-1$
                        : "Workmate never sent its end-of-answer marker") //$NON-NLS-1$
                    .append(", so this is the last text it produced rather than an answer " //$NON-NLS-1$
                        + "it called complete. It may be partial.\n"); //$NON-NLS-1$
            }
            target.append("\n## Answer\n\n").append(trimToNull(workmateResponse.getText())); //$NON-NLS-1$
            String reasoning = trimToNull(workmateResponse.getReasoning());
            if (reasoning != null)
            {
                target.append("\n\n## Reasoning\n\n").append(reasoning); //$NON-NLS-1$
            }
            return;
        }

        target.append("\n## Result\n\n"); //$NON-NLS-1$
        if (rawResult == null)
        {
            target.append("(no result)"); //$NON-NLS-1$
        }
        else if (rawResult instanceof CharSequence)
        {
            target.append(rawResult);
        }
        else
        {
            target.append("```json\n") //$NON-NLS-1$
                .append(GsonProvider.toJson(rawResult))
                .append("\n```"); //$NON-NLS-1$
        }
    }

    private static String formatElapsed(long elapsedMs)
    {
        if (elapsedMs < 1000L)
        {
            return elapsedMs + " ms"; //$NON-NLS-1$
        }
        long seconds = elapsedMs / 1000L;
        long millis = elapsedMs % 1000L;
        return seconds + "." + String.format("%03d", Long.valueOf(millis)) + " s"; //$NON-NLS-1$ //$NON-NLS-2$
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
