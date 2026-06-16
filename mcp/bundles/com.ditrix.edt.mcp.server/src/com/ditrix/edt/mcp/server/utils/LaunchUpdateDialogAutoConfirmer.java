/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>The filter is installed only between an {@code arm} and its paired
 *       {@code disarm} (use try/finally around the single {@code launch()} call),
 *       so manual EDT launches outside an MCP tool still prompt normally.</li>
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

    /** Cap on the widget-tree walk depth when reading a dialog's message body. */
    private static final int MAX_BODY_SCAN_DEPTH = 6;

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
        if (!updateDialog && !sessionDialog && !restructureDialog)
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
        if (!updateDialog && !sessionDialog && !restructureDialog)
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
            boolean anyArmed = updateArmCount > 0 || sessionArmCount > 0 || restructureArmCount > 0;
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
            // Snapshot which matchers are armed RIGHT NOW (the counts can change
            // between events) so each branch fires only for an armed matcher.
            boolean updateArmed;
            boolean sessionArmed;
            boolean restructureArmed;
            synchronized (LOCK)
            {
                updateArmed = updateArmCount > 0;
                sessionArmed = sessionArmCount > 0;
                restructureArmed = restructureArmCount > 0;
            }
            // The body is only read (a widget-tree walk) when the title did not already
            // match an armed TITLE matcher (update or restructure) AND the session
            // matcher is armed — otherwise it is needless work.
            boolean titleMatched =
                (updateArmed && isTargetTitle(title)) || (restructureArmed && isRestructureTitle(title));
            boolean needBody = sessionArmed && !titleMatched;
            String body = needBody ? readDialogBody(shell) : null;
            if (!shouldAutoConfirm(updateArmed, sessionArmed, restructureArmed, title, body))
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
        if (updateArmed && isTargetTitle(title))
        {
            return true;
        }
        if (restructureArmed && isRestructureTitle(title))
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
        // This control's own label-like text: a 1003-prefix match short-circuits;
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
        PRESS_DEFAULT_BUTTON;
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
        if (control == null || control.isDisposed() || depth > MAX_BODY_SCAN_DEPTH)
        {
            return null;
        }
        if (control instanceof Button)
        {
            Button b = (Button)control;
            try
            {
                if (isKeepExistingLabel(b.getText()))
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
                Button found = findButtonByLabel(child, depth + 1);
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
        if (label == null)
        {
            return false;
        }
        String normalized = label.replace("&", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        return DEBUG_SESSION_KEEP_BUTTONS.contains(normalized);
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
