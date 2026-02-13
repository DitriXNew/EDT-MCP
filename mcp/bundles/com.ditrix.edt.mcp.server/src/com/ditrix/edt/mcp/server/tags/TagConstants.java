/*******************************************************************************
 * Copyright (c) 2025 DITRIX
 * 
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 * 
 * SPDX-License-Identifier: EPL-2.0
 ******************************************************************************/
package com.ditrix.edt.mcp.server.tags;

/**
 * Constants used across the tags module.
 * Centralizes magic strings and configuration values.
 * 
 * <p>Note: Metadata type constants are intentionally NOT defined here.
 * Use MdClassPackage.eINSTANCE.getEClassifiers() for reflection-based access
 * to all EClass types, and eClass().getName() for dynamic type name retrieval.
 * This approach supports new metadata types that may be added in future platform versions.</p>
 */
public final class TagConstants {
    
    private TagConstants() {
        // Utility class
    }
    
    // === Storage Constants ===
    
    /** Folder for project settings */
    public static final String SETTINGS_FOLDER = ".settings";
    
    /** YAML file for tag storage */
    public static final String TAGS_FILE = "metadata-tags.yaml";
    
    // === URI Schemes ===
    
    /** BM (Big Model) URI scheme */
    public static final String BM_URI_SCHEME = "bm://";
    
    // === View IDs ===
    
    /** EDT Navigator view ID */
    public static final String NAVIGATOR_VIEW_ID = "com._1c.g5.v8.dt.ui2.navigator";
    
    // === Default Values ===
    
    /** Default tag color (gray) */
    public static final String DEFAULT_TAG_COLOR = "#808080";
    
    /** Default color icon size in pixels */
    public static final int COLOR_ICON_SIZE_SMALL = 12;
    public static final int COLOR_ICON_SIZE_NORMAL = 16;
    public static final int COLOR_ICON_SIZE_LARGE = 24;
    
    // === Method Names (for reflection - used only when EDT interfaces not available) ===
    
    /** getModel() method for navigator adapters */
    public static final String METHOD_GET_MODEL = "getModel";
    
    /** getProject() method for getting project from objects */
    public static final String METHOD_GET_PROJECT = "getProject";
}
