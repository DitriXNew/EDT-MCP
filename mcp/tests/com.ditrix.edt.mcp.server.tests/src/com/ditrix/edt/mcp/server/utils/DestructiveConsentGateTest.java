/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotEquals;

import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.Collections;
import java.util.Set;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.widgets.Shell;
import org.junit.After;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
import com.ditrix.edt.mcp.server.protocol.ToolAnnotationClassifier;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentArbiter;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.Outcome;

/**
 * Headless ratchet for {@link DestructiveConsentGate}: asserts the full
 * env &gt; headless &gt; session &gt; level &gt; per-tool decision table using the
 * pure {@link DestructiveConsentGate#decide} / {@link DestructiveConsentGate#envForcesAllow}
 * seams — with NO SWT instantiation — plus the relationship between
 * {@link DestructiveConsentGate#GATED_TOOLS} and the destructive tools
 * {@link ToolAnnotationClassifier} advertises. The dialog itself is UI and is verified
 * manually in EDT.
 */
public class DestructiveConsentGateTest
{
    private static final String TOOL = "delete_metadata"; //$NON-NLS-1$

    @After
    public void tearDown()
    {
        // Keep the singleton's in-memory session-allow set clean between tests.
        DestructiveConsentGate.getInstance().clearSessionAllow();
    }

    // =====================================================================
    // Step 1 — env EDT_MCP_DESTRUCTIVE_CONSENT (pure classifier, no process env)
    // =====================================================================

    @Test
    public void envAllowForcesAllow()
    {
        assertTrue("'allow' must force the allow bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("allow")); //$NON-NLS-1$
        assertTrue("env value is case-insensitive", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("ALLOW")); //$NON-NLS-1$
        assertTrue("env value is trimmed", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("  allow  ")); //$NON-NLS-1$
    }

    @Test
    public void envAskDoesNotForceAllow()
    {
        assertFalse("'ask' must NOT bypass — the normal decision order applies", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("ask")); //$NON-NLS-1$
        assertFalse("null (unset) must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow(null));
        assertFalse("blank must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("   ")); //$NON-NLS-1$
        assertFalse("an unrelated value must NOT bypass", //$NON-NLS-1$
            DestructiveConsentGate.envForcesAllow("yes")); //$NON-NLS-1$
    }

    // =====================================================================
    // Step 2 — headless: no active UI session -> UNATTENDED (never blocks, never allows)
    // =====================================================================

    @Test
    public void headlessRefusesWithoutPrompt()
    {
        // In the headless unit-test JVM there is no workbench display / active shell, so
        // requireConsent takes the headless path with no SWT. It must REFUSE (issue #566):
        // before that, the absence of a display granted consent on the operator's behalf, so
        // an agent that started EDT headless removed the gate by doing so.
        //
        // Which verdict is correct depends on the environment this JVM was started with, and
        // both branches are a real assertion: with the launch bypass set, step 1 wins BEFORE the
        // headless probe is reached and the answer must still be ALLOW - that bypass is what the
        // unattended e2e suite runs on and this test must not silently certify its removal.
        ConsentDecision decision =
            DestructiveConsentGate.getInstance().requireConsent(TOOL, null);
        if (DestructiveConsentGate.isEnvAllow())
        {
            assertEquals("with EDT_MCP_DESTRUCTIVE_CONSENT=allow, step 1 must still allow", //$NON-NLS-1$
                ConsentDecision.ALLOW, decision);
        }
        else
        {
            assertEquals("Headless / unattended must REFUSE, and never block", //$NON-NLS-1$
                ConsentDecision.UNATTENDED, decision);
        }
    }

    @Test
    public void aSessionAllowDoesNotOutrankTheHeadlessRefusal()
    {
        // Pins the ONE thing about requireConsent that the verdict table above cannot see: the
        // headless probe runs BEFORE the policy, not after. Every other test here calls the pure
        // decide() seam, which never learns whether there is a display; this one drives the whole
        // method with the policy set to ALLOW, so the two steps disagree and the order decides.
        //
        // Before #566 the order genuinely did not matter - both steps answered ALLOW, so a
        // disjunction commuted, and this branch reordered them to spare an already-allowed call
        // the unbounded display.syncExec inside grabActiveShell. #566 killed that equivalence:
        // the headless step now REFUSES, because the absence of a human is not entitled to grant
        // consent on the operator's behalf. Reading the policy first would answer ALLOW here, and
        // equally for ALLOW_ALL and for a Preferences-approved tool - re-opening the fail-open
        // path on exactly the headless EDT #566 closed it for. No verdict-table test catches
        // that: the test JVM has no preference store, so getLevel() answers ASK_ALWAYS, decide()
        // yields PROMPT and the probe runs under either order. Session-allow is the one lever
        // that needs no preference store, so it is the lever this test pulls.
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        gate.allowForSession(TOOL);

        // Positive control. If allowForSession did not take, the policy would say PROMPT, the
        // call would fall through to the probe and refuse under BOTH orders - the pin would pass
        // while measuring nothing, which is precisely how the ratchet this test replaces came to
        // guard the wrong order.
        assertTrue("the policy must actually say ALLOW here, or this test proves nothing", //$NON-NLS-1$
            gate.isSessionAllowed(TOOL));

        ConsentDecision decision = gate.requireConsent(TOOL, null);
        if (DestructiveConsentGate.isEnvAllow())
        {
            // The launch bypass is step 1 and outranks both; assert that it still does rather
            // than skip, so this test cannot quietly certify its removal.
            assertEquals("with EDT_MCP_DESTRUCTIVE_CONSENT=allow, step 1 must still win", //$NON-NLS-1$
                ConsentDecision.ALLOW, decision);
        }
        else
        {
            assertEquals("the headless refusal must outrank a session-allow: with no display " //$NON-NLS-1$
                + "there is nobody to have granted it, and settling the policy first would " //$NON-NLS-1$
                + "return ALLOW for every session-allowed / ALLOW_ALL / per-tool-allowed call " //$NON-NLS-1$
                + "on a headless EDT", ConsentDecision.UNATTENDED, decision); //$NON-NLS-1$
        }
    }

    // =====================================================================
    // Step 3 — in-memory per-tool session-allow
    // =====================================================================

    @Test
    public void sessionAllowShortCircuitsToAllow()
    {
        // Even at the default ASK_ALWAYS level, a session-allowed tool does not prompt.
        assertEquals(Outcome.ALLOW,
            DestructiveConsentGate.decide(true, ConsentSettingsService.Level.ASK_ALWAYS, false));
    }

    @Test
    public void sessionAllowSetIsTracked()
    {
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        assertFalse("clean gate has no session-allow entries", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
        gate.allowForSession(TOOL);
        assertTrue("allowForSession records the tool", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
        gate.clearSessionAllow();
        assertFalse("clearSessionAllow empties the set", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
    }

    // =====================================================================
    // Steps 4/5 — preference level via ConsentSettingsService.Level
    // =====================================================================

    @Test
    public void askAlwaysLevelPrompts()
    {
        assertEquals("ASK_ALWAYS with no session/per-tool allow must PROMPT", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ASK_ALWAYS, false));
    }

    @Test
    public void allowAllLevelAllows()
    {
        assertEquals("ALLOW_ALL must ALLOW without a dialog", //$NON-NLS-1$
            Outcome.ALLOW,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ALLOW_ALL, false));
    }

    @Test
    public void perToolLevelAllowsWhenListed()
    {
        assertEquals("PER_TOOL + tool in allow-set must ALLOW", //$NON-NLS-1$
            Outcome.ALLOW,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.PER_TOOL, true));
    }

    @Test
    public void perToolLevelPromptsWhenNotListed()
    {
        assertEquals("PER_TOOL + tool NOT in allow-set must PROMPT", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.PER_TOOL, false));
    }

    @Test
    public void perToolAllowFlagIsIgnoredAtOtherLevels()
    {
        // The per-tool allow-set only applies at PER_TOOL. A stale allow entry must
        // NOT weaken ASK_ALWAYS.
        assertEquals("ASK_ALWAYS ignores the per-tool allow flag", //$NON-NLS-1$
            Outcome.PROMPT,
            DestructiveConsentGate.decide(false, ConsentSettingsService.Level.ASK_ALWAYS, true));
    }

    // =====================================================================
    // GATED_TOOLS <-> ToolAnnotationClassifier.DESTRUCTIVE_TOOLS relationship
    // =====================================================================

    @Test
    public void gatedToolsAreTheFrozenSet()
    {
        assertEquals("GATED_TOOLS must be exactly the frozen set", //$NON-NLS-1$
            Set.of("delete_metadata", "rename_metadata_object", "delete_project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "delete_infobase", "update_database", "modify_metadata", "dcs", "git", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                "merge_rules", "evaluate_expression"), //$NON-NLS-1$ //$NON-NLS-2$
            DestructiveConsentGate.GATED_TOOLS);
    }

    @Test
    public void everyUnconditionallyGatedToolIsClassifiedDestructive()
    {
        // Every gated tool is a destructive MCP write EXCEPT the CONDITIONALLY destructive ones:
        // modify_metadata (only a type/composite-type change), dcs (only a plain-attribute dynamic-list
        // conversion), git (only the commands that destroy work - see GitTool.destructiveForm), and
        // evaluate_expression (arbitrary BSL: usually a read, occasionally a mutation, and nothing
        // in the call says which - which is exactly why the GATE asks every time while the HINT,
        // one per tool, keeps describing the typical read). Those four carry their own annotations
        // and are deliberately NOT in the always-destructive classifier list.
        //
        // merge_rules is gated conditionally too - only the same-path rewrite asks - and yet it IS
        // classified destructive, so it belongs on the other side of this branch. There is no
        // contradiction: the classifier answers per TOOL and has to describe what the tool CAN do,
        // while the gate is called per CALL and can see which one this is. The four above are
        // absent from ToolAnnotationClassifier.DESTRUCTIVE_TOOLS, so classify() answers "an
        // ordinary write"; merge_rules is IN that list, and its entry there says why.
        Set<String> conditionallyDestructive =
            Set.of("modify_metadata", "dcs", "git", "evaluate_expression"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        for (String tool : DestructiveConsentGate.GATED_TOOLS)
        {
            boolean classifiedDestructive =
                Boolean.TRUE.equals(ToolAnnotationClassifier.classify(tool).getDestructiveHint());
            if (conditionallyDestructive.contains(tool))
            {
                assertFalse(tool + " must NOT be an always-destructive tool", //$NON-NLS-1$
                    classifiedDestructive);
            }
            else
            {
                assertTrue("gated tool '" + tool //$NON-NLS-1$
                    + "' must be classified destructive by ToolAnnotationClassifier", //$NON-NLS-1$
                    classifiedDestructive);
            }
        }
    }

    @Test
    public void deleteLaunchConfigIsDestructiveButNotGated()
    {
        // The only always-destructive tool that is intentionally NOT gated: deleting a
        // launch config is cheap and recoverable (no data loss), so it does not prompt.
        assertTrue("delete_launch_config is an always-destructive tool", //$NON-NLS-1$
            Boolean.TRUE.equals(
                ToolAnnotationClassifier.classify("delete_launch_config").getDestructiveHint())); //$NON-NLS-1$
        assertFalse("delete_launch_config must NOT be gated", //$NON-NLS-1$
            DestructiveConsentGate.GATED_TOOLS.contains("delete_launch_config")); //$NON-NLS-1$
    }

    // =====================================================================
    // Issue #277 — the bounded-wait ConsentArbiter (first-wins, both orders)
    // =====================================================================

    @Test
    public void arbiterFirstDecisionWinsWhenTheRealAnswerComesFirst() throws InterruptedException
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        assertTrue("the first tryDecide call must win the race", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.ALLOW));
        assertFalse("a later tryDecide call must be ignored (already decided)", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.TIMEOUT));
        assertEquals("the winning decision must stick", ConsentDecision.ALLOW, arbiter.peek()); //$NON-NLS-1$
        assertTrue("await must observe the already-recorded decision without waiting", //$NON-NLS-1$
            arbiter.await(0));
    }

    @Test
    public void arbiterFirstDecisionWinsWhenTheTimeoutComesFirst() throws InterruptedException
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        assertTrue("the worker's timeout must win when it decides first", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.TIMEOUT));
        assertFalse("a late dialog answer arriving after the timeout must be ignored", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.REJECT));
        assertEquals("TIMEOUT must stick, not the late REJECT", //$NON-NLS-1$
            ConsentDecision.TIMEOUT, arbiter.peek());
    }

    @Test
    public void arbiterAwaitTimesOutWhenNobodyHasDecidedYet() throws InterruptedException
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        assertFalse("await must time out (return false) with no decision recorded", //$NON-NLS-1$
            arbiter.await(0));
        assertNull("peek must stay null until a decision is recorded", arbiter.peek()); //$NON-NLS-1$
    }

    // =====================================================================
    // Issue #277 — recordDialogAnswer: late "Allow for session" is remembered
    // =====================================================================

    @Test
    public void lateAllowForSessionIsRecordedEvenAfterTheTimeoutWonTheRace()
    {
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        ConsentArbiter arbiter = new ConsentArbiter();
        // The worker's timeout already won the race before the human's answer arrives.
        assertTrue(arbiter.tryDecide(ConsentDecision.TIMEOUT));

        gate.recordDialogAnswer(arbiter, TOOL, DestructiveConsentGate.ALLOW_FOR_SESSION_ID);

        assertEquals("a late answer must NOT overturn the already-decided TIMEOUT", //$NON-NLS-1$
            ConsentDecision.TIMEOUT, arbiter.peek());
        assertTrue("a late 'Allow for session' must still be recorded so the tool is not " //$NON-NLS-1$
            + "re-prompted next time", gate.isSessionAllowed(TOOL)); //$NON-NLS-1$
    }

    @Test
    public void dialogAnswerInTimeWinsTheArbiterAndTheLateTimeoutIsIgnored()
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID);

        assertEquals(ConsentDecision.ALLOW, arbiter.peek());
        assertFalse("the worker's timeout must lose once the dialog already answered", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.TIMEOUT));
        assertEquals(ConsentDecision.ALLOW, arbiter.peek());
    }

    @Test
    public void recordDialogAnswerMapsRejectAndDoesNotTouchSessionAllow()
    {
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        ConsentArbiter arbiter = new ConsentArbiter();

        gate.recordDialogAnswer(arbiter, TOOL, IDialogConstants.CANCEL_ID);

        assertEquals(ConsentDecision.REJECT, arbiter.peek());
        assertFalse("a plain Reject must not add the tool to the session-allow set", //$NON-NLS-1$
            gate.isSessionAllowed(TOOL));
    }

    // =====================================================================
    // Issue #277 — the UI-thread (timerExec) path orderings through the same seams
    // =====================================================================

    @Test
    public void uiThreadTimerWinMapsToTimeoutDespiteTheDefaultOkReturnCode()
    {
        // The UI-thread path's timeout sequence: the timerExec closer wins (TIMEOUT)
        // and force-closes the dialog. A forced Dialog.close() does NOT set a CANCEL
        // return code — JFace Window.returnCode defaults to OK — so open() hands the
        // post-open mapping an OK code. The arbiter, not the code, must carry the
        // verdict: the OK-derived ALLOW candidate loses and nothing enters the
        // session set.
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        ConsentArbiter arbiter = new ConsentArbiter();
        assertTrue("the timer closer must win when nobody answered yet", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.TIMEOUT));

        gate.recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID);

        assertEquals("the default-OK code after a forced close must NOT overturn TIMEOUT", //$NON-NLS-1$
            ConsentDecision.TIMEOUT, arbiter.peek());
        assertFalse("a forced close must not session-allow the tool", //$NON-NLS-1$
            gate.isSessionAllowed(TOOL));
    }

    @Test
    public void uiThreadHumanRejectFirstBeatsTheLateTimer()
    {
        // The UI-thread path's human-first sequence: the human answers Reject inside
        // open(), the answer is mapped first, and the (cancelled-or-late) timer
        // closer must be a harmless no-op.
        DestructiveConsentGate gate = DestructiveConsentGate.getInstance();
        ConsentArbiter arbiter = new ConsentArbiter();

        gate.recordDialogAnswer(arbiter, TOOL, IDialogConstants.CANCEL_ID);

        assertEquals(ConsentDecision.REJECT, arbiter.peek());
        assertFalse("a late timer closer must lose to the human's answer", //$NON-NLS-1$
            arbiter.tryDecide(ConsentDecision.TIMEOUT));
        assertEquals("REJECT must stick, not the late TIMEOUT", //$NON-NLS-1$
            ConsentDecision.REJECT, arbiter.peek());
    }

    // =====================================================================
    // Issue #277 — consentDeniedMessage: REJECT text unchanged, TIMEOUT text actionable
    // =====================================================================

    // =====================================================================
    // The shell probe is BOUNDED: a wedged UI thread is refused, not waited on
    // =====================================================================

    /**
     * The defect this closes: the gate asked the UI thread for a shell with an unbounded
     * syncExec, from a worker thread that is holding the caller's lock. On a workbench whose UI
     * thread is wedged the call never returned, so merge_rules kept its path mutex and every
     * later call for that path queued behind a wait that could not end.
     * <p>
     * A latch that is never counted down IS that wedged UI thread as far as the wait is
     * concerned, which is what makes the deadline testable with no display in the room.
     * </p>
     */
    @Test
    public void aUiThreadThatNeverAnswersIsGivenUpOnRatherThanWaitedFor()
    {
        CountDownLatch neverAnswered = new CountDownLatch(1);

        long startedAt = System.nanoTime();
        LaunchLifecycleUtils.ShellProbe probe =
            LaunchLifecycleUtils.awaitShellAnswer(neverAnswered, new Shell[1], 150L);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("a silent UI thread must end as TIMED_OUT", //$NON-NLS-1$
            LaunchLifecycleUtils.ShellProbeOutcome.TIMED_OUT, probe.outcome());
        assertNull("and hand back no shell", probe.shell()); //$NON-NLS-1$
        assertTrue("it must give up near the budget, not hang: took " + tookMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            tookMs < 10_000L);
    }

    /**
     * The other edge, and the one that keeps #566 intact: a UI thread that ANSWERS "there is no
     * shell" must not be reported as a timeout. The two mean different things to the operator -
     * one says nobody is there, the other says somebody is stuck - and the gate turns them into
     * different verdicts with different remedies.
     */
    @Test
    public void aUiThreadThatAnswersNoShellIsNotATimeout()
    {
        CountDownLatch answered = new CountDownLatch(1);
        answered.countDown();

        LaunchLifecycleUtils.ShellProbe probe =
            LaunchLifecycleUtils.awaitShellAnswer(answered, new Shell[1], 150L);

        assertEquals("an answered probe with no shell is NO_SHELL, not TIMED_OUT", //$NON-NLS-1$
            LaunchLifecycleUtils.ShellProbeOutcome.NO_SHELL, probe.outcome());
    }

    /**
     * The probe must not spend its budget when there is simply no display: a headless runtime is
     * answered from the workbench check, without ever posting to a UI thread.
     */
    @Test
    public void aHeadlessRuntimeIsAnsweredWithoutSpendingTheBudget()
    {
        long startedAt = System.nanoTime();
        LaunchLifecycleUtils.ShellProbe probe =
            LaunchLifecycleUtils.grabActiveShellWithin(30_000L);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertEquals("no workbench means NO_SHELL", //$NON-NLS-1$
            LaunchLifecycleUtils.ShellProbeOutcome.NO_SHELL, probe.outcome());
        assertTrue("and it must answer at once, not wait 30s: took " + tookMs + "ms", //$NON-NLS-1$ //$NON-NLS-2$
            tookMs < 5_000L);
    }

    /**
     * The branch that keeps the bounded probe usable from the UI thread itself:
     * rename_metadata_object asks the gate from INSIDE its {@code syncExec} scope, where posting
     * the question and then waiting on it would block the only thread that could answer - the
     * probe would spend its whole budget and refuse on a perfectly healthy workbench. An answer
     * taken inline is therefore never a timeout; with no shell it is NO_SHELL, a different
     * verdict with a different remedy.
     */
    @Test
    public void anInlineAnswerIsNeverATimeout()
    {
        LaunchLifecycleUtils.ShellProbe probe = LaunchLifecycleUtils.inlineShellAnswer(null);

        assertEquals("an inline read with no shell means NO_SHELL, not TIMED_OUT", //$NON-NLS-1$
            LaunchLifecycleUtils.ShellProbeOutcome.NO_SHELL, probe.outcome());
        assertNull("and it hands back no shell", probe.shell()); //$NON-NLS-1$
    }

    /**
     * The probe timeout and the dialog timeout are DIFFERENT failures and must not share a text:
     * nothing was ever shown here, the wait was the probe's 5 s and not the prompt's 120 s, and a
     * Preferences allowance cannot rescue it because the probe runs BEFORE the policy is read.
     * Telling the operator to answer a dialog promptly sends them after a window that never opened.
     */
    @Test
    public void anUnresponsiveUiIsNotReportedAsAnUnansweredDialog()
    {
        String message =
            DestructiveConsentGate.consentDeniedMessage(ConsentDecision.UI_UNRESPONSIVE, TOOL);

        assertTrue(message, message.contains(TOOL));
        assertTrue("it must name the probe budget: " + message, //$NON-NLS-1$
            message.contains(String.valueOf(DestructiveConsentGate.SHELL_PROBE_TIMEOUT_MS)));
        assertTrue("and say the workbench did not answer: " + message, //$NON-NLS-1$
            message.contains("wedged") || message.contains("did not answer")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("it must NOT quote the dialog budget: " + message, //$NON-NLS-1$
            message.contains(DestructiveConsentGate.CONSENT_PROMPT_TIMEOUT_SECONDS + " s")); //$NON-NLS-1$
        assertFalse("nor send them to a dialog that was never shown: " + message, //$NON-NLS-1$
            message.contains("answer the confirmation dialog")); //$NON-NLS-1$
    }

    /**
     * The mirror direction: the DIALOG timeout keeps its own text, so splitting the two verdicts
     * did not quietly rewrite the case that really is an unanswered prompt.
     */
    @Test
    public void theDialogTimeoutKeepsItsOwnPromptText()
    {
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);

        assertTrue("the dialog verdict still names its 120 s budget: " + message, //$NON-NLS-1$
            message.contains(String.valueOf(DestructiveConsentGate.CONSENT_PROMPT_TIMEOUT_SECONDS)));
        assertTrue("and still points at the dialog: " + message, //$NON-NLS-1$
            message.contains("confirmation dialog")); //$NON-NLS-1$
    }

    @Test
    public void consentDeniedMessageKeepsTheOriginalRejectText()
    {
        assertEquals("Operation declined by user", //$NON-NLS-1$
            DestructiveConsentGate.consentDeniedMessage(ConsentDecision.REJECT, TOOL));
    }

    @Test
    public void consentDeniedMessageForTimeoutNamesToolSecondsAndAllThreeRemedies()
    {
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);

        assertTrue("must name the tool", message.contains(TOOL)); //$NON-NLS-1$
        assertTrue("must mention the timeout seconds", //$NON-NLS-1$
            message.contains("120")); //$NON-NLS-1$
        assertTrue("must mention the Preferences remedy", //$NON-NLS-1$
            message.contains("Preferences")); //$NON-NLS-1$
        assertTrue("must mention the env-bypass remedy", //$NON-NLS-1$
            message.contains("EDT_MCP_DESTRUCTIVE_CONSENT")); //$NON-NLS-1$
        assertTrue("must mention re-running / answering promptly as the third remedy", //$NON-NLS-1$
            message.contains("re-run")); //$NON-NLS-1$
    }

    // =====================================================================
    // Issue #566 — the headless path REFUSES, and says how to opt in
    // =====================================================================

    @Test
    public void consentDeniedMessageForUnattendedNamesTheLaunchBypassAndTheWorkbench()
    {
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.UNATTENDED, TOOL);

        assertTrue("must name the tool", message.contains(TOOL)); //$NON-NLS-1$
        assertTrue("must name the launch bypass that would have allowed it", //$NON-NLS-1$
            message.contains("EDT_MCP_DESTRUCTIVE_CONSENT=allow")); //$NON-NLS-1$
        assertTrue("must say the operation did NOT happen", //$NON-NLS-1$
            message.contains("nothing was changed")); //$NON-NLS-1$
        assertTrue("must point at the other remedy - an EDT with a window", //$NON-NLS-1$
            message.contains("workbench")); //$NON-NLS-1$
        assertNotEquals("it must not be the generic decline text - the operator did not decline", //$NON-NLS-1$
            DestructiveConsentGate.consentDeniedMessage(ConsentDecision.REJECT, TOOL), message);
    }

    @Test
    public void theAuditLineCannotBeForgedOrFloodedByACallersOwnText()
    {
        // A preview's item names are caller-supplied - evaluate_expression puts the whole BSL
        // expression there - and the env-bypass audit line writes them. Two things must not
        // travel into the log verbatim.

        // 1. A newline would end the line and let the caller forge what reads as a fresh
        //    !ENTRY, attributing anything it likes to the plugin.
        String forged = "harmless\n!ENTRY com.ditrix.edt.mcp.server 4 0\r!MESSAGE nothing happened"; //$NON-NLS-1$
        String audited = DestructiveConsentGate.auditSafe(forged);
        assertFalse("no newline may survive into the audit line", audited.contains("\n")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor a carriage return", audited.contains("\r")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("the text itself is still readable", audited.startsWith("harmless ")); //$NON-NLS-1$ //$NON-NLS-2$

        // 2. An expression may be nearly as large as the request-body limit; the log must not
        //    grow with it, and must still say how much was elided.
        String huge = "x".repeat(50_000); //$NON-NLS-1$
        String bounded = DestructiveConsentGate.auditSafe(huge);
        assertTrue("a huge value must be elided, not logged whole: " + bounded.length(), //$NON-NLS-1$
            bounded.length() < 200);
        assertTrue("and the elision must name the real length", bounded.contains("50000")); //$NON-NLS-1$ //$NON-NLS-2$

        // A value that fits is passed through untouched, so the common case stays exact.
        assertEquals("Catalog.Goods", DestructiveConsentGate.auditSafe("Catalog.Goods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("", DestructiveConsentGate.auditSafe(null)); //$NON-NLS-1$
    }

    @Test
    public void theAuditLineRecordsTheShapeOfAnExpressionAndNeverItsText()
    {
        // evaluate_expression's one "name" is the caller's own BSL, and the env-bypass audit
        // line goes to <workspace>/.metadata/.log - a file that outlives the run, rotates into
        // .bak_*.log and travels with bug reports. Sanitising is not redaction: a SHORT
        // expression carrying a password passed auditSafe untouched and was written down.
        String secret = "ConnectToDatabase(\"Password=hunter2;User=admin\")"; //$NON-NLS-1$
        String line = DestructiveConsentGate.describe(ConsentPreview.withUnloggableNames(
            "Evaluate a BSL expression", "runs in the paused application", 1, //$NON-NLS-1$ //$NON-NLS-2$
            Collections.singletonList(secret)));

        assertFalse("no part of the expression may reach the log: " + line, //$NON-NLS-1$
            line.contains("hunter2")); //$NON-NLS-1$
        assertFalse("nor the parameter that carried it: " + line, line.contains("Password")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("nor what it called: " + line, line.contains("ConnectToDatabase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("but the line must still say what was allowed: " + line, //$NON-NLS-1$
            line.contains("Evaluate a BSL expression")); //$NON-NLS-1$
        assertTrue("and how much of it ran: " + line, //$NON-NLS-1$
            line.contains(String.valueOf(secret.length())));
    }

    @Test
    public void anOrdinaryPreviewStillNamesWhatWouldBeDestroyed()
    {
        // The other edge of the same change: redaction must not spread to the previews whose
        // names are identifiers this server chose. For those the names ARE the evidence an
        // unattended delete leaves behind, and a line that only counted them would be useless.
        String line = DestructiveConsentGate.describe(new ConsentPreview(
            "Delete metadata node", "removes it from the configuration", 2, //$NON-NLS-1$ //$NON-NLS-2$
            Arrays.asList("Catalog.Goods", "Document.Invoice"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue("an ordinary preview must still name its targets: " + line, //$NON-NLS-1$
            line.contains("Catalog.Goods")); //$NON-NLS-1$
        assertTrue("all of them, up to the listing cap: " + line, //$NON-NLS-1$
            line.contains("Document.Invoice")); //$NON-NLS-1$
    }

    @Test
    public void unattendedIsARefusalNotAnAllow()
    {
        // The whole point of #566: every caller tests `decision != ALLOW`, so the headless verdict
        // has to be a value that is not ALLOW - if UNATTENDED ever became an alias for it, the
        // gate would be fail-open again and every one of those call sites would proceed.
        assertNotEquals(ConsentDecision.ALLOW, ConsentDecision.UNATTENDED);
        for (ConsentDecision refusal : new ConsentDecision[] {ConsentDecision.REJECT,
            ConsentDecision.TIMEOUT, ConsentDecision.UNATTENDED})
        {
            assertNotEquals("every refusal must be distinguishable from ALLOW", //$NON-NLS-1$
                ConsentDecision.ALLOW, refusal);
        }
    }
}
