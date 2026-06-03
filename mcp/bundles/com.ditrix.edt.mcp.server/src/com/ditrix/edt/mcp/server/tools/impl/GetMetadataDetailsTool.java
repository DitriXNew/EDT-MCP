/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

import com._1c.g5.v8.dt.core.platform.IConfigurationProvider;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.tools.metadata.MetadataFormatterRegistry;
import com.ditrix.edt.mcp.server.utils.MarkdownUtils;
import com.ditrix.edt.mcp.server.utils.MetadataLanguageUtils;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

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
               "Returns basic info by default, or full details with 'full: true'. " + //$NON-NLS-1$
               "In full mode each reflection section is capped (a '[truncated]' row " + //$NON-NLS-1$
               "marks omitted rows); request fewer FQNs to keep the response small."; //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        if (objectFqns == null || objectFqns.isEmpty())
        {
            return ToolResult.error("objectFqns is required (array of FQNs like 'Catalog.Products')").toJson(); //$NON-NLS-1$
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
                resultRef.set(ToolResult.error(e.getMessage()).toJson());
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
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();
        
        // Get configuration
        IConfigurationProvider configProvider = Activator.getDefault().getConfigurationProvider();
        if (configProvider == null)
        {
            return ToolResult.error("Configuration provider not available").toJson(); //$NON-NLS-1$
        }

        Configuration config = configProvider.getConfiguration(project);
        if (config == null)
        {
            return ToolResult.error("Could not get configuration for project: " + projectName).toJson(); //$NON-NLS-1$
        }
        
        // Determine language CODE for synonyms (the synonym map is keyed by code,
        // e.g. "ru"/"en", not by the Language object's name). May be null when the
        // configuration has no languages; downstream synonym lookup tolerates that.
        String effectiveLanguage = MetadataLanguageUtils.resolveLanguageCode(config, language);
        
        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Details: ").append(projectName).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Per-object outcomes are split into two channels so a structural client
        // can tell a failed object from data: successfully resolved objects render
        // as data in the body, while failures are collected and emitted as a
        // dedicated, clearly-delimited machine-readable table at the end. A
        // per-object failure is NOT a whole-call failure, so it stays in this
        // success body (the top-level ToolResult.error channel above is reserved
        // for whole-call failures such as a missing project or configuration).
        List<String[]> failures = new ArrayList<>();

        // Process each FQN
        for (String fqn : objectFqns)
        {
            MdObject mdObject = resolveObject(config, fqn);
            if (mdObject == null)
            {
                failures.add(new String[] { fqn, describeResolutionFailure(fqn) });
                continue;
            }
            sb.append(MetadataFormatterRegistry.format(mdObject, full, effectiveLanguage));
            sb.append("\n---\n\n"); //$NON-NLS-1$
        }

        if (!failures.isEmpty())
        {
            sb.append(formatFailures(failures));
        }

        return sb.toString();
    }

    /**
     * Resolves a single FQN to its metadata object, or {@code null} when the FQN
     * is malformed or the object does not exist. A {@code null} result is a
     * per-object failure (recorded in the machine-readable failures table), never
     * a whole-call failure.
     */
    private MdObject resolveObject(Configuration config, String fqn)
    {
        // Parse FQN: Type.Name
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return null;
        }

        String mdType = parts[0];
        String mdName = parts[1];

        // Normalize metadata type to English singular form (supports Russian and plural forms)
        String normalized = MetadataTypeUtils.toEnglishSingular(mdType);
        if (normalized != null)
        {
            mdType = normalized;
        }

        return MetadataTypeUtils.findObject(config, mdType, mdName);
    }

    /**
     * Builds the machine-readable reason for a FQN that {@link #resolveObject}
     * could not resolve. The reason becomes data in the failures table, never
     * prose mixed into the data body.
     */
    String describeResolutionFailure(String fqn)
    {
        String[] parts = fqn.split("\\."); //$NON-NLS-1$
        if (parts.length < 2)
        {
            return "Invalid FQN. Expected format: Type.Name (e.g. Catalog.Products)"; //$NON-NLS-1$
        }
        return "Object not found"; //$NON-NLS-1$
    }

    /**
     * Renders the per-object failures as a dedicated, clearly-delimited
     * machine-readable section. Every cell goes through the shared table builder,
     * so an FQN or reason containing '|' or a newline cannot break the table. The
     * heading marker {@code ## Errors} lets a structural client locate failed
     * objects without scraping prose out of the data body.
     */
    String formatFailures(List<String[]> failures)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("## Errors\n\n"); //$NON-NLS-1$
        sb.append(MarkdownUtils.tableHeader("FQN", "Status", "Reason")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (String[] failure : failures)
        {
            sb.append(MarkdownUtils.tableRow(failure[0], "ERROR", failure[1])); //$NON-NLS-1$
        }
        return sb.toString();
    }

}
