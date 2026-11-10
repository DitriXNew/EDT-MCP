/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;

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
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionErrorProjection;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.ReadResult;
import com.ditrix.edt.mcp.server.utils.InfobaseSessionSupport.SessionInfo;
import com.ditrix.edt.mcp.server.utils.LaunchLifecycleUtils;
import com.ditrix.edt.mcp.server.utils.PlatformFailures;
import com.ditrix.edt.mcp.server.utils.ProjectStateChecker;
import com.ditrix.edt.mcp.server.utils.StandaloneServerPortConflictPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.Recovery;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.StartOutcome;
import com.ditrix.edt.mcp.server.utils.StandaloneServerStateRecovery.StopPolicy;
import com.ditrix.edt.mcp.server.utils.StandaloneServerSupport;
import com.e1c.g5.dt.applications.IApplication;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Reports, stops, starts or restarts the EDT standalone server ({@code ibsrv}) of a
 * {@code wst-server} application.
 *
 * <p>The stop is EDT's own application cleanup (a forced WST stop) run through the shared
 * per-application recovery guard, under the cross-tool infobase lock. Because EDT reports
 * {@code STOPPED} even when its own 6-second process wait gave up, the tool verifies the
 * {@code ibsrv} process exit itself. A stop installs a hold that refuses every plugin-driven start
 * of that server until {@code action=start}; see
 * {@link StandaloneServerStateRecovery#heldStoppedFailure}.
 */
public class StandaloneServerTool implements IMcpTool
{
    /** MCP tool name. */
    public static final String NAME = "standalone_server"; //$NON-NLS-1$

    static final String ACTION_STATUS = "status"; //$NON-NLS-1$
    static final String ACTION_STOP = "stop"; //$NON-NLS-1$
    static final String ACTION_START = "start"; //$NON-NLS-1$
    static final String ACTION_RESTART = "restart"; //$NON-NLS-1$
    static final String ACTION_PREVIEW = "preview"; //$NON-NLS-1$

    static final String KEY_CONFIRM = "confirm"; //$NON-NLS-1$
    static final String KEY_PORT_CONFLICT = "standaloneServerPortConflict"; //$NON-NLS-1$
    private static final String KEY_SERVER_NAME = "serverName"; //$NON-NLS-1$
    private static final String KEY_STATE = "state"; //$NON-NLS-1$
    private static final String KEY_LIVE_LAUNCH = "liveLaunch"; //$NON-NLS-1$
    private static final String KEY_PROCESS_ALIVE = "processAlive"; //$NON-NLS-1$
    private static final String KEY_PID = "pid"; //$NON-NLS-1$
    private static final String KEY_HELD_STOPPED = "heldStopped"; //$NON-NLS-1$
    private static final String KEY_HELD_SINCE = "heldSince"; //$NON-NLS-1$
    private static final String KEY_DATABASE_DIRECTORY = "databaseDirectory"; //$NON-NLS-1$
    private static final String KEY_INFOBASE_URL = "infobaseUrl"; //$NON-NLS-1$
    private static final String KEY_SESSIONS = "sessions"; //$NON-NLS-1$
    private static final String KEY_CLIENT_LAUNCHES = "clientLaunches"; //$NON-NLS-1$
    private static final String KEY_DEBUG_TARGETS = "debugTargets"; //$NON-NLS-1$
    private static final String KEY_CONFIRMATION_REQUIRED = "confirmationRequired"; //$NON-NLS-1$
    private static final String KEY_ALREADY_STOPPED = "alreadyStopped"; //$NON-NLS-1$
    private static final String KEY_ALREADY_RUNNING = "alreadyRunning"; //$NON-NLS-1$
    private static final String KEY_PORTS_REASSIGNED = "portsReassigned"; //$NON-NLS-1$
    private static final String KEY_MUTATION_COMMITTED = "mutationCommitted"; //$NON-NLS-1$
    private static final String KEY_MUTATION_OUTCOME_UNKNOWN = "mutationOutcomeUnknown"; //$NON-NLS-1$

    /** WST {@code IServer.STATE_STARTED}; the WST API is read reflectively, never imported. */
    private static final int WST_STARTED = 2;

    /** WST {@code IServer.STATE_STOPPED}. */
    private static final int WST_STOPPED = 4;

    /** Bound on resolving the target application, as {@code infobase_sessions} uses. */
    static final long APPLICATION_LOOKUP_TIMEOUT_MS = 15_000L;

    /** Test seam for the post-stop process-exit wait; production never reassigns it. */
    static volatile long processExitTimeoutMs = StandaloneServerSupport.PROCESS_EXIT_TIMEOUT_MS;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Report, stop or start the EDT standalone server (ibsrv) of a wst-server application. " //$NON-NLS-1$
            + "DESTRUCTIVE for stop/restart: it ends every session and client of that server - call " //$NON-NLS-1$
            + "once WITHOUT confirm to preview them, then with confirm=true. A stopped server stays " //$NON-NLS-1$
            + "held until action=start. Full parameters and examples: call " //$NON-NLS-1$
            + "get_tool_guide('standalone_server')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.PROJECT_NAME,
                "EDT project that owns the standalone-server application (required).", true) //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application ID from get_applications; defaults to the project's default application. " //$NON-NLS-1$
                    + "Must be a wst-server application.") //$NON-NLS-1$
            .enumProperty(McpKeys.ACTION,
                "status (default) reads the server; stop and restart end its sessions; start releases " //$NON-NLS-1$
                    + "the hold a stop leaves.", //$NON-NLS-1$
                ACTION_STATUS, ACTION_STOP, ACTION_START, ACTION_RESTART)
            .booleanProperty(KEY_CONFIRM,
                "For stop/restart: omit to preview what ends, true to do it. Refused on status/start.") //$NON-NLS-1$
            .stringProperty(KEY_PORT_CONFLICT,
                "For start/restart only. " + StandaloneServerPortConflictPolicy.PARAMETER_DESCRIPTION) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the tool call succeeded.", true) //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("error", "Human-readable failure message when success=false.") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.ACTION,
                "What this result reports: status, preview, stop, start or restart.") //$NON-NLS-1$
            .stringProperty(McpKeys.PROJECT, "Target EDT project name.") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Resolved standalone-server application ID.") //$NON-NLS-1$
            .stringProperty(KEY_SERVER_NAME, "EDT's name of the standalone server.") //$NON-NLS-1$
            .stringProperty(KEY_STATE,
                "WST server state: STARTING, STARTED, STOPPING, STOPPED or UNKNOWN.") //$NON-NLS-1$
            .booleanProperty(KEY_LIVE_LAUNCH,
                "Whether EDT still holds a live launch for the server; absent when unreadable.") //$NON-NLS-1$
            .booleanProperty(KEY_PROCESS_ALIVE,
                "Whether the ibsrv process is running; absent when no process id could be read.") //$NON-NLS-1$
            .integerProperty(KEY_PID, "Process id of the ibsrv process this result is about.") //$NON-NLS-1$
            .booleanProperty(KEY_HELD_STOPPED,
                "true while a stop holds the server: plugin-driven starts are refused until " //$NON-NLS-1$
                    + "action=start.") //$NON-NLS-1$
            .stringProperty(KEY_HELD_SINCE, "When the hold was installed (ISO-8601).") //$NON-NLS-1$
            .stringProperty(KEY_DATABASE_DIRECTORY,
                "Directory of a file-backed infobase served by this server.") //$NON-NLS-1$
            .stringProperty(KEY_INFOBASE_URL, "URL clients connect to; present while STARTED.") //$NON-NLS-1$
            .objectProperty(KEY_SESSIONS,
                "For stop/restart: the ibcmd session list read before the stop - reachable, " //$NON-NLS-1$
                    + "unreachableReason, count, designerCount and non-personal session rows.") //$NON-NLS-1$
            .integerProperty(KEY_CLIENT_LAUNCHES,
                "EDT client launches attached to the application (0 or 1).") //$NON-NLS-1$
            .integerProperty(KEY_DEBUG_TARGETS,
                "Live debug targets of the server (0 or 1).") //$NON-NLS-1$
            .booleanProperty(KEY_CONFIRMATION_REQUIRED,
                "Present with action=preview: repeat the call with confirm=true to proceed.") //$NON-NLS-1$
            .booleanProperty(KEY_ALREADY_STOPPED, "The server was already STOPPED; it is now held.") //$NON-NLS-1$
            .booleanProperty(KEY_ALREADY_RUNNING,
                "The server was already running under a live launch; nothing was started.") //$NON-NLS-1$
            .booleanProperty(KEY_PORTS_REASSIGNED,
                "true when standaloneServerPortConflict=reassign moved the server to other ports.") //$NON-NLS-1$
            .booleanProperty(KEY_MUTATION_COMMITTED, "true when the server was stopped or started.") //$NON-NLS-1$
            .booleanProperty(KEY_MUTATION_OUTCOME_UNKNOWN,
                "true when a stop or start may still be running.") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable summary and next step.") //$NON-NLS-1$
            .build();
    }

    @Override
    public ResponseType getResponseType()
    {
        return ResponseType.JSON;
    }

    @Override
    public boolean returnsInfobaseData()
    {
        // The stop preview lists live infobase sessions.
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String required = JsonUtils.requireArgument(params, McpKeys.PROJECT_NAME);
        if (required != null)
        {
            return required;
        }
        String projectName = JsonUtils.extractStringArgument(params, McpKeys.PROJECT_NAME);
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        String action = JsonUtils.extractStringArgument(params, McpKeys.ACTION);
        action = action == null || action.isBlank() ? ACTION_STATUS : action.trim().toLowerCase(Locale.ROOT);
        boolean confirm = JsonUtils.extractBooleanArgument(params, KEY_CONFIRM, false);
        String rawPortPolicy = JsonUtils.extractStringArgument(params, KEY_PORT_CONFLICT);

        String validation = validate(action, confirm, rawPortPolicy);
        if (validation != null)
        {
            return ToolResult.error(validation).toJson();
        }
        StandaloneServerPortConflictPolicy portPolicy =
            StandaloneServerPortConflictPolicy.parse(rawPortPolicy);

        String building = ProjectStateChecker.buildingErrorOrNull(projectName);
        if (building != null)
        {
            return ToolResult.error(building).toJson();
        }
        try
        {
            ApplicationSupport.ManagerResult managerResult =
                ApplicationSupport.resolveManager(projectName);
            if (!managerResult.ok())
            {
                return managerResult.errorJson();
            }
            ApplicationSupport.ApplicationResolution resolution =
                ApplicationSupport.resolveApplication(managerResult.manager(), managerResult.project(),
                    projectName, applicationId, APPLICATION_LOOKUP_TIMEOUT_MS,
                    "The standalone server was not touched"); //$NON-NLS-1$
            if (resolution.errorJson() != null)
            {
                return resolution.errorJson();
            }
            return run(new Request(projectName, action, confirm, portPolicy),
                managerResult.manager(), managerResult.project(), resolution.application(),
                Environment.PRODUCTION);
        }
        catch (Exception e)
        {
            Activator.logError("standalone_server failed for project " + projectName, e); //$NON-NLS-1$
            return ToolResult.error("standalone_server failed: " + PlatformFailures.describe(e) //$NON-NLS-1$
                + ". Check the server with standalone_server(action='status') before retrying.") //$NON-NLS-1$
                .toJson();
        }
    }

    /**
     * Validates the action-specific parameters before any platform access.
     *
     * <p>{@code stop}/{@code restart} without {@code confirm} is NOT an error: it is the preview
     * half of the preview-then-confirm protocol.
     *
     * @param action the lower-cased action
     * @param confirm the confirm flag
     * @param rawPortPolicy the raw {@code standaloneServerPortConflict} argument (may be {@code null})
     * @return the refusal, or {@code null} when the request may proceed
     */
    static String validate(String action, boolean confirm, String rawPortPolicy)
    {
        if (!ACTION_STATUS.equals(action) && !ACTION_STOP.equals(action)
            && !ACTION_START.equals(action) && !ACTION_RESTART.equals(action))
        {
            return "Unknown action: '" + action //$NON-NLS-1$
                + "'. Allowed values: status, stop, start, restart."; //$NON-NLS-1$
        }
        if (confirm && (ACTION_STATUS.equals(action) || ACTION_START.equals(action)))
        {
            return "confirm applies only to action='stop' and action='restart'."; //$NON-NLS-1$
        }
        boolean portPolicyGiven = rawPortPolicy != null && !rawPortPolicy.isBlank();
        if (portPolicyGiven && (ACTION_STATUS.equals(action) || ACTION_STOP.equals(action)))
        {
            return KEY_PORT_CONFLICT + " applies only to action='start' and action='restart'."; //$NON-NLS-1$
        }
        if (StandaloneServerPortConflictPolicy.parse(rawPortPolicy) == null)
        {
            return "Unknown " + KEY_PORT_CONFLICT + " value: '" + rawPortPolicy //$NON-NLS-1$ //$NON-NLS-2$
                + "'. Accepted values: " + StandaloneServerPortConflictPolicy.acceptedValues(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * The refusal for an application that is not a standalone server, or {@code null}.
     *
     * @param projectName the project name
     * @param application the resolved application
     * @return the refusal text, or {@code null} for a wst-server application
     */
    static String typeRefusal(String projectName, IApplication application)
    {
        if (InfobaseSessionSupport.appliesTo(application))
        {
            return null;
        }
        String typeId = application.getType() == null ? "unknown" : application.getType().getId(); //$NON-NLS-1$
        return "Application '" + application.getId() + "' in project '" + projectName //$NON-NLS-1$ //$NON-NLS-2$
            + "' is not a standalone server (type " + typeId + ", expected " //$NON-NLS-1$ //$NON-NLS-2$
            + StandaloneServerSupport.WST_SERVER_APP_TYPE + "). " + NAME //$NON-NLS-1$
            + " manages only wst-server applications; get_applications lists the types."; //$NON-NLS-1$
    }

    /**
     * Runs one validated request against an already-resolved application.
     *
     * @param request the validated request
     * @param manager the application manager
     * @param project the project owning the application
     * @param application the resolved application
     * @param env the platform reads and the consent gate (a test seam)
     * @return the serialized result
     */
    static String run(Request request, IApplicationManager manager, IProject project,
        IApplication application, Environment env)
    {
        String refusal = typeRefusal(request.projectName, application);
        if (refusal != null)
        {
            return ToolResult.error(refusal).put(McpKeys.ACTION, request.action)
                .put(McpKeys.PROJECT, request.projectName)
                .put(McpKeys.APPLICATION_ID, application.getId()).toJson();
        }
        if (ACTION_STATUS.equals(request.action))
        {
            return status(request, project, application, env);
        }
        String applicationId = application.getId();
        LaunchLifecycleUtils.LaunchLock lock =
            LaunchLifecycleUtils.lockFor(request.projectName, applicationId);
        LaunchLifecycleUtils.Acquisition acquisition =
            lock.tryAcquire(LaunchLifecycleUtils.SESSIONS_LOCK_TIMEOUT_MS, null);
        if (!acquisition.acquired())
        {
            return ToolResult.error(LaunchLifecycleUtils.lockNotAcquiredMessage(acquisition,
                request.projectName, applicationId, LaunchLifecycleUtils.SESSIONS_LOCK_TIMEOUT_MS)
                + (ACTION_START.equals(request.action) ? " The server was not started."  //$NON-NLS-1$
                    : " The server was not stopped.") //$NON-NLS-1$
                + " Wait for that operation to finish and call " + NAME + " again.").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }
        try
        {
            if (ACTION_START.equals(request.action))
            {
                return start(request, manager, project, application, env, null);
            }
            return stop(request, manager, project, application, env);
        }
        finally
        {
            lock.unlock();
        }
    }

    // ==================== status ====================

    private static String status(Request request, IProject project, IApplication application,
        Environment env)
    {
        Object server = StandaloneServerSupport.serverOfApplication(application);
        if (server == null)
        {
            return unresolvedServer(request, application);
        }
        String applicationId = application.getId();
        String message;
        if (StandaloneServerStateRecovery.releaseHoldIfStartedOutside(project, applicationId, server))
        {
            message = "hold released: the server was started outside " + NAME + "."; //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            message = null;
        }
        ToolResult result = base(ToolResult.success(), ACTION_STATUS, request, application, server);
        describeServer(result, project, application, server, env, true);
        String state = stateOf(server);
        if (message == null)
        {
            message = "The standalone server is " + state //$NON-NLS-1$
                + (StandaloneServerStateRecovery.heldSince(project, applicationId) != null
                    ? " and held stopped; plugin-driven starts are refused until " + NAME //$NON-NLS-1$
                        + "(action='start')." //$NON-NLS-1$
                    : "."); //$NON-NLS-1$
        }
        return result.put(McpKeys.MESSAGE, message).toJson();
    }

    // ==================== stop / restart ====================

    private static String stop(Request request, IApplicationManager manager, IProject project,
        IApplication application, Environment env)
    {
        boolean restart = ACTION_RESTART.equals(request.action);
        String applicationId = application.getId();
        Object server = StandaloneServerSupport.serverOfApplication(application);
        if (server == null)
        {
            return unresolvedServer(request, application);
        }
        Integer state = StandaloneServerStateRecovery.serverState(server);
        Boolean liveLaunch = StandaloneServerStateRecovery.hasLiveLaunch(server);
        boolean alreadyStopped = state != null && state.intValue() == WST_STOPPED
            && !Boolean.TRUE.equals(liveLaunch);
        Snapshot snapshot = alreadyStopped ? Snapshot.EMPTY : Snapshot.read(server, application, env);

        if (!request.confirm)
        {
            return preview(request, project, application, server, env, snapshot, alreadyStopped);
        }
        if (alreadyStopped)
        {
            if (restart)
            {
                return start(request, manager, project, application, env, null);
            }
            // Under the recovery guard: a start that already claims the server is refused, and a
            // server somebody started meanwhile is not stopped without a fresh preview and consent.
            Recovery held = StandaloneServerStateRecovery.stopGuarded(project, application,
                applicationId, manager, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS,
                StopPolicy.HOLD_ONLY);
            if (!held.recovered())
            {
                return stopFailure(request, project, application, server, held);
            }
            return alreadyStoppedResult(request, project, application, server, env);
        }

        ConsentPreview consentPreview = new ConsentPreview(
            restart ? "Restart standalone server" : "Stop standalone server", //$NON-NLS-1$ //$NON-NLS-2$
            application.getName() + " - " + snapshot.whatEnds(), //$NON-NLS-1$
            snapshot.sessionCount(), snapshot.sessionNames());
        ConsentDecision decision = env.consent(consentPreview);
        if (decision != ConsentDecision.ALLOW)
        {
            return ToolResult.error(DestructiveConsentGate.consentDeniedMessage(decision, NAME))
                .put(McpKeys.ACTION, request.action).put(McpKeys.PROJECT, request.projectName)
                .put(McpKeys.APPLICATION_ID, applicationId).toJson();
        }

        // Read again right before the stop: consent can take a person's time.
        LiveProcess process = env.liveProcess(server);
        Recovery recovery = StandaloneServerStateRecovery.stopGuarded(project, application,
            applicationId, manager, StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS,
            StopPolicy.RUNNING);
        if (!recovery.recovered())
        {
            return stopFailure(request, project, application, server, recovery);
        }
        if (recovery.alreadyStopped())
        {
            // Stopped by somebody else between the read above and the guarded re-read.
            return restart ? start(request, manager, project, application, env, null)
                : alreadyStoppedResult(request, project, application, server, env);
        }
        Boolean processAlive = null;
        if (process != null)
        {
            boolean exited = StandaloneServerSupport.awaitProcessExit(process.alive(),
                processExitTimeoutMs, StandaloneServerSupport.PROCESS_EXIT_POLL_MS);
            processAlive = Boolean.valueOf(!exited);
        }
        if (Boolean.TRUE.equals(processAlive))
        {
            return lingeringProcess(request, project, application, server, env, process.pid());
        }
        if (restart)
        {
            return start(request, manager, project, application, env, snapshot);
        }
        ToolResult result = base(ToolResult.success(), ACTION_STOP, request, application, server)
            .put(KEY_MUTATION_COMMITTED, true);
        snapshot.putInto(result);
        describeServer(result, project, application, server, env, false);
        if (processAlive != null)
        {
            result.put(KEY_PROCESS_ALIVE, false).put(KEY_PID, process.pid());
        }
        String directory = env.databaseDirectory(application);
        return result.put(McpKeys.MESSAGE, "The standalone server is stopped" //$NON-NLS-1$
                + (processAlive == null ? ". Its process id could not be read, so its exit was not " //$NON-NLS-1$
                    + "verified separately. " : " and its ibsrv process has exited. ") //$NON-NLS-1$ //$NON-NLS-2$
            + (directory == null ? "" //$NON-NLS-1$
                : "EDT's hold on the file infobase at " + directory + " is released; other " //$NON-NLS-1$ //$NON-NLS-2$
                    + "processes using it were not inspected. ") //$NON-NLS-1$
            + "It stays held stopped: launch, update_database, run_yaxunit_tests, " //$NON-NLS-1$
            + "build_external_objects and export_configuration_to_file refuse to start it until " //$NON-NLS-1$
            + NAME + "(action='start').").toJson(); //$NON-NLS-1$
    }

    /** The stop preview: what a confirmed stop would end, and what changed (nothing). */
    private static String preview(Request request, IProject project, IApplication application,
        Object server, Environment env, Snapshot snapshot, boolean alreadyStopped)
    {
        boolean restart = ACTION_RESTART.equals(request.action);
        ToolResult result = base(ToolResult.success(), ACTION_PREVIEW, request, application, server)
            .put(KEY_CONFIRMATION_REQUIRED, true);
        snapshot.putInto(result);
        describeServer(result, project, application, server, env, true);
        String url = env.infobaseUrl(application);
        String message;
        if (alreadyStopped)
        {
            message = "Preview only - nothing changed. The standalone server is already STOPPED, " //$NON-NLS-1$
                + "so nothing would end. " + (restart //$NON-NLS-1$
                    ? "With confirm=true it is started." //$NON-NLS-1$
                    : "With confirm=true it is held stopped until " + NAME + "(action='start')."); //$NON-NLS-1$ //$NON-NLS-2$
        }
        else
        {
            message = "Preview only - nothing was stopped. " + (restart ? "Restarting" : "Stopping") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + " the standalone server ends " + snapshot.whatEnds() + "." //$NON-NLS-1$ //$NON-NLS-2$
                + (restart ? " It is then started again." //$NON-NLS-1$
                    : " It then stays held stopped until " + NAME + "(action='start').") //$NON-NLS-1$ //$NON-NLS-2$
                + " Call again with confirm=true to proceed. To reach the infobase without stopping " //$NON-NLS-1$
                + "the server, connect through it instead: 1cv8 DESIGNER /S " //$NON-NLS-1$
                + (url == null ? "<server address>\\<infobase>" : url) //$NON-NLS-1$
                + ", or ibcmd with --pid of the running ibsrv."; //$NON-NLS-1$
        }
        return result.put(McpKeys.MESSAGE, message).toJson();
    }

    private static String stopFailure(Request request, IProject project, IApplication application,
        Object server, Recovery recovery)
    {
        String applicationId = application.getId();
        if (recovery.stopStillInFlight())
        {
            return base(ToolResult.errorWithUnknownMutationOutcome("Stopping the standalone server of '" //$NON-NLS-1$
                + applicationId + "' did not finish (" + recovery.detail() //$NON-NLS-1$
                + "); it may still be running, and the server is held stopped meanwhile. Wait, then " //$NON-NLS-1$
                + "call " + NAME + "(action='status'). Do not kill ibsrv with OS tools: a forced " //$NON-NLS-1$ //$NON-NLS-2$
                + "kill can damage a file infobase."), request.action, request, application, server) //$NON-NLS-1$
                .put(KEY_HELD_STOPPED, StandaloneServerStateRecovery.heldSince(project, applicationId) != null)
                .toJson();
        }
        if (recovery.dispatchedAndFailed())
        {
            ToolResult result = base(ToolResult.errorWithUnknownMutationOutcome("EDT's stop of the " //$NON-NLS-1$
                + "standalone server of '" + applicationId + "' ran and failed: " + recovery.detail() //$NON-NLS-1$ //$NON-NLS-2$
                + ". It may have ended sessions or the server on the way; check " + NAME //$NON-NLS-1$
                + "(action='status') before retrying."), request.action, request, application, //$NON-NLS-1$
                server);
            result.put(KEY_STATE, stateOf(server));
            return heldFlag(result, project, applicationId).toJson();
        }
        return heldFlag(base(ToolResult.error("The standalone server of '" + applicationId //$NON-NLS-1$
            + "' was not stopped: " + recovery.detail() + ". Nothing was dispatched to EDT. Retry " //$NON-NLS-1$ //$NON-NLS-2$
            + "once " + NAME + "(action='status') reports STARTED or STOPPED."), //$NON-NLS-1$ //$NON-NLS-2$
            request.action, request, application, server), project, applicationId).toJson();
    }

    /** One shape for a stop that found the server already STOPPED, read before or under the guard. */
    private static String alreadyStoppedResult(Request request, IProject project,
        IApplication application, Object server, Environment env)
    {
        ToolResult result = base(ToolResult.success(), ACTION_STOP, request, application, server)
            .put(KEY_ALREADY_STOPPED, true);
        describeServer(result, project, application, server, env, false);
        return result.put(McpKeys.MESSAGE, "The standalone server was already STOPPED, so " //$NON-NLS-1$
            + "nothing was stopped; it is now held stopped until " + NAME //$NON-NLS-1$
            + "(action='start'). The process id of its previous run is not known.").toJson(); //$NON-NLS-1$
    }

    private static String lingeringProcess(Request request, IProject project,
        IApplication application, Object server, Environment env, long pid)
    {
        String applicationId = application.getId();
        String directory = env.databaseDirectory(application);
        ToolResult result = base(ToolResult.errorAfterMutation("EDT reports the standalone server of '" //$NON-NLS-1$
            + applicationId + "' STOPPED, but its ibsrv process (pid " + pid + ") is still running " //$NON-NLS-1$ //$NON-NLS-2$
            + (processExitTimeoutMs / 1000) + "s after the stop" //$NON-NLS-1$
            + (directory == null ? "" : "; the file infobase at " + directory + " may still be locked") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + ". Do not kill it with OS tools (#438: a forced kill can damage a file infobase). " //$NON-NLS-1$
            + "Wait, then call " + NAME + "(action='status'); if it never exits, stop it in EDT's " //$NON-NLS-1$ //$NON-NLS-2$
            + "Servers view or restart EDT." //$NON-NLS-1$
            + (ACTION_RESTART.equals(request.action) ? " The server was not started again." : "")), //$NON-NLS-1$ //$NON-NLS-2$
            request.action, request, application, server);
        describeServer(result, project, application, server, env, false);
        return result.put(KEY_PROCESS_ALIVE, true).put(KEY_PID, pid).toJson();
    }

    // ==================== start ====================

    private static String start(Request request, IApplicationManager manager, IProject project,
        IApplication application, Environment env, Snapshot stopped)
    {
        String applicationId = application.getId();
        Object service = env.service();
        StartOutcome outcome = StandaloneServerStateRecovery.startExplicitly(project, application,
            applicationId, manager, service, request.portPolicy,
            StandaloneServerSupport.SERVER_OPERATION_TIMEOUT_MS);
        Object server = StandaloneServerSupport.serverOfApplication(application);
        boolean restart = ACTION_RESTART.equals(request.action);
        String stoppedPrefix = restart && stopped != null
            ? "The standalone server was stopped (ending " + stopped.whatEnds() + "), but " //$NON-NLS-1$ //$NON-NLS-2$
            : ""; //$NON-NLS-1$
        switch (outcome.kind())
        {
        case STARTED:
        case ALREADY_RUNNING:
        {
            boolean already = outcome.kind() == StandaloneServerStateRecovery.StartKind.ALREADY_RUNNING;
            ToolResult result = base(ToolResult.success(), request.action, request, application, server);
            if (already)
            {
                result.put(KEY_ALREADY_RUNNING, true);
            }
            else
            {
                result.put(KEY_MUTATION_COMMITTED, true);
            }
            if (outcome.portsReassigned())
            {
                result.put(KEY_PORTS_REASSIGNED, true);
            }
            if (stopped != null)
            {
                stopped.putInto(result);
            }
            describeServer(result, project, application, server, env, true);
            return result.put(McpKeys.MESSAGE, (already
                ? "The standalone server was already running under a live launch; nothing was " //$NON-NLS-1$
                    + "started. "
                : (restart ? "The standalone server was restarted. " //$NON-NLS-1$
                    : "The standalone server was started. ")) //$NON-NLS-1$
                + "The stop hold is released, so plugin-driven starts proceed again." //$NON-NLS-1$
                + (outcome.portsReassigned()
                    ? " EDT moved it to other ports (its configuration was rewritten)." : "")) //$NON-NLS-1$ //$NON-NLS-2$
                .toJson();
        }
        case REFUSED:
        {
            ToolResult result = restart && stopped != null
                ? ToolResult.errorAfterMutation(stoppedPrefix + "it was not started again: " //$NON-NLS-1$
                    + outcome.detail() + ". It stays held stopped; call " + NAME //$NON-NLS-1$
                    + "(action='start') once the cause is cleared.") //$NON-NLS-1$
                : ToolResult.error("The standalone server of '" + applicationId + "' was not started: " //$NON-NLS-1$ //$NON-NLS-2$
                    + outcome.detail() + ". Nothing was started. Check " + NAME //$NON-NLS-1$
                    + "(action='status') and retry."); //$NON-NLS-1$
            return heldFlag(base(result, request.action, request, application, server), project,
                applicationId).toJson();
        }
        case FAILED:
        default:
        {
            String text = (stoppedPrefix.isEmpty() ? "Starting the standalone server of '" //$NON-NLS-1$
                + applicationId + "' failed: " : stoppedPrefix + "starting it again failed: ") //$NON-NLS-1$ //$NON-NLS-2$
                + outcome.detail() + "."; //$NON-NLS-1$
            ToolResult result;
            if (!outcome.conclusive())
            {
                result = ToolResult.errorWithUnknownMutationOutcome(text
                    + " The start may still be running; wait, then call " + NAME //$NON-NLS-1$
                    + "(action='status')." //$NON-NLS-1$
                    + (outcome.portReassignmentOutcomeUnknown()
                        ? " It may still move the server to other ports." : "")); //$NON-NLS-1$ //$NON-NLS-2$
            }
            else
            {
                String retry = " It stays held stopped; fix the cause and call " + NAME //$NON-NLS-1$
                    + "(action='start')."; //$NON-NLS-1$
                result = stopped != null ? ToolResult.errorAfterMutation(text + retry)
                    : ToolResult.error(text + retry);
            }
            return heldFlag(base(result, request.action, request, application, server), project,
                applicationId).toJson();
        }
        }
    }

    // ==================== shared rendering ====================

    private static ToolResult heldFlag(ToolResult result, IProject project, String applicationId)
    {
        String since = StandaloneServerStateRecovery.heldSince(project, applicationId);
        result.put(KEY_HELD_STOPPED, since != null);
        if (since != null)
        {
            result.put(KEY_HELD_SINCE, since);
        }
        return result;
    }

    private static ToolResult base(ToolResult result, String action, Request request,
        IApplication application, Object server)
    {
        result.put(McpKeys.ACTION, action).put(McpKeys.PROJECT, request.projectName)
            .put(McpKeys.APPLICATION_ID, application.getId());
        String serverName = server == null ? null : StandaloneServerSupport.nameOfServer(server);
        if (serverName != null)
        {
            result.put(KEY_SERVER_NAME, serverName);
        }
        return result;
    }

    /** Adds state, launch, process, hold, directory and URL fields read now. */
    private static void describeServer(ToolResult result, IProject project, IApplication application,
        Object server, Environment env, boolean readProcess)
    {
        Integer state = StandaloneServerStateRecovery.serverState(server);
        Boolean liveLaunch = StandaloneServerStateRecovery.hasLiveLaunch(server);
        result.put(KEY_STATE, stateOf(state));
        if (liveLaunch != null)
        {
            result.put(KEY_LIVE_LAUNCH, liveLaunch.booleanValue());
        }
        // processAlive is reported only for a process whose id was actually read: a stale STARTED
        // server whose launch is gone may still have a live ibsrv nobody can name.
        if (readProcess && Boolean.TRUE.equals(liveLaunch))
        {
            LiveProcess process = env.liveProcess(server);
            if (process != null)
            {
                result.put(KEY_PID, process.pid());
                result.put(KEY_PROCESS_ALIVE, process.alive().getAsBoolean());
            }
        }
        heldFlag(result, project, application.getId());
        String directory = env.databaseDirectory(application);
        if (directory != null)
        {
            result.put(KEY_DATABASE_DIRECTORY, directory);
        }
        if (state != null && state.intValue() == WST_STARTED)
        {
            String url = env.infobaseUrl(application);
            if (url != null)
            {
                result.put(KEY_INFOBASE_URL, url);
            }
        }
    }

    private static String stateOf(Object server)
    {
        return stateOf(StandaloneServerStateRecovery.serverState(server));
    }

    private static String stateOf(Integer state)
    {
        return state == null ? "UNKNOWN" : StandaloneServerStateRecovery.stateName(state.intValue()); //$NON-NLS-1$
    }

    private static String unresolvedServer(Request request, IApplication application)
    {
        return ToolResult.error("EDT could not resolve the standalone server of application '" //$NON-NLS-1$
            + application.getId() + "'. Check it in get_applications and EDT's Servers view; " //$NON-NLS-1$
            + "nothing was changed.").put(McpKeys.ACTION, request.action) //$NON-NLS-1$
            .put(McpKeys.PROJECT, request.projectName)
            .put(McpKeys.APPLICATION_ID, application.getId()).toJson();
    }

    // ==================== value types and seams ====================

    /** One validated request. */
    static final class Request
    {
        final String projectName;
        final String action;
        final boolean confirm;
        final StandaloneServerPortConflictPolicy portPolicy;

        Request(String projectName, String action, boolean confirm,
            StandaloneServerPortConflictPolicy portPolicy)
        {
            this.projectName = projectName;
            this.action = action;
            this.confirm = confirm;
            this.portPolicy = portPolicy == null ? StandaloneServerPortConflictPolicy.DEFAULT
                : portPolicy;
        }
    }

    /** What a stop would end, read before it. */
    static final class Snapshot
    {
        static final Snapshot EMPTY = new Snapshot(
            ReadResult.unreachable("The standalone server is not running."), 0, 0); //$NON-NLS-1$

        final ReadResult sessions;
        final int clientLaunches;
        final int debugTargets;

        Snapshot(ReadResult sessions, int clientLaunches, int debugTargets)
        {
            this.sessions = sessions;
            this.clientLaunches = clientLaunches;
            this.debugTargets = debugTargets;
        }

        static Snapshot read(Object server, IApplication application, Environment env)
        {
            ReadResult sessions = Boolean.TRUE.equals(StandaloneServerStateRecovery.hasLiveLaunch(server))
                ? env.listSessions(application)
                : ReadResult.unreachable("The standalone server holds no live launch."); //$NON-NLS-1$
            return new Snapshot(sessions, env.clientLaunches(application.getId()),
                env.debugTargets(application.getId()));
        }

        int sessionCount()
        {
            return sessions.isReadable() ? sessions.sessions().size() : 0;
        }

        int designerCount()
        {
            if (!sessions.isReadable())
            {
                return 0;
            }
            int count = 0;
            for (SessionInfo session : sessions.sessions())
            {
                if (InfobaseSessionsTool.isDesignerSession(session))
                {
                    count++;
                }
            }
            return count;
        }

        List<String> sessionNames()
        {
            if (!sessions.isReadable())
            {
                return List.of();
            }
            return sessions.sessions().stream()
                .map(InfobaseSessionErrorProjection::identifier)
                .flatMap(java.util.Optional::stream)
                .toList();
        }

        String whatEnds()
        {
            String sessionPart = sessions.isReadable()
                ? sessionCount() + " session(s), of which " + designerCount() + " Designer" //$NON-NLS-1$ //$NON-NLS-2$
                : "an unknown number of sessions (" + sessions.unreachableReason() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            return sessionPart + ", " + clientLaunches + " client launch(es) and " + debugTargets //$NON-NLS-1$ //$NON-NLS-2$
                + " debug target(s)"; //$NON-NLS-1$
        }

        void putInto(ToolResult result)
        {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("reachable", sessions.isReadable()); //$NON-NLS-1$
            if (sessions.isReadable())
            {
                block.put("count", sessionCount()); //$NON-NLS-1$
                block.put("designerCount", designerCount()); //$NON-NLS-1$
                block.put(KEY_SESSIONS, InfobaseSessionErrorProjection.fields(sessions.sessions()));
            }
            else
            {
                block.put("unreachableReason", sessions.unreachableReason()); //$NON-NLS-1$
            }
            result.put(KEY_SESSIONS, block);
            result.put(KEY_CLIENT_LAUNCHES, clientLaunches);
            result.put(KEY_DEBUG_TARGETS, debugTargets);
        }
    }

    /**
     * The ibsrv process of a running server: its id and an aliveness check bound to the process
     * read at that moment (a later process reusing the id does not count).
     */
    record LiveProcess(long pid, BooleanSupplier alive)
    {
    }

    /** The platform reads and the consent gate the tool depends on; a seam for unit tests. */
    interface Environment
    {
        /** @return the live ibsrv process, or {@code null} when none could be read */
        LiveProcess liveProcess(Object server);

        /** @return the ibcmd session list */
        ReadResult listSessions(IApplication application);

        /** @return EDT client launches attached to the application (0 or 1) */
        int clientLaunches(String applicationId);

        /** @return live debug targets of the server (0 or 1) */
        int debugTargets(String applicationId);

        /** @return the consent decision for a stop */
        ConsentDecision consent(ConsentPreview preview);

        /** @return the standalone-server service, or {@code null} */
        Object service();

        /** @return the file-backed infobase directory, or {@code null} */
        String databaseDirectory(IApplication application);

        /** @return the client URL of the infobase, or {@code null} */
        String infobaseUrl(IApplication application);

        /** The live platform. */
        Environment PRODUCTION = new Environment()
        {
            @Override
            public LiveProcess liveProcess(Object server)
            {
                return StandaloneServerSupport.liveProcess(server)
                    .map(handle -> new LiveProcess(handle.pid(), handle::isAlive))
                    .orElse(null);
            }

            @Override
            public ReadResult listSessions(IApplication application)
            {
                return InfobaseSessionSupport.listSessions(application);
            }

            @Override
            public int clientLaunches(String applicationId)
            {
                return LaunchLifecycleUtils.resolveExistingClientSession(applicationId) == null ? 0 : 1;
            }

            @Override
            public int debugTargets(String applicationId)
            {
                return DebugServerTargetSupport.resolve(applicationId) == null ? 0 : 1;
            }

            @Override
            public ConsentDecision consent(ConsentPreview preview)
            {
                return DestructiveConsentGate.getInstance().requireConsent(NAME, preview);
            }

            @Override
            public Object service()
            {
                return StandaloneServerSupport.acquireService();
            }

            @Override
            public String databaseDirectory(IApplication application)
            {
                Object module = StandaloneServerSupport.moduleOfApplication(application);
                return module == null ? null : StandaloneServerSupport.databaseDirOf(module);
            }

            @Override
            public String infobaseUrl(IApplication application)
            {
                Object module = StandaloneServerSupport.moduleOfApplication(application);
                return module == null ? null
                    : StandaloneServerSupport.infobaseUrlOf(StandaloneServerSupport.acquireService(), module);
            }
        };
    }
}
