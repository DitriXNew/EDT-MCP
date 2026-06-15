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

    /** type filter value: 1C:Enterprise runtime-client configs. */
    private static final String KEY_CLIENT = "client"; //$NON-NLS-1$

    /** Token "attach": the server-side Attach type filter value and per-config output flag. */
    private static final String KEY_ATTACH = "attach"; //$NON-NLS-1$

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
                KEY_ATTACH, KEY_CLIENT, "all") //$NON-NLS-1$
            .stringProperty("projectName", "Optional exact project-name filter.") //$NON-NLS-1$ //$NON-NLS-2$
            .build();
    }

    @Override
    public String getOutputSchema()
    {
        return JsonSchemaBuilder.object()
            .booleanProperty("success", "Whether the operation succeeded", true) //$NON-NLS-1$ //$NON-NLS-2$
            .objectArrayProperty("configurations", //$NON-NLS-1$
                "Matching launch configurations with their state.") //$NON-NLS-1$
            .integerProperty("count", "Number of configurations returned.") //$NON-NLS-1$ //$NON-NLS-2$
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

        // Reject an out-of-set type instead of silently treating it as 'all' (the
        // schema declares the enum; honour it at runtime too).
        if (typeFilter != null && !typeFilter.isEmpty() && !isKnownTypeFilter(typeFilter))
        {
            return ToolResult.error("Invalid type: '" + typeFilter //$NON-NLS-1$
                + "'. Must be one of: all, attach, client.").toJson(); //$NON-NLS-1$
        }

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

                configs.add(buildConfigEntry(cfg, typeId, isAttach, project, liveByAppId));
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

    /**
     * Builds the JSON entry for one launch configuration: its name/type/attach flag plus any present
     * applicationId, project, infobase alias and debug server URL, and — when a matching live launch
     * exists — its running/mode/suspended state. The caller has already applied the type and project
     * filters; this is pure entry assembly with no side effects.
     */
    private static Map<String, Object> buildConfigEntry(ILaunchConfiguration cfg, String typeId,
        boolean isAttach, String project, Map<String, ILaunch> liveByAppId)
    {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", cfg.getName()); //$NON-NLS-1$
        entry.put("type", typeId); //$NON-NLS-1$
        entry.put(KEY_ATTACH, isAttach);

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
        return entry;
    }

    /**
     * Whether {@code filter} is an accepted type token. {@code all}/{@code attach}/
     * {@code client} are the schema enum; {@code runtime}/{@code runtimeClient} are
     * tolerated aliases of {@code client}. A genuinely-unknown value is rejected in
     * {@link #execute} before the listing loop runs.
     */
    private static boolean isKnownTypeFilter(String filter)
    {
        return "all".equalsIgnoreCase(filter) || KEY_ATTACH.equalsIgnoreCase(filter) //$NON-NLS-1$
            || KEY_CLIENT.equalsIgnoreCase(filter) || "runtime".equalsIgnoreCase(filter) //$NON-NLS-1$
            || "runtimeClient".equalsIgnoreCase(filter); //$NON-NLS-1$
    }

    private static boolean matchesTypeFilter(String filter, boolean isAttach, boolean isClient)
    {
        if (filter == null || filter.isEmpty() || "all".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return true;
        }
        if (KEY_ATTACH.equalsIgnoreCase(filter))
        {
            return isAttach;
        }
        if (KEY_CLIENT.equalsIgnoreCase(filter) || "runtime".equalsIgnoreCase(filter) //$NON-NLS-1$
            || "runtimeClient".equalsIgnoreCase(filter)) //$NON-NLS-1$
        {
            return isClient;
        }
        // Unreachable for unknown values: execute() rejects them upstream via
        // isKnownTypeFilter. Kept as a defensive default.
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
