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
import com.ditrix.edt.mcp.server.utils.ApplicationUpdatePolicy;
import com.ditrix.edt.mcp.server.utils.EdtDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.ProjectContext;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.UpdateWatchdog;
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

    /** Default watchdog window for the synchronous update() call, in seconds. */
    private static final int DEFAULT_UPDATE_TIMEOUT_SECONDS = 120;

    /** Lower clamp bound for the update watchdog window. */
    private static final int MIN_UPDATE_TIMEOUT_SECONDS = 5;

    /** Upper clamp bound for the update watchdog window. */
    private static final int MAX_UPDATE_TIMEOUT_SECONDS = 600;

    /**
     * Auto-confirms a blocking update/restructurization modal during the
     * programmatic update only. {@code confirm=true} already authorised the
     * mutation, so pressing the dialog's default ("proceed") button is what a
     * careful user would do. Reuses the launch modal's localized titles; the EDT
     * restructurization dialog title (for the FULL path) is still to be confirmed
     * live and added to the shared set.
     */
    private static final EdtDialogAutoConfirmer UPDATE_DIALOG_CONFIRMER =
        new EdtDialogAutoConfirmer(LaunchUpdateDialogAutoConfirmer.APPLICATION_UPDATE_TITLES);

    /** Appended when an update may be blocked by a client this EDT did not launch. */
    private static final String EXTERNAL_SESSION_NOTE =
        "If a 1C client started OUTSIDE this EDT (Designer, a standalone 1cv8c, another EDT " //$NON-NLS-1$
            + "instance, or a server session) is holding the infobase, it is invisible to " //$NON-NLS-1$
            + "terminate_launch and must be closed manually before the update can complete."; //$NON-NLS-1$

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
            + "applicationId. Destructive/irreversible: guarded by a confirm-preview - call without " //$NON-NLS-1$
            + "confirm to preview the exact update (no infobase change), then confirm=true to apply. " //$NON-NLS-1$
            + "On confirm it transparently terminates EDT-launched 1C clients on the target " //$NON-NLS-1$
            + "infobase first (external clients must be closed manually). " //$NON-NLS-1$
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
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = apply the update; default false = preview only (resolves the target and " //$NON-NLS-1$
                + "reports what would change WITHOUT mutating the infobase).") //$NON-NLS-1$
            .booleanProperty("terminateRunningClients", //$NON-NLS-1$
                "Before updating (confirm=true), terminate any 1C client THIS EDT launched against " //$NON-NLS-1$
                + "the target infobase so the update is not blocked by an exclusive lock and the new " //$NON-NLS-1$
                + "code is not masked by a cached session (default true). External clients are not " //$NON-NLS-1$
                + "affected.") //$NON-NLS-1$
            .integerProperty("updateTimeoutSeconds", //$NON-NLS-1$
                "Watchdog window for the update call in seconds (default 120, clamped 5..600). If the " //$NON-NLS-1$
                + "update does not finish in time it keeps running in the background and the tool " //$NON-NLS-1$
                + "returns stateAfter=BEING_UPDATED so you can poll get_applications.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("action", "Either 'preview' (nothing changed) or 'updated' (applied).") //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no infobase change made); absent/false once updated.") //$NON-NLS-1$
            .stringProperty("project", "Target EDT project name.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationId", "Target application ID.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("applicationName", "Display name of the target application.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("updateType", "Update mode applied: FULL or INCREMENTAL.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateBefore", "Application update state before the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("stateAfter", "Application update state after the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("message", "Human-readable status message for the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("terminatedClients", //$NON-NLS-1$
                "Number of EDT-launched 1C clients terminated before the update.") //$NON-NLS-1$
            .booleanProperty("fullUpdateRequired", //$NON-NLS-1$
                "Present and true when the incremental update genuinely needs a full update " //$NON-NLS-1$
                + "(state FULL_UPDATE_REQUIRED); re-call with fullUpdate=true.") //$NON-NLS-1$
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
        String projectName = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$
        String applicationId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$
        boolean autoRestructure = JsonUtils.extractBooleanArgument(params, "autoRestructure", true); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean terminateRunningClients =
            JsonUtils.extractBooleanArgument(params, "terminateRunningClients", true); //$NON-NLS-1$
        int updateTimeoutSeconds = clampTimeout(
            JsonUtils.extractIntArgument(params, "updateTimeoutSeconds", DEFAULT_UPDATE_TIMEOUT_SECONDS)); //$NON-NLS-1$

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

        return updateDatabase(projectName, applicationId, fullUpdate, autoRestructure, confirm,
            terminateRunningClients, updateTimeoutSeconds);
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
            boolean fullUpdate, boolean autoRestructure, boolean confirm,
            boolean terminateRunningClients, int updateTimeoutSeconds)
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

            // Confirm-preview gate (mirrors delete_metadata): a bare call
            // resolves the target and reports the exact IRREVERSIBLE action WITHOUT touching the
            // infobase; only confirm=true actually applies it. All validation above (project open,
            // application exists, not already being updated) has run, so the preview is trustworthy.
            if (!confirm)
            {
                return ToolResult.success()
                    .put("action", "preview") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("confirmationRequired", true) //$NON-NLS-1$
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("applicationName", application.getName()) //$NON-NLS-1$
                    .put("updateType", updateType.name()) //$NON-NLS-1$
                    .put("stateBefore", stateBefore.name()) //$NON-NLS-1$
                    .put("message", "PREVIEW: this would apply a " + updateType.name() //$NON-NLS-1$ //$NON-NLS-2$
                        + " configuration update to the database of application '" + application.getName() //$NON-NLS-1$
                        + "' (project " + projectName + "). This mutates the infobase and is " //$NON-NLS-1$ //$NON-NLS-2$
                        + "IRREVERSIBLE. Re-call with confirm=true to apply it.") //$NON-NLS-1$
                    .toJson();
            }

            // confirm=true: serialise the terminate+update sequence with concurrent
            // test runs on the same IB via the shared per-IB lock.
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                // Free the infobase first: terminate any 1C client THIS EDT launched
                // against it, so the update is not blocked by an exclusive lock and the
                // new code is not masked by a cached session. External clients are
                // invisible to us (flagged below if the update stalls).
                int terminatedClients = 0;
                if (terminateRunningClients)
                {
                    ILaunchManager lm = DebugPlugin.getDefault().getLaunchManager();
                    if (lm != null)
                    {
                        int termTimeout = LaunchLifecycleUtils.getDefaultTerminateTimeoutSeconds();
                        LaunchLifecycleUtils.PreLaunchResult term =
                            LaunchLifecycleUtils.terminateLiveLaunchesForIb(lm, project,
                                applicationId, termTimeout);
                        if (!term.isOk())
                        {
                            return ToolResult.error(term.getError() + " " + EXTERNAL_SESSION_NOTE) //$NON-NLS-1$
                                .toJson();
                        }
                        terminatedClients = term.getTerminatedCount();
                    }
                }

                // Re-check the state UNDER the lock: a concurrent confirm=true call or a
                // pre-launch update on this IB could have started between the early stateBefore
                // read and here (and the watchdog releases this lock on timeout while an update
                // still runs in the background). Don't fire a second appManager.update against an
                // IB that is already updating. This fresh value is also what we report.
                ApplicationUpdateState stateBeforeLocked = appManager.getUpdateState(application);
                if (stateBeforeLocked == ApplicationUpdateState.BEING_UPDATED)
                {
                    return ToolResult.error("Application is already being updated (a concurrent " //$NON-NLS-1$
                        + "update is in progress). Poll get_applications until it settles, then " //$NON-NLS-1$
                        + "retry.").toJson(); //$NON-NLS-1$
                }

                // Create execution context with the active Shell so EDT can parent its
                // dialogs. Shared SWT-grab lives in LaunchLifecycleUtils.
                ExecutionContext context = new ExecutionContext();
                Shell shell = LaunchLifecycleUtils.grabActiveShell();
                if (shell != null)
                {
                    context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
                }

                Activator.logInfo("Update database: project=" + projectName //$NON-NLS-1$
                    + ", application=" + applicationId //$NON-NLS-1$
                    + ", type=" + updateType //$NON-NLS-1$
                    + ", autoRestructure=" + autoRestructure //$NON-NLS-1$
                    + ", terminatedClients=" + terminatedClients); //$NON-NLS-1$

                IProgressMonitor monitor = new NullProgressMonitor();

                // Auto-confirm a blocking update/restructurization modal for the ENTIRE life of
                // the update (an active session can make appManager.update pop one that would
                // otherwise hang the MCP call). The confirmer stays armed even after the watchdog
                // times out, so a long full reload that pops a modal minutes later — after we
                // already returned BEING_UPDATED — is still auto-confirmed in the background. The
                // watchdog bounds the wait so a stuck update returns BEING_UPDATED, not a hang.
                ApplicationUpdateState stateAfter = UpdateWatchdog.runWithTimeout(
                    () -> appManager.update(application, updateType, context, monitor),
                    updateTimeoutSeconds,
                    UPDATE_DIALOG_CONFIRMER::arm, UPDATE_DIALOG_CONFIRMER::disarm);

                // Single source of truth for "is this success?": INCREMENTAL_UPDATE_REQUIRED
                // after an incremental update is the expected cosmetic state for an
                // extension-bearing config (changes ARE published) and must NOT trigger a
                // needless full update; only FULL_UPDATE_REQUIRED genuinely needs one.
                ApplicationUpdatePolicy.Result verdict =
                    ApplicationUpdatePolicy.classifyExplicitUpdate(updateType, stateAfter);
                boolean ok = verdict.outcome() == ApplicationUpdatePolicy.Outcome.SUCCESS
                    || verdict.outcome() == ApplicationUpdatePolicy.Outcome.IN_PROGRESS;
                String message = verdict.outcome() == ApplicationUpdatePolicy.Outcome.IN_PROGRESS
                    ? verdict.message() + " " + EXTERNAL_SESSION_NOTE //$NON-NLS-1$
                    : verdict.message();

                // message is in the structured body on EVERY outcome (the output schema
                // declares it); ToolResult.error() additionally surfaces it in the error
                // envelope, so a NEEDS_FULL_UPDATE/FAILED caller still gets the actionable text.
                ToolResult result = ok ? ToolResult.success() : ToolResult.error(message);
                result.put("action", "updated") //$NON-NLS-1$ //$NON-NLS-2$
                    .put("project", projectName) //$NON-NLS-1$
                    .put("applicationId", applicationId) //$NON-NLS-1$
                    .put("applicationName", application.getName()) //$NON-NLS-1$
                    .put("updateType", updateType.name()) //$NON-NLS-1$
                    .put("stateBefore", stateBeforeLocked.name()) //$NON-NLS-1$
                    .put("stateAfter", stateAfter.name()) //$NON-NLS-1$
                    .put("terminatedClients", terminatedClients) //$NON-NLS-1$
                    .put("message", message); //$NON-NLS-1$
                if (verdict.outcome() == ApplicationUpdatePolicy.Outcome.NEEDS_FULL_UPDATE)
                {
                    result.put("fullUpdateRequired", true); //$NON-NLS-1$
                }
                return result.toJson();
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Database update was interrupted while waiting for it to " //$NON-NLS-1$
                + "complete. It may still be running in the background — poll get_applications.") //$NON-NLS-1$
                .toJson();
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

    /** Clamps the watchdog window to a sane range. */
    private static int clampTimeout(int seconds)
    {
        if (seconds < MIN_UPDATE_TIMEOUT_SECONDS)
        {
            return MIN_UPDATE_TIMEOUT_SECONDS;
        }
        if (seconds > MAX_UPDATE_TIMEOUT_SECONDS)
        {
            return MAX_UPDATE_TIMEOUT_SECONDS;
        }
        return seconds;
    }
}
