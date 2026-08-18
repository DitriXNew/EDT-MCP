/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.progress.IProgressConstants;

/**
 * Schedules the background {@link Job}s this server runs on the caller's behalf.
 *
 * <h2>Why this exists</h2>
 * A workbench job that finishes with an {@code ERROR} status is reported by Eclipse itself: the
 * progress manager hands the result to the status manager with SHOW, which opens a MODAL error
 * dialog with a stack trace in it. That is the right default for something a human just clicked;
 * it is the wrong default for work an MCP client asked for, because the human sitting in front of
 * EDT gets an unexplained Eclipse error box for an operation they did not start, while the agent
 * that DID start it gets its answer through the protocol either way.
 *
 * <p>Setting {@code NO_IMMEDIATE_ERROR_PROMPT_PROPERTY} keeps the failure exactly where a
 * background job's failure belongs — attached to the job in the Progress view, still openable by
 * the user — and suppresses only the unrequested modal. It changes nothing about what the job
 * returns, what is logged, or what the tool reports.
 */
public final class McpJobs
{
    private McpJobs()
    {
        // Utility class
    }

    /**
     * Schedules a job whose failure must not raise a modal dialog in the user's workbench.
     *
     * @param job the job to schedule (never {@code null})
     */
    public static void schedule(Job job)
    {
        markNoErrorDialog(job);
        job.schedule();
    }

    /**
     * Marks a job so Eclipse reports an {@code ERROR} result in the Progress view instead of an
     * immediate modal dialog. Must be set before the job finishes; scheduling through
     * {@link #schedule(Job)} is the normal way to get it right.
     *
     * @param job the job to mark (never {@code null})
     * @return the same job, for chaining
     */
    public static Job markNoErrorDialog(Job job)
    {
        job.setProperty(IProgressConstants.NO_IMMEDIATE_ERROR_PROMPT_PROPERTY, Boolean.TRUE);
        return job;
    }
}
