/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.jobs.Job;

import com._1c.g5.v8.dt.common.FileUtil;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseAssociationManager;
import com._1c.g5.v8.dt.platform.services.core.infobases.IInfobaseManager;
import com._1c.g5.v8.dt.platform.services.model.FileConnectionString;
import com._1c.g5.v8.dt.platform.services.model.IConnectionString;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
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

    /** Output key: confirmation-required flag (true on a preview). */
    private static final String KEY_CONFIRMATION_REQUIRED = "confirmationRequired"; //$NON-NLS-1$

    /** Output key: the kind of application removed. */
    private static final String KEY_APPLICATION_KIND = "applicationKind"; //$NON-NLS-1$

    /** Output key: whether the database files on disk were deleted. */
    private static final String KEY_DATABASE_FILES_DELETED = "databaseFilesDeleted"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the infobase was removed. */
    private static final String VAL_DELETED = "deleted"; //$NON-NLS-1$

    /** Output key: display name of the infobase. */
    private static final String KEY_INFOBASE_NAME = "infobaseName"; //$NON-NLS-1$

    /** Output key: whether the EDT registry entry was (or would be) removed. */
    private static final String KEY_DELETE_REGISTRATION = "deleteRegistration"; //$NON-NLS-1$

    /** Infobase application type ID. */
    private static final String INFOBASE_APP_TYPE = "com.e1c.g5.dt.applications.type.infobase"; //$NON-NLS-1$

    /** Background-Job timeout for the standalone-server deletion (stop + remove). */
    private static final long DELETE_TIMEOUT_SECONDS = 120;

    /** Maximum re-poll attempts for the read-back that confirms the application is gone. */
    private static final int READ_BACK_MAX_POLLS = 5;

    /** Delay between read-back re-poll attempts (ms). */
    private static final long READ_BACK_POLL_DELAY_MS = 300;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove a FILE infobase association from a configuration project OR delete a standalone " //$NON-NLS-1$
            + "(autonomous) server application. Destructive: guarded by a confirm-preview - call without " //$NON-NLS-1$
            + "confirm to preview what would be removed (no change), then confirm=true to delete. For a " //$NON-NLS-1$
            + "file infobase: dissociates it and (deleteRegistration, default true) deregisters it from " //$NON-NLS-1$
            + "the EDT infobases list. For a standalone server (applicationKind=standaloneServer): stops " //$NON-NLS-1$
            + "it and removes the WST server and its server config folder. By default the infobase " //$NON-NLS-1$
            + "DATABASE FILES on disk are KEPT (both kinds); pass deleteDatabaseFiles=true to also delete " //$NON-NLS-1$
            + "the database directory. The inverse of create_infobase. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('delete_infobase')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT configuration project the infobase is bound to (required).", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications. Either applicationId or infobaseName " //$NON-NLS-1$
                + "is required.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME,
                "Display name of the infobase to remove. Either applicationId or infobaseName " //$NON-NLS-1$
                + "is required.") //$NON-NLS-1$
            .booleanProperty(KEY_DELETE_REGISTRATION,
                "true = also deregister the infobase from the global EDT infobases list " //$NON-NLS-1$
                + "(equivalent to 'Delete' in the Infobases view); default true.") //$NON-NLS-1$
            .booleanProperty("deleteDatabaseFiles", //$NON-NLS-1$
                "true = ALSO delete the infobase database files/directory from disk (the 1Cv8.1CD " //$NON-NLS-1$
                + "directory) — IRREVERSIBLE. Default false = keep the database files on disk (the " //$NON-NLS-1$
                + "registration is removed but the data stays). Works for both a file infobase and a " //$NON-NLS-1$
                + "standalone server (deletes the served database directory).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = perform the removal; default false = preview only (no change).") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview' (nothing changed) or 'deleted' (removed).") //$NON-NLS-1$
            .booleanProperty(KEY_CONFIRMATION_REQUIRED,
                "true on a preview (no change made); absent/false once deleted.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_KIND,
                "'infobase' or 'standaloneServer' — the kind of application removed (standalone-server " //$NON-NLS-1$
                + "deletions only).") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Name of the configuration project.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application ID that was removed.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_NAME, "Display name of the removed infobase.") //$NON-NLS-1$
            .booleanProperty(KEY_DELETE_REGISTRATION,
                "Whether the EDT registry entry was (or would be) removed: the global infobases-list " //$NON-NLS-1$
                + "entry for a file infobase, or the infobases.yaml registry entry for a standalone " //$NON-NLS-1$
                + "server.") //$NON-NLS-1$
            .booleanProperty(KEY_DATABASE_FILES_DELETED,
                "Whether the database files on disk were actually deleted (only when " //$NON-NLS-1$
                + "deleteDatabaseFiles=true; false otherwise or if the directory could not be removed).") //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (err != null)
        {
            return err;
        }

        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String infobaseName = JsonUtils.extractStringArgument(params, KEY_INFOBASE_NAME);
        boolean deleteRegistration =
            JsonUtils.extractBooleanArgument(params, KEY_DELETE_REGISTRATION, true);
        boolean deleteDatabaseFiles =
            JsonUtils.extractBooleanArgument(params, "deleteDatabaseFiles", false); //$NON-NLS-1$
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
            deleteDatabaseFiles, confirm);
    }

    private String deleteInfobase(String projectName, String applicationId, String infobaseName,
            boolean deleteRegistration, boolean deleteDatabaseFiles, boolean confirm)
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
            // Find by display name among the project's infobase-type OR standalone-server applications.
            try
            {
                List<IApplication> apps = appManager.getApplications(project);
                if (apps != null)
                {
                    for (IApplication app : apps)
                    {
                        String appTypeId = app.getType() != null ? app.getType().getId() : null;
                        if (infobaseName.equals(app.getName())
                            && (INFOBASE_APP_TYPE.equals(appTypeId)
                                || StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(appTypeId)))
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

        // Standalone-server (wst-server) applications: the inverse of
        // create_infobase applicationKind=standaloneServer. Handled by a dedicated path.
        String typeId = targetApp.getType() != null ? targetApp.getType().getId() : null;
        if (StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId))
        {
            return deleteStandaloneServer(projectName, project, appManager, targetApp,
                deleteRegistration, deleteDatabaseFiles, confirm);
        }

        // Verify it is a file infobase application (not some other type we do not manage here).
        if (!(targetApp instanceof IInfobaseApplication))
        {
            return ToolResult.error("Application '" + targetApp.getName() //$NON-NLS-1$
                + "' (id=" + targetApp.getId() //$NON-NLS-1$
                + ") is neither a file infobase (" + INFOBASE_APP_TYPE //$NON-NLS-1$
                + ") nor a standalone server (" + StandaloneServerSupport.WST_SERVER_APP_TYPE //$NON-NLS-1$
                + ") application; this tool cannot remove it.").toJson(); //$NON-NLS-1$
        }
        IInfobaseApplication ibApp = (IInfobaseApplication) targetApp;
        InfobaseReference ibRef = ibApp.getInfobase();
        String resolvedName = targetApp.getName();
        String resolvedId = targetApp.getId();
        // Resolve the on-disk infobase directory now (for deleteDatabaseFiles), before dissociation.
        final Path dbDir = resolveFileInfobaseDir(ibRef);
        // A file infobase can be bound to SEVERAL projects; wiping the shared data would break the
        // others. If asked to delete files, check (before dissociating) whether any OTHER project still
        // uses this infobase — if so we keep the files.
        final boolean dbSharedWithOthers = deleteDatabaseFiles && dbDir != null
            && isSharedWithOtherProjects(appManager, project, dbDir);

        // --- Confirm-preview gate ---
        if (!confirm)
        {
            return ToolResult.success()
                .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
                .put(KEY_CONFIRMATION_REQUIRED, true)
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, resolvedId)
                .put(KEY_INFOBASE_NAME, resolvedName)
                .put(KEY_DELETE_REGISTRATION, deleteRegistration)
                .put(McpKeys.MESSAGE, "PREVIEW: this would dissociate infobase '" + resolvedName //$NON-NLS-1$
                    + "' from project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                    + (deleteRegistration
                        ? " AND deregister it from the EDT infobases list" //$NON-NLS-1$
                        : " (EDT infobases list entry kept)") //$NON-NLS-1$
                    + databasePreviewNote(deleteDatabaseFiles, dbDir, dbSharedWithOthers)
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
                    // Non-fatal: return success but note the partial deletion. We deliberately do NOT
                    // delete the database files here even if requested — deregistration failed, so the
                    // safe choice is to leave the data and say so explicitly (schema-consistent).
                    return ToolResult.success()
                        .put(McpKeys.ACTION, VAL_DELETED)
                        .put(McpKeys.PROJECT, projectName)
                        .put(McpKeys.APPLICATION_ID, resolvedId)
                        .put(KEY_INFOBASE_NAME, resolvedName)
                        .put(KEY_DELETE_REGISTRATION, false)
                        .put(KEY_DATABASE_FILES_DELETED, false)
                        .put(McpKeys.MESSAGE, "Infobase '" + resolvedName //$NON-NLS-1$
                            + "' was dissociated from project '" + projectName //$NON-NLS-1$
                            + "' but could not be deregistered from the EDT list: " //$NON-NLS-1$
                            + e.getMessage()
                            + ". You can remove it manually from the Infobases view in EDT." //$NON-NLS-1$
                            + (deleteDatabaseFiles
                                ? " The database files on disk were KEPT (deregistration failed); " //$NON-NLS-1$
                                    + "remove the directory manually if intended." //$NON-NLS-1$
                                : "")) //$NON-NLS-1$
                        .toJson();
                }
            }
        }

        // Step 3: optionally delete the infobase database files from disk (IRREVERSIBLE).
        // Skip when the same on-disk database is still referenced by other workspace projects —
        // deleting it would break those projects (a file infobase can be shared).
        boolean dbFilesDeleted = false;
        if (deleteDatabaseFiles && dbDir != null && !dbSharedWithOthers)
        {
            dbFilesDeleted = deleteDatabaseDirBestEffort(dbDir);
        }

        Activator.logInfo("delete_infobase: done, resolvedId=" + resolvedId //$NON-NLS-1$
            + " (dbFilesDeleted=" + dbFilesDeleted + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, resolvedId)
            .put(KEY_INFOBASE_NAME, resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration)
            .put(KEY_DATABASE_FILES_DELETED, dbFilesDeleted)
            .put(McpKeys.MESSAGE, "Infobase '" + resolvedName //$NON-NLS-1$
                + "' removed from project '" + projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (deleteRegistration ? " and deregistered from the EDT infobases list." //$NON-NLS-1$
                    : " (EDT infobases list entry kept).") //$NON-NLS-1$
                + databaseResultNote(deleteDatabaseFiles, dbDir, dbFilesDeleted, dbSharedWithOthers)) //$NON-NLS-1$
            .toJson();
    }

    /**
     * Deletes a standalone (WST) server application — the inverse of
     * {@code create_infobase applicationKind=standaloneServer}. Mirrors EDT's "Delete server" UI action
     * via {@code IStandaloneServerService.deleteServer(...)} (resolved reflectively): it stops the server,
     * removes the WST {@code IServer} (servers.xml) and deletes its config folder (the served database).
     * EDT itself leaves an orphaned entry in the standalone-server {@code infobases.yaml} registry; when
     * {@code deleteRegistration} is true we additionally clean that entry (best-effort).
     *
     * <p><strong>Unattended-safety:</strong> {@code deleteServer} is non-modal and monitor-driven; it runs
     * inside a bounded background Job — never on the UI thread.
     */
    private String deleteStandaloneServer(String projectName, IProject project,
            IApplicationManager appManager, IApplication targetApp, boolean deleteRegistration,
            boolean deleteDatabaseFiles, boolean confirm)
    {
        final String resolvedName = targetApp.getName();
        final String resolvedId = targetApp.getId();

        // Resolve the standalone-server service reflectively (no Require-Bundle on the optional feature).
        Object service = StandaloneServerSupport.acquireService();
        if (service == null)
        {
            return ToolResult.error("Standalone-server service is not available; the EDT " //$NON-NLS-1$
                + "standalone-server feature is missing. Cannot delete server application '" //$NON-NLS-1$
                + resolvedName + "'.").toJson(); //$NON-NLS-1$
        }

        // Resolve the backing WST IServer: direct IServerApplication.getServer(), else a name scan.
        Object server = StandaloneServerSupport.serverOfApplication(targetApp);
        if (server == null)
        {
            server = StandaloneServerSupport.findServerByModuleName(service, resolvedName);
        }
        if (server == null)
        {
            return ToolResult.error("Could not resolve the WST server backing application '" //$NON-NLS-1$
                + resolvedName + "' (id=" + resolvedId //$NON-NLS-1$
                + "). It may already be deleted — re-run get_applications.").toJson(); //$NON-NLS-1$
        }

        // Capture the infobaseId AND the served-DB directory (database.path) BEFORE deletion — the
        // module/config is torn down by deleteServer. The infobaseId drives the yaml cleanup; the DB
        // directory is used only when deleteDatabaseFiles=true (deleteServer itself never deletes it).
        Object module = StandaloneServerSupport.moduleOfApplication(targetApp);
        final String infobaseId = module != null ? StandaloneServerSupport.infobaseIdOf(module) : null;
        final String dbDirStr = module != null ? StandaloneServerSupport.databaseDirOf(module) : null;
        final Path dbDir = (dbDirStr != null && !dbDirStr.isEmpty()) ? Paths.get(dbDirStr) : null;
        // A server's served DB is normally dedicated, but EDT does not forbid another project from
        // registering the same directory as a FILE infobase — so apply the same shared-files guard.
        final boolean dbSharedWithOthers = deleteDatabaseFiles && dbDir != null
            && isSharedWithOtherProjects(appManager, project, dbDir);

        // --- Confirm-preview gate ---
        if (!confirm)
        {
            return ToolResult.success()
                .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
                .put(KEY_CONFIRMATION_REQUIRED, true)
                .put(KEY_APPLICATION_KIND, "standaloneServer") //$NON-NLS-1$
                .put(McpKeys.PROJECT, projectName)
                .put(McpKeys.APPLICATION_ID, resolvedId)
                .put(KEY_INFOBASE_NAME, resolvedName)
                .put(KEY_DELETE_REGISTRATION, deleteRegistration)
                .put(McpKeys.MESSAGE, "PREVIEW: this would delete standalone server '" + resolvedName //$NON-NLS-1$
                    + "' (stop it, remove the WST server and its server config folder)" //$NON-NLS-1$
                    + (deleteRegistration ? " AND clean its infobases.yaml registry entry" //$NON-NLS-1$
                        : " (infobases.yaml entry kept)") //$NON-NLS-1$
                    + databasePreviewNote(deleteDatabaseFiles, dbDir, dbSharedWithOthers)
                    + " for project '" + projectName //$NON-NLS-1$
                    + "'. This is irreversible. Re-call with confirm=true to apply.") //$NON-NLS-1$
                .toJson();
        }

        // Capture how many applications currently carry this id, so the read-back can confirm THIS
        // deletion via a count decrease even when a same-named twin shares the id (a degenerate case).
        final int beforeCount = countAppsWithId(appManager, project, resolvedId);

        // --- Perform deletion + registry cleanup in ONE bounded background Job. deleteServer is
        // non-modal/monitor-driven; keeping the yaml cleanup in the same Job bounds it by one timeout
        // and keeps it off the request thread. ---
        final Object finalService = service;
        final Object finalServer = server;
        final String finalInfobaseId = infobaseId;
        final boolean doRegistry = deleteRegistration;
        final AtomicReference<IStatus> jobStatus = new AtomicReference<>();
        final AtomicReference<StandaloneServerSupport.RegistryCleanup> jobCleanup =
            new AtomicReference<>(StandaloneServerSupport.RegistryCleanup.NOT_PRESENT);
        final AtomicReference<Exception> jobError = new AtomicReference<>();

        Job deleteJob = new Job("Delete standalone server: " + resolvedName) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                try
                {
                    IStatus s = StandaloneServerSupport.deleteServer(finalService, finalServer, monitor);
                    jobStatus.set(s);
                    // Only clean the registry once the server is actually gone — a null/non-OK status
                    // means deleteServer did not run/succeed, so the server still exists.
                    if (s != null && s.isOK() && doRegistry)
                    {
                        jobCleanup.set(
                            StandaloneServerSupport.removeFromInfobaseRegistry(finalInfobaseId, monitor));
                    }
                }
                catch (Exception e)
                {
                    jobError.set(e);
                }
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        deleteJob.setUser(false);
        deleteJob.setSystem(true);
        deleteJob.schedule();

        try
        {
            boolean finished = deleteJob.join(TimeUnit.SECONDS.toMillis(DELETE_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                // The reflective deleteServer is a blocking platform call that may not honour
                // cancellation, so do not claim nothing changed — be honest that it may still finish.
                deleteJob.cancel();
                return ToolResult.error("Standalone-server deletion of '" + resolvedName //$NON-NLS-1$
                    + "' did not finish within " + DELETE_TIMEOUT_SECONDS //$NON-NLS-1$
                    + " seconds. It MAY STILL BE COMPLETING in the background — re-run get_applications " //$NON-NLS-1$
                    + "to check the current state before retrying. See the EDT log for details.").toJson(); //$NON-NLS-1$
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Standalone-server deletion was interrupted.").toJson(); //$NON-NLS-1$
        }

        if (jobError.get() != null)
        {
            Activator.logError("delete_infobase: standalone-server deletion failed for " //$NON-NLS-1$
                + resolvedName, jobError.get());
            return ToolResult.error("Standalone-server deletion failed for '" + resolvedName //$NON-NLS-1$
                + "': " + jobError.get().getMessage()).toJson(); //$NON-NLS-1$
        }
        IStatus status = jobStatus.get();
        if (status == null || !status.isOK())
        {
            // null = deleteServer returned a non-IStatus (reflective miss); non-OK = the platform
            // reported a failure. Either way the server was NOT cleanly deleted — report an error.
            String detail = status != null ? status.getMessage() : "no status returned"; //$NON-NLS-1$
            Activator.logError("delete_infobase: deleteServer did not succeed: " + detail, null); //$NON-NLS-1$
            return ToolResult.error("Standalone-server deletion did not complete cleanly for '" //$NON-NLS-1$
                + resolvedName + "': " + detail).toJson(); //$NON-NLS-1$
        }

        StandaloneServerSupport.RegistryCleanup cleanup = jobCleanup.get();
        Activator.logInfo("delete_infobase: standalone server '" + resolvedName //$NON-NLS-1$
            + "' deleted (registryCleanup=" + cleanup + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        // Optionally delete the served-DB files from disk (deleteServer removes only the SERVER config
        // folder, never the served database at database.path — so this is what makes "delete everything").
        boolean dbFilesDeleted = false;
        if (deleteDatabaseFiles && dbDir != null && !dbSharedWithOthers)
        {
            dbFilesDeleted = deleteDatabaseDirBestEffort(dbDir);
        }

        // Read-back: confirm THIS deletion via a count decrease (tolerant of same-id twins).
        boolean removed = confirmApplicationRemoved(appManager, project, resolvedId, beforeCount);

        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(KEY_APPLICATION_KIND, "standaloneServer") //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, resolvedId)
            .put(KEY_INFOBASE_NAME, resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration)
            .put(KEY_DATABASE_FILES_DELETED, dbFilesDeleted)
            .put(McpKeys.MESSAGE, "Standalone server '" + resolvedName //$NON-NLS-1$
                + "' deleted from project '" + projectName //$NON-NLS-1$
                + "' (server stopped, WST server and its server config folder removed)" //$NON-NLS-1$
                + (deleteRegistration ? registryNote(cleanup)
                    : " (infobases.yaml registry entry kept).") //$NON-NLS-1$
                + databaseResultNote(deleteDatabaseFiles, dbDir, dbFilesDeleted, dbSharedWithOthers)
                + (removed ? "" : " NOTE: it may still appear in get_applications briefly.")) //$NON-NLS-1$ //$NON-NLS-2$
            .toJson();
    }

    /** Human-readable note describing the infobases.yaml cleanup outcome (deleteRegistration=true). */
    private static String registryNote(StandaloneServerSupport.RegistryCleanup cleanup)
    {
        switch (cleanup)
        {
            case REMOVED:
                return " and its infobases.yaml registry entry cleaned."; //$NON-NLS-1$
            case NOT_PRESENT:
                return " (no stale infobases.yaml registry entry to clean)."; //$NON-NLS-1$
            default:
                return "; the infobases.yaml entry could not be cleaned now (cosmetic — it self-heals " //$NON-NLS-1$
                    + "on the next EDT restart)."; //$NON-NLS-1$
        }
    }

    /**
     * Bounded re-poll that confirms THIS standalone-server deletion took effect, by waiting until the
     * number of applications carrying {@code appId} drops below {@code beforeCount} (absorbs the
     * provision-delegate listener race). Counting (rather than presence) makes it correct even when a
     * same-named twin server shares the id: deleting one of two twins is confirmed when the count goes
     * 2 -> 1. Returns true once the count decreased (or reached zero), false if it never did.
     */
    private static boolean confirmApplicationRemoved(IApplicationManager appManager, IProject project,
            String appId, int beforeCount)
    {
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++)
        {
            int now = countAppsWithId(appManager, project, appId);
            if (now < 0)
            {
                // Cannot read back — do not block the (already successful) deletion result.
                return true;
            }
            if (now == 0 || now < beforeCount)
            {
                return true;
            }

            if (poll < READ_BACK_MAX_POLLS - 1)
            {
                try
                {
                    Thread.sleep(READ_BACK_POLL_DELAY_MS);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Counts the project's applications whose id equals {@code appId}. Returns -1 if the application list
     * could not be read (the caller treats that as "cannot confirm" rather than a failure).
     */
    private static int countAppsWithId(IApplicationManager appManager, IProject project, String appId)
    {
        try
        {
            List<IApplication> apps = appManager.getApplications(project);
            int count = 0;
            if (apps != null)
            {
                for (IApplication a : apps)
                {
                    if (appId.equals(a.getId()))
                    {
                        count++;
                    }
                }
            }
            return count;
        }
        catch (Exception e)
        {
            return -1;
        }
    }

    /**
     * Resolves the on-disk directory of a FILE infobase from its {@link InfobaseReference}: the FILE
     * connection string's path ({@link FileConnectionString#getFile()}). Returns {@code null} for a
     * non-file (server/web) reference or when the path is empty — the caller then skips file deletion.
     */
    private static Path resolveFileInfobaseDir(InfobaseReference ibRef)
    {
        try
        {
            IConnectionString cs = (ibRef != null) ? ibRef.getConnectionString() : null;
            if (cs instanceof FileConnectionString)
            {
                String path = ((FileConnectionString)cs).getFile();
                if (path != null && !path.trim().isEmpty())
                {
                    return Paths.get(path.trim());
                }
            }
        }
        catch (Exception e)
        {
            Activator.logError("delete_infobase: could not resolve the file-infobase directory", e); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The same on-disk database can be used by SEVERAL projects at once — as a FILE infobase OR as a
     * standalone (wst) server's served database. Returns {@code true} when ANY project OTHER than
     * {@code currentProject} still has an application backed by the SAME on-disk directory
     * ({@code dbDir}) — in which case wiping the files would break those projects, so the caller keeps
     * the data. Conservative: any failure to enumerate is treated as "shared" (keep).
     * <p>
     * CLOSED projects are intentionally NOT skipped: {@code IApplicationManager.getApplications} resolves
     * a project's infobases/servers from WORKSPACE-level stores (the infobase association manager and the
     * standalone-server registry), NOT from the project's open resources (bytecode-verified on 2025.2), so
     * a closed project that still references the same database is visible here and must be honoured.
     */
    private static boolean isSharedWithOtherProjects(IApplicationManager appManager, IProject currentProject,
            Path dbDir)
    {
        if (appManager == null || dbDir == null)
        {
            return false;
        }
        Path target = dbDir.toAbsolutePath().normalize();
        try
        {
            for (IProject other : ProjectContext.allProjects())
            {
                if (other == null || other.equals(currentProject))
                {
                    continue;
                }
                List<IApplication> apps;
                try
                {
                    apps = appManager.getApplications(other);
                }
                catch (Exception e)
                {
                    // A project that cannot be queried might still share the infobase — be safe.
                    Activator.logError("delete_infobase: could not list applications of project '" //$NON-NLS-1$
                        + other.getName() + "' while checking for shared infobases", e); //$NON-NLS-1$
                    return true;
                }
                if (apps == null)
                {
                    continue;
                }
                for (IApplication app : apps)
                {
                    // Resolve EVERY co-owner kind: a FILE infobase OR a standalone (wst) server that
                    // serves the same on-disk database — not just file infobases.
                    Path otherDir = resolveApplicationDbDir(app);
                    if (otherDir != null && target.equals(otherDir.toAbsolutePath().normalize()))
                    {
                        Activator.logInfo("delete_infobase: database '" + dbDir //$NON-NLS-1$
                            + "' is also used by project '" + other.getName() //$NON-NLS-1$
                            + "' — keeping the files on disk"); //$NON-NLS-1$
                        return true;
                    }
                }
            }
        }
        catch (Exception e)
        {
            // Enumeration itself failed — keep the files (the safe choice).
            Activator.logError("delete_infobase: shared-infobase check failed — keeping the files", e); //$NON-NLS-1$
            return true;
        }
        return false;
    }

    /**
     * Resolves an application's on-disk database directory for the shared-files guard: a FILE infobase
     * via its connection string, OR a standalone (wst) server via its served-database path
     * ({@link StandaloneServerSupport#databaseDirOf}). Returns {@code null} for any other application kind
     * or when the path cannot be resolved.
     */
    private static Path resolveApplicationDbDir(IApplication app)
    {
        if (app instanceof IInfobaseApplication)
        {
            return resolveFileInfobaseDir(((IInfobaseApplication)app).getInfobase());
        }
        String typeId = (app != null && app.getType() != null) ? app.getType().getId() : null;
        if (StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId))
        {
            Object module = StandaloneServerSupport.moduleOfApplication(app);
            String dir = module != null ? StandaloneServerSupport.databaseDirOf(module) : null;
            if (dir != null && !dir.isEmpty())
            {
                try
                {
                    return Paths.get(dir);
                }
                catch (RuntimeException e)
                {
                    Activator.logError("delete_infobase: could not parse served-DB path '" + dir //$NON-NLS-1$
                        + "' of a standalone server while checking shared infobases", e); //$NON-NLS-1$
                }
            }
        }
        return null;
    }

    /**
     * Best-effort recursive delete of an infobase database directory via EDT's
     * {@code FileUtil.deleteRecursivelyWithRetries} (3 attempts, 500 ms apart; the same primitive the
     * standalone-server delete uses). Non-fatal: a locked/undeletable directory is logged and the call
     * returns {@code false} (the registration is already removed; the message tells the user to delete
     * the directory manually). Returns {@code true} only when the directory is gone afterwards.
     */
    private static boolean deleteDatabaseDirBestEffort(Path dir)
    {
        if (dir == null)
        {
            return false;
        }
        // Resolve to an ABSOLUTE path first: getParent()==null is a filesystem-root test ONLY for an
        // absolute path — a single-segment RELATIVE path ("MyInfobase") or "." also has no parent and
        // would otherwise be wrongly refused as if it were a drive root.
        dir = dir.toAbsolutePath().normalize();
        // SAFETY: the directory is user-supplied (create_infobase's infobaseFile) and mode='register'
        // accepts ANY folder that merely contains a 1Cv8.1CD — so a recursive delete could wipe unrelated
        // sibling files or a drive root. Refuse a filesystem root, and only delete a directory that
        // actually looks like a 1C file infobase (carries a 1Cv8.1CD).
        if (dir.getNameCount() == 0 || dir.getParent() == null)
        {
            Activator.logError("delete_infobase: refusing to delete the filesystem root '" + dir //$NON-NLS-1$
                + "'", null); //$NON-NLS-1$
            return false;
        }
        if (!dir.resolve("1Cv8.1CD").toFile().exists()) //$NON-NLS-1$
        {
            Activator.logError("delete_infobase: '" + dir + "' is not a 1C infobase directory " //$NON-NLS-1$ //$NON-NLS-2$
                + "(no 1Cv8.1CD) — NOT deleting it", null); //$NON-NLS-1$
            return false;
        }
        try
        {
            FileUtil.deleteRecursivelyWithRetries(dir);
            return !dir.toFile().exists();
        }
        catch (IOException | RuntimeException e)
        {
            Activator.logError("delete_infobase: could not delete the database directory '" + dir //$NON-NLS-1$
                + "' (non-fatal — the registration is already removed; delete it manually)", e); //$NON-NLS-1$
            return false;
        }
    }

    /** Preview-message fragment describing what deleteDatabaseFiles will (or will not) do to disk. */
    private static String databasePreviewNote(boolean deleteDatabaseFiles, Path dbDir,
            boolean sharedWithOthers)
    {
        if (!deleteDatabaseFiles)
        {
            return " (the database files on disk are KEPT)"; //$NON-NLS-1$
        }
        if (dbDir == null)
        {
            return " (deleteDatabaseFiles was requested but no database directory could be resolved)"; //$NON-NLS-1$
        }
        if (sharedWithOthers)
        {
            return " (the database files would be KEPT — this infobase is still used by other projects)"; //$NON-NLS-1$
        }
        return " AND DELETE its database files at '" + dbDir + "' from disk (IRREVERSIBLE)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Result-message fragment describing the actual outcome of the database-files deletion. */
    private static String databaseResultNote(boolean deleteDatabaseFiles, Path dbDir, boolean deleted,
            boolean sharedWithOthers)
    {
        if (!deleteDatabaseFiles)
        {
            return " The database files on disk were kept (pass deleteDatabaseFiles=true to remove them)."; //$NON-NLS-1$
        }
        if (dbDir == null)
        {
            return " No database directory could be resolved, so nothing was deleted from disk."; //$NON-NLS-1$
        }
        if (sharedWithOthers)
        {
            return " The database files were KEPT: this infobase is still used by other project(s) in " //$NON-NLS-1$
                + "the workspace; deleting them would break those projects."; //$NON-NLS-1$
        }
        return deleted
            ? " The database files at '" + dbDir + "' were deleted from disk." //$NON-NLS-1$ //$NON-NLS-2$
            : " The database files at '" + dbDir //$NON-NLS-1$
                + "' were NOT deleted (locked, already absent, or not a recognised infobase directory " //$NON-NLS-1$
                + "without a 1Cv8.1CD) — check/remove the directory manually."; //$NON-NLS-1$
    }
}
