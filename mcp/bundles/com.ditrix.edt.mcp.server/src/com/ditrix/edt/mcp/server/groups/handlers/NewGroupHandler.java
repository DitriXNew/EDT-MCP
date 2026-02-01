/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.handlers;

import java.lang.reflect.Method;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.model.IWorkbenchAdapter;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "New Group" command.
 * Creates a new virtual folder group in the Navigator.
 */
public class NewGroupHandler extends AbstractHandler {
    
    private static final String COLLECTION_ADAPTER_CLASS_NAME = 
        "com._1c.g5.v8.dt.navigator.adapters.CollectionNavigatorAdapterBase";
    
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
        String parentPath = null;
        IProject project = null;
        
        if (selected instanceof GroupNavigatorAdapter groupAdapter) {
            // Creating inside another group
            parentPath = groupAdapter.getGroup().getFullPath();
            project = groupAdapter.getProject();
        } else if (isCollectionAdapter(selected)) {
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
        
        // Show input dialog for group name
        final IProject finalProject = project;
        final String finalParentPath = parentPath;
        
        InputDialog dialog = new InputDialog(
            shell,
            "New Group",
            "Enter group name:",
            "",
            new IInputValidator() {
                @Override
                public String isValid(String newText) {
                    if (newText == null || newText.trim().isEmpty()) {
                        return "Group name cannot be empty";
                    }
                    String trimmed = newText.trim();
                    if (trimmed.contains("/") || trimmed.contains("\\")) {
                        return "Group name cannot contain path separators";
                    }
                    // Check for existing group with same name
                    GroupService service = GroupService.getInstance();
                    String fullPath = finalParentPath.isEmpty() 
                        ? trimmed 
                        : finalParentPath + "/" + trimmed;
                    if (service.getGroupStorage(finalProject).getGroupByFullPath(fullPath) != null) {
                        return "A group with this name already exists";
                    }
                    return null;
                }
            }
        );
        
        if (dialog.open() == Window.OK) {
            String groupName = dialog.getValue().trim();
            
            try {
                GroupService service = GroupService.getInstance();
                Group newGroup = service.createGroup(project, groupName, parentPath, null);
                
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
     * Gets the full collection path for a collection adapter.
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
