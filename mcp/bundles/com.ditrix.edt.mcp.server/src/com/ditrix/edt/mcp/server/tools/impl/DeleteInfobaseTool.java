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
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
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

    /**
     * Output key: the DECLARED reason the database files were (or, on a preview, would be) KEPT.
     *
     * <p>{@code databaseFilesDeleted=false} has six causes and only the prose told them apart, so
     * a programmatic client could not distinguish a MEASURED co-owner from a shared-infobase check
     * that never concluded — the one distinction #622 exists to keep.
     */
    private static final String KEY_DATABASE_FILES_KEPT = "databaseFilesKept"; //$NON-NLS-1$

    /** {@link #KEY_DATABASE_FILES_KEPT}: deleteDatabaseFiles was not requested. */
    static final String KEPT_NOT_REQUESTED = "notRequested"; //$NON-NLS-1$

    /** {@link #KEY_DATABASE_FILES_KEPT}: no on-disk database directory could be resolved. */
    static final String KEPT_NO_DIRECTORY = "noDirectory"; //$NON-NLS-1$

    /** {@link #KEY_DATABASE_FILES_KEPT}: another workspace project was FOUND using the same database. */
    static final String KEPT_SHARED = "shared"; //$NON-NLS-1$

    /** {@link #KEY_DATABASE_FILES_KEPT}: the shared-infobase check did not conclude — nothing measured. */
    static final String KEPT_UNKNOWN = "unknown"; //$NON-NLS-1$

    /** {@link #KEY_DATABASE_FILES_KEPT}: the removal ran or was refused and the directory is not gone. */
    static final String KEPT_DELETE_FAILED = "deleteFailed"; //$NON-NLS-1$

    /**
     * {@link #KEY_DATABASE_FILES_KEPT}: deregistration failed, so the deletion was not attempted —
     * and nothing measured earlier already explained the keep. It is the LAST reason, never one
     * that overwrites a co-ownership answer the same call had already established.
     */
    static final String KEPT_DEREGISTRATION_FAILED = "deregistrationFailed"; //$NON-NLS-1$

    /** Output value for {@link McpKeys#ACTION}: the infobase was removed. */
    private static final String VAL_DELETED = "deleted"; //$NON-NLS-1$

    /** Output key: display name of the infobase. */
    private static final String KEY_INFOBASE_NAME = "infobaseName"; //$NON-NLS-1$

    /** Output key: whether the EDT registry entry was (or would be) removed. */
    private static final String KEY_DELETE_REGISTRATION = "deleteRegistration"; //$NON-NLS-1$

    /** Infobase application type ID. */
    private static final String INFOBASE_APP_TYPE = "com.e1c.g5.dt.applications.type.infobase"; //$NON-NLS-1$

    /** Consent-subtitle fragment appended when deleteDatabaseFiles=true (irreversible file deletion). */
    private static final String CONSENT_DELETE_FILES_SUFFIX =
        " and may delete its database files from disk (irreversible)."; //$NON-NLS-1$

    /** Message fragment joining an infobase/server name to its owning project name. */
    private static final String MSG_FROM_PROJECT = "' from project '"; //$NON-NLS-1$

    /** Background-Job timeout for the standalone-server deletion (stop + remove). */
    private static final long DELETE_TIMEOUT_SECONDS = 120;

    /** Maximum re-poll attempts for the read-back that confirms the application is gone. */
    private static final int READ_BACK_MAX_POLLS = 5;

    /** Delay between read-back re-poll attempts (ms). */
    private static final long READ_BACK_POLL_DELAY_MS = 300;

    /**
     * Bound on the WHOLE shared-infobase enumeration (#622), not on one project's listing.
     *
     * <p>One bound, two different reasons it is the right shape. On a WEDGED EDT the per-project
     * reads all queue behind the same platform monitor, so a per-project deadline would multiply
     * by the workspace size for no extra information. On a healthy but large workspace they do not
     * queue — they SUM — and a single bound is then the only thing that keeps a big workspace from
     * running past it. Twice {@link ApplicationSupport#LOOKUP_TIMEOUT_MS} because it is one wait
     * covering many reads, and expiry keeps the database files rather than deleting them.
     */
    private static final long SHARED_INFOBASE_CHECK_TIMEOUT_MS = 2 * ApplicationSupport.LOOKUP_TIMEOUT_MS;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Remove a project's infobase or its standalone-server registration, optionally deleting the " //$NON-NLS-1$
            + "database files. DESTRUCTIVE and IRREVERSIBLE. Two-phase: call once WITHOUT confirm to " //$NON-NLS-1$
            + "preview, then again with confirm=true to apply. Parameters and examples: " //$NON-NLS-1$
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
            .enumProperty(KEY_DATABASE_FILES_KEPT,
                "WHY the database files were kept; absent exactly when they were (or, on a " //$NON-NLS-1$
                    + "preview, would be) deleted. 'notRequested' = deleteDatabaseFiles was not " //$NON-NLS-1$
                    + "asked for. 'noDirectory' = no on-disk database directory could be resolved. " //$NON-NLS-1$
                    + "'shared' = another workspace project was FOUND using the same database. " //$NON-NLS-1$
                    + "'unknown' = the shared-infobase check did not conclude, so co-ownership was " //$NON-NLS-1$
                    + "never measured - do NOT read it as 'shared'; the next call re-runs the " //$NON-NLS-1$
                    + "check. 'deleteFailed' = the removal did not leave the directory gone: it " //$NON-NLS-1$
                    + "was refused (a filesystem root, or no 1Cv8.1CD - which is also how a " //$NON-NLS-1$
                    + "directory that was already gone looks) or the delete itself failed " //$NON-NLS-1$
                    + "(locked) - check the EDT log and remove it manually if it is still there. " //$NON-NLS-1$
                    + "'deregistrationFailed' = deregistration failed, so the deletion was " //$NON-NLS-1$
                    + "deliberately not attempted, AND no earlier reason applied - a measured " //$NON-NLS-1$
                    + "'shared'/'unknown'/'noDirectory' still wins, so confirm never contradicts " //$NON-NLS-1$
                    + "the preview. The last two arise only on a deletion, never on a preview.", //$NON-NLS-1$
                KEPT_NOT_REQUESTED, KEPT_NO_DIRECTORY, KEPT_SHARED, KEPT_UNKNOWN, KEPT_DELETE_FAILED,
                KEPT_DEREGISTRATION_FAILED)
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message.") //$NON-NLS-1$
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
        // Stopping a standalone server / dissociating an infobase reaches the
        // application/infobase connection layer (issue #270).
        return true;
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
        // --- Resolve project + services ---
        DeleteContext context = resolveContext(projectName);
        if (context.error != null)
        {
            return context.error;
        }
        IProject project = context.project;
        IApplicationManager appManager = context.appManager;
        IInfobaseAssociationManager assocManager = context.assocManager;
        IInfobaseManager ibManager = context.ibManager;

        // --- Find the target application ---
        TargetApplication target =
            resolveTargetApplication(appManager, project, projectName, applicationId, infobaseName);
        if (target.error != null)
        {
            return target.error;
        }
        IApplication targetApp = target.app;

        // Standalone-server (wst-server) applications: the inverse of
        // create_infobase applicationKind=standaloneServer. Handled by a dedicated path.
        String typeId = targetApp.getType() != null ? targetApp.getType().getId() : null;
        if (StandaloneServerSupport.WST_SERVER_APP_TYPE.equals(typeId))
        {
            return deleteStandaloneServer(projectName, project, appManager, targetApp,
                deleteRegistration, deleteDatabaseFiles, confirm);
        }

        // Resolve the file-infobase target (cast-check, on-disk directory, shared-files guard) -
        // read-only. On a non-file/non-server application it carries the SAME refusal JSON.
        FileDeletionPlan ibPlan = resolveFileDeletionPlan(targetApp, project, appManager,
            deleteRegistration, deleteDatabaseFiles);
        if (ibPlan.error != null)
        {
            return ibPlan.error;
        }

        // --- Confirm-preview gate ---
        if (!confirm)
        {
            return buildPreviewResult(
                new IbIdentity(projectName, ibPlan.resolvedId, ibPlan.resolvedName),
                deleteRegistration, deleteDatabaseFiles, ibPlan.dbDir, ibPlan.dbShared);
        }

        // Destructive-operation consent gate: the LAST check before the infobase is dissociated /
        // deregistered / its files removed. On REJECT nothing is mutated.
        String declined = checkDestructiveConsent(ibPlan.resolvedName, "This dissociates infobase '" //$NON-NLS-1$
            + ibPlan.resolvedName + MSG_FROM_PROJECT + projectName + "'" //$NON-NLS-1$
            + (deleteRegistration ? " and deregisters it from the EDT infobases list" : "") //$NON-NLS-1$ //$NON-NLS-2$
            + (deleteDatabaseFiles ? CONSENT_DELETE_FILES_SUFFIX : ".")); //$NON-NLS-1$
        if (declined != null)
        {
            return declined;
        }

        return performFileInfobaseDeletion(projectName, project, assocManager, ibManager,
            deleteDatabaseFiles, ibPlan);
    }

    /**
     * Resolves the file-infobase deletion target (read-only): verifies the application is a file
     * infobase (not some other unmanaged type), reads its {@link InfobaseReference}, the on-disk
     * database directory and the shared-files guard. Returns a {@link FileDeletionPlan} whose
     * {@code error} carries the SAME refusal JSON the inline cast-check produced when the application
     * is neither a file infobase nor a standalone server; otherwise {@code error} is {@code null} and
     * the plan fields are populated. Extracted verbatim from {@link #deleteInfobase}.
     */
    private static FileDeletionPlan resolveFileDeletionPlan(IApplication targetApp, IProject project,
            IApplicationManager appManager, boolean deleteRegistration,
            boolean deleteDatabaseFiles)
    {
        // Verify it is a file infobase application (not some other type we do not manage here).
        if (!(targetApp instanceof IInfobaseApplication))
        {
            return FileDeletionPlan.failed(ToolResult.error("Application '" + targetApp.getName() //$NON-NLS-1$
                + "' (id=" + targetApp.getId() //$NON-NLS-1$
                + ") is neither a file infobase (" + INFOBASE_APP_TYPE //$NON-NLS-1$
                + ") nor a standalone server (" + StandaloneServerSupport.WST_SERVER_APP_TYPE //$NON-NLS-1$
                + ") application; this tool cannot remove it.").toJson()); //$NON-NLS-1$
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
        final SharedDatabase dbShared = deleteDatabaseFiles && dbDir != null
            ? isSharedWithOtherProjects(appManager, project, dbDir) : SharedDatabase.NOT_SHARED;
        return FileDeletionPlan.of(ibRef, resolvedName, resolvedId, dbDir, dbShared,
            deleteRegistration);
    }

    /**
     * Performs the file-infobase deletion (confirm already granted): dissociate, optional
     * deregister, optional on-disk file deletion — every destructive call stays INLINE at the same
     * point under the same guards. Extracted verbatim from {@link #deleteInfobase}; the early-returns
     * (dissociate failure, deregister partial-failure) return the SAME JSON in the SAME case.
     */
    private String performFileInfobaseDeletion(String projectName, IProject project,
            IInfobaseAssociationManager assocManager, IInfobaseManager ibManager,
            boolean deleteDatabaseFiles, FileDeletionPlan plan)
    {
        final InfobaseReference ibRef = plan.ibRef;
        final IbIdentity id = new IbIdentity(projectName, plan.resolvedId, plan.resolvedName);
        final Path dbDir = plan.dbDir;
        final SharedDatabase dbShared = plan.dbShared;

        // --- Perform deletion ---
        Activator.logInfo("delete_infobase: dissociating '" + id.resolvedName //$NON-NLS-1$
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
            return ToolResult.error("Failed to dissociate infobase '" + id.resolvedName //$NON-NLS-1$
                + MSG_FROM_PROJECT + projectName + "': " + e.getMessage()).toJson(); //$NON-NLS-1$
        }

        // Step 2: optionally deregister from the global EDT infobases list. A non-null result is the
        // partial-failure JSON returned as-is (the database files are then KEPT); null = continue.
        String deregisterFailed = deregisterFileInfobase(ibManager, ibRef, id,
            new DbFileOutcome(deleteDatabaseFiles, dbDir, false, dbShared), plan.deleteRegistration);
        if (deregisterFailed != null)
        {
            return deregisterFailed;
        }

        // Step 3: optionally delete the infobase database files from disk (IRREVERSIBLE).
        // Skip when the same on-disk database is still referenced by other workspace projects —
        // deleting it would break those projects (a file infobase can be shared).
        boolean dbFilesDeleted = false;
        if (deleteDatabaseFiles && dbDir != null && !dbShared.keepsFiles())
        {
            dbFilesDeleted = deleteDatabaseDirBestEffort(dbDir);
        }

        Activator.logInfo("delete_infobase: done, resolvedId=" + id.resolvedId //$NON-NLS-1$
            + " (dbFilesDeleted=" + dbFilesDeleted + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        return buildDeleteSuccessResult(id, plan.deleteRegistration,
            new DbFileOutcome(deleteDatabaseFiles, dbDir, dbFilesDeleted, dbShared));
    }

    /**
     * Step 2 of the file-infobase deletion: optionally deregister the infobase from the global EDT
     * infobases list. The {@code ibManager.delete} destructive call stays INLINE here under the same
     * {@code deleteRegistration && ibRef != null} guard. A missing {@code IInfobaseManager} is logged
     * and treated as non-fatal (the dissociation already succeeded). Returns the partial-failure JSON
     * (which the caller returns as-is — the database files are then KEPT) when the delete throws, or
     * {@code null} to continue.
     *
     * <p>Takes the whole {@link DbFileOutcome} rather than the {@code deleteDatabaseFiles} flag
     * alone: the shared-database check has already run by this point, and the partial-failure
     * payload must report what it MEASURED rather than overwrite it with its own branch.
     */
    private String deregisterFileInfobase(IInfobaseManager ibManager, InfobaseReference ibRef,
            IbIdentity id, DbFileOutcome db, boolean deleteRegistration)
    {
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
                    return buildDeregisterFailedResult(id, db, e);
                }
            }
        }
        return null;
    }

    /**
     * Destructive-operation consent gate shared by both deletion paths (file infobase + standalone
     * server): builds a {@link ConsentPreview} naming the single target and asks
     * {@link DestructiveConsentGate}. Returns the ready error JSON on a non-ALLOW verdict (REJECT: the
     * human declined; TIMEOUT: nobody answered within the gate's bounded wait — see
     * {@link DestructiveConsentGate#consentDeniedMessage}) — the caller returns it and mutates nothing
     * — or {@code null} on ALLOW to continue. Headless / env-bypass / non-ASK never block. This is the
     * LAST check before the deletion.
     *
     * @param targetName the resolved infobase / server display name (the single top-name)
     * @param subtitle a human-readable description of what will be removed
     * @return the decline/timeout error JSON on a non-ALLOW verdict, or {@code null} on ALLOW
     */
    private static String checkDestructiveConsent(String targetName, String subtitle)
    {
        ConsentPreview preview = new ConsentPreview("Delete infobase", subtitle, 1, //$NON-NLS-1$
            java.util.Collections.singletonList(targetName));
        DestructiveConsentGate.ConsentDecision consentDecision =
            DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
        if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
        }
        return null;
    }

    /**
     * Resolves the configuration project and the platform services needed for a file-infobase
     * deletion (read-only). Returns a {@link DeleteContext} whose {@code error} field carries the
     * tool-result JSON for the SAME early-return cases the inline code produced (project missing /
     * closed, {@code IApplicationManager} / {@code IInfobaseAssociationManager} unavailable);
     * otherwise {@code error} is {@code null} and the service fields are populated. The optional
     * {@code ibManager} (only needed for deleteRegistration) may be {@code null} with no error.
     */
    private static DeleteContext resolveContext(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return DeleteContext.failed(ToolResult.error(
                ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return DeleteContext.failed(
                ToolResult.error("Project is closed: " + projectName).toJson()); //$NON-NLS-1$
        }

        IApplicationManager appManager = Activator.getDefault().getApplicationManager();
        if (appManager == null)
        {
            return DeleteContext.failed(
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }

        IInfobaseAssociationManager assocManager =
            Activator.getDefault().getInfobaseAssociationManager();
        if (assocManager == null)
        {
            return DeleteContext.failed(ToolResult.error(
                "IInfobaseAssociationManager service is not available.").toJson()); //$NON-NLS-1$
        }

        // ibManager is optional — only needed for deleteRegistration=true; null checked at use.
        IInfobaseManager ibManager = Activator.getDefault().getInfobaseManager();

        return DeleteContext.of(ctx.project(), appManager, assocManager, ibManager);
    }

    /**
     * Finds the application to remove (read-only) either by {@code applicationId} (exact lookup) or,
     * when no id is given, by {@code infobaseName} among the project's file-infobase OR
     * standalone-server applications. Returns a {@link TargetApplication} whose {@code error} field
     * carries the tool-result JSON for the SAME early-return cases the inline code produced
     * (application id not found, error listing applications, name not found); otherwise {@code error}
     * is {@code null} and {@code app} is the resolved application.
     *
     * <p>Both reads are BOUNDED (#622). This resolution names what a DESTRUCTIVE call is about to
     * delete, so an expired deadline refuses with its own wording rather than fall into either
     * not-found branch.
     */
    static TargetApplication resolveTargetApplication(IApplicationManager appManager,
            IProject project, String projectName, String applicationId, String infobaseName)
    {
        return resolveTargetApplication(appManager, project, projectName, applicationId,
            infobaseName, ApplicationSupport.LOOKUP_TIMEOUT_MS);
    }

    /** Same resolution with an explicit deadline for the two bounded application reads. */
    static TargetApplication resolveTargetApplication(IApplicationManager appManager, // NOSONAR reflective/form or transport god-method; further extraction deferred (reflective code)
            IProject project, String projectName, String applicationId, String infobaseName,
            long timeoutMs)
    {
        if (applicationId != null && !applicationId.isEmpty())
        {
            ApplicationSupport.BoundedRead<Optional<IApplication>> read =
                ApplicationSupport.getApplicationBounded(appManager, project, applicationId,
                    timeoutMs);
            if (!read.concluded())
            {
                return TargetApplication.failed(ToolResult.error("Could not resolve application '" //$NON-NLS-1$
                    + applicationId + "' for project '" + projectName + "': " //$NON-NLS-1$ //$NON-NLS-2$
                    + read.deadlineFailure() + ". Nothing was deleted.").toJson()); //$NON-NLS-1$
            }
            Optional<IApplication> found = read.valueOrRethrow();
            if (!found.isPresent())
            {
                return TargetApplication.failed(ToolResult.error("Application not found: '" //$NON-NLS-1$
                    + applicationId + "' for project '" + projectName //$NON-NLS-1$
                    + "'. Use get_applications to list available application IDs.").toJson()); //$NON-NLS-1$
            }
            return TargetApplication.of(found.get());
        }

        // Find by display name among the project's infobase-type OR standalone-server applications.
        IApplication targetApp = null;
        ApplicationSupport.BoundedRead<List<IApplication>> listRead =
            ApplicationSupport.getApplicationsBounded(appManager, project, timeoutMs);
        if (!listRead.concluded())
        {
            return TargetApplication.failed(ToolResult.error("Error listing applications: " //$NON-NLS-1$
                + listRead.deadlineFailure() + ". Nothing was deleted.").toJson()); //$NON-NLS-1$
        }
        try
        {
            List<IApplication> apps = listRead.valueOrRethrow();
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
            return TargetApplication.failed(
                ToolResult.error("Error listing applications: " + e.getMessage()).toJson()); //$NON-NLS-1$
        }
        if (targetApp == null)
        {
            return TargetApplication.failed(ToolResult.error("Infobase with name '" + infobaseName //$NON-NLS-1$
                + "' not found in project '" + projectName //$NON-NLS-1$
                + "'. Use get_applications to list available infobases.").toJson()); //$NON-NLS-1$
        }
        return TargetApplication.of(targetApp);
    }

    /**
     * Identity quartet shared by the result-builders: the project name plus the resolved application
     * id / name (and, for the success builders, the deleteRegistration flag is passed alongside). A
     * parameter-object that keeps the builders below the 7-parameter bar; carries no behaviour.
     */
    static final class IbIdentity
    {
        final String projectName;
        final String resolvedId;
        final String resolvedName;

        IbIdentity(String projectName, String resolvedId, String resolvedName)
        {
            this.projectName = projectName;
            this.resolvedId = resolvedId;
            this.resolvedName = resolvedName;
        }
    }

    /**
     * The on-disk database-files outcome cluster shared by the success result-builders and
     * {@link #databaseResultNote}: whether deletion was requested, the resolved directory (may be
     * {@code null}), whether it was actually deleted and whether it was kept because shared. A
     * parameter-object that keeps the builders below the 7-parameter bar; carries no behaviour.
     */
    static final class DbFileOutcome
    {
        final boolean deleteDatabaseFiles;
        final Path dbDir;
        final boolean dbFilesDeleted;
        final SharedDatabase dbShared;

        DbFileOutcome(boolean deleteDatabaseFiles, Path dbDir, boolean dbFilesDeleted,
                SharedDatabase dbShared)
        {
            this.deleteDatabaseFiles = deleteDatabaseFiles;
            this.dbDir = dbDir;
            this.dbFilesDeleted = dbFilesDeleted;
            this.dbShared = dbShared;
        }
    }

    /**
     * The reason the database files are kept that is already known BEFORE any deletion is
     * attempted — the only reasons a preview can report.
     *
     * <p>Ordered exactly like {@link #databasePreviewNote}, so the declared value and the prose
     * cannot drift apart. {@link SharedDatabase#SHARED} and {@link SharedDatabase#UNKNOWN} keep the
     * files for OPPOSITE reasons and get their own values.
     *
     * @param deleteDatabaseFiles whether the caller asked for the files to be deleted
     * @param dbDir the resolved on-disk database directory, or {@code null}
     * @param shared what the shared-infobase check established
     * @return the keep reason, or {@code null} when the files are (or would be) deleted
     */
    static String prospectiveDatabaseFilesKept(boolean deleteDatabaseFiles, Path dbDir,
            SharedDatabase shared)
    {
        if (!deleteDatabaseFiles)
        {
            return KEPT_NOT_REQUESTED;
        }
        if (dbDir == null)
        {
            return KEPT_NO_DIRECTORY;
        }
        if (shared == SharedDatabase.SHARED)
        {
            return KEPT_SHARED;
        }
        if (shared == SharedDatabase.UNKNOWN)
        {
            return KEPT_UNKNOWN;
        }
        return null;
    }

    /**
     * The reason the database files were kept after the deletion step ran.
     *
     * @param db the on-disk outcome cluster
     * @return the keep reason, or {@code null} exactly when the files were deleted
     */
    static String databaseFilesKept(DbFileOutcome db)
    {
        if (db.dbFilesDeleted)
        {
            return null;
        }
        String prospective =
            prospectiveDatabaseFilesKept(db.deleteDatabaseFiles, db.dbDir, db.dbShared);
        return prospective != null ? prospective : KEPT_DELETE_FAILED;
    }

    /**
     * Writes the on-disk outcome into a result: whether the files went, and — whenever they did
     * not — the DECLARED reason. Both keys are written HERE, so a payload cannot report
     * {@code databaseFilesDeleted=false} without naming why.
     *
     * @param result the payload being built
     * @param db the on-disk outcome cluster
     * @return the same {@code result}, for chaining
     */
    static ToolResult applyDatabaseFilesOutcome(ToolResult result, DbFileOutcome db)
    {
        result.put(KEY_DATABASE_FILES_DELETED, db.dbFilesDeleted);
        String kept = databaseFilesKept(db);
        if (kept != null)
        {
            result.put(KEY_DATABASE_FILES_KEPT, kept);
        }
        return result;
    }

    /**
     * Writes a preview's prospective keep reason. A preview makes no change, so it carries the
     * reason WITHOUT {@code databaseFilesDeleted}; an absent key means the files would go.
     *
     * @param result the payload being built
     * @param deleteDatabaseFiles whether the caller asked for the files to be deleted
     * @param dbDir the resolved on-disk database directory, or {@code null}
     * @param shared what the shared-infobase check established
     * @return the same {@code result}, for chaining
     */
    static ToolResult applyProspectiveDatabaseFilesKept(ToolResult result,
            boolean deleteDatabaseFiles, Path dbDir, SharedDatabase shared)
    {
        String kept = prospectiveDatabaseFilesKept(deleteDatabaseFiles, dbDir, shared);
        if (kept != null)
        {
            result.put(KEY_DATABASE_FILES_KEPT, kept);
        }
        return result;
    }

    /**
     * Builds the confirm-preview tool-result JSON for a file infobase (no change is made).
     */
    static String buildPreviewResult(IbIdentity id, boolean deleteRegistration,
            boolean deleteDatabaseFiles, Path dbDir, SharedDatabase dbShared)
    {
        return applyProspectiveDatabaseFilesKept(ToolResult.success()
            .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
            .put(KEY_CONFIRMATION_REQUIRED, true)
            .put(McpKeys.PROJECT, id.projectName)
            .put(McpKeys.APPLICATION_ID, id.resolvedId)
            .put(KEY_INFOBASE_NAME, id.resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration), deleteDatabaseFiles, dbDir, dbShared)
            .put(McpKeys.MESSAGE, "PREVIEW: this would dissociate infobase '" + id.resolvedName //$NON-NLS-1$
                + MSG_FROM_PROJECT + id.projectName + "'" //$NON-NLS-1$
                + (deleteRegistration
                    ? " AND deregister it from the EDT infobases list" //$NON-NLS-1$
                    : " (EDT infobases list entry kept)") //$NON-NLS-1$
                + databasePreviewNote(deleteDatabaseFiles, dbDir, dbShared)
                + ". Re-call with confirm=true to apply.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Builds the tool-result JSON for the partial-success case where the infobase was dissociated but
     * could NOT be deregistered from the EDT list. The database files are reported as KEPT (the safe
     * choice when deregistration failed). Read-only — the dissociation has already happened at the
     * call site.
     *
     * <p>The keep REASON is the one {@link #prospectiveDatabaseFilesKept} already established,
     * and only when that has nothing to say does the deregistration failure name itself. This
     * branch is reached AFTER the shared-database check has run, so answering
     * {@code deregistrationFailed} unconditionally overwrote a MEASURED co-ownership: a database
     * this very call had found shared with another project came back as
     * "remove the directory manually if intended", and the preview of the same input said
     * {@code shared}. Preview and confirm must not disagree about a fact neither of them changed.
     *
     * @param id the resolved infobase identity
     * @param db the on-disk outcome cluster as measured BEFORE the deletion step (never deleted:
     *     this branch returns before it)
     * @param e what {@code IInfobaseManager.delete} raised
     * @return the serialized partial-success payload
     */
    static String buildDeregisterFailedResult(IbIdentity id, DbFileOutcome db, Exception e)
    {
        String measured = prospectiveDatabaseFilesKept(db.deleteDatabaseFiles, db.dbDir, db.dbShared);
        boolean deregistrationIsTheReason = measured == null;
        return ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(McpKeys.PROJECT, id.projectName)
            .put(McpKeys.APPLICATION_ID, id.resolvedId)
            .put(KEY_INFOBASE_NAME, id.resolvedName)
            .put(KEY_DELETE_REGISTRATION, false)
            .put(KEY_DATABASE_FILES_DELETED, false)
            .put(KEY_DATABASE_FILES_KEPT,
                deregistrationIsTheReason ? KEPT_DEREGISTRATION_FAILED : measured)
            .put(McpKeys.MESSAGE, "Infobase '" + id.resolvedName //$NON-NLS-1$
                + "' was dissociated from project '" + id.projectName //$NON-NLS-1$
                + "' but could not be deregistered from the EDT list: " //$NON-NLS-1$
                + e.getMessage()
                + ". You can remove it manually from the Infobases view in EDT." //$NON-NLS-1$
                + deregisterFailedDatabaseNote(db, deregistrationIsTheReason))
            .toJson();
    }

    /**
     * The database sentence of {@link #buildDeregisterFailedResult}: it says the same thing as the
     * declared keep reason, so the prose cannot tell the user to delete a directory the
     * machine-readable field says belongs to another project (or says does not exist).
     *
     * @param db the measured on-disk outcome cluster
     * @param deregistrationIsTheReason whether the deregistration failure is what kept the files
     * @return the sentence to append, possibly empty
     */
    private static String deregisterFailedDatabaseNote(DbFileOutcome db,
            boolean deregistrationIsTheReason)
    {
        if (!db.deleteDatabaseFiles)
        {
            return ""; //$NON-NLS-1$
        }
        if (deregistrationIsTheReason)
        {
            return " The database files on disk were KEPT (deregistration failed); " //$NON-NLS-1$
                + "remove the directory manually if intended."; //$NON-NLS-1$
        }
        // A measured reason: the SAME sentence the completed deletion would have produced, so the
        // two paths cannot describe one unchanged fact differently.
        return databaseResultNote(db);
    }

    /**
     * Builds the final success tool-result JSON after a file infobase was removed. Byte-for-byte
     * identical to the inline result the success path produced. Read-only — all mutations
     * (dissociate / deregister / file deletion) have already happened at the call site.
     */
    static String buildDeleteSuccessResult(IbIdentity id, boolean deleteRegistration,
            DbFileOutcome db)
    {
        return applyDatabaseFilesOutcome(ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(McpKeys.PROJECT, id.projectName)
            .put(McpKeys.APPLICATION_ID, id.resolvedId)
            .put(KEY_INFOBASE_NAME, id.resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration), db)
            .put(McpKeys.MESSAGE, "Infobase '" + id.resolvedName //$NON-NLS-1$
                + "' removed from project '" + id.projectName + "'" //$NON-NLS-1$ //$NON-NLS-2$
                + (deleteRegistration ? " and deregistered from the EDT infobases list." //$NON-NLS-1$
                    : " (EDT infobases list entry kept).") //$NON-NLS-1$
                + databaseResultNote(db))
            .toJson();
    }

    /**
     * Holder for the project + platform services resolved by {@link #resolveContext(String)}. When
     * {@code error} is non-null it carries a ready tool-result JSON and the other fields are unset;
     * otherwise {@code error} is {@code null} and the fields are populated (ibManager may be null).
     */
    private static final class DeleteContext
    {
        final String error;
        final IProject project;
        final IApplicationManager appManager;
        final IInfobaseAssociationManager assocManager;
        final IInfobaseManager ibManager;

        private DeleteContext(String error, IProject project, IApplicationManager appManager,
                IInfobaseAssociationManager assocManager, IInfobaseManager ibManager)
        {
            this.error = error;
            this.project = project;
            this.appManager = appManager;
            this.assocManager = assocManager;
            this.ibManager = ibManager;
        }

        static DeleteContext failed(String error)
        {
            return new DeleteContext(error, null, null, null, null);
        }

        static DeleteContext of(IProject project, IApplicationManager appManager,
                IInfobaseAssociationManager assocManager, IInfobaseManager ibManager)
        {
            return new DeleteContext(null, project, appManager, assocManager, ibManager);
        }
    }

    /**
     * Holder for the application resolved by
     * {@link #resolveTargetApplication(IApplicationManager, IProject, String, String, String)}. When
     * {@code error} is non-null it carries a ready tool-result JSON and {@code app} is {@code null};
     * otherwise {@code error} is {@code null} and {@code app} is the resolved application.
     */
    static final class TargetApplication
    {
        final String error;
        final IApplication app;

        private TargetApplication(String error, IApplication app)
        {
            this.error = error;
            this.app = app;
        }

        static TargetApplication failed(String error)
        {
            return new TargetApplication(error, null);
        }

        static TargetApplication of(IApplication app)
        {
            return new TargetApplication(null, app);
        }
    }

    /**
     * Holder for the file-infobase deletion resolved by
     * {@link #resolveFileDeletionPlan(IApplication, IProject, IApplicationManager, boolean,
     * boolean)}: the {@link InfobaseReference}, the resolved name / id, the on-disk database directory,
     * the shared-files guard and the deleteRegistration flag. When {@code error} is non-null it carries
     * a ready tool-result JSON (the cast-check refusal) and the other fields are unset; otherwise
     * {@code error} is {@code null} and the fields are populated ({@code ibRef} / {@code dbDir} may be
     * {@code null} as in the original inline code).
     */
    private static final class FileDeletionPlan
    {
        final String error;
        final InfobaseReference ibRef;
        final String resolvedName;
        final String resolvedId;
        final Path dbDir;
        final SharedDatabase dbShared;
        final boolean deleteRegistration;

        private FileDeletionPlan(String error, InfobaseReference ibRef, String resolvedName,
                String resolvedId, Path dbDir, SharedDatabase dbShared, boolean deleteRegistration)
        {
            this.error = error;
            this.ibRef = ibRef;
            this.resolvedName = resolvedName;
            this.resolvedId = resolvedId;
            this.dbDir = dbDir;
            this.dbShared = dbShared;
            this.deleteRegistration = deleteRegistration;
        }

        static FileDeletionPlan failed(String error)
        {
            return new FileDeletionPlan(error, null, null, null, null, SharedDatabase.NOT_SHARED,
                false);
        }

        static FileDeletionPlan of(InfobaseReference ibRef, String resolvedName, String resolvedId,
                Path dbDir, SharedDatabase dbShared, boolean deleteRegistration)
        {
            return new FileDeletionPlan(null, ibRef, resolvedName, resolvedId, dbDir,
                dbShared, deleteRegistration);
        }
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
        final IbIdentity id = new IbIdentity(projectName, resolvedId, resolvedName);

        // Resolve the standalone-server service, backing WST server, infobaseId and served-DB
        // directory (read-only: acquires services and reads paths; deletes nothing). On a
        // missing service/server it carries an error payload the caller returns as-is.
        DeletionContext deletionCtx = resolveDeletionContext(targetApp, appManager, project,
            resolvedName, resolvedId, deleteDatabaseFiles);
        if (deletionCtx.error != null)
        {
            return deletionCtx.error;
        }
        Object service = deletionCtx.service;
        Object server = deletionCtx.server;
        final String infobaseId = deletionCtx.infobaseId;
        final Path dbDir = deletionCtx.dbDir;
        final SharedDatabase dbShared = deletionCtx.dbShared;

        // --- Confirm-preview gate ---
        String preview = buildStandaloneServerPreview(confirm, id,
            deleteRegistration, deleteDatabaseFiles, dbDir, dbShared);
        if (preview != null)
        {
            return preview;
        }

        // Destructive-operation consent gate: the LAST check before the standalone server is stopped /
        // removed (run on the request thread, before the deletion Job is scheduled). On REJECT nothing
        // is mutated.
        String declined = checkDestructiveConsent(resolvedName, "This deletes standalone server '" //$NON-NLS-1$
            + resolvedName + "' (stops it, removes the WST server and its config folder)" //$NON-NLS-1$
            + (deleteDatabaseFiles ? CONSENT_DELETE_FILES_SUFFIX : ".")); //$NON-NLS-1$
        if (declined != null)
        {
            return declined;
        }

        // Capture how many applications currently carry this id, so the read-back can confirm THIS
        // deletion via a count decrease even when a same-named twin shares the id (a degenerate case).
        final int beforeCount = countAppsWithId(appManager, project, resolvedId);

        // --- Perform deletion + registry cleanup in ONE bounded background Job. deleteServer is
        // non-modal/monitor-driven; keeping the yaml cleanup in the same Job bounds it by one timeout
        // and keeps it off the request thread. ---
        final Object finalService = service;
        final Object finalServer = server;
        final Object finalModule = deletionCtx.module;
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
                        jobCleanup.set(StandaloneServerSupport.removeFromInfobaseRegistry(finalModule,
                            finalInfobaseId, monitor));
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
        McpJobs.schedule(deleteJob);

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

        String outcomeError = checkDeletionOutcome(jobError.get(), jobStatus.get(), resolvedName);
        if (outcomeError != null)
        {
            return outcomeError;
        }

        StandaloneServerSupport.RegistryCleanup cleanup = jobCleanup.get();
        Activator.logInfo("delete_infobase: standalone server '" + resolvedName //$NON-NLS-1$
            + "' deleted (registryCleanup=" + cleanup + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        // Optionally delete the served-DB files from disk (deleteServer removes only the SERVER config
        // folder, never the served database at database.path — so this is what makes "delete everything").
        boolean dbFilesDeleted = false;
        if (deleteDatabaseFiles && dbDir != null && !dbShared.keepsFiles())
        {
            dbFilesDeleted = deleteDatabaseDirBestEffort(dbDir);
        }

        // Read-back: confirm THIS deletion via a count decrease (tolerant of same-id twins).
        ReadBack removed = confirmApplicationRemoved(appManager, project, resolvedId, beforeCount);

        return buildDeletedResult(id, deleteRegistration,
            new DbFileOutcome(deleteDatabaseFiles, dbDir, dbFilesDeleted, dbShared),
            cleanup, removed);
    }

    /**
     * Resolves the standalone-server service, its backing WST server, the served-DB
     * directory and the shared-files guard (read-only — acquires services and reads paths,
     * deletes nothing). On a missing service or unresolvable server the returned context
     * carries an {@code error} JSON payload that the caller should return as-is.
     *
     * @param targetApp the application being deleted
     * @param appManager the application manager (for the shared-files guard)
     * @param project the owning project
     * @param resolvedName the application name (for error messages)
     * @param resolvedId the application id (for error messages)
     * @param deleteDatabaseFiles whether the caller intends to delete the served DB files
     *     (the shared-files guard is only computed when true)
     * @return a {@link DeletionContext}; its {@code error} is non-null only on a resolution
     *     failure
     */
    private DeletionContext resolveDeletionContext(IApplication targetApp,
        IApplicationManager appManager, IProject project, String resolvedName, String resolvedId,
        boolean deleteDatabaseFiles)
    {
        DeletionContext ctx = new DeletionContext();

        // Resolve the standalone-server service reflectively (no Require-Bundle on the optional feature).
        Object service = StandaloneServerSupport.acquireService();
        if (service == null)
        {
            ctx.error = ToolResult.error("Standalone-server service is not available; the EDT " //$NON-NLS-1$
                + "standalone-server feature is missing. Cannot delete server application '" //$NON-NLS-1$
                + resolvedName + "'.").toJson(); //$NON-NLS-1$
            return ctx;
        }

        // Resolve the backing WST IServer: direct IServerApplication.getServer(), else a name scan.
        Object server = StandaloneServerSupport.serverOfApplication(targetApp);
        if (server == null)
        {
            server = StandaloneServerSupport.findServerByModuleName(service, resolvedName);
        }
        if (server == null)
        {
            ctx.error = ToolResult.error("Could not resolve the WST server backing application '" //$NON-NLS-1$
                + resolvedName + "' (id=" + resolvedId //$NON-NLS-1$
                + "). It may already be deleted — re-run get_applications.").toJson(); //$NON-NLS-1$
            return ctx;
        }

        // Capture the module, its infobaseId AND the served-DB directory (database.path) BEFORE
        // deletion — the module/config is torn down by deleteServer. The module + infobaseId drive the
        // yaml cleanup (value-identity first, raw-id key fallback — the map key scheme differs per EDT
        // version, see StandaloneServerSupport.removeFromInfobaseRegistry); the DB directory is used
        // only when deleteDatabaseFiles=true (deleteServer itself never deletes it).
        Object module = StandaloneServerSupport.moduleOfApplication(targetApp);
        final String infobaseId = module != null ? StandaloneServerSupport.infobaseIdOf(module) : null;
        final String dbDirStr = module != null ? StandaloneServerSupport.databaseDirOf(module) : null;
        final Path dbDir = (dbDirStr != null && !dbDirStr.isEmpty()) ? Paths.get(dbDirStr) : null;
        // A server's served DB is normally dedicated, but EDT does not forbid another project from
        // registering the same directory as a FILE infobase — so apply the same shared-files guard.
        final SharedDatabase dbShared = deleteDatabaseFiles && dbDir != null
            ? isSharedWithOtherProjects(appManager, project, dbDir) : SharedDatabase.NOT_SHARED;

        ctx.service = service;
        ctx.server = server;
        ctx.module = module;
        ctx.infobaseId = infobaseId;
        ctx.dbDir = dbDir;
        ctx.dbShared = dbShared;
        return ctx;
    }

    /**
     * Builds the confirm-preview payload for a standalone-server deletion (pure string
     * building — no side effects). Returns {@code null} when {@code confirm} is true, so
     * the caller proceeds to the real deletion.
     *
     * @return the preview JSON payload, or {@code null} when {@code confirm} is true
     */
    static String buildStandaloneServerPreview(boolean confirm, IbIdentity id,
        boolean deleteRegistration, boolean deleteDatabaseFiles, Path dbDir,
        SharedDatabase dbShared)
    {
        if (confirm)
        {
            return null;
        }
        return applyProspectiveDatabaseFilesKept(ToolResult.success()
            .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
            .put(KEY_CONFIRMATION_REQUIRED, true)
            .put(KEY_APPLICATION_KIND, "standaloneServer") //$NON-NLS-1$
            .put(McpKeys.PROJECT, id.projectName)
            .put(McpKeys.APPLICATION_ID, id.resolvedId)
            .put(KEY_INFOBASE_NAME, id.resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration), deleteDatabaseFiles, dbDir, dbShared)
            .put(McpKeys.MESSAGE, "PREVIEW: this would delete standalone server '" + id.resolvedName //$NON-NLS-1$
                + "' (stop it, remove the WST server and its server config folder)" //$NON-NLS-1$
                + (deleteRegistration ? " AND clean its infobases.yaml registry entry" //$NON-NLS-1$
                    : " (infobases.yaml entry kept)") //$NON-NLS-1$
                + databasePreviewNote(deleteDatabaseFiles, dbDir, dbShared)
                + " for project '" + id.projectName //$NON-NLS-1$
                + "'. This is irreversible. Re-call with confirm=true to apply.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Inspects the background deletion Job's outcome (read-only — only reads the captured
     * error/status and logs). Returns an error JSON payload when the Job threw or the
     * platform reported a non-OK / missing status, or {@code null} when the server was
     * cleanly deleted.
     *
     * @param jobError the exception the Job captured, if any
     * @param status the {@link IStatus} the Job captured, if any
     * @param resolvedName the application name (for messages)
     * @return an error payload, or {@code null} when the deletion succeeded cleanly
     */
    private String checkDeletionOutcome(Exception jobError, IStatus status, String resolvedName)
    {
        if (jobError != null)
        {
            Activator.logError("delete_infobase: standalone-server deletion failed for " //$NON-NLS-1$
                + resolvedName, jobError);
            return ToolResult.error("Standalone-server deletion failed for '" + resolvedName //$NON-NLS-1$
                + "': " + jobError.getMessage()).toJson(); //$NON-NLS-1$
        }
        if (status == null || !status.isOK())
        {
            // null = deleteServer returned a non-IStatus (reflective miss); non-OK = the platform
            // reported a failure. Either way the server was NOT cleanly deleted — report an error.
            String detail = status != null ? status.getMessage() : "no status returned"; //$NON-NLS-1$
            Activator.logError("delete_infobase: deleteServer did not succeed: " + detail, null); //$NON-NLS-1$
            return ToolResult.error("Standalone-server deletion did not complete cleanly for '" //$NON-NLS-1$
                + resolvedName + "': " + detail).toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Builds the success payload for a completed standalone-server deletion (pure string
     * building — no side effects).
     *
     * @return the success JSON payload
     */
    static String buildDeletedResult(IbIdentity id, boolean deleteRegistration, DbFileOutcome db,
        StandaloneServerSupport.RegistryCleanup cleanup, ReadBack removed)
    {
        return applyDatabaseFilesOutcome(ToolResult.success()
            .put(McpKeys.ACTION, VAL_DELETED)
            .put(KEY_APPLICATION_KIND, "standaloneServer") //$NON-NLS-1$
            .put(McpKeys.PROJECT, id.projectName)
            .put(McpKeys.APPLICATION_ID, id.resolvedId)
            .put(KEY_INFOBASE_NAME, id.resolvedName)
            .put(KEY_DELETE_REGISTRATION, deleteRegistration), db)
            .put(McpKeys.MESSAGE, "Standalone server '" + id.resolvedName //$NON-NLS-1$
                + "' deleted from project '" + id.projectName //$NON-NLS-1$
                + "' (server stopped, WST server and its server config folder removed)" //$NON-NLS-1$
                + (deleteRegistration ? registryNote(cleanup)
                    : " (infobases.yaml registry entry kept).") //$NON-NLS-1$
                + databaseResultNote(db)
                + readBackNote(removed))
            .toJson();
    }

    /**
     * The tail naming what the post-deletion read-back established. An {@link ReadBack#UNREADABLE}
     * read-back gets its own sentence: it is not a confirmation, and it is not evidence that the
     * application is still there either.
     */
    static String readBackNote(ReadBack readBack)
    {
        switch (readBack)
        {
            case STILL_LISTED:
                return " NOTE: it may still appear in get_applications briefly."; //$NON-NLS-1$
            case UNREADABLE:
                return " NOTE: the removal could not be CONFIRMED — the application list could not " //$NON-NLS-1$
                    + "be read back (see the EDT log). The deletion itself reported success; check " //$NON-NLS-1$
                    + "with get_applications."; //$NON-NLS-1$
            default:
                return ""; //$NON-NLS-1$
        }
    }

    /**
     * Holder for {@link #resolveDeletionContext}: the resolved service, WST server, live
     * {@code StandaloneServerInfobase} module (the value-identity target of the infobases.yaml
     * cleanup), infobaseId, served-DB directory and shared-files guard, or an {@code error}
     * payload the caller returns as-is.
     */
    private static class DeletionContext
    {
        Object service;
        Object server;
        Object module;
        String infobaseId;
        Path dbDir;
        SharedDatabase dbShared;
        String error;
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
     * What the post-deletion read-back established. Three states, not two: a read that could not be
     * performed says NOTHING about the application, and reporting it as a confirmation is how a
     * destructive tool comes to claim an outcome nobody measured.
     */
    enum ReadBack
    {
        /** The application is gone (or one of several same-id twins is). */
        CONFIRMED,
        /** The count was read every time and never dropped — it is still listed. */
        STILL_LISTED,
        /** The list could not be read (deadline or platform failure), so nothing was established. */
        UNREADABLE
    }

    /** The count of a list that could not be read — never a measured zero. */
    static final int COUNT_UNKNOWN = -1;

    /**
     * Bounded re-poll that confirms THIS standalone-server deletion took effect, by waiting until the
     * number of applications carrying {@code appId} drops below {@code beforeCount} (absorbs the
     * provision-delegate listener race). Counting (rather than presence) makes it correct even when a
     * same-named twin server shares the id: deleting one of two twins is confirmed when the count goes
     * 2 -> 1.
     *
     * <p>An unreadable count is {@link ReadBack#UNREADABLE}, never a confirmation — #622 gave the
     * read a 30 s deadline on exactly the wedge it exists for, so "could not read" is now a likely
     * outcome and answering it with "removed" would be a claim about an unasked question.
     *
     * <p>A {@code beforeCount} of {@link #COUNT_UNKNOWN} is likewise not a baseline: a twin going
     * 2 -> 1 cannot be recognised against it, so anything other than a measured zero is
     * {@link ReadBack#UNREADABLE} rather than a false "still listed".
     */
    static ReadBack confirmApplicationRemoved(IApplicationManager appManager,
            IProject project, String appId, int beforeCount)
    {
        for (int poll = 0; poll < READ_BACK_MAX_POLLS; poll++)
        {
            int now = countAppsWithId(appManager, project, appId);
            if (now == COUNT_UNKNOWN)
            {
                return ReadBack.UNREADABLE;
            }
            if (now == 0 || (beforeCount != COUNT_UNKNOWN && now < beforeCount))
            {
                return ReadBack.CONFIRMED;
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
                    return ReadBack.UNREADABLE;
                }
            }
        }
        // Every poll read a count, and it never dropped. With no baseline to compare against, a
        // non-zero count is not evidence that the deletion failed either.
        return beforeCount == COUNT_UNKNOWN ? ReadBack.UNREADABLE : ReadBack.STILL_LISTED;
    }

    /**
     * Counts the project's applications whose id equals {@code appId}. Returns {@link #COUNT_UNKNOWN}
     * if the application list could not be read.
     *
     * <p>BOUNDED (#622). An expired deadline is one more way of not being able to read, so it takes
     * the same {@link #COUNT_UNKNOWN}; the caller's poll loop stops at the first one, so at most one
     * deadline is paid.
     */
    private static int countAppsWithId(IApplicationManager appManager, IProject project, String appId)
    {
        ApplicationSupport.BoundedRead<List<IApplication>> read =
            ApplicationSupport.getApplicationsBounded(appManager, project,
                ApplicationSupport.LOOKUP_TIMEOUT_MS);
        if (!read.concluded())
        {
            Activator.logError("delete_infobase: read-back could not be completed — " //$NON-NLS-1$
                + read.deadlineFailure(), null);
            return COUNT_UNKNOWN;
        }
        try
        {
            List<IApplication> apps = read.valueOrRethrow();
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
            return COUNT_UNKNOWN;
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
     * standalone (wst) server's served database. Returns {@link SharedDatabase#SHARED} when ANY
     * project OTHER than {@code currentProject} still has an application backed by the SAME on-disk
     * directory ({@code dbDir}) — in which case wiping the files would break those projects, so the
     * caller keeps the data.
     * <p>
     * A failure to enumerate is {@link SharedDatabase#UNKNOWN}, which keeps the files exactly as
     * SHARED does. The two are separated only so that the result never STATES co-ownership it did
     * not measure: "another project uses this database" and "we could not find out" lead a reader
     * to completely different next steps.
     * <p>
     * CLOSED projects are intentionally NOT skipped: {@code IApplicationManager.getApplications} resolves
     * a project's infobases/servers from WORKSPACE-level stores (the infobase association manager and the
     * standalone-server registry), NOT from the project's open resources (bytecode-verified on 2025.2), so
     * a closed project that still references the same database is visible here and must be honoured.
     * <p>
     * BOUNDED (#622) as ONE wait around the WHOLE enumeration, not per project. On a WEDGED EDT the
     * per-project reads queue behind the same platform monitor, so one bound covers them all; on a
     * healthy but large workspace they do not queue, they SUM, so the bound is also what stops a
     * big workspace from running past it. Either way it is re-run per CALL, so a preview and its
     * confirm can reach different answers — which is why an UNKNOWN preview says so.
     */
    static SharedDatabase isSharedWithOtherProjects(IApplicationManager appManager,
            IProject currentProject, Path dbDir)
    {
        if (appManager == null || dbDir == null)
        {
            return SharedDatabase.NOT_SHARED;
        }
        Path target = dbDir.toAbsolutePath().normalize();
        return sharedCheckBounded(SHARED_INFOBASE_CHECK_TIMEOUT_MS,
            () -> enumerateSharedWithOtherProjects(appManager, currentProject, target, dbDir));
    }

    /**
     * Runs one shared-infobase enumeration under a deadline and maps it onto the three states.
     *
     * <p>Split out so the mapping — and above all the UNKNOWN arm — can be driven without a
     * workspace: it is the arm that decides whether a DESTRUCTIVE tool states co-ownership it never
     * measured.
     *
     * @param timeoutMs the bound on the whole enumeration
     * @param enumeration answers whether any OTHER project uses the same on-disk database
     * @return what the check established, never {@code null}
     */
    static SharedDatabase sharedCheckBounded(long timeoutMs,
        ApplicationSupport.IApplicationRead<Boolean> enumeration)
    {
        ApplicationSupport.BoundedRead<Boolean> read = ApplicationSupport.readBounded(
            "Check shared infobases", //$NON-NLS-1$
            "the shared-infobase check across the workspace's projects", //$NON-NLS-1$
            timeoutMs, enumeration);
        if (!read.concluded())
        {
            Activator.logError("delete_infobase: shared-infobase check did not conclude — keeping " //$NON-NLS-1$
                + "the files without establishing co-ownership: " + read.deadlineFailure(), null); //$NON-NLS-1$
            return SharedDatabase.UNKNOWN;
        }
        return Boolean.TRUE.equals(read.valueOrRethrow())
            ? SharedDatabase.SHARED : SharedDatabase.NOT_SHARED;
    }

    /**
     * What the shared-infobase check established about the on-disk database.
     *
     * <p>Three states, because {@link #SHARED} and {@link #UNKNOWN} keep the files for OPPOSITE
     * reasons: one is a measured co-owner, the other is a question that was never answered. A
     * destructive tool that reports the second as the first claims an outcome nobody measured.
     */
    enum SharedDatabase
    {
        /** Another workspace project was found using the same on-disk database. */
        SHARED,
        /** The enumeration ran and found no other project using it. */
        NOT_SHARED,
        /** The enumeration did not conclude (deadline or platform failure) — nothing established. */
        UNKNOWN;

        /** @return whether this state means the database files must be kept */
        boolean keepsFiles()
        {
            return this != NOT_SHARED;
        }
    }

    /** The enumeration {@link #isSharedWithOtherProjects} runs under one deadline. */
    private static boolean enumerateSharedWithOtherProjects(IApplicationManager appManager,
            IProject currentProject, Path target, Path dbDir)
    {
        try
        {
            for (IProject other : ProjectContext.allProjects()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
            {
                if (other == null || other.equals(currentProject))
                {
                    continue;
                }
                List<IApplication> apps;
                try // NOSONAR nested try is intentional (distinct resource/exception scopes)
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
                if (anyApplicationServesDir(apps, target, other, dbDir))
                {
                    return true;
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
     * Returns {@code true} when ANY application of {@code other} resolves to the same on-disk database
     * {@code target} (absolute, normalized) — the per-project arm of {@link #isSharedWithOtherProjects}.
     * Resolves EVERY co-owner kind: a FILE infobase OR a standalone (wst) server that serves the same
     * on-disk database — not just file infobases. {@code other} / {@code dbDir} are used only for the
     * "kept on disk" log line.
     */
    private static boolean anyApplicationServesDir(List<IApplication> apps, Path target, IProject other,
            Path dbDir)
    {
        for (IApplication app : apps)
        {
            Path otherDir = resolveApplicationDbDir(app);
            if (otherDir != null && target.equals(otherDir.toAbsolutePath().normalize()))
            {
                Activator.logInfo("delete_infobase: database '" + dbDir //$NON-NLS-1$
                    + "' is also used by project '" + other.getName() //$NON-NLS-1$
                    + "' — keeping the files on disk"); //$NON-NLS-1$
                return true;
            }
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

    /**
     * Preview-message fragment describing what deleteDatabaseFiles will (or will not) do to disk.
     * Its branches are ordered exactly like
     * {@link #prospectiveDatabaseFilesKept(boolean, Path, SharedDatabase)}, which declares the same
     * answer as a machine-readable value — keep the two in step.
     */
    static String databasePreviewNote(boolean deleteDatabaseFiles, Path dbDir,
            SharedDatabase shared)
    {
        if (!deleteDatabaseFiles)
        {
            return " (the database files on disk are KEPT)"; //$NON-NLS-1$
        }
        if (dbDir == null)
        {
            return " (deleteDatabaseFiles was requested but no database directory could be resolved)"; //$NON-NLS-1$
        }
        if (shared == SharedDatabase.SHARED)
        {
            return " (the database files would be KEPT — this infobase is still used by other projects)"; //$NON-NLS-1$
        }
        if (shared == SharedDatabase.UNKNOWN)
        {
            // Two facts the caller needs and one guess it must not be given: the files would be
            // kept, WHY they would be kept is unproven, and the confirm call runs this check AGAIN
            // — so a preview built on an unconcluded check does not bind the deletion.
            return " (the database files would be KEPT, but NOT because co-ownership was " //$NON-NLS-1$
                + "established: the shared-infobase check did not complete, so whether another " //$NON-NLS-1$
                + "project uses this database is UNKNOWN. confirm=true re-runs that check, and if " //$NON-NLS-1$
                + "it concludes then, the files may be DELETED after all — see the EDT log)"; //$NON-NLS-1$
        }
        return " AND DELETE its database files at '" + dbDir + "' from disk (IRREVERSIBLE)"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Result-message fragment describing the actual outcome of the database-files deletion. */
    static String databaseResultNote(DbFileOutcome db)
    {
        if (!db.deleteDatabaseFiles)
        {
            return " The database files on disk were kept (pass deleteDatabaseFiles=true to remove them)."; //$NON-NLS-1$
        }
        if (db.dbDir == null)
        {
            return " No database directory could be resolved, so nothing was deleted from disk."; //$NON-NLS-1$
        }
        if (db.dbShared == SharedDatabase.SHARED)
        {
            return " The database files were KEPT: this infobase is still used by other project(s) in " //$NON-NLS-1$
                + "the workspace; deleting them would break those projects."; //$NON-NLS-1$
        }
        if (db.dbShared == SharedDatabase.UNKNOWN)
        {
            // The claim this branch exists to avoid making: co-ownership as a MEASURED fact. The
            // files are kept for the same conservative reason either way, but the reader acts very
            // differently on "another project uses it" than on "we could not find out".
            return " The database files at '" + db.dbDir //$NON-NLS-1$
                + "' were KEPT because the shared-infobase check could not be completed (see the " //$NON-NLS-1$
                + "EDT log) — NOT because another project was found using them. Whether this " //$NON-NLS-1$
                + "database is shared is UNKNOWN; re-run delete_infobase once EDT is responsive, " //$NON-NLS-1$
                + "or remove the directory manually after checking it yourself."; //$NON-NLS-1$
        }
        return db.dbFilesDeleted
            ? " The database files at '" + db.dbDir + "' were deleted from disk." //$NON-NLS-1$ //$NON-NLS-2$
            : " The database files at '" + db.dbDir //$NON-NLS-1$
                + "' were NOT deleted (locked, already absent, or not a recognised infobase directory " //$NON-NLS-1$
                + "without a 1Cv8.1CD) — check/remove the directory manually."; //$NON-NLS-1$
    }
}
