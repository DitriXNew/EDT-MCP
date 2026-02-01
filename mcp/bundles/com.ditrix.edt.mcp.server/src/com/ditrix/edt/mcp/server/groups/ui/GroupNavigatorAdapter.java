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
        
        // Add objects in this group - resolve FQN to actual EObject
        for (String objectFqn : group.getChildren()) {
            EObject resolvedObject = resolveFqnToEObject(objectFqn);
            if (resolvedObject != null) {
                children.add(resolvedObject);
            } else {
                // Fallback to placeholder if resolution fails
                children.add(new GroupedObjectPlaceholder(objectFqn, project, this));
            }
        }
        
        return children.isEmpty() ? NO_CHILDREN : children.toArray();
    }
    
    /**
     * Resolves an FQN to an EObject using BM.
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
            
            return (EObject) bmModel.executeReadonlyTask(
                new AbstractBmTask<IBmObject>("Resolve FQN to EObject") {
                    @Override
                    public IBmObject execute(IBmTransaction transaction, IProgressMonitor progressMonitor) {
                        return transaction.getTopObjectByFqn(fqn);
                    }
                });
        } catch (Exception e) {
            Activator.logError("Failed to resolve FQN: " + fqn, e);
            return null;
        }
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
     * Used to identify objects that need to be resolved by the content provider.
     */
    public static class GroupedObjectPlaceholder implements IAdaptable {
        
        private final String objectFqn;
        private final IProject project;
        private final GroupNavigatorAdapter parentGroup;
        
        public GroupedObjectPlaceholder(String objectFqn, IProject project, 
                GroupNavigatorAdapter parentGroup) {
            this.objectFqn = objectFqn;
            this.project = project;
            this.parentGroup = parentGroup;
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
        
        @SuppressWarnings("unchecked")
        @Override
        public <T> T getAdapter(Class<T> adapter) {
            if (adapter == IProject.class) {
                return (T) project;
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
