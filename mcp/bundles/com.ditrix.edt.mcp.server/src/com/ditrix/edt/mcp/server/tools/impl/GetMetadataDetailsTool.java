/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;

/**
 * Tool to get detailed properties of metadata objects from 1C configuration.
 * Supports sections: basic, attributes, tabular, forms, commands.
 */
public class GetMetadataDetailsTool implements IMcpTool
{
    public static final String NAME = "get_metadata_details"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get detailed properties of metadata objects from 1C configuration. " + //$NON-NLS-1$
               "Returns basic info by default, or full details with 'full: true'."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name (required)", true) //$NON-NLS-1$
            .stringArrayProperty("objectFqns", //$NON-NLS-1$
                "Array of FQNs (e.g. ['Catalog.Products', 'Document.SalesOrder']). " + //$NON-NLS-1$
                "Russian type names are also supported (e.g. '\u0421\u043F\u0440\u0430\u0432\u043E\u0447\u043D\u0438\u043A.\u041D\u043E\u043C\u0435\u043D\u043A\u043B\u0430\u0442\u0443\u0440\u0430'). Required.", //$NON-NLS-1$
                true)
            .booleanProperty("full", //$NON-NLS-1$
                "Return all properties (true) or only key info (false). Default: false") //$NON-NLS-1$
            .stringProperty("language", //$NON-NLS-1$
                "Language code for synonyms (e.g. 'en', 'ru'). Uses configuration default if not specified.") //$NON-NLS-1$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.MARKDOWN;
    }
    
    @Override
    public String getResultFileName(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        if (projectName != null && !projectName.isEmpty())
        {
            return "metadata-details-" + projectName.toLowerCase() + ".md"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return "metadata-details.md"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        List<String> objectFqns = JsonUtils.extractArrayArgument(params, "objectFqns"); //$NON-NLS-1$
        String fullStr = JsonUtils.extractStringArgument(params, "full"); //$NON-NLS-1$
        String language = JsonUtils.extractStringArgument(params, "language"); //$NON-NLS-1$
        
        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return "Error: projectName is required"; //$NON-NLS-1$
        }
        
        if (objectFqns == null || objectFqns.isEmpty())
        {
            return "Error: objectFqns is required (array of FQNs like 'Catalog.Products')"; //$NON-NLS-1$
        }
        
        boolean full = "true".equalsIgnoreCase(fullStr); //$NON-NLS-1$
        
        // Execute on UI thread
        AtomicReference<String> resultRef = new AtomicReference<>();
        final List<String> fqns = objectFqns;
        final boolean fullMode = full;
        final String lang = language;
        
        Display display = PlatformUI.getWorkbench().getDisplay();
        display.syncExec(() -> {
            try
            {
                String result = getMetadataDetailsInternal(projectName, fqns, fullMode, lang);
                resultRef.set(result);
            }
            catch (Exception e)
            {
                Activator.logError("Error getting metadata details", e); //$NON-NLS-1$
                resultRef.set("Error: " + e.getMessage()); //$NON-NLS-1$
            }
        });
        
        return resultRef.get();
    }
    
    /**
     * Internal implementation that runs on UI thread.
     */
    private String getMetadataDetailsInternal(String projectName, List<String> objectFqns,
                                               boolean full, String language)
    {
        // Get project
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (project == null || !project.exists())
        {
            return "Error: Project not found: " + projectName; //$NON-NLS-1$
        }
        
        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return "Error: Configuration provider not available"; //$NON-NLS-1$
        }
        
        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return "Error: Could not get configuration for project: " + projectName; //$NON-NLS-1$
        }
        
        // Determine language for synonyms
        String effectiveLanguage = language;
        if (effectiveLanguage == null || effectiveLanguage.isEmpty())
        {
            if (config.getDefaultLanguage() != null)
            {
                effectiveLanguage = config.getDefaultLanguage().getName();
            }
            else
            {
                effectiveLanguage = "ru"; //$NON-NLS-1$
            }
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Details: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        
        // Process each FQN
        for (String fqn : objectFqns)
        {
            String details = formatObjectDetails(config, fqn, full, effectiveLanguage);
            sb.append(details);
            sb.append("\n---\n\n"); //$NON-NLS-1$
        }
        
        return sb.toString();
    }
    
    /**
     * Formats details for a single metadata object.
     */
    private String formatObjectDetails(Configuration config, String fqn,
                                        boolean full, String language)
    {
        // Parse FQN: Type.Name
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "**Error:** Invalid FQN: " + fqn + ". Expected format: Type.Name (e.g. Catalog.Products)\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        String mdType = parts[0];
        String mdName = parts[1];

        // Normalize metadata type to English singular form (supports Russian and plural forms)
        String normalized = MetadataTypeUtils.toEnglishSingular(mdType);
        if (normalized != null)
        {
            mdType = normalized;
        }

        // Find the object
        MdObject mdObject = MetadataTypeUtils.findObject(config, mdType, mdName);
        if (mdObject == null)
        {
            return "**Error:** Object not found: " + fqn + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        // Use the new formatter registry
        return MetadataFormatterRegistry.format(mdObject, full, language);
    }
    
}
