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
import org.eclipse.jface.window.Window;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.EditGroupDialog;

/**
 * Handler for the "Rename Group" command.
 * Allows editing name and description of a virtual folder group in the Navigator.
 */
public class RenameGroupHandler extends AbstractGroupHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        GroupSelection sel = extractSelection(event);
        if (sel == null || !sel.isValid()) {
            return null;
        }
        
        Group group = sel.group;
        String oldFullPath = group.getFullPath();
        String parentPath = group.getPath();
        
        // Show dialog for editing name and description
        EditGroupDialog dialog = new EditGroupDialog(sel.shell, group, name -> {
            if (name == null || name.trim().isEmpty()) {
                return "Group name cannot be empty";
            }
            String trimmed = name.trim();
            if (trimmed.contains("/") || trimmed.contains("\\")) {
                return "Group name cannot contain path separators";
            }
            // Check if same as current name
            if (trimmed.equals(group.getName())) {
                return null; // Same name is OK
            }
            // Check for existing group with new name
            String newFullPath = (parentPath == null || parentPath.isEmpty()) 
                ? trimmed 
                : parentPath + "/" + trimmed;
            if (getGroupService().getGroupStorage(sel.project).getGroupByFullPath(newFullPath) != null) {
                return "A group with this name already exists";
            }
            return null;
        });
        
        if (dialog.open() == Window.OK) {
            String newName = dialog.getGroupName();
            String description = dialog.getGroupDescription();
            
            try {
                boolean updated = getGroupService().updateGroup(sel.project, oldFullPath, newName, 
                    description.isEmpty() ? null : description);
                
                if (!updated) {
                    Activator.logInfo("Failed to update group: " + oldFullPath);
                }
                
            } catch (Exception e) {
                Activator.logError("Error updating group", e);
                throw new ExecutionException("Failed to update group", e);
            }
        }
        
        return null;
    }
}
