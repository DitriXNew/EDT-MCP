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
            + "applicationId. Destructive/irreversible: run only on explicit request, and " //$NON-NLS-1$
            + "terminate any running 1C client on the target infobase first (exclusive lock). " //$NON-NLS-1$
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
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("project", "Target EDT project name.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Target application ID.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationName", "Display name of the target application.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("updateType", "Update mode applied: FULL or INCREMENTAL.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateBefore", "Application update state before the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateAfter", "Application update state after the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message for the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# update_database\n\n" //$NON-NLS-1$
            + "Applies the EDT configuration to an application's database (infobase) — the " //$NON-NLS-1$
            + "equivalent of \"Update database configuration\" in Designer. Supports a full " //$NON-NLS-1$
            + "reload or an incremental (changes-only) update.\n\n" //$NON-NLS-1$
            + "## Think twice — destructive\n\n" //$NON-NLS-1$
            + "This tool mutates the infobase and is **irreversible**. Run it ONLY on an explicit " //$NON-NLS-1$
            + "user request. A full update can drop/recreate database structures; back up or be " //$NON-NLS-1$
            + "sure the infobase is disposable.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "After changing metadata/configuration, to push those changes into the running " //$NON-NLS-1$
            + "infobase so a launched client sees them. Typically: edit metadata -> " //$NON-NLS-1$
            + "`update_database` -> launch/restart the client.\n\n" //$NON-NLS-1$
            + "## Targeting (choose ONE)\n\n" //$NON-NLS-1$
            + "1. **`launchConfigurationName`** (preferred) — exact runtime-client config name " //$NON-NLS-1$
            + "from `list_configurations`. It fixes the project + applicationId pair for you, so " //$NON-NLS-1$
            + "you cannot mismatch them. Must be a runtime-client config (not an Attach config).\n" //$NON-NLS-1$
            + "2. **`projectName` + `applicationId`** — used only when " //$NON-NLS-1$
            + "`launchConfigurationName` is omitted. Get `applicationId` from `get_applications`. " //$NON-NLS-1$
            + "Both are required in this mode.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- **launchConfigurationName** (string) — preferred target; see above.\n" //$NON-NLS-1$
            + "- **projectName** (string) — required if launchConfigurationName is omitted.\n" //$NON-NLS-1$
            + "- **applicationId** (string) — from `get_applications`; required if " //$NON-NLS-1$
            + "launchConfigurationName is omitted.\n" //$NON-NLS-1$
            + "- **fullUpdate** (boolean, default false) — true performs a FULL reload (complete " //$NON-NLS-1$
            + "rebuild), false performs an INCREMENTAL update (changed objects only). " //$NON-NLS-1$
            + "Incremental is faster; use full when the structure changed substantially or an " //$NON-NLS-1$
            + "incremental update fails.\n" //$NON-NLS-1$
            + "- **autoRestructure** (boolean, default true) — automatically apply database " //$NON-NLS-1$
            + "restructurization (table/index changes) when the update requires it, instead of " //$NON-NLS-1$
            + "prompting. Leave true for unattended use.\n\n" //$NON-NLS-1$
            + "## Exclusive-lock gotcha\n\n" //$NON-NLS-1$
            + "If a 1C client launched from this EDT is currently running against the target " //$NON-NLS-1$
            + "infobase, the update typically FAILS because the infobase is held in exclusive " //$NON-NLS-1$
            + "use. Check `list_configurations` for `running: true`; if so, call " //$NON-NLS-1$
            + "`terminate_launch` first (it only affects launches started from this EDT " //$NON-NLS-1$
            + "instance), then retry. Externally launched clients (Designer, ad-hoc 1cv8c.exe) " //$NON-NLS-1$
            + "are invisible to `terminate_launch` and must be closed by hand.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "- Preferred, incremental: `launchConfigurationName=\"MyApp / ThinClient\"`.\n" //$NON-NLS-1$
            + "- Full reload via project + appId: `projectName=\"MyProject\"`, " //$NON-NLS-1$
            + "`applicationId=\"<id from get_applications>\"`, `fullUpdate=true`.\n\n" //$NON-NLS-1$
            + "## Result\n\n" //$NON-NLS-1$
            + "JSON with `project`, `applicationId`, `applicationName`, `updateType` " //$NON-NLS-1$
            + "(FULL/INCREMENTAL), `stateBefore`, `stateAfter` and a `message`. A successful run " //$NON-NLS-1$
            + "reports `stateAfter = UPDATED`. If the application is already BEING_UPDATED the " //$NON-NLS-1$
            + "tool returns an error and you should wait.\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- Most failures are the exclusive lock above — terminate the running launch first.\n" //$NON-NLS-1$
            + "- `launchConfigurationName` must reference a runtime-client config; an Attach " //$NON-NLS-1$
            + "config is rejected.\n" //$NON-NLS-1$
            + "- The project must exist and be open; a closed project returns an error."; //$NON-NLS-1$
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
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.exists())
            {
                return ToolResult.error("Project not found: " + projectName).toJson(); //$NON-NLS-1$
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
