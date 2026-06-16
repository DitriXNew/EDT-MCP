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
    public static String FilterByTagDialog_Title; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_Description; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_SetButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_TurnOffButton; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_SelectAll; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_DeselectAll; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_SearchPlaceholder; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_EditTag; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_ShowUntaggedOnly; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    public static String FilterByTagDialog_ShowUntaggedOnlyTooltip; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    
    // FilterByTagManager
    public static String FilterByTagManager_FilterName; // NOSONAR Eclipse NLS field: value injected by NLS.initializeMessages, cannot be final; Eclipse NLS field name must equal its .properties key
    
    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }
    
    private Messages() {
    }
}
