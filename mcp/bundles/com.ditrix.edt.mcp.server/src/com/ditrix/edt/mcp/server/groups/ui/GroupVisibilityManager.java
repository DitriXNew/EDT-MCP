package com.ditrix.edt.mcp.server.groups.ui;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.State;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.RegistryToggleState;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Keeps the Navigator group visibility toggle state.
 */
public final class GroupVisibilityManager {

    public static final String HIDE_GROUPS_COMMAND_ID = "com.ditrix.edt.mcp.server.groups.hideGroups"; //$NON-NLS-1$

    private static GroupVisibilityManager instance;

    private volatile boolean groupsHidden;

    private GroupVisibilityManager() {
        Display.getDefault().asyncExec(() -> updateToggleState(false));
    }

    public static synchronized GroupVisibilityManager getInstance() {
        if (instance == null) {
            instance = new GroupVisibilityManager();
        }
        return instance;
    }

    public boolean areGroupsHidden() {
        return groupsHidden;
    }

    public void toggleGroupsHidden() {
        setGroupsHidden(!groupsHidden);
    }

    public void setGroupsHidden(boolean hidden) {
        groupsHidden = hidden;
        updateToggleState(hidden);
        refreshNavigator();
        Activator.logInfo("Navigator groups " + (hidden ? "hidden" : "shown")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void refreshNavigator() {
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }

        display.asyncExec(() -> {
            try {
                CommonNavigator navigator = getNavigator();
                if (navigator == null) {
                    return;
                }

                CommonViewer viewer = navigator.getCommonViewer();
                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                    viewer.refresh();
                }
            } catch (Exception e) {
                Activator.logError("Failed to refresh Navigator after changing group visibility", e); //$NON-NLS-1$
            }
        });
    }

    private void updateToggleState(boolean hidden) {
        try {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null) {
                Command command = commandService.getCommand(HIDE_GROUPS_COMMAND_ID);
                if (command != null) {
                    State state = command.getState(RegistryToggleState.STATE_ID);
                    if (state != null) {
                        state.setValue(hidden);
                        commandService.refreshElements(HIDE_GROUPS_COMMAND_ID, null);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore visual feedback errors.
        }
    }

    private CommonNavigator getNavigator() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null && window.getActivePage() != null) {
            var viewPart = window.getActivePage().findView(TagConstants.NAVIGATOR_VIEW_ID);
            if (viewPart instanceof CommonNavigator navigator) {
                return navigator;
            }
        }
        return null;
    }
}