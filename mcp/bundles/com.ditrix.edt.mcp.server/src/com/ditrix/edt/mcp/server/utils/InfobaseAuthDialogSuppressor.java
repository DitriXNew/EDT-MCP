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

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Auto-cancels EDT's blocking <em>"Configure Infobase access Settings"</em>
 * dialog ({@code InfobaseAccessSettingsDialog}, raised by
 * {@code InfobaseAccessSettingsRequestor}) while the MCP server is running.
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
 * <h2>Why always-on (not per-call)</h2>
 * EDT computes an application's update state (and connects to the infobase) inside
 * <b>background {@link org.eclipse.core.runtime.jobs.Job Jobs}</b> — e.g. the
 * read-back of {@code get_applications}/{@code create_infobase} — which raise the
 * dialog <em>after</em> the originating {@code tool.execute(...)} has already
 * returned. A filter that was armed only around the call would miss those. So the
 * filter is installed once (lazily, the first time a workbench {@link Display} is
 * available — {@link #ensureInstalled()} is called on every tool dispatch) and
 * stays installed for the workbench's lifetime: the MCP server runs unattended, so
 * this dialog must never block, whoever raises it. Credentials are provided through
 * {@code set_infobase_credentials} / {@code create_infobase}, so on a correctly
 * configured base the dialog never needs to appear at all.
 *
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>Only a shell title with the exact "Configure Infobase…" prefix is matched
 *       (English / Russian — the only NL variants EDT ships), so no unrelated dialog
 *       is touched.</li>
 *   <li>The match closes the shell ({@link Shell#close()}, which JFace maps to
 *       Cancel); the blocked connection then fails fast with an authentication error
 *       the tool reports (pointing at {@code set_infobase_credentials}).</li>
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

    private static final Object LOCK = new Object();

    /** Fast-path flag: once the filter is installed on a live display, dispatch skips the UI round-trip. */
    private static volatile boolean installed;

    private static Display filterDisplay;
    private static Listener filter;

    private InfobaseAuthDialogSuppressor()
    {
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
            if (!isAuthDialogTitle(title))
            {
                return;
            }
            // Defer so the modal finishes building before we cancel it.
            shell.getDisplay().asyncExec(() -> cancelDialog(shell));
        };
    }

    /**
     * Cancels the access-settings dialog by closing its shell (JFace maps the close to Cancel), so
     * the blocked connection attempt fails fast instead of waiting for human input. Guarded against
     * disposal; never throws onto the UI thread.
     */
    private static void cancelDialog(Shell shell)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            Activator.logInfo("Auto-cancelling infobase access-settings dialog '" //$NON-NLS-1$
                + safeShellText(shell) + "' (no/invalid stored credentials — set them with " //$NON-NLS-1$
                + "set_infobase_credentials)"); //$NON-NLS-1$
            shell.close();
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to auto-cancel the infobase access-settings dialog", e); //$NON-NLS-1$
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
