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
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;

import com.ditrix.edt.mcp.server.tags.TagConstants;

/**
 * Handler for the "Expand All" command.
 * Expands all nodes in the Navigator tree.
 */
public class ExpandAllHandler extends AbstractHandler {
    
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
                viewer.expandAll();
            }
        }
        
        return null;
    }
}
