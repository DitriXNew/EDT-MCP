/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.IGroupChangeListener;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Content provider that integrates virtual groups into the EDT Navigator.
 * 
 * <p>This provider:
 * <ul>
 *   <li>Adds group folders as children of collection folders (CommonModules, etc.)</li>
 *   <li>Filters out objects that are in groups from their original location</li>
 *   <li>Resolves grouped objects from FQN placeholders</li>
 * </ul>
 * </p>
 */
public class GroupContentProvider implements ICommonContentProvider, IGroupChangeListener {
    
    private static final Object[] NO_CHILDREN = new Object[0];
    
    private StructuredViewer viewer;
    private GroupSelectionHelper selectionHelper;
    private volatile boolean listenerRegistered = false;
    
    public GroupContentProvider() {
        Activator.logDebug("GroupContentProvider: constructor called");
    }
    
    /**
     * Gets the group service with lazy listener registration.
     * Returns null if service is not available.
     */
    private IGroupService getGroupService() {
        IGroupService service = Activator.getGroupServiceStatic();
        if (service != null && !listenerRegistered) {
            synchronized (this) {
                if (!listenerRegistered) {
                    service.addGroupChangeListener(this);
                    listenerRegistered = true;
                }
            }
        }
        return service;
    }
    
    @Override
    public void init(ICommonContentExtensionSite aConfig) {
        Activator.logDebug("GroupContentProvider.init called");
        // Lazy registration - service may not be available yet
        getGroupService();
    }
    
    @Override
    public Object[] getElements(Object inputElement) {
        return getChildren(inputElement);
    }
    
    @Override
    public Object[] getChildren(Object parentElement) {
        // Handle collection folders (CommonModules, Catalogs, etc.)
        if (isCollectionAdapter(parentElement)) {
            return getChildrenForCollection(parentElement);
        }
        
        // Handle our group adapters
        if (parentElement instanceof GroupNavigatorAdapter groupAdapter) {
            return groupAdapter.getChildren(parentElement);
        }
        
        return NO_CHILDREN;
    }
    
    /**
     * Checks if the element is a collection adapter (using utility class).
     */
    private boolean isCollectionAdapter(Object element) {
        return CollectionAdapterUtils.isCollectionAdapter(element);
    }
    
    /**
     * Gets children for a collection folder, adding groups and filtering grouped objects.
     */
    private Object[] getChildrenForCollection(Object collectionAdapter) {
        // Get the project from the collection adapter
        IProject project = CollectionAdapterUtils.getProjectFromAdapter(collectionAdapter);
        if (project == null) {
            return NO_CHILDREN;
        }
        
        // Get the path for this collection (e.g., "CommonModules")
        String collectionPath = CollectionAdapterUtils.getFullCollectionPath(collectionAdapter, 
            TagUtils::extractFqn);
        if (collectionPath == null) {
            return NO_CHILDREN;
        }
        
        IGroupService groupService = getGroupService();
        if (groupService == null) {
            return NO_CHILDREN;
        }
        
        // Check if there are any groups at this path
        if (!groupService.hasGroupsAtPath(project, collectionPath)) {
            return NO_CHILDREN;
        }
        
        List<Object> children = new ArrayList<>();
        
        // Add group folders
        List<Group> groups = groupService.getGroupsAtPath(project, collectionPath);
        for (Group group : groups) {
            children.add(new GroupNavigatorAdapter(group, project, collectionAdapter));
        }
        
        return children.toArray();
    }
    
    @Override
    public Object getParent(Object element) {
        if (element instanceof GroupNavigatorAdapter groupAdapter) {
            return groupAdapter.getParent(element);
        }
        return null;
    }
    
    @Override
    public boolean hasChildren(Object element) {
        if (element instanceof GroupNavigatorAdapter groupAdapter) {
            Group group = groupAdapter.getGroup();
            // Has children if has nested groups or objects
            IProject project = groupAdapter.getProject();
            IGroupService service = getGroupService();
            if (service == null) {
                return !group.getChildren().isEmpty();
            }
            
            boolean hasNestedGroups = service.hasGroupsAtPath(project, group.getFullPath());
            boolean hasObjects = !group.getChildren().isEmpty();
            
            return hasNestedGroups || hasObjects;
        }
        
        if (isCollectionAdapter(element)) {
            // Check if this collection has any groups
            IProject project = CollectionAdapterUtils.getProjectFromAdapter(element);
            String path = CollectionAdapterUtils.getFullCollectionPath(element, TagUtils::extractFqn);
            if (project != null && path != null) {
                IGroupService svc = getGroupService();
                return svc != null && svc.hasGroupsAtPath(project, path);
            }
        }
        
        return false;
    }
    
    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        Activator.logDebug("GroupContentProvider.inputChanged called, viewer type: " 
            + (viewer != null ? viewer.getClass().getName() : "null"));
        
        if (viewer instanceof StructuredViewer sv) {
            this.viewer = sv;
            // Note: Filter is added via commonFilter in plugin.xml with activeByDefault="true"
            // No need to add programmatically - this avoids duplicate filter issues
            
            // Attach selection helper for grouped object selection restoration
            if (viewer instanceof TreeViewer tv && selectionHelper == null) {
                selectionHelper = new GroupSelectionHelper(tv);
                selectionHelper.attach();
                Activator.logDebug("GroupContentProvider: attached GroupSelectionHelper");
            }
        }
    }
    
    @Override
    public void dispose() {
        if (selectionHelper != null) {
            selectionHelper.detach();
            selectionHelper = null;
        }
        if (listenerRegistered) {
            IGroupService service = Activator.getGroupServiceStatic();
            if (service != null) {
                service.removeGroupChangeListener(this);
            }
            listenerRegistered = false;
        }
        viewer = null;
    }
    
    @Override
    public void onGroupsChanged(IProject project) {
        if (viewer == null || viewer.getControl() == null || viewer.getControl().isDisposed()) {
            return;
        }
        
        Display display = viewer.getControl().getDisplay();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() -> {
                if (viewer != null && viewer.getControl() != null && !viewer.getControl().isDisposed()) {
                    viewer.refresh();
                }
            });
        }
    }
    
    @Override
    public void restoreState(IMemento aMemento) {
        // No state to restore
    }
    
    @Override
    public void saveState(IMemento aMemento) {
        // No state to save
    }
}
