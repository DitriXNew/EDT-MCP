/**
 * Copyright (c) 2025 DitriX
 */
package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

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

/**
 * Tool to update database (infobase) for an application.
 * Supports full and incremental update modes.
 */
public class UpdateDatabaseTool implements IMcpTool
{
    public static final String NAME = "update_database"; //$NON-NLS-1$
    
    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Update database (infobase) for an application. " + //$NON-NLS-1$
               "Requires application ID from get_applications tool. " + //$NON-NLS-1$
               "Supports full update (complete reload) and incremental update (changes only)."; //$NON-NLS-1$
    }
    
    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", "EDT project name (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID from get_applications (required)", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("fullUpdate", "If true - full reload, if false - incremental update (default: false)") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("autoRestructure", "Automatically apply restructurization if needed (default: true)") //$NON-NLS-1$ //$NON-NLS-2$
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
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$
        
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
        
        return updateDatabase(projectName, applicationId, fullUpdate, autoRestructure);
    }
    
    /**
     * Updates the database for the specified application.
     * 
     * @param projectName name of the project
     * @param applicationId ID of the application
     * @param fullUpdate true for full update, false for incremental
     * @param autoRestructure whether to auto-apply restructurization
     * @return JSON string with result
     */
    private String updateDatabase(String projectName, String applicationId, 
            boolean fullUpdate, boolean autoRestructure)
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
            
            // Find application by ID
            Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
            if (!appOpt.isPresent())
            {
                return ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                        ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
            }
            
            IApplication application = appOpt.get();
            
            // Check current update state before proceeding
            ApplicationUpdateState stateBefore = appManager.getUpdateState(application);
            if (stateBefore == ApplicationUpdateState.BEING_UPDATED)
            {
                return ToolResult.error("Application is currently being updated. Please wait.").toJson(); //$NON-NLS-1$
            }
            
            // Determine update type
            ApplicationUpdateType updateType = fullUpdate 
                    ? ApplicationUpdateType.FULL 
                    : ApplicationUpdateType.INCREMENTAL;
            
            // Create execution context with Shell from UI thread
            ExecutionContext context = new ExecutionContext();
            
            // Get Shell from Display and set it in context
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
            
            Activator.logInfo("Update database: project=" + projectName +  //$NON-NLS-1$
                    ", application=" + applicationId +  //$NON-NLS-1$
                    ", type=" + updateType +  //$NON-NLS-1$
                    ", autoRestructure=" + autoRestructure); //$NON-NLS-1$
            
            // Create progress monitor
            IProgressMonitor monitor = new NullProgressMonitor();
            
            // Perform update
            ApplicationUpdateState stateAfter = appManager.update(application, updateType, context, monitor);
            
            // Build result
            ToolResult result = ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("applicationName", application.getName()) //$NON-NLS-1$
                .put("updateType", updateType.name()) //$NON-NLS-1$
                .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                .put("stateAfter", stateAfter.name()); //$NON-NLS-1$
            
            // Add status message based on result
            if (stateAfter == ApplicationUpdateState.UPDATED)
            {
                result.put("message", "Database updated successfully"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
            {
                result.put("message", "Update in progress"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                result.put("message", "Update completed with state: " + stateAfter.name()); //$NON-NLS-1$ //$NON-NLS-2$
            }
            
            return result.toJson();
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error updating database for application: " + applicationId, e); //$NON-NLS-1$
            
            // Return detailed error information
            ToolResult errorResult = ToolResult.error("Database update failed: " + e.getMessage()); //$NON-NLS-1$
            errorResult.put("applicationId", applicationId); //$NON-NLS-1$
            errorResult.put("projectName", projectName); //$NON-NLS-1$
            
            // Try to get additional error details
            if (e.getCause() != null)
            {
                errorResult.put("causeMessage", e.getCause().getMessage()); //$NON-NLS-1$
                errorResult.put("causeType", e.getCause().getClass().getSimpleName()); //$NON-NLS-1$
            }
            
            return errorResult.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during database update", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
