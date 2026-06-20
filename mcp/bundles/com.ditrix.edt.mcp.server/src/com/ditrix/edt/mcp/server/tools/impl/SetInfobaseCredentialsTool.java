/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Stores the <em>infobase connection credentials</em> (user/password) EDT uses
 * to authenticate the designer agent for {@code update_database} and
 * {@code debug_launch} against an infobase that requires user authentication
 * (issue #194).
 *
 * <p>Without stored credentials the update agent is started without the infobase
 * user and fails to authenticate, popping a blocking "Configure Infobase access
 * Settings" dialog that hangs the unattended call. After this tool the headless
 * update authenticates as the given user.
 *
 * <p>These credentials select an <b>existing</b> infobase user — they do NOT
 * create users. Demo bases typically have a user with an empty password, so an
 * empty {@code password} is valid.
 */
public class SetInfobaseCredentialsTool implements IMcpTool
{
    public static final String NAME = "set_infobase_credentials"; //$NON-NLS-1$

    /** Output key: display name of the target application. */
    private static final String KEY_APPLICATION_NAME = "applicationName"; //$NON-NLS-1$
    /** Output key: stored user name. */
    private static final String KEY_USER = "user"; //$NON-NLS-1$
    /** Output key: stored access kind (INFOBASE / OS). */
    private static final String KEY_ACCESS = "access"; //$NON-NLS-1$
    /** Output key: whether a non-empty password was stored (the password itself is never returned). */
    private static final String KEY_PASSWORD_SET = "passwordSet"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Store infobase connection credentials (user/password) so update_database and " //$NON-NLS-1$
            + "debug_launch can authenticate the update agent on an infobase that has a user list " //$NON-NLS-1$
            + "(issue #194). Selects an EXISTING infobase user (does not create users); an empty " //$NON-NLS-1$
            + "password is valid (demo bases). Target by launchConfigurationName (preferred) or " //$NON-NLS-1$
            + "projectName + applicationId (from get_applications)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client config name from list_configurations (preferred target).") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name; required if launchConfigurationName is omitted.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications; required if launchConfigurationName is omitted.") //$NON-NLS-1$
            .stringProperty(KEY_USER,
                "Infobase user name to authenticate as (an EXISTING user). Optional: empty stores " //$NON-NLS-1$
                + "no-user credentials (OS-authenticated or userless base / reset).") //$NON-NLS-1$
            .stringProperty("password", //$NON-NLS-1$
                "Infobase user password. Optional; default empty (demo bases use an empty password).") //$NON-NLS-1$
            .enumProperty(KEY_ACCESS,
                "Authentication kind: 'INFOBASE' (default, 1C user auth) or 'OS' (OS authentication).", //$NON-NLS-1$
                "INFOBASE", "OS") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the credentials were stored", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Target application ID.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_NAME, "Display name of the target application.") //$NON-NLS-1$
            .stringProperty(KEY_USER, "Stored infobase user name.") //$NON-NLS-1$
            .stringProperty(KEY_ACCESS, "Stored access kind (INFOBASE or OS).") //$NON-NLS-1$
            .booleanProperty(KEY_PASSWORD_SET, "True when a non-empty password was stored.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String user = JsonUtils.extractStringArgument(params, KEY_USER);
        String password = JsonUtils.extractStringArgument(params, "password"); //$NON-NLS-1$
        String access = JsonUtils.extractStringArgument(params, KEY_ACCESS);

        boolean hasName = configName != null && !configName.isEmpty();
        if (!hasName)
        {
            if (projectName == null || projectName.isEmpty())
            {
                return ToolResult.error("projectName is required (or pass launchConfigurationName).").toJson(); //$NON-NLS-1$
            }
            if (applicationId == null || applicationId.isEmpty())
            {
                return ToolResult.error("applicationId is required (or pass launchConfigurationName). " //$NON-NLS-1$
                    + "Use get_applications or list_configurations.").toJson(); //$NON-NLS-1$
            }
        }

        // Resolve the project + applicationId from the launch configuration when a name was given.
        if (hasName)
        {
            String resolveError = null;
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
            String cfgProject = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String cfgAppId = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
            if (cfgProject.isEmpty() || cfgAppId.isEmpty())
            {
                resolveError = "Launch configuration '" + cfg.getName() //$NON-NLS-1$
                    + "' has no project or applicationId attribute — cannot derive the target."; //$NON-NLS-1$
            }
            if (resolveError != null)
            {
                return ToolResult.error(resolveError).toJson();
            }
            projectName = cfgProject;
            applicationId = cfgAppId;
        }

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return store(projectName, applicationId, user, password, access);
    }

    private String store(String projectName, String applicationId, String user, String password,
            String access)
    {
        ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
        if (!mr.ok())
        {
            return mr.errorJson();
        }
        IProject project = mr.project();
        IApplicationManager appManager = mr.manager();

        Optional<IApplication> appOpt;
        try
        {
            appOpt = appManager.getApplication(project, applicationId);
        }
        catch (Exception e) // NOSONAR EDT application lookup — surface as an actionable error
        {
            return ToolResult.error("Error resolving application '" + applicationId + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage()).toJson();
        }
        if (!appOpt.isPresent())
        {
            return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                + ". Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
        }
        IApplication application = appOpt.get();

        InfobaseAccess accessKind = InfobaseAccessSupport.parseAccess(access);
        String error = InfobaseAccessSupport.storeCredentials(application, user, password, accessKind);
        if (error != null)
        {
            return ToolResult.error(error).toJson();
        }

        boolean passwordSet = password != null && !password.isEmpty();
        String storedUser = user == null ? "" : user; //$NON-NLS-1$
        return ToolResult.success()
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_APPLICATION_NAME, application.getName())
            .put(KEY_USER, storedUser)
            .put(KEY_ACCESS, accessKind.getName())
            .put(KEY_PASSWORD_SET, passwordSet)
            .put(McpKeys.MESSAGE, "Stored infobase access credentials for application '" //$NON-NLS-1$
                + application.getName() + "' (user '" + storedUser + "', access " //$NON-NLS-1$ //$NON-NLS-2$
                + accessKind.getName() + "). update_database / debug_launch will now authenticate " //$NON-NLS-1$
                + "with these credentials.") //$NON-NLS-1$
            .toJson();
    }
}
