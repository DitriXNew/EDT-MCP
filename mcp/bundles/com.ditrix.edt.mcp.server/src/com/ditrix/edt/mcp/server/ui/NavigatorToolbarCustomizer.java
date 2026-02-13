/**
 * Copyright (c) 2025, Dmitriy Safonov
 * https://github.com/desfarik/
 * 
 * Utility class to hide the standard Collapse All button from EDT Navigator toolbar.
 * This allows us to use our own Collapse All button with proper positioning.
 */
package com.ditrix.edt.mcp.server.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IContributionItem;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Manager that hides the standard Collapse All button from EDT Navigator.
 */
public class NavigatorToolbarCustomizer {

    private static final String COLLAPSE_ALL_ACTION_DEF_ID = "org.eclipse.ui.navigate.collapseAll";

    private static NavigatorToolbarCustomizer instance;
    private IPartListener2 partListener;
    private IWindowListener windowListener;
    private Map<IWorkbenchWindow, IPageListener> pageListeners = new HashMap<>();
    private List<IWorkbenchPage> registeredPages = new ArrayList<>();
    private boolean initialized = false;

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
        if (initialized) {
            return;
        }
        initialized = true;
        // Create part listener
        partListener = new IPartListener2() {
            @Override
            public void partOpened(IWorkbenchPartReference partRef) {
                if (TagConstants.NAVIGATOR_VIEW_ID.equals(partRef.getId())) {
                    hideCollapseAllButton(partRef);
                }
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef) {
                // Also check on activation in case we missed the open
                if (TagConstants.NAVIGATOR_VIEW_ID.equals(partRef.getId())) {
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
        windowListener = new IWindowListener() {
            @Override
            public void windowOpened(IWorkbenchWindow window) {
                registerOnWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window) {
                unregisterFromWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window) {
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window) {
            }
        };
        PlatformUI.getWorkbench().addWindowListener(windowListener);

        // Check if Navigator is already open
        for (IWorkbenchWindow window : windows) {
            IWorkbenchPage page = window.getActivePage();
            if (page != null) {
                IViewPart view = page.findView(TagConstants.NAVIGATOR_VIEW_ID);
                if (view != null) {
                    hideCollapseAllButtonFromView(view);
                }
            }
        }
    }

    private void registerOnWindow(IWorkbenchWindow window) {
        IPageListener pageListener = new IPageListener() {
            @Override
            public void pageOpened(IWorkbenchPage page) {
                page.addPartListener(partListener);
                registeredPages.add(page);
            }

            @Override
            public void pageClosed(IWorkbenchPage page) {
                page.removePartListener(partListener);
                registeredPages.remove(page);
            }

            @Override
            public void pageActivated(IWorkbenchPage page) {
            }
        };
        window.addPageListener(pageListener);
        pageListeners.put(window, pageListener);

        // Register on existing pages
        for (IWorkbenchPage page : window.getPages()) {
            page.addPartListener(partListener);
            registeredPages.add(page);
        }
    }

    private void unregisterFromWindow(IWorkbenchWindow window) {
        IPageListener pageListener = pageListeners.remove(window);
        if (pageListener != null) {
            window.removePageListener(pageListener);
        }
        // Pages from this window will be automatically removed when closed
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
     * Dispose the customizer and remove all listeners.
     */
    public void dispose() {
        // Remove window listener
        if (windowListener != null) {
            try {
                PlatformUI.getWorkbench().removeWindowListener(windowListener);
            } catch (Exception e) {
                // Workbench may be closing
            }
            windowListener = null;
        }

        // Remove page listeners
        for (Map.Entry<IWorkbenchWindow, IPageListener> entry : pageListeners.entrySet()) {
            try {
                entry.getKey().removePageListener(entry.getValue());
            } catch (Exception e) {
                // Window may be closed
            }
        }
        pageListeners.clear();

        // Remove part listeners from registered pages
        if (partListener != null) {
            for (IWorkbenchPage page : registeredPages) {
                try {
                    page.removePartListener(partListener);
                } catch (Exception e) {
                    // Page may be closed
                }
            }
            registeredPages.clear();
        }

        partListener = null;
        initialized = false;
        instance = null;
    }
}
