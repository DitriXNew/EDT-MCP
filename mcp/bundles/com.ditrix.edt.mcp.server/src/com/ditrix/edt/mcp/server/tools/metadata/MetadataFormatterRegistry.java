/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.metadata;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.metadata.mdclass.MdObject;

/**
 * Registry for metadata formatters.
 * Delegates all formatting to the {@link UniversalMetadataFormatter},
 * which handles any metadata type via dynamic EMF reflection.
 */
public class MetadataFormatterRegistry
{
    private MetadataFormatterRegistry()
    {
        // Utility class
    }
    
    /**
     * Returns the universal formatter instance.
     * 
     * @return the universal formatter
     */
    public static IMetadataFormatter getFormatter()
    {
        return UniversalMetadataFormatter.getInstance();
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
        return UniversalMetadataFormatter.getInstance().format(mdObject, full, language);
    }

    /** Includes base-type comparisons for adopted attributes of the project. */
    public static String format(MdObject mdObject, boolean full, String language, IProject project)
    {
        return project == null ? format(mdObject, full, language)
            : new UniversalMetadataFormatter(project).format(mdObject, full, language);
    }
}
