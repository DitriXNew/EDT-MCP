/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.handlers;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.ui.navigator.CommonViewer;

/**
 * Handler for the "Expand Below" command.
 * Expands all nodes below the currently selected element in the Navigator tree.
 */
public class ExpandBelowHandler extends AbstractNavigatorViewerHandler
{
    @Override
    protected void onViewer(CommonViewer viewer)
    {
        expandSelectedElements(viewer);
    }

    /**
     * Expands all elements below each selected element.
     */
    private void expandSelectedElements(TreeViewer viewer)
    {
        ISelection selection = viewer.getSelection();
        if (selection.isEmpty() || !(selection instanceof IStructuredSelection))
        {
            return;
        }

        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        for (Object element : structuredSelection.toList())
        {
            // Expand all levels below this element
            viewer.expandToLevel(element, TreeViewer.ALL_LEVELS);
        }
    }
}
