/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Filter that hides objects from their original location when they are in a group.
 * 
 * <p>Objects placed in virtual folder groups should only appear inside the group,
 * not in the main collection list. This filter ensures that when viewing the
 * original collection (e.g., Common Modules), objects that have been added to
 * a group are hidden from the list.</p>
 */
public class GroupedObjectsFilter extends ViewerFilter {
    
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        // Check if search/filter is active on the viewer
        // When search is active, we should show all objects (disable group filtering)
        if (isSearchActive(viewer)) {
            return true;
        }
        
        // Skip filtering inside groups - don't hide objects displayed in GroupNavigatorAdapter
        if (parentElement instanceof GroupNavigatorAdapter) {
            return true;
        }
        
        // Handle TreePath - check if any ancestor is GroupNavigatorAdapter
        if (parentElement instanceof TreePath treePath) {
            for (int i = 0; i < treePath.getSegmentCount(); i++) {
                Object segment = treePath.getSegment(i);
                if (segment instanceof GroupNavigatorAdapter) {
                    return true; // Show element - it's inside a group
                }
            }
        }
        
        // Only filter EObjects (metadata objects)
        if (!(element instanceof EObject eObject)) {
            return true;
        }
        
        // Get project from the EObject
        IProject project = TagUtils.extractProject(eObject);
        if (project == null) {
            return true;
        }
        
        // Get FQN of the object
        String fqn = TagUtils.extractFqn(eObject);
        if (fqn == null) {
            return true;
        }
        
        // Check if this object is in any group
        IGroupService service = Activator.getGroupServiceStatic();
        if (service == null) {
            return true; // Service not available, show object
        }
        Group containingGroup = service.findGroupForObject(project, fqn);
        
        // If object is in a group, hide it from the original location
        return containingGroup == null;
    }
    
    /**
     * Checks if a text search filter is currently active on the viewer.
     * When text search is active, we disable group filtering to allow finding objects in groups.
     * Note: Tag filter (TagSearchFilter) does NOT disable group filtering - it handles groups itself.
     */
    private boolean isSearchActive(Viewer viewer) {
        if (viewer == null) {
            return false;
        }
        
        // Check if there are any PatternFilter-like filters active on the viewer
        if (viewer instanceof org.eclipse.jface.viewers.StructuredViewer sv) {
            ViewerFilter[] filters = sv.getFilters();
            for (ViewerFilter filter : filters) {
                // Skip ourselves
                if (filter == this) {
                    continue;
                }
                // Skip TagSearchFilter - it handles groups itself (use instanceof for type safety)
                if (filter instanceof com.ditrix.edt.mcp.server.tags.ui.TagSearchFilter) {
                    continue;
                }
                // Check for common text search filter types by class name
                String className = filter.getClass().getName();
                if (className.contains("Pattern") || className.contains("Search") || 
                    className.contains("Quick") || className.contains("Text")) {
                    // This is a text search filter - our filter should be disabled
                    return true;
                }
            }
        }
        
        return false;
    }
}
