/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService;
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
 *   <li><b>Dialog</b>: open {@link DestructiveConsentDialog} and BLOCK. Allow = OK,
 *       Reject = CANCEL, "Allow for session" adds the tool to the session set. When
 *       already on the UI thread ({@code Display.getCurrent() != null}) open directly;
 *       otherwise {@code display.syncExec} capturing the choice in an
 *       {@link AtomicInteger} (the {@code AbstractMetadataWriteTool} syncExec idiom).</li>
 * </ol>
 *
 * <p><b>Invariant:</b> the gate NEVER blocks in a headless / env-bypass / non-ASK
 * (level-2/session/per-tool-allowed) path; it {@code syncExec}s ONLY with a live
 * display and a live shell; it does not deadlock when already on the UI thread; and
 * a {@link ConsentDecision#REJECT} mutates nothing (it only returns the decision, and
 * the caller turns it into an error).
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
        REJECT
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
     * Opens {@link DestructiveConsentDialog} on the live UI session and blocks until
     * the user answers. Runs directly when already on the UI thread; otherwise
     * {@code display.syncExec} captures the raw button id in an {@link AtomicInteger}
     * (the {@code AbstractMetadataWriteTool} syncExec idiom), so the MCP worker thread
     * blocks exactly until the human responds. Maps the button id to a decision and,
     * for "Allow for session", records the tool in {@link #sessionAllow}.
     */
    private ConsentDecision promptForConsent(String toolName, ConsentPreview preview, Display display,
        Shell shell)
    {
        final AtomicInteger returnCode = new AtomicInteger(IDialogConstants.CANCEL_ID);
        Runnable openDialog = () -> {
            DestructiveConsentDialog dialog = new DestructiveConsentDialog(shell, toolName, preview);
            returnCode.set(dialog.open());
        };
        if (Display.getCurrent() != null)
        {
            openDialog.run();
        }
        else
        {
            display.syncExec(openDialog);
        }

        int code = returnCode.get();
        if (code == ALLOW_FOR_SESSION_ID)
        {
            sessionAllow.add(toolName);
            Activator.logInfo("Destructive-consent gate: user allowed '" + toolName //$NON-NLS-1$
                + "' for the rest of this EDT session."); //$NON-NLS-1$
            return ConsentDecision.ALLOW;
        }
        if (code == IDialogConstants.OK_ID)
        {
            return ConsentDecision.ALLOW;
        }
        Activator.logInfo("Destructive-consent gate: user declined '" + toolName + "'."); //$NON-NLS-1$ //$NON-NLS-2$
        return ConsentDecision.REJECT;
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
}
