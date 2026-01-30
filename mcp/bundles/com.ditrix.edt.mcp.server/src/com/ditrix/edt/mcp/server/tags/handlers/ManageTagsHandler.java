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
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.tags.ui.ManageTagsDialog;

/**
 * Handler for the "Manage Tags" context menu command.
 * Opens a dialog to add/remove tags for the selected metadata object.
 */
public class ManageTagsHandler extends AbstractTagHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Activator.logInfo("ManageTagsHandler.execute() called");
        
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        Activator.logInfo("Selection: " + selection);
        
        if (selection instanceof IStructuredSelection ssel) {
            Object firstElement = ssel.getFirstElement();
            Activator.logInfo("First element: " + firstElement);
            Activator.logInfo("Element class: " + (firstElement != null ? firstElement.getClass().getName() : "null"));
        }
        
        IProject project = getSelectedProject(event);
        EObject mdObject = getSelectedMdObject(event);
        String fqn = extractFqn(mdObject);
        
        Activator.logInfo("Project: " + project + ", MdObject: " + mdObject + ", FQN: " + fqn);
        
        if (project == null || fqn == null) {
            Activator.logInfo("Project or FQN is null, returning");
            return null;
        }
        
        Shell shell = HandlerUtil.getActiveShell(event);
        ManageTagsDialog dialog = new ManageTagsDialog(shell, project, fqn);
        dialog.open();
        
        return null;
    }
}
