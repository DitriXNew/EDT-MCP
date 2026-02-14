/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tags.ui;

import org.eclipse.osgi.util.NLS;

/**
 * Messages for tag UI components.
 */
public class Messages extends NLS {
    
    private static final String BUNDLE_NAME = "com.ditrix.edt.mcp.server.tags.ui.messages"; //$NON-NLS-1$
    
    // FilterByTagDialog
    public static String FilterByTagDialog_Title;
    public static String FilterByTagDialog_Description;
    public static String FilterByTagDialog_SetButton;
    public static String FilterByTagDialog_TurnOffButton;
    public static String FilterByTagDialog_SelectAll;
    public static String FilterByTagDialog_DeselectAll;
    public static String FilterByTagDialog_SearchPlaceholder;
    public static String FilterByTagDialog_EditTag;
    public static String FilterByTagDialog_ShowUntaggedOnly;
    public static String FilterByTagDialog_ShowUntaggedOnlyTooltip;
    
    // FilterByTagManager
    public static String FilterByTagManager_FilterName;
    
    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }
    
    private Messages() {
    }
}
