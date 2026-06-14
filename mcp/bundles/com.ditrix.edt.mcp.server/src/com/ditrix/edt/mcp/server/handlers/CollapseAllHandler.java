/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.handlers;

import org.eclipse.ui.navigator.CommonViewer;

/**
 * Handler that collapses all nodes in the EDT Navigator tree.
 */
public class CollapseAllHandler extends AbstractNavigatorViewerHandler
{
    @Override
    protected void onViewer(CommonViewer viewer)
    {
        viewer.collapseAll();
    }
}
