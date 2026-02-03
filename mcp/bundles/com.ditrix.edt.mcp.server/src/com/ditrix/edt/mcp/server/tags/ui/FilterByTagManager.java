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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.State;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.RegistryToggleState;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.TagConstants;
import com.ditrix.edt.mcp.server.tags.model.Tag;

/**
 * Manager for filter by tag functionality.
 * Handles activating/deactivating the tag filter on the navigator
 * and stores the selected tags.
 */
public class FilterByTagManager {
    
    private static FilterByTagManager instance;
    
    /** Currently selected tags per project */
    private Map<IProject, Set<Tag>> selectedTags = new HashMap<>();
    
    /** Whether filter is currently active */
    private boolean isFilterActive = false;
    
    /** Whether to show only untagged objects */
    private boolean showUntaggedOnly = false;
    
    /** The filter instance */
    private TagSearchFilter tagFilter;
    
    private FilterByTagManager() {
        // Singleton - reset toggle state on creation (fresh start)
        resetToggleState();
    }
    
    /**
     * Resets the toggle state of the filter command button to off.
     * Called at startup to ensure button state matches actual filter state.
     */
    private void resetToggleState() {
        // Delay execution to ensure command service is available
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            updateToggleState(false);
        });
    }
    
    /**
     * Gets the singleton instance.
     */
    public static synchronized FilterByTagManager getInstance() {
        if (instance == null) {
            instance = new FilterByTagManager();
        }
        return instance;
    }
    
    /**
     * Opens the filter by tag dialog.
     */
    public void openFilterDialog() {
        Display.getDefault().asyncExec(() -> {
            try {
                Shell shell = getActiveShell();
                if (shell == null) {
                    return;
                }
                
                IV8ProjectManager projectManager = getV8ProjectManager();
                if (projectManager == null) {
                    Activator.logError("V8ProjectManager not available", null);
                    return;
                }
                
                FilterByTagDialog dialog = new FilterByTagDialog(shell, projectManager);
                dialog.setInitialSelection(selectedTags);
                dialog.setInitialShowUntaggedOnly(showUntaggedOnly);
                dialog.open();
                
                if (dialog.isFilterEnabled()) {
                    // Apply new filter
                    selectedTags = dialog.getSelectedTags();
                    showUntaggedOnly = dialog.isShowUntaggedOnly();
                    if (!selectedTags.isEmpty() || showUntaggedOnly) {
                        activateFilter();
                    } else {
                        deactivateFilter();
                    }
                } else if (dialog.isTurnedOff()) {
                    // Turn off filter
                    selectedTags.clear();
                    showUntaggedOnly = false;
                    deactivateFilter();
                }
                // else: Cancel - do nothing
                
            } catch (Exception e) {
                Activator.logError("Error opening filter by tag dialog", e);
            }
        });
    }
    
    /**
     * Returns whether filter is currently active.
     */
    public boolean isFilterActive() {
        return isFilterActive;
    }
    
    /**
     * Gets the currently selected tags.
     */
    public Map<IProject, Set<Tag>> getSelectedTags() {
        return new HashMap<>(selectedTags);
    }
    
    /**
     * Gets selected tag names for a specific project.
     */
    public Set<String> getSelectedTagNames(IProject project) {
        Set<Tag> tags = selectedTags.get(project);
        if (tags == null || tags.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (Tag tag : tags) {
            names.add(tag.getName());
        }
        return names;
    }
    
    /**
     * Checks if a tag is selected for a project.
     */
    public boolean isTagSelected(IProject project, String tagName) {
        Set<Tag> tags = selectedTags.get(project);
        if (tags == null) {
            return false;
        }
        for (Tag tag : tags) {
            if (tag.getName().equals(tagName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Activates the tag filter on the navigator.
     */
    private void activateFilter() {
        CommonNavigator navigator = getNavigator();
        if (navigator == null) {
            return;
        }
        
        CommonViewer viewer = navigator.getCommonViewer();
        if (viewer == null) {
            return;
        }
        
        // Calculate matching FQNs based on selected tags
        if (tagFilter == null) {
            tagFilter = new TagSearchFilter();
        }
        
        if (showUntaggedOnly) {
            tagFilter.setShowUntaggedOnly(true);
        } else {
            tagFilter.setSelectedTagsMode(selectedTags);
        }
        
        // Add filter to viewer if not already added
        ViewerFilter[] filters = viewer.getFilters();
        boolean hasFilter = false;
        for (ViewerFilter f : filters) {
            if (f == tagFilter) {
                hasFilter = true;
                break;
            }
        }
        
        if (!hasFilter) {
            viewer.addFilter(tagFilter);
        }
        
        isFilterActive = true;
        
        // Update toggle button state
        updateToggleState(true);
        
        // Refresh the tree
        viewer.refresh();
        
        // Expand tree to show matched objects
        // Use limited depth to avoid UI freeze on large configurations
        try {
            viewer.expandToLevel(3);
        } catch (Exception e) {
            // Ignore - may fail if project is being cleaned/closed
        }
        
        Activator.logInfo("Tag filter activated with " + countSelectedTags() + " tags selected");
    }
    
    /**
     * Deactivates the tag filter from the navigator.
     */
    private void deactivateFilter() {
        CommonNavigator navigator = getNavigator();
        if (navigator == null) {
            return;
        }
        
        CommonViewer viewer = navigator.getCommonViewer();
        if (viewer == null) {
            return;
        }
        
        if (tagFilter != null) {
            tagFilter.clearSelectedTagsMode();
            viewer.removeFilter(tagFilter);
        }
        
        isFilterActive = false;
        
        // Update toggle button state
        updateToggleState(false);
        
        // Refresh the tree
        viewer.refresh();
        
        Activator.logInfo("Tag filter deactivated");
    }
    
    /**
     * Updates the toggle state of the filter command button.
     * 
     * @param active true if filter is active, false otherwise
     */
    private void updateToggleState(boolean active) {
        try {
            ICommandService commandService = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (commandService != null) {
                Command command = commandService.getCommand("com.ditrix.edt.mcp.server.tags.filterByTag");
                if (command != null) {
                    State state = command.getState(RegistryToggleState.STATE_ID);
                    if (state != null) {
                        state.setValue(active);
                        commandService.refreshElements("com.ditrix.edt.mcp.server.tags.filterByTag", null);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore - just visual feedback
        }
    }
    
    private int countSelectedTags() {
        int count = 0;
        for (Set<Tag> tags : selectedTags.values()) {
            count += tags.size();
        }
        return count;
    }
    
    private CommonNavigator getNavigator() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null) {
                return (CommonNavigator) window.getActivePage().findView(TagConstants.NAVIGATOR_VIEW_ID);
            }
        } catch (Exception e) {
            Activator.logError("Failed to get navigator", e);
        }
        return null;
    }
    
    private Shell getActiveShell() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window != null) {
            return window.getShell();
        }
        return Display.getDefault().getActiveShell();
    }
    
    private IV8ProjectManager getV8ProjectManager() {
        // Get from Activator's service tracker
        try {
            return Activator.getDefault().getV8ProjectManager();
        } catch (Exception e) {
            Activator.logError("Failed to get V8ProjectManager", e);
            return null;
        }
    }
}
