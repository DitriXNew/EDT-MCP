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
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;

/**
 * Handler for the "Delete Group" command.
 * Deletes a virtual folder group from the Navigator.
 * Objects in the group return to their original location.
 */
public class DeleteGroupHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        
        if (!(selection instanceof IStructuredSelection structuredSelection)) {
            return null;
        }
        
        Object selected = structuredSelection.getFirstElement();
        if (!(selected instanceof GroupNavigatorAdapter groupAdapter)) {
            return null;
        }
        
        Group group = groupAdapter.getGroup();
        IProject project = groupAdapter.getProject();
        
        // Confirm deletion
        String message = "Are you sure you want to delete the group '" + group.getName() + "'?";
        if (!group.getChildren().isEmpty()) {
            message += "\n\n" + group.getChildren().size() + " object(s) will return to their original location.";
        }
        
        boolean confirmed = MessageDialog.openConfirm(
            shell,
            "Delete Group",
            message
        );
        
        if (!confirmed) {
            return null;
        }
        
        try {
            GroupService service = GroupService.getInstance();
            boolean deleted = service.deleteGroup(project, group.getFullPath());
            
            if (!deleted) {
                Activator.logInfo("Failed to delete group: " + group.getFullPath());
            }
            
        } catch (Exception e) {
            Activator.logError("Error deleting group", e);
            throw new ExecutionException("Failed to delete group", e);
        }
        
        return null;
    }
}
