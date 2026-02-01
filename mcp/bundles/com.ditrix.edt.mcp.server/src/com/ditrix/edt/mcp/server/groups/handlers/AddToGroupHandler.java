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
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "Add to Group..." command.
 * Shows a dialog to select target group and adds selected objects to it.
 */
public class AddToGroupHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            return null;
        }
        
        // Collect selected objects
        List<EObject> selectedObjects = new ArrayList<>();
        IProject project = null;
        
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                selectedObjects.add(eObject);
                if (project == null) {
                    project = TagUtils.extractProject(eObject);
                }
            }
        }
        
        if (selectedObjects.isEmpty() || project == null) {
            MessageDialog.openWarning(shell, "Add to Group", 
                "Please select one or more metadata objects to add to a group.");
            return null;
        }
        
        // Get all available groups
        GroupService service = GroupService.getInstance();
        List<Group> allGroups = service.getAllGroups(project);
        
        if (allGroups.isEmpty()) {
            MessageDialog.openInformation(shell, "Add to Group", 
                "No groups exist. Create a group first using 'New Group...' on a collection folder.");
            return null;
        }
        
        // Show group selection dialog
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(shell, new LabelProvider() {
            @Override
            public String getText(Object element) {
                if (element instanceof Group group) {
                    return group.getFullPath();
                }
                return super.getText(element);
            }
        });
        dialog.setTitle("Add to Group");
        dialog.setMessage("Select target group:");
        dialog.setElements(allGroups.toArray());
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
