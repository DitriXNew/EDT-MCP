/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.ui.DestructiveConsentDialog;

/**
 * The single decision point that asks the human for consent before a destructive
 * MCP write. Every gated tool calls {@link #requireConsent(String, ConsentPreview)}
 * at its confirm-point, AFTER it has computed its preview data and BEFORE the actual
 * mutation. On {@link ConsentDecision#REJECT} the caller returns an error and mutates
 * NOTHING; on {@link ConsentDecision#ALLOW} the behaviour is byte-identical to
 * before the gate existed.
 *
 * <p><b>Decision order</b> (each step reuses an existing plugin idiom):
 * <ol>
 *   <li><b>env {@code EDT_MCP_DESTRUCTIVE_CONSENT}</b> ({@code allow}/{@code ask},
 *       case-insensitive, read like {@code Toolsets.ENV_PROGRESSIVE_DISCLOSURE}) —
 *       {@code allow} WINS and returns {@link ConsentDecision#ALLOW} without any UI.
 *       This is the automated-run bypass the destructive e2e suite relies on.</li>
 *   <li><b>Headless</b>: if there is no live workbench display or no active shell
 *       (via {@link LaunchLifecycleUtils#workbenchDisplayOrNull()} /
 *       {@link LaunchLifecycleUtils#grabActiveShell()}) → {@link ConsentDecision#ALLOW}
 *       plus a logged info line. NEVER {@code syncExec} against a null/disposed
 *       display; NEVER block.</li>
 *   <li><b>In-memory session-allow</b>: a per-tool {@link Set} populated by the
 *       dialog's "Allow for session" button. A gated tool the user allowed for the
 *       session this EDT run proceeds without a dialog.</li>
 *   <li><b>Preference level</b> via {@link ConsentSettingsService}:
 *       {@code ALLOW_ALL} → allow; {@code PER_TOOL} + the tool is in the allow-set →
 *       allow; otherwise ({@code ASK_ALWAYS}, or {@code PER_TOOL} + not allowed) →
 *       prompt.</li>
 *   <li><b>Dialog</b>: open {@link DestructiveConsentDialog}, time-bounded to
 *       {@link #CONSENT_PROMPT_TIMEOUT_SECONDS} on BOTH threading paths. Allow = OK,
 *       Reject = CANCEL, "Allow for session" adds the tool to the session set.
 *       Already on the UI thread ({@code Display.getCurrent() != null}) — the NORMAL
 *       path for the gated tools whose whole {@code execute()} is marshalled onto the
 *       UI thread before the gate is ever reached ({@code delete_metadata} /
 *       {@code modify_metadata} via {@code AbstractMetadataWriteTool}'s
 *       {@code syncExec}, {@code rename_metadata_object} via its own) — the dialog
 *       opens directly and a pre-scheduled {@link Display#timerExec} closer fires
 *       inside {@code open()}'s nested event loop to auto-close it on timeout
 *       ({@link #promptOnUiThread}). Otherwise (an MCP worker thread —
 *       {@code delete_project} / {@code delete_infobase} / {@code update_database}
 *       call the gate straight from the worker) the dialog is opened via
 *       {@code display.asyncExec} (NOT {@code syncExec}, so the UI thread is never
 *       blocked waiting on the worker) and the worker awaits a decision up to the
 *       timeout ({@link #promptWithTimeout}). Both paths race through the same
 *       decide-once {@link ConsentArbiter}: whichever side decides first wins; on a
 *       timeout win the verdict is {@link ConsentDecision#TIMEOUT} and the dialog
 *       shell is closed (best-effort) so it does not linger.</li>
 * </ol>
 *
 * <p><b>Invariant:</b> the gate NEVER blocks indefinitely in EITHER dialog path — it
 * waits at most {@link #CONSENT_PROMPT_TIMEOUT_SECONDS} (issue #277); it NEVER blocks
 * at all in a headless / env-bypass / non-ASK (level-2/session/per-tool-allowed)
 * path; it does not deadlock when already on the UI thread; and a non-
 * {@link ConsentDecision#ALLOW} verdict ({@link ConsentDecision#REJECT} or
 * {@link ConsentDecision#TIMEOUT}) mutates nothing (it only returns the decision, and
 * the caller turns it into an error via {@link #consentDeniedMessage(ConsentDecision, String)}).
 */
public final class DestructiveConsentGate // NOSONAR intentional singleton (Eclipse service / getInstance); a single instance is by design
{
    /**
     * Environment variable that overrides the consent gate for automated runs.
     * When set to {@code allow} (case-insensitive) the gate short-circuits to
     * {@link ConsentDecision#ALLOW} without any UI — the bypass the destructive e2e
     * suite sets on the EDT launch. Any other value (including {@code ask}) leaves
     * the normal decision order in effect. Mirrors {@code Toolsets.ENV_PROGRESSIVE_DISCLOSURE}.
     */
    static final String ENV_DESTRUCTIVE_CONSENT = "EDT_MCP_DESTRUCTIVE_CONSENT"; //$NON-NLS-1$

    /** The env value that forces allow. */
    private static final String ENV_VALUE_ALLOW = "allow"; //$NON-NLS-1$

    /**
     * How long the gate waits for a human to answer the consent dialog before
     * auto-rejecting with {@link ConsentDecision#TIMEOUT} — on both prompt paths (the
     * worker's latch await in {@link #promptWithTimeout} and the {@code timerExec}
     * closer in {@link #promptOnUiThread}). Chosen below common MCP client request
     * budgets (many default around 300 s) so the caller gets an actionable timeout
     * error instead of its OWN transport timing out first, while still long enough
     * for a human actually at the keyboard to read the preview and click a button.
     * See issue #277: before this bound, an unanswered dialog blocked the MCP call
     * indefinitely.
     */
    static final int CONSENT_PROMPT_TIMEOUT_SECONDS = 120;

    /**
     * Custom JFace button id for the dialog's "Allow for session" button (mirrors
     * {@code FilterByTagDialog}'s {@code TURN_OFF_ID = 1024}). Pressing it adds the
     * tool to {@link #sessionAllow} and closes the dialog with an ALLOW verdict.
     */
    public static final int ALLOW_FOR_SESSION_ID = 1024;

    /**
     * The frozen set of destructive tool NAMEs the gate protects: the five
     * always-destructive tools plus {@code modify_metadata} (gated only for a
     * type/composite-type change — the tool decides when to call, the gate does not).
     *
     * <p>Related to but deliberately NOT equal to
     * {@code ToolAnnotationClassifier.DESTRUCTIVE_TOOLS}: that MCP-hint list carries
     * {@code delete_launch_config} (which is cheap/recoverable and NOT gated) and
     * omits {@code modify_metadata} (whose destructiveness is conditional). The exact
     * relationship is asserted by {@code DestructiveConsentGateTest} so the two lists
     * never silently drift.
     */
    public static final Set<String> GATED_TOOLS = Set.of(
        "delete_metadata", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "delete_project", //$NON-NLS-1$
        "delete_infobase", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "modify_metadata" //$NON-NLS-1$
    );

    private static final DestructiveConsentGate INSTANCE = new DestructiveConsentGate();

    /**
     * Tool names the user chose "Allow for session" for. In-memory only — cleared on
     * EDT restart. A concurrent set: the gate is called from MCP worker threads.
     */
    private final Set<String> sessionAllow = ConcurrentHashMap.newKeySet();

    private DestructiveConsentGate()
    {
        // Singleton
    }

    /**
     * @return the singleton instance
     */
    public static DestructiveConsentGate getInstance()
    {
        return INSTANCE;
    }

    /** The final consent verdict returned to a gated tool. */
    public enum ConsentDecision
    {
        /** Proceed with the mutation. */
        ALLOW,
        /** The user declined — the caller returns an error and mutates nothing. */
        REJECT,
        /**
         * Nobody answered the dialog within
         * {@link DestructiveConsentGate#CONSENT_PROMPT_TIMEOUT_SECONDS} — a
         * REJECT-like verdict (the caller returns an error and mutates nothing) but
         * with its own actionable error text built by
         * {@link DestructiveConsentGate#consentDeniedMessage(ConsentDecision, String)},
         * distinct from a human's explicit {@link #REJECT}.
         */
        TIMEOUT
    }

    /**
     * Outcome of the pure, headless-testable decision core: either a final ALLOW, or
     * a signal that the caller must PROMPT the human (only reachable on a live UI
     * session). REJECT is never produced here — it can only come from the dialog.
     */
    enum Outcome
    {
        /** Allow without a dialog. */
        ALLOW,
        /** A dialog must be shown (live-UI path only). */
        PROMPT
    }

    /**
     * Asks for consent to run the given destructive tool, following the decision
     * order documented on this class. Safe to call for any tool: an ungated tool name
     * is not expected here (callers gate only {@link #GATED_TOOLS}), but the method
     * itself never inspects membership — it applies the same policy to whatever name
     * it is given.
     *
     * @param toolName the gated tool's name (e.g. {@code "delete_metadata"})
     * @param preview the compact preview to render if a dialog is shown; may be
     *            {@code null} (the dialog then shows only the tool name)
     * @return {@link ConsentDecision#ALLOW} to proceed, {@link ConsentDecision#REJECT}
     *         to abort
     */
    public ConsentDecision requireConsent(String toolName, ConsentPreview preview)
    {
        // Step 1 — env bypass WINS (the automated-run / e2e path).
        if (isEnvAllow())
        {
            return ConsentDecision.ALLOW;
        }

        // Step 2 — headless probe: never syncExec / block without a live display+shell.
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        Shell shell = display != null ? LaunchLifecycleUtils.grabActiveShell() : null;
        if (display == null || display.isDisposed() || shell == null)
        {
            Activator.logInfo("Destructive-consent gate: no active UI session — allowing '" //$NON-NLS-1$
                + toolName + "' without a prompt (headless/unattended)."); //$NON-NLS-1$
            return ConsentDecision.ALLOW;
        }

        // Steps 3-5 — pure decision from the resolved sources.
        ConsentSettingsService settings = ConsentSettingsService.getInstance();
        Outcome outcome = decide(sessionAllow.contains(toolName), settings.getLevel(),
            settings.isToolAllowed(toolName));
        if (outcome == Outcome.ALLOW)
        {
            return ConsentDecision.ALLOW;
        }

        // Step 6 — prompt on a live UI session (the SWT seam).
        return promptForConsent(toolName, preview, display, shell);
    }

    /**
     * Builds the {@link ToolResult#error(String)} text for a non-{@link ConsentDecision#ALLOW}
     * verdict from {@link #requireConsent}. Called at each gated tool's confirm-point in
     * place of the previously-inlined literal, so both texts live in one place:
     * {@link ConsentDecision#REJECT} keeps the original, unchanged
     * {@code "Operation declined by user"} text; {@link ConsentDecision#TIMEOUT} gets
     * its own actionable text naming the tool, the {@link #CONSENT_PROMPT_TIMEOUT_SECONDS}
     * budget and the three remedies (allow via Preferences, the
     * {@link #ENV_DESTRUCTIVE_CONSENT} launch bypass, or re-running and answering
     * promptly) so an unattended caller can self-diagnose instead of retrying blind
     * against the same wall.
     *
     * @param decision the verdict from {@link #requireConsent}; must not be
     *            {@link ConsentDecision#ALLOW}
     * @param toolName the gated tool's name, echoed into the {@code TIMEOUT} text
     * @return the ready {@link ToolResult#error(String)} message text
     */
    public static String consentDeniedMessage(ConsentDecision decision, String toolName)
    {
        if (decision == ConsentDecision.TIMEOUT)
        {
            return "Destructive operation '" + toolName + "' was not confirmed within " //$NON-NLS-1$ //$NON-NLS-2$
                + CONSENT_PROMPT_TIMEOUT_SECONDS + " s at the EDT workbench, so it was auto-rejected " //$NON-NLS-1$
                + "and nothing was changed. To proceed: (1) Preferences -> MCP Server -> Destructive " //$NON-NLS-1$
                + "operations, set 'Allow all' or allow '" + toolName + "' per-tool; (2) set " //$NON-NLS-1$ //$NON-NLS-2$
                + ENV_DESTRUCTIVE_CONSENT + "=allow on the EDT process at launch for unattended runs; " //$NON-NLS-1$
                + "or (3) re-run the call and answer the confirmation dialog promptly."; //$NON-NLS-1$
        }
        return "Operation declined by user"; //$NON-NLS-1$
    }

    /**
     * The pure decision core (steps 3-5): given the already-resolved session-allow
     * flag, the preference {@link ConsentSettingsService.Level} and the per-tool
     * allow flag, decides whether to allow outright or to prompt. Contains NO SWT and
     * NO service lookups, so the whole decision table is unit-testable headlessly.
     *
     * @param sessionAllowed whether the tool is in the in-memory session-allow set
     * @param level the current preference consent level (never {@code null})
     * @param perToolAllowed whether the per-tool allow-set contains the tool
     * @return {@link Outcome#ALLOW} to proceed without a dialog, {@link Outcome#PROMPT}
     *         to show the dialog
     */
    static Outcome decide(boolean sessionAllowed, ConsentSettingsService.Level level,
        boolean perToolAllowed)
    {
        // Step 3 — session-allow (the "Allow for session" button).
        if (sessionAllowed)
        {
            return Outcome.ALLOW;
        }
        // Step 4/5 — preference level.
        if (level == ConsentSettingsService.Level.ALLOW_ALL)
        {
            return Outcome.ALLOW;
        }
        if (level == ConsentSettingsService.Level.PER_TOOL && perToolAllowed)
        {
            return Outcome.ALLOW;
        }
        // ASK_ALWAYS, or PER_TOOL without an allow entry → prompt.
        return Outcome.PROMPT;
    }

    /**
     * Reads the {@link #ENV_DESTRUCTIVE_CONSENT} environment variable and reports
     * whether it forces allow. Delegates to the pure {@link #envForcesAllow(String)}
     * so the classification is unit-testable without touching the process environment.
     */
    static boolean isEnvAllow()
    {
        return envForcesAllow(System.getenv(ENV_DESTRUCTIVE_CONSENT));
    }

    /**
     * Pure classifier for the {@link #ENV_DESTRUCTIVE_CONSENT} value: {@code allow}
     * (case-insensitive, trimmed) forces allow; any other value — including
     * {@code ask}, blank or {@code null} — leaves the normal decision order in effect.
     *
     * @param rawEnvValue the raw environment value (may be {@code null})
     * @return {@code true} iff the value forces the allow bypass
     */
    static boolean envForcesAllow(String rawEnvValue)
    {
        return rawEnvValue != null && ENV_VALUE_ALLOW.equalsIgnoreCase(rawEnvValue.trim());
    }

    /**
     * Opens {@link DestructiveConsentDialog} on the live UI session, time-bounded on
     * BOTH threading paths (issue #277):
     * <ul>
     *   <li><b>already on the UI thread</b> — the NORMAL path for
     *       {@code delete_metadata}, {@code modify_metadata} and
     *       {@code rename_metadata_object}, whose whole {@code execute()} is
     *       marshalled onto the UI thread ({@code AbstractMetadataWriteTool}'s
     *       {@code syncExec} / {@code RenameMetadataObjectTool}'s own) before the
     *       gate is ever reached, NOT just a safety net — which is exactly why it
     *       needs its own bound: there is no separate worker thread to time out
     *       against, so {@link #promptOnUiThread} bounds it with a
     *       {@link Display#timerExec} closer instead;</li>
     *   <li><b>a worker thread</b> — {@code delete_project} / {@code delete_infobase}
     *       / {@code update_database} call the gate straight from the MCP worker —
     *       bounded by {@link #promptWithTimeout}'s latch await.</li>
     * </ul>
     */
    private ConsentDecision promptForConsent(String toolName, ConsentPreview preview, Display display,
        Shell shell)
    {
        if (Display.getCurrent() != null)
        {
            return promptOnUiThread(toolName, preview, display, shell);
        }

        // Shutdown race: the display was live when requireConsent checked it, but the workbench can
        // close before asyncExec runs. A disposed display makes asyncExec throw SWTException; rather
        // than fail a destructive tool with that, fall back to the documented headless ALLOW (mirrors
        // the neighbouring SWT auto-confirmer). See #222 review.
        if (display.isDisposed())
        {
            return allowOnDisposedDisplay(toolName);
        }
        return promptWithTimeout(toolName, preview, display, shell);
    }

    /**
     * The bounded UI-thread prompt (issue #277): schedules a {@link Display#timerExec}
     * closer for {@link #CONSENT_PROMPT_TIMEOUT_SECONDS} BEFORE opening the dialog,
     * then opens it and blocks in {@code open()}'s nested {@code readAndDispatch}
     * loop. That nested loop DOES dispatch {@code timerExec} runnables, so the closer
     * fires INSIDE {@code open()}: it publishes {@link ConsentDecision#TIMEOUT}
     * through the decide-once {@link ConsentArbiter} (a no-op if the human already
     * answered) and closes the dialog shell, which unblocks {@code open()}.
     *
     * <p>NB: a forced {@code Dialog.close()} does NOT set a CANCEL return code —
     * JFace {@code Window.returnCode} defaults to {@code OK} — so the post-open code
     * must NOT trust the return code alone. It maps the outcome through the arbiter
     * instead: {@link #recordDialogAnswer} submits the code-derived candidate, which
     * LOSES to an already-published TIMEOUT (and still records a late "Allow for
     * session"); the arbiter's decision is what is returned.
     *
     * <p>When the human answers first, the still-pending closer is cancelled by
     * re-scheduling it with a negative delay ({@code timerExec(-1, closer)} — SWT's
     * documented cancel idiom). If the closer already fired, that call is a harmless
     * no-op, and the arbiter guard makes a late closer harmless in every other
     * interleaving too — the cancel is an optimisation, not a correctness need.
     */
    private ConsentDecision promptOnUiThread(String toolName, ConsentPreview preview, Display display,
        Shell shell)
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        DestructiveConsentDialog dialog = new DestructiveConsentDialog(shell, toolName, preview);
        Runnable closer = () -> {
            if (arbiter.tryDecide(ConsentDecision.TIMEOUT))
            {
                logTimeoutWin(toolName);
                closeOnUiThread(dialog);
            }
        };
        display.timerExec((int)TimeUnit.SECONDS.toMillis(CONSENT_PROMPT_TIMEOUT_SECONDS), closer);
        int code = dialog.open();
        // Cancel the closer if it is still pending (negative delay = SWT's cancel idiom);
        // harmless when it already fired or the display is shutting down.
        try
        {
            display.timerExec(-1, closer);
        }
        catch (SWTException e) // NOSONAR display disposed while the dialog was up -> nothing left to cancel
        {
            // No-op: the workbench is shutting down; the arbiter guard already covers the late closer.
        }
        recordDialogAnswer(arbiter, toolName, code);
        return arbiter.peek();
    }

    /**
     * The bounded-wait MCP-worker path (issue #277): opens {@link DestructiveConsentDialog}
     * via {@code display.asyncExec} (never {@code syncExec} — the UI thread must stay
     * free to keep pumping its event loop while this thread waits) and blocks the
     * CALLING (worker) thread on a {@link ConsentArbiter} for at most
     * {@link #CONSENT_PROMPT_TIMEOUT_SECONDS}. The dialog's eventual answer (UI thread,
     * inside the async runnable, mapped by {@link #recordDialogAnswer}) and this
     * method's timeout (worker thread) race to decide first via
     * {@link ConsentArbiter#tryDecide}; whichever side wins is final, the other is
     * ignored — EXCEPT "Allow for session", which {@link #recordDialogAnswer} records
     * into {@link #sessionAllow} unconditionally, even when the dialog answers after
     * the timeout already won (a human who eventually clicks it should not be
     * re-prompted on the next call). On a worker-side timeout win, the dialog shell is
     * closed best-effort so it does not linger answering nobody.
     */
    private ConsentDecision promptWithTimeout(String toolName, ConsentPreview preview, Display display,
        Shell shell)
    {
        ConsentArbiter arbiter = new ConsentArbiter();
        AtomicReference<DestructiveConsentDialog> dialogRef = new AtomicReference<>();
        Runnable openDialog = () -> {
            if (shell.isDisposed())
            {
                return;
            }
            DestructiveConsentDialog dialog = new DestructiveConsentDialog(shell, toolName, preview);
            // create() BEFORE open() so dialogRef is populated before open()'s blocking nested event
            // loop starts — a close request queued while that loop is pumping is what lets the
            // worker's timeout close the shell (see closeDialogIfOpen).
            dialog.create();
            dialogRef.set(dialog);
            int code = dialog.open();
            recordDialogAnswer(arbiter, toolName, code);
        };
        try
        {
            display.asyncExec(openDialog);
        }
        catch (SWTException e) // NOSONAR display disposed in the gap between the check above and this call -> allow (headless fallback)
        {
            return allowOnDisposedDisplay(toolName);
        }

        boolean decidedInTime;
        try
        {
            decidedInTime = arbiter.await(CONSENT_PROMPT_TIMEOUT_SECONDS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            decidedInTime = false;
        }
        if (decidedInTime)
        {
            return arbiter.peek();
        }

        // The wait elapsed with no answer recorded yet. Publish the timeout verdict; tryDecide is a
        // CAS, so if the dialog answers in this same instant exactly one side wins.
        if (arbiter.tryDecide(ConsentDecision.TIMEOUT))
        {
            logTimeoutWin(toolName);
            closeDialogIfOpen(display, dialogRef.get());
            return ConsentDecision.TIMEOUT;
        }
        // Lost the race: the dialog answered right as the wait elapsed.
        return arbiter.peek();
    }

    /**
     * The single post-dialog mapper for BOTH prompt paths (worker-thread
     * {@link #promptWithTimeout} and UI-thread {@link #promptOnUiThread}): maps the
     * JFace button {@code code} to a candidate decision and submits it to
     * {@code arbiter}. "Allow for session" is recorded into {@link #sessionAllow}
     * UNCONDITIONALLY — even when {@code arbiter.tryDecide} loses (the timeout
     * already won) — so a human who eventually clicks it is not re-prompted on the
     * next call. Takes no SWT types, so this exact behaviour (particularly the
     * unconditional session recording and the candidate losing to an
     * already-published TIMEOUT) is headlessly unit-testable without a live
     * {@link Display}.
     *
     * @param arbiter the race arbiter for this dialog invocation
     * @param toolName the gated tool's name, for logging/session-recording
     * @param code the JFace button id the dialog closed with (NB: after a forced
     *            timeout close this is JFace's default {@code OK} — the arbiter, not
     *            the code, carries the real verdict)
     */
    void recordDialogAnswer(ConsentArbiter arbiter, String toolName, int code)
    {
        boolean sessionButton = code == ALLOW_FOR_SESSION_ID;
        ConsentDecision candidate =
            sessionButton || code == IDialogConstants.OK_ID ? ConsentDecision.ALLOW : ConsentDecision.REJECT;
        if (sessionButton)
        {
            sessionAllow.add(toolName);
            Activator.logInfo("Destructive-consent gate: user allowed '" + toolName //$NON-NLS-1$
                + "' for the rest of this EDT session."); //$NON-NLS-1$
        }
        if (arbiter.tryDecide(candidate) && candidate == ConsentDecision.REJECT)
        {
            Activator.logInfo("Destructive-consent gate: user declined '" + toolName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Best-effort close of a timed-out dialog from the WORKER thread: asks the UI
     * thread (via {@code asyncExec}, never blocking the caller) to run
     * {@link #closeOnUiThread(DestructiveConsentDialog)}. Never throws — a workbench
     * shutdown racing this close is not a reason to fail the timeout verdict that
     * already won.
     *
     * @param display the workbench display
     * @param dialog the dialog instance captured by {@link #promptWithTimeout}, or
     *            {@code null} if the async open never ran
     */
    private static void closeDialogIfOpen(Display display, DestructiveConsentDialog dialog)
    {
        if (dialog == null)
        {
            return;
        }
        try
        {
            display.asyncExec(() -> closeOnUiThread(dialog));
        }
        catch (SWTException e) // NOSONAR display disposed meanwhile -> nothing left to close
        {
            // No-op: the workbench is shutting down, so the shell is gone regardless.
        }
    }

    /**
     * Closes the dialog if its shell is still open. MUST run on the UI thread (called
     * directly by {@link #promptOnUiThread}'s timer closer, and inside
     * {@link #closeDialogIfOpen}'s {@code asyncExec} for the worker path). Guards
     * every disposal step and never throws — closing an already-decided timed-out
     * dialog is best-effort only.
     *
     * @param dialog the dialog to close (never {@code null})
     */
    private static void closeOnUiThread(DestructiveConsentDialog dialog)
    {
        try
        {
            Shell dialogShell = dialog.getShell();
            if (dialogShell != null && !dialogShell.isDisposed())
            {
                dialog.close();
            }
        }
        catch (SWTException e) // NOSONAR best-effort close of an already-decided timeout; never fail
        {
            Activator.logInfo(
                "Destructive-consent gate: could not close the timed-out dialog shell (already closing)."); //$NON-NLS-1$
        }
    }

    /**
     * Logs the timeout-win line shared by both prompt paths.
     *
     * @param toolName the gated tool that timed out
     */
    private static void logTimeoutWin(String toolName)
    {
        Activator.logInfo("Destructive-consent gate: '" + toolName + "' timed out after " //$NON-NLS-1$ //$NON-NLS-2$
            + CONSENT_PROMPT_TIMEOUT_SECONDS + " s waiting for a human to confirm; auto-rejecting."); //$NON-NLS-1$
    }

    /**
     * The disposed-display fallback: the workbench closed between the live-display check in
     * {@link #requireConsent} and the prompt, so there is no UI to ask — allow (the same policy as
     * the headless / no-shell path), logging it, never failing the destructive tool. See #222.
     *
     * @param toolName the tool being gated
     * @return {@link ConsentDecision#ALLOW}
     */
    private static ConsentDecision allowOnDisposedDisplay(String toolName)
    {
        Activator.logInfo("Destructive-consent gate: display disposed before/while prompting for '" //$NON-NLS-1$
            + toolName + "'; allowing (headless fallback)."); //$NON-NLS-1$
        return ConsentDecision.ALLOW;
    }

    /**
     * Whether the given tool has been "Allow for session"-ed this EDT run. Test/UI
     * helper — the gate itself consults the set inside {@link #requireConsent}.
     *
     * @param toolName the tool name
     * @return {@code true} if the tool is in the in-memory session-allow set
     */
    boolean isSessionAllowed(String toolName)
    {
        return sessionAllow.contains(toolName);
    }

    /**
     * Records a tool as allowed for the rest of this EDT session. Exposed for tests;
     * production code only adds via the dialog's "Allow for session" button.
     *
     * @param toolName the tool name
     */
    void allowForSession(String toolName)
    {
        if (toolName != null)
        {
            sessionAllow.add(toolName);
        }
    }

    /**
     * Clears the in-memory session-allow set. Exposed for tests to keep them isolated;
     * production code never clears it (it lives for the whole EDT session).
     */
    void clearSessionAllow()
    {
        sessionAllow.clear();
    }

    /**
     * The decide-once arbiter shared by both prompt paths: the dialog's eventual
     * answer races the timeout to decide the verdict for one dialog invocation. On
     * the worker path ({@link #promptWithTimeout}) the two sides run on different
     * threads (dialog on UI, timeout on the worker); on the UI-thread path
     * ({@link #promptOnUiThread}) both run sequentially on the UI thread (the
     * {@code timerExec} closer fires inside {@code open()}'s nested event loop).
     * Either way, whichever side calls {@link #tryDecide} FIRST wins — its value
     * becomes the permanent decision, and every later call (from either side, in
     * either order) is a no-op. Package-private, holds no SWT or gate state, so the
     * race itself is unit-testable headlessly without a live {@link Display}.
     */
    static final class ConsentArbiter
    {
        private final AtomicReference<ConsentDecision> decision = new AtomicReference<>();

        private final CountDownLatch latch = new CountDownLatch(1);

        /**
         * Attempts to record the final decision. The first call across both threads
         * wins and releases {@link #await(long)}; every subsequent call is a no-op.
         *
         * @param candidate the decision this side wants to record (never {@code null})
         * @return {@code true} if this call won the race and set the decision,
         *         {@code false} if another call already decided first
         */
        boolean tryDecide(ConsentDecision candidate)
        {
            boolean won = decision.compareAndSet(null, candidate);
            if (won)
            {
                latch.countDown();
            }
            return won;
        }

        /**
         * @return the recorded decision, or {@code null} if {@link #tryDecide} has not
         *         been called (successfully) yet
         */
        ConsentDecision peek()
        {
            return decision.get();
        }

        /**
         * Blocks the calling thread until a decision is recorded or the timeout
         * elapses, whichever comes first.
         *
         * @param timeoutSeconds how long to wait, in seconds
         * @return {@code true} if a decision was recorded before the timeout elapsed,
         *         {@code false} if the wait timed out with no decision yet
         * @throws InterruptedException if the calling thread is interrupted while
         *             waiting
         */
        boolean await(long timeoutSeconds) throws InterruptedException
        {
            return latch.await(timeoutSeconds, TimeUnit.SECONDS);
        }
    }
}
