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
    
    /** Default result limit for tools */
    public static final String PREF_DEFAULT_LIMIT = "mcpDefaultLimit"; //$NON-NLS-1$
    
    /** Maximum result limit for tools */
    public static final String PREF_MAX_LIMIT = "mcpMaxLimit"; //$NON-NLS-1$
    
    /** Plain text mode (Cursor compatibility) - returns text instead of embedded resources */
    public static final String PREF_PLAIN_TEXT_MODE = "mcpPlainTextMode"; //$NON-NLS-1$
    
    /** Default port */
    public static final int DEFAULT_PORT = 8765;
    
    /** Default auto-start */
    public static final boolean DEFAULT_AUTO_START = false;
    
    /** Default checks folder (empty - feature disabled) */
    public static final String DEFAULT_CHECKS_FOLDER = ""; //$NON-NLS-1$
    
    /** Default result limit */
    public static final int DEFAULT_DEFAULT_LIMIT = 100;
    
    /** Default maximum limit */
    public static final int DEFAULT_MAX_LIMIT = 1000;
    
    /** Default plain text mode (disabled - use embedded resources by default) */
    public static final boolean DEFAULT_PLAIN_TEXT_MODE = false;
    
    // === Tag decoration preferences ===
    
    /** Show tags in navigator tree */
    public static final String PREF_TAGS_SHOW_IN_NAVIGATOR = "tags.showInNavigator"; //$NON-NLS-1$
    
    /** Tag decoration style */
    public static final String PREF_TAGS_DECORATION_STYLE = "tags.decorationStyle"; //$NON-NLS-1$
    
    /** Decoration style: show all tags as suffix */
    public static final String TAGS_STYLE_SUFFIX = "suffix"; //$NON-NLS-1$
    
    /** Decoration style: show only first tag */
    public static final String TAGS_STYLE_FIRST_TAG = "firstTag"; //$NON-NLS-1$
    
    /** Decoration style: show tag count */
    public static final String TAGS_STYLE_COUNT = "count"; //$NON-NLS-1$
    
    /** Default: show tags in navigator */
    public static final boolean DEFAULT_TAGS_SHOW_IN_NAVIGATOR = true;
    
    /** Default decoration style */
    public static final String DEFAULT_TAGS_DECORATION_STYLE = TAGS_STYLE_SUFFIX;
    
    private PreferenceConstants()
    {
        // Utility class
    }
}
