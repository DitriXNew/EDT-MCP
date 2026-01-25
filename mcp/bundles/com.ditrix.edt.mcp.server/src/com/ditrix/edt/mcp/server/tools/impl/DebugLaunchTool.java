/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.ApplicationUpdateState;
import com.e1c.g5.dt.applications.ApplicationUpdateType;
import com.e1c.g5.dt.applications.ExecutionContext;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import org.eclipse.swt.widgets.Shell;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to launch EDT application in debug mode.
 * Finds launch configurations for the project/application and starts debugging.
 */
public class DebugLaunchTool implements IMcpTool
{
    public static final String NAME = "debug_launch"; //$NON-NLS-1$
    
    /** 1C:EDT launch configuration type ID */
    private static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$
    
    /** Launch configuration attributes */
    private static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$
    private static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Launch EDT application in debug mode. " + //$NON-NLS-1$
               "Finds existing launch configuration for the project/application and starts debugging. " + //$NON-NLS-1$
               "Requires application ID from get_applications tool."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID from get_applications (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("updateBeforeLaunch", "If true - update database before launching (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
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
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$
        
        // Validate required parameters
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required").toJson(); //$NON-NLS-1$
        }
        
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required. Use get_applications to get application list.").toJson(); //$NON-NLS-1$
        }
        
        // Check if project is ready for operations
        String notReadyError = ProjectStateChecker.checkReadyOrError(projectName);
        if (notReadyError != null)
        {
            return ToolResult.error(notReadyError).toJson();
        }
        
        return launchDebug(projectName, applicationId, updateBeforeLaunch);
    }
    
    /**
     * Launches the application in debug mode.
     * 
     * @param projectName name of the project
     * @param applicationId ID of the application
     * @param updateBeforeLaunch whether to update database before launching
     * @return JSON string with result
     */
    private String launchDebug(String projectName, String applicationId, boolean updateBeforeLaunch)
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
            
            // Verify application exists and get its name
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            String applicationName = applicationId; // Default to ID if can't get name
            IApplication application = null;
            
            if (appManager != null)
            {
                try
                {
                    Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                    if (!appOpt.isPresent())
                    {
                        return ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                                ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    }
                    application = appOpt.get();
                    applicationName = application.getName();
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error checking application", e); //$NON-NLS-1$
                    // Continue - we'll try to find launch config anyway
                }
            }
            
            // Update database before launch if requested
            if (updateBeforeLaunch && appManager != null && application != null)
            {
                try
                {
                    ApplicationUpdateState updateState = appManager.getUpdateState(application);
                    
                    // Only update if needed
                    if (updateState != ApplicationUpdateState.UPDATED && 
                        updateState != ApplicationUpdateState.BEING_UPDATED)
                    {
                        Activator.logInfo("Updating database before launch: project=" + projectName + //$NON-NLS-1$
                                ", application=" + applicationId); //$NON-NLS-1$
                        
                        // Create execution context with Shell
                        ExecutionContext context = new ExecutionContext();
                        Display display = Display.getDefault();
                        if (display != null && !display.isDisposed())
                        {
                            final Shell[] shellHolder = new Shell[1];
                            display.syncExec(() -> {
                                shellHolder[0] = display.getActiveShell();
                                if (shellHolder[0] == null)
                                {
                                    Shell[] shells = display.getShells();
                                    if (shells.length > 0)
                                    {
                                        shellHolder[0] = shells[0];
                                    }
                                }
                            });
                            if (shellHolder[0] != null)
                            {
                                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shellHolder[0]);
                            }
                        }
                        
                        IProgressMonitor monitor = new NullProgressMonitor();
                        ApplicationUpdateState stateAfter = appManager.update(application, 
                                ApplicationUpdateType.INCREMENTAL, context, monitor);
                        
                        Activator.logInfo("Database update completed: stateAfter=" + stateAfter); //$NON-NLS-1$
                    }
                }
                catch (ApplicationException e)
                {
                    Activator.logError("Error updating database before launch", e); //$NON-NLS-1$
                    // Return error but allow user to retry with updateBeforeLaunch=false
                    return ToolResult.error("Failed to update database before launch: " + e.getMessage() + //$NON-NLS-1$
                            ". You can retry with updateBeforeLaunch=false to skip update.").toJson(); //$NON-NLS-1$
                }
            }
            
            // Get launch manager
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }
            
            // Get launch configuration type
            ILaunchConfigurationType configType = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return ToolResult.error("Launch configuration type not found: " + LAUNCH_CONFIG_TYPE_ID).toJson(); //$NON-NLS-1$
            }
            
            // Find matching launch configurations
            ILaunchConfiguration[] allConfigs = launchManager.getLaunchConfigurations(configType);
            ILaunchConfiguration matchingConfig = null;
            
            for (ILaunchConfiguration config : allConfigs)
            {
                try
                {
                    String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    String configAppId = config.getAttribute(ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
                    
                    if (projectName.equals(configProject) && applicationId.equals(configAppId))
                    {
                        matchingConfig = config;
                        break;
                    }
                }
                catch (CoreException e)
                {
                    Activator.logError("Error reading launch configuration: " + config.getName(), e); //$NON-NLS-1$
                }
            }
            
            if (matchingConfig == null)
            {
                // No exact match - try to find any config for this project
                for (ILaunchConfiguration config : allConfigs)
                {
                    try
                    {
                        String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                        if (projectName.equals(configProject))
                        {
                            matchingConfig = config;
                            break;
                        }
                    }
                    catch (CoreException e)
                    {
                        // Skip this config
                    }
                }
            }
            
            if (matchingConfig == null)
            {
                // Return list of available configurations for debugging
                JsonArray availableConfigs = new JsonArray();
                for (ILaunchConfiguration config : allConfigs)
                {
                    try
                    {
                        JsonObject configObj = new JsonObject();
                        configObj.addProperty("name", config.getName()); //$NON-NLS-1$
                        configObj.addProperty("project", config.getAttribute(ATTR_PROJECT_NAME, "")); //$NON-NLS-1$ //$NON-NLS-2$
                        configObj.addProperty("applicationId", config.getAttribute(ATTR_APPLICATION_ID, "")); //$NON-NLS-1$ //$NON-NLS-2$
                        availableConfigs.add(configObj);
                    }
                    catch (CoreException e)
                    {
                        // Skip
                    }
                }
                
                ToolResult errorResult = ToolResult.error("No launch configuration found for project '" + projectName + //$NON-NLS-1$
                        "' and application '" + applicationName + "' (" + applicationId + "). " + //$NON-NLS-1$ //$NON-NLS-2$
                        "Create a launch configuration in EDT first."); //$NON-NLS-1$
                errorResult.put("availableConfigurations", availableConfigs); //$NON-NLS-1$
                return errorResult.toJson();
            }
            
            // Launch in debug mode
            final ILaunchConfiguration configToLaunch = matchingConfig;
            final String configName = configToLaunch.getName();
            
            Activator.logInfo("Launching debug: config=" + configName +  //$NON-NLS-1$
                    ", project=" + projectName +  //$NON-NLS-1$
                    ", application=" + applicationId); //$NON-NLS-1$
            
            // Launch must be done on UI thread
            final boolean[] launchSuccess = {false};
            final String[] launchError = {null};
            
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
            {
                display.syncExec(() -> {
                    try
                    {
                        // Use DebugUITools for proper debug launch
                        org.eclipse.debug.ui.DebugUITools.launch(configToLaunch, ILaunchManager.DEBUG_MODE);
                        launchSuccess[0] = true;
                    }
                    catch (Exception e)
                    {
                        Activator.logError("Error launching debug session", e); //$NON-NLS-1$
                        launchError[0] = e.getMessage();
                    }
                });
            }
            else
            {
                // Fallback - direct launch without UI
                try
                {
                    configToLaunch.launch(ILaunchManager.DEBUG_MODE, null);
                    launchSuccess[0] = true;
                }
                catch (CoreException e)
                {
                    Activator.logError("Error launching debug session", e); //$NON-NLS-1$
                    launchError[0] = e.getMessage();
                }
            }
            
            if (launchSuccess[0])
            {
                return ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("launchConfiguration", configName) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "Debug session started successfully") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }
            else
            {
                return ToolResult.error("Failed to launch debug session" + //$NON-NLS-1$
                        (launchError[0] != null ? ": " + launchError[0] : "")).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error launching debug for application: " + applicationId, e); //$NON-NLS-1$
            return ToolResult.error("Launch failed: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
