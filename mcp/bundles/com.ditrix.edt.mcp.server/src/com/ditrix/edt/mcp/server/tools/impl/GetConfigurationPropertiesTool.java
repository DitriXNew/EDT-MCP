/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.widgets.Display;

import com._1c.g5.v8.dt.core.platform.IConfigurationProject;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.core.platform.IV8ProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.Configuration;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.tools.IMcpTool;

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
        return "{\"type\": \"object\", \"properties\": {" + //$NON-NLS-1$
            "\"projectName\": {\"type\": \"string\", \"description\": \"Project name (optional, if not specified returns first configuration project)\"}" + //$NON-NLS-1$
            "}, \"required\": []}"; //$NON-NLS-1$
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = params != null ? params.get("projectName") : null; //$NON-NLS-1$
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
        StringBuilder json = new StringBuilder();
        json.append("{"); //$NON-NLS-1$
        
        try
        {
            IDtProjectManager dtProjectManager = Activator.getDefault().getDtProjectManager();
            IV8ProjectManager v8ProjectManager = Activator.getDefault().getV8ProjectManager();
            
            if (dtProjectManager == null || v8ProjectManager == null)
            {
                Activator.logInfo("getConfigurationProperties: Project managers not available"); //$NON-NLS-1$
                json.append("\"error\": \"Project manager not available\"}"); //$NON-NLS-1$
                return json.toString();
            }

            IConfigurationProject configProject = null;
            
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
                if (v8Project instanceof IConfigurationProject)
                {
                    if (projectName == null || projectName.isEmpty() || 
                        project.getName().equals(projectName))
                    {
                        configProject = (IConfigurationProject) v8Project;
                        break;
                    }
                }
            }
            
            if (configProject == null)
            {
                json.append("\"error\": \"No configuration project found"); //$NON-NLS-1$
                if (projectName != null && !projectName.isEmpty())
                {
                    json.append(" with name: ").append(JsonUtils.escapeJson(projectName)); //$NON-NLS-1$
                }
                json.append("\"}"); //$NON-NLS-1$
                return json.toString();
            }

            // Get configuration object
            Configuration configuration = configProject.getConfiguration();
            if (configuration == null)
            {
                json.append("\"error\": \"Configuration object not available\"}"); //$NON-NLS-1$
                return json.toString();
            }

            // General properties
            json.append("\"name\": \"").append(JsonUtils.escapeJson(configuration.getName())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Synonym (localized map)
            json.append("\"synonym\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getSynonym());
            json.append(","); //$NON-NLS-1$
            
            json.append("\"comment\": \"").append(JsonUtils.escapeJson(configuration.getComment())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Script variant
            if (configuration.getScriptVariant() != null)
            {
                json.append("\"scriptVariant\": \"").append(configuration.getScriptVariant().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Default run mode
            if (configuration.getDefaultRunMode() != null)
            {
                json.append("\"defaultRunMode\": \"").append(configuration.getDefaultRunMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Data lock control mode
            if (configuration.getDataLockControlMode() != null)
            {
                json.append("\"dataLockControlMode\": \"").append(configuration.getDataLockControlMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Compatibility mode
            if (configuration.getCompatibilityMode() != null)
            {
                json.append("\"compatibilityMode\": \"").append(configuration.getCompatibilityMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Modal use mode
            if (configuration.getModalityUseMode() != null)
            {
                json.append("\"modalityUseMode\": \"").append(configuration.getModalityUseMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Interface compatibility mode
            if (configuration.getInterfaceCompatibilityMode() != null)
            {
                json.append("\"interfaceCompatibilityMode\": \"").append(configuration.getInterfaceCompatibilityMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Object autonumeration mode
            if (configuration.getObjectAutonumerationMode() != null)
            {
                json.append("\"objectAutonumerationMode\": \"").append(configuration.getObjectAutonumerationMode().toString()).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Use purposes (array of purposes)
            json.append("\"usePurposes\": ["); //$NON-NLS-1$
            if (configuration.getUsePurposes() != null && !configuration.getUsePurposes().isEmpty())
            {
                boolean first = true;
                for (Object purpose : configuration.getUsePurposes())
                {
                    if (!first)
                    {
                        json.append(","); //$NON-NLS-1$
                    }
                    first = false;
                    json.append("\"").append(JsonUtils.escapeJson(purpose.toString())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            json.append("],"); //$NON-NLS-1$
            
            // Brief information
            json.append("\"briefInformation\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getBriefInformation());
            json.append(","); //$NON-NLS-1$
            
            // Detailed information
            json.append("\"detailedInformation\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getDetailedInformation());
            json.append(","); //$NON-NLS-1$
            
            // Vendor
            json.append("\"vendor\": \"").append(JsonUtils.escapeJson(configuration.getVendor())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Version
            json.append("\"version\": \"").append(JsonUtils.escapeJson(configuration.getVersion())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            
            // Copyright
            json.append("\"copyright\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getCopyright());
            json.append(","); //$NON-NLS-1$
            
            // Vendor information address
            json.append("\"vendorInformationAddress\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getVendorInformationAddress());
            json.append(","); //$NON-NLS-1$
            
            // Configuration information address
            json.append("\"configurationInformationAddress\": "); //$NON-NLS-1$
            JsonUtils.appendLocalizedStringMap(json, configuration.getConfigurationInformationAddress());
            json.append(","); //$NON-NLS-1$
            
            // Default language
            if (configuration.getDefaultLanguage() != null)
            {
                json.append("\"defaultLanguage\": \"").append(JsonUtils.escapeJson(configuration.getDefaultLanguage().getName())).append("\","); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            // Project name
            json.append("\"projectName\": \"").append(JsonUtils.escapeJson(configProject.getProject().getName())).append("\""); //$NON-NLS-1$ //$NON-NLS-2$
            
            json.append("}"); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Failed to get configuration properties", e); //$NON-NLS-1$
            return "{\"error\": \"" + JsonUtils.escapeJson(e.getMessage()) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        
        return json.toString();
    }
}
