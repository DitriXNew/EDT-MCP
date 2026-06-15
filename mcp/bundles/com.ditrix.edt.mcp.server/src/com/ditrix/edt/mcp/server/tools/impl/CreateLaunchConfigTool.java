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
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.e1c.g5.dt.applications.ApplicationException;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Creates a 1C:EDT runtime-client launch configuration (thin / thick / web client).
 *
 * <p>The same configuration is usable for BOTH run and debug: run vs debug is the launch
 * <em>mode string</em> passed to {@code debug_launch} / {@code run_yaxunit_tests}, never a
 * different config type or attribute. There is therefore no separate "debug configuration" type —
 * this one tool covers all launch / debug use-cases for runtime clients. (Attach-to-server configs
 * such as RemoteRuntime / LocalRuntime are a separate feature and are out of scope v1.)
 *
 * <p>The created config carries a real {@code ATTR_APPLICATION_ID} (resolved from
 * {@link IApplicationManager} if the caller omits it) so it round-trips through
 * {@link ListConfigurationsTool} and is addressable by both name and
 * {@code projectName + applicationId} in {@code debug_launch} / {@code run_yaxunit_tests}.
 *
 * <p>Mirrors the recipe used by EDT's own
 * {@code AbstractRuntimeClientLaunchShortcut.createLaunchConfiguration} (decompiled from
 * EDT 2025.2.6 bytecode).
 */
public class CreateLaunchConfigTool implements IMcpTool
{
    public static final String NAME = "create_launch_config"; //$NON-NLS-1$

    /** Param/output key: client kind selector (thin/thick/web). */
    private static final String KEY_CLIENT_TYPE = "clientType"; //$NON-NLS-1$

    /** clientType enum value: thick client. */
    private static final String KEY_THICK = "thick"; //$NON-NLS-1$

    // -------------------------------------------------------------------------
    // New constants (string literals from R-B javap -constants table, verified)
    // Added to this tool; also available via LaunchConfigUtils for future callers.
    // -------------------------------------------------------------------------

    /** Launch attribute: client type id string (one of the IRuntimeComponentTypes ids). */
    static final String ATTR_CLIENT_TYPE = "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_TYPE"; //$NON-NLS-1$

    /** Launch attribute: whether EDT auto-selects the client type. Set false to pin a kind. */
    static final String ATTR_CLIENT_AUTO_SELECT =
        "com._1c.g5.v8.dt.launching.core.ATTR_CLIENT_AUTO_SELECT"; //$NON-NLS-1$

    /** Eclipse standard attribute: which IProcessFactory to use for the spawned process. */
    static final String ATTR_PROCESS_FACTORY_ID = "process_factory_id"; //$NON-NLS-1$

    /** Value for ATTR_PROCESS_FACTORY_ID: marks the spawned process as an EDT runtime process. */
    static final String VALUE_PROCESS_FACTORY = "com._1c.g5.v8.dt.debug.core.RuntimeProcessFactory"; //$NON-NLS-1$

    /** ATTR_CLIENT_TYPE value for a thin client (verified from javap bytecode ldc). */
    static final String COMPONENT_TYPE_THIN =
        "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThinClient"; //$NON-NLS-1$

    /** ATTR_CLIENT_TYPE value for a thick client (verified from javap bytecode ldc). */
    static final String COMPONENT_TYPE_THICK =
        "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"; //$NON-NLS-1$

    /** ATTR_CLIENT_TYPE value for a web client (verified from javap bytecode ldc). */
    static final String COMPONENT_TYPE_WEB =
        "com._1c.g5.v8.dt.platform.services.core.componentTypes.WebClient"; //$NON-NLS-1$

    // Wizard-parity default attributes (from AbstractRuntimeClientLaunchShortcut.setDefaults bytecode).
    private static final String ATTR_RUNTIME_INSTALL_AUTO =
        "com._1c.g5.v8.dt.debug.core.ATTR_RUNTIME_INSTALLATION_USE_AUTO"; //$NON-NLS-1$
    private static final String ATTR_USE_LOCAL_DEBUG_SERVER =
        "com._1c.g5.v8.dt.debug.core.ATTR_USE_LOCAL_DEBUG_SERVER"; //$NON-NLS-1$
    private static final String ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS"; //$NON-NLS-1$
    private static final String ATTR_CALL_DELAY =
        "com._1c.g5.v8.dt.launching.core.ATTR_CALL_DELAY"; //$NON-NLS-1$
    private static final String ATTR_DATA_SENDING_DELAY =
        "com._1c.g5.v8.dt.launching.core.ATTR_DATA_SENDING_DELAY"; //$NON-NLS-1$
    private static final String ATTR_DATA_RECEIVING_DELAY =
        "com._1c.g5.v8.dt.launching.core.ATTR_DATA_RECEIVING_DELAY"; //$NON-NLS-1$
    private static final String ATTR_DO_NOT_DISPLAY_WARNINGS =
        "com._1c.g5.v8.dt.launching.core.ATTR_DO_NOT_DISPLAY_WARNINGS"; //$NON-NLS-1$
    private static final String ATTR_SHOW_PERFORMANCE =
        "com._1c.g5.v8.dt.launching.core.ATTR_SHOW_PERFORMANCE"; //$NON-NLS-1$
    private static final String ATTR_SHOW_ALL_FUNCTIONS =
        "com._1c.g5.v8.dt.launching.core.ATTR_SHOW_ALL_FUNCTIONS"; //$NON-NLS-1$

    // Nature id for V8 configuration projects (same constant used in GenerateTranslationStringsTool).
    private static final String V8_CONFIGURATION_NATURE = "com._1c.g5.v8.dt.core.V8ConfigurationNature"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Create a 1C:EDT runtime-client launch configuration (thin/thick/web). " //$NON-NLS-1$
            + "The SAME config works for both run and debug (mode is chosen at launch time " //$NON-NLS-1$
            + "by debug_launch/run_yaxunit_tests — there is no separate debug-config type). " //$NON-NLS-1$
            + "Use delete_launch_config to remove it. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('create_launch_config')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "V8 configuration project name (required). Must be a V8ConfigurationNature project. " //$NON-NLS-1$
                + "Use list_projects to discover available projects.", //$NON-NLS-1$
                true)
            .enumProperty(KEY_CLIENT_TYPE,
                "Client kind: thin (default), thick, or web.", //$NON-NLS-1$
                "thin", KEY_THICK, "web") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", //$NON-NLS-1$
                "Config name; default '<Project> Thin|Thick|Web Client', uniquified. " //$NON-NLS-1$
                + "If a config with this name already exists the call is rejected.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application (infobase) ID from get_applications. " //$NON-NLS-1$
                + "If omitted the project's default application is used. " //$NON-NLS-1$
                + "If the project has no applications, the call is rejected with a hint.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Always 'created' on success.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("name", "Exact name of the created launch configuration.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("project", "Project the configuration targets.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_CLIENT_TYPE, "Client kind: thin, thick, or web.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID stored in the config (real IApplicationManager id).") //$NON-NLS-1$
            .stringProperty("type", "Launch configuration type id.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message.") //$NON-NLS-1$ //$NON-NLS-2$
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
        // ── 1. Required argument ───────────────────────────────────────────────
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String clientTypeParam = JsonUtils.extractStringArgument(params, KEY_CLIENT_TYPE);
        String nameParam = JsonUtils.extractStringArgument(params, "name"); //$NON-NLS-1$
        String applicationIdParam = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);

        // ── 2. Resolve client type ─────────────────────────────────────────────
        String clientType = (clientTypeParam == null || clientTypeParam.isEmpty()) ? "thin" : clientTypeParam.toLowerCase(java.util.Locale.ROOT); //$NON-NLS-1$
        String componentTypeId = componentTypeIdFor(clientType);
        if (componentTypeId == null)
        {
            return ToolResult.error("Invalid clientType: '" + clientType //$NON-NLS-1$
                + "'. Must be one of: thin, thick, web.").toJson(); //$NON-NLS-1$
        }
        String clientLabel = clientLabelFor(clientType);

        // ── 3. Resolve project ─────────────────────────────────────────────────
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson();
        }
        if (!ctx.isOpen())
        {
            return ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project first.").toJson(); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        // ── 4. Reject non-configuration projects ───────────────────────────────
        try
        {
            if (!project.hasNature(V8_CONFIGURATION_NATURE))
            {
                return ToolResult.error("Not a V8 configuration project: '" + projectName //$NON-NLS-1$
                    + "'. A runtime-client launch configuration requires a V8ConfigurationNature " //$NON-NLS-1$
                    + "project (e.g. use list_projects and look for type='configuration').").toJson(); //$NON-NLS-1$
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error checking project nature for: " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("Cannot check project nature for '" + projectName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // ── 5. Resolve application id ──────────────────────────────────────────
        String effectiveApplicationId;
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("IApplicationManager service is not available. " //$NON-NLS-1$
                + "EDT may still be starting up — retry in a moment.").toJson(); //$NON-NLS-1$
        }

        if (applicationIdParam != null && !applicationIdParam.isEmpty())
        {
            // Caller supplied an explicit id — validate it exists for this project.
            // Distinguish "not found" (empty Optional) from a transient service failure
            // (exception while EDT is mid-index) so the diagnosis is not misleading.
            try
            {
                Optional<IApplication> appOpt = appManager.getApplication(project, applicationIdParam);
                if (!appOpt.isPresent())
                {
                    return buildAppNotFoundError(projectName, applicationIdParam);
                }
                effectiveApplicationId = applicationIdParam;
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error resolving application for: " + projectName, e); //$NON-NLS-1$
                return ToolResult.error("Could not resolve application '" + applicationIdParam //$NON-NLS-1$
                    + "' for project '" + projectName + "': " + e.getMessage() //$NON-NLS-1$ //$NON-NLS-2$
                    + ". The project may still be indexing — retry in a moment.").toJson(); //$NON-NLS-1$
            }
        }
        else
        {
            // Resolve the project's default application.
            try
            {
                Optional<IApplication> defaultApp = appManager.getDefaultApplication(project);
                if (!defaultApp.isPresent())
                {
                    return ToolResult.error("Project '" + projectName //$NON-NLS-1$
                        + "' has no applications (infobases). Create an infobase first " //$NON-NLS-1$
                        + "(Window -> Preferences -> EDT -> Applications or the 1C Administrator " //$NON-NLS-1$
                        + "console), then retry. Use get_applications to check available applications.").toJson(); //$NON-NLS-1$
                }
                effectiveApplicationId = defaultApp.get().getId();
            }
            catch (ApplicationException e)
            {
                Activator.logError("Error resolving default application for: " + projectName, e); //$NON-NLS-1$
                return ToolResult.error("Cannot resolve default application for '" + projectName //$NON-NLS-1$
                    + "': " + e.getMessage() + ". Use get_applications to list available applications.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        // ── 6. Acquire launch manager and type ────────────────────────────────
        ILaunchManager lm = LaunchConfigUtils.getLaunchManager();
        if (lm == null)
        {
            return ToolResult.error("Eclipse launch manager is not available. " //$NON-NLS-1$
                + "The debug plugin may not have started yet — retry in a moment.").toJson(); //$NON-NLS-1$
        }
        ILaunchConfigurationType type = lm.getLaunchConfigurationType(LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID);
        if (type == null)
        {
            return ToolResult.error("Launch configuration type '" //$NON-NLS-1$
                + LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID + "' is not registered. " //$NON-NLS-1$
                + "Verify that the EDT launching.core plugin is installed.").toJson(); //$NON-NLS-1$
        }

        // ── 7. Resolve effective name ──────────────────────────────────────────
        String effectiveName;
        if (nameParam != null && !nameParam.isEmpty())
        {
            // Caller provided an explicit name — reject duplicates.
            ILaunchConfiguration existing = LaunchConfigUtils.findLaunchConfigByName(lm, nameParam);
            if (existing != null)
            {
                return ToolResult.error("A launch configuration named '" + nameParam //$NON-NLS-1$
                    + "' already exists. Use list_configurations to see existing configs, " //$NON-NLS-1$
                    + "or omit 'name' to auto-generate a unique name.").toJson(); //$NON-NLS-1$
            }
            effectiveName = nameParam;
        }
        else
        {
            // Generate a unique name mirroring the EDT shortcut convention.
            String baseName = projectName + " " + clientLabel; //$NON-NLS-1$
            effectiveName = lm.generateLaunchConfigurationName(baseName);
        }

        // ── 8. Create and save the working copy ───────────────────────────────
        try
        {
            Activator.logInfo("create_launch_config: name=" + effectiveName //$NON-NLS-1$
                + ", project=" + projectName //$NON-NLS-1$
                + ", clientType=" + clientType //$NON-NLS-1$
                + ", applicationId=" + effectiveApplicationId); //$NON-NLS-1$

            // null container -> workspace-local config persisted in workspace .metadata.
            ILaunchConfigurationWorkingCopy wc = type.newInstance(null, effectiveName);

            // Identity / binding (required by RuntimeClientLaunchDelegate.getProject + ATTR_APPLICATION_ID
            // must be real so getApplicationIdFor takes branch A, not the synthetic 'launch:' fallback).
            wc.setAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, projectName);
            wc.setAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, effectiveApplicationId);

            // Process factory (marks the process as an EDT runtime process in the Debug view).
            wc.setAttribute(ATTR_PROCESS_FACTORY_ID, VALUE_PROCESS_FACTORY);

            // Client kind (pin the specific kind exactly as the shortcut does).
            wc.setAttribute(ATTR_CLIENT_AUTO_SELECT, false);
            wc.setAttribute(ATTR_CLIENT_TYPE, componentTypeId);

            // Runtime + debug-server defaults (from AbstractLaunchShortcut.setDefaults bytecode).
            wc.setAttribute(ATTR_RUNTIME_INSTALL_AUTO, true);
            wc.setAttribute(ATTR_USE_LOCAL_DEBUG_SERVER, "AUTO"); //$NON-NLS-1$

            // UX defaults from AbstractRuntimeClientLaunchShortcut.setDefaults (wizard parity).
            wc.setAttribute(ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, true);
            wc.setAttribute(ATTR_CALL_DELAY, 145);
            wc.setAttribute(ATTR_DATA_SENDING_DELAY, 45);
            wc.setAttribute(ATTR_DATA_RECEIVING_DELAY, 15);
            wc.setAttribute(ATTR_DO_NOT_DISPLAY_WARNINGS, true);
            wc.setAttribute(ATTR_SHOW_PERFORMANCE, true);
            wc.setAttribute(ATTR_SHOW_ALL_FUNCTIONS, true);

            // Link the config to the project resource (cosmetic; shown in Eclipse Run Configurations UI).
            wc.setMappedResources(new org.eclipse.core.resources.IResource[]{ project });

            ILaunchConfiguration saved = wc.doSave();

            return ToolResult.success()
                .put("action", "created") //$NON-NLS-1$ //$NON-NLS-2$
                .put("name", saved.getName()) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put(KEY_CLIENT_TYPE, clientType)
                .put(McpKeys.APPLICATION_ID, effectiveApplicationId)
                .put("type", LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID) //$NON-NLS-1$
                .put("message", "Created '" + saved.getName() + "' (" + clientLabel //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + ") for project '" + projectName + "'. Use debug_launch or " //$NON-NLS-1$ //$NON-NLS-2$
                    + "run_yaxunit_tests with launchConfigurationName='" + saved.getName() //$NON-NLS-1$
                    + "' to launch it.") //$NON-NLS-1$
                .toJson();
        }
        catch (CoreException e)
        {
            Activator.logError("Error creating launch config: " + effectiveName, e); //$NON-NLS-1$
            return ToolResult.error("Failed to create launch configuration '" + effectiveName //$NON-NLS-1$
                + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Maps the user-facing client type token to the component-type id stored in
     * {@code ATTR_CLIENT_TYPE}. Returns {@code null} for an unrecognised token.
     */
    private static String componentTypeIdFor(String clientType)
    {
        switch (clientType)
        {
            case "thin": //$NON-NLS-1$
                return COMPONENT_TYPE_THIN;
            case KEY_THICK:
                return COMPONENT_TYPE_THICK;
            case "web": //$NON-NLS-1$
                return COMPONENT_TYPE_WEB;
            default:
                return null;
        }
    }

    /** Human-readable label for the name suffix and the created-config message. */
    private static String clientLabelFor(String clientType)
    {
        switch (clientType)
        {
            case "thin": //$NON-NLS-1$
                return "Thin Client"; //$NON-NLS-1$
            case KEY_THICK:
                return "Thick Client"; //$NON-NLS-1$
            case "web": //$NON-NLS-1$
                return "Web Client"; //$NON-NLS-1$
            default:
                return "Client"; //$NON-NLS-1$
        }
    }

    /** Formats the "application not found" error with an actionable message. */
    private static String buildAppNotFoundError(String projectName, String applicationId)
    {
        return ToolResult.error("Application '" + applicationId //$NON-NLS-1$
            + "' was not found for project '" + projectName //$NON-NLS-1$
            + "'. Use get_applications(projectName='" + projectName //$NON-NLS-1$
            + "') to see the valid application IDs for this project.").toJson(); //$NON-NLS-1$
    }
}
