/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer.ConfirmAction;

/**
 * Tests for the external-changes conflict matcher of {@link LaunchUpdateDialogAutoConfirmer}:
 * EDT's "Infobase configuration changes" modal, raised by the configuration-to-infobase
 * update when the infobase was changed OUTSIDE EDT (Designer, ibcmd, a CLI pipeline).
 *
 * <p>Unlike the other two modals this one has NO safe default button - its default is
 * "Import", which rewrites the caller's PROJECT sources. So the press is driven by the
 * call's {@link ExternalInfobaseChangesPolicy} and a label that cannot be located must
 * degrade to cancelling the dialog, never to the default button.
 */
public class LaunchUpdateDialogConflictMatcherTest
{
    /** EDT's conflict message for infobase "agent-base". */
    private static final String BODY_A =
        "Infobase \"agent-base\" configuration was changed independent of the project " //$NON-NLS-1$
            + "since last EDT infobase interaction, possible with the Designer."; //$NON-NLS-1$

    /** The same message for infobase "other-base". */
    private static final String BODY_B =
        "Infobase \"other-base\" configuration was changed independent of the project."; //$NON-NLS-1$

    /** The same message for an infobase nobody armed. */
    private static final String BODY_C =
        "Infobase \"third-base\" configuration was changed independent of the project."; //$NON-NLS-1$

    /** Russian title of the modal, unicode-escaped exactly like the production constant. */
    private static final String CONFLICT_TITLE_RU = "\u0418\u0437\u043C\u0435\u043D\u0435\u043D\u0438\u044F \u043A\u043E\u043D\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0438 \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 \u0431\u0430\u0437\u044B"; //$NON-NLS-1$

    /** Russian label of the "Override" button. */
    private static final String OVERRIDE_RU = "\u041F\u0435\u0440\u0435\u0437\u0430\u043F\u0438\u0441\u0430\u0442\u044C"; //$NON-NLS-1$

    /** Russian label of the "Import" button. */
    private static final String IMPORT_RU = "\u0418\u043C\u043F\u043E\u0440\u0442\u0438\u0440\u043E\u0432\u0430\u0442\u044C"; //$NON-NLS-1$

    @Test
    public void testMatchesBothShippedLocales()
    {
        assertTrue(LaunchUpdateDialogAutoConfirmer.isConflictTitle("Infobase configuration changes")); //$NON-NLS-1$
        assertTrue(LaunchUpdateDialogAutoConfirmer.isConflictTitle(CONFLICT_TITLE_RU));
    }

    @Test
    public void testDoesNotMatchUnrelatedOrNearbyTitles()
    {
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle(null));
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle("")); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle("Application update")); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle("Restructure data")); //$NON-NLS-1$
        // Substrings must not match - the compare is whole-title.
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle("Infobase configuration")); //$NON-NLS-1$
        assertFalse(
            LaunchUpdateDialogAutoConfirmer.isConflictTitle("Infobase configuration changes found")); //$NON-NLS-1$
    }

    @Test
    public void testConflictTitleIsDisjointFromTheOtherMatchers()
    {
        assertFalse(LaunchUpdateDialogAutoConfirmer.isTargetTitle("Infobase configuration changes")); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.isRestructureTitle("Infobase configuration changes")); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.isConflictTitle(CONFLICT_TITLE_RU + " ")); //$NON-NLS-1$
    }

    @Test
    public void testGatingFiresOnlyWhenTheConflictMatcherIsArmed()
    {
        String title = "Infobase configuration changes"; //$NON-NLS-1$
        assertTrue(LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, false, true, title, null));
        // Armed for the other modals only: this dialog is left for a human.
        assertFalse(LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, true, false, title, null));
    }

    @Test
    public void testOverrideAndImportPressTheirOwnLabelledButton()
    {
        assertEquals(ConfirmAction.PRESS_POLICY_BUTTON,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(ExternalInfobaseChangesPolicy.OVERRIDE, true));
        assertEquals(ConfirmAction.PRESS_POLICY_BUTTON,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(ExternalInfobaseChangesPolicy.IMPORT, true));
    }

    @Test
    public void testCancelPolicyClosesTheDialogInsteadOfPressingAnything()
    {
        assertEquals(ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(ExternalInfobaseChangesPolicy.CANCEL, true));
        assertNull(
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.CANCEL));
    }

    @Test
    public void testMissingButtonLabelCancelsRatherThanPressingTheDefaultButton()
    {
        // The default button of this modal is "Import" (it rewrites the project sources),
        // so a label miss must never fall through to it.
        assertEquals(ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(ExternalInfobaseChangesPolicy.OVERRIDE, false));
        assertEquals(ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(ExternalInfobaseChangesPolicy.IMPORT, false));
        assertEquals(ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(null, true));
    }

    @Test
    public void testPolicyButtonLabelsCoverBothLocales()
    {
        assertTrue(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel("Override", //$NON-NLS-1$
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.OVERRIDE)));
        assertTrue(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel(OVERRIDE_RU,
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.OVERRIDE)));
        assertTrue(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel("Import", //$NON-NLS-1$
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.IMPORT)));
        assertTrue(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel(IMPORT_RU,
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.IMPORT)));
        // A mnemonic marker must not break the compare.
        assertTrue(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel("&Override", //$NON-NLS-1$
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.OVERRIDE)));
        // The two policies never claim each other's button.
        assertFalse(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel("Import", //$NON-NLS-1$
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.OVERRIDE)));
        assertFalse(LaunchUpdateDialogAutoConfirmer.matchesButtonLabel(null,
            LaunchUpdateDialogAutoConfirmer.conflictButtonLabels(ExternalInfobaseChangesPolicy.IMPORT)));
    }

    @Test
    public void testAmbiguousArmsDegradeToCancel()
    {
        // Two concurrent launches with DIFFERENT policies share the one Display filter and
        // the modal carries no owner information, so acting on either arm would apply a
        // choice the other caller never asked for - and one of those choices rewrites
        // project sources. The ambiguous window must therefore resolve to CANCEL.
        assertEquals(ConfirmAction.CANCEL_DIALOG, LaunchUpdateDialogAutoConfirmer
            .chooseConflictAction(ExternalInfobaseChangesPolicy.CANCEL, true));
        // A single armed policy is unambiguous and is acted on.
        assertEquals(ConfirmAction.PRESS_POLICY_BUTTON, LaunchUpdateDialogAutoConfirmer
            .chooseConflictAction(ExternalInfobaseChangesPolicy.IMPORT, true));
    }

    @Test
    public void testCancelledConflictIsVisibleToTheUpdateCaller()
    {
        // The update caller samples the counter around its update, so a failed update can name
        // the real cause instead of EDT's generic out-of-sync text. A COUNTER (not a clock
        // stamp) keeps that detection immune to rollback and same-millisecond events.
        int before = LaunchUpdateDialogAutoConfirmer.conflictCancelCount();
        assertEquals(before, LaunchUpdateDialogAutoConfirmer.conflictCancelCount());
        LaunchUpdateDialogAutoConfirmer.recordConflictCancelForTest(
            LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_POLICY);
        assertTrue(LaunchUpdateDialogAutoConfirmer.conflictCancelCount() != before);
        assertEquals(LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_POLICY,
            LaunchUpdateDialogAutoConfirmer.lastConflictCancelReason());
    }

    @Test
    public void testDeclinedUpdateErrorAdviceDependsOnTheCancelReason()
    {
        // The caller cannot see the dialog, so the message must name the cause AND a way out -
        // and repeating the same policy is NOT a way out of a missing button label.
        String policyCancel = ExternalInfobaseChangesPolicy.declinedUpdateError(
            ExternalInfobaseChangesPolicy.CANCEL, LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_POLICY);
        assertTrue(policyCancel, policyCancel.contains("externalInfobaseChanges=cancel"));
        assertTrue(policyCancel, policyCancel.contains("override") && policyCancel.contains("import"));

        String labelMiss = ExternalInfobaseChangesPolicy.declinedUpdateError(
            ExternalInfobaseChangesPolicy.OVERRIDE,
            LaunchUpdateDialogAutoConfirmer.CANCEL_REASON_BUTTON_NOT_FOUND);
        assertTrue(labelMiss, labelMiss.contains("was not found"));
        assertFalse(labelMiss, labelMiss.contains("Re-run with externalInfobaseChanges"));
    }

    @Test
    public void testOnlyADialogAboutAnArmedInfobaseMayBePressed()
    {
        // EDT states the infobase in the conflict modal message - the only owner information
        // the dialog carries. A dialog about ANOTHER infobase (or one we cannot read) must not
        // be pressed: override would discard that infobase's external changes and import would
        // rewrite that project's sources.
        String body = "Infobase \"agent-base\" configuration was changed independent of the project " //$NON-NLS-1$
            + "since last EDT infobase interaction, possible with the Designer."; //$NON-NLS-1$
        assertTrue(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body,
            Arrays.asList("agent-base"))); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body,
            Arrays.asList("other-base"))); //$NON-NLS-1$
        // A bare substring must NOT claim a dialog about a different infobase: the match is on
        // the QUOTED name EDT renders, so an armed "base" never captures "agent-base".
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body,
            Arrays.asList("base"))); //$NON-NLS-1$
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(
            "Infobase configuration changes", Arrays.asList("configuration"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body, Arrays.asList()));
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body, null));
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(null,
            Arrays.asList("agent-base"))); //$NON-NLS-1$
        // A blank armed name must not match everything.
        assertFalse(LaunchUpdateDialogAutoConfirmer.bodyMentionsAny(body, Arrays.asList(""))); //$NON-NLS-1$
        // An unattributed dialog completes as a CANCEL (policy null), never as a press.
        assertEquals(ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConflictAction(null, true));
    }

    @Test
    public void testNotArmedYieldsNoPolicy()
    {
        // Headless (no Display): arm() is a no-op, so nothing is ever armed here and the
        // filter can never claim a dialog.
        assertNull(LaunchUpdateDialogAutoConfirmer.chooseConflictPolicy(BODY_A));
        LaunchUpdateDialogAutoConfirmer.arm(false, false, false, ExternalInfobaseChangesPolicy.OVERRIDE);
        assertNull(LaunchUpdateDialogAutoConfirmer.chooseConflictPolicy(BODY_A));
        // An unbalanced disarm stays a no-op too.
        LaunchUpdateDialogAutoConfirmer.disarm(false, false, false, ExternalInfobaseChangesPolicy.OVERRIDE);
        assertNull(LaunchUpdateDialogAutoConfirmer.chooseConflictPolicy(BODY_A));
    }

    @Test
    public void testPolicyIsChosenPerAttributedInfobaseNotGlobally()
    {
        // Two independent updates armed at once with DIFFERENT policies: each dialog must get
        // the policy of the arm that NAMED its infobase. Collapsing to CANCEL just because some
        // other policy is armed would break parallel runs of unrelated projects.
        List<LaunchUpdateDialogAutoConfirmer.ConflictArm> arms = Arrays.asList(
            arm("agent-base", ExternalInfobaseChangesPolicy.OVERRIDE), //$NON-NLS-1$
            arm("other-base", ExternalInfobaseChangesPolicy.IMPORT)); //$NON-NLS-1$
        assertEquals(ExternalInfobaseChangesPolicy.OVERRIDE,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, arms));
        assertEquals(ExternalInfobaseChangesPolicy.IMPORT,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_B, arms));
        // A dialog about an infobase nobody armed is somebody else\'s: cancel, never a press.
        assertNull(LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_C, arms));
        assertNull(LaunchUpdateDialogAutoConfirmer.choosePolicyFor(null, arms));
    }

    @Test
    public void testSameInfobaseWithTwoPoliciesIsTheOnlyRealAmbiguity()
    {
        // Two callers want DIFFERENT answers to the SAME dialog - the only case where acting
        // would apply a choice the other caller never asked for.
        assertEquals(ExternalInfobaseChangesPolicy.CANCEL,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, Arrays.asList(
                arm("agent-base", ExternalInfobaseChangesPolicy.OVERRIDE), //$NON-NLS-1$
                arm("agent-base", ExternalInfobaseChangesPolicy.IMPORT)))); //$NON-NLS-1$
        // The same policy twice (nested arms) is not ambiguous.
        assertEquals(ExternalInfobaseChangesPolicy.OVERRIDE,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, Arrays.asList(
                arm("agent-base", ExternalInfobaseChangesPolicy.OVERRIDE), //$NON-NLS-1$
                arm("agent-base", ExternalInfobaseChangesPolicy.OVERRIDE)))); //$NON-NLS-1$
    }

    @Test
    public void testUnnamedArmsKeepThePreAttributionBehaviour()
    {
        // A launch window that could not resolve an infobase name (EDT performs the update
        // inside its own launch delegate) cannot attribute anything - the single armed policy
        // still applies, and only a genuine disagreement cancels.
        assertEquals(ExternalInfobaseChangesPolicy.OVERRIDE,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, Arrays.asList(
                arm(null, ExternalInfobaseChangesPolicy.OVERRIDE))));
        assertEquals(ExternalInfobaseChangesPolicy.CANCEL,
            LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, Arrays.asList(
                arm(null, ExternalInfobaseChangesPolicy.OVERRIDE),
                arm(null, ExternalInfobaseChangesPolicy.IMPORT))));
        // A NAMED arm that does not match wins over an unnamed one: the dialog is not ours.
        assertNull(LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_C, Arrays.asList(
            arm(null, ExternalInfobaseChangesPolicy.OVERRIDE),
            arm("agent-base", ExternalInfobaseChangesPolicy.OVERRIDE)))); //$NON-NLS-1$
        assertNull(LaunchUpdateDialogAutoConfirmer.choosePolicyFor(BODY_A, Arrays.asList()));
    }

    private static LaunchUpdateDialogAutoConfirmer.ConflictArm arm(String infobase,
        ExternalInfobaseChangesPolicy policy)
    {
        return new LaunchUpdateDialogAutoConfirmer.ConflictArm(infobase, policy);
    }
}
