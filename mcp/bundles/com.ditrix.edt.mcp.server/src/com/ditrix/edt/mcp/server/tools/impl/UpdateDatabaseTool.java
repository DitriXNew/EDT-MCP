/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
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
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate;
import com.ditrix.edt.mcp.server.utils.ExternalInfobaseChangesPolicy;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.LaunchUpdateDialogAutoConfirmer;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
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
    /** Output key: EDT moved the standalone server to free ports to let the update through. */
    private static final String KEY_PORTS_REASSIGNED = "standaloneServerPortsReassigned"; //$NON-NLS-1$

    /**
     * Cap on how many {@link Throwable#getCause()} hops {@link #describeInternalInfoHint} walks,
     * guarding against a cyclical cause chain.
     */
    private static final int MAX_CAUSE_CHAIN_DEPTH = 10;

    /**
     * Cap on how many applications an ambiguity refusal spells out before it defers to
     * {@code get_applications} for the rest.
     */
    private static final int MAX_LISTED_CANDIDATES = 10;

    @Override
    public String getName()
    {
        return NAME;
    }
    
    @Override
    public String getDescription()
    {
        return "Apply the current EDT configuration to an infobase. DESTRUCTIVE - restructures data and can " //$NON-NLS-1$
            + "evict live sessions. Two-phase: call once WITHOUT confirm to preview, then again with " //$NON-NLS-1$
            + "confirm=true to apply. Parameters and examples: get_tool_guide('update_database')."; //$NON-NLS-1$
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
            .stringProperty("externalInfobaseChanges", //$NON-NLS-1$
                RunYaxunitTestsTool.EXTERNAL_INFOBASE_CHANGES_DESCRIPTION)
            .stringProperty("standaloneServerPortConflict", //$NON-NLS-1$
                StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION)
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
            .booleanProperty(KEY_PORTS_REASSIGNED,
                "Present and true ONLY when standaloneServerPortConflict=reassign was applied: " //$NON-NLS-1$
                + "EDT moved the standalone server to free ports and REWROTE its configuration, " //$NON-NLS-1$
                + "so the address its clients connect to has changed.") //$NON-NLS-1$
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
        // getUpdateState()/update() open a live connection to run the database update
        // (issue #270) — the classic case #194 introduced the auth dialog for.
        return true;
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
        String rawPolicy = JsonUtils.extractStringArgument(params, "externalInfobaseChanges"); //$NON-NLS-1$
        ExternalInfobaseChangesPolicy externalChanges = ExternalInfobaseChangesPolicy.parse(rawPolicy);
        if (externalChanges == null)
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

        boolean hasName = configName != null && !configName.isEmpty();
        String argError = validateDirectArguments(hasName, projectName, applicationId);
        if (argError != null)
        {
            return argError;
        }

        // Resolve via launch config if name is given — it fixes the project, and the
        // applicationId too when the configuration is actually bound to an application
        // (see effectiveApplicationId for the unbound case).
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
            LaunchTarget target = resolveLaunchConfigTarget(cfg, applicationId);
            if (target.errorJson != null)
            {
                return target.errorJson;
            }
            projectName = target.projectName;
            applicationId = target.applicationId;
        }

        // Refuse only the transient BUILDING state; a missing/closed project falls through
        // to the value-naming "Project not found" below.
        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        if (applicationId == null || applicationId.isEmpty())
        {
            // Only reachable through the launch-configuration branch: validateDirectArguments
            // makes applicationId mandatory in the projectName+applicationId mode. Runs AFTER
            // the BUILDING gate on purpose — enumerating the applications of a project that is
            // mid-build can observe an incomplete list, and the "exactly one" decision below
            // is only as trustworthy as that list. Guarded like updateDatabase's own body: an
            // unchecked failure from the platform must come back as this tool's JSON error, not
            // escape execute() (the protocol handler treats that as a contract violation).
            try
            {
                ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
                if (!mr.ok())
                {
                    return mr.errorJson();
                }
                ApplicationFallback fallback =
                    resolveSoleApplicationId(mr.project(), mr.manager(), projectName, configName);
                if (fallback.errorJson != null)
                {
                    return fallback.errorJson;
                }
                applicationId = fallback.applicationId;
            }
            catch (Exception e)
            {
                Activator.logError("Error deriving the update target for launch configuration " //$NON-NLS-1$
                    + configName, e);
                return ToolResult.error("Could not derive the update target of launch " //$NON-NLS-1$
                    + "configuration '" + configName + "': " + e //$NON-NLS-1$ //$NON-NLS-2$
                    + ". Pass projectName + applicationId explicitly (get_applications lists the " //$NON-NLS-1$
                    + "application ids).").toJson(); //$NON-NLS-1$
            }
        }

        return updateDatabase(projectName, applicationId, fullUpdate, confirm,
            terminateRunningClients, externalChanges, portPolicy);
    }

    /**
     * Resolves the project + application pair a named runtime-client launch configuration
     * addresses, or the actionable refusal that replaces it.
     *
     * <p>Split out of {@code execute} so the whole named-configuration decision — the type gate,
     * the attribute read, the missing-project refusal and the
     * {@link #effectiveApplicationId} merge — is reachable from a unit test with a mocked
     * {@link ILaunchConfiguration}; only the launch-manager lookup that produced {@code cfg}
     * stays behind the live platform.
     *
     * <p>The two attributes are read DIRECTLY rather than through
     * {@link LaunchConfigUtils#readAttribute}: that helper maps a read failure onto the default
     * value, and an empty applicationId no longer means "refuse" — it now unlocks the
     * project-derived fallback. An attribute this tool FAILED to read must not be mistaken for
     * one the configuration does not have, or an unreadable binding would silently become a
     * write to whatever the project resolves to.
     *
     * @param cfg the resolved launch configuration (never {@code null})
     * @param requestedApplicationId the caller's {@code applicationId} argument (may be
     *            {@code null}/empty)
     * @return the target pair, or an error JSON; the application id is empty when it must still
     *         be derived from the project
     */
    static LaunchTarget resolveLaunchConfigTarget(ILaunchConfiguration cfg,
            String requestedApplicationId)
    {
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(cfg)))
        {
            return LaunchTarget.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' is not a runtime-client config — update_database requires one."); //$NON-NLS-1$
        }
        String cfgProject;
        String cfgAppId;
        try
        {
            cfgProject = cfg.getAttribute(LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            cfgAppId = cfg.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            Activator.logError("Error reading attributes of launch configuration " //$NON-NLS-1$
                + cfg.getName(), e);
            return LaunchTarget.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' could not be read: " + e.getMessage() //$NON-NLS-1$
                + ". Fix or recreate it in EDT, or target the update directly with projectName " //$NON-NLS-1$
                + "+ applicationId (get_applications lists the application ids)."); //$NON-NLS-1$
        }
        if (cfgProject.isEmpty())
        {
            return LaunchTarget.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' has no project attribute — cannot derive update target. Bind it to a " //$NON-NLS-1$
                + "project in EDT, or target the update directly with projectName + " //$NON-NLS-1$
                + "applicationId (get_applications lists the application ids)."); //$NON-NLS-1$
        }
        return LaunchTarget.of(cfgProject, effectiveApplicationId(cfgAppId, requestedApplicationId));
    }

    /**
     * Outcome of {@link #resolveLaunchConfigTarget}: either the {@link #projectName} +
     * {@link #applicationId} pair (the id may be empty — the project still has to supply it) or
     * an {@link #errorJson} to return verbatim. Never both.
     */
    static final class LaunchTarget
    {
        final String projectName;
        final String applicationId;
        final String errorJson;

        private LaunchTarget(String projectName, String applicationId, String errorJson)
        {
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.errorJson = errorJson;
        }

        static LaunchTarget of(String projectName, String applicationId)
        {
            return new LaunchTarget(projectName, applicationId, null);
        }

        static LaunchTarget error(String message)
        {
            return new LaunchTarget(null, null, ToolResult.error(message).toJson());
        }
    }

    /**
     * Chooses the application id to update with when the target came from a launch configuration.
     *
     * <p>A configuration WITH a binding fixes the pair, as it always did: its own id wins over
     * anything the caller passed. A configuration WITHOUT one used to be an outright refusal;
     * now an explicitly supplied id is used instead (the caller named the target themselves —
     * there is nothing to guess), and only a genuinely unspecified target falls through to
     * {@link #resolveSoleApplicationId}. Mirrors {@code RunYaxunitTestsTool.deriveLaunchContext},
     * which likewise substitutes the configuration's attribute only into an EMPTY value.
     *
     * @param configApplicationId the configuration's own attribute, empty when it is unbound
     * @param requestedApplicationId the caller's {@code applicationId} argument (may be
     *            {@code null}/empty)
     * @return the id to update with, or an empty string when it must be derived from the project
     */
    static String effectiveApplicationId(String configApplicationId, String requestedApplicationId)
    {
        if (configApplicationId != null && !configApplicationId.isEmpty())
        {
            return configApplicationId;
        }
        return requestedApplicationId == null ? "" : requestedApplicationId; //$NON-NLS-1$
    }

    /**
     * Derives the update target for a runtime-client launch configuration that carries no
     * {@code ATTR_APPLICATION_ID} binding — the case {@code run_yaxunit_tests} and
     * {@code debug_launch} already survive (they fall back to the project's default
     * application through {@link LaunchLifecycleUtils#resolveDefaultApplicationId}) and this
     * tool used to refuse outright.
     *
     * <p><b>The fallback is deliberately narrower than the launch tools'.</b> A launch that
     * guesses the wrong application starts the wrong client — annoying, visible, undone by
     * closing it; the alternative there is EDT's blocking "Update infobase before launch?"
     * modal, which hangs an unattended call. This call WRITES to an infobase and cannot be
     * undone, so it only substitutes when the answer is unambiguous: the project must have
     * <b>exactly one</b> application. Anything else is refused with the candidates named, so
     * the caller (not this code) chooses which database gets updated.
     *
     * <p>The target is the one enumerated application — the list the "exactly one" decision was
     * actually made on — and {@link LaunchLifecycleUtils#resolveDefaultApplicationId} is then run
     * as a CROSS-CHECK, so an app-less configuration provably resolves to the same infobase here
     * as under the launch tools. When the project has exactly one application EDT's own
     * {@code getDefaultApplication} returns that application (it clears a stale stored default
     * and then falls through to "one application → that one"), so the two agree and the check is
     * silent; that also makes a disagreement mean the enumeration and the resolver disagree about
     * the project — the one situation in which picking either could update a database nobody
     * named, so it is refused instead. Absence of a recorded default is NOT a disagreement: the
     * single enumerated application is then the answer. The check is kept even though a stable
     * EDT cannot produce it: this bundle is compiled against one EDT version and runs on later
     * ones, and failing closed is the cheap side of that bet.
     *
     * <p>Side-effect-free with respect to the infobase and the project sources: it reads the
     * application list and asks for the default application (which can make EDT drop a stale
     * default-application preference, exactly as {@code get_applications} already does).
     *
     * @param project the resolved project (never {@code null})
     * @param appManager the resolved application manager (never {@code null})
     * @param projectName the project name to name in error messages
     * @param configName the launch configuration name to name in error messages
     * @return the resolved application id, or an actionable error JSON
     */
    static ApplicationFallback resolveSoleApplicationId(IProject project,
            IApplicationManager appManager, String projectName, String configName)
    {
        List<IApplication> applications;
        try
        {
            applications = appManager.getApplications(project);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error listing applications of project " + projectName, e); //$NON-NLS-1$
            return ApplicationFallback.error(noBindingPrefix(configName)
                + "and the applications of project '" + projectName + "' could not be listed: " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getMessage() + ". The project may still be indexing — retry in a moment, or " //$NON-NLS-1$
                + "pass projectName + applicationId explicitly (get_applications lists the " //$NON-NLS-1$
                + "application ids)."); //$NON-NLS-1$
        }
        if (applications == null || applications.isEmpty())
        {
            return ApplicationFallback.error(noBindingPrefix(configName)
                + "and project '" + projectName + "' has no applications of its own — nothing to " //$NON-NLS-1$ //$NON-NLS-2$
                + "update. Bind the configuration to an application in EDT, or create an infobase " //$NON-NLS-1$
                + "for the project. Use get_applications to see which project owns the " //$NON-NLS-1$
                + "applications: for an extension project they belong to its base configuration " //$NON-NLS-1$
                + "project, and update_database must then target THAT project."); //$NON-NLS-1$
        }
        if (applications.size() > 1)
        {
            return ApplicationFallback.error(noBindingPrefix(configName)
                + "and project '" + projectName + "' has " + applications.size() //$NON-NLS-1$ //$NON-NLS-2$
                + " applications, so the target is ambiguous — refusing to guess which database " //$NON-NLS-1$
                + "to update: " + describeCandidates(applications) //$NON-NLS-1$
                + ". Re-call with projectName='" + projectName //$NON-NLS-1$
                + "' and one of those applicationId values (get_applications lists them)."); //$NON-NLS-1$
        }
        IApplication only = applications.get(0);
        String onlyId = only == null ? null : only.getId();
        if (onlyId == null || onlyId.trim().isEmpty())
        {
            // A target is only a target if it can be named: forwarding a blank id would reach
            // the application lookup as if the caller had asked for nothing.
            return ApplicationFallback.error(noBindingPrefix(configName)
                + "and the single application of project '" + projectName //$NON-NLS-1$
                + "' reports no id — nothing to target. Pass projectName + applicationId " //$NON-NLS-1$
                + "explicitly (get_applications lists the application ids)."); //$NON-NLS-1$
        }
        String resolved = LaunchLifecycleUtils.resolveDefaultApplicationId(project, "", appManager); //$NON-NLS-1$
        if (resolved != null && !resolved.isEmpty() && !resolved.equals(onlyId))
        {
            return ApplicationFallback.error(noBindingPrefix(configName)
                + "and project '" + projectName + "' reports a single application '" //$NON-NLS-1$ //$NON-NLS-2$
                + onlyId + "' but a different default application '" + resolved //$NON-NLS-1$
                + "' — refusing to guess which database to update. Re-call with projectName='" //$NON-NLS-1$
                + projectName + "' and an explicit applicationId (get_applications lists them)."); //$NON-NLS-1$
        }
        return ApplicationFallback.of(onlyId);
    }

    /**
     * The shared opening of every "the configuration carries no application binding" refusal
     * (unlistable, none, several, unnameable, disagreeing), so all five state the same fact in
     * the same words.
     */
    private static String noBindingPrefix(String configName)
    {
        return "Launch configuration '" + configName //$NON-NLS-1$
            + "' has no applicationId attribute "; //$NON-NLS-1$
    }

    /**
     * Renders the ambiguous applications as {@code id ('name')}, capped at
     * {@link #MAX_LISTED_CANDIDATES} so a project with many infobases cannot turn one refusal
     * into an unreadable wall; the tail names {@code get_applications} for the full list.
     */
    private static String describeCandidates(List<IApplication> applications)
    {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(applications.size(), MAX_LISTED_CANDIDATES);
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            IApplication app = applications.get(i);
            sb.append(app.getId()).append(" ('").append(app.getName()).append("')"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (applications.size() > shown)
        {
            sb.append(" and ").append(applications.size() - shown) //$NON-NLS-1$
                .append(" more (get_applications lists them all)"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Outcome of {@link #resolveSoleApplicationId}: exactly one of {@link #applicationId} (the
     * derived target) and {@link #errorJson} (a ready {@code ToolResult.error} payload to
     * return verbatim) is non-{@code null}.
     */
    static final class ApplicationFallback
    {
        final String applicationId;
        final String errorJson;

        private ApplicationFallback(String applicationId, String errorJson)
        {
            this.applicationId = applicationId;
            this.errorJson = errorJson;
        }

        static ApplicationFallback of(String applicationId)
        {
            return new ApplicationFallback(applicationId, null);
        }

        static ApplicationFallback error(String message)
        {
            return new ApplicationFallback(null, ToolResult.error(message).toJson());
        }
    }

    /**
     * The extra sentence the consent preview needs when the infobase turns out to have been changed
     * OUTSIDE EDT: the configured {@code externalInfobaseChanges} policy decides what happens then,
     * and one of the answers writes the PROJECT sources rather than the infobase. Approving "an
     * irreversible infobase update" must not silently cover that.
     *
     * @param policy the policy this call runs with (may be {@code null})
     * @return a sentence to append, or an empty string when there is nothing extra to warn about
     */
    private static String externalChangesConsentNote(ExternalInfobaseChangesPolicy policy)
    {
        if (policy == ExternalInfobaseChangesPolicy.IMPORT)
        {
            return " If the infobase was changed outside EDT since the last EDT interaction, " //$NON-NLS-1$
                + "externalInfobaseChanges=import will pull those changes into the PROJECT SOURCES " //$NON-NLS-1$
                + "as well - this run can therefore modify your working tree, not only the infobase."; //$NON-NLS-1$
        }
        if (policy == ExternalInfobaseChangesPolicy.OVERRIDE)
        {
            return " If the infobase was changed outside EDT since the last EDT interaction, " //$NON-NLS-1$
                + "externalInfobaseChanges=override will DISCARD those external changes and " //$NON-NLS-1$
                + "overwrite the infobase with the project configuration."; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * The extra sentence a preview and a consent prompt need when the call may ALSO re-address the
     * standalone server. Approving "an irreversible infobase update" must not silently cover a
     * rewrite of the server configuration: that outlives this call and changes the address every
     * client of that server uses.
     *
     * @param portPolicy the policy this call runs with (may be {@code null})
     * @return a sentence to append, or an empty string when nothing extra can happen
     */
    private static String portConflictConsentNote(StandaloneServerPortConflictPolicy portPolicy)
    {
        if (portPolicy != StandaloneServerPortConflictPolicy.REASSIGN)
        {
            return ""; //$NON-NLS-1$
        }
        return " If this is a standalone-server application and its ports are busy, " //$NON-NLS-1$
            + "standaloneServerPortConflict=reassign additionally lets EDT move the server to free " //$NON-NLS-1$
            + "ports and REWRITE its configuration - the address its clients connect to then changes."; //$NON-NLS-1$
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
     * @param externalChanges how to answer EDT's "Infobase configuration changes" modal when the
     *            infobase was changed outside EDT since the last EDT interaction
     * @return JSON string with result
     */
    private String updateDatabase(String projectName, String applicationId, // NOSONAR one resolved plan, not a bag of concerns
            boolean fullUpdate, boolean confirm,
            boolean terminateRunningClients, ExternalInfobaseChangesPolicy externalChanges,
            StandaloneServerPortConflictPolicy portPolicy)
    {
        boolean terminatedClient = false;
        boolean portsReassigned = false;
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
                return ToolResult.error("Application not found: " + applicationId //$NON-NLS-1$
                        + "." + describeLaunchIdentifierHint(applicationId) //$NON-NLS-1$
                        + " Use get_applications to get valid application IDs.").toJson(); //$NON-NLS-1$
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
                    stateBefore, terminateRunningClients, externalChanges, portPolicy);
            }

            // Destructive-operation consent gate: the LAST check before the (irreversible) infobase
            // mutation and before any running client is terminated to free it. Built from the resolved
            // update plan the tool already computed; on ALLOW the behaviour is byte-identical, on REJECT
            // nothing is mutated. Headless / env-bypass / non-ASK never block.
            ConsentPreview consentPreview = new ConsentPreview(
                "Update database", //$NON-NLS-1$
                "This applies a " + updateType.name() //$NON-NLS-1$
                    + " configuration update to the database of application '" + application.getName() //$NON-NLS-1$
                    + "' (project " + projectName + "). This mutates the infobase and is irreversible." //$NON-NLS-1$ //$NON-NLS-2$
                    + externalChangesConsentNote(externalChanges)
                    + portConflictConsentNote(portPolicy),
                1, java.util.Collections.singletonList(application.getName()));
            DestructiveConsentGate.ConsentDecision consentDecision =
                DestructiveConsentGate.getInstance().requireConsent(NAME, consentPreview);
            if (consentDecision != DestructiveConsentGate.ConsentDecision.ALLOW)
            {
                return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(consentDecision, NAME)).toJson();
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
                // The same update can also raise EDT's "Infobase configuration changes" modal when
                // the infobase was changed outside EDT since the last EDT interaction; it is answered
                // by the caller's externalInfobaseChanges policy (default: override the infobase with
                // the project configuration, i.e. exactly what this tool was asked to do).
                // Reference-first: EDT's dialog names the REGISTERED infobase, which can differ
                // from the application's display name — resolving the wrong one would leave every
                // dialog unattributable and silently degrade override/import to cancel.
                String infobaseName = LaunchLifecycleUtils.conflictAttributionName(application);
                try (LaunchUpdateDialogAutoConfirmer.ConflictWatch watch =
                    LaunchUpdateDialogAutoConfirmer.beginConflictWatch(infobaseName))
                {
                    // The port matcher is armed ONLY for a standalone-server target: a file or
                    // client-server application cannot raise that modal, and an arm held for the
                    // whole of such an update would answer a dialog belonging to a concurrent (or
                    // manual) server start.
                    StandaloneServerPortConflictPolicy armedPortPolicy =
                        DebugServerTargetSupport.isServerApplicationId(applicationId)
                            ? portPolicy : null;
                    LaunchUpdateDialogAutoConfirmer.arm(false, false, true, externalChanges,
                        infobaseName, armedPortPolicy);
                    try
                    {
                        stateAfter = appManager.update(application, updateType, context, monitor);
                    }
                    catch (ApplicationException ex)
                    {
                        // FIRST, before any early return or rethrow: if "Find free port" was
                        // pressed, the server configuration is already rewritten and that stands
                        // whatever happens to the update afterwards.
                        portsReassigned = watch.portsReassigned();
                        // A standalone-server target publishes THROUGH its server, so the update
                        // starts it first; when its ports are busy EDT raises the port-conflict
                        // modal, the auto-confirmer cancels it (see LaunchUpdateDialogAutoConfirmer)
                        // and the platform reports only "User has cancelled operation." - a
                        // cancellation the caller never asked for and cannot act on. The window
                        // carries what the dialog actually said, so report THAT instead.
                        if (watch.portConflicted())
                        {
                            return portConflictError(watch, projectName, applicationId,
                                terminatedClient);
                        }
                        // The cancel can ABORT the update instead of letting it return a state: the
                        // reason is still in the window, and it explains the failure far better than
                        // EDT's own message - it names the knob that would have let it through.
                        if (watch.cancelled())
                        {
                            return ToolResult.error(ExternalInfobaseChangesPolicy.declinedUpdateError(
                                externalChanges, watch.reason())).toJson();
                        }
                        throw ex;
                    }
                    finally
                    {
                        LaunchUpdateDialogAutoConfirmer.disarm(false, false, true, externalChanges,
                            infobaseName, armedPortPolicy);
                    }
                    // Same reasoning as the catch above, for the path where the cancelled server
                    // start lets update() return a (cached, therefore meaningless) state instead
                    // of throwing: nothing was published, so this is a failure whatever it says.
                    if (watch.portConflicted())
                    {
                        return portConflictError(watch, projectName, applicationId,
                            terminatedClient);
                    }
                    // A cancelled external-changes modal means the update wrote NOTHING. Reporting
                    // "updated" here would be a false success — and the returned state cannot be
                    // used to tell the two apart: EDT may hand back a CACHED UPDATED, because the
                    // process that changed the infobase behind its back emitted no state event. The
                    // window is per-update, so a cancel recorded in it is this call's by
                    // construction and is a failure whatever the state says.
                    if (watch.cancelled())
                    {
                        return ToolResult.error(ExternalInfobaseChangesPolicy.declinedUpdateError(
                            externalChanges, watch.reason())).toJson();
                    }
                    // Read INSIDE the window (it is deregistered on close): the update succeeded,
                    // but only because EDT re-addressed the server, and the caller must be told —
                    // that is a change to their stand, not to this infobase.
                    portsReassigned = watch.portsReassigned();
                }
            }

            return buildUpdatedResult(projectName, applicationId, application, updateType,
                stateBefore, stateAfter, terminatedClient, portsReassigned);
        }
        catch (ApplicationException e)
        {
            Activator.logError("Error updating database for application: " + applicationId, e); //$NON-NLS-1$
            return buildApplicationErrorResult(e, projectName, applicationId, terminatedClient,
                portsReassigned);
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
     * The sentence appended to "Application not found" when the rejected value is one of the two
     * {@code :}-prefixed identifiers {@code list_configurations} publishes under its
     * {@code applicationId} key for a configuration whose application binding is absent or
     * unreadable
     * ({@code launch:<configName>} / {@code attach:<configName>}, minted by
     * {@link LaunchConfigUtils#getApplicationIdFor}). Carrying that value straight into this tool
     * is the natural mistake the key invites, and the bare "not found" hides it: the value is not
     * an application id at all, so no amount of re-reading {@code get_applications} explains it.
     *
     * <p>The two forms get DIFFERENT advice because only one of them has a usable route:
     * {@code launch:} names a configuration that may be the runtime-client config this tool
     * accepts, so it points at {@code launchConfigurationName}; {@code attach:} names an Attach
     * (debug-server) configuration, which {@code update_database} rejects by type, so pointing
     * there would send the caller into a second refusal.
     *
     * <p>The classification is made from the STRING alone — no configuration is looked up — so
     * the wording claims only that the value has the FORM of such an identifier. A value that
     * merely looks like one (a stale id, a hand-typed string) must not be described as something
     * {@code list_configurations} actually reported.
     *
     * <p>Deliberately tests the two prefixes rather than calling
     * {@link LaunchConfigUtils#isSyntheticApplicationId}: that predicate also matches
     * {@code ServerApplication.}, which is the prefix REAL 1C standalone-server applications carry
     * in their own id — using it would tell a caller whose server application is merely missing or
     * stale that they had not passed an application id at all. Exposed (package-private) so the
     * classification can be unit-tested directly.
     *
     * @param applicationId the id that failed to resolve (may be {@code null})
     * @return the leading-space diagnosis, or an empty string when the value is not one of the
     *         two prefixed forms (or carries no configuration name after the prefix)
     */
    static String describeLaunchIdentifierHint(String applicationId)
    {
        if (applicationId == null)
        {
            return ""; //$NON-NLS-1$
        }
        if (applicationId.startsWith(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX))
        {
            String configName =
                applicationId.substring(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX.length());
            if (configName.isEmpty())
            {
                return ""; //$NON-NLS-1$
            }
            return " That value has the form of the identifier list_configurations reports for a " //$NON-NLS-1$
                + "launch configuration whose application binding is absent or unreadable, so it " //$NON-NLS-1$
                + "is not an application id. If '" + configName + "' is a runtime-client " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration, pass it as launchConfigurationName instead."; //$NON-NLS-1$
        }
        if (applicationId.startsWith(LaunchConfigUtils.ATTACH_APP_ID_PREFIX))
        {
            String configName =
                applicationId.substring(LaunchConfigUtils.ATTACH_APP_ID_PREFIX.length());
            if (configName.isEmpty())
            {
                return ""; //$NON-NLS-1$
            }
            return " That value has the form of the identifier list_configurations reports for an " //$NON-NLS-1$
                + "Attach (debug-server) configuration ('" + configName + "'), so it is not an " //$NON-NLS-1$ //$NON-NLS-2$
                + "application id — and update_database requires a runtime-client configuration, " //$NON-NLS-1$
                + "which an Attach configuration is not."; //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Builds the failure JSON for an update whose standalone server could not start because its
     * network ports were taken (EDT's port-conflict modal, auto-cancelled by
     * {@link LaunchUpdateDialogAutoConfirmer}). Nothing was published, so this is an error, not a
     * partial success — and it names the real condition instead of the platform's bare
     * "User has cancelled operation.".
     *
     * @param watch the window that recorded the cancelled dialog
     * @param projectName the target project (echoed for the caller's context)
     * @param applicationId the target application (echoed for the caller's context)
     * @return the error payload
     */
    private static String portConflictError(LaunchUpdateDialogAutoConfirmer.ConflictWatch watch,
        String projectName, String applicationId, boolean terminatedClient)
    {
        ToolResult result = ToolResult.error("Database update failed: " //$NON-NLS-1$
            + LaunchUpdateDialogAutoConfirmer.portConflictError(watch.portConflictDetail(),
                watch.portConflictReason())
            + " The infobase was NOT changed.") //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId);
        if (terminatedClient)
        {
            // The sweep runs BEFORE the server start, so a client can already be gone when the
            // ports turn out to be busy. Silence here would leave the caller believing its session
            // survived a failed update (review of #435).
            result.put(KEY_TERMINATED_CLIENT, true);
        }
        return result.toJson();
    }

    /**
     * Builds the confirm-preview JSON (no infobase change): resolves and reports the exact
     * IRREVERSIBLE action that confirm=true would apply. Side-effect-free.
     */
    private static String buildPreviewResult(String projectName, String applicationId, // NOSONAR every value is already resolved by the caller; a parameter object would only move the list
            IApplication application, ApplicationUpdateType updateType,
            ApplicationUpdateState stateBefore, boolean terminateRunningClients,
            ExternalInfobaseChangesPolicy externalChanges,
            StandaloneServerPortConflictPolicy portPolicy)
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
                + externalChangesConsentNote(externalChanges)
                + portConflictConsentNote(portPolicy)
                + " Re-call with confirm=true to apply it.") //$NON-NLS-1$
            .toJson();
    }

    /**
     * Builds the success JSON after an applied update. terminatedClient is emitted ONLY when a
     * client was actually terminated (truthful; "swept but none / not confirmed" and opt-out are
     * indistinguishable by absence — the confirmationRequired idiom). Side-effect-free.
     */
    private static String buildUpdatedResult(String projectName, String applicationId, // NOSONAR every value is already resolved by the caller
            IApplication application, ApplicationUpdateType updateType,
            ApplicationUpdateState stateBefore, ApplicationUpdateState stateAfter,
            boolean terminatedClient, boolean portsReassigned)
    {
        ToolResult result = ToolResult.success()
            .put(McpKeys.ACTION, "updated") //$NON-NLS-1$
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_APPLICATION_NAME, application.getName())
            .put(KEY_UPDATE_TYPE, updateType.name())
            .put(KEY_STATE_BEFORE, stateBefore.name())
            // A delegate can hand back NO state at all (EDT's standalone-server delegate returns
            // whatever its server operation produced), and reading .name() off that turned a
            // platform outcome into a raw NullPointerException from this tool. Report the absence
            // as UNKNOWN — the same token the platform uses for "cannot tell".
            .put("stateAfter", stateAfter == null //$NON-NLS-1$
                ? ApplicationUpdateState.UNKNOWN.name() : stateAfter.name());
        if (terminatedClient)
        {
            result.put(KEY_TERMINATED_CLIENT, true);
        }
        if (portsReassigned)
        {
            result.put(KEY_PORTS_REASSIGNED, true);
        }

        // The re-address is stated FIRST in every message it applies to: it outlives this call
        // and changes the address clients use, so it must not be a flag the caller has to notice.
        String reassignNote = portsReassigned
            ? " NOTE: the standalone server's ports were busy, so EDT moved it to free ports and " //$NON-NLS-1$
                + "rewrote its configuration (standaloneServerPortConflict=reassign) — clients " //$NON-NLS-1$
                + "must use the new address." //$NON-NLS-1$
            : ""; //$NON-NLS-1$

        // Add status message based on result
        if (stateAfter == ApplicationUpdateState.UPDATED)
        {
            result.put(McpKeys.MESSAGE, "Database updated successfully" + reassignNote); //$NON-NLS-1$
        }
        else if (stateAfter == ApplicationUpdateState.BEING_UPDATED)
        {
            result.put(McpKeys.MESSAGE, "Update in progress" + reassignNote); //$NON-NLS-1$
        }
        else if (stateAfter == null)
        {
            // Honest about what is and is not known: the call returned without an error, but the
            // platform reported no resulting state, so "updated successfully" would be a claim
            // nothing backs. get_applications re-reads the state authoritatively.
            result.put(McpKeys.MESSAGE, "The update call returned without an error but EDT " //$NON-NLS-1$
                + "reported no resulting state; verify with get_applications (updateState) " //$NON-NLS-1$
                + "before relying on the infobase being up to date." + reassignNote); //$NON-NLS-1$
        }
        else
        {
            result.put(McpKeys.MESSAGE,
                "Update completed with state: " + stateAfter.name() + reassignNote); //$NON-NLS-1$
        }

        return result.toJson();
    }

    /**
     * Builds the JSON for an {@link ApplicationException} failure. The common failure is the
     * exclusive lock: name a 1C client that still holds the infobase (an MCP-owned sibling launch
     * is exempt from the sweep, or a client outlived the terminate window) so the agent can act
     * instead of seeing a bare failure. When the failure matches the known EDT-platform
     * {@code InternalInfo} pipeline limitation (#258), {@link #describeInternalInfoHint} takes
     * priority and {@link #describeAuthHint} is suppressed — that failure has nothing to do with
     * credentials, and appending the auth hint too would mislead the caller. Side-effect-free (the
     * error is already logged by the caller). Exposed (package-private) so the #258
     * InternalInfo-vs-auth-hint precedence can be unit-tested directly.
     */
    static String buildApplicationErrorResult(ApplicationException e, String projectName,
            String applicationId, boolean terminatedClient)
    {
        return buildApplicationErrorResult(e, projectName, applicationId, terminatedClient, false);
    }

    /**
     * Same failure payload, additionally stating that EDT had ALREADY moved the standalone server
     * to free ports before the update failed for another reason. That re-address outlives this
     * call, so it is reported on the failure path too — not only when everything worked.
     *
     * @param portsReassigned whether the server was re-addressed during this call
     */
    static String buildApplicationErrorResult(ApplicationException e, String projectName,
            String applicationId, boolean terminatedClient, boolean portsReassigned)
    {
        String internalInfoHint = describeInternalInfoHint(e);
        String hint = internalInfoHint.isEmpty() ? describeAuthHint(e) : internalInfoHint;
        // PlatformFailures, not getMessage(): EDT reports failures as IStatus and only wraps them,
        // so the exception's own message is routinely empty (a cancelled server operation) or
        // generic while the reason sits in the status tree - and "Database update failed: " with
        // nothing after it tells the caller nothing at all.
        ToolResult errorResult = ToolResult.error("Database update failed: " //$NON-NLS-1$
            + PlatformFailures.describe(e) + describeInfobaseHolder(applicationId) + hint
            + (portsReassigned
                ? " NOTE: before this failure EDT had already moved the standalone server to free " //$NON-NLS-1$
                    + "ports and rewritten its configuration " //$NON-NLS-1$
                    + "(standaloneServerPortConflict=reassign) — that change stands." //$NON-NLS-1$
                : "")); //$NON-NLS-1$
        errorResult.put(McpKeys.APPLICATION_ID, applicationId);
        errorResult.put(McpKeys.PROJECT, projectName);
        if (terminatedClient)
        {
            errorResult.put(KEY_TERMINATED_CLIENT, true);
        }
        if (portsReassigned)
        {
            errorResult.put(KEY_PORTS_REASSIGNED, true);
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

    /**
     * Hint appended to the update-failure message when the failure is the known EDT-platform
     * pipeline limitation (#258): the configuration XML that EDT itself generated for the load is
     * rejected because the {@code InternalInfo} node is missing (Russian EDT message:
     * "Отсутствует внутренняя информация (узел InternalInfo) для объекта Configuration"). This is
     * an EDT-side failure, not something the MCP call causes — the EDT GUI's "Update database
     * configuration" fails the same way on the same project.
     * <p>
     * Detection is MARKER-FIRST (issue #382): the whole chain is searched for the {@code InternalInfo}
     * marker before anything else, and only a chain without it falls back to reporting a
     * {@code ConfigurationLoadException} GENERICALLY — surfacing the platform's own message and
     * asserting no cause. The previous single pass treated the exception TYPE as proof of the
     * InternalInfo limitation, so every unrelated load failure (a malformed form attribute, for one)
     * was answered with a cause that was not there and a workaround that could not help.
     * <p>
     * Each pass walks the WHOLE cause chain (this exception plus every {@link Throwable#getCause()}
     * below it, depth-capped at {@link #MAX_CAUSE_CHAIN_DEPTH} to guard against a cycle). Empty when
     * the failure is neither; when either matches, the caller suppresses {@link #describeAuthHint}
     * (see {@link #buildApplicationErrorResult}) because a configuration-load failure has nothing to
     * do with credentials either way. Exposed (package-private) so the matching can be unit-tested
     * directly.
     *
     * @param e the application exception
     * @return the InternalInfo hint, the generic configuration-load hint, or an empty string
     */
    static String describeInternalInfoHint(ApplicationException e)
    {
        // TWO passes, and the marker pass goes FIRST. The InternalInfo limitation is identified by its
        // MARKER, never by the exception type alone: a configuration load can fail for entirely
        // different reasons (a malformed form attribute, say - issue #382), and answering every
        // ConfigurationLoadException with "the InternalInfo node is missing" states a cause that is not
        // there and sends the caller to a workaround that cannot help. Searching the whole chain for
        // the marker before falling back also stops a generic outer load exception from masking a real
        // InternalInfo failure deeper down.
        Throwable marked = findInChain(e, current -> String.valueOf(current.getMessage())
            .contains("InternalInfo")); //$NON-NLS-1$
        if (marked != null)
        {
            return " The platform rejected the configuration XML EDT generated for the load " //$NON-NLS-1$
                + "(the InternalInfo node is missing). This is an EDT-side pipeline limitation " //$NON-NLS-1$
                + "for this project - the EDT GUI 'Update database configuration' fails the same " //$NON-NLS-1$
                + "way; it is not caused by the MCP call. Workarounds: update via the platform " //$NON-NLS-1$
                + "CLI (export_configuration_to_xml, then 1cv8 DESIGNER /LoadConfigFromFiles " //$NON-NLS-1$
                + "<dir> /UpdateDBCfg), or try a newer EDT release."; //$NON-NLS-1$
        }
        Throwable loadFailure = findInChain(e,
            current -> current.getClass().getSimpleName().contains("ConfigurationLoadException")); //$NON-NLS-1$
        if (loadFailure != null)
        {
            String reported = loadFailure.getMessage();
            // Nothing is asserted about WHAT failed - only what the platform itself reported, and
            // where to look next. The platform's message may name an object, only a property, or
            // nothing at all, so promising that it "names the offending object" would be another
            // invented certainty of exactly the kind this method was fixed to stop making (#382).
            boolean hasMessage = reported != null && !reported.isEmpty();
            return " The platform rejected the configuration XML EDT generated for the load" //$NON-NLS-1$
                + (hasMessage ? ": " + reported : ".") //$NON-NLS-1$ //$NON-NLS-2$
                + " The load stops at the first thing it cannot read." //$NON-NLS-1$
                // Only point at the platform's message when there IS one - the exception can carry
                // none, and telling the caller to inspect "whatever the message names" would then
                // send them after nothing.
                + (hasMessage ? " Use get_project_errors for validation markers, and" //$NON-NLS-1$
                    + " get_metadata_details on whatever the message above points at." //$NON-NLS-1$
                    : " The platform reported no detail; use get_project_errors for validation" //$NON-NLS-1$
                        + " markers on the project."); //$NON-NLS-1$
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * The first throwable in {@code e}'s cause chain matching {@code test}, or {@code null}. Walks at
     * most {@link #MAX_CAUSE_CHAIN_DEPTH} hops, guarding against a cyclical chain.
     *
     * @param e the exception to walk from (inclusive)
     * @param test the predicate
     * @return the matching throwable, or {@code null} when none matches
     */
    private static Throwable findInChain(Throwable e, java.util.function.Predicate<Throwable> test)
    {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_CHAIN_DEPTH)
        {
            if (test.test(current))
            {
                return current;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }
}
