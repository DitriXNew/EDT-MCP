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
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter.GroupedObjectPlaceholder;

/**
 * Handler for the "Remove from Group" command.
 * Removes selected objects from their groups, returning them to the original location.
 * Only works on GroupedObjectPlaceholder elements (objects displayed inside a group).
 */
public class RemoveFromGroupHandler extends AbstractHandler {
    
    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection contains GroupedObjectPlaceholder elements
        Object selection = HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        // Enable if any selected element is a GroupedObjectPlaceholder
        for (Object element : structuredSelection.toList()) {
            if (element instanceof GroupedObjectPlaceholder) {
                setBaseEnabled(true);
                return;
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
        
        // Collect selected placeholders
        List<GroupedObjectPlaceholder> placeholders = new ArrayList<>();
        IProject project = null;
        
        for (Object element : structuredSelection.toList()) {
            if (element instanceof GroupedObjectPlaceholder placeholder) {
                placeholders.add(placeholder);
                if (project == null) {
                    project = placeholder.getProject();
                }
            }
        }
        
        if (placeholders.isEmpty() || project == null) {
            return null;
        }
        
        // Confirm removal
        String message = placeholders.size() == 1
            ? "Remove this object from its group?"
            : "Remove " + placeholders.size() + " objects from their groups?";
        
        if (!MessageDialog.openConfirm(shell, "Remove from Group", message)) {
            return null;
        }
        
        // Remove objects from their groups
        GroupService service = GroupService.getInstance();
        int successCount = 0;
        int failCount = 0;
        
        for (GroupedObjectPlaceholder placeholder : placeholders) {
            try {
                boolean removed = service.removeObjectFromGroup(project, placeholder.getObjectFqn());
                if (removed) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                Activator.logError("Failed to remove " + placeholder.getObjectFqn() + " from group", e);
                failCount++;
            }
        }
        
        // Show result
        if (successCount > 0 && failCount == 0) {
            MessageDialog.openInformation(shell, "Remove from Group", 
                "Removed " + successCount + " object(s) from their groups.");
        } else if (failCount > 0) {
            MessageDialog.openWarning(shell, "Remove from Group", 
                "Removed " + successCount + " object(s), failed to remove " + failCount + " object(s).");
        }
        
        return null;
    }
}
