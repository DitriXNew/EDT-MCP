/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;
import com.e1c.g5.dt.applications.infobases.IInfobaseApplication;

/**
 * Removes a FILE infobase association from a configuration project and, optionally,
 * deletes the infobase from the global EDT infobases list.
 *
 * <p>Destructive: guarded by a confirm-preview (mirroring {@link DeleteProjectTool}).
 * A bare call (confirm omitted / false) reports what would be removed WITHOUT changing
 * anything; only {@code confirm=true} performs the removal.
 *
 * <p>This is the inverse of {@link CreateInfobaseTool} and is the cleanup step for
 * the create-infobase e2e round-trip.
 */
public class DeleteInfobaseTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "delete_infobase"; //$NON-NLS-1$

    /** Infobase application type ID. */
    private static final String INFOBASE_APP_TYPE = "com.e1c.g5.dt.applications.type.infobase"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove a FILE infobase association from a configuration project (and optionally " //$NON-NLS-1$
            + "deregister it from the global EDT infobases list). Destructive: guarded by a " //$NON-NLS-1$
            + "confirm-preview - call without confirm to preview what would be removed (no change), " //$NON-NLS-1$
            + "then confirm=true to delete. The inverse of create_infobase. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('delete_infobase')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("projectName", //$NON-NLS-1$
                "EDT configuration project the infobase is bound to (required).", true) //$NON-NLS-1$
            .stringProperty("applicationId", //$NON-NLS-1$
                "Application ID from get_applications. Either applicationId or infobaseName " //$NON-NLS-1$
                + "is required.") //$NON-NLS-1$
            .stringProperty("infobaseName", //$NON-NLS-1$
                "Display name of the infobase to remove. Either applicationId or infobaseName " //$NON-NLS-1$
                + "is required.") //$NON-NLS-1$
            .booleanProperty("deleteRegistration", //$NON-NLS-1$
                "true = also deregister the infobase from the global EDT infobases list " //$NON-NLS-1$
                + "(equivalent to 'Delete' in the Infobases view); default true.") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = perform the removal; default false = preview only (no change).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' (nothing changed) or 'deleted' (removed).") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no change made); absent/false once deleted.") //$NON-NLS-1$
            .stringProperty("project", "Name of the configuration project.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Application ID that was removed.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("infobaseName", "Display name of the removed infobase.") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("deleteRegistration", //$NON-NLS-1$
                "Whether the infobase was (or would be) deregistered from the EDT infobases list.") //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, "projectName"); //$NON-NLS-1$
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        String infobaseName = JsonUtils.extractStringArgument(params, "infobaseName"); //$NON-NLS-1$
        boolean deleteRegistration =
            JsonUtils.extractBooleanArgument(params, "deleteRegistration", true); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$

        boolean hasId = applicationId != null && !applicationId.isEmpty();
        boolean hasName = infobaseName != null && !infobaseName.isEmpty();
        if (!hasId && !hasName)
        {
            return ToolResult.error("Either applicationId (from get_applications) or " //$NON-NLS-1$
                + "infobaseName is required.").toJson(); //$NON-NLS-1$
        }

        // Refuse only the transient BUILDING state.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return deleteInfobase(projectName, applicationId, infobaseName, deleteRegistration,
            confirm);
    }

    private String deleteInfobase(String projectName, String applicationId, String infobaseName,
            boolean deleteRegistration, boolean confirm)
    {
        // --- Resolve project ---
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

        // --- Acquire services ---
        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return ToolResult.error("IApplicationManager service is not available").toJson(); //$NON-NLS-1$
        }

        IInfobaseAssociationManager assocManager =
            Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return ToolResult.error("IInfobaseAssociationManager service is not available.").toJson(); //$NON-NLS-1$
        }

        IInfobaseManager ibManager = Activator.getDefault().getInfobaseManager();
        // ibManager is optional — only needed for deleteRegistration=true; null checked below.

        // --- Find the target application ---
        IApplication targetApp = null;

        if (applicationId != null && !applicationId.isEmpty())
        {
            Optional<IApplication> found =
                appManager.getApplication(project, applicationId);
            if (!found.isPresent())
            {
                return ToolResult.error("Application not found: '" + applicationId //$NON-NLS-1$
                    + "' for project '" + projectName //$NON-NLS-1$
                    + "'. Use get_applications to list available application IDs.").toJson(); //$NON-NLS-1$
            }
            targetApp = found.get();
        }
        else
        {
            // Find by display name among the project's infobase-type applications.
            try
            {
                List<IApplication> apps = appManager.getApplications(project);
                if (apps != null)
                {
                    for (IApplication app : apps)
                    {
                        if (infobaseName.equals(app.getName())
                            && app.getType() != null
                            && INFOBASE_APP_TYPE.equals(app.getType().getId()))
                        {
                            targetApp = app;
                            break;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                return ToolResult.error("Error listing applications: " + e.getMessage()).toJson(); //$NON-NLS-1$
            }
            if (targetApp == null)
            {
                return ToolResult.error("Infobase with name '" + infobaseName //$NON-NLS-1$
                    + "' not found in project '" + projectName //$NON-NLS-1$
                    + "'. Use get_applications to list available infobases.").toJson(); //$NON-NLS-1$
            }
        }

        // Verify it is an infobase application (not a server/web type we do not manage here).
        if (!(targetApp instanceof IInfobaseApplication))
        {
            return ToolResult.error("Application '" + targetApp.getName() //$NON-NLS-1$
                + "' (id=" + targetApp.getId() //$NON-NLS-1$
                + ") is not a file infobase application. This tool only removes infobase " //$NON-NLS-1$
                + "applications of type " + INFOBASE_APP_TYPE + ".").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IInfobaseApplication ibApp = (IInfobaseApplication) targetApp;
        InfobaseReference ibRef = ibApp.getInfobase();
        String resolvedName = targetApp.getName();
        String resolvedId = targetApp.getId();

        // --- Confirm-preview gate ---
        if (!confirm)
        {
            return ToolResult.success()
                .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                .put("confirmationRequired", true) //$NON-NLS-1$
                .put("project", projectName) //$NON-NLS-1$
                .put("applicationId", resolvedId) //$NON-NLS-1$
                .put("infobaseName", resolvedName) //$NON-NLS-1$
                .put("deleteRegistration", deleteRegistration) //$NON-NLS-1$
                .put("message", "PREVIEW: this would dissociate infobase '" + resolvedName //$NON-NLS-1$ //$NON-NLS-2$
                    + "' from project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + (deleteRegistration
                        ? " AND deregister it from the EDT infobases list" //$NON-NLS-1$
                        : " (EDT infobases list entry kept)") //$NON-NLS-1$
                    + ". Re-call with confirm=true to apply.") //$NON-NLS-1$
                .toJson();
        }

        // --- Perform deletion ---
        Activator.logInfo("delete_infobase: dissociating '" + resolvedName //$NON-NLS-1$
            + "' from project " + projectName); //$NON-NLS-1$

        // Step 1: dissociate from the project (removes the infobase Application).
        try
        {
            assocManager.dissociate(project, ibRef,
                com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseAssociationContext.empty());
        }
        catch (Exception e)
        {
            Activator.logError("delete_infobase: dissociate failed", e); //$NON-NLS-1$
            return ToolResult.error("Failed to dissociate infobase '" + resolvedName //$NON-NLS-1$
                + "' from project '" + projectName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Step 2: optionally deregister from the global EDT infobases list.
        if (deleteRegistration && ibRef != null)
        {
            if (ibManager == null)
            {
                Activator.logError("delete_infobase: IInfobaseManager not available; " //$NON-NLS-1$
                    + "infobase was dissociated but NOT deregistered from the list", null); //$NON-NLS-1$
                // Non-fatal: the dissociation succeeded.
            }
            else
            {
                try
                {
                    ibManager.delete(ibRef);
                }
                catch (Exception e)
                {
                    Activator.logError("delete_infobase: IInfobaseManager.delete failed " //$NON-NLS-1$
                        + "(non-fatal — dissociation already succeeded)", e); //$NON-NLS-1$
                    // Non-fatal: return success but note the partial deletion.
                    return ToolResult.success()
                        .put("action", "deleted") //$NON-NLS-1$ //$NON-NLS-2$
                        .put("project", projectName) //$NON-NLS-1$
                        .put("applicationId", resolvedId) //$NON-NLS-1$
                        .put("infobaseName", resolvedName) //$NON-NLS-1$
                        .put("deleteRegistration", false) //$NON-NLS-1$
                        .put("message", "Infobase '" + resolvedName //$NON-NLS-1$ //$NON-NLS-2$
                            + "' was dissociated from project '" + projectName //$NON-NLS-1$
                            + "' but could not be deregistered from the EDT list: " //$NON-NLS-1$
                            + e.getMessage()
                            + ". You can remove it manually from the Infobases view in EDT.") //$NON-NLS-1$
                        .toJson();
                }
            }
        }

        Activator.logInfo("delete_infobase: done, resolvedId=" + resolvedId); //$NON-NLS-1$

        return ToolResult.success()
            .put("action", "deleted") //$NON-NLS-1$ //$NON-NLS-2$
            .put("project", projectName) //$NON-NLS-1$
            .put("applicationId", resolvedId) //$NON-NLS-1$
            .put("infobaseName", resolvedName) //$NON-NLS-1$
            .put("deleteRegistration", deleteRegistration) //$NON-NLS-1$
            .put("message", "Infobase '" + resolvedName //$NON-NLS-1$ //$NON-NLS-2$
                + "' removed from project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (deleteRegistration ? " and deregistered from the EDT infobases list." //$NON-NLS-1$
                    : " (EDT infobases list entry kept).")) //$NON-NLS-1$
            .toJson();
    }
}
