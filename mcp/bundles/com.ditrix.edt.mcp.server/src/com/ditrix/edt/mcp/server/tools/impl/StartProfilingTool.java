/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.Map;

import org.eclipse.debug.core.model.IDebugTarget;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.McpKeys;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.ProfilingSupport;
import com.ditrix.edt.mcp.server.utils.ProfilingSessionRegistry;

/**
 * Starts 1C performance measurement on the active
 * debug target. Once enabled, every executed BSL line is tracked with call count
 * and timing. Call {@code stop_profiling} to stop, and
 * {@code get_profiling_results} after the test finishes to retrieve which code
 * was covered.
 *
 * <p>Uses reflection to access {@code IProfilingService} via
 * {@code ServiceAccess.get()} from the {@code com._1c.g5.wiring} bundle,
 * and {@code IProfileTarget.toggleProfiling()} on the debug target.
 *
 * <p><b>Determinism.</b> The underlying EDT API ({@code IProfilingService})
 * exposes only a single {@code toggleProfiling(target)} primitive and no public
 * "is profiling active" query, so {@link ProfilingSessionRegistry} tracks the state by
 * {@code applicationId} — as the single source of truth shared with
 * {@code stop_profiling} and {@code get_profiling_results}. {@code start_profiling}
 * is start-only and idempotent: calling it while profiling is already active does
 * not toggle profiling off — it returns an "already profiling" result instead.
 */
public class StartProfilingTool implements IMcpTool
{
    public static final String NAME = "start_profiling"; //$NON-NLS-1$

    /** Output key: whether profiling is currently active for the application id. */
    private static final String KEY_ACTIVE = "active"; //$NON-NLS-1$

    /** Output key: whether this call started profiling (vs. already active). */
    private static final String KEY_STARTED = "started"; //$NON-NLS-1$

    static final Object TRANSITIONS = ProfilingSessionRegistry.TRANSITIONS;

    public static boolean isProfilingActive(String applicationId)
    {
        return ProfilingSessionRegistry.isProfilingActive(applicationId);
    }

    static void reconcile(String applicationId, IDebugTarget target)
    {
        ProfilingSessionRegistry.reconcile(applicationId, target);
    }

    static IDebugTarget profiledTarget(String applicationId, IDebugTarget lookedUp)
    {
        return ProfilingSessionRegistry.profiledTarget(applicationId, lookedUp);
    }

    static IDebugTarget profiledTarget(Object owner, IDebugTarget lookedUp, java.util.function.Predicate<IDebugTarget> ended)
    {
        return ProfilingSessionRegistry.profiledTarget(owner, lookedUp, ended);
    }

    static Object ownerAfterReading(Object current, IDebugTarget target, boolean enabled, java.util.function.Predicate<IDebugTarget> ended)
    {
        return ProfilingSessionRegistry.ownerAfterReading(current, target, enabled, ended);
    }

    static boolean ended(IDebugTarget target)
    {
        return ProfilingSessionRegistry.ended(target);
    }

    static boolean ended(IDebugTarget target, java.util.function.Predicate<org.eclipse.debug.core.ILaunch> registered)
    {
        return ProfilingSessionRegistry.ended(target, registered);
    }

    public static boolean isAnyProfilingActive()
    {
        return ProfilingSessionRegistry.isAnyProfilingActive();
    }

    static void markActive(String applicationId)
    {
        ProfilingSessionRegistry.markActive(applicationId);
    }

    static void markActive(String applicationId, IDebugTarget target)
    {
        ProfilingSessionRegistry.markActive(applicationId, target);
    }

    static void markInactive(String applicationId)
    {
        ProfilingSessionRegistry.markInactive(applicationId);
    }

    static void clearStateForTests()
    {
        ProfilingSessionRegistry.clearStateForTests();
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Measure execution time and coverage of BSL code in a debug session. Parameters and examples: " //$NON-NLS-1$
            + "get_tool_guide('start_profiling')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the running debug session (required)", true) //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .booleanProperty(KEY_ACTIVE, "Whether profiling is now active for this applicationId") //$NON-NLS-1$
            .booleanProperty(KEY_STARTED, "True if this call started profiling, false if already active") //$NON-NLS-1$
            .stringProperty(McpKeys.APPLICATION_ID, "Application id of the profiled debug session") //$NON-NLS-1$
            .stringProperty(McpKeys.MESSAGE, "Human-readable status and next-step guidance") //$NON-NLS-1$
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
        String err = JsonUtils.requireArgument(params, McpKeys.APPLICATION_ID);
        if (err != null)
        {
            return err;
        }
        String applicationId = JsonUtils.extractStringArgument(params, McpKeys.APPLICATION_ID);
        synchronized (TRANSITIONS)
        {
            return start(applicationId);
        }
    }

    private static String start(String applicationId)
    {
        try
        {
            IDebugTarget lookedUp = DebugSessionRegistry.findActiveTarget(applicationId);
            // Read EDT's state of the target that owns the flag, so a run stopped elsewhere is noticed.
            IDebugTarget owner = profiledTarget(applicationId, lookedUp);
            if (owner != null && ProfilingSupport.enabled(ProfilingSupport.service(), owner) == null)
            {
                return ToolResult.error("Profiling state is unknown; EDT's profiling state could not be read. "
                    + "Inspect the profiling view before retrying; no toggle was sent.").toJson();
            }

            reconcile(applicationId, owner);
            // The toggle acts on the looked-up target, so its own state is read too before it is toggled.
            if (lookedUp != null && lookedUp != owner && !isProfilingActive(applicationId))
            {
                reconcile(applicationId, lookedUp);
            }
            IDebugTarget target = lookedUp;
            // Start-only: if we already track profiling as active for this id, do
            // NOT toggle (toggling would silently switch profiling OFF). Return an
            // idempotent "already profiling" result instead.
            if (isProfilingActive(applicationId))
            {
                return ToolResult.success()
                    .put(KEY_ACTIVE, true)
                    .put(KEY_STARTED, false)
                    .put(McpKeys.APPLICATION_ID, applicationId)
                    .put(McpKeys.MESSAGE, "Profiling is already active for this applicationId. " //$NON-NLS-1$
                        + "Run your test, then call stop_profiling and get_profiling_results.") //$NON-NLS-1$
                    .toJson();
            }

            if (target == null)
            {
                return ToolResult.error("No active debug target for applicationId: " + applicationId //$NON-NLS-1$
                    + ". Start a debug session first (launch or debug_yaxunit_tests).").toJson(); //$NON-NLS-1$
            }

            // Resolve the profiling service + profile target and flip profiling on.
            // Gated above on our shared OFF state, so this toggle deterministically
            // switches profiling ON.
            String toggleError = ProfilingSupport.startProfilingIfKnown(ProfilingSupport.service(), target);
            if (toggleError != null)
            {
                return ToolResult.error(toggleError).toJson();
            }

            markActive(applicationId, target);

            Activator.logInfo("Profiling started via IProfilingService for applicationId=" + applicationId); //$NON-NLS-1$

            return ToolResult.success()
                .put(KEY_ACTIVE, true)
                .put(KEY_STARTED, true)
                .put(McpKeys.APPLICATION_ID, applicationId)
                .put(McpKeys.MESSAGE, "Profiling started. Run your test, then call stop_profiling " //$NON-NLS-1$
                    + "and get_profiling_results.") //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in start_profiling", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
