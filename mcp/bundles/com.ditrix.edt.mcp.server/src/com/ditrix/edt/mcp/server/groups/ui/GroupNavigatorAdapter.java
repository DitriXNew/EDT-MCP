/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.ui;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.model.WorkbenchAdapter;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.bm.core.IBmObject;
import com._1c.g5.v8.bm.integration.IBmModel;
import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.utils.BmTransactions;
import com.ditrix.edt.mcp.server.utils.EObjectFqnNavigator;

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
    @SuppressWarnings("java:S2696")
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
        IGroupService service = Activator.getGroupServiceStatic();
        
        List<Object> children = new ArrayList<>();
        
        // Add nested groups (only if service is available)
        if (service != null) {
            List<Group> nestedGroups = service.getGroupsAtPath(project, group.getFullPath());
            for (Group nestedGroup : nestedGroups) {
                children.add(new GroupNavigatorAdapter(nestedGroup, project, this));
            }
        }
        
        // Add objects in this group - return real EObjects so context menu works properly
        // The filter will identify grouped objects by checking if parent is GroupNavigatorAdapter
        for (String objectFqn : group.getChildren()) {
            EObject resolvedObject = resolveFqnToEObject(objectFqn);
            if (resolvedObject != null) {
                children.add(resolvedObject);
            }
            // If resolved object is null, skip this entry (object may have been deleted)
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
            
            return BmTransactions.<EObject>read(bmModel, "Resolve FQN to EObject", (transaction, progressMonitor) -> {
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
                return EObjectFqnNavigator.resolveNestedObject((EObject) topObject, parts, 2);
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
}
