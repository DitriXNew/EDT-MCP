/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups;

import org.eclipse.core.resources.IProject;

/**
 * Listener interface for group change events.
 */
public interface IGroupChangeListener {
    
    /**
     * Called when groups have changed for a project.
     * 
     * @param project the project whose groups changed
     */
    void onGroupsChanged(IProject project);
}
