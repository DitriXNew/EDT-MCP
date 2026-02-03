/**
 * Copyright (c) 2025, Dmitriy Safonov
 * https://github.com/desfarik/
 * 
 * Utility class to hide the standard Collapse All button from EDT Navigator toolbar.
 * This allows us to use our own Collapse All button with proper positioning.
 */
package com.ditrix.edt.mcp.server.ui;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

/**
 * Manager that hides the standard Collapse All button from EDT Navigator.
 */
public class NavigatorToolbarCustomizer {

    private static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator";
    private static final String COLLAPSE_ALL_ACTION_DEF_ID = "org.eclipse.ui.navigate.collapseAll";

    private static NavigatorToolbarCustomizer instance;
    private IPartListener2 partListener;

    private NavigatorToolbarCustomizer() {
    }

    public static synchronized NavigatorToolbarCustomizer getInstance() {
        if (instance == null) {
            instance = new NavigatorToolbarCustomizer();
        }
        return instance;
    }

    /**
     * Initialize the customizer to listen for Navigator view activation.
     */
    public void initialize() {
        // Create part listener
        partListener = new IPartListener2() {
            @Override
            public void partOpened(IWorkbenchPartReference partRef) {
                if (NAVIGATOR_VIEW_ID.equals(partRef.getId())) {
                    hideCollapseAllButton(partRef);
                }
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef) {
                // Also check on activation in case we missed the open
                if (NAVIGATOR_VIEW_ID.equals(partRef.getId())) {
                    hideCollapseAllButton(partRef);
                }
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference partRef) {
            }

            @Override
            public void partClosed(IWorkbenchPartReference partRef) {
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference partRef) {
            }

            @Override
            public void partHidden(IWorkbenchPartReference partRef) {
            }

            @Override
            public void partVisible(IWorkbenchPartReference partRef) {
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference partRef) {
            }
        };

        // Register listener on all windows
        IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
        for (IWorkbenchWindow window : windows) {
            registerOnWindow(window);
        }

        // Also listen for new windows
        PlatformUI.getWorkbench().addWindowListener(new org.eclipse.ui.IWindowListener() {
            @Override
            public void windowOpened(IWorkbenchWindow window) {
                registerOnWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window) {
            }

            @Override
            public void windowActivated(IWorkbenchWindow window) {
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window) {
            }
        });

        // Check if Navigator is already open
        for (IWorkbenchWindow window : windows) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                IViewPart view = page.findView(NAVIGATOR_VIEW_ID);
                if (view != null) {
                    hideCollapseAllButtonFromView(view);
                }
            }
        }
    }

    private void registerOnWindow(IWorkbenchWindow window) {
        window.addPageListener(new IPageListener() {
            @Override
            public void pageOpened(IWorkbenchPage page) {
                page.addPartListener(partListener);
            }

            @Override
            public void pageClosed(IWorkbenchPage page) {
                page.removePartListener(partListener);
            }

            @Override
            public void pageActivated(IWorkbenchPage page) {
            }
        });

        // Register on existing pages
        for (IWorkbenchPage page : window.getPages()) {
            page.addPartListener(partListener);
        }
    }

    private void hideCollapseAllButton(IWorkbenchPartReference partRef) {
        var part = partRef.getPart(false);
        if (part instanceof IViewPart viewPart) {
            hideCollapseAllButtonFromView(viewPart);
        }
    }

    private void hideCollapseAllButtonFromView(IViewPart viewPart) {
        if (!(viewPart instanceof CommonNavigator navigator)) {
            return;
        }

        var viewSite = navigator.getViewSite();
        if (viewSite == null) {
            return;
        }

        var actionBars = viewSite.getActionBars();
        if (actionBars == null) {
            return;
        }

        IToolBarManager toolBarManager = actionBars.getToolBarManager();
        if (toolBarManager == null) {
            return;
        }

        // Find and hide the standard Collapse All button by iterating through items
        // The CollapseAllAction has actionDefinitionId = "org.eclipse.ui.navigate.collapseAll"
        IContributionItem[] items = toolBarManager.getItems();
        for (IContributionItem item : items) {
            if (item instanceof ActionContributionItem actionItem) {
                IAction action = actionItem.getAction();
                if (action != null && COLLAPSE_ALL_ACTION_DEF_ID.equals(action.getActionDefinitionId())) {
                    item.setVisible(false);
                    toolBarManager.update(true);
                    actionBars.updateActionBars();
                    break;
                }
            }
        }
    }

    /**
     * Dispose the customizer and remove listeners.
     */
    public void dispose() {
        // Listeners will be cleaned up when the workbench closes
        partListener = null;
        instance = null;
    }
}
