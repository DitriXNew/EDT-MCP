/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.List;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;

/**
 * The refusals this feature is allowed to produce, in one place, so that every comparison tool says
 * the same thing about the same situation.
 *
 * <h2>Why a shared vocabulary</h2>
 * Three tools observe the same four situations — no comparison service, a comparison already
 * running, an id that no longer names anything, and a platform failure. Written per tool they drift
 * within a release, and the drift is not cosmetic: "already running" is the message that decides
 * whether the caller waits, cancels, or gives up, so it has to name the live comparison and the way
 * out every single time.
 *
 * <h2>What it delegates</h2>
 * The TEXT of a platform failure comes from {@link PlatformFailures}: EDT reports failures as
 * {@code IStatus} trees whose most informative message is frequently not {@code getMessage()}, and
 * that selection problem is already solved. This class only decides which situation is being
 * described and what the caller should do about it.
 *
 * <h2>What it refuses to say</h2>
 * A comparison that reports nothing is not a comparison that found nothing, and a session EDT has
 * forgotten is not a session that finished. Each message below states what was OBSERVED and names
 * the next step; none of them turns an absence of information into a result.
 */
public final class ComparisonFailures
{
    private ComparisonFailures()
    {
        // Utility class
    }

    /**
     * The most informative text a platform failure carries, with any leaked object identity
     * scrubbed out.
     *
     * @param failure the failure (may be {@code null})
     * @return a non-blank description, never {@code null}
     */
    public static String describe(Throwable failure)
    {
        return PlatformFailures.withoutObjectIdentity(PlatformFailures.describe(failure));
    }

    /**
     * EDT's comparison service is not registered — the plugin is starting, stopping, or running in
     * an EDT build that does not carry the comparison bundles.
     *
     * @return the refusal
     */
    public static ToolResult serviceUnavailable()
    {
        return ToolResult.error("EDT's configuration-comparison service is not available in this " //$NON-NLS-1$
            + "workbench. Wait until EDT has finished starting and try again; if it never becomes " //$NON-NLS-1$
            + "available, this EDT installation does not carry the comparison bundles."); //$NON-NLS-1$
    }

    /**
     * A comparison is already running. EDT allows exactly ONE per instance and a second launch
     * fails rather than queueing, so the caller is told which comparison holds the slot and how to
     * end it — never left to retry into the same wall.
     * <p>
     * BOTH remedies are named because neither one covers the whole situation: {@code cancel_job}
     * ends a comparison that is still RUNNING, and it cannot end one that has finished — that
     * job is terminal, and a terminal job is answered with ALREADY_TERMINAL without the owning
     * tool's handler ever running. A finished comparison is given back by
     * {@code compare_configurations} with {@code releaseComparisonId}. Naming only the first
     * would send the caller of the commoner case at the one action proven not to work.
     *
     * @param liveComparisonId the id of the comparison holding the slot, or {@code null} when this
     *     server did not start it (it can be started from the EDT user interface, and then we know
     *     only that the slot is taken)
     * @return the refusal
     */
    public static ToolResult alreadyRunning(String liveComparisonId)
    {
        if (liveComparisonId == null || liveComparisonId.isEmpty())
        {
            return ToolResult.error("EDT is already running a comparison that this server did not " //$NON-NLS-1$
                + "start - it allows one at a time and a second one is refused rather than queued. " //$NON-NLS-1$
                + "End the running comparison in EDT (close its comparison editor) and try again."); //$NON-NLS-1$
        }
        return ToolResult.error("EDT is already running comparison '" + liveComparisonId //$NON-NLS-1$
            + "' - it allows one at a time and a second one is refused rather than queued. End " //$NON-NLS-1$
            + "that one first: while it is still running, cancel_job on the job that started " //$NON-NLS-1$
            + "it (get_job_status lists the id); once it has FINISHED, cancel_job can no " //$NON-NLS-1$
            + "longer end it - call compare_configurations with releaseComparisonId='" //$NON-NLS-1$
            + liveComparisonId + "' instead. Then start this comparison again."); //$NON-NLS-1$
    }

    /**
     * The caller quoted a {@code comparisonId} that names nothing any more.
     *
     * @param comparisonId the value the caller passed
     * @param liveIds the ids that ARE registered right now (possibly empty)
     * @return the refusal
     */
    public static ToolResult unknownComparison(String comparisonId, List<String> liveIds)
    {
        StringBuilder message = new StringBuilder();
        message.append("Comparison '").append(comparisonId) //$NON-NLS-1$
            .append("' is not running. It either never existed, was cancelled, or was released ") //$NON-NLS-1$
            .append("after sitting idle."); //$NON-NLS-1$
        if (liveIds == null || liveIds.isEmpty())
        {
            message.append(" No comparison is running right now - start one with compare_configurations."); //$NON-NLS-1$
        }
        else
        {
            message.append(" Running now: ").append(String.join(", ", liveIds)).append('.'); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return ToolResult.error(message.toString());
    }

    /**
     * The comparison failed. This is reachable ONLY through
     * {@code CompareMergeProcessBatch.getFailureCause()}: the process status has no failure
     * literal, so a caller that trusts the status alone sees a dead comparison as a running one.
     *
     * @param cause the failure the batch carried
     * @return the refusal
     */
    public static ToolResult comparisonFailed(Throwable cause)
    {
        return ToolResult.error("The comparison failed: " + describe(cause) //$NON-NLS-1$
            + ". Nothing was written. Check that both revisions exist and that the project is " //$NON-NLS-1$
            + "fully loaded (list_projects reports readiness), then start it again."); //$NON-NLS-1$
    }

    /**
     * A comparison was registered here but EDT no longer holds it — it was cancelled elsewhere, or
     * EDT restarted its session.
     *
     * @param comparisonId the id the caller quoted
     * @return the refusal
     */
    public static ToolResult sessionGone(String comparisonId)
    {
        return ToolResult.error("Comparison '" + comparisonId //$NON-NLS-1$
            + "' is no longer held by EDT - it was ended outside this server, so its comparison " //$NON-NLS-1$
            + "tree can no longer be read. Start a new comparison with compare_configurations."); //$NON-NLS-1$
    }

    /**
     * The comparison is still registered here, but EDT's comparison service could not be asked for
     * its tree right now.
     * <p>
     * A DIFFERENT situation from {@link #sessionGone(String)} and the difference is the whole
     * reason this exists. "EDT no longer knows this handle" is an answer EDT gave, and it entitles
     * a caller to say the comparison was ended outside this server; "the service could not be
     * asked" is a fact about this server's reach at one instant, and saying the first when the
     * second happened tells the caller their comparison is destroyed when it is merely unreadable
     * for a moment. The two used to be folded together by an {@code orElse(null)} on the view.
     * <p>
     * So the remedy differs too: this one is RETRYABLE and the comparison keeps its slot, its
     * session and its node ids.
     *
     * @param comparisonId the id the caller quoted
     * @return the refusal
     */
    public static ToolResult readUnavailable(String comparisonId)
    {
        return ToolResult.error("EDT's configuration-comparison service could not be asked for " //$NON-NLS-1$
            + "comparison '" + comparisonId + "' just now, so its tree was not read. The " //$NON-NLS-1$ //$NON-NLS-2$
            + "comparison is still registered and still holds EDT's single comparison slot - " //$NON-NLS-1$
            + "nothing was ended and its nodeIds still resolve. Wait until EDT has finished " //$NON-NLS-1$
            + "starting and read it again with get_comparison_node; release it with " //$NON-NLS-1$
            + "compare_configurations releaseComparisonId='" + comparisonId //$NON-NLS-1$
            + "' when you no longer want it."); //$NON-NLS-1$
    }

    /**
     * The platform threw while the tool was doing something specific. The action is named because
     * "the comparison broke" and "reading node 42 broke" send the caller to different places.
     *
     * @param action what was being attempted, as a short phrase (e.g. {@code "reading node 42"})
     * @param cause the platform failure
     * @return the refusal
     */
    public static ToolResult failed(String action, Throwable cause)
    {
        return ToolResult.error("Failed while " + action + ": " + describe(cause)); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
