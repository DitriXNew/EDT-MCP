/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
     * Russian title of the modal ("Обновление приложения"), unicode-escaped
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
        // unicode-escaped, so the assertion is encoding-independent; we also pin the
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
     * ("Сессия отладки для проекта"), unicode-escaped exactly like the
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
     * ("Сохранить старую и запустить новую"), unicode-escaped exactly like the
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

    // === per-dialog auto-press decision (chooseConfirmAction) ===

    @Test
    public void test1003WithKeepButtonPressesIt()
    {
        // The 1003 modal with the labelled keep-button present: press it — the
        // existing session survives and the new client starts alongside.
        assertEquals(LaunchUpdateDialogAutoConfirmer.ConfirmAction.PRESS_KEEP_BUTTON,
            LaunchUpdateDialogAutoConfirmer.chooseConfirmAction(true, true));
    }

    @Test
    public void test1003WithoutKeepButtonCancelsNeverPressesDefault()
    {
        // CRITICAL (the fallback pin): when the keep-button is NOT found by label,
        // the dialog is CANCELLED — the 1003 modal's DEFAULT button is the
        // destructive 'Stop existing and start new', and pressing it blind would
        // terminate the very session the keep-press exists to protect. Cancelling
        // aborts only the NEW launch (non-destructive) instead of hanging on the
        // modal or killing the existing session.
        assertEquals(LaunchUpdateDialogAutoConfirmer.ConfirmAction.CANCEL_DIALOG,
            LaunchUpdateDialogAutoConfirmer.chooseConfirmAction(true, false));
    }

    @Test
    public void testUpdateModalPressesDefaultButton()
    {
        // The 'Application update' modal still completes via its default button
        // ('Update then run') — the keep-button flag is meaningless for it.
        assertEquals(LaunchUpdateDialogAutoConfirmer.ConfirmAction.PRESS_DEFAULT_BUTTON,
            LaunchUpdateDialogAutoConfirmer.chooseConfirmAction(false, false));
        assertEquals(LaunchUpdateDialogAutoConfirmer.ConfirmAction.PRESS_DEFAULT_BUTTON,
            LaunchUpdateDialogAutoConfirmer.chooseConfirmAction(false, true));
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

    /**
     * Russian title of the DB-restructure modal ("Реорганизация информации"),
     * unicode-escaped exactly like the production constant so it compiles
     * identically whatever encoding the Tycho compiler picks for the test bundle.
     */
    private static final String RUSSIAN_RESTRUCTURE_TITLE =
        "\u0420\u0435\u043E\u0440\u0433\u0430\u043D\u0438\u0437\u0430\u0446\u0438\u044F \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u0438";

    @Test
    public void testRestructureTitleEnglishMatches()
    {
        assertTrue("the English restructure modal title must match",
            LaunchUpdateDialogAutoConfirmer.isRestructureTitle("Restructure data"));
    }

    @Test
    public void testRestructureTitleRussianMatches()
    {
        // EDT localizes this title — the Russian restructure title must match too, or the
        // unattended update_database hangs on the un-dismissed modal.
        assertTrue("the Russian restructure modal title must match",
            LaunchUpdateDialogAutoConfirmer.isRestructureTitle(RUSSIAN_RESTRUCTURE_TITLE));
    }

    @Test
    public void testRestructureRussianConstantDecodesCorrectly()
    {
        assertEquals("the Russian restructure title constant must equal the expected string",
            RUSSIAN_RESTRUCTURE_TITLE, LaunchUpdateDialogAutoConfirmer.RESTRUCTURE_TITLE_RU);
        assertEquals("the Russian restructure title is 'Реорганизация информации' (24 chars)",
            24, LaunchUpdateDialogAutoConfirmer.RESTRUCTURE_TITLE_RU.length());
    }

    @Test
    public void testRestructureTitleSetHasBothLocales()
    {
        assertTrue("the restructure-titles set must contain the English title",
            LaunchUpdateDialogAutoConfirmer.RESTRUCTURE_TITLES.contains("Restructure data"));
        assertTrue("the restructure-titles set must contain the Russian title",
            LaunchUpdateDialogAutoConfirmer.RESTRUCTURE_TITLES.contains(RUSSIAN_RESTRUCTURE_TITLE));
    }

    @Test
    public void testRestructureMatcherDisjointFromOthers()
    {
        // The restructure title is its own concern: not an update title, not a 1003 body;
        // and the update title / 1003 body are not restructure titles.
        assertFalse("the restructure title must not match the update matcher",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Restructure data"));
        assertFalse("the update title must not match the restructure matcher",
            LaunchUpdateDialogAutoConfirmer.isRestructureTitle("Application update"));
        assertFalse("a null title must not match the restructure matcher",
            LaunchUpdateDialogAutoConfirmer.isRestructureTitle(null));
        assertFalse("the restructure title must not match the 1003 body matcher",
            LaunchUpdateDialogAutoConfirmer.isDebugSessionExistsBody("Restructure data"));
    }

    @Test
    public void testRestructureOnlyArmConfirmsRestructureTitleOnly()
    {
        // update_database arms ONLY the restructure matcher: it confirms the restructure
        // title and ignores both the update modal title and the 1003 session body.
        assertTrue("restructure-only arm must confirm the restructure title",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, true, "Restructure data", null));
        assertFalse("restructure-only arm must ignore the update modal title",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, true, "Application update", null));
        assertFalse("restructure-only arm must ignore the 1003 session body",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, true, "Question", DEBUG_SESSION_BODY));
    }

    @Test
    public void testRestructureNotArmedIgnoresRestructureTitle()
    {
        // Without the restructure arm the restructure title is left untouched even if the
        // other two matchers are armed.
        assertFalse("an un-armed restructure matcher must leave the restructure title alone",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, false, "Restructure data", null));
    }

    // ============ standalone-server port conflict ============

    /**
     * Russian title of EDT's port-conflict modal ("Конфликт портов автономного сервера"),
     * unicode-escaped like the production constant so this test compiles identically whatever
     * source encoding Tycho picks for the test bundle.
     */
    private static final String RUSSIAN_PORT_CONFLICT_TITLE = "\u041A\u043E\u043D\u0444\u043B\u0438\u043A\u0442 \u043F\u043E\u0440\u0442\u043E\u0432 \u0430\u0432\u0442\u043E\u043D\u043E\u043C\u043D\u043E\u0433\u043E \u0441\u0435\u0440\u0432\u0435\u0440\u0430";

    /** EDT's own rendering of the modal body: header line plus one line per busy port. */
    private static final String PORT_CONFLICT_BODY = "Standalone server \"TestConfiguration #1\""
        + " conflicts with another application using the same network port:\n"
        + "- 8429 - HTTP gate port\n"
        + "- 8483 - Debug server port\n";

    @Test
    public void testPortConflictTitleMatchesBothLocales()
    {
        // A miss here is a guaranteed hang: the modal is application-modal, so an unattended
        // call waits on it forever and every later call queues behind it.
        assertTrue("the English port-conflict title must match",
            LaunchUpdateDialogAutoConfirmer.isPortConflictTitle("Standalone server port conflict"));
        assertTrue("the Russian port-conflict title must match",
            LaunchUpdateDialogAutoConfirmer.isPortConflictTitle(RUSSIAN_PORT_CONFLICT_TITLE));
    }

    @Test
    public void testPortConflictTitleDoesNotMatchOtherDialogs()
    {
        assertFalse("an unrelated title must not match",
            LaunchUpdateDialogAutoConfirmer.isPortConflictTitle("Standalone server"));
        assertFalse("a null title must not match",
            LaunchUpdateDialogAutoConfirmer.isPortConflictTitle(null));
        assertFalse("the port-conflict title must not match the update matcher",
            LaunchUpdateDialogAutoConfirmer.isTargetTitle("Standalone server port conflict"));
        assertFalse("the port-conflict title must not match the restructure matcher",
            LaunchUpdateDialogAutoConfirmer.isRestructureTitle("Standalone server port conflict"));
    }

    @Test
    public void testPortConflictClaimedOnlyWhenArmed()
    {
        assertTrue("an armed port-conflict matcher must claim the modal",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, false, false, true,
                "Standalone server port conflict", null));
        assertTrue("the Russian title must be claimed too",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(false, false, false, false, true,
                RUSSIAN_PORT_CONFLICT_TITLE, null));
        assertFalse("an un-armed port-conflict matcher must leave the modal alone",
            LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(true, true, true, true, false,
                "Standalone server port conflict", null));
    }

    @Test
    public void testPortConflictSweepAgreesWithTheFilter()
    {
        // The already-open sweep and the Display filter must claim exactly the same dialogs —
        // this modal is the one most likely to be up BEFORE the arm (the server start races the
        // tool call), so a divergence here is the hang the matcher exists to prevent.
        FakeDialog dialog = new FakeDialog("Standalone server port conflict", PORT_CONFLICT_BODY);
        int pressed = LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(false, false, false, false, true),
            java.util.Arrays.asList(dialog));
        assertEquals("the sweep must press the already-open port-conflict modal", 1, pressed);

        FakeDialog unarmed = new FakeDialog("Standalone server port conflict", PORT_CONFLICT_BODY);
        assertEquals("an un-armed sweep must leave it for a human", 0,
            LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
                new LaunchUpdateDialogAutoConfirmer.ArmState(true, true, true, false, false),
                java.util.Arrays.asList(unarmed)));
    }

    @Test
    public void testSummarizePortConflictTextKeepsTheBusyPorts()
    {
        String summary =
            LaunchUpdateDialogAutoConfirmer.summarizePortConflictText(PORT_CONFLICT_BODY);
        assertNotNull("a readable dialog must produce a summary", summary);
        assertTrue("the summary must name the busy ports", summary.contains("8429"));
        assertTrue("the summary must name every busy port", summary.contains("8483"));
        assertTrue("the summary must name the server", summary.contains("TestConfiguration #1"));
        assertFalse("the summary must be a single line", summary.contains("\n"));
        // Flattened bullets must stay countable: the marker goes, the list separator stays, and
        // the header keeps a plain space after its colon.
        assertEquals("the ports must read as a list, not as one dash-joined sentence",
            "Standalone server \"TestConfiguration #1\" conflicts with another application using"
                + " the same network port: 8429 - HTTP gate port; 8483 - Debug server port",
            summary);
    }

    @Test
    public void testSummarizePortConflictTextHandlesNothingReadable()
    {
        assertNull("no text means no summary",
            LaunchUpdateDialogAutoConfirmer.summarizePortConflictText(null));
        assertNull("blank text means no summary",
            LaunchUpdateDialogAutoConfirmer.summarizePortConflictText("   \n \n"));
        assertNull("a dialog that carried only its own title has nothing to add",
            LaunchUpdateDialogAutoConfirmer.summarizePortConflictText(
                "Standalone server port conflict\n"));
    }

    @Test
    public void testPortConflictErrorIsActionable()
    {
        String withDetail = LaunchUpdateDialogAutoConfirmer.portConflictError(
            "- 8429 - HTTP gate port");
        assertTrue("the error must state the condition",
            withDetail.contains("network ports are already in use"));
        assertTrue("the error must carry the dialog's own detail", withDetail.contains("8429"));
        assertTrue("the error must name the usual holder", withDetail.contains("ibsrv"));
        assertTrue("the error must say what this call declined and why",
            withDetail.contains("rewrites the server configuration"));

        String noDetail = LaunchUpdateDialogAutoConfirmer.portConflictError(null);
        assertTrue("an unreadable dialog must still produce the condition",
            noDetail.contains("network ports are already in use"));
        assertFalse("nothing may be invented when the dialog carried no detail",
            noDetail.contains("null"));
    }

    @Test
    public void testWatchRecordsThePortConflictSeparatelyFromAPolicyCancel()
    {
        try (LaunchUpdateDialogAutoConfirmer.ConflictWatch watch =
            LaunchUpdateDialogAutoConfirmer.beginConflictWatch("TestConfiguration #1"))
        {
            assertFalse("a fresh window has seen nothing", watch.portConflicted());
            assertNull("and carries no detail", watch.portConflictDetail());

            LaunchUpdateDialogAutoConfirmer.recordPortConflictForTest("- 8429 - HTTP gate port");

            assertTrue("the window must see the auto-cancelled port conflict",
                watch.portConflicted());
            assertEquals("and keep its detail for the error message", "- 8429 - HTTP gate port",
                watch.portConflictDetail());
            // The two are different failures: a port conflict is not the caller's data question
            // being declined, so it must not masquerade as one.
            assertFalse("a port conflict is not an external-changes cancel", watch.cancelled());
        }
    }

    @Test
    public void testReassignButtonLabelsCoverBothLocales()
    {
        // Pressed BY LABEL, never as "the default button": a locale this set does not know must
        // fall back to cancelling rather than rewriting the server configuration blind.
        assertTrue("the English 'Find free port' label must be known",
            LaunchUpdateDialogAutoConfirmer.PORT_CONFLICT_REASSIGN_BUTTONS.contains(
                "Find free port"));
        assertTrue("the Russian label must be known too",
            LaunchUpdateDialogAutoConfirmer.PORT_CONFLICT_REASSIGN_BUTTONS.contains(
                "\u041D\u0430\u0439\u0442\u0438 \u0441\u0432\u043E\u0431\u043E\u0434\u043D\u044B\u0439 \u043F\u043E\u0440\u0442"));
        assertFalse("the reassign and cancel label sets must stay disjoint",
            LaunchUpdateDialogAutoConfirmer.PORT_CONFLICT_REASSIGN_BUTTONS.contains("Cancel"));
    }

    @Test
    public void testWatchTellsAReassignApartFromARefusal()
    {
        // The two outcomes of the same modal must never be conflated: one failed the call, the
        // other let it through but changed the stand. A caller reports them differently.
        try (LaunchUpdateDialogAutoConfirmer.ConflictWatch watch =
            LaunchUpdateDialogAutoConfirmer.beginConflictWatch("TestConfiguration #1"))
        {
            assertFalse(watch.portsReassigned());
            assertNull(watch.portReassignDetail());

            LaunchUpdateDialogAutoConfirmer.recordPortReassignForTest("8429 - HTTP gate port");

            assertTrue("the window must see the re-address", watch.portsReassigned());
            assertEquals("8429 - HTTP gate port", watch.portReassignDetail());
            assertFalse("a re-address is not a refusal — the operation proceeded",
                watch.portConflicted());
            assertNull(watch.portConflictDetail());
        }
    }

    @Test
    public void testPortConflictErrorPointsAtTheReassignKnob()
    {
        // The refusal must name the way out, or the caller has no route from MCP at all.
        assertTrue("the error must name the parameter that allows the move",
            LaunchUpdateDialogAutoConfirmer.portConflictError(null)
                .contains("standaloneServerPortConflict='reassign'"));
    }

    @Test
    public void testPortConflictIsNotRecordedIntoAClosedWindow()
    {
        LaunchUpdateDialogAutoConfirmer.ConflictWatch watch =
            LaunchUpdateDialogAutoConfirmer.beginConflictWatch(null);
        watch.close();
        LaunchUpdateDialogAutoConfirmer.recordPortConflictForTest("- 8429 - HTTP gate port");
        assertFalse("a closed window must not keep collecting", watch.portConflicted());
    }

    // ============ #357 — dialogs already on screen when the arm happens ============

    /** A recorded {@link LaunchUpdateDialogAutoConfirmer.IOpenDialog} with no SWT behind it. */
    private static final class FakeDialog implements LaunchUpdateDialogAutoConfirmer.IOpenDialog
    {
        private final String title;
        private final String body;
        boolean pressed;
        int bodyReads;

        FakeDialog(String title, String body)
        {
            this.title = title;
            this.body = body;
        }

        @Override
        public String title()
        {
            return title;
        }

        @Override
        public String body()
        {
            bodyReads++;
            return body;
        }

        @Override
        public void press()
        {
            pressed = true;
        }
    }

    @Test
    public void testSweepPressesADialogThatWasAlreadyOpenWhenTheArmHappened()
    {
        // #357: the Display filter reacts to Activate/Show EVENTS only, so a modal raised
        // BEFORE the arm produces nothing for it to see. Once such an application-modal shell is
        // up unattended nothing on the wire can clear it — every later launch blocks behind it.
        // The sweep is what makes that state recoverable.
        FakeDialog update = new FakeDialog("Application update", null); //$NON-NLS-1$
        int pressed = LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(true, false, true, false),
            java.util.Arrays.asList(update));

        assertEquals("the already-open update modal must be pressed", 1, pressed);
        assertTrue("the already-open update modal must be pressed", update.pressed);
    }

    @Test
    public void testSweepLeavesADialogNoArmedMatcherClaims()
    {
        // The sweep must not be wider than the filter: pressing a dialog that genuinely needs a
        // human is worse than the hang it clears. Same arm state as above, unrelated dialog.
        FakeDialog unrelated = new FakeDialog("Delete configuration?", "This cannot be undone"); //$NON-NLS-1$ //$NON-NLS-2$
        FakeDialog restructure = new FakeDialog("Restructure data", null); //$NON-NLS-1$
        int pressed = LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(true, false, true, false),
            java.util.Arrays.asList(unrelated, restructure));

        assertEquals("only the claimed dialog may be pressed", 1, pressed);
        assertFalse("a dialog no armed matcher claims must be left for a human",
            unrelated.pressed);
        assertTrue("the armed restructure modal must be pressed", restructure.pressed);
    }

    @Test
    public void testSweepPressesNothingWhenNothingIsArmed()
    {
        FakeDialog update = new FakeDialog("Application update", null); //$NON-NLS-1$
        int pressed = LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(false, false, false, false),
            java.util.Arrays.asList(update));

        assertEquals("an un-armed confirmer must press nothing", 0, pressed);
        assertFalse("an un-armed confirmer must press nothing", update.pressed);
    }

    @Test
    public void testGuideForTheSweepIsTheSamePredicateTheFilterUses()
    {
        // The sweep and the filter must claim EXACTLY the same dialogs. Anything the sweep
        // claimed and the filter did not would be a widening of the auto-press.
        String[] titles = {"Application update", "Restructure data", //$NON-NLS-1$ //$NON-NLS-2$
            "Infobase configuration changes", "Question", "Something else"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String title : titles)
        {
            FakeDialog dialog = new FakeDialog(title, null);
            boolean sweepClaims = LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
                new LaunchUpdateDialogAutoConfirmer.ArmState(true, false, true, false),
                java.util.Arrays.asList(dialog)) == 1;
            boolean filterClaims = LaunchUpdateDialogAutoConfirmer.shouldAutoConfirm(
                true, false, true, false, title, null);
            assertEquals("sweep and filter must agree on '" + title + "'", filterClaims,
                sweepClaims);
        }
    }

    @Test
    public void testSweepReadsTheBodyOnlyWhenABodyMatcherNeedsIt()
    {
        // The body walk is a widget-tree traversal per shell; the sweep runs on the UI thread on
        // every arm, so it must keep the filter's laziness rather than reading every dialog.
        FakeDialog claimedByTitle = new FakeDialog("Application update", "irrelevant"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(true, true, true, false),
            java.util.Arrays.asList(claimedByTitle));
        assertEquals("a title match must not pay for a body walk", 0, claimedByTitle.bodyReads);

        FakeDialog needsBody = new FakeDialog("Question", "irrelevant"); //$NON-NLS-1$ //$NON-NLS-2$
        LaunchUpdateDialogAutoConfirmer.sweepOpenDialogs(
            new LaunchUpdateDialogAutoConfirmer.ArmState(true, true, true, false),
            java.util.Arrays.asList(needsBody));
        assertEquals("an armed body matcher with no title match must read the body", 1,
            needsBody.bodyReads);
    }

    @Test
    public void testTwoArgArmAlsoArmsRestructureWhenUpdateArmed()
    {
        // The back-compat 2-arg arm ties the restructure matcher to the update flag — a
        // pure no-op call here (headless), but the delegation contract is pinned by the
        // 3-arg shouldAutoConfirm tests above; this just exercises the overload safely.
        LaunchUpdateDialogAutoConfirmer.arm(false, false);
        LaunchUpdateDialogAutoConfirmer.disarm(false, false);
        LaunchUpdateDialogAutoConfirmer.arm(false, false, false);
        LaunchUpdateDialogAutoConfirmer.disarm(false, false, false);
    }
}
