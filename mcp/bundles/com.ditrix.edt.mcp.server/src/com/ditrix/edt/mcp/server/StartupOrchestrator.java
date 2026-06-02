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
 * customizer (non-headless, on the UI thread via {@code syncExec}), deactivate
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
        // Dispose UI components only in non-headless mode
        if (!headless)
        {
            // Dispose navigator toolbar customizer
            try
            {
                org.eclipse.swt.widgets.Display display = org.eclipse.swt.widgets.Display.getDefault();
                if (display != null && !display.isDisposed())
                {
                    display.syncExec(() -> {
                        try
                        {
                            com.ditrix.edt.mcp.server.ui.NavigatorToolbarCustomizer.getInstance().dispose();
                        }
                        catch (Exception e)
                        {
                            // Ignore - workbench may be closing
                        }
                    });
                }
            }
            catch (Exception e)
            {
                // Ignore - display may be disposed
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
