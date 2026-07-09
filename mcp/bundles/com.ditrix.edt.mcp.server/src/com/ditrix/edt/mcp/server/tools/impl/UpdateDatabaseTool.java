/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Locale;
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
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.ConsentPreview;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
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

    /** Output key: whether a running 1C client was terminated to free the infobase. */
    private static final String KEY_TERMINATED_CLIENT = "terminatedClient"; //$NON-NLS-1$
    /** Output key: display name of the target application. */
    private static final String KEY_APPLICATION_NAME = "applicationName"; //$NON-NLS-1$
    /** Output key: update mode applied (FULL or INCREMENTAL). */
    private static final String KEY_UPDATE_TYPE = "updateType"; //$NON-NLS-1$
    /** Output key: application update state before the update. */
    private static final String KEY_STATE_BEFORE = "stateBefore"; //$NON-NLS-1$

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
            + "Auto-terminates any 1C client THIS EDT launched on the target infobase first to free the " //$NON-NLS-1$
            + "exclusive lock (opt out with terminateRunningClients=false). " //$NON-NLS-1$
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
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications; required if launchConfigurationName is omitted.") //$NON-NLS-1$
            .booleanProperty("fullUpdate", //$NON-NLS-1$
                "true = full reload, false = incremental (default false).") //$NON-NLS-1$
            .booleanProperty("confirm", //$NON-NLS-1$
                "true = apply the update; default false = preview only (resolves the target and " //$NON-NLS-1$
                + "reports what would change WITHOUT mutating the infobase).") //$NON-NLS-1$
            .booleanProperty("terminateRunningClients", //$NON-NLS-1$
                "Before applying, terminate any 1C client THIS EDT launched on the target infobase " //$NON-NLS-1$
                + "to free the exclusive lock (default true). false keeps a running client — the " //$NON-NLS-1$
                + "update then fails if that client holds the infobase exclusively.") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION, "Either 'preview' (nothing changed) or 'updated' (applied).") //$NON-NLS-1$
            .booleanProperty("confirmationRequired", //$NON-NLS-1$
                "true on a preview (no infobase change made); absent/false once updated.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Target application ID.") //$NON-NLS-1$
            .stringProperty(KEY_APPLICATION_NAME, "Display name of the target application.") //$NON-NLS-1$
            .stringProperty(KEY_UPDATE_TYPE, "Update mode applied: FULL or INCREMENTAL.") //$NON-NLS-1$
            .stringProperty(KEY_STATE_BEFORE, "Application update state before the update.") //$NON-NLS-1$
            .stringProperty("stateAfter", "Application update state after the update.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status message for the update.") //$NON-NLS-1$
            .booleanProperty(KEY_TERMINATED_CLIENT,
                "Present and true ONLY when an applied update (confirm=true) terminated a running " //$NON-NLS-1$
                + "client to free the infobase; absent otherwise (preview, opt-out, or no running " //$NON-NLS-1$
                + "client).") //$NON-NLS-1$
            .booleanProperty("willTerminateRunningClients", //$NON-NLS-1$
                "On a preview: whether confirm=true would first terminate a running client " //$NON-NLS-1$
                + "(reflects terminateRunningClients).") //$NON-NLS-1$
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
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        boolean fullUpdate = JsonUtils.extractBooleanArgument(params, "fullUpdate", false); //$NON-NLS-1$
        boolean confirm = JsonUtils.extractBooleanArgument(params, "confirm", false); //$NON-NLS-1$
        boolean terminateRunningClients =
            JsonUtils.extractBooleanArgument(params, "terminateRunningClients", true); //$NON-NLS-1$

        boolean hasName = configName != null && !configName.isEmpty();
        String argError = validateDirectArguments(hasName, projectName, applicationId);
        if (argError != null)
        {
            return argError;
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

        return updateDatabase(projectName, applicationId, fullUpdate, confirm,
            terminateRunningClients);
    }

    /**
     * Validates the directly supplied target arguments used when no launch
     * configuration name is given. Returns a ready {@link ToolResult#error} JSON
     * payload describing the first missing argument, or {@code null} when the
     * arguments are acceptable. When {@code hasName} is {@code true} the target is
     * derived from the launch configuration instead, so no direct argument is
     * required and {@code null} is returned.
     *
     * @param hasName whether a launch configuration name was supplied
     * @param projectName the directly supplied project name (may be {@code null})
     * @param applicationId the directly supplied application ID (may be {@code null})
     * @return error JSON when a required direct argument is missing, otherwise {@code null}
     */
    private static String validateDirectArguments(boolean hasName, String projectName,
            String applicationId)
    {
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
        return null;
    }

    /**
     * Updates the database for the specified application.
     *
     * @param projectName name of the project
     * @param applicationId ID of the application
     * @param fullUpdate true for full update, false for incremental
     * @param confirm false previews without mutating; true applies the update
     * @param terminateRunningClients true (default) frees the infobase by terminating a 1C client
     *            this EDT launched on it before the update; false leaves a running client in place
     * @return JSON string with result
     */
    private String updateDatabase(String projectName, String applicationId,
            boolean fullUpdate, boolean confirm,
            boolean terminateRunningClients)
    {
        boolean terminatedClient = false;
        try
        {
            ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
            if (!mr.ok())
            {
                return mr.errorJson();
            }
            IProject project = mr.project();
            IApplicationManager appManager = mr.manager();
            
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
                return buildPreviewResult(projectName, applicationId, application, updateType,
                    stateBefore, terminateRunningClients);
            }

            // Destructive-operation consent gate: the LAST check before the (irreversible) infobase
            // mutation and before any running client is terminated to free it. Built from the resolved
            // update plan the tool already computed; on ALLOW the behaviour is byte-identical, on REJECT
            // nothing is mutated. Headless / env-bypass / non-ASK never block.
            ConsentPreview consentPreview = new ConsentPreview(
                "Update database", //$NON-NLS-1$
                "This applies a " + updateType.name() //$NON-NLS-1$
                    + " configuration update to the database of application '" + application.getName() //$NON-NLS-1$
                    + "' (project " + projectName + "). This mutates the infobase and is irreversible.", //$NON-NLS-1$ //$NON-NLS-2$
                1, java.util.Collections.singletonList(application.getName()));
            if (DestructiveConsentGate.getInstance().requireConsent(NAME, consentPreview)
                == DestructiveConsentGate.ConsentDecision.REJECT)
            {
                return ToolResult.error("Operation declined by user").toJson(); //$NON-NLS-1$
            }

            // Create execution context with the active Shell so EDT can parent
            // its dialogs. Shared SWT-grab lives in LaunchLifecycleUtils.
            ExecutionContext context = new ExecutionContext();
            Shell shell = LaunchLifecycleUtils.grabActiveShell();
            if (shell != null)
            {
                context.setProperty(ExecutionContext.ACTIVE_SHELL_NAME, shell);
            }

            Activator.logInfo("Update database: project=" + projectName +  //$NON-NLS-1$
                    ", application=" + applicationId +  //$NON-NLS-1$
                    ", type=" + updateType); //$NON-NLS-1$

            IProgressMonitor monitor = new NullProgressMonitor();

            // Free the infobase and apply the update under the SAME per-IB lock the launch path
            // uses (LaunchLifecycleUtils.lockFor), so a concurrent run_yaxunit_tests / debug_launch
            // on this infobase cannot interleave its own terminate+update (two updates racing, or a
            // freshly-freed IB grabbed by a new client between the sweep and update()). A 1C client
            // THIS EDT launched holds the IB in exclusive use (the update fails) and caches the old
            // module version (stale code even after a successful publish); the reused sweep is
            // client-typed-thread discriminated (never a debug-server session) and exempts MCP-owned
            // launches. Runs only on confirm=true, never in preview.
            ApplicationUpdateState stateAfter;
            synchronized (LaunchLifecycleUtils.lockFor(projectName, applicationId))
            {
                if (terminateRunningClients)
                {
                    terminatedClient =
                        LaunchLifecycleUtils.ensureNoExistingClientSession(project, applicationId);
                    if (terminatedClient)
                    {
                        Activator.logInfo("Update database: terminated a running client to free the " //$NON-NLS-1$
                            + "infobase: project=" + projectName + ", application=" + applicationId); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                // EDT pops a blocking "Restructure data" / «Реорганизация информации» modal
                // (InfobaseUpdateConfirmDialog) whenever the config changes the DB structure; it
                // hangs this unattended call. Arm the restructure matcher to auto-press its default
                // "Accept" button around the update only — the confirm=true gate already approved the
                // (irreversible) update, so accepting the platform's re-prompt is the correct completion.
                LaunchUpdateDialogAutoConfirmer.arm(false, false, true);
                try
                {
                    stateAfter = appManager.update(application, updateType, context, monitor);
                }
                finally
                {
                    LaunchUpdateDialogAutoConfirmer.disarm(false, false, true);
                }
            }

            return buildUpdatedResult(projectName, applicationId, application, updateType,
                stateBefore, stateAfter, terminatedClient);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error updating database for application: " + applicationId, e); //$NON-NLS-1$
            return buildApplicationErrorResult(e, projectName, applicationId, terminatedClient);
        }
        catch (Exception e)
        {
            Activator.logError("Unexpected error during database update", e); //$NON-NLS-1$
            ToolResult errorResult = ToolResult.error("Unexpected error: " + e.getMessage()); //$NON-NLS-1$
            if (terminatedClient)
            {
                errorResult.put(KEY_TERMINATED_CLIENT, true);
            }
            return errorResult.toJson();
        }
    }

    /**
     * Builds the confirm-preview JSON (no infobase change): resolves and reports the exact
     * IRREVERSIBLE action that confirm=true would apply. Side-effect-free.
     */
    private static String buildPreviewResult(String projectName, String applicationId,
            IApplication application, ApplicationUpdateType updateType,
            ApplicationUpdateState stateBefore, boolean terminateRunningClients)
    {
        return ToolResult.success()
            .put(McpKeys.ACTION, "preview") //$NON-NLS-1$
            .put("confirmationRequired", true) //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_APPLICATION_NAME, application.getName())
            .put(KEY_UPDATE_TYPE, updateType.name())
            .put(KEY_STATE_BEFORE, stateBefore.name())
            .put("willTerminateRunningClients", terminateRunningClients) //$NON-NLS-1$
            .put(McpKeys.MESSAGE, "PREVIEW: this would apply a " + updateType.name() //$NON-NLS-1$
                + " configuration update to the database of application '" + application.getName() //$NON-NLS-1$
                + "' (project " + projectName + "). This mutates the infobase and is " //$NON-NLS-1$ //$NON-NLS-2$
                + "IRREVERSIBLE." //$NON-NLS-1$
                + (terminateRunningClients
                    ? " It will first terminate any 1C client this EDT launched on the infobase." //$NON-NLS-1$
                    : "") //$NON-NLS-1$
                + " Re-call with confirm=true to apply it.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Builds the success JSON after an applied update. terminatedClient is emitted ONLY when a
     * client was actually terminated (truthful; "swept but none / not confirmed" and opt-out are
     * indistinguishable by absence — the confirmationRequired idiom). Side-effect-free.
     */
    private static String buildUpdatedResult(String projectName, String applicationId,
            IApplication application, ApplicationUpdateType updateType,
            ApplicationUpdateState stateBefore, ApplicationUpdateState stateAfter,
            boolean terminatedClient)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, "updated") //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_APPLICATION_NAME, application.getName())
            .put(KEY_UPDATE_TYPE, updateType.name())
            .put(KEY_STATE_BEFORE, stateBefore.name())
            .put("stateAfter", stateAfter.name()); //$NON-NLS-1$
        if (terminatedClient)
        {
            result.put(KEY_TERMINATED_CLIENT, true);
        }

        // Add status message based on result
        if (stateAfter == ApplicationUpdateState.UPDATED)
        {
            result.put(McpKeys.MESSAGE, "Database updated successfully"); //$NON-NLS-1$
        }
        else if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
        {
            result.put(McpKeys.MESSAGE, "Update in progress"); //$NON-NLS-1$
        }
        else
        {
            result.put(McpKeys.MESSAGE, "Update completed with state: " + stateAfter.name()); //$NON-NLS-1$
        }

        return result.toJson();
    }

    /**
     * Builds the JSON for an {@link ApplicationException} failure. The common failure is the
     * exclusive lock: name a 1C client that still holds the infobase (an MCP-owned sibling launch
     * is exempt from the sweep, or a client outlived the terminate window) so the agent can act
     * instead of seeing a bare failure. Side-effect-free (the error is already logged by the caller).
     */
    private static String buildApplicationErrorResult(ApplicationException e, String projectName,
            String applicationId, boolean terminatedClient)
    {
        ToolResult errorResult = ToolResult.error("Database update failed: " //$NON-NLS-1$
            + e.getMessage() + describeInfobaseHolder(applicationId) + describeAuthHint(e));
        errorResult.put(McpKeys.APPLICATION_ID, applicationId);
        errorResult.put(McpKeys.PROJECT, projectName);
        if (terminatedClient)
        {
            errorResult.put(KEY_TERMINATED_CLIENT, true);
        }

        // Try to get additional error details
        if (e.getCause() != null)
        {
            errorResult.put("causeMessage", e.getCause().getMessage()); //$NON-NLS-1$
            errorResult.put("causeType", e.getCause().getClass().getSimpleName()); //$NON-NLS-1$
        }

        return errorResult.toJson();
    }

    /**
     * Best-effort hint naming a 1C client that still holds the infobase, appended to the
     * exclusive-lock failure message: an MCP-owned sibling launch (exempt from the auto-sweep)
     * or a client that outlived the terminate window. Empty string when none is resolvable, so
     * the base error message is unchanged.
     */
    private static String describeInfobaseHolder(String applicationId)
    {
        try
        {
            LaunchLifecycleUtils.ExistingClientSession holder =
                LaunchLifecycleUtils.resolveExistingClientSession(applicationId);
            if (holder != null && holder.launch != null)
            {
                String name = holder.launch.getLaunchConfiguration() != null
                    ? holder.launch.getLaunchConfiguration().getName() : "<unknown>"; //$NON-NLS-1$
                return " A 1C client still holds the infobase (launch '" + name //$NON-NLS-1$
                    + "'); if it is an MCP-owned session, stop it with terminate_launch " //$NON-NLS-1$
                    + "(force=true) and retry."; //$NON-NLS-1$
            }
        }
        catch (Exception ignore)
        {
            // best-effort hint only — never let it mask the real error
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Hint appended to the update-failure message when the failure looks like an infobase
     * connection / authentication problem (#194): the cause is a synchronization / connection /
     * authentication exception, or the message mentions a connection/auth failure. Names the
     * {@code set_infobase_credentials} tool so the caller can fix it. Detection keys off the cause
     * TYPE name (language-independent) plus English message keywords. Empty when the failure is
     * unrelated (e.g. an exclusive lock, already covered by {@link #describeInfobaseHolder}).
     *
     * @param e the application exception
     * @return the credentials hint, or an empty string
     */
    private static String describeAuthHint(ApplicationException e)
    {
        Throwable cause = e.getCause();
        String causeType = cause != null ? cause.getClass().getSimpleName() : ""; //$NON-NLS-1$
        String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        boolean likelyAuth = causeType.contains("Synchronization") //$NON-NLS-1$
            || causeType.contains("Authentication") //$NON-NLS-1$
            || causeType.contains("Connection") //$NON-NLS-1$
            || message.contains("authenticat") //$NON-NLS-1$
            || message.contains("connect"); //$NON-NLS-1$
        if (!likelyAuth)
        {
            return ""; //$NON-NLS-1$
        }
        return " If the infobase requires user authentication, set the connection credentials with " //$NON-NLS-1$
            + "set_infobase_credentials (user/password) and retry."; //$NON-NLS-1$
    }
}
