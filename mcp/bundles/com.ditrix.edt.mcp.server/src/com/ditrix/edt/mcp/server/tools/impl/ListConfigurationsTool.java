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
import org.eclipse.debug.core.model.IThread;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.JsonSchemaBuilder;
import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.tools.IMcpTool;
import com.ditrix.edt.mcp.server.utils.LaunchConfigUtils;

/**
 * Lists EDT launch configurations — runtime-client, Attach (RemoteRuntime /
 * LocalRuntime), and any other config in the 1C/EDT namespace — together with
 * their current running state. This is the discovery step that precedes
 * {@code debug_launch}, {@code run_yaxunit_tests}, {@code debug_yaxunit_tests}
 * and {@code update_database}: once the MCP client knows the exact
 * {@code name}, it can target that configuration by name without having to
 * juggle applicationId/project pairs.
 *
 * <p>Intended workflow for server-side debugging:
 * <ol>
 *   <li>{@code list_configurations({type: "attach"})} — see available Attach
 *       configs, their infobase aliases, and whether any is already running.</li>
 *   <li>{@code debug_launch({launchConfigurationName: ...})} — attach to it.</li>
 *   <li>{@code set_breakpoint} → {@code wait_for_break} → standard debug flow.</li>
 * </ol>
 */
public class ListConfigurationsTool implements IMcpTool
{
    public static final String NAME = "list_configurations"; //$NON-NLS-1$

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public String getDescription()
    {
        return "List EDT launch configurations (runtime client + Attach + other 1C types) " //$NON-NLS-1$
            + "with their current running state. Each entry carries the configuration name " //$NON-NLS-1$
            + "(use it as launchConfigurationName in debug_launch / run_yaxunit_tests / " //$NON-NLS-1$
            + "debug_yaxunit_tests / update_database), type id, attach flag, applicationId " //$NON-NLS-1$
            + "(real or synthetic 'attach:<name>'), project, infobase alias, debug server URL, " //$NON-NLS-1$
            + "and a 'running' flag (plus 'suspended' when the session is paused on a breakpoint). " //$NON-NLS-1$
            + "Use type='attach' for server-side debug setups (HTTP services, background jobs), " //$NON-NLS-1$
            + "type='client' for 1C:Enterprise client configs, or type='all' (default)."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .stringProperty("type", //$NON-NLS-1$
                "Filter: 'attach' (RemoteRuntime + LocalRuntime), 'client' (RuntimeClient), " //$NON-NLS-1$
                    + "or 'all' (default — any 1C/EDT launch config)") //$NON-NLS-1$
            .stringProperty("projectName", "Optional project name filter") //$NON-NLS-1$ //$NON-NLS-2$
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
        String typeFilter = JsonUtils.extractStringArgument(params, "type"); //$NON-NLS-1$
        String projectFilter = JsonUtils.extractStringArgument(params, "projectName"); //$NON-NLS-1$

        try
        {
            ILaunchManager launchManager = LaunchConfigUtils.getLaunchManager();
            if (launchManager == null)
            {
                return ToolResult.error("Launch manager is not available").toJson(); //$NON-NLS-1$
            }

            Map<String, ILaunch> liveByAppId = indexLiveLaunches(launchManager);

            List<Map<String, Object>> configs = new ArrayList<>();
            for (ILaunchConfiguration cfg : LaunchConfigUtils.getAllEdtConfigs(launchManager))
            {
                String typeId = LaunchConfigUtils.getConfigTypeId(cfg);
                boolean isAttach = LaunchConfigUtils.isAttachConfigTypeId(typeId);
                boolean isClient = LaunchConfigUtils.LAUNCH_CONFIG_TYPE_ID.equals(typeId);

                if (!matchesTypeFilter(typeFilter, isAttach, isClient))
                {
                    continue;
                }

                String project = LaunchConfigUtils.readAttribute(cfg,
                    LaunchConfigUtils.ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                if (projectFilter != null && !projectFilter.isEmpty()
                    && !projectFilter.equals(project))
                {
                    continue;
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", cfg.getName()); //$NON-NLS-1$
                entry.put("type", typeId); //$NON-NLS-1$
                entry.put("attach", isAttach); //$NON-NLS-1$

                String appId = LaunchConfigUtils.getApplicationIdFor(cfg);
                if (appId != null)
                {
                    entry.put("applicationId", appId); //$NON-NLS-1$
                }
                if (!project.isEmpty())
                {
                    entry.put("project", project); //$NON-NLS-1$
                }

                String alias = LaunchConfigUtils.readAttribute(cfg,
                    LaunchConfigUtils.ATTR_DEBUG_INFOBASE_ALIAS, ""); //$NON-NLS-1$
                if (!alias.isEmpty())
                {
                    entry.put("infobaseAlias", alias); //$NON-NLS-1$
                }
                String url = LaunchConfigUtils.readAttribute(cfg,
                    LaunchConfigUtils.ATTR_DEBUG_SERVER_URL, ""); //$NON-NLS-1$
                if (!url.isEmpty())
                {
                    entry.put("debugServerUrl", url); //$NON-NLS-1$
                }

                ILaunch liveLaunch = appId != null ? liveByAppId.get(appId) : null;
                boolean running = liveLaunch != null;
                entry.put("running", running); //$NON-NLS-1$
                if (running)
                {
                    entry.put("mode", liveLaunch.getLaunchMode()); //$NON-NLS-1$
                    entry.put("suspended", anyThreadSuspended(liveLaunch)); //$NON-NLS-1$
                }
                configs.add(entry);
            }

            return ToolResult.success()
                .put("configurations", configs) //$NON-NLS-1$
                .put("count", configs.size()) //$NON-NLS-1$
                .toJson();
        }
        catch (Exception e)
        {
            Activator.logError("Error in list_configurations", e); //$NON-NLS-1$
            return ToolResult.error("Error: " + e.getMessage()).toJson(); //$NON-NLS-1$
        }
    }

    private static boolean matchesTypeFilter(String filter, boolean isAttach, boolean isClient)
    {
        if (filter == null || filter.isEmpty() || "all".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return true;
        }
        if ("attach".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return isAttach;
        }
        if ("client".equalsIgnoreCase(filter) || "runtime".equalsIgnoreCase(filter) //$NON-NLS-1$ //$NON-NLS-2$
            || "runtimeClient".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return isClient;
        }
        // Unknown filter — be permissive.
        return true;
    }

    private static Map<String, ILaunch> indexLiveLaunches(ILaunchManager mgr)
    {
        Map<String, ILaunch> map = new LinkedHashMap<>();
        DebugPlugin plugin = DebugPlugin.getDefault();
        if (plugin == null)
        {
            return map;
        }
        for (ILaunch launch : mgr.getLaunches())
        {
            if (launch.isTerminated())
            {
                continue;
            }
            String appId = LaunchConfigUtils.getApplicationIdFor(launch);
            if (appId != null)
            {
                map.putIfAbsent(appId, launch);
            }
        }
        return map;
    }

    private static boolean anyThreadSuspended(ILaunch launch)
    {
        try
        {
            for (IDebugTarget t : launch.getDebugTargets())
            {
                if (t == null || t.isTerminated())
                {
                    continue;
                }
                for (IThread th : t.getThreads())
                {
                    if (th.isSuspended())
                    {
                        return true;
                    }
                }
            }
        }
        catch (Exception ex)
        {
            // best-effort
        }
        return false;
    }
}
