/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

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
 *
 * <p>While a text search narrows the tree the filter switches itself off (see
 * {@link NavigatorSearchState}): the search hides the virtual group folders, so a grouped
 * object has to stay visible in its original location to remain findable.</p>
 */
public class GroupedObjectsFilter extends ViewerFilter {
    
    @Override
    public boolean select(Viewer viewer, Object parentElement, Object element) {
        if (GroupVisibilityManager.getInstance().areGroupsHidden()) {
            return true;
        }

        // Check if search/filter is active on the viewer
        // When search is active, we should show all objects (disable group filtering)
        if (NavigatorSearchState.isSearchActive(viewer, this)) {
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
}
