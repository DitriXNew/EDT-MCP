/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Auto-dismisses the unattended-blocking dialogs that EDT / Eclipse can raise around infobase
 * authentication while the MCP server is running:
 * <ul>
 *   <li>EDT's <em>"Configure Infobase access Settings"</em> credentials dialog
 *       ({@code InfobaseAccessSettingsDialog}, raised by {@code InfobaseAccessSettingsRequestor}) — closed
 *       (Cancel) so a connection with missing/wrong credentials fails fast instead of waiting for a human;</li>
 *   <li>Eclipse Secure Storage's <em>"Secure Storage - Password Hint Needed"</em> follow-up prompt, shown
 *       the first time the keyring master password is created — declined (No), because the MCP server
 *       supplies that master password programmatically ({@link McpSecureStorageProvider}) and needs no
 *       recovery hint. Without this, the first {@code set_infobase_credentials} call would leave this modal
 *       sitting on the headless UI even though the credential write itself already succeeded.</li>
 * </ul>
 *
 * <h2>Why this is needed (issue #194)</h2>
 * When an MCP operation connects to an infobase that requires user authentication
 * and the stored connection credentials are missing or wrong, the EDT designer
 * agent fails to authenticate and the platform pops a modal credentials dialog —
 * "Configure Infobase "{0}" access Settings" — and blocks the worker thread until
 * a human fills it in. That hangs the unattended call (it surfaced as the agent
 * "hanging" in #194). The dialog is raised deep inside EDT's connection layer with
 * no public hook, so it is intercepted at the SWT level, like the launch modals in
 * {@link LaunchUpdateDialogAutoConfirmer}.
 *
 * <h2>Why activity-scoped (not always-on) — issue #230</h2>
 * The auth (access-settings) dialog is auto-cancelled only while an MCP tool call is
 * <em>active or has just finished</em>, so a human who opens EDT's
 * "Configure Infobase access" dialog in the GUI while the server is <b>idle</b> can
 * still use it to store credentials into Secure Storage. Two mechanisms decide "active":
 * <ul>
 *   <li>an in-flight <b>counter</b> ({@link #IN_FLIGHT}, bumped by
 *       {@link #markActivityStart()} / {@link #markActivityEnd()} around
 *       {@code tool.execute(...)}) covers the whole <em>synchronous</em> call —
 *       {@code update_database}'s minutes included — regardless of duration;</li>
 *   <li>a trailing <b>grace window</b> ({@link #DEFAULT_ACTIVITY_GRACE_MILLIS}, from
 *       {@link #lastActivityEndMillis}) bridges the short gap to the <em>asynchronous</em>
 *       read-back {@link org.eclipse.core.runtime.jobs.Job Jobs} — e.g.
 *       {@code get_applications}/{@code create_infobase} — that raise the dialog
 *       <em>after</em> {@code tool.execute(...)} has already returned. Without this a
 *       missed async dialog would hang an unattended call (a #194 regression).</li>
 * </ul>
 * An env <b>kill-switch</b> {@link #ENV_SUPPRESS_AUTH_DIALOG} (default ON — set it to
 * {@code false}/{@code 0}/{@code no} to disable) lets an operator turn the auth-dialog
 * suppression off entirely. The filter itself is still installed once (lazily, the
 * first time a workbench {@link Display} is available — {@link #ensureInstalled()} is
 * called on every tool dispatch) and stays installed for the workbench's lifetime;
 * the activity/env decision is applied per event, only to the auth dialog.
 * <p>The Secure Storage password-<b>hint</b> dialog stays <b>always-suppressed</b>
 * (dismissed unconditionally, ignoring both the activity window and the env flag): it
 * is an internal follow-up to the MCP server supplying the keyring master password
 * programmatically ({@link McpSecureStorageProvider}) and is never something a human
 * configures. Credentials are provided through {@code set_infobase_credentials} /
 * {@code create_infobase}, so on a correctly configured base the auth dialog never
 * needs to appear at all.
 *
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>Only a shell title with the exact "Configure Infobase…" prefix (English / Russian — the only NL
 *       variants EDT ships) or the "Secure Storage - Password Hint" prefix is matched, so no unrelated
 *       dialog is touched.</li>
 *   <li>The match closes the shell ({@link Shell#close()}, which JFace maps to Cancel): for the
 *       access-settings dialog the blocked connection fails fast with an authentication error the tool
 *       reports (pointing at {@code set_infobase_credentials}); for the hint dialog the question resolves
 *       to "No" so the keyring keeps its (programmatically supplied) master password without a hint.</li>
 *   <li>Headless (no workbench {@link Display}) is a no-op — the probe never CREATES
 *       a display — and the install is idempotent and never throws.</li>
 * </ul>
 */
public final class InfobaseAuthDialogSuppressor
{
    /** English title prefix of {@code InfobaseAccessSettingsDialog} ("Configure Infobase Access Settings" / "Configure Infobase \"{0}\" access Settings"). */
    static final String AUTH_DIALOG_TITLE_PREFIX = "Configure Infobase"; //$NON-NLS-1$

    /**
     * Russian title prefix of the same dialog ("Сконфигурируйте доступ к информационной базе").
     * Verified verbatim from EDT's {@code com._1c.g5.v8.dt.platform.services.ui} bundle
     * ({@code InfobaseAccessSettingsDialog_Configure_infobase__0__access_settings}). A real 1C
     * dialog title the code matches, kept as a UTF-8 literal — the Tycho build forces
     * {@code project.build.sourceEncoding=UTF-8} and {@code <encoding>UTF-8</encoding>}, so it
     * compiles identically (CLAUDE.md rule #7 allows justified Cyrillic in matched string literals).
     */
    static final String AUTH_DIALOG_TITLE_PREFIX_RU =
        "Сконфигурируйте" //$NON-NLS-1$
            + " доступ к инфо" //$NON-NLS-1$
            + "рмационной базе"; //$NON-NLS-1$

    /** Every shipped localized title prefix of the access-settings dialog (English / Russian). */
    static final Set<String> AUTH_DIALOG_TITLE_PREFIXES = Collections.unmodifiableSet(
        new LinkedHashSet<>(Arrays.asList(AUTH_DIALOG_TITLE_PREFIX, AUTH_DIALOG_TITLE_PREFIX_RU)));

    /**
     * Title prefix of the Eclipse Secure Storage password-hint follow-up dialog
     * ({@code pswdRecoveryOptionTitle} in {@code org.eclipse.equinox.security.ui}'s
     * {@code messages.properties}: "Secure Storage - Password Hint Needed"). After a master password is
     * first created, the security UI asks whether to record a recovery hint. The string ships
     * <em>untranslated</em> (no NL fragment provides {@code pswdRecoveryOptionTitle}), so a single English
     * prefix matches on every locale.
     */
    static final String SECURE_STORAGE_HINT_TITLE_PREFIX = "Secure Storage - Password Hint"; //$NON-NLS-1$

    /**
     * Kill-switch environment variable for the infobase access-settings ("auth") dialog suppression
     * (issue #230). Default <b>ON</b>: unset / blank / any value other than a trimmed case-insensitive
     * {@code false}/{@code 0}/{@code no} keeps the suppression enabled (so the #194 unattended-safety
     * behaviour is preserved by default). Set it to {@code false} (or {@code 0}/{@code no}) on the EDT
     * launch to disable auth-dialog suppression entirely.
     *
     * <p>The polarity is <em>inverted</em> vs {@code DestructiveConsentGate.ENV_DESTRUCTIVE_CONSENT}
     * (that is opt-<em>IN</em>: only {@code allow} enables the bypass). Here the safe default is ON, so
     * the classifier defaults to {@code true} and only an explicit negative value disables it.
     */
    static final String ENV_SUPPRESS_AUTH_DIALOG = "EDT_MCP_SUPPRESS_AUTH_DIALOG"; //$NON-NLS-1$

    /**
     * Trailing grace window (ms) after the last MCP call ends during which an auth dialog is still
     * auto-cancelled. The in-flight {@link #IN_FLIGHT} counter already covers the whole synchronous
     * {@code execute()} window regardless of duration; this window only bridges the short gap from
     * execute-completion to an asynchronous read-back Job raising the dialog
     * ({@code get_applications}/{@code create_infobase} — realistically a few seconds). 30 s gives a
     * wide margin against a #194 regression while a human deliberately configuring credentials (idle
     * for minutes) is far past the window.
     */
    static final long DEFAULT_ACTIVITY_GRACE_MILLIS = 30_000L;

    /**
     * Number of MCP tool calls currently executing. Incremented by {@link #markActivityStart()} before
     * {@code tool.execute(...)} and decremented by {@link #markActivityEnd()} in the dispatch
     * {@code finally}, so a still-running call keeps the auth dialog suppressed for its whole duration.
     * An {@link AtomicInteger} because MCP worker threads call the mutators concurrently.
     */
    static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    /**
     * {@link System#currentTimeMillis()} of the most recent {@link #markActivityEnd()}. Combined with
     * {@link #DEFAULT_ACTIVITY_GRACE_MILLIS} it bridges the gap to an asynchronous read-back Job's
     * dialog. Volatile: written on worker threads, read on the SWT event thread.
     */
    static volatile long lastActivityEndMillis;

    private static final Object LOCK = new Object();

    /** Fast-path flag: once the filter is installed on a live display, dispatch skips the UI round-trip. */
    private static volatile boolean installed;

    private static Display filterDisplay;
    private static Listener filter;

    private InfobaseAuthDialogSuppressor()
    {
    }

    /**
     * Marks the start of an MCP tool call: increments the {@link #IN_FLIGHT} counter so an auth dialog
     * raised while the call runs is auto-cancelled. Paired with {@link #markActivityEnd()} in the
     * dispatch {@code finally} ({@code McpProtocolHandler.executeToolTimed}). Also armed around the
     * <em>fire-and-forget</em> launch in {@code DebugLaunchTool} (whose infobase connect happens in a
     * background Job after {@code execute()} has returned, so the trailing grace alone would not cover a
     * minutes-long launch — see issue #230).
     */
    public static void markActivityStart()
    {
        IN_FLIGHT.incrementAndGet();
    }

    /**
     * Marks the end of an MCP tool call: decrements the {@link #IN_FLIGHT} counter (clamped at zero as a
     * defence against an unbalanced call) and stamps {@link #lastActivityEndMillis} so the trailing
     * grace window keeps a briefly-later asynchronous read-back dialog suppressed. Always runs in the
     * dispatch {@code finally} so the counter never leaks on a thrown {@code execute}.
     */
    public static void markActivityEnd()
    {
        if (IN_FLIGHT.decrementAndGet() < 0)
        {
            IN_FLIGHT.set(0);
        }
        lastActivityEndMillis = System.currentTimeMillis();
    }

    /**
     * Reads the {@link #ENV_SUPPRESS_AUTH_DIALOG} environment variable and reports whether auth-dialog
     * suppression is enabled. Delegates to the pure {@link #envAllowsSuppression(String)} so the
     * classification is unit-testable without touching the process environment.
     *
     * @return {@code true} when the env flag leaves suppression enabled (the default)
     */
    static boolean isEnvSuppressEnabled()
    {
        return envAllowsSuppression(System.getenv(ENV_SUPPRESS_AUTH_DIALOG));
    }

    /**
     * Pure classifier for the {@link #ENV_SUPPRESS_AUTH_DIALOG} value, <b>default ON</b>: returns
     * {@code false} ONLY for a trimmed case-insensitive {@code false}/{@code 0}/{@code no}; every other
     * value — including {@code null}, blank, {@code true}, or garbage — returns {@code true}. The
     * default-ON polarity preserves the #194 unattended-safety behaviour for anyone who does not set the
     * flag (a naive copy of {@code DestructiveConsentGate.envForcesAllow} would default suppression OFF
     * and silently regress it).
     *
     * @param rawEnvValue the raw environment value (may be {@code null})
     * @return {@code true} to keep suppression enabled, {@code false} only for an explicit off value
     */
    static boolean envAllowsSuppression(String rawEnvValue)
    {
        if (rawEnvValue == null)
        {
            return true;
        }
        String value = rawEnvValue.trim();
        return !("false".equalsIgnoreCase(value) //$NON-NLS-1$
            || "0".equals(value) //$NON-NLS-1$
            || "no".equalsIgnoreCase(value)); //$NON-NLS-1$
    }

    /**
     * Pure decision (and test seam): should the infobase access-settings ("auth") dialog be
     * auto-cancelled right now? Suppress only when the env kill-switch leaves suppression enabled AND
     * there is current MCP activity — either a call is in flight, or the last call ended within the
     * grace window (so an asynchronous read-back dialog is still covered). When the server is idle
     * (no in-flight call and past the window) the dialog is left alone so a human can use it.
     *
     * @param envEnabled whether the env kill-switch leaves suppression enabled
     * @param inFlight the current {@link #IN_FLIGHT} count
     * @param now the current time ({@link System#currentTimeMillis()})
     * @param lastActivityEnd the timestamp of the last {@link #markActivityEnd()}
     * @param grace the trailing grace window in ms ({@link #DEFAULT_ACTIVITY_GRACE_MILLIS})
     * @return {@code true} to auto-cancel the auth dialog, {@code false} to leave it for the human
     */
    static boolean shouldSuppressAuthDialog(boolean envEnabled, int inFlight, long now,
        long lastActivityEnd, long grace)
    {
        return envEnabled && (inFlight > 0 || now - lastActivityEnd < grace);
    }

    /**
     * Pure decision (and test seam): is {@code shellTitle} the EDT
     * "Configure Infobase access Settings" dialog, in either shipped locale? Matched on the
     * stable leading prefix because EDT may interpolate the infobase name into the title.
     *
     * @param shellTitle a shell title (may be {@code null})
     * @return {@code true} when the title begins with a known access-settings prefix
     */
    static boolean isAuthDialogTitle(String shellTitle)
    {
        if (shellTitle == null)
        {
            return false;
        }
        for (String prefix : AUTH_DIALOG_TITLE_PREFIXES)
        {
            if (shellTitle.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure decision (and test seam): is {@code shellTitle} the Eclipse Secure Storage
     * "Password Hint Needed" follow-up dialog? Matched on its leading prefix
     * ({@link #SECURE_STORAGE_HINT_TITLE_PREFIX}).
     *
     * @param shellTitle a shell title (may be {@code null})
     * @return {@code true} when the title begins with the secure-storage password-hint prefix
     */
    static boolean isSecureStorageHintDialogTitle(String shellTitle)
    {
        return shellTitle != null && shellTitle.startsWith(SECURE_STORAGE_HINT_TITLE_PREFIX);
    }

    /**
     * Idempotently installs the global access-settings-dialog filter on the workbench
     * {@link Display}. Cheap after the first successful install (a volatile read, no UI
     * round-trip). No-op headless (no workbench display yet); never throws — callers invoke
     * it from the tool-dispatch hot path. Re-installs if the previous display was disposed
     * (a workbench recreated within the same JVM).
     */
    public static void ensureInstalled()
    {
        if (installed)
        {
            Display current = filterDisplay;
            if (current != null && !current.isDisposed())
            {
                return; // already installed on a live display — fast path
            }
        }
        Display display = safeDisplay();
        if (display == null || display.isDisposed())
        {
            return;
        }
        try
        {
            display.syncExec(() -> installOnUiThread(display));
        }
        catch (SWTException e)
        {
            // ERROR_DEVICE_DISPOSED race on shutdown — nothing to install.
        }
    }

    private static void installOnUiThread(Display display)
    {
        Listener toInstall;
        synchronized (LOCK)
        {
            if (filter != null && filterDisplay == display && !display.isDisposed())
            {
                installed = true;
                return; // already installed on this display
            }
            toInstall = createFilterListener();
            filter = toInstall;
            filterDisplay = display;
        }
        display.addFilter(SWT.Activate, toInstall);
        display.addFilter(SWT.Show, toInstall);
        installed = true;
    }

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
            boolean authDialog = isAuthDialogTitle(title);
            boolean hintDialog = !authDialog && isSecureStorageHintDialogTitle(title);
            if (!authDialog && !hintDialog)
            {
                return;
            }
            // The Secure Storage hint dialog is dismissed unconditionally (internal follow-up, never
            // human-configured). The auth dialog is dismissed only while there is MCP activity (a call
            // in flight or within the trailing grace window) and the env kill-switch leaves suppression
            // enabled — so a human who opens it in the GUI while the server is idle can use it (#230).
            if (authDialog && !shouldSuppressAuthDialog(isEnvSuppressEnabled(), IN_FLIGHT.get(),
                System.currentTimeMillis(), lastActivityEndMillis, DEFAULT_ACTIVITY_GRACE_MILLIS))
            {
                return;
            }
            // Defer so the modal finishes building before we dismiss it.
            shell.getDisplay().asyncExec(() -> dismissDialog(shell, hintDialog));
        };
    }

    /**
     * Dismisses an unattended-blocking dialog by closing its shell. JFace maps the shell close to its
     * {@code CANCEL} return code, which is exactly what each case needs:
     * <ul>
     *   <li>access-settings dialog → Cancel, so the blocked connection attempt fails fast with an
     *       authentication error instead of waiting for human input;</li>
     *   <li>secure-storage hint dialog ({@code isHint}) → the {@code openQuestion} call returns
     *       {@code false} (No), so no recovery hint is recorded — the master password is already created.</li>
     * </ul>
     * Guarded against disposal; never throws onto the UI thread.
     *
     * @param shell the dialog shell to close
     * @param isHint {@code true} for the secure-storage password-hint dialog, {@code false} for the
     *            infobase access-settings dialog (controls only the log message)
     */
    private static void dismissDialog(Shell shell, boolean isHint)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            if (isHint)
            {
                Activator.logInfo("Auto-declining Eclipse Secure Storage password-hint dialog '" //$NON-NLS-1$
                    + safeShellText(shell) + "' (master password is supplied programmatically by " //$NON-NLS-1$
                    + "McpSecureStorageProvider — no recovery hint needed)"); //$NON-NLS-1$
            }
            else
            {
                Activator.logInfo("Auto-cancelling infobase access-settings dialog '" //$NON-NLS-1$
                    + safeShellText(shell) + "' (no/invalid stored credentials — set them with " //$NON-NLS-1$
                    + "set_infobase_credentials)"); //$NON-NLS-1$
            }
            shell.close();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to auto-dismiss an unattended-blocking dialog", e); //$NON-NLS-1$
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
     * Returns the workbench {@link Display} or {@code null} when no workbench is running (headless),
     * via {@link LaunchLifecycleUtils#workbenchDisplayOrNull()} — never creates a display.
     */
    private static Display safeDisplay()
    {
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        return display != null && !display.isDisposed() ? display : null;
    }
}
