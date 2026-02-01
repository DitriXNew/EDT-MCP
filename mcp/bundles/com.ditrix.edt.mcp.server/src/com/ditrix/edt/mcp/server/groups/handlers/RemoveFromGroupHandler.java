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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.tags.TagUtils;

/**
 * Handler for the "Remove from Group" command.
 * Removes selected objects from their groups, returning them to the original location.
 * Works on EObjects that are currently in a group.
 */
public class RemoveFromGroupHandler extends AbstractHandler {
    
    @Override
    public void setEnabled(Object evaluationContext) {
        // Check if selection contains objects that are in groups
        Object selection = HandlerUtil.getVariable(evaluationContext, "selection");
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            setBaseEnabled(false);
            return;
        }
        
        GroupService service = GroupService.getInstance();
        
        // Enable if any selected element is an EObject in a group
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                IProject project = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                if (project != null && fqn != null) {
                    Group group = service.findGroupForObject(project, fqn);
                    if (group != null) {
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
        
        GroupService service = GroupService.getInstance();
        
        // Collect selected objects that are in groups
        List<ObjectInGroup> objectsToRemove = new ArrayList<>();
        IProject project = null;
        
        for (Object element : structuredSelection.toList()) {
            if (element instanceof EObject eObject) {
                IProject objProject = TagUtils.extractProject(eObject);
                String fqn = TagUtils.extractFqn(eObject);
                if (objProject != null && fqn != null) {
                    Group group = service.findGroupForObject(objProject, fqn);
                    if (group != null) {
                        objectsToRemove.add(new ObjectInGroup(objProject, fqn));
                        if (project == null) {
                            project = objProject;
                        }
                    }
                }
            }
        }
        
        if (objectsToRemove.isEmpty() || project == null) {
            return null;
        }
        
        // Confirm removal
        String message = objectsToRemove.size() == 1
            ? "Remove this object from its group?"
            : "Remove " + objectsToRemove.size() + " objects from their groups?";
        
        if (!MessageDialog.openConfirm(shell, "Remove from Group", message)) {
            return null;
        }
        
        // Remove objects from their groups
        int successCount = 0;
        int failCount = 0;
        
        for (ObjectInGroup obj : objectsToRemove) {
            try {
                boolean removed = service.removeObjectFromGroup(obj.project, obj.fqn);
                if (removed) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                Activator.logError("Failed to remove " + obj.fqn + " from group", e);
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
    
    /**
     * Helper record to hold project and FQN for removal.
     */
    private record ObjectInGroup(IProject project, String fqn) {}
}
