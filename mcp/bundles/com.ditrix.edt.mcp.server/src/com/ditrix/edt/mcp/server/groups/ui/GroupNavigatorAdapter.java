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

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.model.WorkbenchAdapter;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.core.IBmTransaction;
import com._1c.g5.v8.bm.integration.AbstractBmTask;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;

/**
 * Navigator adapter for virtual folder groups.
 * Represents a group folder in the Navigator tree.
 */
public class GroupNavigatorAdapter extends WorkbenchAdapter implements IAdaptable {
    
    private static final Object[] NO_CHILDREN = new Object[0];
    private static ImageDescriptor folderIcon;
    
    private final Group group;
    private final IProject project;
    private final Object parent;
    
    /**
     * Creates a new group navigator adapter.
     * 
     * @param group the group model
     * @param project the project
     * @param parent the parent object in Navigator tree
     */
    public GroupNavigatorAdapter(Group group, IProject project, Object parent) {
        this.group = Objects.requireNonNull(group, "group must not be null");
        this.project = Objects.requireNonNull(project, "project must not be null");
        this.parent = parent;
    }
    
    /**
     * Gets the group model.
     * 
     * @return the group
     */
    public Group getGroup() {
        return group;
    }
    
    /**
     * Gets the project.
     * 
     * @return the project
     */
    public IProject getProject() {
        return project;
    }
    
    @Override
    public String getLabel(Object object) {
        return group.getName();
    }
    
    @Override
    public ImageDescriptor getImageDescriptor(Object object) {
        if (folderIcon == null) {
            try {
                Bundle bundle = Activator.getDefault().getBundle();
                URL url = bundle.getEntry("icons/group.png");
                if (url != null) {
                    folderIcon = ImageDescriptor.createFromURL(url);
                }
            } catch (Exception e) {
                Activator.logError("Failed to load folder icon", e);
            }
        }
        return folderIcon;
    }
    
    @Override
    public Object[] getChildren(Object object) {
        GroupService service = GroupService.getInstance();
        
        List<Object> children = new ArrayList<>();
        
        // Add nested groups
        List<Group> nestedGroups = service.getGroupsAtPath(project, group.getFullPath());
        for (Group nestedGroup : nestedGroups) {
            children.add(new GroupNavigatorAdapter(nestedGroup, project, this));
        }
        
        // Add objects in this group - wrap in placeholder to avoid filtering
        for (String objectFqn : group.getChildren()) {
            EObject resolvedObject = resolveFqnToEObject(objectFqn);
            // Always wrap in placeholder so filter can identify grouped objects
            GroupedObjectPlaceholder placeholder = new GroupedObjectPlaceholder(objectFqn, project, this, resolvedObject);
            children.add(placeholder);
        }
        
        return children.isEmpty() ? NO_CHILDREN : children.toArray();
    }
    
    /**
     * Resolves an FQN to an EObject using BM.
     * Supports both top-level objects (e.g., "Catalog.Files") and nested objects
     * (e.g., "Catalog.Files.CatalogAttribute.Width").
     * 
     * @param fqn the fully qualified name
     * @return the resolved EObject or null
     */
    private EObject resolveFqnToEObject(String fqn) {
        try {
            IBmModelManager bmModelManager = Activator.getDefault().getBmModelManager();
            if (bmModelManager == null) {
                return null;
            }
            
            IBmModel bmModel = bmModelManager.getModel(project);
            if (bmModel == null) {
                return null;
            }
            
            return bmModel.executeReadonlyTask(
                new AbstractBmTask<EObject>("Resolve FQN to EObject") {
                    @Override
                    public EObject execute(IBmTransaction transaction, IProgressMonitor progressMonitor) {
                        String[] parts = fqn.split("\\.");
                        if (parts.length < 2) {
                            return null;
                        }
                        
                        // Build top-level FQN (first two parts: Type.Name)
                        String topFqn = parts[0] + "." + parts[1];
                        IBmObject topObject = transaction.getTopObjectByFqn(topFqn);
                        
                        if (topObject == null) {
                            return null;
                        }
                        
                        // If it's a top-level object, return it
                        if (parts.length == 2) {
                            return (EObject) topObject;
                        }
                        
                        // Otherwise resolve nested object
                        return resolveNestedObject((EObject) topObject, parts, 2);
                    }
                });
        } catch (Exception e) {
            Activator.logError("Failed to resolve FQN: " + fqn, e);
            return null;
        }
    }
    
    /**
     * Resolves nested objects from a parent by navigating the FQN parts.
     * 
     * @param parent the parent EObject
     * @param parts the FQN parts
     * @param startIndex the index to start from (skip top-level type and name)
     * @return the resolved nested EObject or null
     */
    private EObject resolveNestedObject(EObject parent, String[] parts, int startIndex) {
        EObject current = parent;
        
        for (int i = startIndex; i < parts.length; i += 2) { // Skip by 2 (SubTypeName.SubName)
            if (i + 1 >= parts.length) {
                break;
            }
            
            String subTypeName = parts[i];
            String subName = parts[i + 1];
            
            // Try to find the child by navigating containment references
            EObject child = findChildByTypeAndName(current, subTypeName, subName);
            if (child == null) {
                return null;
            }
            current = child;
        }
        
        return current;
    }
    
    /**
     * Finds a child EObject by type and name.
     */
    private EObject findChildByTypeAndName(EObject parent, String typeName, String name) {
        // Try to find in all containment references
        for (org.eclipse.emf.ecore.EReference ref : parent.eClass().getEAllContainments()) {
            Object value = parent.eGet(ref);
            if (value instanceof java.util.Collection<?> collection) {
                for (Object item : collection) {
                    if (item instanceof EObject child) {
                        if (matchesTypeAndName(child, typeName, name)) {
                            return child;
                        }
                    }
                }
            } else if (value instanceof EObject child) {
                if (matchesTypeAndName(child, typeName, name)) {
                    return child;
                }
            }
        }
        return null;
    }
    
    /**
     * Checks if an EObject matches the given type and name.
     */
    private boolean matchesTypeAndName(EObject obj, String typeName, String name) {
        String objTypeName = obj.eClass().getName();
        if (!objTypeName.equals(typeName) && !objTypeName.endsWith(typeName)) {
            return false;
        }
        
        // Try to get name
        try {
            for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
                if ("getName".equals(m.getName()) && m.getParameterCount() == 0) {
                    Object objName = m.invoke(obj);
                    return name.equals(objName);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return false;
    }
    
    @Override
    public Object getParent(Object object) {
        return parent;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        GroupNavigatorAdapter other = (GroupNavigatorAdapter) obj;
        return Objects.equals(group.getFullPath(), other.group.getFullPath())
            && Objects.equals(project, other.project);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(group.getFullPath(), project);
    }
    
    @SuppressWarnings("unchecked")
    @Override
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == Group.class) {
            return (T) group;
        }
        if (adapter == IProject.class) {
            return (T) project;
        }
        return Platform.getAdapterManager().getAdapter(this, adapter);
    }
    
    /**
     * Placeholder for an object inside a group.
     * Used to identify objects that are displayed inside a group, 
     * so the filter won't hide them from the group.
     */
    public static class GroupedObjectPlaceholder implements IAdaptable {
        
        private final String objectFqn;
        private final IProject project;
        private final GroupNavigatorAdapter parentGroup;
        private final EObject resolvedObject;
        
        public GroupedObjectPlaceholder(String objectFqn, IProject project, 
                GroupNavigatorAdapter parentGroup, EObject resolvedObject) {
            this.objectFqn = objectFqn;
            this.project = project;
            this.parentGroup = parentGroup;
            this.resolvedObject = resolvedObject;
        }
        
        public String getObjectFqn() {
            return objectFqn;
        }
        
        public IProject getProject() {
            return project;
        }
        
        public GroupNavigatorAdapter getParentGroup() {
            return parentGroup;
        }
        
        /**
         * Gets the resolved EObject for this placeholder.
         * 
         * @return the resolved EObject, may be null if resolution failed
         */
        public EObject getResolvedObject() {
            return resolvedObject;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public <T> T getAdapter(Class<T> adapter) {
            if (adapter == IProject.class) {
                return (T) project;
            }
            // Return resolved object for EObject and IBmObject adapters
            // This enables double-click to open the object in editor
            if (resolvedObject != null) {
                if (adapter == EObject.class) {
                    return (T) resolvedObject;
                }
                if (adapter.getName().equals("com._1c.g5.v8.bm.core.IBmObject") 
                        && adapter.isInstance(resolvedObject)) {
                    return (T) resolvedObject;
                }
            }
            // Delegate to resolved object if present
            if (resolvedObject != null && resolvedObject instanceof IAdaptable adaptable) {
                T result = adaptable.getAdapter(adapter);
                if (result != null) {
                    return result;
                }
            }
            return Platform.getAdapterManager().getAdapter(this, adapter);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            GroupedObjectPlaceholder other = (GroupedObjectPlaceholder) obj;
            return Objects.equals(objectFqn, other.objectFqn)
                && Objects.equals(project, other.project);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(objectFqn, project);
        }
    }
}
