/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
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
