/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Registry for metadata formatters.
 * Uses a single universal formatter that can handle any metadata type
 * via dynamic EMF reflection.
 */
public class MetadataFormatterRegistry
{
    private static final UniversalMetadataFormatter UNIVERSAL_FORMATTER = UniversalMetadataFormatter.getInstance();
    
    /**
     * Gets the universal formatter that can handle any metadata object.
     * 
     * @param mdObject The metadata object to format (not used, always returns universal formatter)
     * @return The universal formatter
     */
    public static IMetadataFormatter getFormatter(MdObject mdObject)
    {
        return UNIVERSAL_FORMATTER;
    }
    
    /**
     * Gets the universal formatter.
     * 
     * @param typeName The metadata type name (not used, always returns universal formatter)
     * @return The universal formatter
     */
    public static IMetadataFormatter getFormatter(String typeName)
    {
        return UNIVERSAL_FORMATTER;
    }
    
    /**
     * Formats a metadata object using the universal formatter.
     * 
     * @param mdObject The metadata object to format
     * @param full If true, includes all properties; if false, only basic properties
     * @param language Language code for synonyms
     * @return Formatted markdown string
     */
    public static String format(MdObject mdObject, boolean full, String language)
    {
        return UNIVERSAL_FORMATTER.format(mdObject, full, language);
    }
}
