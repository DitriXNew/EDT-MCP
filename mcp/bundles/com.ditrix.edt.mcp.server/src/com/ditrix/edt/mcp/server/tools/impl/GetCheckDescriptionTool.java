/**
 * Copyright (c) 2025 DitriX
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
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
               "\"checkId\": {\"type\": \"string\", \"description\": \"Check ID (e.g. 'begin-transaction', 'ql-temp-table-index')\"}" + //$NON-NLS-1$
               "}, \"required\": [\"checkId\"]}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String checkId = JsonUtils.extractStringArgument(params, "checkId"); //$NON-NLS-1$
        return getCheckDescription(checkId);
    }
    
    /**
     * Gets check description from the configured folder.
     * 
     * @param checkId the check ID
     * @return JSON string with check description or error
     */
    public static String getCheckDescription(String checkId)
    {
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
        // Validate checkId parameter
        if (checkId == null || checkId.isEmpty())
        {
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"checkId parameter is required\""); //$NON-NLS-1$
            json.append("}"); //$NON-NLS-1$
            return json.toString();
        }
        
        try
        {
            // Get checks folder from preferences
            IPreferenceStore store = Activator.getDefault().getPreferenceStore();
            String checksFolder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
            
            if (checksFolder == null || checksFolder.isEmpty())
            {
                json.append("\"success\": false,"); //$NON-NLS-1$
                json.append("\"error\": \"Check descriptions folder is not configured. "); //$NON-NLS-1$
                json.append("Please set it in Preferences -> MCP Server.\""); //$NON-NLS-1$
                json.append("}"); //$NON-NLS-1$
                return json.toString();
            }
            
            Path folderPath = Paths.get(checksFolder);
            if (!Files.exists(folderPath) || !Files.isDirectory(folderPath))
            {
                json.append("\"success\": false,"); //$NON-NLS-1$
                json.append("\"error\": \"Check descriptions folder does not exist: "); //$NON-NLS-1$
                json.append(JsonUtils.escapeJson(checksFolder)).append("\""); //$NON-NLS-1$
                json.append("}"); //$NON-NLS-1$
                return json.toString();
            }
            
            // Sanitize checkId to prevent path traversal
            String sanitizedCheckId = checkId.replaceAll("[^a-zA-Z0-9_-]", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (!sanitizedCheckId.equals(checkId))
            {
                json.append("\"success\": false,"); //$NON-NLS-1$
                json.append("\"error\": \"Invalid checkId format. Only alphanumeric characters, dashes and underscores are allowed.\""); //$NON-NLS-1$
                json.append("}"); //$NON-NLS-1$
                return json.toString();
            }
            
            // Try to find the file with .md extension
            Path checkFile = folderPath.resolve(checkId + ".md"); //$NON-NLS-1$
            
            if (!Files.exists(checkFile))
            {
                // Try lowercase version
                Path checkFileLower = folderPath.resolve(checkId.toLowerCase() + ".md"); //$NON-NLS-1$
                if (Files.exists(checkFileLower))
                {
                    checkFile = checkFileLower;
                }
                else
                {
                    json.append("\"success\": false,"); //$NON-NLS-1$
                    json.append("\"error\": \"Check description not found for: "); //$NON-NLS-1$
                    json.append(JsonUtils.escapeJson(checkId)).append("\""); //$NON-NLS-1$
                    json.append("}"); //$NON-NLS-1$
                    return json.toString();
                }
            }
            
            // Read file content
            String content = Files.readString(checkFile, StandardCharsets.UTF_8);
            
            json.append("\"success\": true,"); //$NON-NLS-1$
            json.append("\"checkId\": \"").append(JsonUtils.escapeJson(checkId)).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            json.append("\"description\": \"").append(JsonUtils.escapeJson(content)).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        catch (IOException e)
        {
            Activator.logError("Error reading check description for: " + checkId, e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"Failed to read check description: "); //$NON-NLS-1$
            json.append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error getting check description", e); //$NON-NLS-1$
            json.append("\"success\": false,"); //$NON-NLS-1$
            json.append("\"error\": \"").append(JsonUtils.escapeJson(e.getMessage())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        json.append("}"); //$NON-NLS-1$
        return json.toString();
    }
}
