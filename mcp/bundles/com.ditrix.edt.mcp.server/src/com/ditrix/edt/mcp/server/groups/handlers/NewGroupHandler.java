/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.handlers;

import java.lang.reflect.Method;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.model.IWorkbenchAdapter;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.EditGroupDialog;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;

/**
 * Handler for the "New Group" command.
 * Creates a new virtual folder group in the Navigator.
 * Only enabled for top-level collection adapters (Catalogs, CommonModules, etc.).
 * Nested groups (groups inside groups) are not supported.
 */
public class NewGroupHandler extends AbstractHandler {
    
    private static final String COLLECTION_ADAPTER_CLASS_NAME = 
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase";
    
    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection is valid for group creation
        Object selection = org.eclipse.ui.handlers.HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        Object selected = structuredSelection.getFirstElement();
        if (selected == null) {
            setBaseEnabled(false);
            return;
        }
        
        // Nested groups are not supported - disable for GroupNavigatorAdapter
        if (selected instanceof GroupNavigatorAdapter) {
            setBaseEnabled(false);
            return;
        }
        
        // Enable for top-level collection adapters only
        if (isCollectionAdapter(selected)) {
            String path = getCollectionPath(selected);
            // path will be null for nested collections
            setBaseEnabled(path != null);
            return;
        }
        
        setBaseEnabled(false);
    }
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            return null;
        }
        
        Object selected = structuredSelection.getFirstElement();
        if (selected == null) {
            return null;
        }
        
        // Determine parent path and project based on selection
        // Only collection adapters are supported (no nested groups)
        String parentPath = null;
        IProject project = null;
        
        if (isCollectionAdapter(selected)) {
            // Creating inside a collection folder (CommonModules, etc.)
            parentPath = getCollectionPath(selected);
            project = getProjectFromAdapter(selected);
        } else {
            // Not a valid target for group creation
            return null;
        }
        
        if (project == null || parentPath == null) {
            return null;
        }
        
        // Show dialog for group name and description
        final IProject finalProject = project;
        final String finalParentPath = parentPath;
        
        EditGroupDialog dialog = new EditGroupDialog(shell,
            name -> validateGroupName(name, finalProject, finalParentPath));
        
        if (dialog.open() == Window.OK) {
            String groupName = dialog.getGroupName();
            String description = dialog.getGroupDescription();
            
            try {
                IGroupService svc = Activator.getGroupServiceStatic();
                Group newGroup = svc.createGroup(project, groupName, parentPath, 
                    description.isEmpty() ? null : description);
                
                if (newGroup == null) {
                    Activator.logInfo("Failed to create group: " + groupName);
                }
                
                // The navigator will be refreshed by the GroupService listener
                
            } catch (Exception e) {
                Activator.logError("Error creating group", e);
                throw new ExecutionException("Failed to create group", e);
            }
        }
        
        return null;
    }

    /**
     * Validates a proposed group name for the {@link EditGroupDialog}. Returns an
     * error message to display, or {@code null} when the name is acceptable: it must
     * be non-blank, contain no path separators, and not collide with an existing
     * group at the same parent path. Performs no mutations.
     *
     * @param name the candidate name entered in the dialog
     * @param project the project the group would be created in
     * @param parentPath the collection path the group would live under
     * @return the validation error message, or {@code null} if the name is valid
     */
    private String validateGroupName(String name, IProject project, String parentPath) {
        if (name == null || name.trim().isEmpty()) {
            return "Group name cannot be empty";
        }
        String trimmed = name.trim();
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            return "Group name cannot contain path separators";
        }
        // Check for existing group with same name
        IGroupService service = Activator.getGroupServiceStatic();
        String fullPath = parentPath.isEmpty()
            ? trimmed
            : parentPath + "/" + trimmed;
        if (service.getGroupStorage(project).getGroupByFullPath(fullPath) != null) {
            return "A group with this name already exists";
        }
        return null;
    }

    /**
     * Checks if the element is a collection adapter.
     */
    private boolean isCollectionAdapter(Object element) {
        if (element == null) {
            return false;
        }
        Class<?> clazz = element.getClass();
        while (clazz != null) {
            if (COLLECTION_ADAPTER_CLASS_NAME.equals(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }
    
    /**
     * Gets the collection path for a collection adapter.
     * Only returns top-level collection types (CommonModule, Catalog, Document, etc.)
     * Returns null for nested collections (like Catalog.Products.Attribute).
     */
    private String getCollectionPath(Object adapter) {
        try {
            // Get the model object name (e.g., "Attribute", "CommonModule")
            String modelObjectName = invokeGetModelObjectName(adapter);

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
            
            // Check if this is a nested collection (has parent EObject)
            // We only support top-level collections for groups
            if (isNestedCollection(adapter)) {
                // This is a nested collection (e.g., Catalog.Products.Attribute)
                // We don't support groups for nested collections
                return null;
            }

            // Return simple collection type name (e.g., "CommonModule", "Catalog")
            return modelObjectName;
            
        } catch (Exception e) {
            Activator.logError("Error getting collection path", e);
        }
        
        return null;
    }

    /**
     * Reflectively invokes {@code getModelObjectName()} on the adapter.
     * Returns the model object name (e.g. "CommonModule", "Catalog"), or
     * {@code null} when the method is absent or does not return a String.
     */
    private String invokeGetModelObjectName(Object adapter) throws Exception {
        try {
            Method getModelObjectNameMethod = adapter.getClass().getMethod("getModelObjectName");
            Object result = getModelObjectNameMethod.invoke(adapter);
            if (result instanceof String) {
                return (String) result;
            }
        } catch (NoSuchMethodException e) {
            // Method doesn't exist
        }
        return null;
    }

    /**
     * Determines whether the adapter represents a nested collection (i.e. its
     * parent is an {@link EObject}, like Catalog.Products.Attribute). When the
     * reflective {@code getParent(Object)} method is absent this is treated as a
     * top-level collection (returns {@code false}).
     */
    private boolean isNestedCollection(Object adapter) throws Exception {
        try {
            Method getParentMethod = adapter.getClass().getMethod("getParent", Object.class);
            Object parent = getParentMethod.invoke(adapter, adapter);
            return parent instanceof EObject;
        } catch (NoSuchMethodException e) {
            // Method doesn't exist - this is fine, proceed with simple path
            return false;
        }
    }

    /**
     * Gets the project from a navigator adapter.
     */
    private IProject getProjectFromAdapter(Object adapter) {
        if (adapter instanceof IAdaptable adaptable) {
            return adaptable.getAdapter(IProject.class);
        }
        return null;
    }
}
