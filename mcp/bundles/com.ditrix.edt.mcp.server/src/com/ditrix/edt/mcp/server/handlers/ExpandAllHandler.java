/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.handlers;

import org.eclipse.ui.navigator.CommonViewer;

/**
 * Handler for the "Expand All" command.
 * Expands all nodes in the Navigator tree.
 */
public class ExpandAllHandler extends AbstractNavigatorViewerHandler
{
    @Override
    protected void onViewer(CommonViewer viewer)
    {
        viewer.expandAll();
    }
}
