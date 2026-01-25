/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to get list of applications for a project.
 * Applications are required for database update and debug launch operations.
 */
public class GetApplicationsTool implements IMcpTool
{
    public static final String NAME = "get_applications"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Get list of applications (infobases) for a project. " + //$NON-NLS-1$
               "Returns application ID, name, type, and update state. " + //$NON-NLS-1$
               "Application ID is required for update_database and debug_launch tools."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }
    
    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }
    
    @Override
    public String execute(Map<String, String> params)
    {
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        
        // Validate project name
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        
        // Check if project is ready for operations
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }
        
        return getApplications(projectName);
    }
    
    /**
     * Gets list of applications for the specified project.
     * 
     * @param projectName name of the project
     * @return JSON string with result
     */
    private String getApplications(String projectName)
    {
        try
        {
            IWorkspace workspace = ResourcesPlugin.getWorkspace();
            IProject project = workspace.getRoot().getProject(projectName);
            
            if (project == null || !project.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
            }
            
            if (!project.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            
            // Get application manager
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            if (appManager == null)
            {
                return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
            }
            
            // Get applications for the project
            List<IApplication> applications = appManager.getApplications(project);
            
            if (applications == null || applications.isEmpty())
            {
                return ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applications", new JsonArray()) //$NON-NLS-1$
                    .put("count", 0) //$NON-NLS-1$
                    .put("message", "No applications found for project") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            
            // Build applications array
            JsonArray appsArray = new JsonArray();
            for (IApplication app : applications)
            {
                JsonObject appObj = new JsonObject();
                appObj.addProperty("id", app.getId()); //$NON-NLS-1$
                appObj.addProperty("name", app.getName()); //$NON-NLS-1$
                
                // Add type info
                if (app.getType() != null)
                {
                    appObj.addProperty("type", app.getType().getId()); //$NON-NLS-1$
                }
                
                // Add update state
                try
                {
                    ApplicationUpdateState updateState = appManager.getUpdateState(app);
                    if (updateState != null)
                    {
                        appObj.addProperty("updateState", updateState.name()); //$NON-NLS-1$
                        
                        // Add human-readable description
                        String stateDescription = getUpdateStateDescription(updateState);
                        appObj.addProperty("updateStateDescription", stateDescription); //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error getting update state for application: " + app.getId(), e); //$NON-NLS-1$
                    appObj.addProperty("updateState", "ERROR"); //$NON-NLS-1$ //$NON-NLS-2$
                    appObj.addProperty("updateStateError", e.getMessage()); //$NON-NLS-1$
                }
                
                // Add required version if present
                app.getRequiredVersion().ifPresent(version -> 
                    appObj.addProperty("requiredVersion", version)); //$NON-NLS-1$
                
                appsArray.add(appObj);
            }
            
            // Get default application
            String defaultAppId = null;
            try
            {
                IApplication defaultApp = appManager.getDefaultApplication(project).orElse(null);
                if (defaultApp != null)
                {
                    defaultAppId = defaultApp.getId();
                }
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error getting default application", e); //$NON-NLS-1$
            }
            
            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applications", appsArray) //$NON-NLS-1$
                .put("count", applications.size()); //$NON-NLS-1$
            
            if (defaultAppId != null)
            {
                result.put("defaultApplicationId", defaultAppId); //$NON-NLS-1$
            }
            
            return result.toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error getting applications for project: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Error getting applications: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
    
    /**
     * Returns human-readable description for update state.
     * 
     * @param state the update state
     * @return description string
     */
    private String getUpdateStateDescription(ApplicationUpdateState state)
    {
        switch (state)
        {
            case UNKNOWN:
                return "Unknown state"; //$NON-NLS-1$
            case INCREMENTAL_UPDATE_REQUIRED:
                return "Incremental update required"; //$NON-NLS-1$
            case FULL_UPDATE_REQUIRED:
                return "Full update required"; //$NON-NLS-1$
            case UPDATED:
                return "Up to date"; //$NON-NLS-1$
            case BEING_UPDATED:
                return "Currently being updated"; //$NON-NLS-1$
            default:
                return state.name();
        }
    }
}
