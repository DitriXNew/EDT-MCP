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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.jface.viewers.AbstractTreeViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

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
    private volatile boolean isInTagSearchMode = false;
    private java.util.Timer stateProviderMonitor;
    private volatile String activeSetupPattern = null;  // Tracks which pattern setup is running for
    
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
        stopStateProviderMonitoring();
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
     * 
     * NEW SIMPLER APPROACH:
     * 1. Wait for the Navigator's searchPerformer to finish (it runs in background)
     * 2. Then deactivate StateProvider so content providers return ALL children
     * 3. Deactivate standard NavigatorSearchFilter so it doesn't filter anything
     * 4. Activate our TagSearchFilter to filter by tag
     * 
     * Key insight: When isActive()==false, content providers ignore Trie and return all children.
     * Our TagSearchFilter then filters those children by tag.
     */
    private void handlePatternChange(String pattern) {
        try {
            CommonNavigator navigator = getNavigator();
            if (navigator == null) {
                return;
            }

            if (pattern.startsWith("#")) {
                // Tag search mode
                isInTagSearchMode = true;
                String tagPattern = pattern.substring(1).trim();
                
                if (tagPattern.isEmpty()) {
                    return;
                }
                
                // Pre-calculate matching FQNs for the filter to use
                // This is done synchronously so the filter has data when it runs
                TagTrieStateProvider tagTrieProvider = TagTrieStateProvider.getInstance();
                tagTrieProvider.buildAndActivate(tagPattern);
                
                // Schedule the filter setup to run AFTER the Navigator's searchPerformer
                // The searchPerformer runs in a background job, so we use a delayed execution
                scheduleTagSearchSetup(navigator, pattern);
                
            } else {
                // Normal mode - restore standard filter if we deactivated it
                isInTagSearchMode = false;
                activeSetupPattern = null;
                stopStateProviderMonitoring();
                
                // Deactivate our tag search
                TagTrieStateProvider.getInstance().deactivate();
                
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
     * Schedules tag search setup to run after the Navigator's searchPerformer finishes.
     * This ensures our setup doesn't get overwritten.
     */
    private void scheduleTagSearchSetup(CommonNavigator navigator, String pattern) {
        stopStateProviderMonitoring(); // Cancel any previous setup
        
        // Track which pattern this setup is for
        activeSetupPattern = pattern;
        
        final String setupPattern = pattern; // Capture for lambda
        
        // Use a timer to wait for searchPerformer to complete
        stateProviderMonitor = new java.util.Timer("TagSearchSetup", true);
        
        java.util.TimerTask setupTask = new java.util.TimerTask() {
            @Override
            public void run() {
                // Check if pattern changed or not in tag search mode BEFORE doing anything
                if (!isInTagSearchMode || !setupPattern.equals(activeSetupPattern)) {
                    stopStateProviderMonitoring();
                    return;
                }
                
                // Use syncExec to ensure we complete before timer fires again
                Display.getDefault().syncExec(() -> {
                    try {
                        // Double-check pattern is still current
                        if (!setupPattern.equals(activeSetupPattern) || !isInTagSearchMode) {
                            return;
                        }
                        
                        INavigatorContentProviderStateProvider stateProvider =
                            Activator.getDefault().getNavigatorStateProvider();
                        
                        if (stateProvider == null) {
                            return;
                        }
                        
                        Activator.logInfo("Setup running for: " + setupPattern);
                        
                        // CRITICAL: Ensure StateProvider is INACTIVE
                        // When inactive, content providers return ALL children (no Trie filtering)
                        if (stateProvider.isActive()) {
                            stateProvider.setActive(false);
                        }
                        
                        // Deactivate standard filter
                        deactivateStandardFilter(navigator);
                        
                        // Activate our tag filter
                        activateTagFilter(navigator);
                        
                        // Dynamic tree expansion like EDT does in UISearchHelper
                        expandTreeDynamically(navigator.getCommonViewer());
                        
                    } catch (Exception e) {
                        Activator.logError("Error in tag search setup", e);
                    }
                });
                
                // Always stop after first execution - no retry needed
                stopStateProviderMonitoring();
            }
        };
        
        // Single delayed execution - wait for searchPerformer to finish
        // searchPerformer typically takes 200-500ms
        stateProviderMonitor.schedule(setupTask, 400);
    }
    
    /**
     * Expands the tree dynamically similar to EDT's UISearchHelper.expandTreeViewerStepByStep().
     * First expands to level 2, then collects level 2 items and fully expands each.
     * 
     * This ensures all nested matching objects are visible regardless of depth.
     */
    private void expandTreeDynamically(CommonViewer viewer) {
        try {
            // Collapse all first to ensure fresh state
            viewer.collapseAll();
            
            // Refresh tree to apply filter changes
            viewer.refresh();
            
            // Process pending UI events to ensure refresh is complete
            Display.getCurrent().update();
            
            // First expand to level 2 to show basic structure
            viewer.expandToLevel(2, true);
            
            // Collect all items at level 2 (children of root items)
            List<TreeItem> level2Items = new ArrayList<>();
            TreeItem[] rootItems = viewer.getTree().getItems();
            Stream.of(rootItems).forEach(rootItem -> 
                Stream.of(rootItem.getItems()).forEach(level2Items::add)
            );
            
            // Fully expand each level 2 item (ALL_LEVELS = -1)
            for (TreeItem item : level2Items) {
                Object data = item.getData();
                if (data != null) {
                    viewer.expandToLevel(data, AbstractTreeViewer.ALL_LEVELS, true);
                }
            }
        } catch (Exception e) {
            Activator.logError("Error in dynamic tree expansion", e);
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
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate standard filter", e);
        }
    }
    
    /**
     * Activates the tag search filter.
     * First "warms up" the filter by calling select() directly, then activates it.
     */
    private void activateTagFilter(CommonNavigator navigator) {
        try {
            var filterService = navigator.getNavigatorContentService().getFilterService();
            
            // "Warm up" the filter by calling select() directly - this pre-calculates matchingFqns
            var descriptors = filterService.getVisibleFilterDescriptors();
            for (var desc : descriptors) {
                if (TAG_SEARCH_FILTER_ID.equals(desc.getId())) {
                    var filter = filterService.getViewerFilter(desc);
                    if (filter != null) {
                        // Pre-calculate by calling select with dummy element
                        filter.select(navigator.getCommonViewer(), null, new Object());
                    }
                    break;
                }
            }

            if (!filterService.isActive(TAG_SEARCH_FILTER_ID)) {
                NavigatorUtil.activateOrRefreshFilter(navigator, TAG_SEARCH_FILTER_ID);
            } else {
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
            }
        } catch (Exception e) {
            Activator.logError("Failed to deactivate tag filter", e);
        }
    }

    /**
     * Stops the tag search setup timer.
     */
    private void stopStateProviderMonitoring() {
        if (stateProviderMonitor != null) {
            stateProviderMonitor.cancel();
            stateProviderMonitor = null;
        }
    }
}
