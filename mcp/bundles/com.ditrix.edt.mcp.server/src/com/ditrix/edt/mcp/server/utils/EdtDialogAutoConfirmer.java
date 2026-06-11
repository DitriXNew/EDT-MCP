/**
 * MCP Server for EDT
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Auto-confirms a blocking EDT modal dialog (by exact title) while armed, by
 * programmatically pressing its <em>default</em> button — the same choice a
 * careful user would make. Each instance watches its own immutable set of
 * titles, so different callers (a launch, a database update) can target
 * different dialogs without interfering with each other.
 *
 * <h2>Why this is needed</h2>
 * Several EDT operations (a runtime-client launch whose infobase is not
 * byte-for-byte equal to the project, and {@code IApplicationManager.update}
 * against an infobase that still has an active session) bring up an
 * <b>application-modal</b> dialog and block the calling thread until a human
 * answers it. In an unattended MCP run nobody clicks it, so the call hangs until
 * the transport times out. While armed, a {@link Display} filter presses the
 * dialog's default button so the operation proceeds.
 *
 * <h2>Scope &amp; safety</h2>
 * <ul>
 *   <li>The filter is installed only between {@link #arm()} and {@link #disarm()}
 *       (use try/finally around the single blocking call), so manual EDT
 *       operations outside that window still prompt normally.</li>
 *   <li>{@link #arm()}/{@link #disarm()} are reentrant via a counter.</li>
 *   <li>Only the exact configured titles are matched, so unrelated dialogs that
 *       happen to appear during the window are left untouched.</li>
 *   <li>Headless (no SWT {@link Display}) is a no-op — no dialog can appear
 *       there anyway.</li>
 * </ul>
 */
public final class EdtDialogAutoConfirmer
{
    private final Set<String> targetTitles;
    private final Object lock = new Object();

    private int armCount;
    private Display filterDisplay;
    private Listener filter;

    /**
     * @param targetTitles exact shell titles to auto-confirm (case-sensitive). A
     *                     defensive immutable copy is taken.
     */
    public EdtDialogAutoConfirmer(Set<String> targetTitles)
    {
        this.targetTitles = Collections.unmodifiableSet(new LinkedHashSet<>(targetTitles));
    }

    /** True if {@code shellTitle} is one of the titles this instance confirms. */
    public boolean isTargetTitle(String shellTitle)
    {
        return shellTitle != null && targetTitles.contains(shellTitle);
    }

    /**
     * Arms the auto-confirmer. MUST be paired with {@link #disarm()} in a
     * {@code finally} block around the blocking call. Reentrant: nested/concurrent
     * uses of the same instance share a single filter.
     *
     * <p>No-op in a headless environment (no SWT display).
     */
    public void arm()
    {
        Display display = safeDisplay();
        if (display == null)
        {
            return;
        }
        synchronized (lock)
        {
            armCount++;
            if (filter != null)
            {
                return;
            }
            Listener listener = event -> {
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
                if (!isTargetTitle(title))
                {
                    return;
                }
                // Defer so the modal finishes building its button bar and enters
                // its event loop; the press then runs inside that loop.
                shell.getDisplay().asyncExec(() -> pressDefaultButton(shell));
            };
            // Display filters must be (un)installed on the UI thread.
            display.syncExec(() -> {
                display.addFilter(SWT.Activate, listener);
                display.addFilter(SWT.Show, listener);
            });
            filter = listener;
            filterDisplay = display;
        }
    }

    /**
     * Disarms the auto-confirmer. The underlying {@link Display} filter is
     * removed only once the last paired {@link #arm()} has been released.
     */
    public void disarm()
    {
        Listener toRemove;
        Display display;
        synchronized (lock)
        {
            if (armCount > 0)
            {
                armCount--;
            }
            if (armCount > 0 || filter == null)
            {
                return;
            }
            toRemove = filter;
            display = filterDisplay;
            filter = null;
            filterDisplay = null;
        }
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> {
                display.removeFilter(SWT.Activate, toRemove);
                display.removeFilter(SWT.Show, toRemove);
            });
        }
    }

    /**
     * Presses the default button of the given dialog shell. Guarded against
     * disposal and never throws onto the UI thread.
     */
    private void pressDefaultButton(Shell shell)
    {
        try
        {
            if (shell == null || shell.isDisposed())
            {
                return;
            }
            Button button = shell.getDefaultButton();
            if (button == null || button.isDisposed())
            {
                return;
            }
            Activator.logInfo("Auto-confirming EDT dialog '" + safeTitle(shell) //$NON-NLS-1$
                + "' via button '" + safeText(button) + "'"); //$NON-NLS-1$ //$NON-NLS-2$
            Event event = new Event();
            event.widget = button;
            // Mirrors a user click: JFace dialog buttons fire buttonPressed() on
            // SWT.Selection, which sets the return code and closes the dialog.
            button.notifyListeners(SWT.Selection, event);
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to auto-confirm an EDT dialog", e); //$NON-NLS-1$
        }
    }

    private static String safeTitle(Shell shell)
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

    /**
     * Returns the default {@link Display} or {@code null} when SWT cannot be
     * initialised (headless CI / no UI).
     */
    private static Display safeDisplay()
    {
        try
        {
            Display display = Display.getDefault();
            return display != null && !display.isDisposed() ? display : null;
        }
        catch (SWTError | UnsatisfiedLinkError e)
        {
            return null;
        }
    }
}
