/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;

import com._1c.g5.v8.dt.platform.services.model.InfobaseAccess;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.ApplicationSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseAccessSupport;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.McpJobs;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Stores the <em>infobase connection credentials</em> (user/password) EDT uses
 * to authenticate the designer agent for {@code update_database} and
 * {@code launch} against an infobase that requires user authentication
 * (issue #194) — including a standalone-server ({@code wst-server}) application
 * wrapping an already-registered infobase (issue #275).
 *
 * <p>Without stored credentials the update agent is started without the infobase
 * user and fails to authenticate, popping a blocking "Configure Infobase access
 * Settings" dialog that hangs the unattended call. After this tool the headless
 * update authenticates as the given user.
 *
 * <p>These credentials select an <b>existing</b> infobase user — they do NOT
 * create users. Demo bases typically have a user with an empty password, so an
 * empty {@code password} is valid.
 *
 * <p><strong>Two consumers, two stores (issue #359).</strong> The infobase access
 * settings above are read by the designer AGENT. The 1C CLIENT a launch starts is a
 * different process and reads its user from the launch configuration's own attributes,
 * so a target given as {@code launchConfigurationName} configures BOTH — see
 * {@link #configureClient}. A target given as {@code projectName} + {@code applicationId}
 * names no launch configuration, so only the agent is configured and the success message
 * says so: otherwise a caller reads {@code success:true} and is surprised by the
 * platform's login dialog on the next {@code run_yaxunit_tests} / {@code launch}.
 *
 * <p><strong>Unattended-safety:</strong> the model work (resolve application -&gt;
 * {@link InfobaseAccessSupport#storeCredentials(IApplication, String, String, InfobaseAccess)}
 * -&gt; {@code IInfobaseAccessManager.updateSettings} -&gt; read-back display name) runs in a
 * bounded background Eclipse Job joined with a short {@link #CREDENTIALS_TIMEOUT_SECONDS}-second
 * timeout — never on the UI thread. Resolving an application can provoke EDT's background
 * application-update-state recompute, which can loop for a long time on an unbounded worker
 * thread; the bounded Job guarantees the call returns. The credentials are recorded as a success
 * the instant {@code updateSettings} commits (before the cosmetic name read-back), so a timeout
 * AFTER the commit still reports success.
 *
 * <p>A Job that outran the deadline is cancelled, but cancellation is cooperative and this one has
 * no monitor poll to honour it, so it keeps running. It therefore checks — on the writing side —
 * whether the caller has already been answered before it touches the launch configuration: a call
 * that reported a failure must not leave a user and a password behind it.
 */
public class SetInfobaseCredentialsTool implements IMcpTool
{
    public static final String NAME = "set_infobase_credentials"; //$NON-NLS-1$

    /** Bounded-Job timeout for the credential store + read-back (model work off the worker thread). */
    private static final int CREDENTIALS_TIMEOUT_SECONDS = 30;

    /** Output key: display name of the target application. */
    private static final String KEY_APPLICATION_NAME = "applicationName"; //$NON-NLS-1$
    /** Output key: stored user name. */
    private static final String KEY_USER = "user"; //$NON-NLS-1$
    /** Output key: stored access kind (INFOBASE / OS). */
    private static final String KEY_ACCESS = "access"; //$NON-NLS-1$
    /** Output key: whether a non-empty password was stored (the password itself is never returned). */
    private static final String KEY_PASSWORD_SET = "passwordSet"; //$NON-NLS-1$
    /** Output key: whether the launched CLIENT was configured too (issue #359). */
    private static final String KEY_CLIENT_CONFIGURED = "clientConfigured"; //$NON-NLS-1$

    /**
     * Stand-in "client write failed" reason for the persist-first record taken BEFORE the client
     * half runs. It only ever reaches the caller when the call ends between the agent commit and
     * the moment the client's outcome is recorded (the bounded Job's deadline), so the agent
     * credentials really are stored. The launch-configuration save is skipped once the caller has
     * been answered (see {@link #configureClient}), but the two events can interleave, so this
     * deliberately UNDER-claims: reporting a client that turns out to be configured is harmless,
     * the reverse is the bug issue #359 is about.
     */
    private static final String CLIENT_WRITE_UNFINISHED =
        "the call ended before the launch configuration's outcome was known"; //$NON-NLS-1$

    /**
     * Reason recorded when the client half is skipped because the caller has already been answered.
     * It is what keeps a call that reported a failure from mutating a launch configuration behind
     * the caller's back — see {@link #configureClient}.
     */
    private static final String CLIENT_WRITE_ABANDONED =
        "the call had already returned, so the launch configuration was left untouched"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "STORE infobase credentials (user/password) in EDT settings so update_database and " //$NON-NLS-1$
            + "launch can authenticate. The secret PERSISTS beyond this call, and addressing a " //$NON-NLS-1$
            + "launch configuration also rewrites that configuration's client authentication. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('set_infobase_credentials')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("launchConfigurationName", //$NON-NLS-1$
                "Exact runtime-client config name from list_configurations (preferred target: the " //$NON-NLS-1$
                + "only shape that also configures the launched 1C client).") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project name; required if launchConfigurationName is omitted. Configures the " //$NON-NLS-1$
                + "designer agent only - this call does not touch any launch configuration's own " //$NON-NLS-1$
                + "client-user settings.") //$NON-NLS-1$
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
            .stringProperty(KEY_APPLICATION_NAME,
                "Display name of the target application (falls back to the application ID).") //$NON-NLS-1$
            .stringProperty(KEY_USER, "Stored infobase user name.") //$NON-NLS-1$
            .stringProperty(KEY_ACCESS, "Stored access kind (INFOBASE or OS).") //$NON-NLS-1$
            .booleanProperty(KEY_PASSWORD_SET, "True when a non-empty password was stored.") //$NON-NLS-1$
            .booleanProperty(KEY_CLIENT_CONFIGURED,
                "True when THIS call also wrote the launched client's own authentication onto a " //$NON-NLS-1$
                + "launch configuration - only possible when the target was given as " //$NON-NLS-1$
                + "launchConfigurationName. False means this call did not configure the client " //$NON-NLS-1$
                + "(no configuration named, or the write failed); see message.") //$NON-NLS-1$
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

        // Reject an out-of-enum access value (the schema declares a closed enum, but a client need
        // not validate against it before sending) — a typo must not silently store a different mode.
        String accessError = InfobaseAccessSupport.accessError(access);
        if (accessError != null)
        {
            return ToolResult.error(accessError).toJson();
        }

        boolean hasName = configName != null && !configName.isEmpty();
        // Stays null for a projectName + applicationId target: there is then no launch
        // configuration in play, which is an ANSWER configureClient returns — not a skipped step.
        ILaunchConfiguration clientConfig = null;
        boolean derivedApplicationId = false;
        if (hasName)
        {
            // Resolve the project + applicationId from the launch configuration when a name was given.
            TargetResolution resolved = resolveFromLaunchConfig(configName);
            if (resolved.error() != null)
            {
                return resolved.error();
            }
            projectName = resolved.projectName();
            applicationId = resolved.applicationId();
            clientConfig = resolved.config();
            derivedApplicationId = resolved.derivedApplicationId();
        }
        else
        {
            String targetError = validateExplicitTarget(projectName, applicationId);
            if (targetError != null)
            {
                return targetError;
            }
        }

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }

        return store(projectName, applicationId, user, password, access,
            hasName ? configName : null, clientConfig, derivedApplicationId);
    }

    /**
     * Configures the launched CLIENT — when there is a launch configuration to configure.
     *
     * <p>The credentials {@link #store} writes below are the <em>infobase access settings</em>, and
     * those are read by the designer AGENT. The client 1C launches is a different process and takes
     * its user from the launch configuration's own attributes, so writing only the former leaves the
     * client popping the platform's "Infobase access" dialog at every launch — a call that reported
     * {@code success:true} while the very next {@code run_yaxunit_tests} still blocked on a login
     * prompt (issue #359).
     *
     * <p>Called on BOTH target shapes, on purpose. "No launch configuration was named" is a value
     * this method returns, not a call site that quietly does not exist, so the one place that
     * decides whether the client is configured is testable for both answers.
     *
     * <p>It runs only AFTER the agent's credentials have committed. There is no transaction across
     * EDT's secure storage and a launch configuration, so one of the two writes is always second —
     * but this way the second one's failure is REPORTED ({@code clientConfigured:false} plus the
     * reason), whereas the reverse order would leave a launch configuration silently rewritten by a
     * call that answered {@code success:false}.
     *
     * <p><strong>It also runs only while the caller is still waiting.</strong> The store Job is
     * joined with a bounded timeout; when that deadline elapses the caller is answered and
     * {@link #awaitStoreJob} cancels the Job — but cancellation is COOPERATIVE and cannot stop a
     * Job that is inside {@code getApplication}/{@code storeCredentials}. The Job therefore reaches
     * this point regardless, which is why the check lives HERE, on the side that writes: an answered
     * call must not go on to put a user and a password into a launch configuration behind the
     * caller's back. In the case the answer was an ERROR (the deadline elapsed before the agent
     * credentials committed) the flag is set long before this method runs, so the write cannot
     * happen at all; in the case the answer was the persist-first success the two can still
     * interleave, and its message already says the client's outcome was unknown.
     *
     * @param callerAnswered raised the moment the bounded join stops waiting; {@code null} is
     *     treated as "still waiting"
     * @param configName the launch configuration named as the target, or {@code null}/empty when the
     *     target was given as projectName + applicationId
     * @param config the resolved launch configuration to write to; only read when {@code configName}
     *     is non-empty
     * @param user the infobase user the client connects as (may be {@code null}/empty for OS auth)
     * @param password the user's password (may be {@code null}; an empty password is legitimate)
     * @param osAuth {@code true} to select OS authentication instead of an explicit user
     * @return {@code null} when nothing failed — including the case where no launch configuration
     *     was named and nothing was written; otherwise the reason the write failed
     */
    static String configureClient(AtomicBoolean callerAnswered, String configName,
            ILaunchConfiguration config, String user, String password, boolean osAuth)
    {
        if (configName == null || configName.isEmpty())
        {
            // projectName + applicationId target: no launch configuration exists to write to. The
            // client stays unconfigured, and buildSuccess() says so out loud rather than letting the
            // caller read "credentials stored" as "a launch will now work".
            return null;
        }
        if (callerAnswered != null && callerAnswered.get())
        {
            return CLIENT_WRITE_ABANDONED;
        }
        return LaunchConfigUtils.applyClientCredentials(config, user, password, osAuth);
    }

    /**
     * Validates that an explicit (project + applicationId) target was supplied when no launch
     * configuration name was given.
     *
     * @return an error tool-result JSON, or {@code null} when both values are present
     */
    private static String validateExplicitTarget(String projectName, String applicationId)
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
        return null;
    }

    /**
     * Resolves the target project + applicationId from a runtime-client launch configuration name.
     *
     * @return a {@link TargetResolution} carrying either the resolved project/applicationId or an
     *         error tool-result JSON
     */
    private static TargetResolution resolveFromLaunchConfig(String configName)
    {
        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        if (launchManager == null)
        {
            return TargetResolution.error(ToolResult.error("Launch manager is not available").toJson()); //$NON-NLS-1$
        }
        ILaunchConfiguration cfg = LaunchConfigUtils.findLaunchConfigByName(launchManager, configName);
        if (cfg == null)
        {
            return TargetResolution.error(ToolResult.error("Launch configuration not found: '" + configName //$NON-NLS-1$
                + "'. Use list_configurations to see what's available.").toJson()); //$NON-NLS-1$
        }
        // findLaunchConfigByName also matches Attach/debug configs, not just runtime-client ones.
        // Credentials target a runtime-client config — the same guard update_database applies — and
        // an attach config has no project or applicationId to derive from.
        if (!LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(LaunchConfigUtils.getConfigTypeId(cfg)))
        {
            return TargetResolution.error(ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' is not a runtime-client config — set_infobase_credentials requires one.").toJson()); //$NON-NLS-1$
        }
        return resolveLaunchConfigTarget(cfg, LaunchLifecycleUtils::resolveDelegateApplicationId);
    }

    /**
     * Resolves the two target attributes of a runtime-client configuration, deriving the
     * application exactly as EDT's launch delegate does when only the project was persisted.
     *
     * <p>The resolver is an argument solely to keep this decision headless-testable. Production
     * passes {@link LaunchLifecycleUtils#resolveDelegateApplicationId(ILaunchConfiguration, String)};
     * no target-resolution logic is duplicated here.
     *
     * @param cfg the configuration to inspect
     * @param applicationIdResolver the existing EDT-delegate application resolver
     * @return a resolved target or a truthful refusal naming the exact missing attribute
     */
    static TargetResolution resolveLaunchConfigTarget(ILaunchConfiguration cfg,
            BiFunction<ILaunchConfiguration, String, String> applicationIdResolver)
    {
        String cfgProject = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        String cfgAppId;
        try
        {
            // This path selects where a SECRET is written. LaunchConfigUtils.readAttribute is
            // intentionally lenient and conflates a failed read with an absent attribute, so use
            // the platform accessor directly here and preserve that distinction.
            cfgAppId = cfg.getAttribute(LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            return TargetResolution.error(ToolResult.error("The application binding could not be " //$NON-NLS-1$
                + "read from launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' — refusing to derive a credential target. Fix the configuration, or pass " //$NON-NLS-1$
                + "projectName + applicationId explicitly.").toJson()); //$NON-NLS-1$
        }
        if (cfgProject.isEmpty())
        {
            return TargetResolution.error(ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' is missing ATTR_PROJECT_NAME (read project='', applicationId='" + cfgAppId //$NON-NLS-1$
                + "') — cannot derive the target. Bind it to a project in EDT, or pass " //$NON-NLS-1$
                + "projectName + applicationId explicitly.").toJson()); //$NON-NLS-1$
        }
        if (!cfgAppId.isEmpty())
        {
            return TargetResolution.resolved(cfgProject, cfgAppId, cfg, false);
        }
        String derived = null;
        try
        {
            derived = applicationIdResolver.apply(cfg, cfgProject);
        }
        catch (Exception e) // NOSONAR resolution failure becomes the refusal below
        {
            // The resolver already owns platform access. Do not replace its decision with a
            // hand-rolled fallback, and do not let an unchecked platform failure escape the tool.
            // But do not swallow it in SILENCE either: this whole issue (#545) is about a caller
            // being told one thing while the log says another, and a bare catch here would leave a
            // platform API change looking exactly like a configuration that has no application -
            // with nothing anywhere to tell the two apart. WARNING, not ERROR: the caller's own
            // answer below is a legitimate refusal, not a server fault.
            Activator.logWarning("Could not derive the application id for launch configuration '" //$NON-NLS-1$
                + cfg.getName() + "' of project '" + cfgProject + "': " //$NON-NLS-1$ //$NON-NLS-2$
                + e.getClass().getName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
        if (!isApplicationManagerId(derived))
        {
            String returned = derived == null || derived.isEmpty()
                // Name what the id IS, not who produced it: EDT answers a synthetic
                // "launch:<config name>" for a configuration that carries no application, and no
                // application manager resolves that. Reporting it as a target would hand the caller
                // an id that fails one call later - the exact shape of failure this issue is about.
                ? "" : " EDT derived only the placeholder id '" + derived //$NON-NLS-1$ //$NON-NLS-2$
                    + "', which names no application."; //$NON-NLS-1$
            return TargetResolution.error(ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' is missing ATTR_APPLICATION_ID (read project='" + cfgProject //$NON-NLS-1$
                + "', applicationId=''); EDT could not derive a project-default application " //$NON-NLS-1$
                + "from that project." + returned + " Cannot derive the target. Bind the " //$NON-NLS-1$ //$NON-NLS-2$
                + "configuration to an application in EDT, or pass projectName + applicationId " //$NON-NLS-1$
                + "explicitly.").toJson()); //$NON-NLS-1$
        }
        return TargetResolution.resolved(cfgProject, derived, cfg, true);
    }

    /** Whether a derived id can be handed to {@code IApplicationManager.getApplication}. */
    private static boolean isApplicationManagerId(String applicationId)
    {
        return applicationId != null && !applicationId.isEmpty()
            && !applicationId.startsWith(LaunchConfigUtils.LAUNCH_APP_ID_PREFIX)
            && !applicationId.startsWith(LaunchConfigUtils.ATTACH_APP_ID_PREFIX);
    }

    /**
     * Outcome of resolving a launch-configuration name: either the resolved project + applicationId,
     * or an error tool-result JSON.
     */
    private static final class TargetResolution
    {
        private final String projectName;
        private final String applicationId;
        private final String error;
        /** The resolved configuration itself - the CLIENT's credentials are written onto it. */
        private final ILaunchConfiguration config;
        /** Whether EDT's project-default application supplied the id. */
        private final boolean derivedApplicationId;

        private TargetResolution(String projectName, String applicationId, String error,
            ILaunchConfiguration config, boolean derivedApplicationId)
        {
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.error = error;
            this.config = config;
            this.derivedApplicationId = derivedApplicationId;
        }

        static TargetResolution resolved(String projectName, String applicationId,
                ILaunchConfiguration config, boolean derivedApplicationId)
        {
            return new TargetResolution(projectName, applicationId, null, config,
                derivedApplicationId);
        }

        ILaunchConfiguration config()
        {
            return config;
        }

        static TargetResolution error(String error)
        {
            return new TargetResolution(null, null, error, null, false);
        }

        String projectName()
        {
            return projectName;
        }

        String applicationId()
        {
            return applicationId;
        }

        String error()
        {
            return error;
        }

        boolean derivedApplicationId()
        {
            return derivedApplicationId;
        }
    }

    private String store(String projectName, String applicationId, String user, String password,
            String access, String clientConfigName, ILaunchConfiguration clientConfig,
            boolean derivedApplicationId)
    {
        // Prelude on the calling thread: resolving the IApplicationManager is a cheap service lookup.
        ApplicationSupport.ManagerResult mr = ApplicationSupport.resolveManager(projectName);
        if (!mr.ok())
        {
            return mr.errorJson();
        }
        final IProject project = mr.project();
        final IApplicationManager appManager = mr.manager();
        final String finalProjectName = projectName;
        final String finalApplicationId = applicationId;
        final String finalUser = user;
        final String finalPassword = password;
        final String finalAccess = access;
        final String finalClientConfigName = clientConfigName;
        final ILaunchConfiguration finalClientConfig = clientConfig;
        final boolean finalDerivedApplicationId = derivedApplicationId;

        // The model work (getApplication -> storeCredentials -> getName) runs in a bounded background
        // Job. Resolving an application can provoke EDT's background application-update-state recompute,
        // which can loop indefinitely on an unbounded worker thread (DesignerSessionPool retries); the
        // Job + short join keeps the call unattended-safe (the UI thread is never blocked).
        final AtomicReference<String> jobResult = new AtomicReference<>();
        // Raised the moment the join stops waiting, and read by the Job before it writes the launch
        // configuration. The Job outlives the call whenever the deadline elapses (cancellation is
        // cooperative), so this is what keeps an answered - possibly FAILED - call from mutating a
        // launch configuration afterwards.
        final AtomicBoolean callerAnswered = new AtomicBoolean();

        Job storeJob = new Job("Store infobase credentials: " + finalApplicationId) //$NON-NLS-1$
        {
            @Override
            protected IStatus run(IProgressMonitor monitor)
            {
                Optional<IApplication> appOpt;
                try
                {
                    appOpt = appManager.getApplication(project, finalApplicationId);
                }
                catch (Exception e) // NOSONAR EDT application lookup — surface as an actionable error
                {
                    jobResult.set(ToolResult.error("Error resolving application '" + finalApplicationId //$NON-NLS-1$
                        + "': " + e.getMessage()).toJson()); //$NON-NLS-1$
                    return Status.OK_STATUS;
                }
                if (!appOpt.isPresent())
                {
                    jobResult.set(ToolResult.error("Application not found: " + finalApplicationId //$NON-NLS-1$
                        + ". Use get_applications to get valid application IDs.").toJson()); //$NON-NLS-1$
                    return Status.OK_STATUS;
                }
                IApplication application = appOpt.get();

                InfobaseAccess accessKind = InfobaseAccessSupport.parseAccess(finalAccess);
                String error =
                    InfobaseAccessSupport.storeCredentials(application, finalUser, finalPassword, accessKind);
                if (error != null)
                {
                    jobResult.set(ToolResult.error(error).toJson());
                    return Status.OK_STATUS;
                }

                // Persist-first: the credentials have committed (updateSettings returned null). Record the
                // success NOW, keyed on the applicationId as the display name, so a later read-back or a
                // timeout cannot lose the persisted success. The client half has not been attempted yet,
                // so this provisional record says so rather than claiming a configured client.
                boolean passwordSet = finalPassword != null && !finalPassword.isEmpty();
                String storedUser = finalUser == null ? "" : finalUser; //$NON-NLS-1$
                jobResult.set(buildSuccess(finalProjectName, finalApplicationId,
                    finalDerivedApplicationId, finalApplicationId,
                    storedUser, passwordSet, accessKind, finalClientConfigName, CLIENT_WRITE_UNFINISHED));

                // The agent half has committed, so now — and only now — the CLIENT half. Writing it
                // after the commit means a failure of the agent half leaves the launch configuration
                // untouched instead of silently rewritten by a call that answered success:false.
                String clientError = configureClient(callerAnswered, finalClientConfigName,
                    finalClientConfig, finalUser, finalPassword,
                    InfobaseAccessSupport.isOsAccess(finalAccess));
                jobResult.set(buildSuccess(finalProjectName, finalApplicationId,
                    finalDerivedApplicationId, finalApplicationId,
                    storedUser, passwordSet, accessKind, finalClientConfigName, clientError));

                // Best-effort enrich: replace the applicationId-named success with the real display name.
                try
                {
                    String name = application.getName();
                    if (name != null && !name.isEmpty())
                    {
                        jobResult.set(buildSuccess(finalProjectName, finalApplicationId,
                            finalDerivedApplicationId, name, storedUser, passwordSet, accessKind,
                            finalClientConfigName, clientError));
                    }
                }
                catch (Exception e) // NOSONAR cosmetic read-back — keep the applicationId-named success
                {
                    // The credentials are already stored; keep the success recorded above.
                }
                return Status.OK_STATUS;
            }
        };
        storeJob.setUser(false);
        storeJob.setSystem(true);
        McpJobs.schedule(storeJob);

        return awaitStoreJob(storeJob, jobResult, callerAnswered, projectName, applicationId);
    }

    /**
     * The sentence that tells the caller whether the launched CLIENT is covered as well.
     *
     * <p>The two consumers are different processes: the designer agent reads the infobase access
     * settings, the client reads its own launch-configuration attributes. Saying only "credentials
     * stored" made a caller believe a launch would now work when it still popped the platform's
     * login dialog (issue #359), so the answer names exactly what was configured.
     *
     * <p>It reports what THIS call did, not what a launch will do. A launch configuration nobody
     * touched here may already carry a user somebody set by hand, and one this call did write can
     * still fail at connect on a user that does not exist or a wrong password — so the wording says
     * "not configured by this call", never "will fail".
     *
     * @param clientConfigName the launch configuration that was updated, or {@code null} when the
     *     target was given as projectName + applicationId and no configuration was touched
     * @param clientError the reason the launch configuration could not be updated, or {@code null}
     * @return the sentence to append to the success message
     */
    static String clientNote(String clientConfigName, String clientError)
    {
        if (clientConfigName == null)
        {
            return "The launched CLIENT reads its user from a launch configuration instead, which is " //$NON-NLS-1$
                + "NOT covered here: call this tool with launchConfigurationName to configure that " //$NON-NLS-1$
                + "too, or the client will keep asking for a password at launch unless its " //$NON-NLS-1$
                + "'Client application user' section was already filled in by hand."; //$NON-NLS-1$
        }
        if (clientError != null)
        {
            return "The launch configuration '" + clientConfigName + "' could NOT be updated (" //$NON-NLS-1$ //$NON-NLS-2$
                + clientError + "), so its client user is whatever it was before - set it in the " //$NON-NLS-1$
                + "'Client application user' section by hand if the client still asks for a " //$NON-NLS-1$
                + "password."; //$NON-NLS-1$
        }
        // Deliberately says nothing about a "user": with access=OS the section carries none.
        return "The launch configuration '" + clientConfigName + "' was updated as well, so the " //$NON-NLS-1$ //$NON-NLS-2$
            + "launched client authenticates from its own settings instead of asking - as long as " //$NON-NLS-1$
            + "what was stored is valid for this infobase."; //$NON-NLS-1$
    }

    /**
     * Builds the SUCCESS tool-result JSON. The same field set (success + clientConfigured + project
     * + applicationId + applicationName + user + access + passwordSet + message) is emitted whether
     * the display name is the applicationId (persist-first) or the real read-back name, so the
     * output shape is identical across branches.
     */
    static String buildSuccess(String projectName, String applicationId, String displayName,
            String storedUser, boolean passwordSet, InfobaseAccess accessKind, String clientConfigName,
            String clientError)
    {
        return buildSuccess(projectName, applicationId, false, displayName, storedUser,
            passwordSet, accessKind, clientConfigName, clientError);
    }

    /** Same success payload, explicitly reporting a project-default application derivation. */
    static String buildSuccess(String projectName, String applicationId,
            boolean derivedApplicationId, String displayName, String storedUser, boolean passwordSet,
            InfobaseAccess accessKind, String clientConfigName, String clientError)
    {
        String derivedNote = derivedApplicationId
            ? " The launch configuration had no applicationId attribute, so EDT's project-default " //$NON-NLS-1$
                + "application '" + applicationId + "' was derived for project '" + projectName + "'." //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            : ""; //$NON-NLS-1$
        return ToolResult.success()
            .put(KEY_CLIENT_CONFIGURED, clientConfigName != null && clientError == null)
            .put(McpKeys.PROJECT, projectName)
            .put(McpKeys.APPLICATION_ID, applicationId)
            .put(KEY_APPLICATION_NAME, displayName)
            .put(KEY_USER, storedUser)
            .put(KEY_ACCESS, accessKind.getName())
            .put(KEY_PASSWORD_SET, passwordSet)
            .put(McpKeys.MESSAGE, "Stored infobase access credentials for application '" //$NON-NLS-1$
                + displayName + "' (user '" + storedUser + "', access " //$NON-NLS-1$ //$NON-NLS-2$
                + accessKind.getName() + ")." + derivedNote //$NON-NLS-1$
                + " The update agent used by update_database / " //$NON-NLS-1$
                + "launch will now authenticate with them. " //$NON-NLS-1$
                + clientNote(clientConfigName, clientError))
            .toJson();
    }

    /**
     * Joins the store Job with the bounded {@link #CREDENTIALS_TIMEOUT_SECONDS} timeout and maps the
     * outcome through the pure {@link #storeOutcome} seam: on a clean finish returns the recorded JSON;
     * on timeout cancels the Job and returns the recorded success (persist-first) or a graceful timeout
     * error; on interruption restores the interrupt flag and returns the recorded JSON (if any) or a
     * graceful interrupted error.
     *
     * <p>Every exit raises {@code callerAnswered} FIRST, before the answer is even built. A Job that
     * outran the deadline keeps running — {@link Job#cancel()} only asks it to stop, and this one has
     * no monitor poll to honour it — so the flag is the one thing that stops it from writing a launch
     * configuration for a call that has already reported a failure (see {@link #configureClient}).
     *
     * @param job the scheduled store Job
     * @param jobResult the JSON the Job records; read only after the flag is raised
     * @param callerAnswered raised here, read by the Job before the client write
     * @param projectName the target project name (for the timeout message)
     * @param applicationId the target application ID (for the timeout message)
     * @return the tool-result JSON
     */
    static String awaitStoreJob(Job job, AtomicReference<String> jobResult, AtomicBoolean callerAnswered,
            String projectName, String applicationId)
    {
        return awaitStoreJob(job, jobResult, callerAnswered, projectName, applicationId,
            TimeUnit.SECONDS.toMillis(CREDENTIALS_TIMEOUT_SECONDS));
    }

    /**
     * {@link #awaitStoreJob(Job, AtomicReference, AtomicBoolean, String, String)} with the deadline
     * spelled out, so the behaviour AT the deadline can be driven without waiting
     * {@link #CREDENTIALS_TIMEOUT_SECONDS} seconds for it. The overload above is the production
     * entry point and supplies that constant.
     *
     * @param job the scheduled store Job
     * @param jobResult the JSON the Job records; read only after the flag is raised
     * @param callerAnswered raised here, read by the Job before the client write
     * @param projectName the target project name (for the timeout message)
     * @param applicationId the target application ID (for the timeout message)
     * @param timeoutMillis how long to wait for the Job before answering without it
     * @return the tool-result JSON
     */
    static String awaitStoreJob(Job job, AtomicReference<String> jobResult, AtomicBoolean callerAnswered,
            String projectName, String applicationId, long timeoutMillis)
    {
        try
        {
            boolean finished = job.join(timeoutMillis, null);
            callerAnswered.set(true);
            if (!finished)
            {
                job.cancel();
                return storeOutcome(false, jobResult.get(), projectName, applicationId);
            }
            return storeOutcome(true, jobResult.get(), projectName, applicationId);
        }
        catch (InterruptedException e)
        {
            callerAnswered.set(true);
            job.cancel();
            Thread.currentThread().interrupt();
            return jobResult.get() != null ? jobResult.get()
                : ToolResult.error("Storing infobase credentials was interrupted.").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Pure, headless-testable seam mapping the bounded-Job outcome to the tool-result JSON. When the
     * Job recorded a result it is returned verbatim — this covers both a clean finish AND the
     * persist-first timeout case where the credentials already committed before the deadline. Otherwise
     * a graceful error is produced: a timeout message when the Job did not finish, or a "no result"
     * message when it finished without recording anything.
     *
     * @param finished whether the Job completed within the timeout budget
     * @param recordedJson the JSON the Job recorded (success or error), or {@code null} if none
     * @param projectName the target project name (for the timeout message)
     * @param applicationId the target application ID (for the timeout message)
     * @return the tool-result JSON
     */
    static String storeOutcome(boolean finished, String recordedJson, String projectName,
            String applicationId)
    {
        if (recordedJson != null)
        {
            return recordedJson;
        }
        if (!finished)
        {
            return ToolResult.error("Storing infobase credentials timed out after " //$NON-NLS-1$
                + CREDENTIALS_TIMEOUT_SECONDS + " seconds for application " + applicationId //$NON-NLS-1$
                + " in project " + projectName //$NON-NLS-1$
                + ". The credentials may not be stored; retry, or set them after the project " //$NON-NLS-1$
                + "finishes building.").toJson(); //$NON-NLS-1$
        }
        return ToolResult.error("Storing infobase credentials produced no result.").toJson(); //$NON-NLS-1$
    }
}
