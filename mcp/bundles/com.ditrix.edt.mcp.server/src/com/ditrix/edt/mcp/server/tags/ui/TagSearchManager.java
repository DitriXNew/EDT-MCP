/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags.ui;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;

import com._1c.g5.v8.dt.navigator.providers.INavigatorContentProviderStateProvider;
import com._1c.g5.v8.dt.navigator.util.NavigatorUtil;
import com.ditrix.edt.mcp.server.Activator;

/**
 * Manages tag-based search in the navigator.
 * When search pattern starts with #, this manager:
 * 1. Deactivates the standard NavigatorSearchFilter
 * 2. Deactivates the NavigatorContentProviderStateManager (so content providers show all children)
 * 3. Activates our TagSearchFilter to filter by tag
 */
public class TagSearchManager implements IPreferenceChangeListener {
    
    private static final String DT_NAVIGATOR_PREFERENCES_NODE = "com._1c.g5.v8.dt.navigator.ui.navigator";
    private static final String SEARCH_HISTORY_NODE = "searchHistory";
    private static final String ACTIVE_PATTERN_KEY = "activePattern";
    private static final String NAVIGATOR_SEARCH_FILTER_ID = 
        "com._1c.g5.v8.dt.internal.navigator.ui.filters.NavigatorSearchFilter";
    private static final String TAG_SEARCH_FILTER_ID = 
        "com.ditrix.edt.mcp.server.tags.TagSearchFilter";
    
    private static TagSearchManager instance;
    private IEclipsePreferences searchHistoryNode;
    private boolean standardFilterWasActive = false;
    private boolean wasStateProviderActive = false;
    
    private TagSearchManager() {
    }
    
    /**
     * Gets the singleton instance.
     */
    public static synchronized TagSearchManager getInstance() {
        if (instance == null) {
            instance = new TagSearchManager();
        }
        return instance;
    }
    
    /**
     * Starts listening for search pattern changes.
     */
    public void start() {
        try {
            IEclipsePreferences root = (IEclipsePreferences) Platform.getPreferencesService()
                .getRootNode().node("instance");
            IEclipsePreferences navigatorNode = (IEclipsePreferences) root.node(DT_NAVIGATOR_PREFERENCES_NODE);
            searchHistoryNode = (IEclipsePreferences) navigatorNode.node(SEARCH_HISTORY_NODE);
            searchHistoryNode.addPreferenceChangeListener(this);
            Activator.logInfo("TagSearchManager started, listening for pattern changes");
        } catch (Exception e) {
            Activator.logError("Failed to start TagSearchManager", e);
        }
    }
    
    /**
     * Stops listening and cleans up.
     */
    public void stop() {
        if (searchHistoryNode != null) {
            searchHistoryNode.removePreferenceChangeListener(this);
            searchHistoryNode = null;
        }
        Activator.logInfo("TagSearchManager stopped");
    }
    
    @Override
    public void preferenceChange(PreferenceChangeEvent event) {
        if (!ACTIVE_PATTERN_KEY.equals(event.getKey())) {
            return;
        }
        
        String newPattern = event.getNewValue() != null ? event.getNewValue().toString() : "";
        
        Activator.logInfo("Search pattern changed: " + newPattern);
        
        // Run on UI thread SYNCHRONOUSLY to ensure filter is deactivated before tree refresh
        Display display = Display.getDefault();
        if (display.getThread() == Thread.currentThread()) {
            // Already on UI thread
            handlePatternChange(newPattern);
        } else {
            // Not on UI thread - use syncExec to wait for completion
            display.syncExec(() -> handlePatternChange(newPattern));
        }
    }
    
    /**
     * Handles pattern change - enables/disables filters as needed.
     */
    private void handlePatternChange(String pattern) {
        try {
            CommonNavigator navigator = getNavigator();
            if (navigator == null) {
                return;
            }
            
            if (pattern.startsWith("#")) {
                // Tag search mode - deactivate standard filter and state provider, activate our filter
                deactivateStandardFilter(navigator);
                deactivateNavigatorStateProvider();
                activateTagFilter(navigator);
            } else {
                // Normal mode - restore standard filter if we deactivated it
                if (standardFilterWasActive || wasStateProviderActive) {
                    // The searchPerformer will handle activating standard filter
                    // We just need to deactivate our filter
                    deactivateTagFilter(navigator);
                    // Note: State provider will be reactivated by NavigatorSearchFilter when needed
                    standardFilterWasActive = false;
                    wasStateProviderActive = false;
                }
            }
        } catch (Exception e) {
            Activator.logError("Error handling pattern change", e);
        }
    }
    
    /**
     * Gets the EDT navigator.
     */
    private CommonNavigator getNavigator() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null && window.getActivePage() != null) {
                var view = window.getActivePage().findView("com._1c.g5.v8.dt.ui2.navigator");
                if (view instanceof CommonNavigator) {
                    return (CommonNavigator) view;
                }
            }
        } catch (Exception e) {
            Activator.logError("Failed to get navigator", e);
        }
        return null;
    }
    
    /**
     * Deactivates the standard NavigatorSearchFilter.
     */
    private void deactivateStandardFilter(CommonNavigator navigator) {
        try {
            var filterService = navigator.getNavigatorContentService().getFilterService();
            
            if (filterService.isActive(NAVIGATOR_SEARCH_FILTER_ID)) {
                standardFilterWasActive = true;
                NavigatorUtil.deactivateFilter(navigator, NAVIGATOR_SEARCH_FILTER_ID);
                Activator.logInfo("Deactivated standard search filter");
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate standard filter", e);
        }
    }
    
    /**
     * Deactivates the NavigatorContentProviderStateProvider.
     * This is critical - it controls what children content providers return.
     * When active, content providers use the trie to filter children.
     * When inactive, content providers return all children (which we then filter).
     */
    private void deactivateNavigatorStateProvider() {
        try {
            INavigatorContentProviderStateProvider stateProvider = 
                Activator.getDefault().getNavigatorStateProvider();
            
            if (stateProvider != null) {
                if (stateProvider.isActive()) {
                    wasStateProviderActive = true;
                    stateProvider.setActive(false);
                    Activator.logInfo("Deactivated NavigatorContentProviderStateProvider");
                } else {
                    Activator.logInfo("NavigatorContentProviderStateProvider was already inactive");
                }
            } else {
                Activator.logInfo("NavigatorContentProviderStateProvider service not available");
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate navigator state provider", e);
        }
    }
    
    /**
     * Activates the tag search filter.
     */
    private void activateTagFilter(CommonNavigator navigator) {
        try {
            var filterService = navigator.getNavigatorContentService().getFilterService();
            
            Activator.logInfo("Attempting to activate tag filter, current active: " + filterService.isActive(TAG_SEARCH_FILTER_ID));
            
            if (!filterService.isActive(TAG_SEARCH_FILTER_ID)) {
                NavigatorUtil.activateOrRefreshFilter(navigator, TAG_SEARCH_FILTER_ID);
                Activator.logInfo("Activated tag search filter");
            } else {
                Activator.logInfo("Tag filter already active, refreshing");
                navigator.getCommonViewer().refresh();
            }
        } catch (Exception e) {
            Activator.logError("Failed to activate tag filter", e);
        }
    }
    
    /**
     * Deactivates the tag search filter.
     */
    private void deactivateTagFilter(CommonNavigator navigator) {
        try {
            var filterService = navigator.getNavigatorContentService().getFilterService();
            
            if (filterService.isActive(TAG_SEARCH_FILTER_ID)) {
                NavigatorUtil.deactivateFilter(navigator, TAG_SEARCH_FILTER_ID);
                Activator.logInfo("Deactivated tag search filter");
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate tag filter", e);
        }
    }
}
