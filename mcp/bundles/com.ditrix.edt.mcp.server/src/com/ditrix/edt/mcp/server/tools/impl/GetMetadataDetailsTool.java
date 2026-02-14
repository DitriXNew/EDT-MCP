/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
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
                "Array of FQNs (e.g. ['Catalog.Products', 'Document.SalesOrder']). Required.", //$NON-NLS-1$
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
        
        // Normalize metadata type to singular form (accept both singular and plural)
        mdType = normalizeMetadataTypeName(mdType);
        
        // Find the object
        MdObject mdObject = findMdObject(config, mdType, mdName);
        if (mdObject == null)
        {
            return "**Error:** Object not found: " + fqn + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        // Use the new formatter registry
        return MetadataFormatterRegistry.format(mdObject, full, language);
    }
    
    /**
     * Normalizes metadata type name to singular form.
     * Accepts both singular and plural forms and returns singular.
     * 
     * @param mdType metadata type name (e.g. "Catalogs" or "Catalog")
     * @return normalized singular form (e.g. "Catalog")
     */
    private String normalizeMetadataTypeName(String mdType)
    {
        if (mdType == null || mdType.isEmpty())
        {
            return mdType;
        }
        
        // Handle irregular plurals first (must be exact matches)
        switch (mdType)
        {
            case "ChartsOfCharacteristicTypes": //$NON-NLS-1$
                return "ChartOfCharacteristicTypes"; //$NON-NLS-1$
            case "FilterCriteria": //$NON-NLS-1$
                return "FilterCriterion"; //$NON-NLS-1$
            case "ChartsOfAccounts": //$NON-NLS-1$
                return "ChartOfAccounts"; //$NON-NLS-1$
            case "ChartsOfCalculationTypes": //$NON-NLS-1$
                return "ChartOfCalculationTypes"; //$NON-NLS-1$
        }
        
        // Handle regular plurals - if ends with 's', try removing it
        // But only for known plural forms to avoid breaking singular forms that end in 's'
        if (mdType.endsWith("s")) //$NON-NLS-1$
        {
            String singularCandidate = mdType.substring(0, mdType.length() - 1);
            // Check against known singular forms
            switch (singularCandidate.toLowerCase())
            {
                case "catalog": //$NON-NLS-1$
                case "document": //$NON-NLS-1$
                case "enum": //$NON-NLS-1$
                case "constant": //$NON-NLS-1$
                case "report": //$NON-NLS-1$
                case "dataprocessor": //$NON-NLS-1$
                case "exchangeplan": //$NON-NLS-1$
                case "businessprocess": //$NON-NLS-1$
                case "task": //$NON-NLS-1$
                case "language": //$NON-NLS-1$
                case "subsystem": //$NON-NLS-1$
                case "styleitem": //$NON-NLS-1$
                case "style": //$NON-NLS-1$
                case "commonpicture": //$NON-NLS-1$
                case "interface": //$NON-NLS-1$
                case "sessionparameter": //$NON-NLS-1$
                case "role": //$NON-NLS-1$
                case "commontemplate": //$NON-NLS-1$
                case "commonmodule": //$NON-NLS-1$
                case "commonattribute": //$NON-NLS-1$
                case "xdtopackage": //$NON-NLS-1$
                case "webservice": //$NON-NLS-1$
                case "httpservice": //$NON-NLS-1$
                case "wsreference": //$NON-NLS-1$
                case "eventsubscription": //$NON-NLS-1$
                case "scheduledjob": //$NON-NLS-1$
                case "settingsstorage": //$NON-NLS-1$
                case "functionaloption": //$NON-NLS-1$
                case "functionaloptionsparameter": //$NON-NLS-1$
                case "definedtype": //$NON-NLS-1$
                case "commoncommand": //$NON-NLS-1$
                case "commandgroup": //$NON-NLS-1$
                case "commonform": //$NON-NLS-1$
                case "documentnumerator": //$NON-NLS-1$
                case "sequence": //$NON-NLS-1$
                case "documentjournal": //$NON-NLS-1$
                case "informationregister": //$NON-NLS-1$
                case "accumulationregister": //$NON-NLS-1$
                case "accountingregister": //$NON-NLS-1$
                case "calculationregister": //$NON-NLS-1$
                case "externaldatasource": //$NON-NLS-1$
                case "integrationservice": //$NON-NLS-1$
                case "bot": //$NON-NLS-1$
                case "websocketclient": //$NON-NLS-1$
                case "palettecolor": //$NON-NLS-1$
                    return singularCandidate;
            }
        }
        
        // Return as-is if no transformation needed (already singular or unknown type)
        return mdType;
    }
    
    /**
     * Finds a metadata object by type and name using EMF reflection.
     * Maps type names to configuration reference names and searches dynamically.
     */
    @SuppressWarnings("unchecked")
    private MdObject findMdObject(Configuration config, String mdType, String mdName)
    {
        // Get the reference name for this type (e.g., "document" -> "documents")
        String refName = getConfigurationReferenceName(mdType.toLowerCase());
        
        // Find the EReference by name
        for (EReference ref : config.eClass().getEAllReferences())
        {
            if (ref.getName().equalsIgnoreCase(refName))
            {
                Object value = config.eGet(ref);
                if (value instanceof EList)
                {
                    EList<EObject> list = (EList<EObject>) value;
                    for (EObject item : list)
                    {
                        if (item instanceof MdObject)
                        {
                            MdObject mdObj = (MdObject) item;
                            if (mdObj.getName().equals(mdName))
                            {
                                return mdObj;
                            }
                        }
                    }
                }
                break;
            }
        }
        return null;
    }
    
    /**
     * Maps metadata type name to Configuration reference name.
     * Most types just need 's' suffix, but some have irregular plurals.
     */
    private static final Map<String, String> TYPE_TO_REFERENCE = new HashMap<>();
    
    static
    {
        // Irregular plurals and special cases
        TYPE_TO_REFERENCE.put("chartofcharacteristictypes", "chartsOfCharacteristicTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("chartofaccounts", "chartsOfAccounts"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("chartofcalculationtypes", "chartsOfCalculationTypes"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("filtercriterion", "filterCriteria"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("businessprocess", "businessProcesses"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("settingsstorage", "settingsStorages"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("httpservice", "httpServices"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("wsreference", "wsReferences"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("xdtopackage", "xdtoPackages"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("externaldatasource", "externalDataSources"); //$NON-NLS-1$ //$NON-NLS-2$
        TYPE_TO_REFERENCE.put("integrationservice", "integrationServices"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Gets the configuration reference name for a metadata type.
     */
    private String getConfigurationReferenceName(String mdType)
    {
        // Check for known irregular forms first
        String special = TYPE_TO_REFERENCE.get(mdType);
        if (special != null)
        {
            return special;
        }
        
        // Default: add 's' suffix for regular plurals
        return mdType + "s"; //$NON-NLS-1$
    }
}
