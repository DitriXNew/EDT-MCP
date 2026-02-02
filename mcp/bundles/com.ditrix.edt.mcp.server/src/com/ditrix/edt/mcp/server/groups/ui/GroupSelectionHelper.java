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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Helper that restores selection for objects inside groups after search filter is cleared.
 * 
 * <p>When a user selects an object in search mode and then clears the search,
 * the object may be hidden by GroupedObjectsFilter (because it's in a group).
 * This helper detects such situations and restores the selection by expanding
 * the group and selecting the object inside it.</p>
 */
public class GroupSelectionHelper implements ISelectionChangedListener {
    
    private final TreeViewer viewer;
    private volatile IStructuredSelection lastNonEmptySelection;
    private volatile long lastSelectionTime;
    
    /**
     * Creates a new selection helper for the given viewer.
     * 
     * @param viewer the tree viewer to monitor
     */
    public GroupSelectionHelper(TreeViewer viewer) {
        this.viewer = viewer;
    }
    
    /**
     * Attaches this helper to the viewer.
     */
    public void attach() {
        viewer.addSelectionChangedListener(this);
    }
    
    /**
     * Detaches this helper from the viewer.
     */
    public void detach() {
        viewer.removeSelectionChangedListener(this);
    }
    
    @Override
    public void selectionChanged(SelectionChangedEvent event) {
        IStructuredSelection selection = (IStructuredSelection) event.getSelection();
        
        Activator.logDebug("GroupSelectionHelper: selectionChanged, isEmpty=" + selection.isEmpty() 
            + ", size=" + selection.size());
        
        if (selection.isEmpty()) {
            // Selection became empty - possibly after search reset
            // Check if we should restore selection for grouped objects
            long timeSinceLastSelection = System.currentTimeMillis() - lastSelectionTime;
            
            Activator.logDebug("GroupSelectionHelper: empty selection, timeSince=" + timeSinceLastSelection 
                + "ms, hasLast=" + (lastNonEmptySelection != null && !lastNonEmptySelection.isEmpty()));
            
            // Only try to restore if the last non-empty selection was recent (within 2 seconds)
            // This avoids interfering with intentional selection clearing
            if (lastNonEmptySelection != null && !lastNonEmptySelection.isEmpty() 
                    && timeSinceLastSelection < 2000) {
                tryRestoreGroupedSelection();
            }
        } else {
            // Remember last non-empty selection ONLY if it contains metadata objects
            // This is important because Navigator may change selection to Project before clearing
            Object firstElement = selection.getFirstElement();
            if (firstElement instanceof EObject) {
                lastNonEmptySelection = selection;
                lastSelectionTime = System.currentTimeMillis();
                
                String typeName = firstElement.getClass().getName();
                Activator.log("GroupSelectionHelper: remembered METADATA selection with " + selection.size() 
                    + " elements, first type=" + typeName);
            } else {
                String typeName = firstElement != null ? firstElement.getClass().getName() : "null";
                Activator.log("GroupSelectionHelper: ignoring non-metadata selection, type=" + typeName);
            }
        }
    }
    
    /**
     * Attempts to restore selection for objects that are in groups.
     */
    private void tryRestoreGroupedSelection() {
        if (lastNonEmptySelection == null || lastNonEmptySelection.isEmpty()) {
            Activator.logDebug("GroupSelectionHelper: tryRestore - no last selection");
            return;
        }
        
        IGroupService groupService = Activator.getGroupServiceStatic();
        if (groupService == null) {
            Activator.logDebug("GroupSelectionHelper: tryRestore - no group service");
            return;
        }
        
        // Check if any selected element is in a group
        boolean hasGroupedElements = false;
        
        for (Iterator<?> it = lastNonEmptySelection.iterator(); it.hasNext();) {
            Object element = it.next();
            Activator.log("GroupSelectionHelper: element type=" + element.getClass().getName());
            
            if (element instanceof EObject eObject) {
                IProject project = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                
                Activator.log("GroupSelectionHelper: checking element fqn=" + fqn);
                
                if (project != null && fqn != null) {
                    Group group = groupService.findGroupForObject(project, fqn);
                    if (group != null) {
                        Activator.log("GroupSelectionHelper: found in group '" + group.getName() + "'");
                        hasGroupedElements = true;
                        break;
                    } else {
                        Activator.log("GroupSelectionHelper: not in any group");
                    }
                }
            } else {
                Activator.log("GroupSelectionHelper: element is not EObject");
            }
        }
        
        if (hasGroupedElements) {
            Activator.logDebug("GroupSelectionHelper: scheduling selection restoration");
            // Schedule selection restoration asynchronously
            Display display = viewer.getControl().getDisplay();
            if (display != null && !display.isDisposed()) {
                display.asyncExec(this::restoreSelection);
            }
        } else {
            Activator.logDebug("GroupSelectionHelper: no grouped elements found");
        }
    }
    
    /**
     * Restores selection by finding the group adapter and creating a TreePath through it.
     * This is necessary because the object is hidden by GroupedObjectsFilter in its original location,
     * so we need to tell the TreeViewer to look for it inside the group.
     */
    private void restoreSelection() {
        Activator.log("GroupSelectionHelper: restoreSelection called");
        
        if (viewer.getControl() == null || viewer.getControl().isDisposed()) {
            return;
        }
        
        if (lastNonEmptySelection == null || lastNonEmptySelection.isEmpty()) {
            return;
        }
        
        IGroupService groupService = Activator.getGroupServiceStatic();
        if (groupService == null) {
            return;
        }
        
        List<TreePath> treePaths = new ArrayList<>();
        
        for (Iterator<?> it = lastNonEmptySelection.iterator(); it.hasNext();) {
            Object element = it.next();
            
            if (element instanceof EObject eObject) {
                IProject project = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                
                if (project != null && fqn != null) {
                    Group group = groupService.findGroupForObject(project, fqn);
                    
                    if (group != null) {
                        // Find the GroupNavigatorAdapter for this group
                        GroupNavigatorAdapter groupAdapter = findOrCreateGroupAdapter(project, group);
                        
                        if (groupAdapter != null) {
                            // Create TreePath: groupAdapter -> element
                            TreePath path = new TreePath(new Object[] { groupAdapter, element });
                            treePaths.add(path);
                            Activator.log("GroupSelectionHelper: created TreePath through group '" + group.getName() + "'");
                        }
                    }
                }
            }
        }
        
        if (!treePaths.isEmpty()) {
            TreeSelection treeSelection = new TreeSelection(treePaths.toArray(new TreePath[0]));
            Activator.log("GroupSelectionHelper: setting TreeSelection with " + treePaths.size() + " paths");
            viewer.setSelection(treeSelection, true);
        } else {
            // Fallback to original selection
            Activator.log("GroupSelectionHelper: no TreePaths created, trying original selection");
            viewer.setSelection(lastNonEmptySelection, true);
        }
    }
    
    /**
     * Finds GroupNavigatorAdapter for the given group by traversing the tree structure.
     * Uses the ContentProvider to navigate through the tree hierarchy.
     */
    private GroupNavigatorAdapter findOrCreateGroupAdapter(IProject project, Group group) {
        if (!(viewer.getContentProvider() instanceof ITreeContentProvider tcp)) {
            Activator.log("GroupSelectionHelper: content provider is not ITreeContentProvider");
            return null;
        }
        
        String targetPath = group.getPath(); // e.g., "CommonModule"
        Activator.log("GroupSelectionHelper: looking for group '" + group.getName() + "' at path=" + targetPath);
        
        // Expand project to see its children
        viewer.expandToLevel(project, 1);
        
        // Find the collection that matches our target path
        Object collection = findCollectionByPath(tcp, project, targetPath);
        
        if (collection == null) {
            Activator.log("GroupSelectionHelper: collection not found for path=" + targetPath);
            return null;
        }
        
        Activator.log("GroupSelectionHelper: found collection, expanding to reveal groups");
        
        // Expand the collection to see groups
        viewer.expandToLevel(collection, 1);
        
        // Now get children of collection - this will include GroupNavigatorAdapters
        Object[] children = tcp.getChildren(collection);
        for (Object child : children) {
            if (child instanceof GroupNavigatorAdapter adapter) {
                if (adapter.getProject().equals(project) 
                        && adapter.getGroup().getName().equals(group.getName())
                        && adapter.getGroup().getPath().equals(group.getPath())) {
                    Activator.log("GroupSelectionHelper: found GroupNavigatorAdapter for group '" + group.getName() + "'");
                    // Expand the group to see its children
                    viewer.expandToLevel(adapter, 1);
                    return adapter;
                }
            }
        }
        
        Activator.log("GroupSelectionHelper: GroupNavigatorAdapter not found in collection children");
        return null;
    }
    
    /**
     * Finds the collection adapter that matches the given path (e.g., "CommonModule").
     * Traverses the tree from project through configuration.
     */
    private Object findCollectionByPath(ITreeContentProvider tcp, IProject project, String targetPath) {
        // Get project children
        Object[] projectChildren = tcp.getChildren(project);
        
        for (Object child : projectChildren) {
            // Look for Configuration (or expand to find it)
            if (child != null) {
                viewer.expandToLevel(child, 1);
                
                // Check this level for matching collection
                String modelName = CollectionAdapterUtils.getModelObjectName(child);
                if (targetPath.equals(modelName)) {
                    return child;
                }
                
                // Check children of this child
                Object[] subChildren = tcp.getChildren(child);
                for (Object subChild : subChildren) {
                    modelName = CollectionAdapterUtils.getModelObjectName(subChild);
                    if (targetPath.equals(modelName)) {
                        return subChild;
                    }
                }
            }
        }
        
        return null;
    }
}
