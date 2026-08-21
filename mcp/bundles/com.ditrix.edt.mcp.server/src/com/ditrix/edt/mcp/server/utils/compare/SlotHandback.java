/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

/**
 * What became of EDT's single comparison slot, and the sentence the caller publishes about it.
 *
 * <h2>The defect family this type ends</h2>
 * EDT runs ONE comparison per instance. Giving that slot back is two facts and one action, and the
 * two facts used to be re-derived at every site that ended a comparison - the poll loop's failed
 * branch, its cancelled branch, its terminal branch, the {@code cancel_job} handler, the
 * {@code releaseComparisonId} entry point and the idle sweep. Each of them combined the same three
 * questions in its own way:
 * <ol>
 *   <li>could the platform be ASKED at all ({@link PlatformAnswer})?</li>
 *   <li>did the hand-back COMPLETE?</li>
 *   <li>who owed the stop in the first place?</li>
 * </ol>
 * Three review rounds found nine, then eight, then six instances of the same mistake, each in a
 * different one of those sites: an answer discarded, a record dropped over a stop that never
 * happened, a service gap read as a failure. Patching them one at a time produced a new instance
 * per new call site, because the DECISION was the thing being duplicated.
 *
 * <h2>What replaces it</h2>
 * One owner - {@link ComparisonSessionRegistry#handBack(String, Ending)} - performs the whole
 * hand-back and answers with this value. Nothing else in the bundle can end a comparison:
 * {@link ComparisonEngine}'s two lifetime verbs are package-scoped and the session map is private,
 * so a caller has no way to drop a record, and no way to stop a comparison, other than through the
 * owner. What a caller may do with the answer is bounded to three things, none of which is a
 * judgement about the slot: publish {@link #sentence()} verbatim, branch on {@link #slotIsFree()},
 * and notice {@link #wasRegistered()}.
 *
 * <h2>The invariant that makes "forgetting" unrepresentable</h2>
 * <b>The record is dropped exactly when the slot is CONFIRMED free.</b> Never otherwise. A
 * comparison whose hand-back did not complete, or could not even be attempted, stays registered -
 * so it is still named by a refusal, still retried by the next sweep, and still addressable by
 * {@code releaseComparisonId}. There is therefore no state in which a caller holds a dropped
 * record and an occupied slot, which is what every one of the six findings produced in its own
 * way. A caller cannot "forget to account for a failed release" because it never sees the release:
 * it sees this value, and the failure is already written into the sentence it must publish.
 *
 * <h2>What this type does NOT guarantee</h2>
 * Stated here rather than left to be discovered, because the boundary was declared before the work
 * started and the remainder is architecture rather than a missing branch:
 * <ol>
 *   <li><b>Nothing retries on its own.</b> {@link Verdict#UNREACHABLE} and
 *       {@link Verdict#NOT_FREED} keep the record so the hand-back CAN be retried, but the retry
 *       rides on the next call that touches the registry - there is no timer and no thread. A
 *       workbench where nobody calls a comparison tool again keeps the session until the bundle
 *       stops.</li>
 *   <li><b>Liveness is a reading, not a subscription.</b> "EDT still holds this handle" is
 *       {@code getHandles} answered at one instant. Between the reading and the stop, a workbench
 *       cancellation or an EDT session restart can end the comparison; the hand-back then reports
 *       what it observed, which is one reading old.</li>
 *   <li><b>A lease keeps the SWEEP off a session, not a caller.</b>
 *       {@link ComparisonSessionRegistry#lease(String)} exists so a long tree read is not
 *       reclaimed under itself. It deliberately does not block {@code releaseComparisonId} or
 *       {@code cancel_job}: those are somebody ASKING, and a read that dies because the caller
 *       ended the comparison fails with the platform's own message, which is the truth.</li>
 *   <li><b>When the platform is already gone, nothing is handed back at all.</b> The bundle's
 *       last act asks EDT once more; if EDT's comparison service has been unregistered first - an
 *       EDT shutdown that stops the compare bundle before ours, or a crash - every session answers
 *       {@link Verdict#UNREACHABLE}, no stop is attempted, and the virtual projects go away with
 *       the JVM. Nothing is written to disk, so the next EDT process starts with no comparison and
 *       nothing to clean up; there is no cross-process reclamation and this type does not pretend
 *       to one.</li>
 *   <li><b>"Free" is about the instant it was observed.</b> {@link Verdict#FREED} means EDT took
 *       the hand-back then. A comparison started from EDT's own interface a moment later takes the
 *       slot again under no id of ours, and this server can then only report that the slot is
 *       taken by something it cannot name.</li>
 *   <li><b>There is no partial hand-back, by construction.</b> Dropping the record is a removal
 *       from an in-memory map and cannot fail, so "the platform was told but the record could not
 *       be dropped" is unrepresentable. If the registry ever became durable that would stop being
 *       true, and the answer would be a different construction rather than a sixth verdict.</li>
 *   <li><b>The last-tick ownership window stays open.</b> A launch claims an outstanding
 *       cancellation once, at its single exit. A hand-over that lands after that claim is owed by
 *       nobody and is answered only by the job's own result - the handler's own sentence promises
 *       exactly that much and no more. Closing it needs the cancellation handler and the job to
 *       share one commit point, which is a change to the background-job registry and not to this
 *       feature.</li>
 * </ol>
 */
public final class SlotHandback
{
    /**
     * Why the comparison is ending, which is the ONE thing a caller knows that the owner cannot.
     *
     * <h2>Why this is not a decision about the slot</h2>
     * It selects between EDT's two hand-back verbs, and those two verbs are the same operation.
     * Measured from {@code ComparisonManager} bytecode (EDT 2026.2,
     * {@code com._1c.g5.v8.dt.compare} 29.0.0), {@code stop(handle)} and {@code cancel(handle)}
     * compile to the same instructions apart from three things: the tracing call, the telemetry
     * string ("Comparison is finished without merging" against "Comparison is cancelled without
     * merging"), and a status stamp {@code cancel} writes onto the session it is discarding. Both
     * stop the running comparison job when the batch is under active comparison, both return early
     * under an active merge - which cannot happen here - and both discard the session.
     * <p>
     * That measurement is what lets the accounting be identical for the two, and it is also why
     * the old code's cancel-THEN-stop pair was redundant: the first call had already discarded the
     * session, so the second one found nothing and reported "already gone" as its ordinary answer.
     * ONE call is made now, and this enum only decides which name EDT records it under.
     */
    public enum Ending
    {
        /** The caller has finished with the comparison, or it ended by itself. */
        CLOSED,
        /** Somebody asked for the comparison to end before it was done. */
        CANCELLED
    }

    /** What the hand-back observed. */
    public enum Verdict
    {
        /** EDT held the comparison, was asked to end it, did not refuse, and the record is gone. */
        FREED,
        /**
         * EDT no longer held the comparison, so nothing was asked of it and nothing was left to
         * give back. The record is gone and THIS comparison holds nothing.
         * <p>
         * This is the ORDINARY answer after a cancellation, not a warning: ending a comparison is
         * what makes EDT forget its handle.
         * <p>
         * <b>It is not a reading of the slot.</b> What was observed is the absence of ONE handle -
         * ours - and the slot is EDT-wide: the platform drops its active batch when a comparison
         * ends, and a comparison launched from EDT's own comparison window is never registered
         * here, so the slot can be occupied by something this server cannot name at the very
         * moment this verdict is produced. The sentence says only what was seen, and the next
         * launch is what actually establishes whether the slot can be taken.
         */
        ALREADY_FREE,
        /**
         * Nothing is registered under that id. This says nothing at all about EDT's slot - the id
         * may never have existed, or may have been given back already.
         */
        NOT_REGISTERED,
        /**
         * EDT still held the comparison, the hand-back was attempted, and it did NOT complete. The
         * record is KEPT so the attempt can be repeated and so a refusal can still name it.
         */
        NOT_FREED,
        /**
         * The hand-back was attempted and NEVER REACHED the platform: EDT's comparison service was
         * not registered at that moment. The record is KEPT, for the same two reasons as
         * {@link #NOT_FREED}.
         * <p>
         * Distinct from {@link #NOT_FREED} because the caller's next move differs - this one is
         * retried once EDT has finished starting, that one is looked up in the EDT error log.
         * <p>
         * This is the verdict that used to be spelled as a stop: a momentary service gap dropped
         * the record while the comparison went on holding the slot with nothing able to address it.
         */
        UNREACHABLE
    }

    private final Verdict verdict;
    private final String comparisonId;

    private SlotHandback(Verdict verdict, String comparisonId)
    {
        this.verdict = verdict;
        this.comparisonId = comparisonId;
    }

    /**
     * Package-scoped: only the owner may state what became of the slot.
     *
     * @param verdict what was observed
     * @param comparisonId the comparison it was observed about
     * @return the value the caller must publish
     */
    static SlotHandback of(Verdict verdict, String comparisonId)
    {
        return new SlotHandback(verdict, comparisonId);
    }

    /**
     * @return what the hand-back observed; never {@code null}
     */
    public Verdict verdict()
    {
        return verdict;
    }

    /**
     * @return the id the hand-back was aimed at
     */
    public String comparisonId()
    {
        return comparisonId;
    }

    /**
     * Whether THIS comparison is done with EDT's single comparison slot as a RESULT of this
     * hand-back.
     * <p>
     * The one predicate a caller is meant to branch on, and the reason the verdicts are not
     * branched on outside the owner: "free" is a two-way question and the verdicts answer a
     * five-way one, so every site that split them itself split them slightly differently.
     * <p>
     * It is a statement about the NAMED comparison and not a reading of the slot. {@link
     * Verdict#FREED} saw EDT take the hand-back; {@link Verdict#ALREADY_FREE} saw that EDT no
     * longer held this handle at all. Neither observation can see a comparison started from EDT's
     * own comparison window, which this server never registers - see {@link Verdict#ALREADY_FREE}.
     * What the predicate is FOR is the record-dropping invariant: the record goes exactly when
     * this comparison is known to hold nothing.
     *
     * @return {@code true} for {@link Verdict#FREED} and {@link Verdict#ALREADY_FREE} only
     */
    public boolean slotIsFree()
    {
        return verdict == Verdict.FREED || verdict == Verdict.ALREADY_FREE;
    }

    /**
     * Whether the session is still registered here, so that the hand-back can be retried and a
     * refusal can still name the comparison.
     *
     * @return {@code true} for {@link Verdict#NOT_FREED} and {@link Verdict#UNREACHABLE}
     */
    public boolean recordKept()
    {
        return verdict == Verdict.NOT_FREED || verdict == Verdict.UNREACHABLE;
    }

    /**
     * Whether anything answered to the id at all.
     * <p>
     * Separate from {@link #slotIsFree()} on purpose: an unknown id is not a freed slot, and a
     * caller that reported it as one told somebody a slot was given back that somebody else may
     * still hold.
     *
     * @return {@code false} only for {@link Verdict#NOT_REGISTERED}
     */
    public boolean wasRegistered()
    {
        return verdict != Verdict.NOT_REGISTERED;
    }

    /**
     * What happened to EDT's single comparison slot, in the words every caller uses.
     * <p>
     * The caller supplies the context it was in ("the comparison failed", "you asked to close
     * it") and this supplies the slot half. That split is the point: the slot half is the sentence
     * a caller ACTS on, so writing it per site is how five sites came to describe the same
     * observation five ways, two of them wrongly.
     *
     * @return a complete sentence, actionable when there is anything to act on
     */
    public String sentence()
    {
        switch (verdict)
        {
            case FREED:
                return "Comparison '" + comparisonId + "' was ended and its temporary workspace " //$NON-NLS-1$ //$NON-NLS-2$
                    + "released, so EDT's single comparison slot is free again."; //$NON-NLS-1$
            case ALREADY_FREE:
                return "EDT no longer held comparison '" + comparisonId + "', so there was " //$NON-NLS-1$ //$NON-NLS-2$
                    + "nothing to stop and its record here is dropped. That is the whole of what " //$NON-NLS-1$
                    + "was observed: comparison '" + comparisonId + "' does not occupy EDT's " //$NON-NLS-1$ //$NON-NLS-2$
                    + "single comparison slot. Whether the slot is taken by something else was " //$NON-NLS-1$
                    + "NOT asked - a comparison started from EDT's own comparison window is never " //$NON-NLS-1$
                    + "registered here, so it would hold the slot under no id this server knows. " //$NON-NLS-1$
                    + "compare_configurations names the occupant when the next start is refused."; //$NON-NLS-1$
            case NOT_REGISTERED:
                return "Nothing is registered here under comparison '" + comparisonId //$NON-NLS-1$
                    + "', so nothing was stopped and nothing is claimed about EDT's single " //$NON-NLS-1$
                    + "comparison slot."; //$NON-NLS-1$
            case NOT_FREED:
                return "EDT still held comparison '" + comparisonId + "' and ending it did NOT " //$NON-NLS-1$ //$NON-NLS-2$
                    + "complete - the failure is in the EDT error log. Its record here is KEPT, " //$NON-NLS-1$
                    + "so compare_configurations with releaseComparisonId='" + comparisonId //$NON-NLS-1$
                    + "' can retry it; do NOT assume EDT's single comparison slot is free."; //$NON-NLS-1$
            default:
                return "EDT's comparison service could not be asked, so comparison '" //$NON-NLS-1$
                    + comparisonId + "' was NOT ended and may still hold EDT's single comparison " //$NON-NLS-1$
                    + "slot. Its record here is KEPT, so compare_configurations with " //$NON-NLS-1$
                    + "releaseComparisonId='" + comparisonId + "' retries it once EDT has " //$NON-NLS-1$ //$NON-NLS-2$
                    + "finished starting."; //$NON-NLS-1$
        }
    }

    @Override
    public String toString()
    {
        return verdict + "(" + comparisonId + ')'; //$NON-NLS-1$
    }
}
