/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.preferences;

/**
 * Plugin preference constants.
 */
public final class PreferenceConstants
{
    /** MCP server port */
    public static final String PREF_PORT = "mcpServerPort"; //$NON-NLS-1$
    
    /** Auto-start on EDT startup */
    public static final String PREF_AUTO_START = "mcpServerAutoStart"; //$NON-NLS-1$
    
    /** Path to check descriptions folder */
    public static final String PREF_CHECKS_FOLDER = "mcpChecksFolder"; //$NON-NLS-1$
    
    /** Default port */
    public static final int DEFAULT_PORT = 8765;
    
    /** Default auto-start */
    public static final boolean DEFAULT_AUTO_START = false;
    
    /** Default checks folder (empty - feature disabled) */
    public static final String DEFAULT_CHECKS_FOLDER = ""; //$NON-NLS-1$
    
    private PreferenceConstants()
    {
        // Utility class
    }
}
