/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
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
 * platform's login dialog on the next {@code run_yaxunit_tests} / {@code debug_launch}.
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
     * credentials really are stored. Cancellation is cooperative, so the launch-configuration save
     * may in fact have completed by then — this deliberately UNDER-claims: reporting a client that
     * turns out to be configured is harmless, the reverse is the bug issue #359 is about.
     */
    private static final String CLIENT_WRITE_UNFINISHED =
        "the call ended before the launch configuration's outcome was known"; //$NON-NLS-1$

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
            + "(issue #194) — also for a standalone-server application wrapping an already-" //$NON-NLS-1$
            + "registered infobase (issue #275). Selects an EXISTING infobase user (does not " //$NON-NLS-1$
            + "create users); an empty password is valid (demo bases). Target by " //$NON-NLS-1$
            + "launchConfigurationName (preferred) or projectName + applicationId (from " //$NON-NLS-1$
            + "get_applications). With launchConfigurationName the launched 1C CLIENT is " //$NON-NLS-1$
            + "configured too so it stops asking for a password (issue #359); with projectName + " //$NON-NLS-1$
            + "applicationId only the agent is - check clientConfigured in the result. " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('set_infobase_credentials')."; //$NON-NLS-1$
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
            hasName ? configName : null, clientConfig);
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
    static String configureClient(String configName, ILaunchConfiguration config, String user,
            String password, boolean osAuth)
    {
        if (configName == null || configName.isEmpty())
        {
            // projectName + applicationId target: no launch configuration exists to write to. The
            // client stays unconfigured, and buildSuccess() says so out loud rather than letting the
            // caller read "credentials stored" as "a launch will now work".
            return null;
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
        String cfgProject = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        String cfgAppId = LaunchConfigUtils.readAttribute(cfg, LaunchConfigUtils.ATTR_APPLICATION_ID, ""); //$NON-NLS-1$
        if (cfgProject.isEmpty() || cfgAppId.isEmpty())
        {
            return TargetResolution.error(ToolResult.error("Launch configuration '" + cfg.getName() //$NON-NLS-1$
                + "' has no project or applicationId attribute — cannot derive the target.").toJson()); //$NON-NLS-1$
        }
        return TargetResolution.resolved(cfgProject, cfgAppId, cfg);
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

        private TargetResolution(String projectName, String applicationId, String error,
            ILaunchConfiguration config)
        {
            this.projectName = projectName;
            this.applicationId = applicationId;
            this.error = error;
            this.config = config;
        }

        static TargetResolution resolved(String projectName, String applicationId, ILaunchConfiguration config)
        {
            return new TargetResolution(projectName, applicationId, null, config);
        }

        ILaunchConfiguration config()
        {
            return config;
        }

        static TargetResolution error(String error)
        {
            return new TargetResolution(null, null, error, null);
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
    }

    private String store(String projectName, String applicationId, String user, String password,
            String access, String clientConfigName, ILaunchConfiguration clientConfig)
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

        // The model work (getApplication -> storeCredentials -> getName) runs in a bounded background
        // Job. Resolving an application can provoke EDT's background application-update-state recompute,
        // which can loop indefinitely on an unbounded worker thread (DesignerSessionPool retries); the
        // Job + short join keeps the call unattended-safe (the UI thread is never blocked).
        final AtomicReference<String> jobResult = new AtomicReference<>();

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
                jobResult.set(buildSuccess(finalProjectName, finalApplicationId, finalApplicationId,
                    storedUser, passwordSet, accessKind, finalClientConfigName, CLIENT_WRITE_UNFINISHED));

                // The agent half has committed, so now — and only now — the CLIENT half. Writing it
                // after the commit means a failure of the agent half leaves the launch configuration
                // untouched instead of silently rewritten by a call that answered success:false.
                String clientError = configureClient(finalClientConfigName, finalClientConfig, finalUser,
                    finalPassword, InfobaseAccessSupport.isOsAccess(finalAccess));
                jobResult.set(buildSuccess(finalProjectName, finalApplicationId, finalApplicationId,
                    storedUser, passwordSet, accessKind, finalClientConfigName, clientError));

                // Best-effort enrich: replace the applicationId-named success with the real display name.
                try
                {
                    String name = application.getName();
                    if (name != null && !name.isEmpty())
                    {
                        jobResult.set(buildSuccess(finalProjectName, finalApplicationId, name, storedUser,
                            passwordSet, accessKind, finalClientConfigName, clientError));
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
        storeJob.schedule();

        return awaitStoreJob(storeJob, jobResult, projectName, applicationId);
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
                + accessKind.getName() + "). The update agent used by update_database / " //$NON-NLS-1$
                + "debug_launch will now authenticate with them. " //$NON-NLS-1$
                + clientNote(clientConfigName, clientError))
            .toJson();
    }

    /**
     * Joins the store Job with the bounded {@link #CREDENTIALS_TIMEOUT_SECONDS} timeout and maps the
     * outcome through the pure {@link #storeOutcome} seam: on a clean finish returns the recorded JSON;
     * on timeout cancels the Job and returns the recorded success (persist-first) or a graceful timeout
     * error; on interruption restores the interrupt flag and returns the recorded JSON (if any) or a
     * graceful interrupted error.
     */
    static String awaitStoreJob(Job job, AtomicReference<String> jobResult, String projectName,
            String applicationId)
    {
        try
        {
            boolean finished = job.join(TimeUnit.SECONDS.toMillis(CREDENTIALS_TIMEOUT_SECONDS), null);
            if (!finished)
            {
                job.cancel();
                return storeOutcome(false, jobResult.get(), projectName, applicationId);
            }
            return storeOutcome(true, jobResult.get(), projectName, applicationId);
        }
        catch (InterruptedException e)
        {
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
