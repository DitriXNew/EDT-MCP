/**
 * Copyright (c) 2025, Dmitriy Safonov
 * https://github.com/desfarik/
 * 
 * Handler for "Collapse All" command in EDT Navigator.
 * Collapses all nodes in the Navigator tree.
 */
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
 * Handler that collapses all nodes in the EDT Navigator tree.
 */
public class CollapseAllHandler extends AbstractHandler {

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

        // Get Navigator view
        var viewPart = page.findView(TagConstants.NAVIGATOR_VIEW_ID);
        if (viewPart instanceof CommonNavigator navigator) {
            CommonViewer viewer = navigator.getCommonViewer();
            if (viewer != null && !viewer.getTree().isDisposed()) {
                viewer.collapseAll();
            }
        }

        return null;
    }
}
