/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless ratchet for {@link InfobaseAuthDialogSuppressor}. Covers the pure, SWT-free seams:
 * <ul>
 *   <li>the two title-prefix matchers — EDT's "Configure Infobase access Settings" dialog and Eclipse
 *       Secure Storage's "Password Hint Needed" follow-up (#194);</li>
 *   <li>the env kill-switch classifier {@link InfobaseAuthDialogSuppressor#envAllowsSuppression} —
 *       <b>default ON</b> polarity (#230), the inverse of {@code DestructiveConsentGate.envForcesAllow};</li>
 *   <li>the activity-scoping decision {@link InfobaseAuthDialogSuppressor#shouldSuppressAuthDialog} —
 *       the full env × in-flight × grace-window truth table incl. the grace boundary (#230);</li>
 *   <li>the {@link InfobaseAuthDialogSuppressor#markActivityStart()} /
 *       {@link InfobaseAuthDialogSuppressor#markActivityEnd()} effect on the {@code IN_FLIGHT} counter and
 *       {@code lastActivityEndMillis} timestamp;</li>
 *   <li>the <b>scope split</b>: the auth dialog is gated by the activity/env decision while the Secure
 *       Storage hint dialog is dismissed unconditionally — asserted through a mirror of the filter's own
 *       dismiss predicate.</li>
 * </ul>
 * The SWT install/dismiss path itself needs a live workbench display and is verified on the e2e stand.
 */
public class InfobaseAuthDialogSuppressorTest
{
    /** A fixed "now" far from zero so a {@code now - grace} never underflows negative. */
    private static final long NOW_MS = 1_000_000_000L;

    /** The trailing grace window used by the truth-table cases (matches the production default). */
    private static final long GRACE_MS = InfobaseAuthDialogSuppressor.DEFAULT_ACTIVITY_GRACE_MILLIS;

    private static final String AUTH_TITLE = "Configure Infobase \"ERP\" access Settings"; //$NON-NLS-1$
    private static final String HINT_TITLE = "Secure Storage - Password Hint Needed"; //$NON-NLS-1$

    // =====================================================================
    // Title-prefix matchers (auth dialog)
    // =====================================================================

    @Test
    public void testEnglishTitleWithInfobaseNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Configure Infobase \"ERP\" access Settings")); //$NON-NLS-1$
    }

    @Test
    public void testEnglishTitleNoNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Configure Infobase Access Settings")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTitleWithInfobaseNameMatches()
    {
        // "Сконфигурируйте доступ к информационной базе \"ERP\""
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Сконфигурируйте доступ к информационной базе \"ERP\"")); //$NON-NLS-1$
    }

    @Test
    public void testRussianTitleNoNameMatches()
    {
        assertTrue(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Сконфигурируйте доступ к информационной базе")); //$NON-NLS-1$
    }

    @Test
    public void testUnrelatedTitlesDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Application update")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Restructure data")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Question")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("Some other dialog")); //$NON-NLS-1$
    }

    @Test
    public void testNullAndEmptyDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle(null));
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle("")); //$NON-NLS-1$
    }

    // =====================================================================
    // Title-prefix matchers (secure-storage hint dialog)
    // =====================================================================

    @Test
    public void testSecureStorageHintTitleMatches()
    {
        // pswdRecoveryOptionTitle from org.eclipse.equinox.security.ui — shipped untranslated.
        assertTrue(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(
            "Secure Storage - Password Hint Needed")); //$NON-NLS-1$
    }

    @Test
    public void testHintMatcherIgnoresAccessSettingsAndUnrelated()
    {
        // The two matchers are disjoint: neither claims the other's dialog, nor any unrelated one.
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(
            "Configure Infobase \"ERP\" access Settings")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("Secure Storage")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("Some other dialog")); //$NON-NLS-1$
        assertFalse(InfobaseAuthDialogSuppressor.isAuthDialogTitle(
            "Secure Storage - Password Hint Needed")); //$NON-NLS-1$
    }

    @Test
    public void testHintMatcherNullAndEmptyDoNotMatch()
    {
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(null));
        assertFalse(InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle("")); //$NON-NLS-1$
    }

    // =====================================================================
    // #230 A — env kill-switch EDT_MCP_SUPPRESS_AUTH_DIALOG (pure classifier, DEFAULT ON)
    // =====================================================================

    @Test
    public void envDefaultsOnWhenUnsetOrBlank()
    {
        // Default ON: unset / blank keep suppression enabled (preserves the #194 unattended-safety
        // behaviour for anyone who does not set the flag). This is the INVERSE polarity of
        // DestructiveConsentGate.envForcesAllow (which defaults to the non-bypass path).
        assertTrue("null (unset) must keep suppression ON", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression(null));
        assertTrue("empty string must keep suppression ON", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("")); //$NON-NLS-1$
    }

    @Test
    public void envKeepsSuppressionOnForNonNegativeValues()
    {
        // Anything that is not an explicit off value leaves suppression enabled — including garbage,
        // so a typo can never silently disable the #194 safety net.
        assertTrue("'true' must keep suppression ON", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("true")); //$NON-NLS-1$
        assertTrue("'1' must keep suppression ON", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("1")); //$NON-NLS-1$
        assertTrue("'yes' must keep suppression ON", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("yes")); //$NON-NLS-1$
        assertTrue("garbage must keep suppression ON (fail-safe default)", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("garbage")); //$NON-NLS-1$
    }

    @Test
    public void envDisablesSuppressionOnlyForExplicitOffValues()
    {
        // The ONLY values that turn suppression off: a trimmed, case-insensitive false / 0 / no.
        assertFalse("'false' must disable suppression", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("false")); //$NON-NLS-1$
        assertFalse("env value is case-insensitive ('FALSE')", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("FALSE")); //$NON-NLS-1$
        assertFalse("'0' must disable suppression", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("0")); //$NON-NLS-1$
        assertFalse("env value is trimmed (' 0 ')", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression(" 0 ")); //$NON-NLS-1$
        assertFalse("'no' must disable suppression", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("no")); //$NON-NLS-1$
        assertFalse("env value is case-insensitive ('No')", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.envAllowsSuppression("No")); //$NON-NLS-1$
    }

    @Test
    public void isEnvSuppressEnabledDelegatesToClassifier()
    {
        // The thin env reader must agree with the pure classifier over the ambient process value —
        // proving delegation without depending on whether the flag is actually set in this JVM.
        assertEquals(
            InfobaseAuthDialogSuppressor.envAllowsSuppression(
                System.getenv(InfobaseAuthDialogSuppressor.ENV_SUPPRESS_AUTH_DIALOG)),
            InfobaseAuthDialogSuppressor.isEnvSuppressEnabled());
    }

    // =====================================================================
    // #230 B — activity scoping shouldSuppressAuthDialog (env × in-flight × grace)
    // =====================================================================

    @Test
    public void graceWindowIsThirtySeconds()
    {
        assertEquals("the activity grace window is 30s (documented in the #230 architecture)", //$NON-NLS-1$
            30_000L, InfobaseAuthDialogSuppressor.DEFAULT_ACTIVITY_GRACE_MILLIS);
    }

    @Test
    public void envOffNeverSuppresses()
    {
        // The env kill-switch is a HARD override: with it off, the auth dialog is left for the human
        // regardless of any in-flight call or how recently one ended.
        assertFalse("env off + call in flight must NOT suppress", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(false, 2, NOW_MS, NOW_MS, GRACE_MS));
        assertFalse("env off + idle within grace must NOT suppress", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                false, 0, NOW_MS, NOW_MS - 5_000L, GRACE_MS));
        assertFalse("env off + idle beyond grace must NOT suppress", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                false, 0, NOW_MS, NOW_MS - 60_000L, GRACE_MS));
    }

    @Test
    public void envOnSuppressesWhileCallInFlight()
    {
        // A call in flight suppresses for its WHOLE duration — even if the last recorded end is already
        // far outside the grace window (update_database's minutes are covered by the counter, not the
        // window).
        assertTrue("env on + call in flight must suppress (even past the grace window)", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                true, 1, NOW_MS, NOW_MS - 60_000L, GRACE_MS));
    }

    @Test
    public void envOnSuppressesWithinGraceAfterLastCall()
    {
        // No call in flight, but the last one ended within the grace window: the async read-back dialog
        // is still covered.
        assertTrue("env on + idle within grace must suppress", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                true, 0, NOW_MS, NOW_MS - 5_000L, GRACE_MS));
    }

    @Test
    public void envOnDoesNotSuppressWhenIdleBeyondGrace()
    {
        // Idle past the window: leave the dialog alone so a human can configure credentials in the GUI.
        assertFalse("env on + idle beyond grace must NOT suppress", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                true, 0, NOW_MS, NOW_MS - 60_000L, GRACE_MS));
    }

    @Test
    public void graceBoundaryIsExclusive()
    {
        // The window is half-open [.., grace): now - lastActivityEnd == grace is already OUTSIDE it.
        assertFalse("now - lastActivityEnd == grace must NOT suppress (boundary is exclusive)", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                true, 0, NOW_MS, NOW_MS - GRACE_MS, GRACE_MS));
        assertTrue("one ms inside the window still suppresses", //$NON-NLS-1$
            InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
                true, 0, NOW_MS, NOW_MS - (GRACE_MS - 1L), GRACE_MS));
    }

    // =====================================================================
    // #230 B — markActivityStart / markActivityEnd effect on the shared state
    // =====================================================================

    @Test
    public void markActivityStartEndAdjustCounterAndTimestamp()
    {
        // Balanced start/end pair restores IN_FLIGHT, so the shared static state stays clean for the
        // other (param-driven) truth-table tests.
        int baseline = InfobaseAuthDialogSuppressor.IN_FLIGHT.get();
        long before = System.currentTimeMillis();

        InfobaseAuthDialogSuppressor.markActivityStart();
        assertEquals("markActivityStart must increment IN_FLIGHT", //$NON-NLS-1$
            baseline + 1, InfobaseAuthDialogSuppressor.IN_FLIGHT.get());

        InfobaseAuthDialogSuppressor.markActivityEnd();
        assertEquals("markActivityEnd must decrement IN_FLIGHT back to the baseline", //$NON-NLS-1$
            baseline, InfobaseAuthDialogSuppressor.IN_FLIGHT.get());

        long stamped = InfobaseAuthDialogSuppressor.lastActivityEndMillis;
        assertTrue("markActivityEnd must stamp lastActivityEndMillis to ~now", //$NON-NLS-1$
            stamped >= before && stamped <= System.currentTimeMillis());
    }

    @Test
    public void markActivityEndClampsCounterAtZero()
    {
        // Requirement 3 ("the counter never goes negative"): an UNBALANCED markActivityEnd() — a decrement
        // with no matching markActivityStart() — must land at max(0, baseline - 1), never below zero, so a
        // stray end can never starve shouldSuppressAuthDialog's `inFlight > 0` guard. This drives the clamp
        // (decrementAndGet() < 0 -> set(0)) that the balanced start/end pair above never reaches: at the
        // usual idle baseline of 0 the decrement goes to -1 and is clamped back to 0.
        int baseline = InfobaseAuthDialogSuppressor.IN_FLIGHT.get();
        try
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
            assertEquals("unbalanced markActivityEnd must clamp IN_FLIGHT at zero (never negative)", //$NON-NLS-1$
                Math.max(0, baseline - 1), InfobaseAuthDialogSuppressor.IN_FLIGHT.get());
        }
        finally
        {
            // Restore the shared static so the other (order-independent) tests keep their baseline.
            InfobaseAuthDialogSuppressor.IN_FLIGHT.set(baseline);
        }
    }

    // =====================================================================
    // #230 — scope split: the auth branch is gated, the hint branch is NOT
    // =====================================================================

    @Test
    public void hintDialogIsDismissedRegardlessOfTheGate()
    {
        // The Secure Storage password-hint dialog is an internal follow-up (never human-configured), so
        // it is dismissed UNCONDITIONALLY — even with the env kill-switch off and the server fully idle
        // beyond the grace window, i.e. exactly the state in which the auth dialog would be left alone.
        assertTrue("hint dialog must be dismissed even with the auth gate fully closed", //$NON-NLS-1$
            filterDismisses(HINT_TITLE, false, 0, NOW_MS, NOW_MS - 60_000L, GRACE_MS));
    }

    @Test
    public void authDialogIsLeftWhenTheGateIsClosed()
    {
        // Same fully-closed-gate state, but for the AUTH dialog: it is left for the human. This is the
        // #230 fix — the difference between this and the hint case above IS the scope split.
        assertFalse("auth dialog must be left for the human when the gate is closed", //$NON-NLS-1$
            filterDismisses(AUTH_TITLE, false, 0, NOW_MS, NOW_MS - 60_000L, GRACE_MS));
    }

    @Test
    public void authDialogIsDismissedWhileTheGateIsOpen()
    {
        // With the env kill-switch on and a call in flight (an MCP-triggered dialog), the auth dialog is
        // auto-cancelled — the #194 unattended-safety behaviour, preserved.
        assertTrue("auth dialog must be dismissed while an MCP call is in flight", //$NON-NLS-1$
            filterDismisses(AUTH_TITLE, true, 1, NOW_MS, NOW_MS, GRACE_MS));
    }

    @Test
    public void unrelatedDialogIsNeverTouched()
    {
        // Neither branch claims an unrelated shell, whatever the gate state.
        assertFalse("unrelated dialog must never be dismissed (gate open)", //$NON-NLS-1$
            filterDismisses("Some other dialog", true, 1, NOW_MS, NOW_MS, GRACE_MS)); //$NON-NLS-1$
        assertFalse("unrelated dialog must never be dismissed (gate closed)", //$NON-NLS-1$
            filterDismisses("Some other dialog", false, 0, NOW_MS, NOW_MS - 60_000L, GRACE_MS)); //$NON-NLS-1$
    }

    /**
     * Mirrors {@code InfobaseAuthDialogSuppressor.createFilterListener}'s dismiss predicate over the pure
     * seams, so the scope split is asserted headlessly (the real predicate lives in SWT code verified on
     * the stand). Dismiss when the shell is the hint dialog (UNCONDITIONAL) OR it is the auth dialog and
     * {@link InfobaseAuthDialogSuppressor#shouldSuppressAuthDialog} allows it. If the auth gate ever
     * leaked into the hint branch (e.g. {@code hint && shouldSuppress}), the hint-with-closed-gate case
     * would flip to {@code false} and fail; if the env polarity were inverted, the env-off cases would
     * flip and fail.
     *
     * @return {@code true} when the filter would dismiss the shell with the given title and gate state
     */
    private static boolean filterDismisses(String title, boolean envEnabled, int inFlight, long now,
        long lastActivityEnd, long grace)
    {
        boolean auth = InfobaseAuthDialogSuppressor.isAuthDialogTitle(title);
        boolean hint = !auth && InfobaseAuthDialogSuppressor.isSecureStorageHintDialogTitle(title);
        return hint || (auth && InfobaseAuthDialogSuppressor.shouldSuppressAuthDialog(
            envEnabled, inFlight, now, lastActivityEnd, grace));
    }
}
