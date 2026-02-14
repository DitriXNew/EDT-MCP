/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;

import com.ditrix.edt.mcp.server.tags.ui.FilterByTagManager;

/**
 * Handler for the "Filter by Tag" command.
 * Opens a dialog to select tags for filtering the navigator.
 */
public class FilterByTagHandler extends AbstractHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        FilterByTagManager.getInstance().openFilterDialog();
        return null;
    }
}
