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
            .stringProperty("applicationId", "Optional application id filter") //$NON-NLS-1$ //$NON-NLS-2$
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
        String filterAppId = JsonUtils.extractStringArgument(params, "applicationId"); //$NON-NLS-1$

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

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("applicationId", appId); //$NON-NLS-1$
                entry.put("mode", launch.getLaunchMode()); //$NON-NLS-1$
                entry.put("debug", ILaunchManager.DEBUG_MODE.equals(launch.getLaunchMode())); //$NON-NLS-1$

                ILaunchConfiguration config = launch.getLaunchConfiguration();
                if (config != null)
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

                IDebugTarget[] targets = launch.getDebugTargets();
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
                entry.put("registered", DebugSessionRegistry.get().hasSnapshot(appId)); //$NON-NLS-1$
                launches.add(entry);
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
     * Enumerates the debug-server targets EDT tracks via
     * {@code IRuntimeDebugClientTargetManager.listDebugTargets()} and enriches each
     * with its REAL suspend state (walking the target's threads through the Eclipse
     * {@link IThread} interface — the 1C target implements
     * {@code org.eclipse.debug.core.model.IDebugTarget}). Surfaces server-side
     * suspends from {@code debug_yaxunit_tests} and EDT-UI-started "Debug As"
     * sessions that the Eclipse {@code ILaunchManager} view misses, and exposes a
     * stable, addressable {@code applicationId} so the other debug tools can resolve
     * them. Fully guarded — never throws (empty when the manager service is absent).
     *
     * @param filterAppId optional applicationId filter (matches the minted
     *     {@code ServerApplication.<app>} id or the bare application name)
     * @return list of server-target descriptors (possibly empty)
     */
    private static List<Map<String, Object>> listDebugServerTargets(String filterAppId)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DebugServerTargetSupport.ServerTarget st : DebugServerTargetSupport.listServerTargets())
        {
            if (filterAppId != null && !filterAppId.isEmpty())
            {
                boolean matches = filterAppId.equals(st.applicationId)
                    || filterAppId.equals(st.application)
                    || (st.applicationId != null
                        && st.applicationId.equals(DebugServerTargetSupport.SERVER_APP_ID_PREFIX + filterAppId));
                if (!matches)
                {
                    continue;
                }
            }
            out.add(DebugServerTargetSupport.describe(st));
        }
        return out;
    }
}
