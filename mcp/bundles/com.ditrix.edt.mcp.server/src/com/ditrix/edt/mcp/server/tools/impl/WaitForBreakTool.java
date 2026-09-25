/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugSnapshotResponse;
import com.ditrix.edt.mcp.server.utils.DebugTargetResolver;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Blocks until a SUSPEND event is observed for the given application id, then
 * returns a snapshot of the suspended thread (top frame info + stack).
 *
 * <p>If the application is already suspended at the time of the call, returns
 * immediately. If the timeout expires without a suspend, returns
 * {@code {hit:false, reason:"timeout"}} — the launch is NOT terminated.
 *
 * <p>{@code applicationId} may be a real id from {@code get_applications} or the
 * synthetic {@code attach:<configName>} id reported by {@code debug_status} for
 * Attach launches. If omitted and exactly one EDT debug launch is active, that
 * launch is auto-resolved.
 */
public class WaitForBreakTool implements IMcpTool
{
    public static final String NAME = "wait_for_break"; //$NON-NLS-1$

    /** Schema/result key: wait window in seconds. */
    private static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /** Result key: reason when no suspend occurred. */
    private static final String KEY_REASON = "reason"; //$NON-NLS-1$

    /** {@link #KEY_REASON} value: the wait window elapsed without a suspend. */
    private static final String REASON_TIMEOUT = "timeout"; //$NON-NLS-1$

    /** Result key: whether the applicationId was auto-resolved. */
    private static final String KEY_AUTO_RESOLVED = "autoResolved"; //$NON-NLS-1$

    /** Result key: whether resolved via the 1C debug-server target bridge. */
    private static final String KEY_SERVER_TARGET = "serverTarget"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 60;

    /** Hard cap on the wait window, prevents a worker thread blocking for hours. */
    static final int MAX_TIMEOUT = 600;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Wait until a running 1C debug session reaches a breakpoint or other suspend event. " //$NON-NLS-1$
            + "Parameters and examples: get_tool_guide('wait_for_break')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.APPLICATION_ID,
                "Application id of the running debug session (real, 'attach:<configName>', " //$NON-NLS-1$
                    + "'launch:<configName>', or 'ServerApplication.<app>'). " //$NON-NLS-1$
                    + "Optional if exactly one debug session is active.") //$NON-NLS-1$
            .integerProperty(KEY_TIMEOUT, "Wait window in seconds (default: 60)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("hit", "True if a suspend was observed, false on timeout") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REASON, "Reason when no suspend occurred (e.g. 'timeout')") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the debug session waited on") //$NON-NLS-1$
            .booleanProperty(KEY_AUTO_RESOLVED, "True if applicationId was auto-resolved") //$NON-NLS-1$
            .booleanProperty(KEY_SERVER_TARGET, "True if resolved via the 1C debug-server target bridge") //$NON-NLS-1$
            .integerProperty("threadId", "Id of the suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty("threadName", "Name of the suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("frames", "Stack frames of the suspended thread (frameIndex, frameRef, name, line, modulePath, project)") //$NON-NLS-1$ //$NON-NLS-2$
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
        // The suspended-thread snapshot exposes stack frames (and their source
        // context) from the running 1C infobase: flag for PII redaction.
        return true;
    }

    @Override
    public String execute(Map<String, String> params)
    {
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        int timeout = clampTimeout(JsonUtils.extractIntArgument(params, KEY_TIMEOUT, DEFAULT_TIMEOUT)); //$NON-NLS-1$

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        registry.ensureListenerRegistered();

        // Unified resolution: accept ANY id form for the session (real,
        // attach:<name>, launch:<name>, ServerApplication.<app>, the bare app name,
        // the debug server URL); a blank id auto-resolves the single active session.
        boolean autoResolved = false;
        DebugServerTargetSupport.ServerTarget serverTarget = null;
        DebugTargetResolver.Resolution res = DebugTargetResolver.resolve(applicationId);
        if (res != null)
        {
            // Use the canonical id so every follow-up tool addresses the same session.
            applicationId = res.canonicalId;
            autoResolved = res.autoResolved;
            serverTarget = res.serverTarget;
        }
        else if (applicationId == null || applicationId.isEmpty())
        {
            return ToolResult.error("applicationId is required — no single active debug session " //$NON-NLS-1$
                + "available for auto-resolution. Use debug_status to list active sessions.").toJson(); //$NON-NLS-1$
        }
        // If res is null but an explicit id was given, fall through with that id: the
        // session may have suspended pre-listener; the launch-based wait below still
        // tries (findActiveTarget) and reports a clean timeout otherwise.

        // SERVER-TARGET PATH: a breakpoint hit in code that runs on the 1C server
        // (the common debug_yaxunit_tests case, or an EDT-UI-started session) suspends
        // a 1C-native debug-server target, NOT an Eclipse ILaunch thread. This path
        // polls the live target (its SUSPEND events do not reliably key into the
        // launch-based registry) and injects the suspended thread so the rest of the
        // snapshot machinery (frames/variables/eval/step) works unchanged.
        if (serverTarget != null && !registry.hasSnapshot(applicationId))
        {
            return waitOnServerTarget(registry, serverTarget, applicationId, timeout, autoResolved);
        }

        // Proactively scan live targets for threads already suspended before the
        // listener was registered (e.g. manual breakpoint hit in EDT, or suspend
        // that happened between launch and this call).
        scanForAlreadySuspended(registry, applicationId);

        try
        {
            DebugSessionRegistry.SuspendSnapshot snapshot =
                registry.waitForSuspend(applicationId, timeout * 1000L);
            if (snapshot == null)
            {
                ToolResult r = ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put(KEY_REASON, REASON_TIMEOUT)
                    .put(McpKeys.APPLICATION_ID, applicationId);
                if (autoResolved)
                {
                    r.put(KEY_AUTO_RESOLVED, true);
                }
                return r.toJson();
            }
            return buildSnapshotResponse(snapshot, registry, applicationId, autoResolved,
                serverTarget != null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for break").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in wait_for_break", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Clamps the requested wait window to {@code [1, MAX_TIMEOUT]} seconds so a
     * worker thread can never block for hours on an unbounded value.
     */
    static int clampTimeout(int requested)
    {
        if (requested < 1)
        {
            return 1;
        }
        if (requested > MAX_TIMEOUT)
        {
            return MAX_TIMEOUT;
        }
        return requested;
    }

    /**
     * Scans the active debug target for threads that are already suspended but
     * were missed by the registry listener (e.g. suspend happened before the
     * listener was installed). If a suspended thread is found and the registry
     * has no snapshot for this appId, injects a synthetic snapshot.
     */
    private static void scanForAlreadySuspended(DebugSessionRegistry registry, String applicationId)
    {
        try
        {
            if (registry.hasSnapshot(applicationId))
            {
                return; // already tracked
            }
            IDebugTarget target = DebugSessionRegistry.findActiveTarget(applicationId);
            if (target == null || target.isTerminated())
            {
                return;
            }
            for (IThread thread : target.getThreads())
            {
                if (thread.isSuspended())
                {
                    registry.injectSuspend(applicationId, thread);
                    return;
                }
            }
        }
        catch (Exception ex)
        {
            // best effort — fall through to normal wait
        }
    }

    /**
     * Waits for a suspend on a 1C debug-server target by polling its live threads
     * (via the Eclipse {@link IThread} interface — the 1C target implements the
     * standard debug model). On a suspend, injects the suspended thread into the
     * registry under {@code applicationId} so the shared snapshot response — and
     * every follow-up tool — addresses it exactly like a thin-client thread.
     */
    private static String waitOnServerTarget(DebugSessionRegistry registry,
        DebugServerTargetSupport.ServerTarget serverTarget, String applicationId, int timeout,
        boolean autoResolved)
    {
        try
        {
            IThread suspended = DebugServerTargetSupport.pollForSuspendedThread(
                serverTarget.target, timeout * 1000L, LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
            if (suspended == null)
            {
                ToolResult r = ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put(KEY_REASON, REASON_TIMEOUT)
                    .put(McpKeys.APPLICATION_ID, applicationId)
                    .put(KEY_SERVER_TARGET, true);
                if (autoResolved)
                {
                    r.put(KEY_AUTO_RESOLVED, true);
                }
                return r.toJson();
            }
            registry.injectSuspend(applicationId, suspended);
            DebugSessionRegistry.SuspendSnapshot snapshot = registry.getSnapshot(applicationId);
            if (snapshot == null)
            {
                return ToolResult.error("server target suspended but snapshot registration failed").toJson(); //$NON-NLS-1$
            }
            return buildSnapshotResponse(snapshot, registry, applicationId, autoResolved, true);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("Interrupted while waiting for break").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in wait_for_break (server target)", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Builds the JSON response for a suspend snapshot: {@code hit:true} plus the shared
     * snapshot ({@link DebugSnapshotResponse}). {@code serverTarget} marks a suspend resolved
     * via the 1C debug-server target bridge; as on the timeout path, the flag is emitted only
     * when {@code true} and omitted for ordinary launch threads.
     */
    static String buildSnapshotResponse(DebugSessionRegistry.SuspendSnapshot snapshot,
            DebugSessionRegistry registry, String applicationId, boolean autoResolved,
            boolean serverTarget) throws Exception
    {
        return DebugSnapshotResponse.appendSnapshot(ToolResult.success().put("hit", true), //$NON-NLS-1$
            snapshot, registry, applicationId, autoResolved, serverTarget).toJson();
    }
}
