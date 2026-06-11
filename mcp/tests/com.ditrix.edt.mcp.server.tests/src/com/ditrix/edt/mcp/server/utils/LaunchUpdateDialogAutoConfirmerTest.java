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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.swt.widgets.Display;
import org.junit.Test;

/**
 * Tests for the pure decision of {@link LaunchUpdateDialogAutoConfirmer}: which
 * shell title is the "Application update" launch modal it auto-confirms.
 *
 * <p>The SWT plumbing ({@code arm}/{@code disarm} + the {@code Display} filter)
 * is exercised only live (it needs a real workbench); here we lock down the
 * exact-match contract so the filter never fires on an unrelated dialog, plus
 * the no-op safety of an unbalanced {@code disarm} and of {@code arm} in a
 * headless (no-workbench) runtime.
 *
 * <p>EDT ships the modal title in two locales (English / Russian); both MUST
 * match, because an English-only match silently fails on a Russian-locale
 * EDT and the unattended launch then hangs on the un-dismissed modal.
 */
public class LaunchUpdateDialogAutoConfirmerTest
{
    /**
     * Russian title of the modal ("Обновление приложения"), {@code \\uXXXX}-escaped
     * just like the production constant so this test compiles identically whatever
     * source encoding the Tycho compiler picks for the test bundle (which has no
     * explicit encoding setting, unlike the production bundle).
     */
    private static final String RUSSIAN_TITLE =
        "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435 \u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F";

    @Test
    public void testExactTitleMatches()
    {
        assertTrue("the exact EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update"));
    }

    @Test
    public void testRussianTitleMatches()
    {
        // EDT localizes this dialog title — the Russian modal
        // title must match too, or the auto-confirm filter never fires and the launch
        // hangs on the modal.
        assertTrue("the Russian EDT modal title must match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(RUSSIAN_TITLE));
    }

    @Test
    public void testRussianConstantMatchesExpectedString()
    {
        // Guards against a typo in the escaped production constant: it must equal
        // the exact string EDT's messages_ru.properties renders. Both sides are
        // \\uXXXX-escaped, so the assertion is encoding-independent; we also pin the
        // decoded length so a wrong escape can't accidentally still pass.
        assertEquals("the Russian title constant must equal the expected localized string",
            RUSSIAN_TITLE, LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU);
        assertEquals("the Russian title is 'Обновление приложения' (21 chars)",
            21, LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLE_RU.length());
    }

    @Test
    public void testTitleSetHasBothLocales()
    {
        assertTrue("the known-titles set must contain the English title",
            LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLES.contains("Application update"));
        assertTrue("the known-titles set must contain the Russian title",
            LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLES.contains(RUSSIAN_TITLE));
    }

    @Test
    public void testDifferentTitleDoesNotMatch()
    {
        assertFalse("an unrelated dialog title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Save resources"));
    }

    @Test
    public void testMatchIsCaseSensitive()
    {
        assertFalse("matching is exact, not case-insensitive",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("application update"));
    }

    @Test
    public void testWhitespaceVariantDoesNotMatch()
    {
        assertFalse("a trailing-space variant must not match (exact compare)",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Application update "));
    }

    @Test
    public void testNullTitleDoesNotMatch()
    {
        assertFalse("a null shell title must not match",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(null));
    }

    /**
     * Russian body prefix of the code-1003 "Debug session already exists" modal
     * ("Сессия отладки для проекта"), {@code \\uXXXX}-escaped exactly like the
     * production constant so it compiles identically whatever encoding the Tycho
     * compiler picks for the test bundle.
     */
    private static final String RUSSIAN_BODY_PREFIX =
        "\u0421\u0435\u0441\u0441\u0438\u044F \u043E\u0442\u043B\u0430\u0434\u043A\u0438 "
            + "\u0434\u043B\u044F \u043F\u0440\u043E\u0435\u043A\u0442\u0430";

    // === code-1003 "Debug session already exists" body-prefix matcher ===

    @Test
    public void testDebugSessionExistsEnglishBodyMatches()
    {
        // The full English message starts with the matched prefix; the two
        // interpolated names follow it, so a startsWith() match is required.
        assertTrue("the English 1003 message body must match",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody(
                "Debug session for project \"MyProject\" and application \"Main\" "
                    + "has already been started.\nShould it be stopped?"));
    }

    @Test
    public void testDebugSessionExistsRussianBodyMatches()
    {
        // EDT localizes this modal body — the Russian body prefix
        // must match too, or the safety-net auto-confirm never fires on the 1003 modal.
        assertTrue("the Russian 1003 message body must match",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody(
                RUSSIAN_BODY_PREFIX + " \"\u041F\u0440\u043E\u0435\u043A\u0442\""));
    }

    @Test
    public void testDebugSessionExistsRussianConstantDecodesCorrectly()
    {
        // Guard against a typo in the escaped production constant.
        assertEquals("the Russian body-prefix constant must equal the expected string",
            RUSSIAN_BODY_PREFIX, LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_EXISTS_BODY_PREFIX_RU);
        assertEquals("the Russian body prefix is 'Сессия отладки для проекта' (26 chars)",
            26, LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_EXISTS_BODY_PREFIX_RU.length());
    }

    @Test
    public void testBareQuestionBodyDoesNotMatch()
    {
        // CRITICAL: a generic question dialog (the 1003 modal's shell title is the
        // generic "Question") whose body is NOT the debug-session message must NOT
        // match — else the safety net would auto-dismiss every question dialog.
        assertFalse("an unrelated question-dialog body must not match",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody(
                "Are you sure you want to delete this object?"));
    }

    @Test
    public void testNullBodyDoesNotMatch()
    {
        assertFalse("a null dialog body must not match",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody(null));
    }

    @Test
    public void testEmptyBodyDoesNotMatch()
    {
        assertFalse("an empty dialog body must not match",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody(""));
    }

    @Test
    public void testBodyPrefixSetHasBothLocales()
    {
        assertTrue("the 1003 body-prefix set must contain the English prefix",
            LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_EXISTS_BODY_PREFIXES
                .contains("Debug session for project"));
        assertTrue("the 1003 body-prefix set must contain the Russian prefix",
            LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_EXISTS_BODY_PREFIXES
                .contains(RUSSIAN_BODY_PREFIX));
    }

    @Test
    public void testUpdateTitleBodyMatcherDisjoint()
    {
        // The two matchers must stay disjoint: the update modal's TITLE must not be
        // mistaken for a 1003 body, and the 1003 body must not be a known update title.
        assertFalse("the update-modal title must not match the 1003 body matcher",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody("Application update"));
        assertFalse("a 1003 body must not be matched as an update-modal title",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle(
                "Debug session for project \"X\" and application \"Y\" has already been started."));
    }

    // === independently-gated matchers (shouldAutoConfirm) ===

    /** English 1003 body, as the live modal renders it (prefix + interpolated names). */
    private static final String DEBUG_SESSION_BODY =
        "Debug session for project \"MyProject\" and application \"Main\" "
            + "has already been started.\nShould it be stopped?";

    @Test
    public void testUpdateOnlyArmConfirmsUpdateTitle()
    {
        // update-only arm (the back-compat arm()/the yaxunit callers): the update
        // TITLE is auto-confirmed.
        assertTrue("update-only arm must confirm the update-modal title",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, false, "Application update", null));
    }

    @Test
    public void testUpdateOnlyArmIgnoresSessionBody()
    {
        // CRITICAL (split contract): an update-only arm must NOT react to the 1003
        // body — only the session matcher owns that dialog. The 1003 modal's shell
        // title is the generic "Question", which is not the update title either.
        assertFalse("update-only arm must ignore the 1003 session body",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, false, "Question", DEBUG_SESSION_BODY));
    }

    @Test
    public void testSessionOnlyArmConfirmsSessionBody()
    {
        // session-only arm (the debug path with updateBeforeLaunch=false): the 1003
        // body IS auto-confirmed even though the update matcher is disarmed.
        assertTrue("session-only arm must confirm the 1003 session body",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, true, "Question", DEBUG_SESSION_BODY));
    }

    @Test
    public void testSessionOnlyArmIgnoresUpdateTitle()
    {
        // CRITICAL (the update opt-out): a session-only arm must NOT auto-press the
        // "Application update" modal — that would perform the DB update the caller
        // opted out of (updateBeforeLaunch=false). Only the update matcher owns it.
        assertFalse("session-only arm must ignore the update-modal title (preserve opt-out)",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, true, "Application update", null));
    }

    @Test
    public void testBothArmedHandlesUpdateTitleAndSessionBody()
    {
        // both-arm (the debug path with updateBeforeLaunch=true): each matcher fires
        // for its own dialog.
        assertTrue("both-arm must confirm the update-modal title",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, "Application update", null));
        assertTrue("both-arm must confirm the 1003 session body",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, "Question", DEBUG_SESSION_BODY));
    }

    @Test
    public void testNeitherArmedConfirmsNothing()
    {
        // With no matcher armed nothing is auto-pressed, even for the exact modals.
        assertFalse("a disarmed confirmer must not press the update modal",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, "Application update", null));
        assertFalse("a disarmed confirmer must not press the 1003 modal",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, "Question", DEBUG_SESSION_BODY));
    }

    @Test
    public void testBothArmedIgnoresUnrelatedDialog()
    {
        // Even with both matchers armed, an unrelated dialog (neither update title
        // nor 1003 body) is left untouched.
        assertFalse("an unrelated dialog must not be auto-confirmed by either matcher",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, "Save resources",
                "Are you sure you want to delete this object?"));
    }

    @Test
    public void testUpdateOnlyArmIgnoresNullTitleAndBody()
    {
        assertFalse("null title/body must not be confirmed",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, null, null));
    }

    // === 1003 modal presses "Keep existing and start new" (LAUNCH_ANYWAY), not default ===

    /**
     * Russian label of the 1003 "keep existing and start new" button
     * ("Сохранить старую и запустить новую"), {@code \\uXXXX}-escaped exactly like the
     * production constant so this test compiles identically whatever encoding the Tycho
     * compiler picks for the test bundle.
     */
    private static final String RUSSIAN_KEEP_BUTTON =
        "\u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c \u0441\u0442\u0430\u0440\u0443\u044e "
            + "\u0438 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u043d\u043e\u0432\u0443\u044e";

    @Test
    public void testKeepButtonEnglishLabelMatches()
    {
        // The 1003 modal's LAUNCH_ANYWAY button must be recognized by its English label
        // so it is pressed instead of the default "stop existing" button.
        assertTrue("the English 'keep existing and start new' label must match",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel("Keep existing and start new"));
    }

    @Test
    public void testKeepButtonRussianLabelMatches()
    {
        // EDT localizes this button label — the Russian
        // LAUNCH_ANYWAY button label must match too, or the 1003 safety net would press the
        // default "stop existing" button and kill the server/other client.
        assertTrue("the Russian 'keep existing and start new' label must match",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel(RUSSIAN_KEEP_BUTTON));
    }

    @Test
    public void testKeepButtonRussianConstantDecodesCorrectly()
    {
        // Guard against a typo in the escaped production constant: it must equal the
        // exact label EDT renders, and we pin the decoded length so a wrong escape can't
        // silently still pass.
        assertEquals("the Russian keep-button constant must equal the expected string",
            RUSSIAN_KEEP_BUTTON, LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_KEEP_BUTTON_RU);
        assertEquals("the Russian keep-button label is 'Сохранить старую и запустить новую' (34 chars)",
            34, LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_KEEP_BUTTON_RU.length());
    }

    @Test
    public void testKeepButtonStripsMnemonicMarker()
    {
        // JFace may render a '&' mnemonic in the button label; the matcher strips it
        // before the exact compare so the button is still recognized.
        assertTrue("a mnemonic '&' in the label must not defeat the match",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel("&Keep existing and start new"));
    }

    @Test
    public void testKeepButtonDefaultStopLabelDoesNotMatch()
    {
        // CRITICAL: the DEFAULT (index 0) button "Stop existing and start new" /
        // "Завершить старую и запустить новую" (RESTART_APPLICATION) must NOT be
        // recognized as the keep-button — pressing it would terminate the existing
        // session, the exact regression this guards against.
        assertFalse("the default 'stop existing' button must NOT match the keep-button",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel("Stop existing and start new"));
        assertFalse("the Russian 'stop existing' button must NOT match the keep-button",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel(
                "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044c \u0441\u0442\u0430\u0440\u0443\u044e "
                    + "\u0438 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u043d\u043e\u0432\u0443\u044e"));
    }

    @Test
    public void testKeepButtonNullAndUnrelatedLabelsDoNotMatch()
    {
        assertFalse("a null label must not match", LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel(null));
        assertFalse("an empty label must not match", LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel(""));
        assertFalse("an unrelated button label must not match",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel("Cancel"));
        assertFalse("the update modal's button must not match the keep-button",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel("Update then run"));
    }

    @Test
    public void testKeepButtonSetHasBothLocales()
    {
        assertTrue("the keep-button set must contain the English label",
            LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_KEEP_BUTTONS
                .contains("Keep existing and start new"));
        assertTrue("the keep-button set must contain the Russian label",
            LaunchUpdateDialogAutoConfirmer.DEBUG_SESSION_KEEP_BUTTONS.contains(RUSSIAN_KEEP_BUTTON));
    }

    @Test
    public void testKeepButtonAndBodyMatchersDisjoint()
    {
        // The keep-button LABEL matcher and the 1003 BODY matcher are distinct concerns:
        // a button label is not a message body, and vice versa.
        assertFalse("a keep-button label must not be matched as a 1003 body",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody("Keep existing and start new"));
        assertFalse("a 1003 body must not be matched as a keep-button label",
            LaunchUpdateDialogAutoConfirmer.isKeepExistingLabel(DEBUG_SESSION_BODY));
    }

    @Test
    public void testUnbalancedDisarmIsNoOp()
    {
        // Releasing without a prior arm() must not throw or touch a Display:
        // with no filter installed it returns before any UI access.
        LaunchUpdateDialogAutoConfirmer.disarm();
        LaunchUpdateDialogAutoConfirmer.disarm();
    }

    @Test
    public void testUnbalancedSplitDisarmIsNoOp()
    {
        // The split disarm(boolean, boolean) overload is equally safe to call
        // unbalanced (e.g. a finally after a headless no-op arm).
        LaunchUpdateDialogAutoConfirmer.disarm(true, true);
        LaunchUpdateDialogAutoConfirmer.disarm(false, true);
        LaunchUpdateDialogAutoConfirmer.disarm(true, false);
    }

    @Test
    public void testArmWithBothFlagsFalseIsNoOp()
    {
        // arm(false, false) requests nothing — a pure no-op (and its mirror disarm).
        LaunchUpdateDialogAutoConfirmer.arm(false, false);
        LaunchUpdateDialogAutoConfirmer.disarm(false, false);
    }

    @Test
    public void testArmWithoutWorkbenchIsNoOpAndCreatesNoDisplay() throws Exception
    {
        // The display probe must NEVER create a display.
        // The previous Display.getDefault() probe either CREATED a display owned
        // by the calling (non-UI) thread on the first arm() of the headless
        // sync-launch path, or — when another thread already owned the default
        // display — blocked forever in syncExec against an event loop nobody
        // pumps. No workbench runs in this harness, so arm() must be a complete
        // no-op, and the paired disarm() must stay a no-op too.
        //
        // The check runs on a FRESH thread to stay order-independent: other
        // tests in this suite exercise production code that calls
        // Display.getDefault() on the shared surefire thread, so that thread
        // may legitimately own a display by the time this test runs. On a new
        // thread both old failure modes are observable: a created display shows
        // up via Display.getCurrent(), and a blocking syncExec trips the join
        // timeout.
        AtomicReference<Display> created = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            LaunchUpdateDialogAutoConfirmer.arm();
            try
            {
                created.set(Display.getCurrent());
            }
            catch (LinkageError e)
            {
                // SWT natives are not loadable in this environment — then no
                // display can exist at all and the contract trivially holds.
            }
            finally
            {
                LaunchUpdateDialogAutoConfirmer.disarm();
            }
        }, "confirmer-headless-arm-probe"); //$NON-NLS-1$
        // A regression that blocks inside arm() must fail the test, not wedge the JVM.
        worker.setDaemon(true);
        worker.start();
        worker.join(10_000);
        assertFalse("arm() must return promptly, not syncExec onto a never-pumped display",
            worker.isAlive());
        assertNull("arm() must not create a display on the calling thread", created.get());
    }
}
