/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.tags.ui.ManageTagsDialog;

/**
 * Handler for the "Manage Tags" context menu command.
 * Opens a dialog to add/remove tags for the selected metadata object.
 */
public class ManageTagsHandler extends AbstractTagHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IProject project = getSelectedProject(event);
        EObject mdObject = getSelectedMdObject(event);
        String fqn = extractFqn(mdObject);
        
        if (project == null || fqn == null) {
            return null;
        }
        
        Shell shell = HandlerUtil.getActiveShell(event);
        ManageTagsDialog dialog = new ManageTagsDialog(shell, project, fqn);
        dialog.open();
        
        return null;
    }
}
