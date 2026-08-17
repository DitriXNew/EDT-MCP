/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.CLabel;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Auto-confirms EDT's blocking <em>"Application update"</em> launch modal, but
 * ONLY while one of the YAXUnit tools is spawning a launch via
 * {@code workingCopy.launch()}.
 *
 * <h2>Why this is needed</h2>
 * When a launch configuration's infobase is not byte-for-byte equal to the
 * project, EDT's runtime launch delegate routes through
 * {@code ApplicationUpdateStatusHandler} (status code {@code 1006}) which calls
 * {@code IApplicationUiSupport.ensureUpdated}. If
 * {@code IApplicationManager.getUpdateState(application)} is anything other than
 * {@code UPDATED}, that method pops an <b>application-modal</b> dialog titled
 * "Application update" with the choices "Update then run" / "Run without update"
 * / "Cancel" and blocks the launch thread until a human answers it.
 *
 * <p>For YAXUnit runs the blocker is structural: the dependent <em>test
 * extension</em> reports {@code INCREMENTAL_UPDATE_REQUIRED}, which
 * {@code InfobaseApplicationProvisionDelegate.getUpdateState} propagates to the
 * whole application. A plain {@code IApplicationManager.update} (the same path
 * as {@code update_database} and the EDT "Update then run" button) publishes the
 * configuration but does <b>not</b> durably bring the extension to {@code EQUAL}
 * — the state reverts to {@code INCREMENTAL_UPDATE_REQUIRED} immediately — so the
 * modal returns on every launch and there is no launch-config attribute or
 * preference to suppress it. The MCP call then hangs until the user clicks
 * through, which defeats unattended runs.
 *
 * <h2>What it does</h2>
 * While armed, a {@link Display} filter watches for the activation of a shell
 * whose title is exactly {@link #APPLICATION_UPDATE_TITLE} and programmatically
 * presses its <em>default</em> button ("Update then run", the same choice a
 * careful user would pick), letting the launch proceed without human input. The
 * preceding pre-launch DB update (see {@code LaunchLifecycleUtils}) has already
 * published the configuration, so the auto-pressed update is a fast no-op and
 * does not cascade into a second structural-changes dialog.
 *
 * <h2>The three modals</h2>
 * Besides the "Application update" modal above, two more blocking dialogs are raised by the
 * SAME programmatic {@code IApplicationManager.update} and are matched here, each behind its own
 * arm flag:
 * <ul>
 *   <li><b>"Restructure data"</b> ({@code InfobaseUpdateConfirmDialog}) - the DB structure
 *       changes confirmation; completed via its default "Accept" button.</li>
 *   <li><b>"Infobase configuration changes"</b> ({@code InfobaseUpdateConflictDialog}) - raised
 *       when the infobase configuration was written OUTSIDE EDT (Designer, {@code ibcmd}, a CLI
 *       pipeline) since the last EDT interaction. This one has NO safe default: its default
 *       button is "Import", which rewrites the caller's PROJECT sources. It is therefore
 *       completed by the labelled button the call's {@link ExternalInfobaseChangesPolicy}
 *       selects, and a label that cannot be located degrades to cancelling the dialog.</li>
 * </ul>
 *
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>The filter is installed only between an {@code arm} and its paired
 *       {@code disarm} (use try/finally around the single {@code launch()} call),
 *       so manual EDT launches outside an MCP tool still prompt normally.</li>
 *   <li>An {@code arm} additionally sweeps the shells that are ALREADY open and presses the
 *       ones its matchers claim. The filter only sees {@code Activate}/{@code Show} EVENTS, so
 *       a modal raised before the arm produces nothing for it to react to — and an
 *       application-modal shell left unanswered blocks every later launch, with no way for an
 *       unattended caller to clear it. The sweep uses the same predicate as the filter, so it
 *       widens nothing: a dialog no armed matcher claims is left for a human.</li>
 *   <li>The two matchers — the "Application update" TITLE matcher and the
 *       code-1003 "Debug session already exists" BODY matcher — are armed
 *       <em>independently</em> via {@link #arm(boolean, boolean)}: the debug path
 *       arms the session matcher unconditionally but the update matcher only when
 *       the caller did NOT opt out of the DB update ({@code updateBeforeLaunch}),
 *       so opting out leaves EDT's "Update then run" modal for a human while the
 *       1003 modal is still auto-confirmed. The back-compat {@link #arm()} arms the
 *       update matcher only.</li>
 *   <li>Each matcher is reentrant via its own counter; concurrent launches share
 *       ONE filter, which is installed while EITHER matcher is armed and removed by
 *       the last {@code disarm} of both. Each branch of the listener fires only
 *       while its own matcher is armed.</li>
 *   <li>Only the exact "Application update" title — in either of EDT's two
 *       shipped locales (English / Russian) — is matched, so unrelated dialogs
 *       that happen to appear during the window are left untouched.</li>
 *   <li>Headless (no running workbench, hence no pumped {@link Display}) is a
 *       no-op — no dialog can appear there anyway, and the probe never CREATES
 *       a display (see {@link #safeDisplay()}).</li>
 * </ul>
 *
 * <h2>Residual risk (documented, accepted)</h2>
 * The armed window is not instantaneous: the launch runs as a background Job, so
 * a matcher can stay armed for the MINUTES a slow launch (e.g. a standalone-server
 * mode-switch restart) takes. If a user MANUALLY starts another launch during that
 * window and it raises the same "Application update" (or code-1003) modal, the
 * filter auto-presses it too — a title-only match carries no information about
 * WHICH launch opened the shell, so the user's dialog is indistinguishable from
 * ours. Title matching is still the best available discriminator: the modal is
 * raised deep inside EDT's launch delegate ({@code IApplicationUiSupport}) with no
 * public hook, the shell carries no launch-identifying data (no custom widget id,
 * no owner-launch reference), and matching any wider (e.g. every modal of the
 * owning plug-in) would auto-press unrelated dialogs. The pressed buttons are the
 * conservative choices ("Update then run" / "Keep existing and start new"), so a
 * mis-attributed press performs a safe action, never a destructive one.
 * <p>
 * The already-open sweep widens that same window in TIME: a dialog raised before the arm is
 * pressed too, so one a human happens to be reading can be answered under them. The trade is
 * taken deliberately, because an application-modal shell left unanswered blocks every launch
 * anyway — nobody can proceed while it is up, and unattended there is no one to press it. The
 * predicate is unchanged (only the same conservative buttons on the same matched titles), and
 * a shell that is not yet visible is skipped so the sweep matches exactly what the filter
 * would have seen.
 *
 * <h2>Locale</h2>
 * The modal title is the localized {@code ApplicationUiSupport_Application_update}
 * string. EDT ships exactly two NL variants of the {@code com.e1c.g5.dt.applications.ui}
 * bundle — English ("Application update") and Russian ("Обновление приложения") —
 * so the filter matches BOTH. An English-only match (the previous behaviour)
 * silently fails on a Russian-locale EDT: the unattended launch then hangs on
 * the un-dismissed modal. The update modal's default button is the
 * same choice in both locales ("Update then run" / "Обновить и запустить", button
 * index 0), so {@link #pressConfirmButton(Shell)} stays locale-agnostic for it; the
 * code-1003 modal instead presses its localized "Keep existing and start new" /
 * "Сохранить старую и запустить новую" button, matched by label.
 */
public final class LaunchUpdateDialogAutoConfirmer
{
    /**
     * English title of EDT's launch-delegate "update infobase before launch?"
     * modal ({@code ApplicationUiSupport_Application_update}).
     */
    static final String APPLICATION_UPDATE_TITLE = "Application update"; //$NON-NLS-1$

    /**
     * Russian title of the same modal ({@code messages_ru.properties}:
     * "Обновление приложения"). EDT localizes this dialog title, so both the
     * English and Russian titles must match — an English-only match never fires
     * on a Russian-locale EDT. Kept as a
     * unicode-escaped literal (copied verbatim from EDT's own
     * {@code messages_ru.properties}) so it compiles identically regardless of the
     * source-file encoding the Tycho compiler picks up.
     */
    static final String APPLICATION_UPDATE_TITLE_RU =
        "\u041E\u0431\u043D\u043E\u0432\u043B\u0435\u043D\u0438\u0435 \u043F\u0440\u0438\u043B\u043E\u0436\u0435\u043D\u0438\u044F"; //$NON-NLS-1$

    /**
     * Every shipped localized title of the "Application update" modal. EDT ships
     * only the English and Russian NL variants of
     * {@code com.e1c.g5.dt.applications.ui}, so this set is exhaustive; matching is
     * still an exact, whole-title compare so no unrelated dialog is touched.
     */
    static final Set<String> APPLICATION_UPDATE_TITLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(APPLICATION_UPDATE_TITLE, APPLICATION_UPDATE_TITLE_RU)));

    /**
     * English title of the platform's DB-restructure confirmation modal
     * ({@code InfobaseUpdateConfirmDialog}, resource key
     * {@code InfobaseUpdateConfirmDialog_Restructure_data}). It pops during
     * {@code IApplicationManager.update} (the {@code update_database} tool and the
     * pre-launch DB update) whenever the configuration changes the DB structure, lists
     * the structural changes, and blocks the worker thread until "Accept"/"Cancel" is
     * pressed. Its <b>default</b> button is "Accept", so the same default-button press
     * the update modal uses confirms it.
     */
    static final String RESTRUCTURE_TITLE = "Restructure data"; //$NON-NLS-1$

    /**
     * Russian title of the same restructure modal ({@code messages_ru.properties}:
     * "Реорганизация информации"). Verified verbatim from EDT's own
     * {@code com._1c.g5.v8.dt.platform.services.ui} bundle. Kept unicode-escaped
     * (no raw Cyrillic in source) so it compiles identically whatever encoding the Tycho
     * compiler picks.
     */
    static final String RESTRUCTURE_TITLE_RU =
        "\u0420\u0435\u043E\u0440\u0433\u0430\u043D\u0438\u0437\u0430\u0446\u0438\u044F \u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u0438"; //$NON-NLS-1$

    /**
     * Every shipped localized title of the DB-restructure confirmation modal (English /
     * Russian — the only NL variants EDT ships). Exact whole-title compare, so no
     * unrelated dialog is touched.
     */
    static final Set<String> RESTRUCTURE_TITLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(RESTRUCTURE_TITLE, RESTRUCTURE_TITLE_RU)));

    /**
     * English title of the platform's external-changes conflict modal
     * ({@code InfobaseUpdateConflictDialog}, resource key
     * {@code InfobaseUpdateConflictDialog_Infobase_configuration_changes}). It pops during
     * {@code IApplicationManager.update} whenever the infobase configuration was changed
     * WITHOUT EDT (Designer, {@code ibcmd}, a CLI pipeline) since the last EDT interaction,
     * and offers "Import" / "Override" / "Cancel". Unlike the other two modals it has NO
     * safe default: its default button is "Import", which rewrites the caller's PROJECT
     * sources — so this modal is completed by a LABELLED button chosen from the call's
     * {@link ExternalInfobaseChangesPolicy}, never blind.
     */
    static final String CONFLICT_TITLE = "Infobase configuration changes"; //$NON-NLS-1$

    /**
     * Russian title of the same conflict modal ({@code messages_ru.properties}:
     * "Изменения конфигурации информационной базы"), copied verbatim from EDT's own
     * {@code com._1c.g5.v8.dt.platform.services.ui} bundle and kept unicode-escaped (no raw
     * Cyrillic in source) so it compiles identically whatever encoding Tycho picks.
     */
    static final String CONFLICT_TITLE_RU = "\u0418\u0437\u043C\u0435\u043D\u0435\u043D\u0438\u044F " //$NON-NLS-1$
        + "\u043A\u043E\u043D\u0444\u0438\u0433\u0443\u0440\u0430\u0446\u0438\u0438 " //$NON-NLS-1$
        + "\u0438\u043D\u0444\u043E\u0440\u043C\u0430\u0446\u0438\u043E\u043D\u043D\u043E\u0439 " //$NON-NLS-1$
        + "\u0431\u0430\u0437\u044B"; //$NON-NLS-1$

    /**
     * Every shipped localized title of the external-changes conflict modal (English /
     * Russian — the only NL variants EDT ships). Exact whole-title compare.
     */
    static final Set<String> CONFLICT_TITLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(CONFLICT_TITLE, CONFLICT_TITLE_RU)));

    /**
     * Localized labels of the conflict modal's "Override" button
     * ({@code InfobaseUpdateConflictDialog_Override}, "\u041F\u0435\u0440\u0435\u0437\u0430\u043F\u0438\u0441\u0430\u0442\u044C") — keep the project
     * configuration and overwrite the externally-changed infobase with it.
     */
    static final Set<String> CONFLICT_OVERRIDE_BUTTONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("Override", //$NON-NLS-1$
            "\u041F\u0435\u0440\u0435\u0437\u0430\u043F\u0438\u0441\u0430\u0442\u044C"))); //$NON-NLS-1$

    /**
     * Localized labels of the conflict modal's "Import" button
     * ({@code InfobaseUpdateConflictDialog_Import}, "\u0418\u043C\u043F\u043E\u0440\u0442\u0438\u0440\u043E\u0432\u0430\u0442\u044C") — pull the external
     * infobase changes into the PROJECT sources.
     */
    static final Set<String> CONFLICT_IMPORT_BUTTONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("Import", //$NON-NLS-1$
            "\u0418\u043C\u043F\u043E\u0440\u0442\u0438\u0440\u043E\u0432\u0430\u0442\u044C"))); //$NON-NLS-1$

    /**
     * English title of EDT's standalone-server port-conflict modal
     * ({@code StandaloneServerUiDialogPortConflictDecisionPrompter_Title}). It pops from
     * {@code StandaloneServerBehaviourDelegate.verifyServerPorts} whenever the standalone
     * server is about to START and one of its configured ports (HTTP gate / debug server /
     * SSH gate) is already bound — most often by an ibsrv left over from a previous EDT
     * session. EVERY MCP operation that starts a standalone server reaches it: the launch
     * tools, and {@code update_database} on a {@code ServerApplication.*} target (its
     * publish starts the server first).
     */
    static final String PORT_CONFLICT_TITLE = "Standalone server port conflict"; //$NON-NLS-1$

    /**
     * Russian title of the same port-conflict modal ({@code messages_ru.properties}:
     * "Конфликт портов автономного сервера"), copied verbatim from EDT's own
     * {@code com.e1c.g5.v8.dt.platform.standaloneserver.wst.ui} bundle and kept
     * unicode-escaped (no raw Cyrillic in source) so it compiles identically whatever
     * encoding Tycho picks.
     */
    static final String PORT_CONFLICT_TITLE_RU =
        "\u041A\u043E\u043D\u0444\u043B\u0438\u043A\u0442 \u043F\u043E\u0440\u0442\u043E\u0432 " //$NON-NLS-1$
            + "\u0430\u0432\u0442\u043E\u043D\u043E\u043C\u043D\u043E\u0433\u043E " //$NON-NLS-1$
            + "\u0441\u0435\u0440\u0432\u0435\u0440\u0430"; //$NON-NLS-1$

    /**
     * Every shipped localized title of the standalone-server port-conflict modal (English /
     * Russian — the only NL variants EDT ships). Exact whole-title compare.
     */
    static final Set<String> PORT_CONFLICT_TITLES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(PORT_CONFLICT_TITLE, PORT_CONFLICT_TITLE_RU)));

    /**
     * Localized labels of the port-conflict modal's "Cancel" button
     * ({@code StandaloneServerUiDialogPortConflictDecisionPrompter_Button_cancel},
     * "Отменить") — the answer this filter presses. The OTHER button ("Find free port" /
     * "\u041D\u0430\u0439\u0442\u0438 \u0441\u0432\u043E\u0431\u043E\u0434\u043D\u044B\u0439 \u043F\u043E\u0440\u0442") is the dialog's DEFAULT, and it is never pressed blind: it
     * makes EDT pick new ports and REWRITE the server's {@code config.yaml}, changing the
     * address every existing client of that server is bookmarked against. Cancelling writes
     * nothing and turns the hang into a failure the caller can act on.
     */
    static final Set<String> PORT_CONFLICT_CANCEL_BUTTONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("Cancel", //$NON-NLS-1$
            "\u041E\u0442\u043C\u0435\u043D\u0438\u0442\u044C"))); //$NON-NLS-1$

    /**
     * Localized labels of the port-conflict modal's "Find free port" button
     * ({@code StandaloneServerUiDialogPortConflictDecisionPrompter_Button_update}) — the dialog's
     * DEFAULT button. Pressed ONLY for {@link StandaloneServerPortConflictPolicy#REASSIGN}, and
     * even then BY LABEL rather than as "the default button": it makes EDT pick other ports and
     * REWRITE the server configuration, so a build whose button bar this plugin cannot read must
     * fall back to cancelling, never to whatever happens to be default.
     */
    static final Set<String> PORT_CONFLICT_REASSIGN_BUTTONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList("Find free port", //$NON-NLS-1$
            "\u041D\u0430\u0439\u0442\u0438 \u0441\u0432\u043E\u0431\u043E\u0434\u043D\u044B\u0439 \u043F\u043E\u0440\u0442"))); //$NON-NLS-1$

    /**
     * English message-body prefix of EDT's "Debug session already exists" launch
     * modal (status code {@code 1003}, handler {@code DebugSessionCheckStatusHandler}).
     * The full text is "Debug session for project \"{0}\" and application \"{1}\" has
     * already been started.\nShould it be stopped?" — we match only the stable leading
     * prefix so the two interpolated names don't break the comparison.
     */
    static final String DEBUG_SESSION_EXISTS_BODY_PREFIX = "Debug session for project"; //$NON-NLS-1$

    /**
     * Russian message-body prefix of the same modal (decodes to
     * "Сессия отладки для проекта"). Kept unicode-escaped (no raw Cyrillic in
     * source) so it compiles identically whatever encoding the Tycho compiler picks.
     */
    static final String DEBUG_SESSION_EXISTS_BODY_PREFIX_RU =
        "\u0421\u0435\u0441\u0441\u0438\u044F \u043E\u0442\u043B\u0430\u0434\u043A\u0438 " //$NON-NLS-1$
            + "\u0434\u043B\u044F \u043F\u0440\u043E\u0435\u043A\u0442\u0430"; //$NON-NLS-1$

    /**
     * Every shipped localized message-body prefix of the "Debug session already
     * exists" code-1003 modal. The shell TITLE is the generic "Question"/"Вопрос",
     * which would catch every question dialog — so this modal is matched on the
     * BODY prefix instead.
     */
    static final Set<String> DEBUG_SESSION_EXISTS_BODY_PREFIXES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(
            DEBUG_SESSION_EXISTS_BODY_PREFIX, DEBUG_SESSION_EXISTS_BODY_PREFIX_RU)));

    /**
     * English label of the code-1003 modal's "keep the existing session and start a
     * new one alongside it" button (EDT's {@code Launch_anyway} → LAUNCH_ANYWAY,
     * button index 1). This is the choice that lets a thin CLIENT come up WHILE a
     * standalone-server debug session for the same application is already running
     * (or alongside another client in a race) instead of terminating it. The default
     * button (index 0, {@code Restart_application} → RESTART_APPLICATION) would STOP
     * the existing session — wrong for the "launch client while debug-server is up"
     * scenario.
     */
    static final String DEBUG_SESSION_KEEP_BUTTON = "Keep existing and start new"; //$NON-NLS-1$

    /**
     * Russian label of the same "keep existing and start new" button (decodes to
     * "Сохранить старую и запустить новую"). Kept unicode-escaped (no raw
     * Cyrillic in source) so it compiles identically whatever encoding the Tycho
     * compiler picks. EDT localizes this button label too, so both the English
     * and Russian variants must match.
     */
    static final String DEBUG_SESSION_KEEP_BUTTON_RU =
        "\u0421\u043e\u0445\u0440\u0430\u043d\u0438\u0442\u044c \u0441\u0442\u0430\u0440\u0443\u044e \u0438 \u0437\u0430\u043f\u0443\u0441\u0442\u0438\u0442\u044c \u043d\u043e\u0432\u0443\u044e"; //$NON-NLS-1$

    /**
     * Every shipped localized label of the 1003 "keep existing and start new"
     * (LAUNCH_ANYWAY) button. Matching the button by its label — rather than by a
     * fixed index — keeps the press correct even if EDT reorders the button bar; an
     * exact, whole-label compare so no unrelated button is pressed. If none of these
     * labels is found, the dialog is CANCELLED instead (see
     * {@link ConfirmAction#CANCEL_DIALOG}) — its default button is the destructive
     * "Stop existing and start new" and is never pressed blind.
     */
    static final Set<String> DEBUG_SESSION_KEEP_BUTTONS = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(DEBUG_SESSION_KEEP_BUTTON, DEBUG_SESSION_KEEP_BUTTON_RU)));

    /** Cap on how much dialog text {@link #collectDialogText} accumulates for attribution. */
    private static final int MAX_DIALOG_TEXT_CHARS = 8192;

    /** Cap on how many controls {@link #collectDialogText} visits, bounding the UI-thread cost. */
    private static final int MAX_DIALOG_CONTROLS = 500;

    /** Cap on the widget-tree walk depth when reading a dialog's message body. */
    private static final int MAX_BODY_SCAN_DEPTH = 6;

    /**
     * Cap on the port-conflict detail carried into an error message
     * ({@link #summarizePortConflictText}) — it is quoted inside a single tool error, so a
     * pathological dialog must not turn one failure into a wall of text.
     */
    private static final int MAX_PORT_CONFLICT_DETAIL_CHARS = 400;

    private static final Object LOCK = new Object();

    /**
     * Reentrant arm count for the "Application update" TITLE matcher. While
     * {@code > 0} the listener's update-title branch is allowed to fire. Gated
     * separately from {@link #sessionArmCount} so a caller can opt out of the DB
     * update (and thus the auto-press of its modal) while still suppressing the
     * code-1003 "debug session already exists" modal — see the class header and
     * {@code DebugLaunchTool.performLaunch}.
     */
    private static int updateArmCount;

    /**
     * Reentrant arm count for the code-1003 "Debug session already exists" BODY
     * matcher. While {@code > 0} the listener's 1003-body branch is allowed to
     * fire. Independent of {@link #updateArmCount}: the debug path arms this even
     * when it opts out of the update modal.
     */
    private static int sessionArmCount;

    /**
     * Reentrant arm count for the DB-restructure TITLE matcher ("Restructure data" /
     * "Реорганизация информации"). While {@code > 0} the listener auto-presses that
     * modal's default "Accept" button. Armed alongside the update matcher by the
     * back-compat {@link #arm(boolean, boolean)} (a restructure is a consequence of an
     * update), and independently by {@code update_database} via
     * {@link #arm(boolean, boolean, boolean)}.
     */
    private static int restructureArmCount;

    /**
     * Reentrant arm count for the standalone-server port-conflict TITLE matcher
     * ({@link #PORT_CONFLICT_TITLES}).
     *
     * <p><b>Not independently gated, on purpose.</b> Every other matcher answers a question
     * about the caller's DATA, so each has an opt-out; this one answers a question about the
     * MACHINE ("port 8429 is taken — shall I move the server?"), and its only auto-answer is
     * the one that writes nothing (Cancel). There is therefore no window in which an armed
     * caller would rather hang on it, so it is armed by every {@code arm} that arms anything
     * — including the launch paths, which reach this modal through the very same
     * standalone-server start. Left unarmed it is a guaranteed hang: the modal is
     * application-modal, no MCP call can clear it, and every later call queues behind it.
     */
    private static int portConflictArmCount;

    /**
     * How many of the outstanding port-conflict arms asked for
     * {@link StandaloneServerPortConflictPolicy#REASSIGN}.
     *
     * <p>"Find free port" is pressed only when EVERY outstanding arm asked for it
     * ({@code portConflictReassignArmCount == portConflictArmCount}). The dialog names the
     * SERVER, not the infobase, so it cannot be attributed to one caller the way the
     * external-changes modal can — and the answer REWRITES that server's configuration for
     * everyone. Unanimity is therefore the only safe rule: a concurrent call that did not ask
     * for a re-address must not have one performed under it.
     */
    private static int portConflictReassignArmCount;

    /**
     * Outstanding arms of the external-changes conflict matcher — one entry per {@code arm}
     * call, each pairing the policy with the INFOBASE whose update it covers (the name may be
     * {@code null} when it could not be resolved).
     *
     * <p>The pairing is what lets two concurrent updates of DIFFERENT infobases run with
     * DIFFERENT policies: the dialog is attributed to an infobase first, and only the arms for
     * that infobase decide the button. A global "any two policies differ → cancel" rule would
     * make parallel runs of unrelated projects all degrade to cancelling.
     */
    private static final List<ConflictArm> CONFLICT_ARMS = new ArrayList<>();

    /** {@link #lastConflictCancelReason()} value: the call's own policy asked to cancel. */
    public static final String CANCEL_REASON_POLICY = "policy"; //$NON-NLS-1$

    /** {@link #lastConflictCancelReason()} value: the policy's button was not found in the dialog. */
    public static final String CANCEL_REASON_BUTTON_NOT_FOUND = "button-not-found"; //$NON-NLS-1$

    /**
     * Reason value: the dialog could not be attributed to any armed update — it named another
     * infobase, or the caller could not resolve its own. Cancelling is then the only answer that
     * writes nothing, and repeating the same policy would not change that.
     */
    public static final String CANCEL_REASON_NOT_ATTRIBUTED = "not-attributed"; //$NON-NLS-1$

    /**
     * The conflict-cancel windows currently open — one per update in flight (see
     * {@link #beginConflictWatch(String)}). A cancelled dialog is recorded INTO the windows it
     * belongs to, so both the fact and its reason stay correlated with the caller that owns
     * them: a concurrent update of another application can neither be mistaken for this one's
     * failure nor overwrite its reason. Bounded by construction — a window is removed when its
     * call ends.
     */
    private static final List<ConflictWatch> CONFLICT_WATCHES = new ArrayList<>();

    private static Display filterDisplay;
    private static Listener filter;

    private LaunchUpdateDialogAutoConfirmer()
    {
        // Utility class
    }

    /**
     * Pure decision used by the {@link Display} filter (and by tests): is the
     * given shell title the "Application update" modal we auto-confirm, in any of
     * EDT's shipped locales (English / Russian)?
     */
    static boolean isTargetTitle(String shellTitle)
    {
        return shellTitle != null && APPLICATION_UPDATE_TITLES.contains(shellTitle);
    }

    /**
     * Pure decision (and test seam): is the given shell title the DB-restructure
     * confirmation modal ({@link #RESTRUCTURE_TITLES}, "Restructure data" /
     * "Реорганизация информации") that pops during a configuration to DB update when the
     * structure changes? Auto-confirmed via its DEFAULT button ("Accept"), like the
     * "Application update" modal.
     */
    static boolean isRestructureTitle(String shellTitle)
    {
        return shellTitle != null && RESTRUCTURE_TITLES.contains(shellTitle);
    }

    /**
     * Pure decision (and test seam): is the given shell title EDT's standalone-server
     * port-conflict modal ({@link #PORT_CONFLICT_TITLES}, "Standalone server port conflict" /
     * "Конфликт портов автономного сервера")? Raised while the standalone server is being
     * STARTED — which every launch of, and every {@code update_database} against, a
     * {@code ServerApplication.*} target does — and completed here by its Cancel button.
     */
    static boolean isPortConflictTitle(String shellTitle)
    {
        return shellTitle != null && PORT_CONFLICT_TITLES.contains(shellTitle);
    }

    /**
     * Concatenates the label-like texts of a dialog, depth- and size-bounded. Attribution needs
     * the WHOLE message, not the first label: EDT's conflict dialog opens with its own header
     * ("Infobase configuration changes") and states the infobase only in the paragraph below it,
     * so a first-label read ({@link #readDialogBody}) never sees the name. Fully guarded — never
     * throws onto the UI thread.
     *
     * @param shell the dialog shell (may be {@code null}/disposed)
     * @return the collected text, or {@code null}
     */
    static String collectDialogText(Shell shell)
    {
        if (shell == null || shell.isDisposed())
        {
            return null;
        }
        try
        {
            StringBuilder sb = new StringBuilder();
            appendLabelTexts(shell, 0, sb, new int[] {MAX_DIALOG_CONTROLS});
            return sb.length() == 0 ? null : sb.toString();
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Depth-bounded pre-order walk appending every label-like text. Bounded three ways so the
     * UI thread can never pay for a pathological widget tree: by depth, by accumulated text
     * (each label is truncated to what is left of the budget) and by the number of controls
     * visited ({@code budget[0]}, decremented per node).
     */
    private static void appendLabelTexts(Control control, int depth, StringBuilder sb, int[] budget)
    {
        if (control == null || control.isDisposed() || depth > MAX_BODY_SCAN_DEPTH || budget[0] <= 0
            || sb.length() >= MAX_DIALOG_TEXT_CHARS)
        {
            return;
        }
        budget[0]--;
        String own = labelLikeText(control);
        if (own != null && !own.isEmpty())
        {
            int room = MAX_DIALOG_TEXT_CHARS - sb.length();
            sb.append(own, 0, Math.min(own.length(), room)).append('\n');
        }
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                appendLabelTexts(child, depth + 1, sb, budget);
            }
        }
    }

    /**
     * Pure decision (and test seam): does the dialog body name any of {@code names}, quoted the
     * way EDT renders it? A blank body or an empty name list yields {@code false} — an
     * unattributable dialog is never pressed.
     *
     * @param body the dialog message text (may be {@code null})
     * @param names the armed infobase names (may be {@code null}/empty)
     * @return {@code true} when the body mentions one of the names
     */
    static boolean bodyMentionsAny(String body, List<String> names)
    {
        if (body == null || body.isEmpty() || names == null)
        {
            return false;
        }
        for (String name : names)
        {
            if (name != null && !name.isEmpty() && mentionsQuoted(body, name))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Does {@code body} name {@code name} the way EDT renders it in the conflict modal — in
     * quotes ({@code Infobase "agent-base" configuration was changed…})? A bare substring test
     * would let an armed {@code base} claim a dialog about {@code prod-base} and then answer it
     * with a WRITING choice, so the quotes are part of the match. All quote styles EDT's two
     * locales use are accepted.
     *
     * @param body the collected dialog text, never {@code null}
     * @param name the armed infobase name, never {@code null}/empty
     * @return {@code true} when the body contains the quoted name
     */
    private static boolean mentionsQuoted(String body, String name)
    {
        return body.contains('"' + name + '"')
            || body.contains('\'' + name + '\'')
            || body.contains("\u00AB" + name + "\u00BB")
            || body.contains("\u201C" + name + "\u201D");
    }

    /**
     * Pure decision (and test seam): is the given shell title EDT's external-changes
     * conflict modal ({@link #CONFLICT_TITLES}, "Infobase configuration changes" /
     * "Изменения конфигурации информационной базы")? It pops when the infobase
     * configuration was changed outside EDT (Designer/CLI) since the last EDT
     * interaction. Completed by a LABELLED button chosen from the call's
     * {@link ExternalInfobaseChangesPolicy} — never by the default button, which is
     * "Import" and would rewrite the caller's project sources.
     *
     * @param shellTitle the dialog shell title (may be {@code null})
     * @return {@code true} when the title is the conflict modal's, in either locale
     */
    static boolean isConflictTitle(String shellTitle)
    {
        return shellTitle != null && CONFLICT_TITLES.contains(shellTitle);
    }

    /**
     * Picks the policy the conflict branch acts on. Concurrent launches share the ONE
     * {@link Display} filter and the modal carries no information about which launch
     * raised it, so the choice must be unambiguous:
     * <ul>
     *   <li>exactly one policy armed → that policy;</li>
     *   <li>two or more DIFFERENT policies armed → {@link ExternalInfobaseChangesPolicy#CANCEL}:
     *       the dialog is closed and nothing is written on either side. Acting on one of the
     *       arms would apply a choice the OTHER caller never asked for — and one of those
     *       choices ({@code import}) rewrites project sources — so an ambiguous window
     *       degrades to the choice that cannot damage anything;</li>
     *   <li>none armed → {@code null} (the modal is left for a human).</li>
     * </ul>
     *
     * @return the policy to act on, or {@code null} when the conflict matcher is not armed
     */
    static boolean conflictMatcherArmed()
    {
        synchronized (LOCK)
        {
            return !CONFLICT_ARMS.isEmpty();
        }
    }

    /**
     * The live form of {@link #decideFor(String, List)} — decides against the arms outstanding
     * right now.
     *
     * @param dialogBody the dialog message text (may be {@code null})
     * @return the decision, never {@code null}
     */
    static ConflictDecision decideFor(String dialogBody)
    {
        synchronized (LOCK)
        {
            return decideFor(dialogBody, CONFLICT_ARMS);
        }
    }

    /**
     * Pure decision (and test seam): which button answers a conflict dialog whose message is
     * {@code body}, given the outstanding {@code arms}?
     * <ul>
     *   <li>Arms that NAME an infobase the body mentions win: one distinct policy among them →
     *       that policy; two different ones for the same dialog → {@link
     *       ExternalInfobaseChangesPolicy#CANCEL} (a genuine conflict of intent);</li>
     *   <li>otherwise, if any arm named an infobase at all, the dialog is somebody else's →
     *       {@code null} (cancel, never a writing press);</li>
     *   <li>otherwise (no arm could resolve a name — e.g. a launch window around EDT's own
     *       delegate-performed update) attribution is impossible, so the unnamed arms decide. Such
     *       arms are degraded to {@link ExternalInfobaseChangesPolicy#CANCEL} when they are
     *       recorded ({@link #attributableAnswer}), so what they decide is always a decline.</li>
     * </ul>
     *
     * @param body the dialog message text (may be {@code null})
     * @param arms the outstanding arms (may be empty)
     * @return the policy to apply, or {@code null} when the dialog must be cancelled
     */
    static ExternalInfobaseChangesPolicy choosePolicyFor(String body, List<ConflictArm> arms)
    {
        return decideFor(body, arms).policy;
    }

    /**
     * Same decision as {@link #choosePolicyFor(String, List)}, but also reporting WHICH armed
     * infobase the dialog was attributed to ({@code null} when it could not be attributed).
     * The name correlates the outcome with the caller that owns it: a cancel is counted for
     * that infobase, so a concurrent update of another application never sees it as its own.
     *
     * @param body the dialog message text (may be {@code null})
     * @param arms the outstanding arms (may be empty)
     * @return the decision, never {@code null}
     */
    static ConflictDecision decideFor(String body, List<ConflictArm> arms)
    {
        if (arms == null || arms.isEmpty())
        {
            return new ConflictDecision(null, null);
        }
        ExternalInfobaseChangesPolicy matched = null;
        String matchedName = null;
        boolean anyNamed = false;
        ExternalInfobaseChangesPolicy unnamed = null;
        boolean unnamedAmbiguous = false;
        for (ConflictArm arm : arms)
        {
            if (arm.infobaseName == null)
            {
                if (unnamed != null && unnamed != arm.policy)
                {
                    unnamedAmbiguous = true;
                }
                unnamed = unnamed == null ? arm.policy : unnamed;
                continue;
            }
            anyNamed = true;
            if (body == null || !mentionsQuoted(body, arm.infobaseName))
            {
                continue;
            }
            if (matched != null && matched != arm.policy)
            {
                // The SAME dialog is claimed by two callers wanting different answers.
                return new ConflictDecision(ExternalInfobaseChangesPolicy.CANCEL, arm.infobaseName);
            }
            matched = arm.policy;
            matchedName = arm.infobaseName;
        }
        if (matched != null)
        {
            return new ConflictDecision(matched, matchedName);
        }
        if (anyNamed)
        {
            // Named arms exist but none of them is about THIS dialog: not ours.
            return new ConflictDecision(null, null);
        }
        return new ConflictDecision(
            unnamedAmbiguous ? ExternalInfobaseChangesPolicy.CANCEL : unnamed, null);
    }

    /**
     * The outcome of attributing a conflict dialog: which button to press ({@code null} = cancel
     * it) and which armed infobase it belongs to ({@code null} = could not be attributed).
     */
    static final class ConflictDecision
    {
        final ExternalInfobaseChangesPolicy policy;
        final String infobaseName;

        ConflictDecision(ExternalInfobaseChangesPolicy policy, String infobaseName)
        {
            this.policy = policy;
            this.infobaseName = infobaseName;
        }
    }

    /**
     * The answer an arm may actually give: without an infobase name nothing can be proven to be
     * ours, so a WRITING choice ({@code override} discards the infobase's external changes,
     * {@code import} rewrites the project sources) is degraded to {@code cancel}.
     *
     * <p>That keeps both failure modes closed at once: the modal is still answered, so an
     * unattended call cannot hang on it, and the answer cannot damage an update this caller does
     * not own. Such a cancel is reported as {@link #CANCEL_REASON_NOT_ATTRIBUTED}, whose message
     * explains that the dialog could not be tied to this operation - repeating the same policy
     * would only degrade again - which is the honest outcome: the divergence was not resolved.
     *
     * @param infobaseName the name the caller could resolve (may be {@code null}/blank)
     * @param policy the policy the caller asked for, never {@code null}
     * @return the policy that may be applied
     */
    static ExternalInfobaseChangesPolicy attributableAnswer(String infobaseName,
        ExternalInfobaseChangesPolicy policy)
    {
        return trimToNull(infobaseName) == null ? ExternalInfobaseChangesPolicy.CANCEL : policy;
    }

    /** Trims to {@code null}: a blank infobase name is the same as "no name". */
    private static String trimToNull(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * One outstanding conflict-matcher arm: the policy plus the infobase it covers
     * ({@code null} when the caller could not resolve one). Value semantics, so a
     * {@code disarm} releases exactly one matching arm.
     */
    static final class ConflictArm
    {
        final String infobaseName;
        final ExternalInfobaseChangesPolicy policy;

        ConflictArm(String infobaseName, ExternalInfobaseChangesPolicy policy)
        {
            this.infobaseName = infobaseName;
            this.policy = policy;
        }

        @Override
        public boolean equals(Object other)
        {
            if (this == other)
            {
                return true;
            }
            if (!(other instanceof ConflictArm))
            {
                return false;
            }
            ConflictArm that = (ConflictArm)other;
            return policy == that.policy && Objects.equals(infobaseName, that.infobaseName);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(infobaseName, policy);
        }
    }

    /**
     * Pure decision (and test seam): is the given dialog message BODY the
     * "Debug session already exists" code-1003 modal? The modal's shell title is the
     * generic "Question"/"Вопрос", so it is matched on the localized body PREFIX
     * (the two interpolated project/application names follow it) — never on the
     * generic title, which would catch every question dialog.
     *
     * @param body a dialog message-body string (may be {@code null})
     * @return {@code true} when {@code body} starts with a known 1003 body prefix
     */
    static boolean isDebugSessionExistsBody(String body)
    {
        if (body == null)
        {
            return false;
        }
        for (String prefix : DEBUG_SESSION_EXISTS_BODY_PREFIXES)
        {
            if (body.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Arms the update-dialog matcher only — the back-compat entry point. MUST be
     * paired with {@link #disarm()}. Equivalent to {@code arm(true, false)}: the
     * "Application update" modal is auto-confirmed, the code-1003 modal is NOT.
     * Kept for callers that need only the update modal pressed unconditionally;
     * the YAXUnit tools now gate both matchers per call site via
     * {@link #arm(boolean, boolean)}.
     */
    public static void arm()
    {
        arm(true, false);
    }

    /**
     * Disarms the update-dialog matcher only — the back-compat entry point,
     * mirroring {@link #arm()}. Equivalent to {@code disarm(true, false)}.
     */
    public static void disarm()
    {
        disarm(true, false);
    }

    /**
     * Arms the auto-confirmer with independently-selectable matchers. MUST be
     * paired with {@link #disarm(boolean, boolean)} (same flags) in a
     * {@code finally} block around the {@code launch()} call. Reentrant per
     * matcher: nested/concurrent launches share one {@link Display} filter, which
     * is installed while EITHER matcher has an outstanding arm.
     *
     * <p>The two matchers are gated separately so a caller can opt out of the DB
     * update — and thus the auto-press of EDT's "Application update" modal —
     * while still suppressing the code-1003 "debug session already exists" modal.
     * The debug path passes {@code sessionDialog=true} unconditionally and
     * {@code updateDialog=updateBeforeLaunch}; the update opt-out is preserved.
     *
     * <p>No-op in a headless environment (no SWT display) and when both flags are
     * {@code false}. Never throws — a display disposed mid-call (workbench
     * shutdown) is swallowed, so a launch {@code finally} chain is never broken by
     * the confirmer itself.
     *
     * <p>Threading: only the arm counters are touched under {@code LOCK}; the
     * filter (un)install is marshalled to the UI thread OUTSIDE the monitor.
     * Blocking on {@link Display#syncExec} while holding {@code LOCK} would
     * deadlock: an MCP worker would wait for the UI thread while the UI thread
     * (running another tool's launch lambda) waits for {@code LOCK}.
     *
     * @param updateDialog arm the "Application update" TITLE matcher
     * @param sessionDialog arm the code-1003 "Debug session already exists" BODY matcher
     */
    public static void arm(boolean updateDialog, boolean sessionDialog)
    {
        // A DB restructure is a consequence of the same DB update, so the existing
        // launch callers (which arm the update matcher around their pre-launch update)
        // get the restructure matcher for free, gated on the update flag.
        arm(updateDialog, sessionDialog, updateDialog);
    }

    /**
     * Arms the auto-confirmer with all three independently-selectable matchers — the
     * "Application update" TITLE, the code-1003 "Debug session already exists" BODY,
     * and the DB-restructure ("Restructure data" / "Реорганизация информации") TITLE.
     * MUST be paired with {@link #disarm(boolean, boolean, boolean)} (same flags) in a
     * {@code finally} block. {@code update_database} arms ONLY the restructure matcher
     * ({@code arm(false, false, true)}) around its {@code IApplicationManager.update}
     * call; the launch paths arm update+restructure together via the two-arg overload.
     * Reentrant per matcher; no-op headless / all-false; never throws.
     *
     * @param updateDialog arm the "Application update" TITLE matcher
     * @param sessionDialog arm the code-1003 "Debug session already exists" BODY matcher
     * @param restructureDialog arm the DB-restructure TITLE matcher (press "Accept")
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog)
    {
        arm(updateDialog, sessionDialog, restructureDialog, null);
    }

    /**
     * Arms the auto-confirmer with all three boolean matchers plus the external-changes
     * conflict matcher, whose press is policy-driven. MUST be paired with
     * {@link #disarm(boolean, boolean, boolean, ExternalInfobaseChangesPolicy)} passing the
     * SAME arguments, in a {@code finally} block.
     *
     * <p>The conflict matcher is armed only when {@code conflictPolicy} is non-{@code null}
     * — a caller that leaves it {@code null} keeps EDT's "Infobase configuration changes"
     * modal for a human, exactly like the update opt-out.
     *
     * <p>No-op in a headless environment and when nothing at all is requested. Never throws.
     *
     * @param updateDialog arm the "Application update" TITLE matcher
     * @param sessionDialog arm the code-1003 "Debug session already exists" BODY matcher
     * @param restructureDialog arm the DB-restructure TITLE matcher (press "Accept")
     * @param conflictPolicy the answer for the external-changes conflict modal, or {@code null}
     *            to leave that matcher unarmed. NOTE: this overload names no infobase, so the arm
     *            is degraded to {@link ExternalInfobaseChangesPolicy#CANCEL} — nothing can be
     *            proven to be this caller's. Use the five-argument overload to allow a writing
     *            answer.
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog,
        ExternalInfobaseChangesPolicy conflictPolicy)
    {
        arm(updateDialog, sessionDialog, restructureDialog, conflictPolicy, null);
    }

    /**
     * Arms the auto-confirmer, additionally naming the INFOBASE whose update this window
     * covers so the conflict modal can be attributed (see {@link #CONFLICT_INFOBASE_NAMES}).
     * MUST be paired with the five-argument {@code disarm} passing the same values.
     *
     * @param updateDialog arm the "Application update" TITLE matcher
     * @param sessionDialog arm the code-1003 "Debug session already exists" BODY matcher
     * @param restructureDialog arm the DB-restructure TITLE matcher (press "Accept")
     * @param conflictPolicy the button to press on the external-changes conflict modal, or
     *            {@code null} to leave that modal alone
     * @param infobaseName the infobase this update targets, as EDT names it. When it cannot be
     *            resolved ({@code null}/blank), the arm is degraded to
     *            {@link ExternalInfobaseChangesPolicy#CANCEL}: the modal is still answered, so the
     *            call cannot hang, but nothing is written on a dialog whose ownership is unproven
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog,
        ExternalInfobaseChangesPolicy conflictPolicy, String infobaseName)
    {
        arm(updateDialog, sessionDialog, restructureDialog, conflictPolicy, infobaseName,
            StandaloneServerPortConflictPolicy.DEFAULT);
    }

    /**
     * Arms the auto-confirmer, additionally choosing how EDT's standalone-server port-conflict
     * modal is answered. MUST be paired with the six-argument {@code disarm} passing the same
     * values.
     *
     * @param updateDialog arm the "Application update" TITLE matcher
     * @param sessionDialog arm the code-1003 "Debug session already exists" BODY matcher
     * @param restructureDialog arm the DB-restructure TITLE matcher (press "Accept")
     * @param conflictPolicy the button to press on the external-changes conflict modal, or
     *            {@code null} to leave that modal alone
     * @param infobaseName the infobase this update targets, as EDT names it (may be {@code null})
     * @param portPolicy how to answer the port-conflict modal: {@code CANCEL} (default) refuses
     *            and writes nothing; {@code REASSIGN} presses "Find free port", which makes EDT
     *            REWRITE the server configuration. {@code null} is read as the default. The
     *            reassign answer requires UNANIMITY across the outstanding arms — see
     *            {@link #portConflictReassignArmCount}
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog, // NOSONAR mirrors the existing arm-flag list; a parameter object would move the arity, not remove it
        ExternalInfobaseChangesPolicy conflictPolicy, String infobaseName,
        StandaloneServerPortConflictPolicy portPolicy)
    {
        if (!updateDialog && !sessionDialog && !restructureDialog && conflictPolicy == null)
        {
            return;
        }
        Display display = safeDisplay();
        if (display == null)
        {
            return;
        }
        synchronized (LOCK)
        {
            if (updateDialog)
            {
                updateArmCount++;
            }
            if (sessionDialog)
            {
                sessionArmCount++;
            }
            if (restructureDialog)
            {
                restructureArmCount++;
            }
            // Armed by EVERY arm (the early return above already proved this call arms
            // something): the port-conflict modal blocks the standalone-server START that
            // the launch paths and update_database alike depend on — see portConflictArmCount.
            portConflictArmCount++;
            if (portPolicy == StandaloneServerPortConflictPolicy.REASSIGN)
            {
                portConflictReassignArmCount++;
            }
            if (conflictPolicy != null)
            {
                CONFLICT_ARMS.add(new ConflictArm(trimToNull(infobaseName),
                    attributableAnswer(infobaseName, conflictPolicy)));
            }
        }
        reconcileOnUiThread(display);
    }

    /**
     * Disarms the matchers armed by a matching {@link #arm(boolean, boolean)}.
     * The underlying {@link Display} filter is removed only once BOTH matchers
     * have no outstanding arm. Pass the SAME flags that were passed to
     * {@code arm} so each reentrant counter stays balanced.
     *
     * <p>Never throws (see {@link #arm(boolean, boolean)}): callers invoke this
     * from {@code finally} blocks, where an exception would mask the original
     * launch failure.
     *
     * @param updateDialog release one update-matcher arm
     * @param sessionDialog release one session-matcher arm
     */
    public static void disarm(boolean updateDialog, boolean sessionDialog)
    {
        disarm(updateDialog, sessionDialog, updateDialog);
    }

    /**
     * Disarms the matchers armed by a matching {@link #arm(boolean, boolean, boolean)}
     * (same flags). The underlying {@link Display} filter is removed only once ALL
     * three matchers have no outstanding arm. Never throws.
     *
     * @param updateDialog release one update-matcher arm
     * @param sessionDialog release one session-matcher arm
     * @param restructureDialog release one restructure-matcher arm
     */
    public static void disarm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog)
    {
        disarm(updateDialog, sessionDialog, restructureDialog, null);
    }

    /**
     * Disarms the matchers armed by a matching
     * {@link #arm(boolean, boolean, boolean, ExternalInfobaseChangesPolicy)} (same
     * arguments). The underlying {@link Display} filter is removed only once EVERY matcher
     * — including every per-policy conflict arm — has no outstanding arm. Never throws.
     *
     * @param updateDialog release one update-matcher arm
     * @param sessionDialog release one session-matcher arm
     * @param restructureDialog release one restructure-matcher arm
     * @param conflictPolicy release one conflict-matcher arm of THIS policy, or {@code null}
     */
    public static void disarm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog,
        ExternalInfobaseChangesPolicy conflictPolicy)
    {
        disarm(updateDialog, sessionDialog, restructureDialog, conflictPolicy, null);
    }

    /**
     * Disarms an arm made with the five-argument
     * {@link #arm(boolean, boolean, boolean, ExternalInfobaseChangesPolicy, String)} — pass the
     * SAME values so both the per-policy counter and the armed infobase name are released.
     *
     * @param updateDialog release one update-matcher arm
     * @param sessionDialog release one session-matcher arm
     * @param restructureDialog release one restructure-matcher arm
     * @param conflictPolicy release one conflict-matcher arm of THIS policy, or {@code null}
     * @param infobaseName the name passed to {@code arm} (may be {@code null})
     */
    public static void disarm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog,
        ExternalInfobaseChangesPolicy conflictPolicy, String infobaseName)
    {
        disarm(updateDialog, sessionDialog, restructureDialog, conflictPolicy, infobaseName,
            StandaloneServerPortConflictPolicy.DEFAULT);
    }

    /**
     * Disarms an arm made with the six-argument
     * {@link #arm(boolean, boolean, boolean, ExternalInfobaseChangesPolicy, String,
     * StandaloneServerPortConflictPolicy)} — pass the SAME values, so the port-conflict
     * policy counter is released by the arm that took it.
     *
     * @param updateDialog release one update-matcher arm
     * @param sessionDialog release one session-matcher arm
     * @param restructureDialog release one restructure-matcher arm
     * @param conflictPolicy release one conflict-matcher arm of THIS policy, or {@code null}
     * @param infobaseName the name passed to {@code arm} (may be {@code null})
     * @param portPolicy the port-conflict policy passed to {@code arm} (may be {@code null})
     */
    public static void disarm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog, // NOSONAR mirrors arm(...)
        ExternalInfobaseChangesPolicy conflictPolicy, String infobaseName,
        StandaloneServerPortConflictPolicy portPolicy)
    {
        if (!updateDialog && !sessionDialog && !restructureDialog && conflictPolicy == null)
        {
            return;
        }
        Display display;
        synchronized (LOCK)
        {
            if (updateDialog && updateArmCount > 0)
            {
                updateArmCount--;
            }
            if (sessionDialog && sessionArmCount > 0)
            {
                sessionArmCount--;
            }
            if (restructureDialog && restructureArmCount > 0)
            {
                restructureArmCount--;
            }
            // Mirrors the unconditional increment in arm(...) — same early return above, so
            // every arm has exactly one matching disarm here.
            if (portConflictArmCount > 0)
            {
                portConflictArmCount--;
            }
            if (portPolicy == StandaloneServerPortConflictPolicy.REASSIGN
                && portConflictReassignArmCount > 0)
            {
                portConflictReassignArmCount--;
            }
            if (conflictPolicy != null)
            {
                CONFLICT_ARMS.remove(new ConflictArm(trimToNull(infobaseName),
                    attributableAnswer(infobaseName, conflictPolicy)));
            }
            display = filterDisplay;
        }
        if (display == null)
        {
            // No filter was ever installed (headless no-op arm, or a concurrent
            // arm() whose UI-thread install has not run yet — that install then
            // sees the decremented counters and is skipped).
            return;
        }
        reconcileOnUiThread(display);
    }

    /**
     * Marshals {@link #reconcileFilter(Display)} to the UI thread. Called
     * WITHOUT holding {@code LOCK} (the blocking {@code syncExec} under the
     * monitor was a deadlock, R1). Never throws: a display disposed between
     * the check and the {@code syncExec} (workbench shutdown race) is benign —
     * the filter dies with the display and the counter stays consistent.
     */
    private static void reconcileOnUiThread(Display display)
    {
        if (display.isDisposed())
        {
            return;
        }
        try
        {
            display.syncExec(() -> reconcileFilter(display));
        }
        catch (SWTException e)
        {
            // ERROR_DEVICE_DISPOSED race on shutdown — nothing to (un)install.
        }
    }

    /**
     * Brings the single installed {@link Display} filter in line with the current
     * arm counts. The ONE global filter is installed while EITHER matcher is armed
     * ({@code updateArmCount + sessionArmCount > 0}) and removed once both reach
     * zero; which branch the listener acts on is decided per-event from the live
     * counts. Runs on the UI thread only; takes {@code LOCK} just for the state
     * decision (never blocks inside the monitor), then performs the actual
     * {@code addFilter}/{@code removeFilter} outside it. Because every install and
     * removal funnels through here on the UI thread against the live counters, a
     * concurrent arm/disarm pair can never leave a filter installed with no armed
     * owner (or vice versa).
     */
    private static void reconcileFilter(Display display)
    {
        Listener toInstall = null;
        Listener toRemove = null;
        synchronized (LOCK)
        {
            // A filter whose display died with the workbench is already gone —
            // drop the stale reference so a future arm() can reinstall.
            if (filter != null && (filterDisplay == null || filterDisplay.isDisposed()))
            {
                filter = null;
                filterDisplay = null;
            }
            boolean anyArmed = updateArmCount > 0 || sessionArmCount > 0 || restructureArmCount > 0
                || portConflictArmCount > 0 || !CONFLICT_ARMS.isEmpty();
            if (anyArmed && filter == null)
            {
                toInstall = createFilterListener();
                filter = toInstall;
                filterDisplay = display;
            }
            else if (!anyArmed && filter != null)
            {
                toRemove = filter;
                filter = null;
                filterDisplay = null;
            }
        }
        if (toInstall != null)
        {
            display.addFilter(SWT.Activate, toInstall);
            display.addFilter(SWT.Show, toInstall);
        }
        if (toRemove != null)
        {
            display.removeFilter(SWT.Activate, toRemove);
            display.removeFilter(SWT.Show, toRemove);
        }
        sweepAlreadyOpenShells(display);
    }

    /**
     * Presses the dialogs an armed matcher claims that are ALREADY on screen.
     *
     * <p>The filter this class installs reacts to {@link SWT#Activate} / {@link SWT#Show} —
     * both are EVENTS, so a modal that was raised and activated BEFORE the arm produces no
     * event for it to see and is never pressed. That gap is not theoretical: a launch's own
     * dialog can outlive the window armed around it, and once an application-modal shell is up
     * unattended, nothing on the wire can clear it — every later call blocks behind it and the
     * run never recovers, which is exactly the hang reported in #357.
     *
     * <p>Deliberately NOT a wider match: it applies the SAME predicate the filter applies
     * ({@link #claimsDialog}), so it can only press a dialog the filter would have pressed had
     * it seen the event. A dialog no armed matcher claims — one that genuinely needs a human —
     * is left exactly as it was.
     *
     * <p>Runs on the UI thread ({@link #reconcileFilter} is called through
     * {@link #reconcileOnUiThread}); the press itself is deferred like the filter's, so it
     * executes inside the modal's own event loop.
     *
     * @param display the workbench display (never {@code null} here)
     */
    private static void sweepAlreadyOpenShells(Display display)
    {
        Shell[] shells;
        try
        {
            shells = display.getShells();
        }
        catch (SWTException e)
        {
            return; // display died in the shutdown race — nothing to press
        }
        List<IOpenDialog> open = new ArrayList<>();
        for (Shell shell : shells)
        {
            if (shell == null || shell.isDisposed() || !shell.isVisible())
            {
                // Visibility keeps the sweep no wider than the filter it stands in for: the
                // filter reacts to Show/Activate, so a shell that JFace has constructed but not
                // yet opened is one the filter would not have touched either.
                continue;
            }
            open.add(new IOpenDialog()
            {
                @Override
                public String title()
                {
                    return safeShellText(shell);
                }

                @Override
                public String body()
                {
                    return readDialogBody(shell);
                }

                @Override
                public void press()
                {
                    Activator.logInfo("Auto-confirmer sweep found an already-open dialog '" //$NON-NLS-1$
                        + safeShellText(shell) + "' claimed by an armed matcher"); //$NON-NLS-1$
                    // Deferred like the filter's press, so it runs inside the modal's own loop.
                    display.asyncExec(() -> pressConfirmButton(shell));
                }
            });
        }
        sweepOpenDialogs(currentArms(), open);
    }

    /**
     * A dialog that is already on screen, reduced to what the sweep decision needs.
     *
     * <p>Exists so the sweep is provable without an SWT {@link Shell}: the decision it encodes —
     * which already-open dialogs get pressed and which are left for a human — is the whole point
     * of the sweep, and it must not be verifiable only by watching a live workbench.
     */
    interface IOpenDialog
    {
        /** The dialog's shell title. */
        String title();

        /** The dialog's message body; consulted only when a body matcher is armed. */
        String body();

        /** Presses the button an armed matcher selects for this dialog. */
        void press();
    }

    /**
     * Presses every already-open dialog {@code armed} claims, and only those.
     *
     * <p>Package-private and pure over {@link IOpenDialog} (test seam). The claim predicate is
     * literally the one the {@link Display} filter uses, so the sweep can never press something
     * the filter would have left alone — a widening here would auto-answer a dialog that
     * genuinely needs a human, which is worse than the hang it is meant to clear.
     *
     * @param armed which matchers are armed
     * @param open the dialogs currently on screen
     * @return how many were pressed
     */
    static int sweepOpenDialogs(ArmState armed, List<IOpenDialog> open)
    {
        int pressed = 0;
        for (IOpenDialog dialog : open)
        {
            if (!claims(armed, dialog.title(), dialog::body))
            {
                continue;
            }
            dialog.press();
            pressed++;
        }
        return pressed;
    }

    /**
     * Which matchers are armed at one instant.
     *
     * <p>A snapshot, not a live read: the counts change between events, and a decision that
     * re-read them mid-way could claim a dialog under one matcher and press it under another.
     */
    static final class ArmState
    {
        final boolean update;
        final boolean session;
        final boolean restructure;
        final boolean conflict;
        final boolean portConflict;

        ArmState(boolean update, boolean session, boolean restructure, boolean conflict)
        {
            this(update, session, restructure, conflict, false);
        }

        ArmState(boolean update, boolean session, boolean restructure, boolean conflict,
            boolean portConflict)
        {
            this.update = update;
            this.session = session;
            this.restructure = restructure;
            this.conflict = conflict;
            this.portConflict = portConflict;
        }
    }

    /**
     * Snapshots the live arm counters.
     *
     * <p>All four are read under ONE hold of {@code LOCK} (it is reentrant, so the nested
     * {@link #conflictMatcherArmed()} is free). Reading the conflict arm after releasing the
     * monitor could return a combination that never existed — the update matcher armed and the
     * conflict matcher not, after a disarm that dropped both — and the decision would then judge
     * a dialog against a state no caller ever asked for.
     */
    private static ArmState currentArms()
    {
        synchronized (LOCK)
        {
            // The conflict matcher is armed per policy; an empty arm list means "not armed".
            return new ArmState(updateArmCount > 0, sessionArmCount > 0, restructureArmCount > 0,
                conflictMatcherArmed(), portConflictArmCount > 0);
        }
    }

    /**
     * Whether an armed matcher claims a dialog with this title/body.
     *
     * <p>The single decision shared by the {@link Display} filter and the already-open sweep, so
     * the two can never diverge — a sweep that claimed more than the filter would widen the
     * auto-press, and one that claimed less would leave on screen the very dialog it exists for.
     *
     * @param armed the arm snapshot to judge against
     * @param title the dialog shell title (may be {@code null})
     * @param body supplies the message body; invoked ONLY when a body matcher is armed and no
     *            armed TITLE matcher already claimed the dialog (the walk is not free)
     * @return {@code true} when its default (or policy-selected) button should be pressed
     */
    private static boolean claims(ArmState armed, String title, Supplier<String> body)
    {
        // The body is only read (a widget-tree walk) when the title did not already
        // match an armed TITLE matcher (update, restructure or conflict) AND the
        // session matcher is armed — otherwise it is needless work.
        boolean titleMatched = (armed.update && isTargetTitle(title))
            || (armed.restructure && isRestructureTitle(title))
            || (armed.conflict && isConflictTitle(title))
            || (armed.portConflict && isPortConflictTitle(title));
        boolean needBody = armed.session && !titleMatched;
        return shouldAutoConfirm(armed.update, armed.session, armed.restructure, armed.conflict,
            armed.portConflict, title, needBody ? body.get() : null);
    }

    /**
     * Creates the single {@link Display} filter that watches for the modals we
     * auto-confirm and schedules the per-dialog auto-press. Two matchers share
     * this ONE global filter (reconciled under {@code LOCK} — no second filter, no
     * deadlock), but each acts only while its OWN matcher is armed:
     * <ul>
     *   <li>the "Application update" modal — matched on the exact shell TITLE
     *       ({@link #isTargetTitle}), acted on only while {@code updateArmCount > 0};</li>
     *   <li>the code-1003 "Debug session already exists" modal — matched on the
     *       message BODY prefix ({@link #isDebugSessionExistsBody}), because its
     *       shell title is the generic "Question"/"Вопрос", acted on
     *       only while {@code sessionArmCount > 0}.</li>
     * </ul>
     * Gating per-matcher preserves the update opt-out: an arm with
     * {@code updateDialog=false} leaves the update branch inert (its modal is left
     * for a human) while the session branch still fires. The auto-press is chosen
     * PER DIALOG by {@link #pressConfirmButton}: the update modal completes via its
     * DEFAULT button ("Update then run"); the 1003 modal completes via its <b>"Keep
     * existing and start new"</b> (LAUNCH_ANYWAY) button so an already-running session
     * — a standalone-server debug target, or another client in a race — survives and
     * the new client comes up alongside it, instead of the default button's "Stop
     * existing and start new" terminating it.
     */
    private static Listener createFilterListener()
    {
        return event -> {
            if (!(event.widget instanceof Shell))
            {
                return;
            }
            Shell shell = (Shell)event.widget;
            String title;
            try
            {
                title = shell.getText();
            }
            catch (RuntimeException e)
            {
                return;
            }
            // The SAME decision the already-open sweep applies — see claims / sweepOpenDialogs.
            if (!claims(currentArms(), title, () -> readDialogBody(shell)))
            {
                return;
            }
            // Defer so the modal finishes building its button bar and enters
            // its event loop; the press then runs inside that loop.
            shell.getDisplay().asyncExec(() -> pressConfirmButton(shell));
        };
    }

    /**
     * Pure gating decision for the {@link Display} filter (and the test seam for the
     * matcher split): given which matchers are currently armed and the dialog's
     * title/body, should its default button be auto-pressed? The two matchers are
     * gated independently so the update opt-out is honored:
     * <ul>
     *   <li>the update-TITLE branch fires only when {@code updateArmed} — so an
     *       arm with {@code updateDialog=false} never auto-presses the "Application
     *       update" modal (the opt-out is preserved);</li>
     *   <li>the 1003-BODY branch fires only when {@code sessionArmed} — so it
     *       fires on the debug path regardless of {@code updateBeforeLaunch}, and a
     *       session-only arm never reacts to the update modal's title.</li>
     * </ul>
     *
     * @param updateArmed is the update-TITLE matcher armed
     * @param sessionArmed is the 1003-BODY matcher armed
     * @param title the dialog shell title (may be {@code null})
     * @param body the dialog message body (may be {@code null}; only consulted when
     *            {@code sessionArmed})
     * @return {@code true} when an armed matcher claims this dialog
     */
    static boolean shouldAutoConfirm(boolean updateArmed, boolean sessionArmed, String title, String body)
    {
        return shouldAutoConfirm(updateArmed, sessionArmed, false, title, body);
    }

    /**
     * Pure gating decision including the DB-restructure matcher. The restructure-TITLE
     * branch fires only when {@code restructureArmed} (e.g. {@code update_database} or a
     * pre-launch update); it routes through {@link #pressConfirmButton}'s default-button
     * path (presses "Accept"), like the "Application update" modal. Disjoint from the
     * update title (distinct strings) and from the 1003 body matcher.
     *
     * @param updateArmed is the "Application update" TITLE matcher armed
     * @param sessionArmed is the 1003 "Debug session already exists" BODY matcher armed
     * @param restructureArmed is the DB-restructure TITLE matcher armed
     * @param title the dialog shell title (may be {@code null})
     * @param body the dialog message body (may be {@code null}; only consulted when
     *            {@code sessionArmed})
     * @return {@code true} when an armed matcher claims this dialog
     */
    static boolean shouldAutoConfirm(boolean updateArmed, boolean sessionArmed, boolean restructureArmed,
        String title, String body)
    {
        return shouldAutoConfirm(updateArmed, sessionArmed, restructureArmed, false, title, body);
    }

    /**
     * Pure gating decision including the external-changes conflict matcher. The conflict
     * branch fires only when {@code conflictArmed} (i.e. the caller supplied an
     * {@link ExternalInfobaseChangesPolicy}); its title is disjoint from the other two.
     *
     * @param updateArmed is the "Application update" TITLE matcher armed
     * @param sessionArmed is the 1003 "Debug session already exists" BODY matcher armed
     * @param restructureArmed is the DB-restructure TITLE matcher armed
     * @param conflictArmed is the external-changes conflict TITLE matcher armed
     * @param title the dialog shell title (may be {@code null})
     * @param body the dialog message body (may be {@code null}; only consulted when
     *            {@code sessionArmed})
     * @return {@code true} when an armed matcher claims this dialog
     */
    static boolean shouldAutoConfirm(boolean updateArmed, boolean sessionArmed, boolean restructureArmed,
        boolean conflictArmed, String title, String body)
    {
        return shouldAutoConfirm(updateArmed, sessionArmed, restructureArmed, conflictArmed, false,
            title, body);
    }

    /**
     * Pure gating decision including the standalone-server port-conflict matcher. That branch
     * fires only when {@code portConflictArmed} — which every {@code arm} sets, because the modal
     * blocks the server START both the launch tools and {@code update_database} depend on and its
     * auto-answer (Cancel) writes nothing. Its title is disjoint from the other three.
     *
     * @param updateArmed is the "Application update" TITLE matcher armed
     * @param sessionArmed is the 1003 "Debug session already exists" BODY matcher armed
     * @param restructureArmed is the DB-restructure TITLE matcher armed
     * @param conflictArmed is the external-changes conflict TITLE matcher armed
     * @param portConflictArmed is the standalone-server port-conflict TITLE matcher armed
     * @param title the dialog shell title (may be {@code null})
     * @param body the dialog message body (may be {@code null}; only consulted when
     *            {@code sessionArmed})
     * @return {@code true} when an armed matcher claims this dialog
     */
    static boolean shouldAutoConfirm(boolean updateArmed, boolean sessionArmed, boolean restructureArmed,
        boolean conflictArmed, boolean portConflictArmed, String title, String body)
    {
        if (updateArmed && isTargetTitle(title))
        {
            return true;
        }
        if (restructureArmed && isRestructureTitle(title))
        {
            return true;
        }
        if (conflictArmed && isConflictTitle(title))
        {
            return true;
        }
        if (portConflictArmed && isPortConflictTitle(title))
        {
            return true;
        }
        // The generic "Question" title can't be matched (it would dismiss every
        // question dialog), so the 1003 modal is keyed on its message BODY instead.
        return sessionArmed && isDebugSessionExistsBody(body);
    }

    /**
     * Reads the message-body text of a JFace dialog shell by walking its widget
     * tree and returning the first non-blank {@link Label}/{@link CLabel}/
     * {@link Text}/{@link Link} text that matches a known 1003 body prefix — or, if
     * none matches, the first non-blank label-like text found. JFace
     * {@code MessageDialog} renders its message in such a control inside the dialog
     * area; the shell title alone is too generic to key on. Bounded depth and fully
     * guarded — never throws onto the UI thread.
     *
     * @param shell the dialog shell (may be {@code null}/disposed)
     * @return a candidate message-body string, or {@code null} if none was found
     */
    static String readDialogBody(Shell shell)
    {
        if (shell == null || shell.isDisposed())
        {
            return null;
        }
        try
        {
            return findBodyText(shell, 0);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Depth-bounded pre-order walk of a control tree collecting label-like text.
     * Returns the first text that already matches a 1003 prefix (so the caller's
     * decision is unambiguous); failing that, the first non-blank label-like text it
     * sees (a best-effort fallback that the prefix check then rejects for unrelated
     * dialogs).
     */
    private static String findBodyText(Control control, int depth)
    {
        if (control == null || control.isDisposed() || depth > MAX_BODY_SCAN_DEPTH)
        {
            return null;
        }
        // This control's own label-like text: a 1003-prefix match short-circuits; // NOSONAR explanatory comment, not commented-out code
        // otherwise it is the running best-effort fallback.
        String firstSeen = ownLabelMatch(control);
        if (firstSeen != null && isDebugSessionExistsBody(firstSeen))
        {
            return firstSeen;
        }
        if (control instanceof Composite)
        {
            return scanChildren((Composite)control, depth, firstSeen);
        }
        return firstSeen;
    }

    /**
     * The control's OWN label-like text when non-blank, else {@code null}. Pure
     * sub-block of {@link #findBodyText}: a {@link #isDebugSessionExistsBody}
     * prefix match is preserved for the caller to short-circuit on.
     */
    private static String ownLabelMatch(Control control)
    {
        String text = labelLikeText(control);
        return text != null && !text.trim().isEmpty() ? text : null;
    }

    /**
     * Pre-order scan of a composite's children for {@link #findBodyText}: returns
     * the first child text matching a 1003 prefix, else {@code firstSeen} (the
     * parent's running best-effort fallback, kept if the parent already had one or
     * promoted to the first non-blank child text otherwise). Same skipping and
     * first-wins fallback semantics as the inline loop it replaces.
     */
    private static String scanChildren(Composite composite, int depth, String firstSeen)
    {
        String best = firstSeen;
        for (Control child : composite.getChildren())
        {
            String childText = findBodyText(child, depth + 1);
            if (childText != null)
            {
                if (isDebugSessionExistsBody(childText))
                {
                    return childText;
                }
                if (best == null)
                {
                    best = childText;
                }
            }
        }
        return best;
    }

    /** @return the text of a {@link Label}/{@link CLabel}/{@link Text}/{@link Link}, else {@code null}. */
    private static String labelLikeText(Control control)
    {
        try
        {
            if (control instanceof Label)
            {
                return ((Label)control).getText();
            }
            if (control instanceof CLabel)
            {
                return ((CLabel)control).getText();
            }
            if (control instanceof Text)
            {
                return ((Text)control).getText();
            }
            if (control instanceof Link)
            {
                return ((Link)control).getText();
            }
        }
        catch (RuntimeException e)
        {
            return null;
        }
        return null;
    }

    /**
     * How a matched dialog is auto-completed — the pure outcome of
     * {@link #chooseConfirmAction} (and the unit-test seam pinning the 1003
     * fallback policy).
     */
    enum ConfirmAction
    {
        /**
         * 1003 modal, labelled keep-button present: press "Keep existing and
         * start new" (LAUNCH_ANYWAY).
         */
        PRESS_KEEP_BUTTON,
        /**
         * 1003 modal whose keep-button could not be located by label: CANCEL the
         * dialog — never press the default button, which on this modal is the
         * destructive "Stop existing and start new" (RESTART_APPLICATION) and
         * would terminate the very session the keep-press exists to protect.
         */
        CANCEL_DIALOG,
        /** The "Application update" modal: press its default button ("Update then run"). */
        PRESS_DEFAULT_BUTTON,
        /**
         * The external-changes conflict modal: press the LABELLED button the call's
         * {@link ExternalInfobaseChangesPolicy} selects ("Override" / "Import"). Its
         * default button is "Import", which rewrites the project sources, so it is never
         * pressed blind: a policy whose label is not found falls back to
         * {@link #CANCEL_DIALOG}.
         */
        PRESS_POLICY_BUTTON;
    }

    /**
     * Pure decision (and test seam): how should a dialog this filter matched be
     * auto-completed? The update modal always completes via its default button.
     * The 1003 modal completes via the labelled keep-button when one was found;
     * when the label lookup fails (an unshipped locale, a reworded button) the
     * dialog is CANCELLED instead — cancelling aborts only the NEW launch and is
     * non-destructive, whereas the modal's default button would stop the
     * existing session.
     *
     * @param debugSessionDialog {@code true} when the dialog body matched the
     *            code-1003 "Debug session already exists" modal
     * @param keepButtonFound {@code true} when {@link #findKeepExistingButton}
     *            located the labelled keep-button (only meaningful for the 1003 modal)
     * @return the action that completes the dialog
     */
    static ConfirmAction chooseConfirmAction(boolean debugSessionDialog, boolean keepButtonFound)
    {
        if (!debugSessionDialog)
        {
            return ConfirmAction.PRESS_DEFAULT_BUTTON;
        }
        return keepButtonFound ? ConfirmAction.PRESS_KEEP_BUTTON : ConfirmAction.CANCEL_DIALOG;
    }

    /**
     * Pure decision (and test seam): how should EDT's external-changes conflict modal be
     * completed for the given policy?
     * <ul>
     *   <li>{@link ExternalInfobaseChangesPolicy#CANCEL} (or a {@code null} policy, i.e. a dialog
     *       that could not be attributed) → {@link ConfirmAction#CANCEL_DIALOG}: nothing is written
     *       on either side and the update call fails with an actionable error instead of
     *       hanging;</li>
     *   <li>{@code OVERRIDE}/{@code IMPORT} → {@link ConfirmAction#PRESS_POLICY_BUTTON} when
     *       the labelled button was located, else {@code CANCEL_DIALOG} — the modal's
     *       DEFAULT button is "Import" (it rewrites the project sources), so a label miss
     *       must never fall through to it.</li>
     * </ul>
     *
     * @param policy the policy this arm selected (may be {@code null})
     * @param policyButtonFound {@code true} when the policy's labelled button was located
     * @return the action that completes the conflict modal
     */
    static ConfirmAction chooseConflictAction(ExternalInfobaseChangesPolicy policy, boolean policyButtonFound)
    {
        if (policy == null || policy == ExternalInfobaseChangesPolicy.CANCEL || !policyButtonFound)
        {
            return ConfirmAction.CANCEL_DIALOG;
        }
        return ConfirmAction.PRESS_POLICY_BUTTON;
    }

    /**
     * Returns the localized button labels that carry out the given policy on EDT's
     * external-changes conflict modal, or {@code null} when the policy presses no button
     * ({@link ExternalInfobaseChangesPolicy#CANCEL} cancels the dialog instead).
     *
     * @param policy the policy (may be {@code null})
     * @return the labels to match, or {@code null}
     */
    static Set<String> conflictButtonLabels(ExternalInfobaseChangesPolicy policy)
    {
        if (policy == ExternalInfobaseChangesPolicy.OVERRIDE)
        {
            return CONFLICT_OVERRIDE_BUTTONS;
        }
        if (policy == ExternalInfobaseChangesPolicy.IMPORT)
        {
            return CONFLICT_IMPORT_BUTTONS;
        }
        return null;
    }

    /**
     * Auto-completes a matched dialog, the action chosen PER DIALOG by
     * {@link #chooseConfirmAction}:
     * <ul>
     *   <li><b>code-1003 "Debug session already exists"</b> (body matches
     *       {@link #isDebugSessionExistsBody}) → press the <b>"Keep existing and
     *       start new" / "Сохранить старую и запустить новую"</b> button
     *       (LAUNCH_ANYWAY, index 1), located by its label among the shell's buttons
     *       ({@link #findKeepExistingButton}). This keeps the already-running session
     *       (a standalone-server debug target, or another client in a race) ALIVE and
     *       starts the new client alongside it — pressing the DEFAULT button here
     *       (index 0, "Stop existing and start new" / RESTART_APPLICATION) would
     *       wrongly TERMINATE it. If the keep-button label is not found, the dialog
     *       is CANCELLED ({@link Shell#close()} — JFace maps the close to the
     *       dialog's Cancel) and the miss is logged: the existing session survives
     *       and the new launch aborts cleanly instead of stopping it
     *       ({@link ConfirmAction#CANCEL_DIALOG}).</li>
     *   <li><b>"Application update" modal</b> (everything else this filter matched)
     *       → press the <b>default</b> button ("Update then run", index 0), unchanged.</li>
     * </ul>
     * Guarded against disposal and never throws onto the UI thread.
     */
    private static void pressConfirmButton(Shell shell)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            // The external-changes conflict modal is keyed on its own TITLE and completed
            // by a policy-selected LABELLED button (its default button rewrites the
            // project) — decided before the generic body walk below.
            if (isConflictTitle(safeShellText(shell)))
            {
                // Deferred like every other press: the attribution reads the dialog BODY, and at
                // SWT.Show time the message area may not be populated yet — inside the asyncExec
                // the dialog is fully built and pumping its own event loop.
                shell.getDisplay().asyncExec(() -> pressConflictButton(shell));
                return;
            }
            // The standalone-server port-conflict modal is keyed on its own TITLE and completed
            // by its LABELLED Cancel button — its DEFAULT button rewrites the server's port
            // configuration, so it is never pressed blind. Deferred for the same reason as the
            // conflict modal: the busy-port list is read from the dialog BODY.
            if (isPortConflictTitle(safeShellText(shell)))
            {
                shell.getDisplay().asyncExec(() -> answerPortConflictDialog(shell));
                return;
            }
            // Distinguish the two modals (the body walk is cheap and also drives the
            // per-dialog button choice + the log trail an unattended run leaves).
            boolean debugSessionDialog = isDebugSessionExistsBody(readDialogBody(shell));
            Button keepButton = debugSessionDialog ? findKeepExistingButton(shell) : null;
            switch (chooseConfirmAction(debugSessionDialog, keepButton != null))
            {
            case PRESS_KEEP_BUTTON:
                // The 1003 modal: keep the existing session, start the new one
                // ALONGSIDE it (LAUNCH_ANYWAY) — never the default "stop existing".
                Activator.logInfo("Auto-confirming debug-session dialog '" //$NON-NLS-1$
                    + safeShellText(shell) + "' via button '" + safeText(keepButton) //$NON-NLS-1$
                    + "' (keep existing and start new)"); //$NON-NLS-1$
                pressButton(keepButton);
                return;
            case CANCEL_DIALOG:
                // No labelled keep-button found. The default button here is the
                // DESTRUCTIVE "Stop existing and start new" — never press it blind.
                // Cancel the dialog instead (Shell.close() = the dialog's Cancel):
                // the existing session survives and the new launch aborts cleanly
                // rather than hanging on the modal.
                Activator.logError("Auto-confirm: keep-button not found by label in " //$NON-NLS-1$
                    + "debug-session dialog '" + safeShellText(shell) //$NON-NLS-1$
                    + "' — cancelling the dialog instead of pressing its destructive " //$NON-NLS-1$
                    + "default button", null); //$NON-NLS-1$
                shell.close();
                return;
            case PRESS_DEFAULT_BUTTON:
            default:
                // The update modal: press its default button ("Update then run").
                Button button = shell.getDefaultButton();
                if (button == null || button.isDisposed())
                {
                    return;
                }
                Activator.logInfo("Auto-confirming launch dialog '" + safeShellText(shell) //$NON-NLS-1$
                    + "' via button '" + safeText(button) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
                pressButton(button);
                return;
            }
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to auto-confirm the launch update dialog", e); //$NON-NLS-1$
        }
    }

    /**
     * Locates the code-1003 modal's "Keep existing and start new" (LAUNCH_ANYWAY)
     * button by its label — in either EDT locale ({@link #DEBUG_SESSION_KEEP_BUTTONS})
     * — among all {@link Button}s in the shell's widget tree. Matching by label,
     * rather than a fixed index, stays correct if EDT reorders the button bar. Returns
     * the first non-disposed match, or {@code null} when no labelled keep-button is
     * present (the caller then CANCELS the dialog — see {@link #chooseConfirmAction};
     * the default button here is the destructive "stop existing" choice).
     * Bounded-depth, fully guarded — never throws onto the UI thread.
     *
     * @param shell the 1003 dialog shell (may be {@code null}/disposed)
     * @return the keep-existing button, or {@code null}
     */
    static Button findKeepExistingButton(Shell shell)
    {
        if (shell == null || shell.isDisposed())
        {
            return null;
        }
        try
        {
            return findButtonByLabel(shell, 0);
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    /**
     * Depth-bounded pre-order walk returning the first {@link Button} whose text is a
     * known "keep existing and start new" label ({@link #isKeepExistingLabel}).
     */
    private static Button findButtonByLabel(Control control, int depth)
    {
        return findButtonByLabel(control, depth, DEBUG_SESSION_KEEP_BUTTONS);
    }

    /**
     * Depth-bounded pre-order walk returning the first {@link Button} whose text is one of
     * {@code labels} (exact, whole-label compare after trimming SWT's mnemonic markers).
     * Shared by the 1003 keep-button lookup and the conflict modal's policy button.
     *
     * @param control the widget subtree root (may be {@code null}/disposed)
     * @param depth current recursion depth
     * @param labels the accepted localized labels, never {@code null}
     * @return the first matching button, or {@code null}
     */
    private static Button findButtonByLabel(Control control, int depth, Set<String> labels)
    {
        if (control == null || control.isDisposed() || depth > MAX_BODY_SCAN_DEPTH)
        {
            return null;
        }
        if (control instanceof Button)
        {
            Button b = (Button)control;
            try
            {
                if (matchesButtonLabel(b.getText(), labels))
                {
                    return b;
                }
            }
            catch (RuntimeException e)
            {
                // ignore this button, keep scanning
            }
        }
        if (control instanceof Composite)
        {
            for (Control child : ((Composite)control).getChildren())
            {
                Button found = findButtonByLabel(child, depth + 1, labels);
                if (found != null)
                {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Pure decision (and test seam): is the given button label the 1003 modal's
     * "Keep existing and start new" / "Сохранить старую и запустить новую"
     * (LAUNCH_ANYWAY) button, in either EDT locale? JFace strips no mnemonic here, so
     * a leading {@code &} mnemonic marker (if any) is removed before the exact compare.
     *
     * @param label a button label (may be {@code null})
     * @return {@code true} when {@code label} is a known keep-existing label
     */
    static boolean isKeepExistingLabel(String label)
    {
        return matchesButtonLabel(label, DEBUG_SESSION_KEEP_BUTTONS);
    }

    /**
     * Pure decision (and test seam): is the given button label one of {@code labels}, in
     * any EDT locale? JFace strips no mnemonic here, so {@code &} mnemonic markers (if
     * any) are removed before the exact, whole-label compare.
     *
     * @param label a button label (may be {@code null})
     * @param labels the accepted localized labels (may be {@code null})
     * @return {@code true} when {@code label} matches one of {@code labels}
     */
    static boolean matchesButtonLabel(String label, Set<String> labels)
    {
        if (label == null || labels == null)
        {
            return false;
        }
        String normalized = label.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return labels.contains(normalized);
    }

    /**
     * Completes EDT's external-changes conflict modal ("Infobase configuration changes")
     * according to {@code policy}: presses the labelled "Override"/"Import" button, or —
     * for {@link ExternalInfobaseChangesPolicy#CANCEL}, a {@code null} policy, or a label
     * that could not be located — CANCELS the dialog ({@link Shell#close()}, which JFace
     * maps to the dialog's Cancel). The modal's DEFAULT button is "Import", which rewrites
     * the caller's PROJECT sources, so it is never pressed blind. The update call then
     * returns EDT's own "not resolved" failure, which the tool reports as an actionable
     * error — an unattended run never hangs on this modal either way.
     *
     * <p>Runs on the UI thread; fully guarded — never throws.
     *
     * @param shell the conflict dialog shell
     */
    private static void pressConflictButton(Shell shell)
    {
        if (shell == null || shell.isDisposed())
        {
            return;
        }
        try
        {
            pressConflictButtonUnguarded(shell);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to complete the infobase-changed-outside-EDT dialog", e); //$NON-NLS-1$
        }
    }

    /** The body of {@link #pressConflictButton}, called inside its disposal/exception guard. */
    private static void pressConflictButtonUnguarded(Shell shell)
    {
        // Attribute the dialog and choose in ONE step: the answer belongs to the arm that named
        // THIS infobase, so two concurrent updates of different infobases keep their own policies.
        // Anything unattributable — a foreign infobase, a manually opened dialog, a body we cannot
        // read — yields null, i.e. cancel rather than a writing press.
        ConflictDecision decision = decideFor(collectDialogText(shell));
        ExternalInfobaseChangesPolicy effective = decision.policy;
        String attributedName = decision.infobaseName;
        if (effective == null)
        {
            // Not attributable: the dialog names another infobase, or its text could not be read.
            // It is CANCELLED rather than left alone - this modal is application-modal, so leaving
            // it open freezes the workbench and hangs every call behind it, including the one this
            // window was opened for. Cancelling writes nothing on either side; the worst case is
            // that an update we do not own has to be retried, which beats a stuck workbench.
            Activator.logInfo("Cancelling an infobase-changed-outside-EDT dialog that is not " //$NON-NLS-1$
                + "attributable to an armed update: '" + safeShellText(shell) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            // Recorded only for windows that could not name an infobase either. A NAMED window
            // must NOT see it: its own update may well be applying normally, and marking it would
            // fail a call over somebody else's dialog. The consequence is accepted: when the
            // unreadable dialog WAS ours, that call falls back to the generic out-of-sync error.
            noteCancel(null, CANCEL_REASON_NOT_ATTRIBUTED);
            shell.close();
            return;
        }
        Set<String> labels = conflictButtonLabels(effective);
        Button button = labels == null ? null : findButtonByLabel(shell, 0, labels);
        if (chooseConflictAction(effective, button != null) == ConfirmAction.PRESS_POLICY_BUTTON)
        {
            Activator.logInfo("Auto-resolving infobase-changed-outside-EDT dialog '" //$NON-NLS-1$
                + safeShellText(shell) + "' via button '" + safeText(button) + "' (policy " //$NON-NLS-1$ //$NON-NLS-2$
                + effective.wireValue() + ")"); //$NON-NLS-1$
            pressButton(button);
            return;
        }
        // The reason must match what actually happened, because the caller turns it into advice:
        // a cancel that came from an arm which could not name its infobase (attributedName == null)
        // was DEGRADED here, so "re-run with override" would be wrong - re-running would degrade
        // again. Such a cancel is reported as not-attributed.
        String cancelReason;
        if (labels != null && button == null)
        {
            cancelReason = CANCEL_REASON_BUTTON_NOT_FOUND;
        }
        else if (attributedName == null)
        {
            cancelReason = CANCEL_REASON_NOT_ATTRIBUTED;
        }
        else
        {
            cancelReason = CANCEL_REASON_POLICY;
        }
        noteCancel(attributedName, cancelReason);
        Activator.logInfo("Cancelling infobase-changed-outside-EDT dialog '" //$NON-NLS-1$
            + safeShellText(shell) + "' (policy " //$NON-NLS-1$
            + (effective == null ? "none" : effective.wireValue()) //$NON-NLS-1$
            + (effective == null ? ", not attributable to an armed update" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + (labels != null && button == null ? ", button label not found" : "") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        shell.close();
    }

    /**
     * Completes EDT's standalone-server port-conflict modal by CANCELLING it, and records the
     * busy-port detail it carried so the failing call can explain itself.
     *
     * <p>The dialog offers exactly two answers, and only one of them is safe unattended:
     * <ul>
     *   <li><b>"Find free port"</b> (the DEFAULT button) makes EDT choose other ports and
     *       REWRITE the server's {@code config.yaml}. That silently moves the server's address
     *       — every client, published URL and bookmark against it then points at nothing — so
     *       a call that was asked to update a database must not do it behind the caller's
     *       back;</li>
     *   <li><b>"Cancel"</b> writes nothing. EDT then fails the server start with its
     *       "User has cancelled operation." status, which reaches the tool as a bare
     *       cancellation — hence the detail recorded here, without which the caller would be
     *       told only that something was cancelled.</li>
     * </ul>
     * Pressing the Cancel button BY LABEL rather than closing the shell keeps the answer
     * correct if EDT reorders the button bar (the prompter maps a closed shell to whatever
     * sits at index 1); {@link Shell#close()} stays the fallback when no labelled Cancel is
     * found, so the call still cannot hang.
     *
     * <p>Runs on the UI thread; never throws onto it.
     */
    private static void answerPortConflictDialog(Shell shell)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            String detail = summarizePortConflictText(collectDialogText(shell));
            if (reassignRequested() && pressReassignButton(shell, detail))
            {
                return;
            }
            notePortConflict(detail);
            Button cancel = findButtonByLabel(shell, 0, PORT_CONFLICT_CANCEL_BUTTONS);
            Activator.logError("Standalone server port conflict during an unattended MCP call: " //$NON-NLS-1$
                + (detail == null ? "<the dialog carried no readable detail>" : detail) //$NON-NLS-1$
                + " — cancelling EDT's port-conflict dialog (its default 'Find free port' " //$NON-NLS-1$
                + "would rewrite the server configuration; pass " //$NON-NLS-1$
                + "standaloneServerPortConflict='reassign' to allow that)", null); //$NON-NLS-1$
            if (cancel != null)
            {
                pressButton(cancel);
                return;
            }
            shell.close();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to answer the standalone-server port-conflict dialog", e); //$NON-NLS-1$
        }
    }

    /**
     * Whether the port conflict may be answered with "Find free port": every outstanding arm
     * asked for {@link StandaloneServerPortConflictPolicy#REASSIGN}. Unanimity, because the
     * press rewrites the SERVER's configuration for every user of that server and the dialog
     * carries nothing that could attribute it to one call.
     */
    private static boolean reassignRequested()
    {
        synchronized (LOCK)
        {
            return portConflictArmCount > 0 && portConflictReassignArmCount == portConflictArmCount;
        }
    }

    /**
     * Presses the port-conflict modal's "Find free port" button, located BY LABEL.
     *
     * @param shell the dialog shell
     * @param detail the busy-port summary, recorded so the caller can report the re-address
     * @return {@code true} when the button was found and pressed; {@code false} when it was not,
     *         so the caller falls back to cancelling (the button is the dialog's DEFAULT, and a
     *         configuration rewrite must never happen through a blind default press)
     */
    private static boolean pressReassignButton(Shell shell, String detail)
    {
        Button reassign = findButtonByLabel(shell, 0, PORT_CONFLICT_REASSIGN_BUTTONS);
        if (reassign == null)
        {
            Activator.logError("Standalone server port conflict: standaloneServerPortConflict=" //$NON-NLS-1$
                + "reassign was requested but the 'Find free port' button was not found by " //$NON-NLS-1$
                + "label (an EDT build or locale this plugin does not know) — cancelling " //$NON-NLS-1$
                + "instead of pressing the dialog's default button blind", null); //$NON-NLS-1$
            return false;
        }
        notePortReassign(detail);
        Activator.logInfo("Standalone server port conflict: moving the server to free ports on " //$NON-NLS-1$
            + "the caller's request (standaloneServerPortConflict=reassign) — EDT rewrites the " //$NON-NLS-1$
            + "server configuration. Conflict was: " //$NON-NLS-1$
            + (detail == null ? "<no readable detail>" : detail)); //$NON-NLS-1$
        pressButton(reassign);
        return true;
    }

    /**
     * Reduces the port-conflict dialog's collected text to one bounded line — EDT renders the
     * header and one "- &lt;port&gt; - &lt;role&gt;" line per busy port, and the caller puts the
     * result inside a single error message.
     *
     * <p>Pure (test seam) and deliberately NOT a parser: the port lines are localized and
     * formatted by EDT, so the text is normalized, not interpreted — nothing here can claim a
     * port number the dialog did not state.
     *
     * @param dialogText the collected dialog text (may be {@code null})
     * @return the one-line summary, or {@code null} when there was nothing readable
     */
    static String summarizePortConflictText(String dialogText)
    {
        if (dialogText == null)
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean previousWasBullet = false;
        for (String rawLine : dialogText.split("\n")) //$NON-NLS-1$
        {
            String line = rawLine.trim();
            if (line.isEmpty() || isPortConflictTitle(line))
            {
                continue;
            }
            // EDT renders one "- <port> - <role>" bullet per busy port. Flattened to a single
            // line the bullet dashes read as sentence dashes, so the leading marker is dropped
            // and consecutive bullets are separated by "; " — the ports stay countable inside
            // one line of an error message.
            boolean bullet = line.startsWith("-"); //$NON-NLS-1$
            if (bullet)
            {
                line = line.substring(1).trim();
                if (line.isEmpty())
                {
                    continue;
                }
            }
            if (sb.length() > 0)
            {
                sb.append(previousWasBullet && bullet ? "; " : " "); //$NON-NLS-1$ //$NON-NLS-2$
            }
            previousWasBullet = bullet;
            sb.append(line);
            if (sb.length() >= MAX_PORT_CONFLICT_DETAIL_CHARS)
            {
                sb.setLength(MAX_PORT_CONFLICT_DETAIL_CHARS);
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Records an auto-answered "Find free port" into every open window, so the caller can report
     * that its operation succeeded ONLY because EDT re-addressed the server. Distinct from
     * {@link #notePortConflict}: nothing failed here, but the stand changed.
     */
    private static void notePortReassign(String detail)
    {
        synchronized (LOCK)
        {
            for (ConflictWatch watch : CONFLICT_WATCHES)
            {
                watch.recordPortReassign(detail);
            }
        }
    }

    /** Records an auto-cancelled port conflict into every open window (see {@link ConflictWatch}). */
    private static void notePortConflict(String detail)
    {
        synchronized (LOCK)
        {
            for (ConflictWatch watch : CONFLICT_WATCHES)
            {
                // Recorded into EVERY open window, unlike the infobase-attributed conflict cancel:
                // this modal names the SERVER, not the infobase, so it cannot be attributed to one
                // caller — and a busy port is a machine-wide condition that fails every operation
                // starting that server anyway. The detail is only ever consumed by a call that
                // ALSO failed, and it names the server, so a report stays self-describing.
                watch.recordPortConflict(detail);
            }
        }
    }

    /**
     * The actionable failure message for an operation that could not proceed because EDT's
     * standalone-server port-conflict dialog was auto-cancelled. Shared by {@code update_database}
     * and the pre-launch DB update so both explain the same condition in the same words.
     *
     * <p>Without it the caller sees only what EDT reports for a cancelled server start —
     * "User has cancelled operation." — which names neither the port conflict nor anything the
     * caller could do about it.
     *
     * @param detail the busy-port summary from the dialog (may be {@code null})
     * @return the message, never {@code null}
     */
    public static String portConflictError(String detail)
    {
        return "the standalone server could not start because its network ports are already " //$NON-NLS-1$
            + "in use" //$NON-NLS-1$
            + (detail == null ? "." : ": " + detail) //$NON-NLS-1$ //$NON-NLS-2$
            + " EDT offered to move the server to free ports; this call declined, because that " //$NON-NLS-1$
            + "rewrites the server configuration and changes the address its clients connect " //$NON-NLS-1$
            + "to. Free those ports and retry — most often the holder is an ibsrv process left " //$NON-NLS-1$
            + "over from an earlier EDT session (stop it, or stop the server in EDT's Servers " //$NON-NLS-1$
            + "view). To let EDT move the server instead, re-call with " //$NON-NLS-1$
            + "standaloneServerPortConflict='reassign'."; //$NON-NLS-1$
    }

    /**
     * Test seam: records an auto-cancelled port conflict exactly as the UI-thread press path
     * does, so the resulting contract can be asserted headlessly (no SWT shell required).
     *
     * @param detail the busy-port summary (may be {@code null})
     */
    static void recordPortConflictForTest(String detail)
    {
        notePortConflict(detail);
    }

    /**
     * Test seam: records an auto-answered "Find free port" exactly as the UI-thread press path
     * does, so the resulting contract can be asserted headlessly.
     *
     * @param detail the busy-port summary (may be {@code null})
     */
    static void recordPortReassignForTest(String detail)
    {
        notePortReassign(detail);
    }

    /**
     * Opens a window that records the conflict modals CANCELLED while a single update runs.
     * Pair it with {@link ConflictWatch#close()} (try-with-resources) around the update AND the
     * check that consumes it.
     *
     * <p>{@code infobaseName} is the infobase the caller is updating: a cancelled dialog
     * attributed to that infobase lands in this window. A caller that could not resolve a name
     * passes {@code null} and receives the cancels that could not be attributed either.
     *
     * <p>Windows are keyed by INFOBASE, not by call: two updates of the SAME infobase running at
     * once both see the cancel. That is the honest reading - the divergence neither of them
     * resolved affects both - but it does mean the window is not a per-call token.
     *
     * @param infobaseName the infobase being updated (may be {@code null})
     * @return the open window, never {@code null}
     */
    public static ConflictWatch beginConflictWatch(String infobaseName)
    {
        ConflictWatch watch = new ConflictWatch(trimToNull(infobaseName));
        synchronized (LOCK)
        {
            CONFLICT_WATCHES.add(watch);
        }
        return watch;
    }

    /**
     * Records a cancelled conflict dialog into the open windows it belongs to: the ones naming
     * {@code attributedName}, or — when the cancel itself could not be attributed — the ones that
     * could not name an infobase either.
     */
    private static void noteCancel(String attributedName, String reason)
    {
        synchronized (LOCK)
        {
            for (ConflictWatch watch : CONFLICT_WATCHES)
            {
                // A cancel lands in a window only when it is demonstrably about that window's
                // infobase, or when NEITHER could be named (then the two are as related as anything
                // here can be). It is deliberately NOT handed to "the only open window": callers
                // treat a cancel in their window as a failure, so guessing an owner would fail a
                // call whose own update actually applied.
                boolean mine = attributedName == null
                    ? watch.infobaseName == null
                    : attributedName.equals(watch.infobaseName);
                if (mine)
                {
                    watch.record(reason);
                }
            }
        }
    }

    /**
     * A conflict-cancel window opened around one update: how many conflict modals attributable to
     * ITS infobase were cancelled while it was open, and why the last of them was. Two updates of
     * the same infobase running at once therefore both see the cancel — the divergence neither of
     * them resolved concerns both. Closing the window removes it from the filter's bookkeeping —
     * always close it (try-with-resources).
     */
    public static final class ConflictWatch implements AutoCloseable
    {
        private final String infobaseName;
        private int cancels;
        private String reason;
        private boolean portConflict;
        private String portConflictDetail;
        private boolean portsReassigned;
        private String portReassignDetail;

        ConflictWatch(String infobaseName)
        {
            this.infobaseName = infobaseName;
        }

        void record(String cancelReason)
        {
            cancels++;
            reason = cancelReason;
        }

        /**
         * Records that a standalone-server port-conflict modal was auto-cancelled while this
         * window was open. The flag is kept separate from {@link #record}: that one means "the
         * caller's own data question was declined", this one means "the server never started",
         * and only the second explains a cancellation nobody asked for.
         */
        void recordPortConflict(String detail)
        {
            portConflict = true;
            if (detail != null && portConflictDetail == null)
            {
                portConflictDetail = detail;
            }
        }

        /**
         * Was EDT's standalone-server port-conflict modal auto-cancelled while this window was
         * open — i.e. did the operation fail because the server could not start?
         *
         * @return {@code true} when at least one port conflict was recorded
         */
        public boolean portConflicted()
        {
            synchronized (LOCK)
            {
                return portConflict;
            }
        }

        /**
         * The busy-port summary read from that modal, for the caller's error message.
         *
         * @return the detail, or {@code null} when none was recorded or it was unreadable
         *         (check {@link #portConflicted()} for the fact itself)
         */
        public String portConflictDetail()
        {
            synchronized (LOCK)
            {
                return portConflictDetail;
            }
        }

        /**
         * Records that the port conflict was answered with "Find free port" — the operation
         * proceeds, but EDT rewrote the server's port configuration to make it possible.
         */
        void recordPortReassign(String detail)
        {
            portsReassigned = true;
            if (detail != null && portReassignDetail == null)
            {
                portReassignDetail = detail;
            }
        }

        /**
         * Did this operation only get through because EDT moved the standalone server to free
         * ports (the caller passed {@code standaloneServerPortConflict=reassign})? Not a
         * failure — but the stand changed, so the caller must say so.
         *
         * @return {@code true} when a re-address was performed while this window was open
         */
        public boolean portsReassigned()
        {
            synchronized (LOCK)
            {
                return portsReassigned;
            }
        }

        /**
         * The busy-port summary of the conflict that triggered the re-address.
         *
         * @return the detail, or {@code null} when none was readable
         */
        public String portReassignDetail()
        {
            synchronized (LOCK)
            {
                return portReassignDetail;
            }
        }

        /**
         * Was a conflict modal cancelled while this window was open?
         *
         * @return {@code true} when at least one cancel was recorded
         */
        public boolean cancelled()
        {
            synchronized (LOCK)
            {
                return cancels > 0;
            }
        }

        /**
         * Why the last cancel in this window happened — one of the {@code CANCEL_REASON_*}
         * constants; only meaningful when {@link #cancelled()} is {@code true}.
         *
         * @return the reason token, or {@code null} when nothing was cancelled
         */
        public String reason()
        {
            synchronized (LOCK)
            {
                return reason;
            }
        }

        @Override
        public void close()
        {
            synchronized (LOCK)
            {
                CONFLICT_WATCHES.remove(this);
            }
        }
    }

    /**
     * Test seam: records a cancel exactly as the UI-thread press path does, so the resulting
     * contract can be asserted headlessly (no SWT shell required).
     *
     * @param reason one of the {@code CANCEL_REASON_*} constants
     * @param infobaseName the infobase the cancelled dialog was attributed to (may be {@code null})
     */
    static void recordConflictCancelForTest(String reason, String infobaseName)
    {
        noteCancel(trimToNull(infobaseName), reason);
    }

    /** Fires {@code SWT.Selection} on the button — mirrors a user click. */
    private static void pressButton(Button button)
    {
        Event event = new Event();
        event.widget = button;
        // Mirrors a user click: JFace dialog buttons fire buttonPressed() on
        // SWT.Selection, which sets the return code and closes the dialog.
        button.notifyListeners(SWT.Selection, event);
    }

    private static String safeText(Button button)
    {
        try
        {
            return button.getText();
        }
        catch (RuntimeException e)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    private static String safeShellText(Shell shell)
    {
        try
        {
            return shell.getText();
        }
        catch (RuntimeException e)
        {
            return "<unknown>"; //$NON-NLS-1$
        }
    }

    /**
     * Returns the workbench {@link Display} or {@code null} when no workbench is
     * running (headless CI / EDT CLI), via
     * {@link LaunchLifecycleUtils#workbenchDisplayOrNull()} — the probe NEVER
     * creates a display.
     *
     * <p>The previous {@code Display.getDefault()} probe did exactly that on the
     * headless synchronous launch path: the first {@link #arm()} created a stray
     * display owned by its MCP worker thread, and a later {@code arm()} /
     * {@code disarm()} from a different worker would then {@code syncExec} onto
     * that never-pumped display and hang forever.
     */
    private static Display safeDisplay()
    {
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        return display != null && !display.isDisposed() ? display : null;
    }
}
