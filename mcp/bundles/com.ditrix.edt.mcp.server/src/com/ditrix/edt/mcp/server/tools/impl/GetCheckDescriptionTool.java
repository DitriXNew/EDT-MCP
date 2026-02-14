/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.eclipse.jface.preference.IPreferenceStore;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

/**
 * Tool to get check description by check ID.
 * Reads markdown files from the configured checks folder.
 */
public class GetCheckDescriptionTool implements IMcpTool
{
    public static final String NAME = "get_check_description"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed description of an EDT check by its ID. " + //$NON-NLS-1$
               "Returns markdown content with check explanation, examples, and how to fix."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("checkId", "Check ID (e.g. 'begin-transaction', 'ql-temp-table-index')", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        if (checkId != null && !checkId.isEmpty())
        {
            return checkId + ".md"; //$NON-NLS-1$
        }
        return getName() + ".md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        return getCheckDescription(checkId);
    }
    
    /**
     * Finds the documentation file for a given check ID.
     * 
     * @param checkId the check ID
     * @return Path to the documentation file, or null if not found or invalid
     */
    private static Path findCheckDocumentationFile(String checkId)
    {
        if (checkId == null || checkId.isEmpty())
        {
            return null;
        }
        
        try
        {
            // Get checks folder from preferences
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
            
            if (checksFolder == null || checksFolder.isEmpty())
            {
                return null;
            }
            
            Path folderPath = Paths.get(checksFolder);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
            {
                return null;
            }
            
            // Sanitize checkId to prevent path traversal
            String sanitizedCheckId = checkId.replaceAll("[^a-zA-Z0-9_-]", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (!sanitizedCheckId.equals(checkId))
            {
                return null;
            }
            
            // Try to find the file with .md extension
            Path checkFile = folderPath.resolve(checkId + ".md"); //$NON-NLS-1$
            if (Files.exists(checkFile))
            {
                return checkFile;
            }
            
            // Try lowercase version
            Path checkFileLower = folderPath.resolve(checkId.toLowerCase() + ".md"); //$NON-NLS-1$
            if (Files.exists(checkFileLower))
            {
                return checkFileLower;
            }
            
            return null;
        }
        catch (Exception e)
        {
            return null;
        }
    }
    
    /**
     * Checks if documentation exists for a given check ID.
     * 
     * @param checkId the check ID
     * @return true if documentation file exists, false otherwise
     */
    public static boolean hasCheckDocumentation(String checkId)
    {
        return findCheckDocumentationFile(checkId) != null;
    }
    
    /**
     * Gets check description from the configured folder.
     * 
     * @param checkId the check ID
     * @return Markdown string with check description or error
     */
    public static String getCheckDescription(String checkId)
    {
        // Validate checkId parameter
        if (checkId == null || checkId.isEmpty())
        {
            return "**Error:** checkId parameter is required"; //$NON-NLS-1$
        }
        
        try
        {
            // Get checks folder from preferences for error messages
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
            
            if (checksFolder == null || checksFolder.isEmpty())
            {
                return "**Error:** Check descriptions folder is not configured.\n\n" + //$NON-NLS-1$
                       "Please set it in Preferences -> MCP Server."; //$NON-NLS-1$
            }
            
            Path folderPath = Paths.get(checksFolder);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
            {
                return "**Error:** Check descriptions folder does not exist: " + checksFolder; //$NON-NLS-1$
            }
            
            // Find the documentation file
            Path checkFile = findCheckDocumentationFile(checkId);
            if (checkFile == null)
            {
                return "**Error:** Check description not found for: " + checkId; //$NON-NLS-1$
            }
            
            // Read and return file content directly (it's already Markdown)
            return Files.readString(checkFile, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            Activator.logError("Error reading check description for: " + checkId, e); //$NON-NLS-1$
            return "**Error:** Failed to read check description: " + e.getMessage(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting check description", e); //$NON-NLS-1$
            return "**Error:** " + e.getMessage(); //$NON-NLS-1$
        }
    }
}
