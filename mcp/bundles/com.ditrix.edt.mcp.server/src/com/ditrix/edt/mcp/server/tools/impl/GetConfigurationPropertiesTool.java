/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.emf.common.util.EMap;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.core.platform.IConfigurationAware;
import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IExtensionProject;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.FrontMatter;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Tool to get 1C:Enterprise configuration properties.
 */
public class GetConfigurationPropertiesTool implements IMcpTool
{
    public static final String NAME = "get_configuration_properties"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get 1C:Enterprise configuration properties (name, synonym, comment, script variant, compatibility mode, etc.)"; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "Project name (optional, if not specified returns first configuration project)") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        // Human-readable YAML body delivered as a YAML embedded resource, so the
        // response type, the .yaml resource URI and the body's mimeType all agree
        // (errors still travel as structured JSON via the protocol's
        // isJsonErrorPayload diversion). See card get-configuration-properties-yaml-output.
        return ResponseType.YAML;
    }

    @Override
    public String getResultFileName(Map<String, String> params)
    {
        return "configuration-properties.yaml"; //$NON-NLS-1$
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        return getConfigurationProperties(projectName);
    }
    
    /**
     * Returns configuration properties for the specified project.
     * This method executes in the UI thread to ensure proper access to EDT services.
     * 
     * @param projectName the name of the project (optional)
     * @return JSON string with configuration properties
     */
    public static String getConfigurationProperties(String projectName)
    {
        Activator.logInfo("getConfigurationProperties: Starting..."); //$NON-NLS-1$
        
        // Execute in UI thread to avoid blocking
        final String[] result = new String[1];
        Display display = Display.getDefault();
        
        if (display.getThread() == Thread.currentThread())
        {
            // Already in UI thread
            result[0] = getConfigurationPropertiesInternal(projectName);
        }
        else
        {
            // Execute in UI thread
            Activator.logInfo("getConfigurationProperties: Switching to UI thread..."); //$NON-NLS-1$
            display.syncExec(() -> {
                result[0] = getConfigurationPropertiesInternal(projectName);
            });
        }
        
        return result[0];
    }
    
    /**
     * Internal implementation of getConfigurationProperties.
     * Must be called from the UI thread.
     */
    private static String getConfigurationPropertiesInternal(String projectName)
    {
        Activator.logInfo("getConfigurationPropertiesInternal: Starting..."); //$NON-NLS-1$
        
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            
            if (dtProjectManager == null || v8ProjectManager == null)
            {
                Activator.logInfo("getConfigurationProperties: Project managers not available"); //$NON-NLS-1$
                return ToolResult.error("Project manager not available").toJson(); //$NON-NLS-1$
            }

            // Both a base configuration project AND a configuration EXTENSION project
            // are IConfigurationAware and expose getConfiguration(), so resolve against
            // that shared interface (NOT the narrower IConfigurationProject, which
            // excludes extensions and made this tool error on a valid extension).
            IConfigurationAware configProject = null;
            IProject matchedProject = null;
            boolean matchedIsExtension = false;

            // Find project by name or get first configuration project
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject[] projects = workspace.getRoot().getProjects();

            for (IProject project : projects)
            {
                if (!project.isOpen())
                {
                    continue;
                }

                IDtProject dtProject = dtProjectManager.getDtProject(project);
                if (dtProject == null)
                {
                    continue;
                }

                IV8Project v8Project = v8ProjectManager.getProject(dtProject);
                if (!(v8Project instanceof IConfigurationAware))
                {
                    continue;
                }

                if (projectName == null || projectName.isEmpty())
                {
                    // Default (no name given): the first base CONFIGURATION project — an
                    // extension is not a sensible "default configuration", so skip it here.
                    if (v8Project instanceof IConfigurationProject)
                    {
                        configProject = (IConfigurationAware) v8Project;
                        matchedProject = project;
                        break;
                    }
                }
                else if (project.getName().equals(projectName))
                {
                    // Explicit name: accept a base configuration OR an extension.
                    configProject = (IConfigurationAware) v8Project;
                    matchedProject = project;
                    matchedIsExtension = v8Project instanceof IExtensionProject;
                    break;
                }
            }
            
            if (configProject == null)
            {
                if (projectName != null && !projectName.isEmpty())
                {
                    // Distinguish "no such project" from "exists but is not a
                    // configuration project" so the message is accurate; both name
                    // the value and point at list_projects as the next step.
                    if (!ProjectContext.of(projectName).exists())
                    {
                        return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
                    }
                    // Exists but exposes no 1C configuration — neither a base
                    // configuration nor an extension (both are IConfigurationAware).
                    return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                        + "' does not expose a 1C configuration (not a configuration or extension project). " //$NON-NLS-1$
                        + "Use list_projects to see available projects.").toJson(); //$NON-NLS-1$
                }
                // No projectName given and the workspace holds no configuration
                // project at all — nothing to name; keep the message clear and tell
                // the caller how to discover projects.
                return ToolResult.error("No configuration project found in the workspace. " //$NON-NLS-1$
                    + "Use list_projects to see available projects.").toJson(); //$NON-NLS-1$
            }

            // Get configuration object
            Configuration configuration = configProject.getConfiguration();
            if (configuration == null)
            {
                return ToolResult.error("Configuration object not available").toJson(); //$NON-NLS-1$
            }

            // Build a human-readable YAML body. Null scalars and empty localized
            // maps are omitted so the output stays clean (the old JSON path emitted
            // empty objects like copyright:{}). Errors still go through
            // ToolResult.error(...).toJson() above and are delivered as structured
            // JSON via the protocol diversion, independent of this YAML body.
            StringBuilder yaml = new StringBuilder();
            appendScalar(yaml, "name", configuration.getName()); //$NON-NLS-1$
            appendMap(yaml, "synonym", toLocalizedMap(configuration.getSynonym())); //$NON-NLS-1$
            appendScalar(yaml, "comment", configuration.getComment()); //$NON-NLS-1$

            if (configuration.getScriptVariant() != null)
            {
                appendScalar(yaml, "scriptVariant", configuration.getScriptVariant().toString()); //$NON-NLS-1$
            }
            if (configuration.getDefaultRunMode() != null)
            {
                appendScalar(yaml, "defaultRunMode", configuration.getDefaultRunMode().toString()); //$NON-NLS-1$
            }
            if (configuration.getDataLockControlMode() != null)
            {
                appendScalar(yaml, "dataLockControlMode", configuration.getDataLockControlMode().toString()); //$NON-NLS-1$
            }
            if (configuration.getCompatibilityMode() != null)
            {
                appendScalar(yaml, "compatibilityMode", configuration.getCompatibilityMode().toString()); //$NON-NLS-1$
            }
            if (configuration.getModalityUseMode() != null)
            {
                appendScalar(yaml, "modalityUseMode", configuration.getModalityUseMode().toString()); //$NON-NLS-1$
            }
            if (configuration.getInterfaceCompatibilityMode() != null)
            {
                appendScalar(yaml, "interfaceCompatibilityMode", configuration.getInterfaceCompatibilityMode().toString()); //$NON-NLS-1$
            }
            if (configuration.getObjectAutonumerationMode() != null)
            {
                appendScalar(yaml, "objectAutonumerationMode", configuration.getObjectAutonumerationMode().toString()); //$NON-NLS-1$
            }

            // Use purposes (list)
            List<String> usePurposes = new ArrayList<>();
            if (configuration.getUsePurposes() != null)
            {
                for (Object purpose : configuration.getUsePurposes())
                {
                    usePurposes.add(purpose.toString());
                }
            }
            appendList(yaml, "usePurposes", usePurposes); //$NON-NLS-1$

            // Localized / vendor fields (empty maps omitted)
            appendMap(yaml, "briefInformation", toLocalizedMap(configuration.getBriefInformation())); //$NON-NLS-1$
            appendMap(yaml, "detailedInformation", toLocalizedMap(configuration.getDetailedInformation())); //$NON-NLS-1$
            appendScalar(yaml, "vendor", configuration.getVendor()); //$NON-NLS-1$
            appendScalar(yaml, "version", configuration.getVersion()); //$NON-NLS-1$
            appendMap(yaml, "copyright", toLocalizedMap(configuration.getCopyright())); //$NON-NLS-1$
            appendMap(yaml, "vendorInformationAddress", toLocalizedMap(configuration.getVendorInformationAddress())); //$NON-NLS-1$
            appendMap(yaml, "configurationInformationAddress", toLocalizedMap(configuration.getConfigurationInformationAddress())); //$NON-NLS-1$

            // Default language: report the language CODE (ru/en — the synonym map key)
            // plus its human-readable name.
            if (configuration.getDefaultLanguage() != null)
            {
                appendScalar(yaml, "defaultLanguage", configuration.getDefaultLanguage().getLanguageCode()); //$NON-NLS-1$
                appendScalar(yaml, "defaultLanguageName", configuration.getDefaultLanguage().getName()); //$NON-NLS-1$
            }

            // Extension-specific properties — emitted only for a configuration
            // EXTENSION (a base configuration has no name prefix / purpose / extension
            // compatibility mode), so a base config's output is unchanged.
            if (matchedIsExtension)
            {
                // A synthetic marker that this project is a configuration EXTENSION.
                // NB this is NOT the EMF MdObject.getObjectBelonging() property (that
                // returns Adopted/Native per object) — it is a project-kind hint.
                appendScalar(yaml, "projectKind", "Extension"); //$NON-NLS-1$ //$NON-NLS-2$
                appendScalar(yaml, "namePrefix", configuration.getNamePrefix()); //$NON-NLS-1$
                if (configuration.getConfigurationExtensionPurpose() != null)
                {
                    appendScalar(yaml, "configurationExtensionPurpose", //$NON-NLS-1$
                        configuration.getConfigurationExtensionPurpose().toString());
                }
                if (configuration.getConfigurationExtensionCompatibilityMode() != null)
                {
                    appendScalar(yaml, "configurationExtensionCompatibilityMode", //$NON-NLS-1$
                        configuration.getConfigurationExtensionCompatibilityMode().toString());
                }
            }

            appendScalar(yaml, "projectName", matchedProject.getName()); //$NON-NLS-1$

            return yaml.toString();
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get configuration properties", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson();
        }
    }
    
    /**
     * Appends {@code key: value} when the value is non-null and non-empty; omits
     * the line otherwise. Scalar values are YAML-escaped via the shared
     * {@link FrontMatter#escapeYamlValue}.
     */
    private static void appendScalar(StringBuilder sb, String key, String value)
    {
        if (value == null || value.isEmpty())
        {
            return;
        }
        sb.append(key).append(": ").append(FrontMatter.escapeYamlValue(value)).append('\n'); //$NON-NLS-1$
    }

    /**
     * Appends a nested {@code key:} block with one indented {@code code: value}
     * entry per map item. Omits the whole block when the map is null or empty, so
     * empty localized fields (e.g. an unset copyright) do not clutter the output.
     */
    private static void appendMap(StringBuilder sb, String key, Map<String, String> map)
    {
        if (map == null || map.isEmpty())
        {
            return;
        }
        sb.append(key).append(":\n"); //$NON-NLS-1$
        for (Map.Entry<String, String> entry : map.entrySet())
        {
            sb.append("  ").append(FrontMatter.escapeYamlValue(entry.getKey())) //$NON-NLS-1$
              .append(": ").append(FrontMatter.escapeYamlValue(entry.getValue())).append('\n'); //$NON-NLS-1$
        }
    }

    /**
     * Appends a {@code key:} block with one {@code - item} per element. Omits the
     * whole block when the list is null or empty.
     */
    private static void appendList(StringBuilder sb, String key, List<String> items)
    {
        if (items == null || items.isEmpty())
        {
            return;
        }
        sb.append(key).append(":\n"); //$NON-NLS-1$
        for (String item : items)
        {
            sb.append("  - ").append(FrontMatter.escapeYamlValue(item)).append('\n'); //$NON-NLS-1$
        }
    }

    /**
     * Converts EMap to an ordered Map (insertion order preserved for stable YAML).
     */
    @SuppressWarnings("rawtypes")
    private static Map<String, String> toLocalizedMap(EMap localizedString)
    {
        Map<String, String> map = new LinkedHashMap<>();
        if (localizedString != null)
        {
            for (Object entry : localizedString)
            {
                if (entry instanceof Map.Entry)
                {
                    Map.Entry e = (Map.Entry) entry;
                    String key = e.getKey() != null ? e.getKey().toString() : ""; //$NON-NLS-1$
                    String value = e.getValue() != null ? e.getValue().toString() : ""; //$NON-NLS-1$
                    map.put(key, value);
                }
            }
        }
        return map;
    }
}
