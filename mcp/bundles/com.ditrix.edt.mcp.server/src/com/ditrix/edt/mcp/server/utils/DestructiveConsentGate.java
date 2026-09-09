/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.List;
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
 *       This is the automated-run bypass the destructive e2e suite relies on, and the
 *       ONLY way a destructive operation proceeds with nobody watching; each such allow
 *       logs an audit line naming the tool and its preview.</li>
 *   <li><b>Headless</b>: if there is no live workbench display or no active shell
 *       (via {@link LaunchLifecycleUtils#workbenchDisplayOrNull()} /
 *       {@link LaunchLifecycleUtils#grabActiveShell()}) →
 *       {@link ConsentDecision#UNATTENDED} plus a logged info line. NEVER
 *       {@code syncExec} against a null/disposed display; NEVER block. This step
 *       REFUSES: with no human to ask, consent for an unattended run is the operator's
 *       to grant at launch (step 1), not the missing display's to grant for them.</li>
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
 * {@link ConsentDecision#ALLOW} verdict ({@link ConsentDecision#REJECT},
 * {@link ConsentDecision#TIMEOUT} or {@link ConsentDecision#UNATTENDED}) mutates nothing
 * (it only returns the decision, and the caller turns it into an error via
 * {@link #consentDeniedMessage(ConsentDecision, String)}).
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
     * always-destructive tools plus the conditionally destructive ones —
     * {@code modify_metadata} and {@code dcs} (gated only for a destructive retype),
     * {@code git} (gated per write-capable subcommand) and {@code evaluate_expression}
     * (gated always, because arbitrary BSL cannot be classified). Each tool decides
     * when to call, the gate does not.
     *
     * <p>Related to but deliberately NOT equal to
     * {@code ToolAnnotationClassifier.DESTRUCTIVE_TOOLS}: that MCP-hint list carries
     * {@code delete_launch_config} (which is cheap/recoverable and NOT gated) and
     * omits the conditional four, whose TYPICAL call is not destructive and whose hint
     * therefore stays {@code false} — one hint per tool cannot say "this depends on the
     * arguments", and marking a mostly-read tool destructive would steer clients away
     * from it. The exact relationship is asserted by {@code DestructiveConsentGateTest}
     * so the two lists never silently drift.
     */
    public static final Set<String> GATED_TOOLS = Set.of(
        "delete_metadata", //$NON-NLS-1$
        "rename_metadata_object", //$NON-NLS-1$
        "delete_project", //$NON-NLS-1$
        "delete_infobase", //$NON-NLS-1$
        "update_database", //$NON-NLS-1$
        "modify_metadata", //$NON-NLS-1$
        "dcs", //$NON-NLS-1$
        // Conditionally destructive like modify_metadata: the tool asks for its WRITE-CAPABLE
        // subcommands (everything but status/diff/log/show/blame/ls-files/rev-parse/describe), since
        // whether one destroys work depends on git's per-subcommand option grammar - see
        // GitTool.destructiveForm.
        "git", //$NON-NLS-1$
        // Arbitrary BSL in the running application: its own description says so ("executes
        // arbitrary code in the running application - it can change state, not just read it"),
        // and unlike every other entry here the damage is unbounded and undeclarable - the
        // expression can call anything the 1C session can, so no preview can enumerate what it
        // will touch. set_variable is deliberately NOT gated beside it: it assigns one named
        // variable in one suspended frame, which IS bounded and IS shown to the caller.
        "evaluate_expression" //$NON-NLS-1$
    );

    /**
     * How long the headless probe waits for the UI thread before giving up on it.
     * <p>
     * Short on purpose. This is not the human's thinking time - that is
     * {@link #CONSENT_PROMPT_TIMEOUT_SECONDS}, and it starts later. This is only "is there a
     * workbench able to answer at all", a question a healthy UI thread answers in microseconds.
     * A wedged one never answers, and the wait for it used to be unbounded: the gate is called
     * from a worker thread that holds the caller's lock, so every later call for the same
     * resource queued behind a wait with no end.
     * </p>
     */
    static final long SHELL_PROBE_TIMEOUT_MS = 5_000L;

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
        TIMEOUT,
        /**
         * There was nobody to ask: no live workbench display or shell (a headless EDT), or
         * the display went away between the probe and the prompt. A REJECT-like verdict with
         * its own actionable text naming the two ways to opt in.
         *
         * <p>This used to be an ALLOW. The gate exists to stop an agent destroying something
         * without a human, and "no human is present" is the case it was least entitled to
         * decide by itself: an agent that can start EDT headless removed the gate by doing so.
         * Consent for an unattended run is now something the OPERATOR grants at launch
         * ({@link DestructiveConsentGate#ENV_DESTRUCTIVE_CONSENT}{@code =allow}, step 1) rather
         * than something the absence of a display grants on their behalf.</p>
         */
        UNATTENDED,
        /**
         * The workbench exists but its UI thread did not answer the bounded shell probe within
         * {@link DestructiveConsentGate#SHELL_PROBE_TIMEOUT_MS} — so no dialog was ever shown.
         *
         * <p>Separate from {@link #TIMEOUT} because the remedies differ: that verdict means a
         * dialog WAS shown and nobody answered it within
         * {@link DestructiveConsentGate#CONSENT_PROMPT_TIMEOUT_SECONDS}, so "answer it promptly"
         * and "allow the tool in Preferences" are the right advice. Here they are not: the probe
         * runs BEFORE the policy is read, so a preference allowance cannot get through a wedged
         * UI either — only freeing the UI thread, or the launch-time bypass, does.</p>
         */
        UI_UNRESPONSIVE
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
        // Step 1 — env bypass WINS (the automated-run / e2e path). It leaves an audit line
        // naming the tool and what it was about to do: this is the one path where a
        // destructive operation proceeds with nobody watching, so the run must at least be
        // readable afterwards in the EDT log.
        if (isEnvAllow())
        {
            Activator.logInfo("Destructive-consent gate: " + ENV_DESTRUCTIVE_CONSENT //$NON-NLS-1$
                + "=allow bypassed the prompt for '" + toolName + "' — " + describe(preview)); //$NON-NLS-1$ //$NON-NLS-2$
            return ConsentDecision.ALLOW;
        }

        // Step 2 — headless probe: never block forever, and never grant consent because no UI
        // answered. With no human to ask, the answer is NO; consent for an unattended run comes
        // from the operator at launch (step 1), not from the absence of a display.
        //
        // The probe is BOUNDED. It used to ask the UI thread with an unbounded syncExec, so on a
        // workbench whose UI thread is wedged this call never returned - and it is reached from a
        // worker thread that is holding the caller's lock (merge_rules holds its path mutex),
        // so every later call for the same resource queued behind a wait that could not end.
        //
        // The ORDER is deliberately unchanged. Reading the policy first would answer ALLOW on a
        // display-less EDT for ALLOW_ALL, for a per-tool allowance and for a session allowance -
        // exactly where #566 decided the gate must refuse - so the reorder that was tried once
        // (b1180580) had to be reverted (b1d14680). The fix here is the WAIT, not the sequence.
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        LaunchLifecycleUtils.ShellProbe probe =
            LaunchLifecycleUtils.grabActiveShellWithin(SHELL_PROBE_TIMEOUT_MS);
        if (probe.outcome() == LaunchLifecycleUtils.ShellProbeOutcome.TIMED_OUT)
        {
            // NOT reported as headless: this workbench exists and simply did not answer, so the
            // remedy is to free the UI thread. TIMEOUT already says that in words, and says it
            // without claiming the operator is running without a display.
            Activator.logInfo("Destructive-consent gate: the UI thread did not answer within " //$NON-NLS-1$
                + SHELL_PROBE_TIMEOUT_MS + "ms — refusing '" + toolName //$NON-NLS-1$
                + "' rather than waiting on it."); //$NON-NLS-1$
            return ConsentDecision.UI_UNRESPONSIVE;
        }
        Shell shell = probe.shell();
        if (display == null || display.isDisposed() || shell == null)
        {
            Activator.logInfo("Destructive-consent gate: no active UI session — refusing '" //$NON-NLS-1$
                + toolName + "' (headless/unattended; set " + ENV_DESTRUCTIVE_CONSENT //$NON-NLS-1$
                + "=allow at launch to permit it)."); //$NON-NLS-1$
            return ConsentDecision.UNATTENDED;
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
     * place of the previously-inlined literal, so all three texts live in one place:
     * {@link ConsentDecision#UNATTENDED} names the launch bypass that would have let the
     * call through and the workbench that could confirm it;
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
        if (decision == ConsentDecision.UNATTENDED)
        {
            return "Destructive operation '" + toolName + "' needs a human to confirm it, and this " //$NON-NLS-1$ //$NON-NLS-2$
                + "EDT has no UI session to ask (headless or shutting down), so it was refused and " //$NON-NLS-1$
                + "nothing was changed. To run it unattended, set " + ENV_DESTRUCTIVE_CONSENT //$NON-NLS-1$
                + "=allow on the EDT process at launch; otherwise re-run it on an EDT workbench " //$NON-NLS-1$
                + "with a window open and answer the confirmation dialog."; //$NON-NLS-1$
        }
        if (decision == ConsentDecision.UI_UNRESPONSIVE)
        {
            // No dialog was ever shown, and the wait was the probe's, not the prompt's: naming the
            // prompt budget or the Preferences allowance here would send the operator after a
            // dialog that does not exist and a setting the wedged probe never reaches.
            return "Destructive operation '" + toolName + "' was refused because the EDT workbench " //$NON-NLS-1$ //$NON-NLS-2$
                + "did not answer within " + SHELL_PROBE_TIMEOUT_MS + " ms: its UI thread is busy " //$NON-NLS-1$ //$NON-NLS-2$
                + "or wedged, so no confirmation dialog could be shown and nothing was changed. " //$NON-NLS-1$
                + "Free the UI thread (finish or cancel what the workbench is doing, or restart " //$NON-NLS-1$
                + "it) and re-run; for an unattended run set " + ENV_DESTRUCTIVE_CONSENT //$NON-NLS-1$
                + "=allow on the EDT process at launch. A Preferences allowance does NOT help " //$NON-NLS-1$
                + "here - the gate asks for the workbench before it reads the policy."; //$NON-NLS-1$
        }
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
            return refuseOnDisposedDisplay(toolName);
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
            return refuseOnDisposedDisplay(toolName);
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
     * {@link #requireConsent} and the prompt, so there is no UI to ask — the same policy as the
     * headless / no-shell path, logged, never letting an {@link SWTException} escape as the
     * tool's failure. See #222 for why this path exists at all; it answered ALLOW until #566,
     * on the reasoning that a shutdown race should not fail a destructive tool. It should: a
     * dialog that was never shown is not a human who agreed, and the caller can retry.
     *
     * @param toolName the tool being gated
     * @return {@link ConsentDecision#UNATTENDED}
     */
    private static ConsentDecision refuseOnDisposedDisplay(String toolName)
    {
        Activator.logInfo("Destructive-consent gate: display disposed before/while prompting for '" //$NON-NLS-1$
            + toolName + "'; refusing (nothing was confirmed)."); //$NON-NLS-1$
        return ConsentDecision.UNATTENDED;
    }

    /** Names listed in the audit line before it says "and N more". */
    private static final int AUDIT_MAX_NAMES = 5;

    /** Longest a single audited value may be before it is elided. */
    private static final int AUDIT_MAX_NAME_CHARS = 120;

    /**
     * A one-line, bounded rendering of a {@link ConsentPreview} for the env-bypass audit line.
     * <p>
     * A preview's item names are CALLER-SUPPLIED, and that cuts two ways. Every value goes
     * through {@link #auditSafe} (control characters folded to spaces, elided past
     * {@link #AUDIT_MAX_NAME_CHARS}) so a huge value cannot bloat the log and an embedded
     * newline cannot forge what looks like a fresh {@code !ENTRY}; only the first
     * {@link #AUDIT_MAX_NAMES} are listed.
     * </p>
     * <p>
     * Sanitising is not enough when the names are the caller's own TEXT rather than identifiers
     * this server chose - {@code evaluate_expression} sends BSL that may carry a password or a
     * token, and a bounded, single-line secret is still a secret written to disk. Such a preview
     * says so ({@link ConsentPreview#areNamesLoggable()}), and then the line records how many
     * characters were allowed to run instead of any part of them.
     * </p>
     * <p>
     * Either way the line stays an INDEX into what happened - the tool, the scale, and the
     * targets when the targets are nameable - never a transcript of it. Package-visible so a
     * test can pin the composed line, not just its parts.
     * </p>
     *
     * @param preview the preview the gated tool computed, or {@code null}
     * @return a single line describing the pending operation; never {@code null}
     */
    static String describe(ConsentPreview preview)
    {
        if (preview == null)
        {
            return "no preview supplied"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        if (preview.getTitle() != null)
        {
            sb.append(auditSafe(preview.getTitle()));
        }
        if (preview.getSubtitle() != null)
        {
            sb.append(sb.length() > 0 ? ": " : "").append(auditSafe(preview.getSubtitle())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(sb.length() > 0 ? " " : "").append('(').append(preview.getTotalCount()) //$NON-NLS-1$ //$NON-NLS-2$
            .append(" item(s)"); //$NON-NLS-1$
        List<String> names = preview.getTopNames();
        if (!names.isEmpty())
        {
            sb.append(": "); //$NON-NLS-1$
            if (preview.areNamesLoggable())
            {
                int listed = Math.min(names.size(), AUDIT_MAX_NAMES);
                for (int i = 0; i < listed; i++)
                {
                    sb.append(i > 0 ? ", " : "").append(auditSafe(names.get(i))); //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (names.size() > listed)
                {
                    sb.append(", and ").append(names.size() - listed).append(" more"); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            else
            {
                int chars = 0;
                for (String name : names)
                {
                    chars += name == null ? 0 : name.length();
                }
                sb.append("content not logged, ").append(chars).append(" chars"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.append(')').toString();
    }

    /**
     * Makes one caller-supplied value safe to put on a single log line: every control character
     * (newline included) becomes a space, and anything past {@link #AUDIT_MAX_NAME_CHARS} is
     * replaced by an explicit elision that keeps the original length visible.
     *
     * @param value the value to render; may be {@code null}
     * @return a single-line, length-bounded rendering
     */
    static String auditSafe(String value)
    {
        if (value == null)
        {
            return ""; //$NON-NLS-1$
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), AUDIT_MAX_NAME_CHARS));
        int kept = Math.min(value.length(), AUDIT_MAX_NAME_CHARS);
        for (int i = 0; i < kept; i++)
        {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (value.length() > kept)
        {
            out.append("\u2026 (").append(value.length()).append(" chars)"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return out.toString();
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
