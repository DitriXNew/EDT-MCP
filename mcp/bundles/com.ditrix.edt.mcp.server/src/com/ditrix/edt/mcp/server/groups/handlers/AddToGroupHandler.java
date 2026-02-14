/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.handlers;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.IGroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "Add to Group..." command.
 * Shows a dialog to select target group and adds selected objects to it.
 * Only enabled for top-level metadata objects (with FQN like Type.Name).
 */
public class AddToGroupHandler extends AbstractHandler {
    
    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection contains at least one top-level object
        Object selection = org.eclipse.ui.handlers.HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        // Check if any selected object is a top-level object
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                String fqn = TagUtils.extractFqn(eObject);
                if (fqn != null) {
                    String[] parts = fqn.split("\\.");
                    if (parts.length == 2) {
                        // Found at least one top-level object
                        setBaseEnabled(true);
                        return;
                    }
                }
            }
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
        
        // Collect selected top-level objects and determine their type
        List<EObject> selectedObjects = new ArrayList<>();
        IProject project = null;
        String objectType = null; // e.g., "Catalog", "CommonModule"
        
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                String fqn = TagUtils.extractFqn(eObject);
                if (fqn != null) {
                    String[] parts = fqn.split("\\.");
                    if (parts.length == 2) {
                        // Top-level object
                        selectedObjects.add(eObject);
                        if (project == null) {
                            project = TagUtils.extractProject(eObject);
                        }
                        if (objectType == null) {
                            objectType = parts[0]; // e.g., "Catalog"
                        }
                    }
                }
            }
        }
        
        if (selectedObjects.isEmpty() || project == null || objectType == null) {
            MessageDialog.openWarning(shell, "Add to Group", 
                "Please select one or more top-level metadata objects to add to a group.");
            return null;
        }
        
        // Get groups filtered by object type
        IGroupService service = Activator.getGroupServiceStatic();
        final String filterPath = objectType;
        List<Group> matchingGroups = service.getAllGroups(project).stream()
            .filter(g -> filterPath.equals(g.getPath()))
            .toList();
        
        if (matchingGroups.isEmpty()) {
            MessageDialog.openInformation(shell, "Add to Group", 
                "No groups exist for " + objectType + ".\n" +
                "Create a group first using 'New Group...' on the " + objectType + " folder.");
            return null;
        }
        
        // Show group selection dialog with group names only
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Group group) {
                    return group.getName();
                }
                return super.getText(element);
            }
        });
        dialog.setTitle("Add to Group");
        dialog.setMessage("Select target group for " + objectType + ":");
        dialog.setElements(matchingGroups.toArray());
        dialog.setMultipleSelection(false);
        
        if (dialog.open() != Window.OK) {
            return null;
        }
        
        Object[] result = dialog.getResult();
        if (result == null || result.length == 0 || !(result[0] instanceof Group targetGroup)) {
            return null;
        }
        
        // Add all selected objects to the group
        int successCount = 0;
        int failCount = 0;
        
        for (EObject eObject : selectedObjects) {
            String fqn = TagUtils.extractFqn(eObject);
            if (fqn != null) {
                try {
                    boolean added = service.addObjectToGroup(project, fqn, targetGroup.getFullPath());
                    if (added) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    Activator.logError("Failed to add " + fqn + " to group", e);
                    failCount++;
                }
            }
        }
        
        // Show result
        if (successCount > 0) {
            MessageDialog.openInformation(shell, "Add to Group", 
                "Added " + successCount + " object(s) to group '" + targetGroup.getName() + "'.");
        } else if (failCount > 0) {
            MessageDialog.openWarning(shell, "Add to Group", 
                "Failed to add objects. They may already be in the group.");
        }
        
        return null;
    }
}
