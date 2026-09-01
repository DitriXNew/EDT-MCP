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
import com.ditrix.edt.mcp.server.utils.AsyncLaunchOutcomes;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.InfobaseAuthDialogSuppressor;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchOverrides;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils.ExistingClientSession;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tool to launch an EDT session in debug or run mode.
 *
 * <p>Two target-selection forms:
 * <ul>
 *   <li>{@code launchConfigurationName} — start an existing EDT launch configuration
 *       by its exact name. Works for both runtime-client configs (spawns 1cv8c) and
 *       Attach configurations (attaches to {@code ragent}/{@code rphost} for
 *       server-side code). Does not require {@code applicationId}.</li>
 *   <li>{@code projectName} + {@code applicationId} — legacy path: searches the
 *       runtime-client configs for a match and launches it.</li>
 * </ul>
 */
public class LaunchTool implements IMcpTool
{
    public static final String NAME = "launch"; //$NON-NLS-1$

    /** Input/output key: requested EDT launch mode. */
    private static final String KEY_MODE = "mode"; //$NON-NLS-1$

    /** MCP launch-mode token preserving the historical behaviour. */
    static final String MODE_DEBUG = "debug"; //$NON-NLS-1$

    /** MCP launch-mode token for a regular EDT Run launch. */
    static final String MODE_RUN = "run"; //$NON-NLS-1$

    /** Output key: name of the launched/running launch configuration. */
    private static final String KEY_LAUNCH_CONFIGURATION = "launchConfiguration"; //$NON-NLS-1$

    /** Output key: launch configuration type id. */
    private static final String KEY_CONFIGURATION_TYPE = "configurationType"; //$NON-NLS-1$

    /** Output key: true if this is an Attach (server-side debug) configuration. */
    private static final String KEY_ATTACH = "attach"; //$NON-NLS-1$

    /** Output key: launch status (e.g. "launching"). */
    private static final String KEY_STATUS = "status"; //$NON-NLS-1$

    /** Error-log prefix for an asynchronous launch failure. */
    private static final String ERR_ASYNC_PREFIX = "launch failed asynchronously: "; //$NON-NLS-1$

    /**
     * Input param AND response field: the {@code /C} startup option applied to this launch only.
     *
     * <p>Declared as a tool-local constant, not imported from {@link LaunchOverrides}: the
     * schema/execute parity scan resolves constants from THIS source plus {@code McpKeys}, so a
     * key that lives elsewhere reads as an undeclared parameter.</p>
     */
    private static final String KEY_STARTUP_OPTION = "startupOption"; //$NON-NLS-1$

    /** Input param and response field: the external-objects project holding the object to run. */
    private static final String KEY_EXTERNAL_OBJECT_PROJECT_NAME = "externalObjectProjectName"; //$NON-NLS-1$

    /** Input param and response field: the external data processor / report to run on startup. */
    private static final String KEY_EXTERNAL_OBJECT_NAME = "externalObjectName"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Start a 1C application in EDT debug (default) or run mode. An already-running " //$NON-NLS-1$
            + "session is NOT " //$NON-NLS-1$
            + "relaunched - the call short-circuits with alreadyRunning:true; restartIfRunning=true " //$NON-NLS-1$
            + "instead TERMINATES that live session first. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('launch')."; //$NON-NLS-1$
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
                "Exact name of an EDT launch config (runtime client or Attach); skips projectName/applicationId.") //$NON-NLS-1$
            .enumProperty(KEY_MODE,
                "Launch mode: debug (default) or run. Attach configurations support debug only.", //$NON-NLS-1$
                MODE_DEBUG, MODE_RUN)
            .booleanProperty("updateBeforeLaunch", //$NON-NLS-1$
                "Default true: silently apply the configuration->DB update before launching so no " //$NON-NLS-1$
                    + "'Update database?' modal blocks the call (even on a Russian-locale EDT the dialog " //$NON-NLS-1$
                    + "is auto-confirmed); false skips the update and the platform may then show that " //$NON-NLS-1$
                    + "modal. Ignored for Attach.") //$NON-NLS-1$
            .stringProperty("externalInfobaseChanges", //$NON-NLS-1$
                "How to answer EDT's blocking 'Infobase configuration changes' modal when the infobase " //$NON-NLS-1$
                    + "was changed outside EDT (Designer, ibcmd, a CLI pipeline) since the last EDT " //$NON-NLS-1$
                    + "interaction: 'override' (default) keeps the project configuration and overwrites " //$NON-NLS-1$
                    + "the infobase, 'import' pulls the external changes into the PROJECT sources, " //$NON-NLS-1$
                    + "'cancel' aborts the update with an error. Omitted, the modal is still " //$NON-NLS-1$
                    + "answered (with 'override'), so an unattended call never blocks on it.") //$NON-NLS-1$
            .stringProperty("standaloneServerPortConflict", //$NON-NLS-1$
                StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION)
            .stringProperty(KEY_STARTUP_OPTION,
                "The 1C /C startup option for THIS launch only (e.g. 'xddRun ...; xddReport ...'); " //$NON-NLS-1$
                    + "applied to a working copy, the saved EDT configuration is not modified. " //$NON-NLS-1$
                    + "Runtime-client configs only - an Attach config ignores it and is refused.") //$NON-NLS-1$
            .stringProperty(KEY_EXTERNAL_OBJECT_PROJECT_NAME,
                "Name of an EXTERNAL-OBJECTS project (not the configuration being debugged) whose " //$NON-NLS-1$
                    + "data processor / report to run on startup; pair with externalObjectName. " //$NON-NLS-1$
                    + "EDT builds the .epf itself - there is no way to run a prebuilt file with " //$NON-NLS-1$
                    + "breakpoints, so import such a file into a project first.") //$NON-NLS-1$
            .stringProperty(KEY_EXTERNAL_OBJECT_NAME,
                "Name of the external data processor / report inside externalObjectProjectName " //$NON-NLS-1$
                    + "(the object NAME, not a file path); required together with it. Qualify it as " //$NON-NLS-1$
                    + "'ExternalDataProcessor.Name' / 'ExternalReport.Name' when a processor and a " //$NON-NLS-1$
                    + "report share the name.") //$NON-NLS-1$
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
            .stringProperty(KEY_STARTUP_OPTION,
                "Echoed back when a /C startup option was applied to this launch; absent otherwise.") //$NON-NLS-1$
            .stringProperty(KEY_EXTERNAL_OBJECT_PROJECT_NAME,
                "Echoed back when an external object was launched; absent otherwise.") //$NON-NLS-1$
            .stringProperty(KEY_EXTERNAL_OBJECT_NAME,
                "The external data processor / report this launch runs; absent when none was requested.") //$NON-NLS-1$
            .stringProperty(KEY_MODE,
                "Requested launch mode, or the existing session mode when alreadyRunning is true") //$NON-NLS-1$
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
    public boolean connectsToInfobase()
    {
        // config.launch(...) connects a runtime client to the infobase, synchronously or
        // via the fire-and-forget background launch Job (issue #270).
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String rawMode = JsonUtils.extractStringArgument(params, KEY_MODE);
        String mode = extractLaunchMode(params);
        if (mode == null)
        {
            return ToolResult.error("Unknown mode value: '" + rawMode //$NON-NLS-1$
                + "'. Accepted values: " + MODE_DEBUG + ", " + MODE_RUN + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String configName = JsonUtils.extractStringArgument(params, "launchConfigurationName"); //$NON-NLS-1$
        boolean updateBeforeLaunch = JsonUtils.extractBooleanArgument(params, "updateBeforeLaunch", true); //$NON-NLS-1$
        boolean restartIfRunning = extractRestartIfRunning(params);
        String rawPolicy = JsonUtils.extractStringArgument(params, "externalInfobaseChanges"); //$NON-NLS-1$
        ExternalInfobaseChangesPolicy policy = ExternalInfobaseChangesPolicy.parse(rawPolicy);
        if (policy == null)
        {
            return ToolResult.error("Unknown externalInfobaseChanges value: '" + rawPolicy //$NON-NLS-1$
                + "'. Accepted values: " + ExternalInfobaseChangesPolicy.acceptedValues()).toJson(); //$NON-NLS-1$
        }
        String rawPortPolicy =
            JsonUtils.extractStringArgument(params, "standaloneServerPortConflict"); //$NON-NLS-1$
        StandaloneServerPortConflictPolicy portPolicy =
            StandaloneServerPortConflictPolicy.parse(rawPortPolicy);
        if (portPolicy == null)
        {
            return ToolResult.error("Unknown standaloneServerPortConflict value: '" + rawPortPolicy //$NON-NLS-1$
                + "'. Accepted values: " //$NON-NLS-1$
                + StandaloneServerPortConflictPolicy.acceptedValues()).toJson();
        }

        // Read here, in execute(), rather than inside LaunchOverrides: rule #6 parity is checked
        // by scanning THIS method for the project's accessor idioms.
        LaunchOverrides overrides = LaunchOverrides.of(
            JsonUtils.extractStringArgument(params, KEY_STARTUP_OPTION),
            JsonUtils.extractStringArgument(params, KEY_EXTERNAL_OBJECT_PROJECT_NAME),
            JsonUtils.extractStringArgument(params, KEY_EXTERNAL_OBJECT_NAME));
        // Validated up front, alongside the enum parses above and before EITHER launch mode: both
        // of them can terminate a live client session and update the infobase on the way to the
        // launch, and a mistyped external object must not cost the caller those.
        LaunchOverrides.Prepared prepared = overrides.prepare();
        if (prepared.errorJson != null)
        {
            return prepared.errorJson;
        }

        // Target form 1: explicit config name — no project/application required.
        if (configName != null && !configName.isEmpty())
        {
            return launchByConfigName(configName, updateBeforeLaunch, restartIfRunning, policy,
                portPolicy, overrides, prepared, mode);
        }

        // Target form 2: project + application (runtime-client only).
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

        return launch(projectName, applicationId, updateBeforeLaunch, restartIfRunning, policy,
            portPolicy, overrides, prepared, mode);
    }

    /**
     * Extracts and validates the public launch mode. Omission preserves the old
     * launch behaviour by selecting {@code debug}.
     *
     * @param params tool arguments
     * @return {@code debug}, {@code run}, or {@code null} for an unknown value
     */
    static String extractLaunchMode(Map<String, String> params)
    {
        String mode = JsonUtils.extractStringArgument(params, KEY_MODE);
        if (mode == null || mode.isEmpty())
        {
            return MODE_DEBUG;
        }
        return MODE_DEBUG.equals(mode) || MODE_RUN.equals(mode) ? mode : null;
    }

    /** Maps the public mode token to Eclipse's launch-manager token. */
    private static String eclipseLaunchMode(String mode)
    {
        return MODE_RUN.equals(mode) ? ILaunchManager.RUN_MODE : ILaunchManager.DEBUG_MODE;
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
     * Launches a specific EDT configuration by name.
     * Works for both runtime-client and Attach configuration types.
     */
    private String launchByConfigName(String configName, boolean updateBeforeLaunch, // NOSONAR one argument per independent caller-visible decision; a parameter object would only rename them
        boolean restartIfRunning, ExternalInfobaseChangesPolicy policy,
        StandaloneServerPortConflictPolicy portPolicy, LaunchOverrides overrides,
        LaunchOverrides.Prepared prepared, String mode)
    {
        try
        {
            ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            NamedConfigurationResolution named =
                resolveNamedConfiguration(launchManager, configName);
            if (named.error() != null)
            {
                return named.error();
            }
            ILaunchConfiguration config = named.config();
            if (config == null)
            {
                ToolResult err = ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                    + "'. Create it in EDT first."); //$NON-NLS-1$
                err.put(KEY_MODE, mode);
                err.put("availableConfigurations", listAvailableConfigs(launchManager)); //$NON-NLS-1$
                return err.toJson();
            }

            String typeId = LaunchConfigUtils.getConfigTypeId(config);
            boolean isAttach = LaunchConfigUtils.isAttachConfigTypeId(typeId);
            String configProject = LaunchConfigUtils.readAttribute(config,
                LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String effectiveAppId = LaunchConfigUtils.getApplicationIdFor(config);

            if (isAttach && MODE_RUN.equals(mode))
            {
                return ToolResult.error("mode 'run' is not supported for Attach launch " //$NON-NLS-1$
                    + "configurations. Use mode 'debug'.").toJson(); //$NON-NLS-1$
            }

            // Asked here, the moment isAttach is known, and NOT where the overrides are stamped:
            // the existing-session block below can terminate a live client when
            // restartIfRunning=true, and a request that is going to be refused anyway must not
            // cost somebody their session on the way to the refusal.
            String attachRefusal = prepared.attachRefusalOrNull(config, isAttach);
            if (attachRefusal != null)
            {
                return attachRefusal;
            }

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
            // or a config with no readable ATTR_APPLICATION_ID (we mint a synthetic
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
                runUpdatePreflight(isAttach, updateBeforeLaunch, configProject, effectiveAppId, policy);
            if (preflightError != null)
            {
                return preflightError;
            }

            // An Attach configuration performs no DB update at all (the preflight above is
            // skipped for it), so the conflict matcher must stay unarmed: an "Infobase
            // configuration changes" dialog appearing during an Attach is somebody else's.
            // An Attach neither updates the DB nor STARTS a server, so it must leave BOTH modals
            // to their owners: a port policy forwarded here would arm the matcher for the whole
            // Attach window and could cancel — or, with reassign, re-address — a server some other
            // launch or a human is starting (review of #435).
            // Last step before the launch, so every guard above still reads the SAVED
            // configuration: the overrides change what the client is told to run, never the
            // project / application identity those guards key on.
            LaunchOverrides.Applied applied = prepared.applyTo(config, isAttach);
            if (applied.errorJson != null)
            {
                return applied.errorJson;
            }

            String launchError = performLaunch(applied.config, updateBeforeLaunch,
                isAttach ? null : policy, isAttach ? null : portPolicy,
                eclipseLaunchMode(mode));
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch " + mode + " session: " //$NON-NLS-1$ //$NON-NLS-2$
                    + launchError).toJson();
            }

            return buildLaunchSuccess(config, typeId, isAttach, configProject, effectiveAppId,
                overrides, prepared, mode);
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during launch by name", e); //$NON-NLS-1$
            return ToolResult.error(
                "Unexpected error: " + PlatformFailures.describe(e)).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves a by-name launch target without changing the supported launch domain.
     *
     * <p>The existing runtime-client/Attach lookup runs first and is returned unchanged. Only when
     * it finds nothing do we inspect the standalone-server type, solely to replace the false
     * "not found; create it" advice with the real capability boundary and measured workaround.
     * The standalone type is intentionally not added to
     * {@link LaunchConfigUtils#ALL_DEBUG_CONFIG_TYPE_IDS}, because the other callers of the shared
     * lookup do not thereby gain standalone-server support.
     *
     * @param launchManager Eclipse launch manager
     * @param configName exact configuration name
     * @return the supported configuration, an honest standalone refusal, or neither when absent
     */
    static NamedConfigurationResolution resolveNamedConfiguration(ILaunchManager launchManager,
            String configName)
    {
        ILaunchConfiguration config =
            LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
        if (config != null)
        {
            return NamedConfigurationResolution.config(config);
        }
        ILaunchConfiguration standalone = LaunchConfigUtils.findLaunchConfigByTypeAndName(
            launchManager, LaunchConfigUtils.STANDALONE_SERVER_LAUNCH_CONFIG_TYPE_ID, configName);
        if (standalone == null)
        {
            return NamedConfigurationResolution.notFound();
        }
        String typeId = LaunchConfigUtils.getConfigTypeId(standalone);
        return NamedConfigurationResolution.error(ToolResult.error("Launch configuration '" //$NON-NLS-1$
            + standalone.getName() + "' has type '" + typeId + "'. " + NAME //$NON-NLS-1$ //$NON-NLS-2$
            + " starts runtime " //$NON-NLS-1$
            + "CLIENT configurations; it does not start standalone-server configurations " //$NON-NLS-1$
            + "directly. Try " + NAME //$NON-NLS-1$
            + " with the project's thin-client configuration instead: " //$NON-NLS-1$
            + "launching that client has been observed to bring its standalone server up with " //$NON-NLS-1$
            + "it. " //$NON-NLS-1$
            + TerminateLaunchTool.NAME
            + " does accept this same standalone-server configuration when it " //$NON-NLS-1$
            + "is running.").toJson()); //$NON-NLS-1$
    }

    /** Result of the supported-plus-diagnostic by-name lookup. */
    private static final class NamedConfigurationResolution
    {
        private final ILaunchConfiguration config;
        private final String error;

        private NamedConfigurationResolution(ILaunchConfiguration config, String error)
        {
            this.config = config;
            this.error = error;
        }

        static NamedConfigurationResolution config(ILaunchConfiguration config)
        {
            return new NamedConfigurationResolution(config, null);
        }

        static NamedConfigurationResolution error(String error)
        {
            return new NamedConfigurationResolution(null, error);
        }

        static NamedConfigurationResolution notFound()
        {
            return new NamedConfigurationResolution(null, null);
        }

        ILaunchConfiguration config()
        {
            return config;
        }

        String error()
        {
            return error;
        }
    }

    /**
     * Runtime-client DB-update preflight for the by-name path. Returns a ready
     * error-JSON string when the project is not ready or the database update
     * fails, or {@code null} to proceed. Behaviour is identical to the inline
     * guard it replaces: same condition, same order, same exceptions propagate.
     */
    private String runUpdatePreflight(boolean isAttach, boolean updateBeforeLaunch, String configProject,
        String effectiveAppId, ExternalInfobaseChangesPolicy policy)
    {
        if (!isAttach && updateBeforeLaunch && configProject != null && !configProject.isEmpty())
        {
            String notReady = ProjectStateChecker.checkReadyOrError(configProject);
            if (notReady != null)
            {
                return ToolResult.error(notReady).toJson();
            }
            String updateError = updateDatabaseIfNeeded(configProject, effectiveAppId, policy);
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
    private String buildLaunchSuccess(ILaunchConfiguration config, String typeId, boolean isAttach, // NOSONAR one argument per independent caller-visible decision; a parameter object would only rename them
        String configProject, String effectiveAppId, LaunchOverrides overrides,
        LaunchOverrides.Prepared prepared, String mode)
    {
        ToolResult result = ToolResult.success()
            .put(KEY_LAUNCH_CONFIGURATION, config.getName())
            .put(KEY_CONFIGURATION_TYPE, typeId)
            .put(KEY_ATTACH, isAttach)
            .put(KEY_MODE, mode)
            .put(KEY_STATUS, "launching") //$NON-NLS-1$
            .put(McpKeys.MESSAGE, startingMessage(mode, isAttach));
        if (configProject != null && !configProject.isEmpty())
        {
            result.put(McpKeys.PROJECT, configProject);
        }
        if (effectiveAppId != null)
        {
            result.put(McpKeys.APPLICATION_ID, effectiveAppId);
        }
        return echoOverrides(result, overrides, prepared).toJson();
    }

    /** Returns a mode-appropriate asynchronous-launch status message. */
    private static String startingMessage(String mode, boolean isAttach)
    {
        if (isAttach)
        {
            return "Attach debug session is connecting — poll debug_status to confirm it is " //$NON-NLS-1$
                + "running, then wait_for_break to block until a breakpoint is hit."; //$NON-NLS-1$
        }
        if (MODE_RUN.equals(mode))
        {
            return "Run session is starting asynchronously. The 1C client may show startup " //$NON-NLS-1$
                + "dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
                + "Poll debug_status until the session appears running."; //$NON-NLS-1$
        }
        return "Debug session is starting asynchronously. The 1C client may show startup " //$NON-NLS-1$
            + "dialogs (login / database update); this call does NOT wait for it. " //$NON-NLS-1$
            + "Poll debug_status until the session appears running, then use wait_for_break."; //$NON-NLS-1$
    }

    /**
     * Reports the applied overrides back on a successful launch.
     *
     * <p>Echoed rather than left implicit because the failure mode being guarded against is a
     * launch that looks successful while the processor never ran: a response naming what it is
     * running lets the caller - and the e2e suite - tell the two apart without reading the EDT
     * log. Absent overrides add no keys, so an ordinary launch's payload is unchanged.</p>
     *
     * @param result the success payload being built
     * @param overrides what the caller asked for
     * @return the same payload, for chaining
     */
    private static ToolResult echoOverrides(ToolResult result, LaunchOverrides overrides,
        LaunchOverrides.Prepared prepared)
    {
        if (!LaunchOverrides.blank(overrides.startupOption()))
        {
            result.put(KEY_STARTUP_OPTION, overrides.startupOption());
        }
        // The RESOLVED name, so the echo names what is running - a qualified request resolves to
        // a bare name, and that bare name is what was stamped onto the launch.
        String resolved = prepared.resolvedExternalObjectName();
        if (!LaunchOverrides.blank(resolved))
        {
            result.put(KEY_EXTERNAL_OBJECT_PROJECT_NAME, overrides.externalObjectProjectName());
            result.put(KEY_EXTERNAL_OBJECT_NAME, resolved);
        }
        return result;
    }

    /**
     * Legacy path: launch a runtime-client config matched by project+application.
     */
    private String launch(String projectName, String applicationId, boolean updateBeforeLaunch, // NOSONAR one argument per independent caller-visible decision; a parameter object would only rename them
        boolean restartIfRunning, ExternalInfobaseChangesPolicy policy,
        StandaloneServerPortConflictPolicy portPolicy, LaunchOverrides overrides,
        LaunchOverrides.Prepared prepared, String mode)
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
            // shared LaunchLifecycleUtils.updateApplicationIfNeeded so launch analyses
            // "does the IB need updating?" the same way the YAXUnit tools
            // do: skip on UPDATED, wait on BEING_UPDATED, incremental-update otherwise.
            // For a STANDALONE-SERVER application the programmatic update is SKIPPED
            // and deferred to the launch delegate's coordinated path instead — see
            // runPreLaunchUpdateStep.
            if (appManager != null && application != null)
            {
                String updateError = runPreLaunchUpdateStep(project, applicationId,
                    appManager, updateBeforeLaunch, policy);
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
            Activator.logInfo("Launching " + mode + ": config=" + configName //$NON-NLS-1$ //$NON-NLS-2$
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

            // See the by-name path: applied last so the guards above read the saved config.
            // This path is runtime-client by construction, so isAttach is false.
            LaunchOverrides.Applied applied = prepared.applyTo(matchingConfig, false);
            if (applied.errorJson != null)
            {
                return applied.errorJson;
            }

            String launchError =
                performLaunch(applied.config, updateBeforeLaunch, policy, portPolicy,
                    eclipseLaunchMode(mode));
            if (launchError != null)
            {
                return ToolResult.error("Failed to launch " + mode + " session: " //$NON-NLS-1$ //$NON-NLS-2$
                    + launchError).toJson();
            }

            return echoOverrides(ToolResult.success()
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(KEY_LAUNCH_CONFIGURATION, configName)
                .put(KEY_CONFIGURATION_TYPE, LaunchConfigUtils.getConfigTypeId(matchingConfig))
                .put(KEY_ATTACH, false), overrides, prepared)
                .put(KEY_MODE, mode)
                .put(KEY_STATUS, "launching") //$NON-NLS-1$
                .put(McpKeys.MESSAGE, startingMessage(mode, false))
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during launch", e); //$NON-NLS-1$
            return ToolResult.error(
                "Unexpected error: " + PlatformFailures.describe(e)).toJson(); //$NON-NLS-1$
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
     * {@code DebugServerTargetSupport}) for sessions with no readable
     * {@code ATTR_APPLICATION_ID}. The two {@code :}-forms cannot be
     * resolved through {@link IApplicationManager} at all; the
     * {@code ServerApplication.} form mirrors the id REAL standalone-server
     * applications carry, and is skipped for the separate reason spelled out
     * below. Feeding one of the {@code :}-forms into
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
    private String updateDatabaseIfNeeded(String projectName, String applicationId,
        ExternalInfobaseChangesPolicy policy)
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
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager, false,
            policy).orElse(null);
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
        IApplicationManager appManager, boolean updateBeforeLaunch, ExternalInfobaseChangesPolicy policy)
    {
        if (!updateBeforeLaunch)
        {
            return null;
        }
        if (DebugServerTargetSupport.isServerApplicationId(applicationId))
        {
            Activator.logInfo("launch: server application: deferring DB update to the " //$NON-NLS-1$
                + "launch delegate's coordinated path (auto-confirmed): applicationId=" //$NON-NLS-1$
                + applicationId);
            return null;
        }
        return LaunchLifecycleUtils.updateApplicationIfNeeded(project, applicationId, appManager, false,
            policy).orElse(null);
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
            Activator.logInfo("launch short-circuit (alreadyRunning): applicationId=" //$NON-NLS-1$
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
            Activator.logInfo("launch restartIfRunning: terminating existing client debug " //$NON-NLS-1$
                + "target: applicationId=" + applicationId); //$NON-NLS-1$
            LaunchLifecycleUtils.terminateExistingSessionAndWait(session.liveTarget, applicationId);
        }
        else
        {
            Activator.logInfo("launch restartIfRunning: terminating existing client launch " //$NON-NLS-1$
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
            Activator.logInfo("launch: target-manager match has no live CLIENT-typed thread " //$NON-NLS-1$
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
     * The actionable message for a launch that was stopped by a modal this plugin auto-answered —
     * a standalone-server port conflict (the server never started) or an external-changes dialog
     * cancelled while the launch delegate performed the DB update — or {@code null} when neither
     * happened.
     *
     * <p>Also RECORDS it, so {@code debug_status} can report an outcome that happened long after
     * this Job's caller received its "launching" answer.
     *
     * @param config the launch configuration that was started
     * @param policy the policy the call ran with (may be {@code null})
     * @param conflicts the cancel window opened around the launch
     * @return the message, or {@code null}
     */
    private static String declinedConflictMessage(ILaunchConfiguration config,
        ExternalInfobaseChangesPolicy policy, LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts)
    {
        if (conflicts == null)
        {
            return null;
        }
        // A standalone-server launch STARTS its server first, so a busy port stops it before the
        // DB update is even reached. That modal is auto-cancelled (it would otherwise hang the
        // launch Job forever), and EDT then reports a bare cancellation — checked first because it
        // is the earlier, more specific cause: nothing about the caller's data was declined.
        if (conflicts.portConflicted())
        {
            String message =
                LaunchUpdateDialogAutoConfirmer.portConflictError(conflicts.portConflictDetail(),
                    conflicts.portConflictReason());
            recordAsyncFailure(config, message);
            Activator.logError(ERR_ASYNC_PREFIX + message, null);
            return message;
        }
        // Consulted ONLY when this launch armed the external-changes matcher: the window also
        // covers the port matcher (armed even with updateBeforeLaunch=false), and an
        // unattributed cancel from a concurrent operation is not this launch's declined update.
        if (policy == null || !conflicts.cancelled())
        {
            return null;
        }
        String message = ExternalInfobaseChangesPolicy.declinedUpdateError(policy, conflicts.reason());
        recordAsyncFailure(config, message);
        Activator.logError(ERR_ASYNC_PREFIX + message, null);
        return message;
    }

    /**
     * The port-conflict policy this launch may act on: the caller's choice when the launch targets a
     * STANDALONE-SERVER application, {@code null} (matcher unarmed) otherwise.
     *
     * <p>A file or client-server application cannot raise that modal, and an arm held for the whole
     * of such a launch would claim a dialog belonging to a concurrent — or manual — server start.
     *
     * <p>The test uses the DELEGATE-resolved id ({@code ATTR_APPLICATION_ID}, else the project's
     * default application), NOT the synthetic {@code launch:<name>} form: a runtime-client
     * configuration with no application binding is exactly the shape that reaches a standalone
     * server through the project default, and gating on the synthetic id would leave the matcher
     * unarmed in the very case it exists for.
     *
     * @param config the configuration being launched (may be {@code null})
     * @param requested the policy the caller passed (may be {@code null})
     * @return the policy to arm with, or {@code null} to leave the matcher unarmed
     */
    private static StandaloneServerPortConflictPolicy standaloneServerPortPolicy(
        ILaunchConfiguration config, StandaloneServerPortConflictPolicy requested)
    {
        if (requested == null || config == null)
        {
            return null;
        }
        try
        {
            String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String delegateAppId =
                LaunchLifecycleUtils.resolveDelegateApplicationId(config, projectName);
            return DebugServerTargetSupport.isServerApplicationId(delegateAppId)
                ? requested : null;
        }
        catch (CoreException e) // NOSONAR an unreadable config must not arm the writing answer
        {
            return null;
        }
    }

    /**
     * Stores a failure of the fire-and-forget launch so {@code debug_status} can report it, tagged
     * with the configuration and application it belongs to (a {@code debug_status} filtered by
     * application must not surface someone else's failure).
     *
     * @param config the launch configuration that was started (may be {@code null})
     * @param message the actionable message, never {@code null}
     */
    private static void recordAsyncFailure(ILaunchConfiguration config, String message)
    {
        String name = null;
        String applicationId = null;
        if (config != null)
        {
            name = config.getName();
            applicationId = LaunchConfigUtils.getApplicationIdFor(config);
        }
        AsyncLaunchOutcomes.record(name, applicationId, message);
    }

    /**
     * Resolves the infobase name EDT states in its "Infobase \"<name>\" configuration was
     * changed…" conflict modal for the application this launch configuration targets, so the
     * launch-time auto-confirmer window can be armed with an ATTRIBUTABLE name. Best-effort:
     * {@code null} when the config carries no resolvable project/application.
     *
     * @param config the launch configuration about to be started (may be {@code null})
     * @return the application display name, or {@code null}
     */
    private static String launchInfobaseName(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return null;
        }
        try
        {
            String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            // The DELEGATE id, the same one standaloneServerPortPolicy resolves: a runtime
            // configuration without a stored ATTR_APPLICATION_ID launches the default application
            // application, while getApplicationIdFor yields a synthetic "launch:<name>" that no
            // IApplicationManager knows - so attribution came back null and the arm, though
            // created, could never authorise the re-address the caller asked for.
            String applicationId = LaunchLifecycleUtils.resolveDelegateApplicationId(config,
                projectName);
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.isOpen())
            {
                return null;
            }
            return LaunchLifecycleUtils.attributionInfobaseName(
                Activator.getDefault().getApplicationManager(), ctx.project(), applicationId);
        }
        catch (Exception e) // NOSONAR a best-effort hint must never break the launch
        {
            return null;
        }
    }

    /**
     * The WST server name behind a launch configuration - what the port-conflict dialog quotes.
     * Best-effort: {@code null} simply refuses the writing answer.
     *
     * @param config the launch configuration
     * @return the server name, or {@code null}
     */
    private static String launchServerName(ILaunchConfiguration config)
    {
        try
        {
            String projectName = config.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            // The DELEGATE id, the same one standaloneServerPortPolicy resolves: a runtime
            // configuration without a stored ATTR_APPLICATION_ID launches the default application
            // application, while getApplicationIdFor yields a synthetic "launch:<name>" that no
            // IApplicationManager knows - so attribution came back null and the arm, though
            // created, could never authorise the re-address the caller asked for.
            String applicationId = LaunchLifecycleUtils.resolveDelegateApplicationId(config,
                projectName);
            ProjectContext ctx = ProjectContext.of(projectName);
            if (!ctx.isOpen())
            {
                return null;
            }
            return LaunchLifecycleUtils.attributionServerName(
                Activator.getDefault().getApplicationManager(), ctx.project(), applicationId);
        }
        catch (Exception e) // NOSONAR a best-effort hint must never break the launch
        {
            return null;
        }
    }

    /**
     * Launches the given configuration asynchronously.
     *
     * <p>Uses a direct {@code config.launch(launchMode, monitor)} — not
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
     *   <li>the code-1003 "debug session already exists" matcher is armed only
     *       for debug launches (independent of
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
    String performLaunch(ILaunchConfiguration config, boolean autoConfirmUpdateDialog,
        ExternalInfobaseChangesPolicy policy)
    {
        // Kept so the unit seam stays three-argument: the port-conflict answer then defaults
        // to CANCEL, the behaviour that predates the parameter.
        return performLaunch(config, autoConfirmUpdateDialog, policy,
            StandaloneServerPortConflictPolicy.DEFAULT);
    }

    /**
     * Same launch, additionally choosing how EDT's standalone-server port-conflict modal is
     * answered while the launch starts the server.
     *
     * @param portPolicy the port-conflict answer for this launch (may be {@code null} = default)
     */
    String performLaunch(ILaunchConfiguration config, boolean autoConfirmUpdateDialog,
        ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy)
    {
        return performLaunch(config, autoConfirmUpdateDialog, policy, portPolicy,
            ILaunchManager.DEBUG_MODE);
    }

    /**
     * Same launch, additionally selecting Eclipse's debug or run launch mode.
     *
     * @param launchMode {@link ILaunchManager#DEBUG_MODE} or {@link ILaunchManager#RUN_MODE}
     */
    String performLaunch(ILaunchConfiguration config, boolean autoConfirmUpdateDialog,
        ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        String launchMode)
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
                    return runLaunchJobBody(config, autoConfirmUpdateDialog, policy, portPolicy,
                        launchMode, monitor);
                }
            };
            job.setPriority(Job.INTERACTIVE);
            McpJobs.schedule(job);
            return null;
        }
        // No workbench (headless tests): launch synchronously and surface errors.
        // The infobase auth-dialog suppression is held across the synchronous connect for
        // the same #230 reason as the async Job body above (kept symmetric with the
        // arm/disarm pattern; a no-op headless where no dialog can appear).
        // The conflict matcher follows the same opt-out as the update matcher. It matters
        // most for a STANDALONE-SERVER application: there the pre-launch update is deferred to
        // EDT's launch delegate, so this window is the ONLY one covering that update.
        String launchInfobase = launchInfobaseName(config);
        ExternalInfobaseChangesPolicy launchPolicy = autoConfirmUpdateDialog ? policy : null;
        StandaloneServerPortConflictPolicy launchPortPolicy = standaloneServerPortPolicy(config,
            portPolicy);
        // Resolved ONCE and reused for the disarm: reading it again later could return a
        // different server if the configuration was rebound meanwhile, and the arm would then
        // never be released by the value it was taken with.
        String launchServer = launchServerName(config);
        boolean debugMode = ILaunchManager.DEBUG_MODE.equals(launchMode);
        LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, debugMode,
            autoConfirmUpdateDialog, launchPolicy, launchInfobase, launchPortPolicy, launchServer);
        InfobaseAuthDialogSuppressor.markActivityStart();
        try
        {
            StandaloneServerStateRecovery.launchWithRecovery(config, launchMode, null);
            return null;
        }
        catch (CoreException e)
        {
            Activator.logError("Error launching " + launchMode + " session", e); //$NON-NLS-1$ //$NON-NLS-2$
            return e.getMessage();
        }
        finally
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
            LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, debugMode,
                autoConfirmUpdateDialog, launchPolicy, launchInfobase, launchPortPolicy,
                launchServer);
        }
    }

    /**
     * The body of the background launch {@link Job} — the
     * seam {@link #performLaunch} schedules and the headless unit tests exercise
     * directly. Arms the {@link LaunchUpdateDialogAutoConfirmer} (update matcher
     * gated on {@code autoConfirmUpdateDialog}, code-1003 matcher debug-only —
     * the same flags the asyncExec dispatch used), runs the launch, and ALWAYS
     * disarms in {@code finally} — both calls are thread-safe from a Job thread.
     *
     * <p>Never throws: a Job that dies on an uncaught exception fails silently for
     * the MCP caller, so EVERY failure — {@link CoreException} or any other
     * {@link Throwable} — is logged to the EDT error log and returned as an error
     * {@link IStatus} (visible as the Job's result in the Progress view).
     *
     * @param config the launch configuration to start
     * @param autoConfirmUpdateDialog arm the "Application update" matcher
     * @param monitor the Job's progress monitor, passed through to
     *        {@code config.launch} so the Progress view shows the delegate's steps
     * @return {@link Status#OK_STATUS} on success, else an error status
     */
    static IStatus runLaunchJobBody(ILaunchConfiguration config, boolean autoConfirmUpdateDialog,
        ExternalInfobaseChangesPolicy policy, IProgressMonitor monitor)
    {
        // Kept so the unit seam (and any caller that does not care) stays four-argument: the
        // port-conflict answer then defaults to CANCEL, which is the behaviour that predates it.
        return runLaunchJobBody(config, autoConfirmUpdateDialog, policy,
            StandaloneServerPortConflictPolicy.DEFAULT, monitor);
    }

    /**
     * Same Job body, additionally choosing how EDT's standalone-server port-conflict modal is
     * answered while this launch starts the server.
     *
     * @param portPolicy the port-conflict answer for this launch (may be {@code null} = default)
     */
    static IStatus runLaunchJobBody(ILaunchConfiguration config, boolean autoConfirmUpdateDialog, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        IProgressMonitor monitor)
    {
        return runLaunchJobBody(config, autoConfirmUpdateDialog, policy, portPolicy,
            ILaunchManager.DEBUG_MODE, monitor);
    }

    /**
     * Same Job body, additionally selecting Eclipse's debug or run launch mode.
     *
     * @param launchMode {@link ILaunchManager#DEBUG_MODE} or {@link ILaunchManager#RUN_MODE}
     */
    static IStatus runLaunchJobBody(ILaunchConfiguration config, boolean autoConfirmUpdateDialog, // NOSONAR signature is inherent / public-or-test-contract; a parameter-object would not improve clarity
        ExternalInfobaseChangesPolicy policy, StandaloneServerPortConflictPolicy portPolicy,
        String launchMode, IProgressMonitor monitor)
    {
        // Auto-confirm EDT's blocking launch modals for the duration of this
        // single launch only. The "Application update" modal is pressed
        // only when the caller did NOT opt out of the DB update; the
        // code-1003 "debug session already exists" modal is auto-confirmed only
        // in debug mode (it is independent of the update opt-out). Manual EDT
        // launches outside this window still prompt.
        String launchInfobase = launchInfobaseName(config);
        // The window lasts as long as the launch - minutes for a standalone-server mode switch -
        // so it must never answer a dialog blind. That is handled where the arm is recorded: an arm
        // whose infobase name could not be resolved (a by-name config with no persisted application
        // id) is degraded to 'cancel', which still answers the modal but writes nothing.
        ExternalInfobaseChangesPolicy launchPolicy = autoConfirmUpdateDialog ? policy : null;
        StandaloneServerPortConflictPolicy launchPortPolicy = standaloneServerPortPolicy(config,
            portPolicy);
        // This Job is where a STANDALONE-SERVER application's DB update actually happens (it is
        // deferred to EDT's launch delegate), so an external-changes dialog can be cancelled here —
        // long after launch returned "launching". The window records that outcome so
        // debug_status can report it; without it the caller would see a successful dispatch and
        // then simply no session, with the reason only in the workspace log.
        // Only a launch that actually ARMED the conflict matcher opens a window. An Attach
        // performs no DB update and passes no policy; giving it a window would let an unattributed
        // cancel from a concurrent launch be recorded as an Attach failure.
        // Opened for every launch that can START a server, NOT only for one that also performs
        // a DB update (review of #435): with updateBeforeLaunch=false launchPolicy is null, yet the
        // port-conflict matcher is armed and can refuse the launch - without a window that refusal
        // reached nobody. "policy != null" is the non-Attach signal (the caller passes null for an
        // Attach, which attaches to a running server and never starts one).
        // Resolved ONCE, before anything uses it: the window, the arm and its release must all
        // carry the SAME name. Read twice, a rebound configuration (or one best-effort lookup
        // that momentarily fails) would address the window to one server and the arm to another,
        // and the arm would never be released by the value it was taken with.
        String launchServer = launchServerName(config);
        boolean debugMode = ILaunchManager.DEBUG_MODE.equals(launchMode);
        LaunchUpdateDialogAutoConfirmer.ConflictWatch conflicts = policy == null
            ? null
            : LaunchUpdateDialogAutoConfirmer.beginConflictWatch(launchInfobase, launchServer);
        LaunchUpdateDialogAutoConfirmer.arm(autoConfirmUpdateDialog, debugMode,
            autoConfirmUpdateDialog, launchPolicy, launchInfobase, launchPortPolicy, launchServer);
        // Keep the infobase auth-dialog suppression active for the WHOLE async launch
        // (#230). This launch is fire-and-forget: tool.execute() has already returned and
        // stamped lastActivityEndMillis, and with updateBeforeLaunch=false there is no
        // synchronous preflight connect — the FIRST (and only) infobase connect happens
        // right here in config.launch, which can run for minutes (e.g. the standalone-
        // server mode-switch restart). The in-flight counter — not the short trailing
        // grace window — must therefore cover it, so a "Configure Infobase access Settings"
        // dialog raised by this connect (missing/wrong stored creds) is still auto-cancelled
        // instead of hanging the unattended call (mirrors the arm/disarm pattern above).
        InfobaseAuthDialogSuppressor.markActivityStart();
        try
        {
            StandaloneServerStateRecovery.launchWithRecovery(config, launchMode, monitor);
            String declined = declinedConflictMessage(config, launchPolicy, conflicts);
            if (declined != null)
            {
                // The launch itself did not throw, but the update inside it wrote nothing.
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, declined);
            }
            return Status.OK_STATUS;
        }
        catch (CoreException e)
        {
            // Recorded, not just logged: this Job's caller was answered "launching" long ago, so the
            // log is the only place the reason would otherwise exist. A cancelled conflict is
            // preferred over the delegate's own message: it is the actual cause AND it names the
            // knob that would have let the launch through.
            String declined = declinedConflictMessage(config, launchPolicy, conflicts);
            if (declined != null)
            {
                Activator.logError(ERR_ASYNC_PREFIX + e.getMessage(), e);
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, declined, e);
            }
            recordAsyncFailure(config, ERR_ASYNC_PREFIX + e.getMessage());
            Activator.logError(ERR_ASYNC_PREFIX + e.getMessage(), e);
            return e.getStatus();
        }
        catch (Throwable t)
        {
            // Never let the Job die on an uncaught exception — it would vanish
            // without a trace for the MCP caller. Log + report an error status.
            String declined = declinedConflictMessage(config, launchPolicy, conflicts);
            if (declined != null)
            {
                Activator.logError(ERR_ASYNC_PREFIX + t.getMessage(), t);
                return new Status(IStatus.ERROR, Activator.PLUGIN_ID, declined, t);
            }
            recordAsyncFailure(config, ERR_ASYNC_PREFIX + t.getMessage());
            Activator.logError(ERR_ASYNC_PREFIX + t.getMessage(), t);
            return new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                ERR_ASYNC_PREFIX + t.getMessage(), t);
        }
        finally
        {
            InfobaseAuthDialogSuppressor.markActivityEnd();
            LaunchUpdateDialogAutoConfirmer.disarm(autoConfirmUpdateDialog, debugMode,
                autoConfirmUpdateDialog, launchPolicy, launchInfobase, launchPortPolicy,
                launchServer);
            if (conflicts != null)
            {
                conflicts.close();
            }
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
