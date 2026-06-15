/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
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
        EditGroupDialog dialog = new EditGroupDialog(sel.shell, group,
            name -> validateNewGroupName(name, group, parentPath, sel.project));
        
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

    /**
     * Validates a proposed new group name for an in-place rename. Returns an
     * error message to surface in the dialog, or {@code null} when the name is
     * acceptable (including when it is unchanged).
     *
     * @param name the proposed name as entered (may be {@code null})
     * @param group the group being renamed
     * @param parentPath the path of the group's parent (may be {@code null} or empty for a root group)
     * @param project the project owning the group, used to look up name collisions
     * @return an error message, or {@code null} when the name is valid
     */
    private String validateNewGroupName(String name, Group group, String parentPath, IProject project) {
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
        if (getGroupService().getGroupStorage(project).getGroupByFullPath(newFullPath) != null) {
            return "A group with this name already exists";
        }
        return null;
    }
}
