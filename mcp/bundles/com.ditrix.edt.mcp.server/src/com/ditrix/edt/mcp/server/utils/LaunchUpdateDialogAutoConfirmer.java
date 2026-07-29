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
     * Which button answers the conflict dialog whose message is {@code dialogBody}, given the
     * arms outstanding right now? See {@link #choosePolicyFor(String, List)}.
     *
     * @param dialogBody the dialog message text (may be {@code null})
     * @return the policy to apply, or {@code null} when the dialog must be cancelled
     */
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
     *       delegate-performed update) attribution is impossible, so the unnamed arms decide,
     *       with the same one-policy/ambiguity rule. That is the pre-attribution behaviour.</li>
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
     * @param conflictPolicy the button to press on the external-changes conflict modal, or
     *            {@code null} to leave that modal alone
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
     * @param infobaseName the infobase this update targets, as EDT names it (may be
     *            {@code null}/blank when it cannot be resolved — the conflict modal is then
     *            only ever CANCELLED, never pressed)
     */
    public static void arm(boolean updateDialog, boolean sessionDialog, boolean restructureDialog,
        ExternalInfobaseChangesPolicy conflictPolicy, String infobaseName)
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
            if (conflictPolicy != null)
            {
                CONFLICT_ARMS.add(new ConflictArm(trimToNull(infobaseName), conflictPolicy));
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
            if (conflictPolicy != null)
            {
                CONFLICT_ARMS.remove(new ConflictArm(trimToNull(infobaseName), conflictPolicy));
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
                || !CONFLICT_ARMS.isEmpty();
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
            // The conflict matcher is armed per policy; a null choice means "not armed".
            boolean conflictArmed = conflictMatcherArmed();
            // The body is only read (a widget-tree walk) when the title did not already
            // match an armed TITLE matcher (update, restructure or conflict) AND the
            // session matcher is armed — otherwise it is needless work.
            boolean titleMatched = (updateArmed && isTargetTitle(title))
                || (restructureArmed && isRestructureTitle(title))
                || (conflictArmed && isConflictTitle(title));
            boolean needBody = sessionArmed && !titleMatched;
            String body = needBody ? readDialogBody(shell) : null;
            if (!shouldAutoConfirm(updateArmed, sessionArmed, restructureArmed, conflictArmed, title,
                body))
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
     *   <li>{@link ExternalInfobaseChangesPolicy#CANCEL} (or a {@code null} policy) →
     *       {@link ConfirmAction#CANCEL_DIALOG}: nothing is written on either side and the
     *       update call fails with an actionable error instead of hanging;</li>
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
     * @param policy the policy selected by the arming call (may be {@code null})
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
        // NB: a null policy here means the dialog was NOT attributed to an armed update —
        // cancelling is then the only safe completion.
        noteCancel(attributedName,
            labels != null && button == null ? CANCEL_REASON_BUTTON_NOT_FOUND : CANCEL_REASON_POLICY);
        Activator.logInfo("Cancelling infobase-changed-outside-EDT dialog '" //$NON-NLS-1$
            + safeShellText(shell) + "' (policy " //$NON-NLS-1$
            + (effective == null ? "none" : effective.wireValue()) //$NON-NLS-1$
            + (effective == null ? ", not attributable to an armed update" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + (labels != null && button == null ? ", button label not found" : "") + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        shell.close();
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
