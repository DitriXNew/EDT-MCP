/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.DebugException;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugPause;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugSnapshotResponse;
import com.ditrix.edt.mcp.server.utils.DebugTargetResolver;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Pauses an already running debug session and returns where it is executing - the active
 * counterpart of {@code wait_for_break}, addressed exactly like {@code resume}.
 */
public class DebugPauseTool implements IMcpTool
{
    public static final String NAME = "debug_pause"; //$NON-NLS-1$

    private static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    private static final String KEY_PAUSED = "paused"; //$NON-NLS-1$

    private static final String KEY_STATE = "state"; //$NON-NLS-1$

    private static final String KEY_REASON = "reason"; //$NON-NLS-1$

    private static final String KEY_ALREADY_SUSPENDED = "alreadySuspended"; //$NON-NLS-1$

    private static final String KEY_AUTO_RESOLVED = "autoResolved"; //$NON-NLS-1$

    private static final String KEY_SERVER_TARGET = "serverTarget"; //$NON-NLS-1$

    private static final String STATE_SUSPENDED = "suspended"; //$NON-NLS-1$

    private static final String STATE_RUNNING = "running"; //$NON-NLS-1$

    private static final String STATE_TERMINATED = "terminated"; //$NON-NLS-1$

    private static final String REASON_TIMEOUT = "timeout"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Pause a running 1C debug session now and return where it is executing (thread and " //$NON-NLS-1$
            + "stack frames); unlike wait_for_break, it requests the suspend itself. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('debug_pause')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id of the running debug session (real, 'attach:<configName>', " //$NON-NLS-1$
                    + "'launch:<configName>', or 'ServerApplication.<app>'). " //$NON-NLS-1$
                    + "Optional if exactly one debug session is active.") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT, "Seconds to wait for the pause to take effect (default: " //$NON-NLS-1$
                + DebugPause.DEFAULT_TIMEOUT_SECONDS + ", max: " + DebugPause.MAX_TIMEOUT_SECONDS + ")") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_PAUSED, "True when a suspended thread was observed and is returned") //$NON-NLS-1$
            .stringProperty(KEY_STATE, "Observed session state: 'suspended', 'running' or 'terminated'") //$NON-NLS-1$
            .stringProperty(KEY_REASON, "Why nothing was paused: 'timeout' or 'terminated'") //$NON-NLS-1$
            .booleanProperty(KEY_ALREADY_SUSPENDED,
                "True when the session was already paused, so no suspend request was sent") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "What an unpaused outcome means and what to call next") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Canonical application id of the debug session") //$NON-NLS-1$
            .booleanProperty(KEY_AUTO_RESOLVED, "True if applicationId was auto-resolved") //$NON-NLS-1$
            .booleanProperty(KEY_SERVER_TARGET, "True if resolved via the 1C debug-server target bridge") //$NON-NLS-1$
            .integerProperty("threadId", "Id of the suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("threadName", "Name of the suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("frames", //$NON-NLS-1$
                "Stack frames of the suspended thread (frameIndex, frameRef, name, line, modulePath, project)") //$NON-NLS-1$
            .integerProperty("topFrameRef", "Stable frame reference of the top stack frame") //$NON-NLS-1$ //$NON-NLS-2$
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
        // The stack frames come from the running 1C infobase: flag for PII redaction.
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        int timeout = DebugPause.clampTimeoutSeconds(
            JsonUtils.extractIntArgument(params, KEY_TIMEOUT, DebugPause.DEFAULT_TIMEOUT_SECONDS));

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(applicationId);
        // A blank id pauses only a sole session: the resolver may pick one of several.
        if (res == null || (res.autoResolved && !DebugTargetResolver.isSoleLiveTarget(res.target)))
        {
            return unresolved(applicationId);
        }
        String appId = res.canonicalId;

        DebugPause.Outcome outcome;
        try
        {
            outcome = DebugPause.pause(res.target, appId, registry, timeout * 1000L,
                LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.errorWithUnknownMutationOutcome("Interrupted while pausing " + appId //$NON-NLS-1$
                + "; the suspend request may already be in effect. Check with debug_status or " //$NON-NLS-1$
                + "wait_for_break.").toJson(); //$NON-NLS-1$
        }
        catch (DebugException e)
        {
            Activator.logError("Error in debug_pause", e); //$NON-NLS-1$
            return ToolResult.errorWithUnknownMutationOutcome("The suspend request for " + appId //$NON-NLS-1$
                + " failed: " + e.getMessage() + ". Whether the session paused is unknown - check with " //$NON-NLS-1$ //$NON-NLS-2$
                + "debug_status, or catch a pause with wait_for_break.").toJson(); //$NON-NLS-1$
        }

        return render(outcome, appId, res.autoResolved, res.isServerTarget(), timeout, registry);
    }

    /**
     * Renders an observed pause outcome. Package-visible so every outcome shape is pinned headless.
     *
     * @param outcome the observed outcome
     * @param appId the canonical session id
     * @param autoResolved whether the session was auto-resolved
     * @param serverTarget whether the session is a 1C debug-server target
     * @param timeout the effective wait window in seconds
     * @param registry the registry that issues frame references
     * @return the JSON result
     */
    static String render(DebugPause.Outcome outcome, String appId, boolean autoResolved, boolean serverTarget,
        int timeout, DebugSessionRegistry registry)
    {
        switch (outcome.state)
        {
            case SUSPENDED:
                return suspended(outcome, appId, autoResolved, serverTarget, registry);
            case RUNNING:
                return notPaused(appId, autoResolved, serverTarget, STATE_RUNNING, REASON_TIMEOUT,
                    "Nothing paused within " + timeout //$NON-NLS-1$
                        + "s: the session ran no BSL statement in that time (for example, a client " //$NON-NLS-1$
                        + "waiting for user input). 1C pauses at the next statement executed, so the " //$NON-NLS-1$
                        + "request stays armed - catch the pause with wait_for_break instead of calling " //$NON-NLS-1$
                        + "debug_pause again."); //$NON-NLS-1$
            case TERMINATED:
                return notPaused(appId, autoResolved, serverTarget, STATE_TERMINATED, STATE_TERMINATED,
                    "The debug session ended before it paused. Start a new one with launch."); //$NON-NLS-1$
            default:
                return ToolResult.error("The debug target of " + appId //$NON-NLS-1$
                    + " accepts no suspend request in its current state. Check it with debug_status.") //$NON-NLS-1$
                    .toJson();
        }
    }

    /** The paused result: the shared snapshot plus the pause outcome keys. */
    private static String suspended(DebugPause.Outcome outcome, String appId, boolean autoResolved,
        boolean serverTarget, DebugSessionRegistry registry)
    {
        ToolResult result = ToolResult.success()
            .put(KEY_PAUSED, true)
            .put(KEY_STATE, STATE_SUSPENDED)
            .put(KEY_ALREADY_SUSPENDED, outcome.alreadySuspended);
        try
        {
            return DebugSnapshotResponse.appendSnapshot(result, outcome.snapshot, registry, appId, autoResolved,
                serverTarget).toJson();
        }
        catch (DebugException e)
        {
            Activator.logError("Error reading the paused stack in debug_pause", e); //$NON-NLS-1$
            return ToolResult.errorAfterMutation("The session " + appId //$NON-NLS-1$
                + " is paused, but its stack could not be read: " + e.getMessage() //$NON-NLS-1$
                + ". Call wait_for_break to fetch the snapshot.").toJson(); //$NON-NLS-1$
        }
    }

    /** A success result that reports an observed state other than paused. */
    private static String notPaused(String appId, boolean autoResolved, boolean serverTarget, String state,
        String reason, String message)
    {
        ToolResult result = ToolResult.success()
            .put(KEY_PAUSED, false)
            .put(KEY_STATE, state)
            .put(KEY_REASON, reason)
            .put(McpKeys.MESSAGE, message)
            .put(McpKeys.APPLICATION_ID, appId);
        if (autoResolved)
        {
            result.put(KEY_AUTO_RESOLVED, true);
        }
        if (serverTarget)
        {
            result.put(KEY_SERVER_TARGET, true);
        }
        return result.toJson();
    }

    /** The answer when no live debug target matches: an ended session, a non-debug launch, or none. */
    private static String unresolved(String applicationId)
    {
        if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required - no single active debug session is " //$NON-NLS-1$
                + "available for auto-resolution (none or several are running). Use debug_status to " //$NON-NLS-1$
                + "list active sessions.").toJson(); //$NON-NLS-1$
        }
        if (DebugSessionRegistry.findActiveLaunch(applicationId) != null)
        {
            return ToolResult.error("The launch for applicationId " + applicationId //$NON-NLS-1$
                + " has no debug target to pause: it is still starting or runs in run mode. Check " //$NON-NLS-1$
                + "debug_status; a run-mode session must be relaunched with launch(mode=debug).").toJson(); //$NON-NLS-1$
        }
        if (DebugSessionRegistry.findTerminatedLaunch(applicationId) != null)
        {
            return notPaused(applicationId, false, false, STATE_TERMINATED, STATE_TERMINATED,
                "The debug session " + applicationId //$NON-NLS-1$
                    + " has already ended; there is nothing to pause. Start a new one with launch."); //$NON-NLS-1$
        }
        return ToolResult.error("No active debug session for applicationId: " + applicationId //$NON-NLS-1$
            + ". Use debug_status to list active sessions and their applicationId.").toJson(); //$NON-NLS-1$
    }
}
