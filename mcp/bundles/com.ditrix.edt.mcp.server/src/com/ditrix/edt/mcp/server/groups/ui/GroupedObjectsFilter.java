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

import com.ditrix.edt.mcp.server.groups.GroupService;
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
        // Always show GroupedObjectPlaceholder - these are objects displayed inside a group
        if (element instanceof GroupNavigatorAdapter.GroupedObjectPlaceholder) {
            return true;
        }
        
        // Only filter EObjects (metadata objects)
        if (!(element instanceof EObject eObject)) {
            return true;
        }
        
        // Skip filtering inside groups - don't hide objects displayed in GroupNavigatorAdapter
        if (parentElement instanceof GroupNavigatorAdapter) {
            return true;
        }
        
        // Skip filtering children of GroupedObjectPlaceholder - these are nested children of objects in groups
        if (parentElement instanceof GroupNavigatorAdapter.GroupedObjectPlaceholder) {
            return true;
        }
        
        // Handle TreePath - check if any ancestor is GroupNavigatorAdapter or GroupedObjectPlaceholder
        if (parentElement instanceof TreePath treePath) {
            for (int i = 0; i < treePath.getSegmentCount(); i++) {
                Object segment = treePath.getSegment(i);
                if (segment instanceof GroupNavigatorAdapter 
                        || segment instanceof GroupNavigatorAdapter.GroupedObjectPlaceholder) {
                    return true; // Show element - it's inside a group
                }
            }
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
        GroupService service = GroupService.getInstance();
        Group containingGroup = service.findGroupForObject(project, fqn);
        
        // If object is in a group, hide it from the original location
        return containingGroup == null;
    }
}
