/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

/**
 * How far a cascade rename had got when the caller stopped waiting.
 *
 * <p>The rename runs on the UI thread while the MCP call waits with a deadline (issue #365). When
 * that deadline elapses the work is NOT stopped — cancellation is cooperative and the UI thread is
 * not polling anything — so the honest question the caller must answer is not "did it fail" but
 * "how much of the configuration was already being rewritten". A rename reported as failed while
 * the model is half-renamed is more dangerous than the hang this deadline replaces, so the phase is
 * published as the work advances and read by the timed-out caller to word its error.
 *
 * <p>Written by the UI thread, read by the waiting thread with no synchronisation between them:
 * the field is {@code volatile} so the reader sees a phase that was really entered rather than a
 * stale or torn one. It is a best-effort diagnostic — the work may advance a phase the instant
 * after it is read — so every message it feeds must stay true for that phase AND the ones after it.
 */
public final class RenameProgress
{
    /**
     * The stages of a rename, in the order the service enters them. Later constants mean more of
     * the configuration has been touched.
     */
    public enum Phase
    {
        /**
         * The work never reached the service: it is running but still waiting for the UI thread to
         * take the request. Nothing resolved, built or changed.
         * <p>
         * NB the other way of not reaching the service — the background job never starting at all —
         * does not show up here: {@code BoundedJob} answers that case with its own
         * {@code TIMED_OUT_BEFORE_START}, which is why this phase can be reported as work that is
         * still in flight.
         */
        QUEUED,
        /**
         * Resolving the target and building the LTK refactoring (and, on an execute, composing the
         * consent preview). EDT may save dirty editors and run an incremental build here, but the
         * cascade has NOT started rewriting anything.
         */
        PREPARING,
        /**
         * Inside the destructive-operation consent gate, and nowhere wider. Nothing rewritten — but
         * the decision can arrive later and start the rename after the caller gave up.
         */
        AWAITING_CONSENT,
        /**
         * Past the consent gate and authorised to rewrite: the apply loop is running or about to.
         * A call that stops waiting here can leave the configuration PARTIALLY renamed.
         */
        APPLYING,
        /**
         * The apply loop finished — NOT a claim that every change point succeeded: a failed
         * {@code perform()} and a skipped {@code disableIndices} entry both end here too. It means
         * only that nothing is left to apply and the report was all that remained.
         */
        APPLIED
    }

    private volatile Phase phase = Phase.QUEUED;

    /**
     * Records that the work entered {@code phase}. Phases are entered in declaration order; this
     * method does not enforce that, because a guard that threw here would turn a diagnostic into a
     * failure mode of the rename itself.
     *
     * @param newPhase the phase just entered
     */
    public void enter(Phase newPhase)
    {
        this.phase = newPhase;
    }

    /**
     * @return the last phase the work reported entering — never {@code null}
     */
    public Phase getPhase()
    {
        return phase;
    }
}
