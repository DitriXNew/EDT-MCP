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

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.model.Group;

/**
 * Handler for the "Delete Group" command.
 * Deletes a virtual folder group from the Navigator.
 * Objects in the group return to their original location.
 */
public class DeleteGroupHandler extends AbstractGroupHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        GroupSelection sel = extractSelection(event);
        if (sel == null || !sel.isValid()) {
            return null;
        }
        
        Group group = sel.group;
        
        // Confirm deletion
        String message = "Are you sure you want to delete the group '" + group.getName() + "'?";
        if (!group.getChildren().isEmpty()) {
            message += "\n\n" + group.getChildren().size() + " object(s) will return to their original location.";
        }
        
        boolean confirmed = MessageDialog.openConfirm(
            sel.shell,
            "Delete Group",
            message
        );
        
        if (!confirmed) {
            return null;
        }
        
        try {
            boolean deleted = getGroupService().deleteGroup(sel.project, group.getFullPath());
            
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
