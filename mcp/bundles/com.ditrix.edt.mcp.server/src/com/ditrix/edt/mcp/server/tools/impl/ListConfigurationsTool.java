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
        return "List EDT launch configurations (runtime client + Attach + other 1C types) with " //$NON-NLS-1$
            + "their running state. This is the discovery step before debug_launch / " //$NON-NLS-1$
            + "run_yaxunit_tests / debug_yaxunit_tests / update_database: use the returned 'name' " //$NON-NLS-1$
            + "as their launchConfigurationName. Use type='attach' for server-side debug setups, " //$NON-NLS-1$
            + "type='client' for 1C:Enterprise client configs, or type='all' (default). " //$NON-NLS-1$
            + "Full parameters and examples: call get_tool_guide('list_configurations')."; //$NON-NLS-1$
    }

    @Override
    public String getInputSchema()
    {
        return JsonSchemaBuilder.object()
            .enumProperty("type", //$NON-NLS-1$
                "Filter by config kind; default 'all'. e.g. 'attach' for server-side debug.", //$NON-NLS-1$
                "attach", "client", "all") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .stringProperty("projectName", "Optional exact project-name filter.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getGuide()
    {
        return "Lists EDT launch configurations — runtime client, Attach (RemoteRuntime / " //$NON-NLS-1$
            + "LocalRuntime), and any other config in the 1C/EDT namespace — together with " //$NON-NLS-1$
            + "their current running state. Once the client knows the exact `name`, it can target " //$NON-NLS-1$
            + "that configuration by name without juggling applicationId/project pairs.\n\n" //$NON-NLS-1$

            + "## When to use\n" //$NON-NLS-1$
            + "- Discovery step before `debug_launch`, `run_yaxunit_tests`, " //$NON-NLS-1$
            + "`debug_yaxunit_tests` and `update_database` — copy the returned `name` into " //$NON-NLS-1$
            + "their `launchConfigurationName`.\n" //$NON-NLS-1$
            + "- See whether a configuration is already running (and whether it is paused on a " //$NON-NLS-1$
            + "breakpoint) before launching a second client.\n\n" //$NON-NLS-1$

            + "## Server-side debug workflow\n" //$NON-NLS-1$
            + "1. `list_configurations({type: \"attach\"})` — see available Attach configs, " //$NON-NLS-1$
            + "their infobase aliases, and whether any is already running.\n" //$NON-NLS-1$
            + "2. `debug_launch({launchConfigurationName: ...})` — attach to it.\n" //$NON-NLS-1$
            + "3. `set_breakpoint` → `wait_for_break` → standard debug flow.\n\n" //$NON-NLS-1$

            + "## Parameter details\n" //$NON-NLS-1$
            + "- `type` — filter by config kind:\n" //$NON-NLS-1$
            + "  - `attach`: RemoteRuntime + LocalRuntime (server-side debug: HTTP services, " //$NON-NLS-1$
            + "background jobs).\n" //$NON-NLS-1$
            + "  - `client`: RuntimeClient (1C:Enterprise client configs). The aliases " //$NON-NLS-1$
            + "`runtime` and `runtimeClient` are also accepted.\n" //$NON-NLS-1$
            + "  - `all` (default): any 1C/EDT launch config. An unknown value is treated " //$NON-NLS-1$
            + "permissively as `all`.\n" //$NON-NLS-1$
            + "- `projectName` — optional; keeps only configs whose project attribute equals " //$NON-NLS-1$
            + "this value exactly. Omit to list across all projects.\n\n" //$NON-NLS-1$

            + "## Result fields (per entry)\n" //$NON-NLS-1$
            + "- `name` — the configuration name; this is the value to pass as " //$NON-NLS-1$
            + "`launchConfigurationName` downstream.\n" //$NON-NLS-1$
            + "- `type` — the launch-config type id.\n" //$NON-NLS-1$
            + "- `attach` — boolean, true for Attach configs.\n" //$NON-NLS-1$
            + "- `applicationId` — real applicationId, or a synthetic `attach:<name>` for " //$NON-NLS-1$
            + "Attach configs (present only when known).\n" //$NON-NLS-1$
            + "- `project`, `infobaseAlias`, `debugServerUrl` — present only when the config " //$NON-NLS-1$
            + "defines them.\n" //$NON-NLS-1$
            + "- `running` — boolean; when true, `mode` (debug/run) is added, and " //$NON-NLS-1$
            + "`suspended` is true when a thread is paused on a breakpoint.\n\n" //$NON-NLS-1$

            + "## Notes\n" //$NON-NLS-1$
            + "- Returns JSON: a `configurations` array plus a `count`.\n" //$NON-NLS-1$
            + "- Only the `launchConfigurationName` mode of `debug_launch` can start an Attach " //$NON-NLS-1$
            + "session — the `projectName + applicationId` mode reaches runtime-client " //$NON-NLS-1$
            + "configs only.\n"; //$NON-NLS-1$
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
            return ToolResult.error(e.getMessage()).toJson(); //$NON-NLS-1$
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
