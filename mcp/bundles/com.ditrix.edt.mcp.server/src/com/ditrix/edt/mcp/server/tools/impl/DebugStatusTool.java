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
        return "Report active debug launches: applicationId (real or synthetic 'attach:<name>'), " //$NON-NLS-1$
            + "launch configuration name/type, mode (debug/run), whether the target is currently " //$NON-NLS-1$
            + "suspended, thread count, and the line of the top suspended frame. " //$NON-NLS-1$
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

            return ToolResult.success()
                .put("launches", launches) //$NON-NLS-1$
                .put("count", launches.size()) //$NON-NLS-1$
                .put("registry", registryInfo) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in debug_status", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }
}
