/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Handler for the "Expand Below" command.
 * Expands all nodes below the currently selected element in the Navigator tree.
 */
public class ExpandBelowHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }
        
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }
        
        var viewPart = page.findView(TagConstants.NAVIGATOR_VIEW_ID);
        if (viewPart instanceof CommonNavigator navigator) {
            CommonViewer viewer = navigator.getCommonViewer();
            if (viewer != null && !viewer.getControl().isDisposed()) {
                expandSelectedElements(viewer);
            }
        }
        
        return null;
    }
    
    /**
     * Expands all elements below each selected element.
     */
    private void expandSelectedElements(TreeViewer viewer) {
        ISelection selection = viewer.getSelection();
        if (selection.isEmpty() || !(selection instanceof IStructuredSelection)) {
            return;
        }
        
        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        for (Object element : structuredSelection.toList()) {
            // Expand all levels below this element
            viewer.expandToLevel(element, TreeViewer.ALL_LEVELS);
        }
    }
}
