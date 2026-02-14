/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.groups;

/**
 * Constants for the groups feature.
 */
public final class GroupConstants {
    
    private GroupConstants() {
        // Prevent instantiation
    }
    
    /**
     * Folder in project where groups are stored.
     */
    public static final String SETTINGS_FOLDER = ".settings";
    
    /**
     * File name for groups storage.
     */
    public static final String GROUPS_FILE = "groups.yaml";
    
    /**
     * Full path to groups file relative to project.
     */
    public static final String GROUPS_PATH = SETTINGS_FOLDER + "/" + GROUPS_FILE;
}
