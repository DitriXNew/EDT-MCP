/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to launch an EDT debug session.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code launchConfigurationName} — start an existing EDT launch configuration
 *       by its exact name. Works for both runtime-client configs (spawns 1cv8c) and
 *       Attach configurations (attaches to {@code ragent}/{@code rphost} for
 *       server-side code). Does not require {@code applicationId}.</li>
 *   <li>{@code projectName} + {@code applicationId} — legacy path: searches the
 *       runtime-client configs for a match and launches it.</li>
 * </ul>
 */
public class DebugLaunchTool implements IMcpTool
{
    public static final String NAME = "debug_launch"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start an EDT debug session: either an existing config by launchConfigurationName " //$NON-NLS-1$
            + "(runtime client OR Attach, the latter needed to debug server-side code), or a " //$NON-NLS-1$
            + "runtime-client config matched by projectName + applicationId. If that config is " //$NON-NLS-1$
            + "already running it short-circuits with alreadyRunning:true (terminate_launch first " //$NON-NLS-1$
            + "to force a restart). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('debug_launch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name; required unless launchConfigurationName is given.") //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications; required in the projectName+applicationId mode.") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact name of an EDT debug launch config (runtime client or Attach); skips projectName/applicationId.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Update the database before launching. Default true; ignored for Attach.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("launchConfiguration", "Name of the launched/running launch configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("configurationType", "Launch configuration type id") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("attach", "True if this is an Attach (server-side debug) configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("project", "EDT project name associated with the launch") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application id of the launched configuration") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("alreadyRunning", "True if a matching session was already alive; re-launch skipped") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("mode", "Launch mode of the session (e.g. debug, run)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "# debug_launch\n\n" //$NON-NLS-1$
            + "Starts an EDT debug session for a 1C application. There are two ways to pick what " //$NON-NLS-1$
            + "to launch, plus an idempotency guard that prevents a second client over a session " //$NON-NLS-1$
            + "that is already alive.\n\n" //$NON-NLS-1$
            + "## When to use\n\n" //$NON-NLS-1$
            + "Use this to bring up a debuggable 1C session before setting breakpoints and " //$NON-NLS-1$
            + "stepping. For client-side code, launch a runtime-client config (spawns 1cv8c). " //$NON-NLS-1$
            + "For SERVER-side code (HTTP services, background jobs, scheduled jobs) you must use " //$NON-NLS-1$
            + "an 'Attach to 1C:Enterprise Debug Server' config — a runtime-client launch cannot " //$NON-NLS-1$
            + "hit those breakpoints. After it returns, use `debug_status` to inspect, " //$NON-NLS-1$
            + "`wait_for_break` to block until a breakpoint is hit, and `terminate_launch` to stop.\n\n" //$NON-NLS-1$
            + "## Modes (choose ONE)\n\n" //$NON-NLS-1$
            + "1. **launchConfigurationName** — start an existing EDT launch configuration by its " //$NON-NLS-1$
            + "EXACT name. Works for both runtime-client configs (spawns 1cv8c) AND Attach configs " //$NON-NLS-1$
            + "(attaches to ragent/rphost for server-side code). Does NOT require applicationId. " //$NON-NLS-1$
            + "This is the only mode that can start an Attach session.\n" //$NON-NLS-1$
            + "2. **projectName + applicationId** — searches the runtime-client configs of that " //$NON-NLS-1$
            + "project for a match and launches it. Runtime-client only; cannot reach an Attach " //$NON-NLS-1$
            + "config. Get the applicationId from `get_applications`.\n\n" //$NON-NLS-1$
            + "## Parameters\n\n" //$NON-NLS-1$
            + "- **launchConfigurationName** (string) — exact config name; if set, projectName and " //$NON-NLS-1$
            + "applicationId are ignored. Use `list_configurations` to find the name.\n" //$NON-NLS-1$
            + "- **projectName** (string) — EDT project name; required when launchConfigurationName " //$NON-NLS-1$
            + "is absent.\n" //$NON-NLS-1$
            + "- **applicationId** (string) — from `get_applications`; required in the " //$NON-NLS-1$
            + "projectName+applicationId mode.\n" //$NON-NLS-1$
            + "- **updateBeforeLaunch** (boolean, default true) — run the EDT 'update database " //$NON-NLS-1$
            + "before launch' preflight. Ignored for Attach configs (nothing to update). The " //$NON-NLS-1$
            + "update analysis is shared with the YAXUnit tools: skip when already UPDATED, wait " //$NON-NLS-1$
            + "when BEING_UPDATED, otherwise incremental-update.\n\n" //$NON-NLS-1$
            + "## Already-running guard\n\n" //$NON-NLS-1$
            + "If a launch of the same configuration/application is still alive, the tool " //$NON-NLS-1$
            + "short-circuits with `alreadyRunning: true` and a `mode` field, and does NOT spawn a " //$NON-NLS-1$
            + "fresh client. This also covers a launch started in RUN mode (no debug target): the " //$NON-NLS-1$
            + "tool still detects it and refuses to start a second client over it. To force a clean " //$NON-NLS-1$
            + "restart (e.g. after code changes that require a new session), call " //$NON-NLS-1$
            + "`terminate_launch` first, then `debug_launch` again.\n\n" //$NON-NLS-1$
            + "## Examples\n\n" //$NON-NLS-1$
            + "- Runtime client by name: `launchConfigurationName=\"MyApp / ThinClient\"`.\n" //$NON-NLS-1$
            + "- Attach to debug server-side code: `launchConfigurationName=\"Attach to 1C:Enterprise " //$NON-NLS-1$
            + "Debug Server\"`.\n" //$NON-NLS-1$
            + "- Runtime client by project + app: `projectName=\"MyProject\"`, " //$NON-NLS-1$
            + "`applicationId=\"<id from get_applications>\"`.\n" //$NON-NLS-1$
            + "- Skip the DB update: add `updateBeforeLaunch=false`.\n\n" //$NON-NLS-1$
            + "## Notes\n\n" //$NON-NLS-1$
            + "- Returns JSON. On success: `launchConfiguration`, `configurationType`, `attach`, " //$NON-NLS-1$
            + "`mode`, `project`/`applicationId` (when known), and a `message`.\n" //$NON-NLS-1$
            + "- On a not-found config the error payload includes `availableConfigurations` " //$NON-NLS-1$
            + "(every debug-capable config: runtime client + attach), so you can pick a valid name.\n" //$NON-NLS-1$
            + "- The launch goes through a direct `config.launch(DEBUG_MODE, null)` to avoid modal " //$NON-NLS-1$
            + "EDT dialogs that would block the MCP worker thread.\n\n" //$NON-NLS-1$
            + "## Gotchas\n\n" //$NON-NLS-1$
            + "- Attach is reachable ONLY via launchConfigurationName; projectName+applicationId " //$NON-NLS-1$
            + "never starts an Attach session.\n" //$NON-NLS-1$
            + "- `alreadyRunning: true` is a success, not an error — don't retry it; terminate first " //$NON-NLS-1$
            + "if you truly need a fresh session.\n" //$NON-NLS-1$
            + "- `updateBeforeLaunch` has no effect on Attach configs."; //$NON-NLS-1$
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
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$

        // Mode 1: explicit config name — no project/application required.
        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch);
        }

        // Mode 2: project + application (runtime-client only).
        if (projectName == null || projectName.isEmpty())
        {
            return ToolResult.error("projectName is required (or pass launchConfigurationName)").toJson(); //$NON-NLS-1$
        }

        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required. Use get_applications to get application list, " //$NON-NLS-1$
                + "or pass launchConfigurationName to start a config by name (e.g. an Attach config).").toJson(); //$NON-NLS-1$
        }

        // Refuse only the transient BUILDING state; a missing/closed project falls
        // through to the value-naming "Project not found" below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return launchDebug(projectName, applicationId, updateBeforeLaunch);
    }

    /**
     * Launches a specific EDT debug configuration by name.
     * Works for both runtime-client and Attach configuration types.
     */
    private String launchByConfigName(String configName, boolean updateBeforeLaunch)
    {
        try
        {
            ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            ILaunchConfiguration config = LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
            if (config == null)
            {
                ToolResult err = ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                    + "'. Create it in EDT first."); //$NON-NLS-1$
                err.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return err.toJson();
            }

            String typeId = LaunchConfigUtils.getConfigTypeId(config);
            boolean isAttach = LaunchConfigUtils.isAttachConfigTypeId(typeId);
            String configProject = LaunchConfigUtils.readAttribute(config,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigUtils.getApplicationIdFor(config);

            // If the config is already running in debug mode, don't re-launch.
            if (effectiveAppId != null
                && DebugSessionRegistry.findActiveTarget(effectiveAppId) != null)
            {
                ToolResult already = ToolResult.success()
                    .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                    .put("configurationType", typeId) //$NON-NLS-1$
                    .put("attach", isAttach) //$NON-NLS-1$
                    .put("applicationId", effectiveAppId) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "Launch configuration is already running — skipped re-launch."); //$NON-NLS-1$ //$NON-NLS-2$
                if (configProject != null && !configProject.isEmpty())
                {
                    already.put("project", configProject); //$NON-NLS-1$
                }
                return already.toJson();
            }

            // For runtime-client configs, run the usual DB-update preflight.
            if (!isAttach && updateBeforeLaunch && configProject != null && !configProject.isEmpty())
            {
                String notReady = ProjectStateChecker.checkReadyOrError(configProject);
                if (notReady != null)
                {
                    return ToolResult.error(notReady).toJson();
                }
                String updateError = updateDatabaseIfNeeded(configProject, effectiveAppId);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            String launchError = performLaunch(config);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            ToolResult result = ToolResult.success()
                .put("launchConfiguration", config.getName()) //$NON-NLS-1$
                .put("configurationType", typeId) //$NON-NLS-1$
                .put("attach", isAttach) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put("message", isAttach //$NON-NLS-1$
                    ? "Attach debug session started — use debug_status to inspect, " //$NON-NLS-1$
                        + "wait_for_break to block until a breakpoint is hit." //$NON-NLS-1$
                    : "Debug session started successfully"); //$NON-NLS-1$
            if (configProject != null && !configProject.isEmpty())
            {
                result.put("project", configProject); //$NON-NLS-1$
            }
            if (effectiveAppId != null)
            {
                result.put("applicationId", effectiveAppId); //$NON-NLS-1$
            }
            return result.toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch by name", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Legacy path: launch a runtime-client config matched by project+application.
     */
    private String launchDebug(String projectName, String applicationId, boolean updateBeforeLaunch)
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

            // If this application already has a live debug session, short-circuit
            // — mirrors the launchConfigurationName path so both call styles behave
            // the same. To force a fresh launch, terminate_launch first.
            IDebugTarget activeTarget = DebugSessionRegistry.findActiveTarget(applicationId);
            if (activeTarget != null)
            {
                String activeConfigName = activeTarget.getLaunch() != null
                    && activeTarget.getLaunch().getLaunchConfiguration() != null
                        ? activeTarget.getLaunch().getLaunchConfiguration().getName()
                        : null;
                Activator.logInfo("debug_launch short-circuit (alreadyRunning): project=" //$NON-NLS-1$
                    + projectName + ", applicationId=" + applicationId //$NON-NLS-1$
                    + ", activeConfig=" + activeConfigName); //$NON-NLS-1$
                ToolResult already = ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("attach", false) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("message", "Launch configuration is already running — skipped re-launch. " //$NON-NLS-1$ //$NON-NLS-2$
                        + "Call terminate_launch first to force a fresh session.");
                if (activeConfigName != null)
                {
                    already.put("launchConfiguration", activeConfigName); //$NON-NLS-1$
                }
                return already.toJson();
            }

            // A non-terminated launch may exist for this application WITHOUT a debug
            // target - e.g. it was started in RUN mode. findActiveTarget() (debug
            // targets only) misses it, so without this guard debug_launch would start
            // a SECOND client over the running one. (audit A12)
            ILaunch activeLaunch = DebugSessionRegistry.findActiveLaunch(applicationId);
            if (activeLaunch != null)
            {
                String runningMode = activeLaunch.getLaunchMode();
                ILaunchConfiguration activeConfig = activeLaunch.getLaunchConfiguration();
                ToolResult already = ToolResult.success()
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("attach", false) //$NON-NLS-1$
                    .put("alreadyRunning", true) //$NON-NLS-1$
                    .put("mode", runningMode) //$NON-NLS-1$
                    .put("message", "Application is already running (mode: " + runningMode //$NON-NLS-1$ //$NON-NLS-2$
                        + ") - skipped launch to avoid a second client over the running session. " //$NON-NLS-1$
                        + "Call terminate_launch first to force a fresh session."); //$NON-NLS-1$
                if (activeConfig != null)
                {
                    already.put("launchConfiguration", activeConfig.getName()); //$NON-NLS-1$
                }
                return already.toJson();
            }

            // Update database before launch if requested. Routes through the
            // shared LaunchLifecycleUtils.updateApplicationIfNeeded so debug_launch
            // analyses "does the IB need updating?" the same way the YAXUnit tools
            // do: skip on UPDATED, wait on BEING_UPDATED, incremental-update otherwise.
            if (updateBeforeLaunch && appManager != null && application != null)
            {
                String updateError = LaunchLifecycleUtils
                    .updateApplicationIfNeeded(project, applicationId, appManager)
                    .orElse(null);
                if (updateError != null)
                {
                    return ToolResult.error(updateError).toJson();
                }
            }

            // Get launch manager
            ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            // Get launch configuration type
            ILaunchConfigurationType configType = launchManager
                    .getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
            if (configType == null)
            {
                return ToolResult.error("Launch configuration type not found: " //$NON-NLS-1$
                        + LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID).toJson();
            }

            // Find matching launch configuration via the shared helper.
            ILaunchConfiguration matchingConfig = LaunchConfigUtils.findLaunchConfig(
                    launchManager, configType, projectName, applicationId);

            if (matchingConfig == null)
            {
                ToolResult errorResult = ToolResult.error("No launch configuration found for project '" //$NON-NLS-1$
                    + projectName + "' and application '" + applicationName + "' (" //$NON-NLS-1$ //$NON-NLS-2$
                    + applicationId + "). Create a runtime-client launch configuration in EDT, " //$NON-NLS-1$
                    + "or pass launchConfigurationName to start an Attach configuration."); //$NON-NLS-1$
                errorResult.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return errorResult.toJson();
            }

            final String configName = matchingConfig.getName();
            Activator.logInfo("Launching debug: config=" + configName //$NON-NLS-1$
                + ", project=" + projectName //$NON-NLS-1$
                + ", application=" + applicationId); //$NON-NLS-1$

            String launchError = performLaunch(matchingConfig);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            return ToolResult.success()
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", applicationId) //$NON-NLS-1$
                .put("launchConfiguration", configName) //$NON-NLS-1$
                .put("configurationType", LaunchConfigUtils.getConfigTypeId(matchingConfig)) //$NON-NLS-1$
                .put("attach", false) //$NON-NLS-1$
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put("message", "Debug session started successfully") //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Runs the EDT "update database before launch" step for a runtime-client launch.
     * Returns {@code null} on success, or an error message describing the failure.
     */
    private String updateDatabaseIfNeeded(String projectName, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty()
            || applicationId.startsWith(LaunchConfigUtils.ATTACH_APP_ID_PREFIX))
        {
            return null;
        }
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.isOpen())
        {
            return null;
        }
        IProject project = ctx.project();
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return null;
        }
        // Shared update analysis: skip on UPDATED, wait on BEING_UPDATED, otherwise
        // incremental-update — same path as the YAXUnit auto-chain.
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager)
            .orElse(null);
    }

    /**
     * Launches the given configuration in debug mode.
     *
     * <p>Uses a direct {@code config.launch(DEBUG_MODE, null)} — not
     * {@code DebugUITools.launch} — because the latter may open modal dialogs
     * (save-prompt, perspective-switch, already-running-confirmation) that
     * block the MCP worker thread indefinitely and eventually close the HTTP
     * socket. {@code debug_yaxunit_tests} uses the same direct path.
     *
     * @return {@code null} on success, or an error message on failure.
     */
    private String performLaunch(ILaunchConfiguration config)
    {
        final String[] launchError = {null};
        final boolean[] launchSuccess = {false};
        Display display = Display.getDefault();
        if (display != null && !display.isDisposed())
        {
            display.syncExec(() -> {
                try
                {
                    config.launch(ILaunchManager.DEBUG_MODE, null);
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
            try
            {
                config.launch(ILaunchManager.DEBUG_MODE, null);
                launchSuccess[0] = true;
            }
            catch (CoreException e)
            {
                Activator.logError("Error launching debug session", e); //$NON-NLS-1$
                launchError[0] = e.getMessage();
            }
        }
        return launchSuccess[0] ? null : (launchError[0] != null ? launchError[0] : "unknown error"); //$NON-NLS-1$
    }

    /**
     * Builds a diagnostic list of every debug-capable launch configuration known
     * to EDT (runtime client + attach types), so the MCP client can discover
     * what's available when a lookup fails.
     */
    private static JsonArray listAvailableConfigs(ILaunchManager launchManager)
    {
        JsonArray arr = new JsonArray();
        for (ILaunchConfiguration cfg : LaunchConfigUtils.getAllDebugConfigs(launchManager))
        {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", cfg.getName()); //$NON-NLS-1$
            String typeId = LaunchConfigUtils.getConfigTypeId(cfg);
            obj.addProperty("type", typeId); //$NON-NLS-1$
            obj.addProperty("attach", LaunchConfigUtils.isAttachConfigTypeId(typeId)); //$NON-NLS-1$
            obj.addProperty("project", LaunchConfigUtils.readAttribute(cfg, //$NON-NLS-1$
                LaunchConfigUtils.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$
            obj.addProperty("applicationId", LaunchConfigUtils.readAttribute(cfg, //$NON-NLS-1$
                LaunchConfigUtils.ATTR_APPLICATION_ID, "")); //$NON-NLS-1$
            String alias = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
            if (!alias.isEmpty())
            {
                obj.addProperty("infobaseAlias", alias); //$NON-NLS-1$
            }
            String url = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
            if (!url.isEmpty())
            {
                obj.addProperty("debugServerUrl", url); //$NON-NLS-1$
            }
            arr.add(obj);
        }
        return arr;
    }
}
