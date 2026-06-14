/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
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
 * Base for the Navigator-tree command handlers (Expand All / Expand Below /
 * Collapse All). Resolves the active EDT Navigator {@link CommonViewer} — guarding
 * the active workbench window, page, the located view and a disposed control — and
 * delegates the actual tree action to {@link #onViewer(CommonViewer)}. Each handler
 * supplies only that one action.
 */
public abstract class AbstractNavigatorViewerHandler extends AbstractHandler
{
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException
    {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null)
        {
            return null;
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null)
        {
            return null;
        }

        var viewPart = page.findView(TagConstants.NAVIGATOR_VIEW_ID);
        if (viewPart instanceof CommonNavigator navigator)
        {
            CommonViewer viewer = navigator.getCommonViewer();
            if (viewer != null && !viewer.getControl().isDisposed())
            {
                onViewer(viewer);
            }
        }

        return null;
    }

    /**
     * Performs this handler's action on the resolved, non-disposed Navigator viewer.
     *
     * @param viewer the active EDT Navigator viewer (never {@code null})
     */
    protected abstract void onViewer(CommonViewer viewer);
}
