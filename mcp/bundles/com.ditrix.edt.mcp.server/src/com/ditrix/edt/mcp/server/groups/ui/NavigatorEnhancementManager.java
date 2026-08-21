/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPageListener;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.ICommonFilterDescriptor;
import org.eclipse.ui.navigator.INavigatorActivationService;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.navigator.INavigatorFilterService;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Applies the Enhance Navigator preference to every EDT Navigator content service.
 */
public final class NavigatorEnhancementManager
{
    private static final String GROUPS_CONTENT_ID =
        "com.ditrix.edt.mcp.server.groups.navigatorContent"; //$NON-NLS-1$
    private static final String GROUPED_OBJECTS_FILTER_ID =
        "com.ditrix.edt.mcp.server.groups.groupedObjectsFilter"; //$NON-NLS-1$

    // The tag search filter is intentionally excluded: it is inert until a search starts with #.
    private static final String[] GROUPS_CONTENT_EXTENSION_IDS = {
        GROUPS_CONTENT_ID
    };

    private static NavigatorEnhancementManager instance;

    private final Map<IWorkbenchWindow, IPageListener> pageListeners = new HashMap<>();
    private final Set<IWorkbenchPage> registeredPages = new HashSet<>();

    private IPartListener2 partListener;
    private IWindowListener windowListener;
    private IPropertyChangeListener preferenceListener;
    private IPreferenceStore store;
    private Display display;
    private volatile boolean initialized;
    private boolean disposePending;

    private NavigatorEnhancementManager()
    {
    }

    public static synchronized NavigatorEnhancementManager getInstance()
    {
        if (instance == null)
        {
            instance = new NavigatorEnhancementManager();
        }
        return instance;
    }

    /**
     * Starts preference and workbench listeners. Must be called on the UI thread.
     */
    public synchronized void initialize()
    {
        if (initialized || disposePending)
        {
            return;
        }

        display = Display.getCurrent();
        if (display == null || display.isDisposed())
        {
            return;
        }

        store = Activator.getDefault().getPreferenceStore();
        initialized = true;
        createListeners();
        store.addPropertyChangeListener(preferenceListener);

        IWorkbench workbench = PlatformUI.getWorkbench();
        IWorkbenchWindow[] windows = workbench.getWorkbenchWindows();
        for (IWorkbenchWindow window : windows)
        {
            registerWindow(window);
        }
        workbench.addWindowListener(windowListener);
        applyToOpenNavigators();
    }

    private void createListeners()
    {
        partListener = new IPartListener2()
        {
            @Override
            public void partOpened(IWorkbenchPartReference partRef)
            {
                apply(partRef);
            }

            @Override
            public void partActivated(IWorkbenchPartReference partRef)
            {
                apply(partRef);
            }

            @Override
            public void partBroughtToTop(IWorkbenchPartReference partRef)
            {
                // Not needed.
            }

            @Override
            public void partClosed(IWorkbenchPartReference partRef)
            {
                // Not needed.
            }

            @Override
            public void partDeactivated(IWorkbenchPartReference partRef)
            {
                // Not needed.
            }

            @Override
            public void partHidden(IWorkbenchPartReference partRef)
            {
                // Not needed.
            }

            @Override
            public void partVisible(IWorkbenchPartReference partRef)
            {
                apply(partRef);
            }

            @Override
            public void partInputChanged(IWorkbenchPartReference partRef)
            {
                // Not needed.
            }
        };

        windowListener = new IWindowListener()
        {
            @Override
            public void windowOpened(IWorkbenchWindow window)
            {
                registerWindow(window);
            }

            @Override
            public void windowClosed(IWorkbenchWindow window)
            {
                unregisterWindow(window);
            }

            @Override
            public void windowActivated(IWorkbenchWindow window)
            {
                // Not needed.
            }

            @Override
            public void windowDeactivated(IWorkbenchWindow window)
            {
                // Not needed.
            }
        };

        preferenceListener = event -> {
            if (PreferenceConstants.PREF_ENHANCE_NAVIGATOR.equals(event.getProperty()))
            {
                scheduleApply();
            }
        };
    }

    private void registerWindow(IWorkbenchWindow window)
    {
        if (pageListeners.containsKey(window))
        {
            return;
        }

        IPageListener pageListener = new IPageListener()
        {
            @Override
            public void pageOpened(IWorkbenchPage page)
            {
                registerPage(page);
                apply(page);
            }

            @Override
            public void pageClosed(IWorkbenchPage page)
            {
                unregisterPage(page);
            }

            @Override
            public void pageActivated(IWorkbenchPage page)
            {
                apply(page);
            }
        };

        pageListeners.put(window, pageListener);
        window.addPageListener(pageListener);
        for (IWorkbenchPage page : window.getPages())
        {
            registerPage(page);
            apply(page);
        }
    }

    private void unregisterWindow(IWorkbenchWindow window)
    {
        IPageListener pageListener = pageListeners.remove(window);
        if (pageListener != null)
        {
            window.removePageListener(pageListener);
        }
        for (IWorkbenchPage page : window.getPages())
        {
            unregisterPage(page);
        }
    }

    private void registerPage(IWorkbenchPage page)
    {
        if (registeredPages.add(page))
        {
            page.addPartListener(partListener);
        }
    }

    private void unregisterPage(IWorkbenchPage page)
    {
        if (registeredPages.remove(page))
        {
            page.removePartListener(partListener);
        }
    }

    private void scheduleApply()
    {
        Display uiDisplay = display;
        if (uiDisplay == null || uiDisplay.isDisposed())
        {
            return;
        }
        if (Display.getCurrent() == uiDisplay)
        {
            applyToOpenNavigators();
        }
        else
        {
            uiDisplay.asyncExec(() -> {
                if (initialized)
                {
                    applyToOpenNavigators();
                }
            });
        }
    }

    private void applyToOpenNavigators()
    {
        for (IWorkbenchWindow window : pageListeners.keySet())
        {
            for (IWorkbenchPage page : window.getPages())
            {
                apply(page);
            }
        }
    }

    private void apply(IWorkbenchPartReference partRef)
    {
        if (!TagConstants.NAVIGATOR_VIEW_ID.equals(partRef.getId()))
        {
            return;
        }
        if (partRef.getPart(false) instanceof CommonNavigator navigator)
        {
            apply(navigator);
        }
    }

    private void apply(IWorkbenchPage page)
    {
        IViewPart view = page.findView(TagConstants.NAVIGATOR_VIEW_ID);
        if (view instanceof CommonNavigator navigator)
        {
            apply(navigator);
        }
    }

    private void apply(CommonNavigator navigator)
    {
        if (!initialized)
        {
            return;
        }

        try
        {
            applyPreference(store, navigator.getNavigatorContentService(),
                navigator.getCommonViewer());
        }
        catch (RuntimeException e)
        {
            Activator.logError("Failed to apply Enhance Navigator preference", e); //$NON-NLS-1$
        }
    }

    /**
     * Applies the preference through CNF's public content and filter APIs.
     */
    static void applyPreference(IPreferenceStore preferenceStore,
        INavigatorContentService contentService, StructuredViewer viewer)
    {
        if (contentService == null)
        {
            return;
        }

        boolean enabled = isEnabled(preferenceStore);
        INavigatorActivationService activationService = contentService.getActivationService();
        boolean changed = applyContentPreference(activationService, enabled);

        INavigatorFilterService filterService = contentService.getFilterService();
        if (applyFilterPreference(filterService, viewer, enabled))
        {
            changed = true;
        }

        if (changed)
        {
            // The plugin preference is the source of truth; do not persist duplicate CNF state.
            contentService.update();
        }
    }

    private static boolean applyContentPreference(INavigatorActivationService activationService,
        boolean enabled)
    {
        if (activationService == null || !requiresContentChange(activationService, enabled))
        {
            return false;
        }

        String[] extensionIds = GROUPS_CONTENT_EXTENSION_IDS.clone();
        if (enabled)
        {
            activationService.activateExtensions(extensionIds, false);
        }
        else
        {
            activationService.deactivateExtensions(extensionIds, false);
        }
        return true;
    }

    private static boolean requiresContentChange(INavigatorActivationService activationService,
        boolean enabled)
    {
        for (String extensionId : GROUPS_CONTENT_EXTENSION_IDS)
        {
            if (activationService.isNavigatorExtensionActive(extensionId) != enabled)
            {
                return true;
            }
        }
        return false;
    }

    private static boolean applyFilterPreference(INavigatorFilterService filterService,
        StructuredViewer viewer, boolean enabled)
    {
        if (filterService == null
            || filterService.isActive(GROUPED_OBJECTS_FILTER_ID) == enabled)
        {
            return false;
        }

        // setActiveFilterIds replaces the complete set, so retain every active bound filter.
        Set<String> activeFilterIds = new LinkedHashSet<>();
        ICommonFilterDescriptor groupedObjectsDescriptor = null;
        ICommonFilterDescriptor[] descriptors = filterService.getVisibleFilterDescriptors();
        if (descriptors != null)
        {
            for (ICommonFilterDescriptor descriptor : descriptors)
            {
                if (descriptor != null)
                {
                    String filterId = descriptor.getId();
                    if (GROUPED_OBJECTS_FILTER_ID.equals(filterId))
                    {
                        groupedObjectsDescriptor = descriptor;
                    }
                    if (filterId != null && filterService.isActive(filterId))
                    {
                        activeFilterIds.add(filterId);
                    }
                }
            }
        }

        ViewerFilter groupedObjectsFilter = null;
        if (viewer != null)
        {
            if (groupedObjectsDescriptor == null)
            {
                return false;
            }
            groupedObjectsFilter = filterService.getViewerFilter(groupedObjectsDescriptor);
            if (groupedObjectsFilter == null)
            {
                return false;
            }
        }

        if (enabled)
        {
            activeFilterIds.add(GROUPED_OBJECTS_FILTER_ID);
        }
        else
        {
            activeFilterIds.remove(GROUPED_OBJECTS_FILTER_ID);
        }

        // The convenience update method persists CNF state, so update state and viewer separately.
        filterService.setActiveFilterIds(activeFilterIds.toArray(String[]::new));
        if (viewer != null)
        {
            if (enabled)
            {
                viewer.addFilter(groupedObjectsFilter);
            }
            else
            {
                viewer.removeFilter(groupedObjectsFilter);
            }
        }
        return true;
    }

    /**
     * Resolves the effective preference, including the TRUE default for an uninitialized store.
     */
    static boolean isEnabled(IPreferenceStore preferenceStore)
    {
        if (preferenceStore == null
            || !preferenceStore.contains(PreferenceConstants.PREF_ENHANCE_NAVIGATOR))
        {
            return PreferenceConstants.DEFAULT_ENHANCE_NAVIGATOR;
        }
        return preferenceStore.getBoolean(PreferenceConstants.PREF_ENHANCE_NAVIGATOR);
    }

    /** Removes workbench and preference listeners. */
    public void dispose()
    {
        Display uiDisplay;
        IPreferenceStore preferenceStore;
        IPropertyChangeListener propertyChangeListener;
        synchronized (this)
        {
            if (!initialized || disposePending)
            {
                return;
            }
            initialized = false;
            disposePending = true;
            uiDisplay = display;
            preferenceStore = store;
            propertyChangeListener = preferenceListener;
        }

        clearInstance(this);
        if (preferenceStore != null && propertyChangeListener != null)
        {
            preferenceStore.removePropertyChangeListener(propertyChangeListener);
        }

        if (uiDisplay == null || uiDisplay.isDisposed())
        {
            clearReferences();
        }
        else if (Display.getCurrent() == uiDisplay)
        {
            disposeWorkbenchListeners();
        }
        else
        {
            try
            {
                uiDisplay.asyncExec(this::disposeWorkbenchListeners);
            }
            catch (SWTException e)
            {
                // The display was disposed between the check and asyncExec.
                clearReferences();
            }
        }
    }

    private void disposeWorkbenchListeners()
    {
        try
        {
            IWorkbench workbench = PlatformUI.getWorkbench();
            if (windowListener != null)
            {
                workbench.removeWindowListener(windowListener);
            }
        }
        catch (RuntimeException e)
        {
            // Workbench may be closing.
        }

        for (Map.Entry<IWorkbenchWindow, IPageListener> entry : pageListeners.entrySet())
        {
            try
            {
                entry.getKey().removePageListener(entry.getValue());
            }
            catch (RuntimeException e)
            {
                // Window may be closing.
            }
        }
        pageListeners.clear();

        for (IWorkbenchPage page : registeredPages)
        {
            try
            {
                page.removePartListener(partListener);
            }
            catch (RuntimeException e)
            {
                // Page may be closing.
            }
        }
        registeredPages.clear();

        clearReferences();
    }

    private synchronized void clearReferences()
    {
        pageListeners.clear();
        registeredPages.clear();
        partListener = null;
        windowListener = null;
        preferenceListener = null;
        store = null;
        display = null;
        disposePending = false;
    }

    private static synchronized void clearInstance(NavigatorEnhancementManager manager)
    {
        if (instance == manager)
        {
            instance = null;
        }
    }
}
