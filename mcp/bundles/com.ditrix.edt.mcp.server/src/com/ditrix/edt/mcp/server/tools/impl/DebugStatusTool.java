/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IDebugTarget;
import org.eclipse.debug.core.model.IStackFrame;
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.DebugServerTargetSupport;
import com.ditrix.edt.mcp.server.utils.DebugSessionRegistry;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Reports active debug launches and their suspend state. If {@code applicationId}
 * is given the response is filtered to that one launch; otherwise all currently
 * tracked launches are returned. Covers both runtime-client and Attach debug
 * configurations (synthetic {@code attach:<configName>} ids are reported for
 * attach launches that don't carry {@code ATTR_APPLICATION_ID}).
 */
public class DebugStatusTool implements IMcpTool
{
    public static final String NAME = "debug_status"; //$NON-NLS-1$

    private static final String APPLICATION_ID = "applicationId"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "Report active debug sessions: applicationId (real or synthetic 'attach:<name>' / " //$NON-NLS-1$
            + "'launch:<name>'), launch configuration name/type, mode (debug/run), whether the " //$NON-NLS-1$
            + "target is currently suspended, thread count, and the line of the top suspended frame. " //$NON-NLS-1$
            + "Also reports debugServerTargets: 1C debug-server sessions (server-side suspends, " //$NON-NLS-1$
            + "EDT-UI-started 'Debug As') addressable as 'ServerApplication.<app>'. " //$NON-NLS-1$
            + "Optionally filter by applicationId."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty(APPLICATION_ID, "Optional application id filter") //$NON-NLS-1$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("launches", "Active debug launches with state and frame info") //$NON-NLS-1$ //$NON-NLS-2$
            .integerProperty("count", "Number of active debug launches returned") //$NON-NLS-1$ //$NON-NLS-2$
            .objectProperty("registry", "Debug session registry snapshot counters") //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("debugServerTargets", //$NON-NLS-1$
                "1C debug-server targets (server-side / EDT-UI sessions) with real suspend state") //$NON-NLS-1$
            .integerProperty("debugServerTargetCount", "Number of debug-server targets returned") //$NON-NLS-1$ //$NON-NLS-2$
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
        String filterAppId = JsonUtils.extractStringArgument(params, APPLICATION_ID);

        DebugSessionRegistry.get().ensureListenerRegistered();

        try
        {
            DebugPlugin debugPlugin = DebugPlugin.getDefault();
            if (debugPlugin == null)
            {
                return ToolResult.error("DebugPlugin not available").toJson(); //$NON-NLS-1$
            }
            ILaunchManager mgr = debugPlugin.getLaunchManager();

            List<Map<String, Object>> launches = new ArrayList<>();
            for (ILaunch launch : mgr.getLaunches())
            {
                if (launch.isTerminated())
                {
                    continue;
                }
                String appId = DebugSessionRegistry.findApplicationIdFor(launch);
                // Skip non-EDT launches entirely (e.g. Java apps, Ant tasks).
                if (appId == null)
                {
                    continue;
                }
                if (filterAppId != null && !filterAppId.isEmpty() && !filterAppId.equals(appId))
                {
                    continue;
                }

                launches.add(buildLaunchEntry(launch, appId));
            }

            Map<String, Object> registryInfo = DebugSessionRegistry.get().snapshotInfo();
            List<Map<String, Object>> serverTargets = listDebugServerTargets(filterAppId);

            return ToolResult.success()
                .put("launches", launches) //$NON-NLS-1$
                .put("count", launches.size()) //$NON-NLS-1$
                .put("registry", registryInfo) //$NON-NLS-1$
                .put("debugServerTargets", serverTargets) //$NON-NLS-1$
                .put("debugServerTargetCount", serverTargets.size()) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in debug_status", e); //$NON-NLS-1$
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Builds the descriptor map for a single (non-terminated, EDT) debug launch in the
     * exact field order/shape the JSON response expects: applicationId, mode/debug,
     * launch-configuration attributes (when a config is present), thread count, suspend
     * state and the registered flag.
     *
     * @param launch the live launch
     * @param appId the resolved (real or synthetic) application id for the launch
     * @return the launch entry map
     */
    private static Map<String, Object> buildLaunchEntry(ILaunch launch, String appId)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(APPLICATION_ID, appId);
        entry.put("mode", launch.getLaunchMode()); //$NON-NLS-1$
        entry.put("debug", ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())); //$NON-NLS-1$

        ILaunchConfiguration config = launch.getLaunchConfiguration();
        if (config != null)
        {
            appendLaunchConfigInfo(entry, config);
        }

        appendSuspendState(entry, launch.getDebugTargets());
        entry.put("registered", DebugSessionRegistry.get().hasSnapshot(appId)); //$NON-NLS-1$
        return entry;
    }

    /**
     * Adds the launch-configuration-derived fields to the entry: configuration name,
     * type id and attach flag (always), plus project/infobaseAlias/debugServerUrl only
     * when those attributes are non-empty (matching the original conditional emission).
     *
     * @param entry the launch entry being populated
     * @param config the launch configuration (non-null)
     */
    private static void appendLaunchConfigInfo(Map<String, Object> entry, ILaunchConfiguration config)
    {
        entry.put("launchConfiguration", config.getName()); //$NON-NLS-1$
        String typeId = LaunchConfigUtils.getConfigTypeId(config);
        entry.put("configurationType", typeId); //$NON-NLS-1$
        entry.put("attach", LaunchConfigUtils.isAttachConfigTypeId(typeId)); //$NON-NLS-1$
        String project = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
        if (!project.isEmpty())
        {
            entry.put("project", project); //$NON-NLS-1$
        }
        String alias = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
        if (!alias.isEmpty())
        {
            entry.put("infobaseAlias", alias); //$NON-NLS-1$
        }
        String url = LaunchConfigUtils.readAttribute(config,
            LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
        if (!url.isEmpty())
        {
            entry.put("debugServerUrl", url); //$NON-NLS-1$
        }
    }

    /**
     * Walks the launch's debug targets/threads (best-effort, swallowing per-target
     * exceptions as before) and adds threadCount, suspended and — when a suspended
     * thread with a top frame is found — suspendedAt to the entry. The first suspended
     * top frame seen wins suspendedAt, matching the original iteration order.
     *
     * @param entry the launch entry being populated
     * @param targets the launch's debug targets
     */
    private static void appendSuspendState(Map<String, Object> entry, IDebugTarget[] targets)
    {
        int threadCount = 0;
        boolean anySuspended = false;
        String suspendedAt = null;
        for (IDebugTarget t : targets)
        {
            if (t == null || t.isTerminated())
            {
                continue;
            }
            try
            {
                for (IThread th : t.getThreads())
                {
                    threadCount++;
                    if (th.isSuspended())
                    {
                        anySuspended = true;
                        if (suspendedAt == null)
                        {
                            IStackFrame top = th.getTopStackFrame();
                            if (top != null)
                            {
                                suspendedAt = top.getName() + " @ " + top.getLineNumber(); //$NON-NLS-1$
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                // best-effort
            }
        }
        entry.put("threadCount", threadCount); //$NON-NLS-1$
        entry.put("suspended", anySuspended); //$NON-NLS-1$
        if (suspendedAt != null)
        {
            entry.put("suspendedAt", suspendedAt); //$NON-NLS-1$
        }
    }

    /**
     * Enumerates the debug-server targets {@code DebugServerTargetSupport} reports
     * and enriches each with its REAL suspend state (walking the target's threads
     * through the Eclipse {@link IThread} interface — the 1C target implements
     * {@code org.eclipse.debug.core.model.IDebugTarget}). Surfaces server-side
     * suspends from {@code debug_yaxunit_tests} and EDT-UI-started "Debug As"
     * sessions that the Eclipse {@code ILaunchManager} view misses, and exposes a
     * stable, addressable {@code applicationId} so the other debug tools can resolve
     * them. A thin-client session owned by a registered launch is NOT listed here —
     * it already appears in {@code launches[]}, and duplicating it under a minted
     * {@code ServerApplication.<app>} id misreported every client session as a
     * server target. Fully guarded — never throws (empty when the manager service
     * is absent).
     *
     * @param filterAppId optional applicationId filter — matched with the SAME
     *     predicate the resolver uses
     *     ({@link DebugServerTargetSupport#matchesServerTargetId}): the minted
     *     {@code ServerApplication.<app>} id (exact or bare form), the bare
     *     application name, the debug server URL, or the owning launch's id
     * @return list of server-target descriptors (possibly empty)
     */
    private static List<Map<String, Object>> listDebugServerTargets(String filterAppId)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DebugServerTargetSupport.ServerTarget st : DebugServerTargetSupport.listServerTargets())
        {
            if (filterAppId != null && !filterAppId.isEmpty()
                && !DebugServerTargetSupport.matchesServerTargetId(st, filterAppId))
            {
                continue;
            }
            out.add(DebugServerTargetSupport.describe(st));
        }
        return out;
    }
}
