/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import org.osgi.framework.BundleContext;

import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.internal.GroupServiceImpl;

/**
 * Orchestrates the EDT MCP plugin's startup and shutdown side effects that are
 * not the OSGi service trackers (those live in {@link EdtServices}).
 * <p>
 * Extracted from {@link Activator#start(BundleContext)} /
 * {@link Activator#stop(BundleContext)} so the activator only wires the pieces
 * together. The steps run in exactly the same order as before:
 * <ol>
 *   <li>create + activate the {@link IGroupService};</li>
 *   <li>(non-headless) initialize {@code FilterByTagManager} to reset toggle state;</li>
 *   <li>(non-headless) initialize {@code NavigatorToolbarCustomizer} on the UI thread
 *       via {@code Display.asyncExec}.</li>
 * </ol>
 * Teardown reverses these on {@link #stop()}: dispose the navigator toolbar
 * customizer (non-headless, only when already on a live UI thread), deactivate
 * the group service, then stop the {@code UpdateChecker} scheduler.
 * <p>
 * This class owns the {@link IGroupService} reference; {@link Activator}
 * delegates {@code getGroupService()} to {@link #getGroupService()} so all
 * existing call sites are unchanged.
 */
public class StartupOrchestrator
{
    /** Group service instance (created directly, not via OSGi DS to avoid circular references) */
    private IGroupService groupService;

    /**
     * Runs the startup steps in the same order as the original
     * {@code Activator.start}.
     *
     * @param headless whether the runtime is headless (UI parts are skipped)
     */
    public void start(boolean headless)
    {
        // Create group service directly (not via OSGi DS to avoid circular references)
        groupService = new GroupServiceImpl();
        ((GroupServiceImpl) groupService).activate();

        // Initialize UI components only in non-headless mode
        if (!headless)
        {
            // Initialize filter manager to reset toggle state on startup
            com.ditrix.edt.mcp.server.tags.ui.FilterByTagManager.getInstance();

            // Initialize navigator toolbar customizer to hide standard Collapse All button
            org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
                try {
                    com.ditrix.edt.mcp.server.ui.NavigatorToolbarCustomizer.getInstance().initialize();
                } catch (Exception e) {
                    Activator.logError("Failed to initialize NavigatorToolbarCustomizer", e); //$NON-NLS-1$
                }
            });
        }
    }

    /**
     * Runs the teardown steps in the same order as the original
     * {@code Activator.stop}.
     *
     * @param headless whether the runtime is headless (UI parts are skipped)
     */
    public void stop(boolean headless)
    {
        // Dispose UI components only in non-headless mode.
        // Never block on the UI thread from here: stop() runs on the OSGi
        // framework shutdown thread after the workbench event loop has exited,
        // so a syncExec never returns and pins the JVM — EDT keeps running as
        // a background process (#135). Display.getDefault() is also forbidden
        // here: with the display already disposed it would CREATE a new one on
        // the shutdown thread. Listener teardown is best-effort — widgets die
        // with the display — so run it inline only when already on a live UI
        // thread and skip it otherwise.
        if (!headless)
        {
            org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getCurrent();
            if (display != null && !display.isDisposed())
            {
                try
                {
                    com.ditrix.edt.mcp.server.ui.NavigatorToolbarCustomizer.getInstance().dispose();
                }
                catch (Exception e)
                {
                    // Ignore - workbench may be closing
                }
            }
        }

        // Deactivate group service
        if (groupService instanceof GroupServiceImpl impl)
        {
            impl.deactivate();
        }
        groupService = null;

        // Stop update checker scheduler
        UpdateChecker.getInstance().stopScheduler();
    }

    /**
     * Returns the IGroupService for group operations.
     * Used for virtual folder groups in the Navigator.
     *
     * @return group service or null if not available
     */
    public IGroupService getGroupService()
    {
        return groupService;
    }
}
