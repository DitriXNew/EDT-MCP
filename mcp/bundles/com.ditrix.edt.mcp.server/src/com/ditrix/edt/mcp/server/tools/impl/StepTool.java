/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStep;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.DebugTargetResolver;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Steps a suspended thread (over / into / out) and waits for the next SUSPEND
 * event, returning a fresh snapshot via the same JSON shape as
 * {@link WaitForBreakTool}.
 */
public class StepTool implements IMcpTool
{
    public static final String NAME = "step"; //$NON-NLS-1$

    /** Input/output key: debug thread id. */
    private static final String KEY_THREAD_ID = "threadId"; //$NON-NLS-1$

    /** Input key: wait window in seconds. */
    private static final String KEY_TIMEOUT = "timeout"; //$NON-NLS-1$

    /** Output key: reason when a suspend was not hit. */
    private static final String KEY_REASON = "reason"; //$NON-NLS-1$

    /** {@link #KEY_REASON} value: the wait window elapsed without a suspend. */
    private static final String REASON_TIMEOUT = "timeout"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT = 30;

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
        return "Step a suspended debug thread. kind ∈ {over, into, out}. " //$NON-NLS-1$
            + "Blocks until the next SUSPEND event (or timeout) and returns the new frame snapshot."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .integerProperty(KEY_THREAD_ID, "Thread id from wait_for_break (required)", true) //$NON-NLS-1$
            .enumProperty("kind", "Step kind: over, into, out (required)", true, //$NON-NLS-1$ //$NON-NLS-2$
                "over", "into", "out") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .integerProperty(KEY_TIMEOUT, "Wait window in seconds (default: 30)") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty("hit", "True if a suspend event was caught, false on timeout") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(KEY_REASON, "Reason when not hit (e.g. timeout)") //$NON-NLS-1$
            .integerProperty(KEY_THREAD_ID, "Id of the suspended thread") //$NON-NLS-1$
            .stringProperty("threadName", "Name of the suspended thread") //$NON-NLS-1$ //$NON-NLS-2$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the debug session") //$NON-NLS-1$
            .booleanProperty("serverTarget", "True if stepping a 1C debug-server target") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("frames", "Stack frames: frameIndex, frameRef, name, line, modulePath, project") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("topFrameRef", "Stable ref of the top stack frame") //$NON-NLS-1$ //$NON-NLS-2$
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
        long threadId = JsonUtils.extractLongArgument(params, KEY_THREAD_ID, -1L);
        String kind = JsonUtils.extractStringArgument(params, "kind"); //$NON-NLS-1$
        int timeout = clampTimeout(JsonUtils.extractIntArgument(params, KEY_TIMEOUT, DEFAULT_TIMEOUT));

        if (threadId <= 0)
        {
            return ToolResult.error("threadId is required").toJson(); //$NON-NLS-1$
        }
        if (kind == null || kind.isEmpty())
        {
            return ToolResult.error("kind is required (over/into/out)").toJson(); //$NON-NLS-1$
        }

        DebugSessionRegistry registry = DebugSessionRegistry.get();
        IThread thread = registry.getThread(threadId);
        if (thread == null)
        {
            return ToolResult.error("stale threadId — call wait_for_break again").toJson(); //$NON-NLS-1$
        }
        if (!(thread instanceof IStep))
        {
            return ToolResult.error("thread does not support stepping").toJson(); //$NON-NLS-1$
        }
        IStep stepper = (IStep) thread;

        // The server-target view is still needed for the poll mechanics below (a 1C
        // debug-server target's SUSPEND events do not reliably key into the
        // launch-based registry, so the re-suspend is detected by polling).
        DebugServerTargetSupport.ServerTarget serverTarget = serverTargetOf(thread);

        // CANONICAL SNAPSHOT KEY: the caller's snapshot lives under the
        // appId recorded when this threadId was registered (by wait_for_break or a
        // previous step) — the registry's thread→appId mapping is authoritative.
        // Re-deriving the key from the server view minted ServerApplication.<app>
        // even when the session is keyed by its owning launch id, so clearSnapshot
        // cleared the wrong key and the caller's next wait_for_break returned the
        // surviving PRE-step snapshot.
        String appId = resolveApplicationId(registry, threadId, thread, serverTarget);
        if (appId == null)
        {
            return ToolResult.error("could not determine applicationId for thread").toJson(); //$NON-NLS-1$
        }

        try
        {
            // Clear the current snapshot so waitForSuspend only catches the new
            // SUSPEND event after the step, not the stale pre-step snapshot.
            registry.clearSnapshot(appId);

            String stepError = performStep(stepper, kind);
            if (stepError != null)
            {
                return stepError;
            }

            DebugSessionRegistry.SuspendSnapshot snapshot;
            if (serverTarget != null)
            {
                // Server target: poll the live model for the re-suspend (its SUSPEND
                // events do not reliably key into the launch-based registry), then
                // inject so the shared snapshot response works unchanged. Brief settle
                // first so the step transition (resume → step → suspend) registers and
                // the poll does not catch the pre-step suspended state.
                Thread.sleep(LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
                IThread reSuspended = DebugServerTargetSupport.pollForSuspendedThread(
                    serverTarget.target, timeout * 1000L, LaunchConfigUtils.LAUNCH_POLL_INTERVAL_MS);
                if (reSuspended == null)
                {
                    return ToolResult.success()
                        .put("hit", false) //$NON-NLS-1$
                        .put(KEY_REASON, REASON_TIMEOUT)
                        .put(McpKeys.APPLICATION_ID, appId)
                        .put(KEY_THREAD_ID, threadId)
                        .put("serverTarget", true) //$NON-NLS-1$
                        .toJson();
                }
                registry.injectSuspend(appId, reSuspended);
                snapshot = registry.getSnapshot(appId);
            }
            else
            {
                snapshot = registry.waitForSuspend(appId, timeout * 1000L);
            }
            if (snapshot == null)
            {
                return ToolResult.success()
                    .put("hit", false) //$NON-NLS-1$
                    .put(KEY_REASON, REASON_TIMEOUT)
                    .put(McpKeys.APPLICATION_ID, appId)
                    .put(KEY_THREAD_ID, threadId)
                    .toJson();
            }
            return WaitForBreakTool.buildSnapshotResponse(snapshot, registry, appId, false,
                serverTarget != null);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return ToolResult.error("interrupted").toJson(); //$NON-NLS-1$
        }
        catch (Exception e)
        {
            Activator.logError("Error in step", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the canonical applicationId for the stepped thread. The registry's
     * thread&rarr;appId mapping is authoritative (it carries the key recorded when this
     * threadId was registered); only when it is absent does this fall back to the same
     * canonical policy the resolver applies to every applicationId-based call
     * (owning-launch id first, minted id else). Returns {@code null} when undeterminable.
     */
    private static String resolveApplicationId(DebugSessionRegistry registry, long threadId,
            IThread thread, DebugServerTargetSupport.ServerTarget serverTarget)
    {
        String appId = registry.getThreadApplicationId(threadId);
        if (appId == null)
        {
            IDebugTarget owner = null;
            try
            {
                owner = thread.getDebugTarget();
            }
            catch (Exception ex)
            {
                // best-effort
            }
            appId = DebugTargetResolver.canonicalIdFor(owner, serverTarget);
        }
        return appId;
    }

    /**
     * Issues the requested step (over / into / out) on the suspended thread. Returns an
     * error-response JSON string when the step cannot be performed (capability missing or
     * unknown kind), or {@code null} when the step was issued successfully and the caller
     * should proceed to wait for the next suspend.
     */
    private static String performStep(IStep stepper, String kind) throws DebugException
    {
        switch (kind.toLowerCase())
        {
            case "over": //$NON-NLS-1$
                if (!stepper.canStepOver())
                {
                    return ToolResult.error("cannot step over").toJson(); //$NON-NLS-1$
                }
                stepper.stepOver();
                break;
            case "into": //$NON-NLS-1$
                if (!stepper.canStepInto())
                {
                    return ToolResult.error("cannot step into").toJson(); //$NON-NLS-1$
                }
                stepper.stepInto();
                break;
            case "out": //$NON-NLS-1$
            case "return": //$NON-NLS-1$
                if (!stepper.canStepReturn())
                {
                    return ToolResult.error("cannot step out").toJson(); //$NON-NLS-1$
                }
                stepper.stepReturn();
                break;
            default:
                return ToolResult.error("unknown kind: " + kind).toJson(); //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Returns the debug-server target that owns the given thread (by identity of
     * the thread's {@link IDebugTarget}), or {@code null} when the thread belongs
     * to an ordinary Eclipse launch. Delegates the identity scan to
     * {@link DebugTargetResolver#serverTargetForTarget} (the shared, null-safe
     * implementation), keeping only the thread-to-target hop here.
     *
     * @param thread the suspended thread being stepped
     * @return the owning server target, or {@code null}
     */
    private static DebugServerTargetSupport.ServerTarget serverTargetOf(IThread thread)
    {
        IDebugTarget owner;
        try
        {
            owner = thread.getDebugTarget();
        }
        catch (Exception ex)
        {
            // best-effort — treat as a non-server thread
            return null;
        }
        return owner == null ? null : DebugTargetResolver.serverTargetForTarget(owner);
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

}
