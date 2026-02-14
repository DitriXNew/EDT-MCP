/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Interface for metadata formatters.
 * Defines methods for formatting metadata objects to markdown.
 */
public interface IMetadataFormatter
{
    /**
     * Gets the metadata type this formatter handles.
     * 
     * @return The metadata type name, or "*" for universal formatters
     */
    String getMetadataType();
    
    /**
     * Checks if this formatter can format the given metadata object.
     * 
     * @param mdObject The metadata object to check
     * @return true if this formatter can format the object
     */
    boolean canFormat(MdObject mdObject);
    
    /**
     * Formats a metadata object to markdown.
     * 
     * @param mdObject The metadata object to format
     * @param full If true, includes all properties; if false, only basic properties
     * @param language Language code for synonyms
     * @return Formatted markdown string
     */
    String format(MdObject mdObject, boolean full, String language);
}
