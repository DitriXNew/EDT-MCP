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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.model.IWorkbenchAdapter;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonContentProvider;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
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
    private static final String COLLECTION_ADAPTER_CLASS_NAME = 
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase";
    
    private StructuredViewer viewer;
    
    @Override
    public void init(ICommonContentExtensionSite aConfig) {
        GroupService.getInstance().addGroupChangeListener(this);
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
     * Checks if the element is a collection adapter (using reflection to avoid API restriction).
     */
    private boolean isCollectionAdapter(Object element) {
        if (element == null) {
            return false;
        }
        Class<?> clazz = element.getClass();
        while (clazz != null) {
            String className = clazz.getName();
            if (COLLECTION_ADAPTER_CLASS_NAME.equals(className)) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
    
    /**
     * Gets children for a collection folder, adding groups and filtering grouped objects.
     */
    private Object[] getChildrenForCollection(Object collectionAdapter) {
        // Get the project from the collection adapter
        IProject project = getProjectFromAdapter(collectionAdapter);
        if (project == null) {
            return NO_CHILDREN;
        }
        
        // Get the path for this collection (e.g., "CommonModules")
        String collectionPath = getCollectionPath(collectionAdapter);
        if (collectionPath == null) {
            return NO_CHILDREN;
        }
        
        GroupService groupService = GroupService.getInstance();
        
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
            GroupService service = GroupService.getInstance();
            
            boolean hasNestedGroups = service.hasGroupsAtPath(project, group.getFullPath());
            boolean hasObjects = !group.getChildren().isEmpty();
            
            return hasNestedGroups || hasObjects;
        }
        
        if (isCollectionAdapter(element)) {
            // Check if this collection has any groups
            IProject project = getProjectFromAdapter(element);
            String path = getCollectionPath(element);
            if (project != null && path != null) {
                return GroupService.getInstance().hasGroupsAtPath(project, path);
            }
        }
        
        return false;
    }
    
    private GroupedObjectsFilter groupedObjectsFilter;
    
    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        if (viewer instanceof StructuredViewer sv) {
            this.viewer = sv;
            // Add our filter directly to the viewer
            // CommonFilter in plugin.xml may not work reliably, so we add it programmatically
            if (groupedObjectsFilter == null) {
                groupedObjectsFilter = new GroupedObjectsFilter();
                sv.addFilter(groupedObjectsFilter);
            }
        }
    }
    
    @Override
    public void dispose() {
        GroupService.getInstance().removeGroupChangeListener(this);
        if (viewer != null && groupedObjectsFilter != null) {
            try {
                viewer.removeFilter(groupedObjectsFilter);
            } catch (Exception e) {
                // Ignore - viewer may be disposed
            }
        }
        groupedObjectsFilter = null;
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
    
    // === Helper methods ===
    
    /**
     * Gets the project from a navigator adapter using IAdaptable.
     */
    private IProject getProjectFromAdapter(Object adapter) {
        if (adapter instanceof IAdaptable adaptable) {
            return adaptable.getAdapter(IProject.class);
        }
        return null;
    }
    
    /**
     * Gets the full collection path for a collection adapter using reflection.
     * For nested collections (like Catalog.Products.Attribute), returns the full path.
     */
    private String getCollectionPath(Object adapter) {
        try {
            // Get the model object name (e.g., "Attribute", "CommonModule")
            String modelObjectName = null;
            try {
                Method getModelObjectNameMethod = adapter.getClass().getMethod("getModelObjectName");
                Object result = getModelObjectNameMethod.invoke(adapter);
                if (result instanceof String) {
                    modelObjectName = (String) result;
                }
            } catch (NoSuchMethodException e) {
                // Method doesn't exist
            }
            
            if (modelObjectName == null) {
                // Fallback: try using IWorkbenchAdapter label
                if (adapter instanceof IWorkbenchAdapter workbenchAdapter) {
                    String label = workbenchAdapter.getLabel(adapter);
                    if (label != null) {
                        modelObjectName = label.replace(" ", "");
                    }
                }
            }
            
            if (modelObjectName == null) {
                return null;
            }
            
            // Try to get parent object FQN for nested collections
            try {
                Method getParentMethod = adapter.getClass().getMethod("getParent", Object.class);
                Object parent = getParentMethod.invoke(adapter, adapter);
                if (parent instanceof EObject parentEObject) {
                    String parentFqn = TagUtils.extractFqn(parentEObject);
                    if (parentFqn != null) {
                        // Return full path: ParentFqn.CollectionType
                        return parentFqn + "." + modelObjectName;
                    }
                }
            } catch (NoSuchMethodException e) {
                // Method doesn't exist, fall through to simple path
            }
            
            return modelObjectName;
            
        } catch (Exception e) {
            Activator.logError("Error getting collection path", e);
        }
        
        return null;
    }
}
