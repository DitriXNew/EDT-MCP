/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ExtensionOriginUtils;
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

    /** Output key: array of applications (infobases) for the project. */
    private static final String KEY_APPLICATIONS = "applications"; //$NON-NLS-1$

    /** Output key: number of applications found. */
    private static final String KEY_COUNT = "count"; //$NON-NLS-1$

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
            .stringProperty(McpKeys.PROJECT_NAME, "EDT project name (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.PROJECT, "EDT project name the applications belong to") //$NON-NLS-1$
            .objectArrayProperty(KEY_APPLICATIONS, "Applications with id, name, type and update state") //$NON-NLS-1$
            .integerProperty(KEY_COUNT, "Number of applications found") //$NON-NLS-1$
            .stringProperty("message", "Informational message when no applications are found") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("defaultApplicationId", "Id of the project's default application") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("inheritedFromProject", //$NON-NLS-1$
                "Base/parent project the applications are inherited from (present only for " //$NON-NLS-1$
                    + "external-objects/extension projects whose applications come from their base project).") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);

        // Validate project name
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }
        
        // Refuse only the transient BUILDING state; a missing/closed project
        // falls through to the value-naming 'Project not found' below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
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
            ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
            if (!mr.ok())
            {
                return mr.errorJson();
            }
            IProject project = mr.project();
            IApplicationManager appManager = mr.manager();

            // Get applications for the named project, falling back to the base
            // configuration project for dependent projects with an empty list.
            ResolvedApplications resolved = resolveApplications(appManager, project);
            List<IApplication> applications = resolved.applications;

            if (applications == null || applications.isEmpty())
            {
                return ToolResult.success()
                    .put(McpKeys.PROJECT, projectName)
                    .put(KEY_APPLICATIONS, new JsonArray())
                    .put(KEY_COUNT, 0)
                    .put("message", "No applications found for project") //$NON-NLS-1$ //$NON-NLS-2$
                    .toJson();
            }

            // Build applications array
            JsonArray appsArray = buildApplicationsArray(appManager, applications);

            // Get default application from whichever project supplied the applications
            String defaultAppId = resolveDefaultApplicationId(appManager, resolved.applicationsProject);

            ToolResult result = ToolResult.success()
                .put(McpKeys.PROJECT, projectName)
                .put(KEY_APPLICATIONS, appsArray)
                .put(KEY_COUNT, applications.size());

            if (defaultAppId != null)
            {
                result.put("defaultApplicationId", defaultAppId); //$NON-NLS-1$
            }

            // Present only on the inherited branch (dependent project whose applications
            // come from its base configuration project).
            if (resolved.baseProjectName != null)
            {
                result.put("inheritedFromProject", resolved.baseProjectName); //$NON-NLS-1$
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
     * Resolves the applications for the named project. For a configuration project this is always
     * the named project's own list. For a dependent project (external-objects / extension) whose
     * own application list is empty, falls back to the BASE configuration project the applications
     * are inherited from.
     *
     * @param appManager the application manager
     * @param project the named project
     * @return the resolved applications together with the supplying project and (for the inherited
     *         branch only) the base project name
     * @throws ApplicationException if the application list cannot be read
     */
    private ResolvedApplications resolveApplications(IApplicationManager appManager, IProject project)
        throws ApplicationException
    {
        List<IApplication> applications = appManager.getApplications(project);
        if (applications != null && !applications.isEmpty())
        {
            return new ResolvedApplications(applications, project, null);
        }

        IProject base = ExtensionOriginUtils.resolveBaseProject(project);
        if (base != null && base.exists() && base.isOpen())
        {
            List<IApplication> baseApplications = appManager.getApplications(base);
            if (baseApplications != null && !baseApplications.isEmpty())
            {
                return new ResolvedApplications(baseApplications, base, base.getName());
            }
        }

        return new ResolvedApplications(applications, project, null);
    }

    /**
     * Builds the JSON array describing the given applications, preserving the original
     * per-application property order (id, name, type, update state, required version).
     *
     * @param appManager the application manager
     * @param applications the applications to render
     * @return the JSON array of applications
     */
    private JsonArray buildApplicationsArray(IApplicationManager appManager, List<IApplication> applications)
    {
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
            addUpdateState(appManager, app, appObj);

            // Add required version if present
            app.getRequiredVersion().ifPresent(version ->
                appObj.addProperty("requiredVersion", version)); //$NON-NLS-1$

            appsArray.add(appObj);
        }
        return appsArray;
    }

    /**
     * Holds the applications resolved for a project together with the project that supplied them
     * and, only when inherited from a base configuration project, that base project's name.
     */
    private static final class ResolvedApplications
    {
        final List<IApplication> applications;
        final IProject applicationsProject;
        final String baseProjectName;

        ResolvedApplications(List<IApplication> applications, IProject applicationsProject, String baseProjectName)
        {
            this.applications = applications;
            this.applicationsProject = applicationsProject;
            this.baseProjectName = baseProjectName;
        }
    }

    /**
     * Adds the update-state properties for a single application to its JSON object.
     * On error the state is reported as {@code "ERROR"} together with the error message,
     * preserving the original inline behaviour.
     *
     * @param appManager the application manager
     * @param app the application whose update state is read
     * @param appObj the JSON object to populate
     */
    private void addUpdateState(IApplicationManager appManager, IApplication app, JsonObject appObj)
    {
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
    }

    /**
     * Resolves the id of the project's default application, or {@code null} when there is no
     * default application or it could not be determined.
     *
     * @param appManager the application manager
     * @param project the project
     * @return the default application id, or {@code null}
     */
    private String resolveDefaultApplicationId(IApplicationManager appManager, IProject project)
    {
        try
        {
            IApplication defaultApp = appManager.getDefaultApplication(project).orElse(null);
            if (defaultApp != null)
            {
                return defaultApp.getId();
            }
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error getting default application", e); //$NON-NLS-1$
        }
        return null;
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
