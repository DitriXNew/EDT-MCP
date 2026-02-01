/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.groups.ui;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.navigator.CommonActionProvider;
import org.eclipse.ui.navigator.ICommonActionConstants;
import org.eclipse.ui.navigator.ICommonActionExtensionSite;

import com._1c.g5.v8.dt.ui.util.OpenHelper;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.groups.ui.GroupNavigatorAdapter.GroupedObjectPlaceholder;

/**
 * Action provider for GroupedObjectPlaceholder.
 * Provides the "Open" action for double-click on objects inside groups.
 */
public class GroupActionProvider extends CommonActionProvider {
    
    private IAction openAction;
    private OpenHelper openHelper;
    
    @Override
    public void init(ICommonActionExtensionSite site) {
        super.init(site);
        
        openHelper = new OpenHelper();
        
        openAction = new Action("Open") {
            @Override
            public void run() {
                IStructuredSelection selection = (IStructuredSelection) getContext().getSelection();
                if (selection.isEmpty()) {
                    return;
                }
                
                Object element = selection.getFirstElement();
                if (element instanceof GroupedObjectPlaceholder placeholder) {
                    openPlaceholder(placeholder);
                }
            }
        };
    }
    
    @Override
    public void fillActionBars(IActionBars actionBars) {
        super.fillActionBars(actionBars);
        // Register as the default Open action (double-click)
        actionBars.setGlobalActionHandler(ICommonActionConstants.OPEN, openAction);
    }
    
    /**
     * Opens the resolved object from a placeholder using EDT's OpenHelper.
     */
    private void openPlaceholder(GroupedObjectPlaceholder placeholder) {
        EObject resolvedObject = placeholder.getResolvedObject();
        if (resolvedObject == null) {
            Activator.logInfo("Cannot open placeholder: resolved object is null for " + placeholder.getObjectFqn());
            return;
        }
        
        try {
            // Use EDT's OpenHelper to open the editor
            openHelper.openEditor(resolvedObject);
        } catch (Exception e) {
            Activator.logError("Error opening editor for " + placeholder.getObjectFqn(), e);
        }
    }
}
