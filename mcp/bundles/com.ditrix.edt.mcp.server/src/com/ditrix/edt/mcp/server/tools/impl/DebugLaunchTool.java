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
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.swt.widgets.Display;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ExistingClientSession;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
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

    /** Output key: name of the launched/running launch configuration. */
    private static final String KEY_LAUNCH_CONFIGURATION = "launchConfiguration"; //$NON-NLS-1$

    /** Output key: launch configuration type id. */
    private static final String KEY_CONFIGURATION_TYPE = "configurationType"; //$NON-NLS-1$

    /** Output key: true if this is an Attach (server-side debug) configuration. */
    private static final String KEY_ATTACH = "attach"; //$NON-NLS-1$

    /** Output key: launch status (e.g. "launching"). */
    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    /** Error-log prefix for an asynchronous launch failure. */
    private static final String ERR_ASYNC_PREFIX = "debug_launch failed asynchronously: "; //$NON-NLS-1$

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
            + "already running it short-circuits with alreadyRunning:true; pass " //$NON-NLS-1$
            + "restartIfRunning:true to terminate the existing session and relaunch fresh. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('debug_launch')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT project name; required unless launchConfigurationName is given.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications; required in the projectName+applicationId mode.") //$NON-NLS-1$
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact name of an EDT debug launch config (runtime client or Attach); skips projectName/applicationId.") //$NON-NLS-1$
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true: silently apply the configuration->DB update before launching so no " //$NON-NLS-1$
                    + "'Update database?' modal blocks the call (even on a Russian-locale EDT the dialog " //$NON-NLS-1$
                    + "is auto-confirmed); false skips the update and the platform may then show that " //$NON-NLS-1$
                    + "modal. Ignored for Attach.") //$NON-NLS-1$
            .booleanProperty("restartIfRunning", //$NON-NLS-1$
                "Default false: if a matching session is already running, short-circuit with " //$NON-NLS-1$
                    + "alreadyRunning:true and do NOT relaunch (call terminate_launch to restart). " //$NON-NLS-1$
                    + "true: non-interactively terminate the existing session, then relaunch — no " //$NON-NLS-1$
                    + "'Debug session already exists' modal blocks the call.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_LAUNCH_CONFIGURATION, "Name of the launched/running launch configuration") //$NON-NLS-1$
            .stringProperty(KEY_CONFIGURATION_TYPE, "Launch configuration type id") //$NON-NLS-1$
            .booleanProperty(KEY_ATTACH, "True if this is an Attach (server-side debug) configuration") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "EDT project name associated with the launch") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the launched configuration") //$NON-NLS-1$
            .booleanProperty("alreadyRunning", "True if a matching session was already alive; re-launch skipped") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("mode", "Launch mode of the session (e.g. debug, run)") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_STATUS, "\"launching\" when the launch was dispatched asynchronously and is " //$NON-NLS-1$
                + "still starting; absent on the alreadyRunning short-circuit. Poll debug_status for readiness.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message") //$NON-NLS-1$
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
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$
        boolean restartIfRunning = extractRestartIfRunning(params);

        // Mode 1: explicit config name — no project/application required.
        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch, restartIfRunning);
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

        return launchDebug(projectName, applicationId, updateBeforeLaunch, restartIfRunning);
    }

    /**
     * Extracts the {@code restartIfRunning} flag with its documented default:
     * {@code false} — an already-running session short-circuits with
     * {@code alreadyRunning:true} rather than being terminated. Package-private
     * seam so the default is unit-assertable headlessly; the launch path that
     * consumes the flag needs a live workbench.
     */
    static boolean extractRestartIfRunning(Map<String, String> params)
    {
        return JsonUtils.extractBooleanArgument(params, "restartIfRunning", false); //$NON-NLS-1$
    }

    /**
     * Launches a specific EDT debug configuration by name.
     * Works for both runtime-client and Attach configuration types.
     */
    private String launchByConfigName(String configName, boolean updateBeforeLaunch,
        boolean restartIfRunning)
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

            // Unified existing-session decision. One
            // (project, app-id) → at most one live CLIENT session, with the
            // CLIENT-typed-thread discriminator applied so a standalone-SERVER /
            // profiling session sharing this app id — including a debug-mode server
            // whose live thread is typed SERVER — NEVER short-circuits the client.
            // Covers both a live DEBUG target and a debug-target-less RUN-mode launch
            // (the legacy already-running guard). restartIfRunning is honored here exactly as in
            // the target-manager path: false → alreadyRunning, true → non-interactive
            // terminate + relaunch.
            ExistingClientSession existingByName =
                LaunchLifecycleUtils.resolveExistingClientSession(effectiveAppId);
            if (existingByName != null)
            {
                AlreadyRunningContext ctx = new AlreadyRunningContext(ALREADY_RUNNING_MESSAGE);
                ctx.launchConfiguration = config.getName();
                ctx.configurationType = typeId;
                ctx.attach = Boolean.valueOf(isAttach);
                ctx.project = configProject;
                String shortCircuit = handleExistingClientSession(existingByName, effectiveAppId,
                    restartIfRunning, ctx);
                if (shortCircuit != null)
                {
                    return shortCircuit;
                }
                // restartIfRunning=true: the old client was terminated — fall through
                // and relaunch.
            }

            // Delegate-criterion duplicate guard. Runtime-client DEBUG
            // path only: the "Debug session already exists" code-1003 modal is raised
            // by RuntimeClientLaunchDelegate, which scans
            // IRuntimeDebugClientTargetManager.listDebugTargets() (NOT ILaunchManager)
            // and keys on ATTR_PROJECT_NAME + (ATTR_APPLICATION_ID else default app).
            // Our findActiveTarget/findActiveLaunch guards above scan ILaunchManager
            // and key on getApplicationIdFor() — so a UI-started ("Debug As") session,
            // or a config without a persisted ATTR_APPLICATION_ID (we mint a synthetic
            // launch:<name> the delegate never uses), slips past them and the unattended
            // call then hangs on the human modal. This supplements them with the
            // delegate's own set + key.
            if (!isAttach && configProject != null && !configProject.isEmpty())
            {
                String dupResult = handleDelegateDuplicateSession(config, configProject,
                    isAttach, typeId, restartIfRunning);
                if (dupResult != null)
                {
                    return dupResult;
                }
            }

            // For runtime-client configs, run the usual DB-update preflight.
            String preflightError =
                runUpdatePreflight(isAttach, updateBeforeLaunch, configProject, effectiveAppId);
            if (preflightError != null)
            {
                return preflightError;
            }

            String launchError = performLaunch(config, updateBeforeLaunch);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            return buildLaunchSuccess(config, typeId, isAttach, configProject, effectiveAppId);
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch by name", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Runtime-client DB-update preflight for the by-name path. Returns a ready
     * error-JSON string when the project is not ready or the database update
     * fails, or {@code null} to proceed. Behaviour is identical to the inline
     * guard it replaces: same condition, same order, same exceptions propagate.
     */
    private String runUpdatePreflight(boolean isAttach, boolean updateBeforeLaunch, String configProject,
        String effectiveAppId)
    {
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
        return null;
    }

    /**
     * Builds the success response for the by-name launch path. Pure formatting of
     * read-only inputs; no behavioural change relative to the inline builder.
     */
    private String buildLaunchSuccess(ILaunchConfiguration config, String typeId, boolean isAttach,
        String configProject, String effectiveAppId)
    {
        ToolResult result = ToolResult.success()
            .put(KEY_LAUNCH_CONFIGURATION, config.getName())
            .put(KEY_CONFIGURATION_TYPE, typeId)
            .put(KEY_ATTACH, isAttach)
            .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
            .put(KEY_STATUS, "launching") //$NON-NLS-1$
            .put(McpKeys.MESSAGE, isAttach
                ? "Attach debug session is connecting — poll debug_status to confirm it is " //$NON-NLS-1$
                    + "running, then wait_for_break to block until a breakpoint is hit." //$NON-NLS-1$
                : "Debug session is starting asynchronously. The 1C client may show startup " //$NON-NLS-1$
                    + "dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
                    + "Poll debug_status until the session appears running, then use " //$NON-NLS-1$
                    + "wait_for_break."); //$NON-NLS-1$
        if (configProject != null && !configProject.isEmpty())
        {
            result.put(McpKeys.PROJECT, configProject);
        }
        if (effectiveAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, effectiveAppId);
        }
        return result.toJson();
    }

    /**
     * Legacy path: launch a runtime-client config matched by project+application.
     */
    private String launchDebug(String projectName, String applicationId, boolean updateBeforeLaunch,
        boolean restartIfRunning)
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

            // Verify application exists and get its name
            IApplicationManager appManager = Activator.getDefault().getApplicationManager();
            ApplicationResolution appResolution = resolveApplication(project, applicationId, appManager);
            if (appResolution.error != null)
            {
                return appResolution.error;
            }
            String applicationName = appResolution.applicationName;
            IApplication application = appResolution.application;

            // Unified existing-session decision (see helper): a live DEBUG client target
            // OR a debug-target-less RUN-mode launch short-circuits; a standalone-SERVER
            // session sharing this app id does NOT. Returns null to fall through and
            // (re)launch, otherwise the short-circuit / already-running payload.
            String existingSessionResult =
                handleExistingByAppSession(applicationId, projectName, restartIfRunning);
            if (existingSessionResult != null)
            {
                return existingSessionResult;
            }

            // Update database before launch if requested. Routes through the
            // shared LaunchLifecycleUtils.updateApplicationIfNeeded so debug_launch
            // analyses "does the IB need updating?" the same way the YAXUnit tools
            // do: skip on UPDATED, wait on BEING_UPDATED, incremental-update otherwise.
            // For a STANDALONE-SERVER application the programmatic update is SKIPPED
            // and deferred to the launch delegate's coordinated path instead — see
            // runPreLaunchUpdateStep.
            if (appManager != null && application != null)
            {
                String updateError = runPreLaunchUpdateStep(project, applicationId,
                    appManager, updateBeforeLaunch);
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

            // Delegate-criterion duplicate guard — same supplement as
            // the by-name path: catch a UI-started / target-manager-only DEBUG session
            // the ILaunchManager guards above cannot see, BEFORE config.launch raises
            // the human "Debug session already exists" modal. See
            // handleDelegateDuplicateSession.
            String dupResult = handleDelegateDuplicateSession(matchingConfig, projectName,
                false, LaunchConfigUtils.getConfigTypeId(matchingConfig), restartIfRunning);
            if (dupResult != null)
            {
                return dupResult;
            }

            String launchError = performLaunch(matchingConfig, updateBeforeLaunch);
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch debug session: " + launchError).toJson(); //$NON-NLS-1$
            }

            return ToolResult.success()
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_LAUNCH_CONFIGURATION, configName)
                .put(KEY_CONFIGURATION_TYPE, LaunchConfigUtils.getConfigTypeId(matchingConfig))
                .put(KEY_ATTACH, false)
                .put("mode", "debug") //$NON-NLS-1$ //$NON-NLS-2$
                .put(KEY_STATUS, "launching") //$NON-NLS-1$
                .put(McpKeys.MESSAGE, "Debug session is starting asynchronously. The 1C client may show " //$NON-NLS-1$
                    + "startup dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
                    + "Poll debug_status until the session appears running, then use wait_for_break.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during debug launch", e); //$NON-NLS-1$
            return ToolResult.error("Unexpected error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the application by id and its display name for the legacy
     * project+applicationId path. Mirrors the original inline guard: when {@code appManager}
     * is null the application stays unresolved (name defaults to the id); a present manager
     * that cannot find the id yields an {@code error} payload, while an
     * {@link ApplicationException} is logged and swallowed so the caller still tries to
     * find a launch configuration.
     *
     * @param project the project to look the application up in
     * @param applicationId the application id to resolve
     * @param appManager the application manager (may be null)
     * @return an {@link ApplicationResolution}; its {@code error} is non-null only when the
     *     id was definitively not found
     */
    private static ApplicationResolution resolveApplication(IProject project, String applicationId,
        IApplicationManager appManager)
    {
        ApplicationResolution resolution = new ApplicationResolution();
        resolution.applicationName = applicationId; // Default to ID if can't get name

        if (appManager != null)
        {
            try
            {
                Optional<IApplication> appOpt = appManager.getApplication(project, applicationId);
                if (!appOpt.isPresent())
                {
                    resolution.error = ToolResult.error("Application not found: " + applicationId + //$NON-NLS-1$
                            ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
                    return resolution;
                }
                resolution.application = appOpt.get();
                resolution.applicationName = resolution.application.getName();
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error checking application", e); //$NON-NLS-1$
                // Continue - we'll try to find launch config anyway
            }
        }
        return resolution;
    }

    /**
     * Unified existing-session decision for the project+applicationId path — the SAME
     * CLIENT-typed-thread-discriminated detector + restartIfRunning handling the by-name
     * path uses, so both call styles behave identically. A live DEBUG client target OR a
     * debug-target-less RUN-mode launch short-circuits (the legacy already-running guard);
     * a standalone-SERVER session sharing this app id — even a debug-mode one with a live
     * SERVER-typed thread — does NOT (the client proceeds and attaches). To force a fresh
     * launch when restartIfRunning is false, terminate_launch first.
     *
     * @param applicationId the application id to match an existing client session on
     * @param projectName the project name echoed into the already-running payload
     * @param restartIfRunning whether to terminate an existing client and relaunch
     * @return the short-circuit / already-running payload, or {@code null} to fall through
     *     and (re)launch (no existing client, or restartIfRunning terminated the old one)
     */
    private String handleExistingByAppSession(String applicationId, String projectName,
        boolean restartIfRunning)
    {
        ExistingClientSession existingByApp =
            LaunchLifecycleUtils.resolveExistingClientSession(applicationId);
        if (existingByApp != null)
        {
            ILaunchConfiguration activeConfig = existingByApp.launch != null
                ? existingByApp.launch.getLaunchConfiguration() : null;
            AlreadyRunningContext runningCtx = new AlreadyRunningContext(ALREADY_RUNNING_MESSAGE);
            runningCtx.project = projectName;
            runningCtx.attach = Boolean.FALSE;
            if (activeConfig != null)
            {
                runningCtx.launchConfiguration = activeConfig.getName();
            }
            String shortCircuit = handleExistingClientSession(existingByApp, applicationId,
                restartIfRunning, runningCtx);
            if (shortCircuit != null)
            {
                return shortCircuit;
            }
            // restartIfRunning=true: the old client was terminated — fall through
            // and relaunch.
        }
        return null;
    }

    /**
     * Holder for {@link #resolveApplication}: the resolved {@link IApplication} (may stay
     * null) and its display name, or an {@code error} payload the caller returns as-is.
     */
    private static class ApplicationResolution
    {
        IApplication application;
        String applicationName;
        String error;
    }

    /**
     * Runs the EDT "update database before launch" step for a runtime-client launch.
     * Returns {@code null} on success, or an error message describing the failure.
     *
     * <p>Synthetic application ids — {@code attach:<configName>},
     * {@code launch:<configName>} and {@code ServerApplication.<app>}, see
     * {@link LaunchConfigUtils#isSyntheticApplicationId} — skip the preflight.
     * They are minted by
     * {@link LaunchConfigUtils#getApplicationIdFor(ILaunchConfiguration)} (or, for
     * the {@code ServerApplication.} form, by
     * {@code DebugServerTargetSupport}) for sessions WITHOUT a persisted
     * {@code ATTR_APPLICATION_ID} and cannot be
     * resolved through {@link IApplicationManager}: feeding one into
     * {@code updateApplicationIfNeeded} fails with "Application not found:
     * launch:&lt;name&gt;" and would refuse a perfectly launchable configuration.
     * (The original guard knew only the {@code attach:} prefix, so introducing the
     * {@code launch:} fallback for UI-started-session tracking silently turned
     * such by-name launches into errors.) Skipping is safe:
     * if the launch delegate still detects an out-of-date IB it shows its update
     * modal, which the armed {@link LaunchUpdateDialogAutoConfirmer} presses.
     *
     * <p>For the {@code ServerApplication.*} form the skip is not merely an
     * "unresolvable id" technicality — it is the INTENDED behavior: a
     * standalone-server application must never be DB-updated out-of-band,
     * because {@code IApplicationManager.update} on it starts the standalone server
     * in RUN mode and holds a cached designer-agent connection that wedges the
     * subsequent debug restart. The update is deferred to the launch delegate's
     * coordinated path (server prepared in debug mode FIRST, then updated), whose
     * dialog the armed confirmer auto-presses — see
     * {@link DebugServerTargetSupport#isServerApplicationId} and
     * {@link #runPreLaunchUpdateStep}, the same gate on the
     * project+applicationId path.
     */
    private String updateDatabaseIfNeeded(String projectName, String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty()
            || LaunchConfigUtils.isSyntheticApplicationId(applicationId))
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
     * The pre-launch DB-update step of the project+applicationId path, with the
     * server-application gate. Returns {@code null} to proceed with
     * the launch, or an error message that aborts the call.
     *
     * <ul>
     *   <li>{@code updateBeforeLaunch=false} — documented opt-out: no programmatic
     *       update is run (and {@link #performLaunch} leaves the update confirmer
     *       unarmed, so the platform's update modal — if any — is a human's).</li>
     *   <li>{@code ServerApplication.*} id ({@link
     *       DebugServerTargetSupport#isServerApplicationId}) — the programmatic
     *       update is SKIPPED and deferred to the launch delegate's coordinated
     *       path. Updating a standalone-server application out-of-band starts the
     *       server in RUN mode and caches a live designer-agent connection
     *       (DesignerSessionPool); the launch delegate then restarts the server in
     *       DEBUG mode and the connection teardown wedges the launch. EDT's native
     *       order (prepare the server in debug mode FIRST, then update) has no such
     *       restart; its "Application update" dialog — shown only when the IB is
     *       stale — is auto-pressed by the confirmer {@link #performLaunch} arms
     *       exactly when {@code updateBeforeLaunch=true}. Trade-off: the synchronous
     *       "stale IB" refusal disappears for server apps (the update happens
     *       asynchronously inside the launch); failures surface via
     *       {@code debug_status} / the EDT log — matching EDT-native UX.</li>
     *   <li>Any other (file / client-server infobase) application — the programmatic
     *       pre-update runs exactly as before through
     *       {@link LaunchLifecycleUtils#updateApplicationIfNeeded}: skip on UPDATED,
     *       wait on BEING_UPDATED, incremental-update otherwise; a stale IB still
     *       refuses synchronously.</li>
     * </ul>
     *
     * <p>Package-private (static, mock-friendly) so the headless unit tests can
     * assert the gate decision without a live workbench.
     */
    static String runPreLaunchUpdateStep(IProject project, String applicationId,
        IApplicationManager appManager, boolean updateBeforeLaunch)
    {
        if (!updateBeforeLaunch)
        {
            return null;
        }
        if (DebugServerTargetSupport.isServerApplicationId(applicationId))
        {
            Activator.logInfo("debug_launch: server application: deferring DB update to the " //$NON-NLS-1$
                + "launch delegate's coordinated path (auto-confirmed): applicationId=" //$NON-NLS-1$
                + applicationId);
            return null;
        }
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager)
            .orElse(null);
    }

    /**
     * The single existing-CLIENT-session handler all {@code ILaunchManager}-sourced
     * call sites funnel through, so {@code restartIfRunning} is honored identically
     * everywhere:
     * <ul>
     *   <li>{@code restartIfRunning=false} (default) → returns the
     *       {@code alreadyRunning:true} short-circuit JSON (no launch), carrying the
     *       identity fields the caller supplied.</li>
     *   <li>{@code restartIfRunning=true} → non-interactively terminates the existing
     *       client session (its live DEBUG target, or — for a RUN-mode launch — the
     *       launch) via the shared
     *       {@link LaunchLifecycleUtils#terminateExistingSessionAndWait} /
     *       {@link LaunchLifecycleUtils#terminateExistingLaunchAndWait} helpers
     *       (terminate + {@code forgetApplication} + ≤3s wait), then returns
     *       {@code null} so the caller relaunches — exactly what the target-manager
     *       path ({@link #handleDelegateDuplicateSession}) already does.</li>
     * </ul>
     *
     * @param session the resolved live client session (never {@code null})
     * @param applicationId the application id under which the session was found
     * @param restartIfRunning the flag from the request
     * @param ctx identity fields to echo into the {@code alreadyRunning} payload
     * @return the short-circuit JSON, or {@code null} to proceed with the launch
     */
    String handleExistingClientSession(ExistingClientSession session, String applicationId,
        boolean restartIfRunning, AlreadyRunningContext ctx)
    {
        if (!restartIfRunning)
        {
            Activator.logInfo("debug_launch short-circuit (alreadyRunning): applicationId=" //$NON-NLS-1$
                + applicationId + ", mode=" + session.mode //$NON-NLS-1$
                + ", config=" + ctx.launchConfiguration); //$NON-NLS-1$
            return ctx.buildAlreadyRunning(session.mode, applicationId).toJson();
        }

        // restartIfRunning: stop the existing client session non-interactively, then
        // proceed. resolveExistingClientSession only ever returns a real client (a
        // DEBUG target with a live CLIENT-typed thread, or a RUN-mode launch), NEVER
        // a server/profiling target — a debug-mode standalone server's live thread is
        // typed SERVER and is filtered out — so this terminate can never kill a debug
        // server.
        if (session.liveTarget != null)
        {
            Activator.logInfo("debug_launch restartIfRunning: terminating existing client debug " //$NON-NLS-1$
                + "target: applicationId=" + applicationId); //$NON-NLS-1$
            LaunchLifecycleUtils.terminateExistingSessionAndWait(session.liveTarget, applicationId);
        }
        else
        {
            Activator.logInfo("debug_launch restartIfRunning: terminating existing client launch " //$NON-NLS-1$
                + "(mode=" + session.mode + "): applicationId=" + applicationId); //$NON-NLS-1$ //$NON-NLS-2$
            LaunchLifecycleUtils.terminateExistingLaunchAndWait(session.launch, applicationId);
        }
        return null;
    }

    /**
     * Identity fields echoed into an {@code alreadyRunning:true} short-circuit so the
     * unified {@link #handleExistingClientSession} can build a per-call-site payload
     * that matches what each legacy guard emitted (output-schema parity). Optional
     * fields ({@code null}/empty) are omitted.
     */
    static final class AlreadyRunningContext
    {
        String launchConfiguration;
        String configurationType;
        Boolean attach;
        String project;
        final String message;

        AlreadyRunningContext(String message)
        {
            this.message = message;
        }

        ToolResult buildAlreadyRunning(String mode, String applicationId)
        {
            ToolResult already = ToolResult.success()
                .put("alreadyRunning", true) //$NON-NLS-1$
                .put("mode", mode) //$NON-NLS-1$
                .put(McpKeys.MESSAGE, message);
            if (launchConfiguration != null && !launchConfiguration.isEmpty())
            {
                already.put(KEY_LAUNCH_CONFIGURATION, launchConfiguration);
            }
            if (configurationType != null && !configurationType.isEmpty())
            {
                already.put(KEY_CONFIGURATION_TYPE, configurationType);
            }
            if (attach != null)
            {
                already.put(KEY_ATTACH, attach.booleanValue());
            }
            if (project != null && !project.isEmpty())
            {
                already.put(McpKeys.PROJECT, project);
            }
            if (applicationId != null && !applicationId.isEmpty())
            {
                already.put(McpKeys.APPLICATION_ID, applicationId);
            }
            return already;
        }
    }

    /** Default short-circuit message for a still-running client session. */
    private static final String ALREADY_RUNNING_MESSAGE =
        "Launch configuration is already running — skipped re-launch. " //$NON-NLS-1$
            + "Call terminate_launch first, or pass restartIfRunning=true, to start a fresh session."; //$NON-NLS-1$

    /**
     * Detects a live runtime-client DEBUG session for {@code config}'s
     * {@code (project, delegate-app-id)} the EXACT way EDT's
     * {@code RuntimeClientLaunchDelegate.checkExistingDebugSessions} does — via
     * {@link DebugServerTargetSupport#findRuntimeClientDebugTarget} over the target
     * manager's {@code listDebugTargets()} set, keyed on the delegate's app id
     * ({@code ATTR_APPLICATION_ID} else {@code getDefaultApplication(project)}, see
     * {@link LaunchLifecycleUtils#resolveDelegateApplicationId}). This is the primary
     * duplicate-session guard: it fires BEFORE {@code config.launch} can raise the human
     * "Debug session already exists" code-1003 modal that hangs an unattended call.
     *
     * <ul>
     *   <li>No live duplicate → returns {@code null}; the caller proceeds to launch.</li>
     *   <li>Duplicate found, {@code restartIfRunning=false} (default) → returns the
     *       {@code alreadyRunning:true} short-circuit JSON (no dialog, no launch),
     *       consistent with the documented contract.</li>
     *   <li>Duplicate found, {@code restartIfRunning=true} → terminates the existing
     *       session NON-interactively, {@code forgetApplication}s it, waits up to
     *       ~3s for process death, then returns {@code null} so the caller relaunches.</li>
     * </ul>
     *
     * @return the short-circuit JSON, or {@code null} to proceed with the launch
     */
    private String handleDelegateDuplicateSession(ILaunchConfiguration config, String projectName,
        boolean isAttach, String typeId, boolean restartIfRunning)
    {
        String delegateAppId = LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName);
        IDebugTarget existing =
            DebugServerTargetSupport.findRuntimeClientDebugTarget(projectName, delegateAppId);
        if (existing == null)
        {
            return null;
        }

        // Defensive re-assert: findRuntimeClientDebugTarget already
        // required a live CLIENT-typed thread, but if the matched target lost its last
        // live client thread between detection and now it is no longer a client — do
        // NOT short-circuit or terminate; just proceed to launch.
        if (DebugServerTargetSupport.findFirstLiveClientThread(existing) == null)
        {
            Activator.logInfo("debug_launch: target-manager match has no live CLIENT-typed thread " //$NON-NLS-1$
                + "(server/profiling target) — not short-circuiting; proceeding: project=" //$NON-NLS-1$
                + projectName + ", applicationId=" + delegateAppId); //$NON-NLS-1$
            return null;
        }

        // Funnel through the SAME restartIfRunning-aware handler the ILaunchManager
        // guards use, so the flag is honored identically across every path. The
        // matched target carries a live CLIENT-typed thread, so the handler's terminate
        // path stops a real client, never a debug server.
        ExistingClientSession session = new ExistingClientSession(existing.getLaunch(), existing,
            ILaunchManager.DEBUG_MODE);
        AlreadyRunningContext ctx = new AlreadyRunningContext(
            "Debug session is already running (detected via EDT's debug target manager — e.g. a " //$NON-NLS-1$
                + "UI-started 'Debug As' session) — skipped re-launch to avoid the 'Debug session " //$NON-NLS-1$
                + "already exists' modal. Call terminate_launch first, or pass " //$NON-NLS-1$
                + "restartIfRunning=true, to start a fresh session."); //$NON-NLS-1$
        ctx.launchConfiguration = config.getName();
        ctx.configurationType = typeId;
        ctx.attach = Boolean.valueOf(isAttach);
        ctx.project = projectName;
        // Force mode "debug" in the short-circuit payload (this path is the runtime-
        // client DEBUG delegate) regardless of the synthetic launch's reported mode.
        return handleExistingClientSession(session, delegateAppId, restartIfRunning, ctx);
    }

    /**
     * Launches the given configuration in debug mode, asynchronously.
     *
     * <p>Uses a direct {@code config.launch(DEBUG_MODE, monitor)} — not
     * {@code DebugUITools.launch} — because the latter may open modal dialogs
     * (save-prompt, perspective-switch, already-running-confirmation) that
     * block the MCP worker thread indefinitely and eventually close the HTTP
     * socket. {@code debug_yaxunit_tests} uses the same direct path.
     *
     * <p>The launch runs in a BACKGROUND {@link Job} — never
     * on the SWT UI thread — and this method returns immediately: it does NOT
     * wait for the 1C client to finish starting. The previous {@code asyncExec}
     * dispatch ran the ENTIRE {@code RuntimeClientLaunchDelegate.doLaunch} —
     * including the standalone-server non-debug→debug stop+restart, which takes
     * minutes — ON the UI thread, freezing the whole workbench ("not responding",
     * pale window) for that whole time. A manual EDT launch never freezes because
     * {@code DebugUIPlugin.launchInBackground} runs the launch in a background Job;
     * this Job mirrors that exact shape: {@link Job#INTERACTIVE} priority, no
     * scheduling rule, neither {@code setUser} nor {@code setSystem} — so it shows
     * in the Progress view like EDT's own launches. The delegate's modals
     * self-marshal to the UI thread ({@code syncCall}), so they still appear there
     * and the armed auto-confirmer (whose {@link Display} filter fires on the UI
     * thread regardless of which thread ran {@code launch()}) still presses them.
     * Callers therefore report {@code status: "launching"}; readiness is observed
     * separately via {@code debug_status} / {@code wait_for_break}.
     *
     * <p>Because the launch now runs after this method returns, any failure can no
     * longer be surfaced synchronously to the caller — it is logged from inside the
     * Job body ({@link #runLaunchJobBody}) and reflected in the Job's result
     * {@link IStatus}. Only the synchronous (headless, no workbench) path can
     * still return an error message.
     *
     * <p>The {@code config.launch(...)} call is always wrapped in
     * {@link LaunchUpdateDialogAutoConfirmer#arm(boolean, boolean)}/{@link LaunchUpdateDialogAutoConfirmer#disarm(boolean, boolean)}.
     * Two independently-gated matchers share one {@link Display} filter:
     * <ul>
     *   <li>the "Application update" matcher is armed only when
     *       {@code autoConfirmUpdateDialog} is {@code true}. Even though the
     *       pre-launch update ({@code updateApplicationIfNeeded}) normally leaves the
     *       IB {@code UPDATED} so the EDT launch delegate skips its modal, an IB whose
     *       DB config is genuinely behind (e.g. a restructure the delegate re-detects)
     *       can still pop the "Update then run / Run without update" dialog; while
     *       armed the filter auto-presses its default ("Update then run") button.</li>
     *   <li>the code-1003 "debug session already exists" matcher is armed
     *       <em>unconditionally</em> on this debug path (independent of
     *       {@code autoConfirmUpdateDialog}). With {@code restartIfRunning=true} and a
     *       {@code terminate()} that times out, the relaunch can still race a residual
     *       1003 modal; auto-pressing its "Keep existing and start new" button (located
     *       by label — never the destructive default "stop existing and start new")
     *       keeps an unattended call from hanging. Pressing it performs NO DB update,
     *       so it does not undo the {@code updateBeforeLaunch=false} opt-out.</li>
     * </ul>
     * The arm/disarm runs INSIDE the Job body's try/finally — both are thread-safe
     * from any thread (counters under a lock + a {@code syncExec} reconcile), and
     * the dialog shells are always created on the UI thread, so the filter fires
     * there no matter which thread ran the launch. The MCP worker has already
     * returned, so the server is never hung.
     *
     * <p>Callers pass {@code updateBeforeLaunch} for {@code autoConfirmUpdateDialog}:
     * with {@code updateBeforeLaunch=false} the documented contract is that the
     * platform "may then show that modal" — auto-pressing the UPDATE dialog's default
     * button would silently perform the very DB update the caller disabled, so the
     * UPDATE matcher is NOT armed and that dialog is left for a human. The 1003
     * matcher, which performs no update, stays armed regardless.
     *
     * <p>Package-private (not {@code private}) so the headless unit tests can
     * exercise the synchronous fallback directly.
     *
     * @return {@code null} when the launch was scheduled (or, in a headless test
     *         with no UI thread, completed) successfully; otherwise an error message.
     */
    String performLaunch(ILaunchConfiguration config, boolean autoConfirmUpdateDialog)
    {
        // Workbench-aware probe: never creates a display. It
        // decides Job-vs-headless ONLY: with a live workbench the launch is
        // dispatched as a background Job; a truly headless runtime takes the
        // synchronous fallback below instead of scheduling work nothing observes.
        Display display = LaunchLifecycleUtils.workbenchDisplayOrNull();
        if (display != null && !display.isDisposed())
        {
            // Fire-and-forget in a background Job (mirroring EDT's own
            // DebugUIPlugin.launchInBackground): returns control to the MCP worker
            // immediately, keeps the EDT UI thread free — a minutes-long delegate
            // (e.g. the standalone-server mode-switch restart) no longer freezes
            // the workbench. The launch outcome can no longer be returned to the
            // caller, so the Job body logs it and reports it as its result status.
            Job job = new Job("Launching " + config.getName()) //$NON-NLS-1$
            {
                @Override
                protected IStatus run(IProgressMonitor monitor)
                {
                    return runLaunchJobBody(config, autoConfirmUpdateDialog, monitor);
                }
            };
            job.setPriority(Job.INTERACTIVE);
            job.schedule();
            return null;
        }
        // No workbench (headless tests): launch synchronously and surface errors.
        LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, true);
        try
        {
            config.launch(ILaunchManager.DEBUG_MODE, null);
            return null;
        }
        catch (CoreException e)
        {
            Activator.logError("Error launching debug session", e); //$NON-NLS-1$
            return e.getMessage();
        }
        finally
        {
            LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, true);
        }
    }

    /**
     * The body of the background launch {@link Job} — the
     * seam {@link #performLaunch} schedules and the headless unit tests exercise
     * directly. Arms the {@link LaunchUpdateDialogAutoConfirmer} (update matcher
     * gated on {@code autoConfirmUpdateDialog}, code-1003 matcher unconditional —
     * the same flags the asyncExec dispatch used), runs the launch, and ALWAYS
     * disarms in {@code finally} — both calls are thread-safe from a Job thread.
     *
     * <p>Never throws: a Job that dies on an uncaught exception fails silently for
     * the MCP caller, so EVERY failure — {@link CoreException} or any other
     * {@link Throwable} — is logged to the EDT error log and returned as an error
     * {@link IStatus} (visible as the Job's result in the Progress view).
     *
     * @param config the launch configuration to start in debug mode
     * @param autoConfirmUpdateDialog arm the "Application update" matcher
     * @param monitor the Job's progress monitor, passed through to
     *        {@code config.launch} so the Progress view shows the delegate's steps
     * @return {@link Status#OK_STATUS} on success, else an error status
     */
    static IStatus runLaunchJobBody(ILaunchConfiguration config, boolean autoConfirmUpdateDialog,
        IProgressMonitor monitor)
    {
        // Auto-confirm EDT's blocking launch modals for the duration of this
        // single launch only. The "Application update" modal is pressed
        // only when the caller did NOT opt out of the DB update; the
        // code-1003 "debug session already exists" modal is ALWAYS
        // auto-confirmed on this debug path (it is independent of the update
        // opt-out). Manual EDT launches outside this window still prompt.
        LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, true);
        try
        {
            config.launch(ILaunchManager.DEBUG_MODE, monitor);
            return Status.OK_STATUS;
        }
        catch (CoreException e)
        {
            Activator.logError(ERR_ASYNC_PREFIX + e.getMessage(), e);
            return e.getStatus();
        }
        catch (Throwable t)
        {
            // Never let the Job die on an uncaught exception — it would vanish
            // without a trace for the MCP caller. Log + report an error status.
            Activator.logError(ERR_ASYNC_PREFIX + t.getMessage(), t);
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                ERR_ASYNC_PREFIX + t.getMessage(), t);
        }
        finally
        {
            LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, true);
        }
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
            obj.addProperty(KEY_ATTACH, LaunchConfigUtils.isAttachConfigTypeId(typeId));
            obj.addProperty(McpKeys.PROJECT, LaunchConfigUtils.readAttribute(cfg,
                LaunchConfigUtils.ATTR_PROJECT_NAME, "")); //$NON-NLS-1$
            obj.addProperty(McpKeys.APPLICATION_ID, LaunchConfigUtils.readAttribute(cfg,
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
