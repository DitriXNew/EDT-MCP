/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
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
