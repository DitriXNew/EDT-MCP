/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.nio.file.Path;
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
     * <h2>The nameless case names TWO causes, because only one of them has an editor</h2>
     * When this server has nothing registered and EDT still reports its slot occupied, the
     * refusal used to assert a cause: a comparison started from EDT's own interface, to be ended
     * by closing its comparison editor. That is one of two states, and the advice is useless in
     * the other.
     * <p>
     * The other is a STALE FLAG, and it is reachable through this server's own tools. Measured
     * from {@code ComparisonManager} bytecode (EDT 2026.2, {@code com._1c.g5.v8.dt.compare}
     * 29.0.0): {@code activeComparisonBatch} - the field {@code hasActiveComparison()} reads - is
     * cleared in exactly one place, {@code comparisonFinished(batch)}, and the only caller of that
     * is {@code ComparisonProcessJob.run()}. Ending a comparison cancels that Eclipse job; cancel
     * it before Eclipse has started it and {@code run()} never executes, so the flag is never
     * cleared. The session IS discarded, so {@code getHandles} answers empty and nothing here can
     * name a comparison - and {@code comparisonFinished} is not on {@code IComparisonManager}, so
     * no code in this bundle can withdraw the flag either. Only restarting EDT clears it.
     * <p>
     * So the refusal states what was OBSERVED - EDT reports the slot taken, this server has
     * nothing registered - and gives the way out for each cause, rather than sending every caller
     * at a comparison editor that may not exist.
     *
     * @param liveComparisonId the id of the comparison holding the slot, or {@code null}/empty
     *     when this server has none registered - which is TWO states and not one, so the refusal
     *     names both instead of choosing: a comparison started from EDT's own interface, or a slot
     *     EDT still flags as taken after a comparison whose background job was cancelled before it
     *     began
     * @return the refusal
     */
    public static ToolResult alreadyRunning(String liveComparisonId)
    {
        if (liveComparisonId == null || liveComparisonId.isEmpty())
        {
            return ToolResult.error("EDT reports its single comparison slot occupied, but no " //$NON-NLS-1$
                + "comparison started through this server is registered - it allows one at a " //$NON-NLS-1$
                + "time and a second one is refused rather than queued. That is the whole of " //$NON-NLS-1$
                + "what was observed, and it has two causes. Either a comparison was started " //$NON-NLS-1$
                + "from EDT's own interface, in which case closing its comparison editor ends " //$NON-NLS-1$
                + "it; or EDT is left holding the flag for a comparison whose background job was " //$NON-NLS-1$
                + "cancelled before it began, in which case there is no comparison and no editor " //$NON-NLS-1$
                + "to close - EDT's comparison manager offers no way to withdraw the flag, and " //$NON-NLS-1$
                + "only restarting EDT clears it. Check EDT for an open comparison editor first."); //$NON-NLS-1$
        }
        return ToolResult.error("EDT is already running comparison '" + liveComparisonId //$NON-NLS-1$
            + "' - it allows one at a time and a second one is refused rather than queued. End " //$NON-NLS-1$
            + "that one first: while it is still running, cancel_job on the job that started " //$NON-NLS-1$
            + "it (get_job_status lists the id); once it has FINISHED, cancel_job can no " //$NON-NLS-1$
            + "longer end it - call compare_configurations with releaseComparisonId='" //$NON-NLS-1$
            + liveComparisonId + "' instead. Then start this comparison again."); //$NON-NLS-1$
    }

    /**
     * Refuses a launch because ANOTHER launch has already claimed EDT's single comparison slot and
     * is still preparing its comparison.
     * <p>
     * A separate refusal from {@link #alreadyRunning(String)}, because the remedy is different and
     * the id is not usable. There is no comparison yet: nothing to cancel, nothing to release, no
     * id a caller could quote - only a launch a few seconds ahead, which will either start its
     * comparison or give the slot back. Wording this as "already running" would send the caller to
     * {@code cancel_job} and {@code releaseComparisonId} for a comparison that does not exist.
     *
     * @param projectName the project the standing claim was taken for, or {@code null} when it is
     *     not known
     * @return the refusal
     */
    public static ToolResult launchInFlight(String projectName)
    {
        String project = projectName == null || projectName.isEmpty()
            ? "another project" //$NON-NLS-1$
            : "'" + projectName + "'"; //$NON-NLS-1$ //$NON-NLS-2$
        return ToolResult.error("Another compare_configurations call has already claimed EDT's " //$NON-NLS-1$
            + "single comparison slot for " + project + " and is still preparing its " //$NON-NLS-1$ //$NON-NLS-2$
            + "comparison - EDT allows one at a time and a second one is refused rather than " //$NON-NLS-1$
            + "queued. There is no comparison to cancel or release yet, so wait for that call to " //$NON-NLS-1$
            + "report its jobId - poll it with get_job_status - and start this one when it has " //$NON-NLS-1$
            + "finished, or when it has reported that it could not start."); //$NON-NLS-1$
    }

    /**
     * Upper-cases the first character, so a clause written to be embedded mid-sentence can also
     * open one.
     *
     * @param text the clause
     * @return the same text starting with a capital
     */
    private static String capitalise(String text)
    {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * What to say when this server holds no comparison of its own.
     * <p>
     * An empty local registry proves only that nothing was started THROUGH THIS SERVER. EDT's
     * single slot can be held by a comparison launched from the workbench, which is never
     * registered here - so "we have none" must not be rendered as "none is running", or the
     * caller is told to start one that the platform will then refuse. The three answers are kept
     * apart, "could not be asked" included, and this is the ONE place that words them: the same
     * claim used to live in two tools, and only one of them was corrected.
     *
     * @param edtHasActiveComparison EDT's own answer about its slot, or
     *            {@link PlatformAnswer#unavailable()} when the service could not be asked
     * @return the clause, without a trailing stop
     */
    public static String noKnownComparisonsText(PlatformAnswer<Boolean> edtHasActiveComparison)
    {
        PlatformAnswer<Boolean> active =
            edtHasActiveComparison == null ? PlatformAnswer.unavailable() : edtHasActiveComparison;
        if (active.isUnavailable())
        {
            return "no comparison started through this server is registered, and EDT's " //$NON-NLS-1$
                + "comparison service could not be asked whether one is running"; //$NON-NLS-1$
        }
        if (Boolean.TRUE.equals(active.orElse(Boolean.FALSE)))
        {
            return "no comparison started through this server is registered, but EDT reports " //$NON-NLS-1$
                + "one occupying its single comparison slot - it was started outside this " //$NON-NLS-1$
                + "server, so only EDT can address or end it"; //$NON-NLS-1$
        }
        return "no comparison started through this server is registered, and EDT reports none " //$NON-NLS-1$
            + "running"; //$NON-NLS-1$
    }

    /**
     * The caller quoted a {@code comparisonId} that names nothing any more.
     *
     * @param comparisonId the value the caller passed
     * @param liveIds the ids that ARE registered right now (possibly empty)
     * @param edtHasActiveComparison EDT's answer about its slot, used only when {@code liveIds} is
     *            empty - see {@link #noKnownComparisonsText}
     * @return the refusal
     */
    public static ToolResult unknownComparison(String comparisonId, List<String> liveIds,
        PlatformAnswer<Boolean> edtHasActiveComparison)
    {
        StringBuilder message = new StringBuilder();
        message.append("Comparison '").append(comparisonId) //$NON-NLS-1$
            .append("' is not running. It either never existed, was cancelled, or was released ") //$NON-NLS-1$
            .append("after sitting idle."); //$NON-NLS-1$
        if (liveIds == null || liveIds.isEmpty())
        {
            message.append(' ').append(capitalise(noKnownComparisonsText(edtHasActiveComparison)))
                .append('.');
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
     * Which refusal a comparison-tree read has earned, or {@code null} when the platform's answer
     * carries a readable tree.
     *
     * <h2>Three answers, not two, decided ONCE</h2>
     * "could not ask", "asked and got nothing" and "asked and got something" send the caller to
     * three different places, and only the third is a tree. Both tools that read a tree faced the
     * same fork and one of them still collapsed it with {@code orElse(null)} - so the decision
     * lives here, with the sentences it selects between, rather than being made twice.
     * <p>
     * Generic in what the platform answered with, because the payload is not what it decides on.
     *
     * @param <T> what the platform answers with
     * @param answer what the facade said when asked for the view
     * @param comparisonId the comparison the caller quoted
     * @return the refusal, or {@code null} when {@code answer} carries a usable value
     */
    public static <T> ToolResult unreadableTree(PlatformAnswer<T> answer, String comparisonId)
    {
        if (answer == null || answer.isUnavailable())
        {
            // Retryable, and the comparison is untouched: nothing about it was established.
            return readUnavailable(comparisonId);
        }
        if (answer.orElse(null) == null)
        {
            // EDT ANSWERED, and its answer was that it no longer knows the handle.
            return sessionGone(comparisonId);
        }
        return null;
    }

    /**
     * A path parameter that is not absolute, refused instead of resolved.
     * <p>
     * Asked of the value the caller PASSED, before {@code toAbsolutePath} has had a chance to make
     * one up. That resolution is against the working directory of the EDT PROCESS - the install
     * directory, or wherever a launcher happened to start it - and it never fails, so a relative
     * path does not produce an error: it produces a file somewhere nobody named. An MCP client
     * resolves a relative path against ITS OWN directory, so the two disagree silently, and the
     * report then names the wrong file as the one that was used.
     * <p>
     * Shared rather than written per tool because both comparison tools take a merge-rules path -
     * {@code merge_rules} takes two - and a refusal that differs between them would teach the
     * caller that the rule differs too.
     *
     * @param parameter the parameter name, for the message
     * @param value the value exactly as the caller passed it
     * @param path that value parsed
     * @return the refusal, or {@code null} when the path is absolute
     */
    public static ToolResult relativePath(String parameter, String value, Path path)
    {
        if (path.isAbsolute())
        {
            return null;
        }
        return ToolResult.error(parameter + " must be an ABSOLUTE path, but was '" + value //$NON-NLS-1$ //$NON-NLS-2$
            + "'. A relative path is resolved against the working directory of the EDT process, " //$NON-NLS-1$
            + "not against your project, so the file would be read from - or written to - " //$NON-NLS-1$
            + "wherever EDT happens to have been started. Pass the full path, for example " //$NON-NLS-1$
            + "'C:\\work\\rules.xml' or '/home/user/rules.xml'."); //$NON-NLS-1$
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
