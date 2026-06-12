/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.swt.widgets.Shell;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
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
        return "Apply configuration changes to an application's database (infobase), full or " //$NON-NLS-1$
            + "incremental. Target by launchConfigurationName (preferred) or projectName + " //$NON-NLS-1$
            + "applicationId. Destructive/irreversible: guarded by a confirm-preview - call without " //$NON-NLS-1$
            + "confirm to preview the exact update (no infobase change), then confirm=true to apply. " //$NON-NLS-1$
            + "Auto-terminates any 1C client THIS EDT launched on the target infobase first to free the " //$NON-NLS-1$
            + "exclusive lock (opt out with terminateRunningClients=false). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('update_database')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client config name from list_configurations (preferred target).") //$NON-NLS-1$
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name; required if launchConfigurationName is omitted.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications; required if launchConfigurationName is omitted.") //$NON-NLS-1$
            .booleanProperty("fullUpdate", //$NON-NLS-1$
                "true = full reload, false = incremental (default false).") //$NON-NLS-1$
            .booleanProperty("autoRestructure", //$NON-NLS-1$
                "Auto-apply restructurization when needed (default true).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = apply the update; default false = preview only (resolves the target and " //$NON-NLS-1$
                + "reports what would change WITHOUT mutating the infobase).") //$NON-NLS-1$
            .booleanProperty("terminateRunningClients", //$NON-NLS-1$
                "Before applying, terminate any 1C client THIS EDT launched on the target infobase " //$NON-NLS-1$
                + "to free the exclusive lock (default true). false keeps a running client — the " //$NON-NLS-1$
                + "update then fails if that client holds the infobase exclusively.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' (nothing changed) or 'updated' (applied).") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no infobase change made); absent/false once updated.") //$NON-NLS-1$
            .stringProperty("project", "Target EDT project name.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Target application ID.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationName", "Display name of the target application.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("updateType", "Update mode applied: FULL or INCREMENTAL.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateBefore", "Application update state before the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateAfter", "Application update state after the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message for the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("terminatedClient", //$NON-NLS-1$
                "true when a running 1C client holding the infobase was terminated before the update " //$NON-NLS-1$
                + "(only when terminateRunningClients=true).") //$NON-NLS-1$
            .booleanProperty("willTerminateRunningClients", //$NON-NLS-1$
                "On a preview: whether confirm=true would first terminate a running client " //$NON-NLS-1$
                + "(reflects terminateRunningClients).") //$NON-NLS-1$
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
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean terminateRunningClients =
            JsonUtils.extractBooleanArgument(params, "terminateRunningClients", true); //$NON-NLS-1$

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return ToolResult.error("applicationId is required (or pass launchConfigurationName). " //$NON-NLS-1$
                    + "Use get_applications or list_configurations.").toJson(); //$NON-NLS-1$
            }
        }

        // Resolve via launch config if name is given — it fixes the project + applicationId pair.
        if (hasName)
        {
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }
            ILaunchConfiguration cfg = LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
            if (cfg == null)
            {
                return ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                    + "'. Use list_configurations to see what's available.").toJson(); //$NON-NLS-1$
            }
            if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(cfg)))
            {
                return ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                    + "' is not a runtime-client config — update_database requires one.").toJson(); //$NON-NLS-1$
            }
            String cfgProject = LaunchConfigUtils.readAttribute(cfg,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String cfgAppId = LaunchConfigUtils.readAttribute(cfg,
                LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (cfgProject.isEmpty() || cfgAppId.isEmpty())
            {
                return ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                    + "' has no project or applicationId attribute — cannot derive update target.").toJson(); //$NON-NLS-1$
            }
            projectName = cfgProject;
            applicationId = cfgAppId;
        }

        // Refuse only the transient BUILDING state; a missing/closed project falls through
        // to the value-naming "Project not found" below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return updateDatabase(projectName, applicationId, fullUpdate, autoRestructure, confirm,
            terminateRunningClients);
    }

    /**
     * Updates the database for the specified application.
     *
     * @param projectName name of the project
     * @param applicationId ID of the application
     * @param fullUpdate true for full update, false for incremental
     * @param autoRestructure whether to auto-apply restructurization
     * @param confirm false previews without mutating; true applies the update
     * @param terminateRunningClients true (default) frees the infobase by terminating a 1C client
     *            this EDT launched on it before the update; false leaves a running client in place
     * @return JSON string with result
     */
    private String updateDatabase(String projectName, String applicationId,
            boolean fullUpdate, boolean autoRestructure, boolean confirm,
            boolean terminateRunningClients)
    {
        try
        {
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
            }

            if (!ctx.isOpen())
            {
                return ToolResult.error("Project is closed: " + projectName).toJson(); //$NON-NLS-1$
            }
            IProject project = ctx.project();

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

            // Confirm-preview gate (mirrors delete_metadata): a bare call
            // resolves the target and reports the exact IRREVERSIBLE action WITHOUT touching the
            // infobase; only confirm=true actually applies it. All validation above (project open,
            // application exists, not already being updated) has run, so the preview is trustworthy.
            if (!confirm)
            {
                return ToolResult.success()
                    .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("confirmationRequired", true) //$NON-NLS-1$
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("applicationName", application.getName()) //$NON-NLS-1$
                    .put("updateType", updateType.name()) //$NON-NLS-1$
                    .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                    .put("willTerminateRunningClients", terminateRunningClients) //$NON-NLS-1$
                    .put("message", "PREVIEW: this would apply a " + updateType.name() //$NON-NLS-1$ //$NON-NLS-2$
                        + " configuration update to the database of application '" + application.getName() //$NON-NLS-1$
                        + "' (project " + projectName + "). This mutates the infobase and is " //$NON-NLS-1$ //$NON-NLS-2$
                        + "IRREVERSIBLE." //$NON-NLS-1$
                        + (terminateRunningClients
                            ? " It will first terminate any 1C client this EDT launched on the infobase." //$NON-NLS-1$
                            : "") //$NON-NLS-1$
                        + " Re-call with confirm=true to apply it.") //$NON-NLS-1$
                    .toJson();
            }

            // Free the infobase before updating. A 1C client THIS EDT launched holds the IB in
            // exclusive use (so the update blocks/fails) and caches the old module version (it keeps
            // running stale code even after a successful publish). Reuse the launch path's
            // battle-tested sweep — client-typed-thread discriminated (never touches a debug-server
            // session) and exempting MCP-owned launches. Runs only on confirm=true, never in preview.
            boolean terminatedClient = false;
            if (terminateRunningClients)
            {
                terminatedClient = LaunchLifecycleUtils.ensureNoExistingClientSession(project, applicationId);
                if (terminatedClient)
                {
                    Activator.logInfo("Update database: terminated a running client to free the " //$NON-NLS-1$
                        + "infobase: project=" + projectName + ", application=" + applicationId); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }

            // Create execution context with the active Shell so EDT can parent
            // its dialogs. Shared SWT-grab lives in LaunchLifecycleUtils.
            ExecutionContext context = new ExecutionContext();
            Shell shell = LaunchLifecycleUtils.grabActiveShell();
            if (shell != null)
            {
                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
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
                .put("action", "updated") //$NON-NLS-1$ //$NON-NLS-2$
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("applicationName", application.getName()) //$NON-NLS-1$
                .put("updateType", updateType.name()) //$NON-NLS-1$
                .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                .put("terminatedClient", terminatedClient); //$NON-NLS-1$
            
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
