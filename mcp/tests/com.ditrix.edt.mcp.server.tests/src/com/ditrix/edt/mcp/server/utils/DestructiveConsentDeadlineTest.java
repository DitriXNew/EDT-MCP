/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.HostKind;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.preferences.PreferenceInitializer;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentArbiter;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;

/**
 * PR #1002 review: one prompt has ONE cutoff. The dialog's advertised expiry, the worker's wait and
 * the verdict share the deadline taken when the prompt started, so an answer after it can never
 * apply the operation however the worker or the UI thread was scheduled; and the refusal names the
 * wait that prompt actually used, not the preference as it reads later.
 */
public class DestructiveConsentDeadlineTest
{
    private static final String TOOL = "delete_project"; //$NON-NLS-1$

    private PreferenceStore store;
    private Field plugin;
    private Object previousPlugin;

    @Before
    public void setUp() throws Exception
    {
        store = new PreferenceStore();
        Activator activator = mock(Activator.class);
        when(activator.getPreferenceStore()).thenReturn(store);
        plugin = Activator.class.getDeclaredField("plugin"); //$NON-NLS-1$
        plugin.setAccessible(true);
        previousPlugin = plugin.get(null);
        plugin.set(null, activator);
        new PreferenceInitializer().initializeDefaultPreferences();
        DestructiveConsentGate.getInstance().clearSessionAllow();
    }

    @After
    public void tearDown() throws Exception
    {
        DestructiveConsentGate.getInstance().clearSessionAllow();
        DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL); // drop a leftover hand-off
        plugin.set(null, previousPlugin);
    }

    @Test
    public void anAnswerAfterTheDeadlineLosesToTheTimeout()
    {
        AtomicLong now = new AtomicLong(1_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        now.set(5_000L);
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID);
        assertEquals("a click at or after the shared deadline must not allow the operation", //$NON-NLS-1$
            ConsentDecision.TIMEOUT, arbiter.peek());
    }

    @Test
    public void anAnswerBeforeTheDeadlineStillDecides()
    {
        AtomicLong now = new AtomicLong(1_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        now.set(4_999L);
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID);
        assertEquals(ConsentDecision.ALLOW, arbiter.peek());
    }

    @Test
    public void aLateAllowForSessionIsStillRemembered()
    {
        AtomicLong now = new AtomicLong(9_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL,
            DestructiveConsentGate.ALLOW_FOR_SESSION_ID);
        assertEquals(ConsentDecision.TIMEOUT, arbiter.peek());
        assertTrue("the session allowance keeps its #277 behaviour", //$NON-NLS-1$
            DestructiveConsentGate.getInstance().isSessionAllowed(TOOL));
    }

    @Test
    public void aThreadThatArrivesLateWaitsOnlyWhatIsLeftOfTheSharedDeadline()
    {
        // A 15 s prompt whose worker reaches its wait 14.8 s in: 200 ms are left, not a fresh 15 s.
        AtomicLong now = new AtomicLong(0L);
        ConsentArbiter arbiter = new ConsentArbiter(TimeUnit.SECONDS.toNanos(15), now::get);
        now.set(TimeUnit.MILLISECONDS.toNanos(14_800));
        assertEquals(200L, arbiter.remainingMillis());
        now.set(TimeUnit.SECONDS.toNanos(15) - 1L);
        assertEquals("rounded up, so a wait never ends before the cutoff", 1L, arbiter.remainingMillis()); //$NON-NLS-1$
        now.set(TimeUnit.SECONDS.toNanos(15));
        assertEquals(0L, arbiter.remainingMillis());
        now.set(TimeUnit.SECONDS.toNanos(20));
        assertEquals(0L, arbiter.remainingMillis());
    }

    @Test(timeout = 10_000)
    public void theWaitActuallyLastsUntilTheDeadline() throws Exception
    {
        long cutoff = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        ConsentArbiter arbiter = ConsentArbiter.until(cutoff);
        assertFalse(arbiter.awaitUntilDeadline());
        long early = cutoff - System.nanoTime();
        assertTrue("an undecided prompt must not stop waiting before its cutoff (" + early + " ns early)", //$NON-NLS-1$ //$NON-NLS-2$
            early <= 0);
    }

    @Test(timeout = 20_000)
    public void aThreadPausedAfterReadingTheTimeLeftDoesNotWaitThatSpanPastTheCutoff() throws Exception
    {
        // The first clock read says 5 s are left; every later read says the cutoff has passed - the
        // thread was descheduled between reading the time and starting to wait.
        long cutoff = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        AtomicInteger reads = new AtomicInteger();
        ConsentArbiter arbiter = new ConsentArbiter(cutoff,
            () -> reads.getAndIncrement() == 0 ? cutoff - TimeUnit.SECONDS.toNanos(5) : cutoff + 1L);
        long before = System.nanoTime();
        assertFalse(arbiter.awaitUntilDeadline());
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before);
        assertTrue("re-read the clock instead of waiting the stale 5 s: waited " + waitedMs + " ms", //$NON-NLS-1$ //$NON-NLS-2$
            waitedMs < 3_000);
    }

    @Test
    public void aDialogIsNotOpenedOnceTheCutoffHasPassed()
    {
        AtomicLong now = new AtomicLong(1_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        assertTrue(DestructiveConsentGate.mayOpenDialog(false, arbiter));
        now.set(5_000L);
        assertFalse("past the cutoff the dialog could no longer approve the call", //$NON-NLS-1$
            DestructiveConsentGate.mayOpenDialog(false, arbiter));
        assertTrue("an arbiter without a cutoff still opens", //$NON-NLS-1$
            DestructiveConsentGate.mayOpenDialog(false, new ConsentArbiter()));
    }

    @Test(timeout = 10_000)
    public void aDeadlineAlreadyPassedDoesNotWaitAtAll() throws Exception
    {
        ConsentArbiter arbiter = new ConsentArbiter(System.nanoTime() - 1L, System::nanoTime);
        long before = System.nanoTime();
        assertFalse(arbiter.awaitUntilDeadline());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_000);
        assertTrue("a decided prompt reports it", arbiter.tryDecide(ConsentDecision.REJECT)); //$NON-NLS-1$
        assertTrue(arbiter.awaitUntilDeadline());
    }

    @Test
    public void aTimelyPressIsJudgedWhenItWasPressedNotWhenClosingFinished()
    {
        // Disposing the shell and cancelling the closer took the clock past the cutoff after the press.
        AtomicLong now = new AtomicLong(1_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        now.set(6_000L);
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID,
            OptionalLong.of(4_900L));
        assertEquals(ConsentDecision.ALLOW, arbiter.peek());
    }

    @Test
    public void aPressAfterTheCutoffLosesEvenWhenReportedEarly()
    {
        AtomicLong now = new AtomicLong(1_000L);
        ConsentArbiter arbiter = new ConsentArbiter(5_000L, now::get);
        DestructiveConsentGate.getInstance().recordDialogAnswer(arbiter, TOOL, IDialogConstants.OK_ID,
            OptionalLong.of(5_000L));
        assertEquals(ConsentDecision.TIMEOUT, arbiter.peek());
    }

    @Test
    public void theRefusalNamesTheWaitItsPromptUsedNotTheChangedPreference()
    {
        DestructiveConsentGate.finishPrompt(ConsentDecision.TIMEOUT, 15);
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 600);
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
        assertTrue(message, message.contains("within 15 s")); //$NON-NLS-1$
        // Consumed once: a later refusal on this thread without a prompt reads the setting.
        String later = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
        assertTrue(later, later.contains("within 600 s")); //$NON-NLS-1$
    }

    @Test
    public void thePromptsWaitStaysWithTheThreadThatAsked() throws Exception
    {
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 90);
        DestructiveConsentGate.finishPrompt(ConsentDecision.TIMEOUT, 15);
        AtomicReference<String> other = new AtomicReference<>();
        Thread thread = new Thread(() -> other.set(
            DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL)));
        thread.start();
        thread.join();
        assertTrue(other.get(), other.get().contains("within 90 s")); //$NON-NLS-1$
        assertTrue(DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL).contains("within 15 s")); //$NON-NLS-1$
    }

    @Test
    public void aNewConsentRequestForgetsAnUnreportedTimeout()
    {
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 90);
        DestructiveConsentGate.finishPrompt(ConsentDecision.TIMEOUT, 15);
        assertEquals(ConsentDecision.ALLOW,
            DestructiveConsentGate.getInstance().requireConsent(TOOL, null, HostKind.HEADLESS, false));
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
        assertTrue(message, message.contains("within 90 s")); //$NON-NLS-1$
    }

    @Test
    public void aLateAllowForSessionIsToldNotToExpectTheDialogAgain()
    {
        // The press came after the cutoff: the call is refused, but the session allowance is kept
        // (#277), so a repeat runs without a dialog - the refusal must not promise one.
        DestructiveConsentGate.finishPrompt(ConsentDecision.TIMEOUT, 15);
        DestructiveConsentGate.getInstance().allowForSession(TOOL);
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
        assertTrue(message, message.contains("within 15 s")); //$NON-NLS-1$
        assertTrue(message, message.contains("repeating the same call now runs it WITHOUT a dialog")); //$NON-NLS-1$
        assertFalse(message, message.contains("the dialog opens again")); //$NON-NLS-1$
    }

    @Test
    public void onlyATimeoutLeavesItsWaitBehind()
    {
        store.setValue(PreferenceConstants.PREF_DESTRUCTIVE_CONSENT_WAIT_SECONDS, 90);
        assertEquals(ConsentDecision.REJECT, DestructiveConsentGate.finishPrompt(ConsentDecision.REJECT, 15));
        String message = DestructiveConsentGate.consentDeniedMessage(ConsentDecision.TIMEOUT, TOOL);
        assertTrue(message, message.contains("within 90 s")); //$NON-NLS-1$
    }
}
