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
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.GroupService;
import com.ditrix.edt.mcp.server.groups.model.Group;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter;

/**
 * Handler for the "Rename Group" command.
 * Renames a virtual folder group in the Navigator.
 */
public class RenameGroupHandler extends AbstractHandler {
    
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
        String oldFullPath = group.getFullPath();
        String parentPath = group.getPath();
        
        // Show input dialog for new name
        InputDialog dialog = new InputDialog(
            shell,
            "Rename Group",
            "Enter new group name:",
            group.getName(),
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
                    // Check if same as current name
                    if (trimmed.equals(group.getName())) {
                        return null; // Same name is OK
                    }
                    // Check for existing group with new name
                    GroupService service = GroupService.getInstance();
                    String newFullPath = (parentPath == null || parentPath.isEmpty()) 
                        ? trimmed 
                        : parentPath + "/" + trimmed;
                    if (service.getGroupStorage(project).getGroupByFullPath(newFullPath) != null) {
                        return "A group with this name already exists";
                    }
                    return null;
                }
            }
        );
        
        if (dialog.open() == Window.OK) {
            String newName = dialog.getValue().trim();
            
            // Skip if name didn't change
            if (newName.equals(group.getName())) {
                return null;
            }
            
            try {
                GroupService service = GroupService.getInstance();
                boolean renamed = service.renameGroup(project, oldFullPath, newName);
                
                if (!renamed) {
                    Activator.logInfo("Failed to rename group: " + oldFullPath);
                }
                
            } catch (Exception e) {
                Activator.logError("Error renaming group", e);
                throw new ExecutionException("Failed to rename group", e);
            }
        }
        
        return null;
    }
}
