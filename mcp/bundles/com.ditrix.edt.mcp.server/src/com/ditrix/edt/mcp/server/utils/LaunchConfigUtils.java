/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared helpers for searching and inspecting 1C:EDT launch configurations.
 *
 * <p>Covers two families:
 * <ul>
 *   <li>Runtime client configs ({@link #LAUNCH_CONFIG_TYPE_ID}) — launch a new
 *       1cv8c client and attach the debugger to it. Carry both
 *       {@link #ATTR_PROJECT_NAME} and {@link #ATTR_APPLICATION_ID}.</li>
 *   <li>Attach configs ({@link #TYPE_REMOTE_RUNTIME}, {@link #TYPE_LOCAL_RUNTIME})
 *       — attach to an already-running 1C:Enterprise debug server (ragent/rphost).
 *       These carry {@link #ATTR_PROJECT_NAME} but typically no
 *       {@link #ATTR_APPLICATION_ID}; instead the infobase is identified via
 *       {@link #ATTR_DEBUG_INFOBASE_ALIAS}, {@link #ATTR_INFOBASE_UUID} and
 *       {@link #ATTR_DEBUG_SERVER_URL}.</li>
 * </ul>
 */
public final class LaunchConfigUtils
{
    /**
     * Poll interval (milliseconds) for waiting on launch state transitions —
     * termination, disconnection, DB update settling. Shared by all callers
     * that need to spin-wait on Eclipse debug API state.
     */
    public static final int LAUNCH_POLL_INTERVAL_MS = 100;

    /** 1C:EDT Runtime Client launch configuration type id. */
    public static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Attach to 1C:Enterprise Debug Server (remote cluster debug server). */
    public static final String TYPE_REMOTE_RUNTIME = "com._1c.g5.v8.dt.debug.core.RemoteRuntime"; //$NON-NLS-1$

    /** Attach to a locally spawned debug server. */
    public static final String TYPE_LOCAL_RUNTIME = "com._1c.g5.v8.dt.debug.core.LocalRuntime"; //$NON-NLS-1$

    /** All debug-launch config types understood by this plugin. */
    public static final List<String> ALL_DEBUG_CONFIG_TYPE_IDS = Collections.unmodifiableList(
        Arrays.asList(LAUNCH_CONFIG_TYPE_ID, TYPE_REMOTE_RUNTIME, TYPE_LOCAL_RUNTIME));

    /** Launch configuration attribute: target project name. */
    public static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    /** Launch configuration attribute: target application id. */
    public static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$

    /** Launch configuration attribute: startup option string passed to 1cv8c.exe via /C. */
    public static final String ATTR_STARTUP_OPTION = "com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION"; //$NON-NLS-1$

    /** Attach configs: infobase alias used by the cluster (e.g. "mr_tradev8"). */
    public static final String ATTR_DEBUG_INFOBASE_ALIAS = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_INFOBASE_ALIAS"; //$NON-NLS-1$

    /** Attach configs: infobase UUID (alternative to alias). */
    public static final String ATTR_INFOBASE_UUID = "com._1c.g5.v8.dt.debug.core.ATTR_INFOBASE_UUID"; //$NON-NLS-1$

    /** Remote attach: URL of the HTTP debug server (e.g. "http://localhost:1550"). */
    public static final String ATTR_DEBUG_SERVER_URL = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_SERVER_URL"; //$NON-NLS-1$

    /** Synthetic applicationId prefix for Attach launches that don't carry ATTR_APPLICATION_ID. */
    public static final String ATTACH_APP_ID_PREFIX = "attach:"; //$NON-NLS-1$

    /**
     * Synthetic applicationId prefix for any other EDT/1C debug launch that carries
     * no real {@code ATTR_APPLICATION_ID} and is not an Attach config — typically a
     * session a user started from the EDT UI ("Debug As"), a file-mode standalone
     * server, or a runtime-client config whose application id was never persisted.
     */
    public static final String LAUNCH_APP_ID_PREFIX = "launch:"; //$NON-NLS-1$

    private LaunchConfigUtils()
    {
        // utility class
    }

    /**
     * Returns {@code true} for any EDT debug-server Attach configuration type.
     */
    public static boolean isAttachConfigTypeId(String typeId)
    {
        return TYPE_REMOTE_RUNTIME.equals(typeId) || TYPE_LOCAL_RUNTIME.equals(typeId);
    }

    /**
     * Returns {@code true} if the given launch configuration is of an Attach type.
     */
    public static boolean isAttachConfig(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return false;
        }
        try
        {
            ILaunchConfigurationType type = config.getType();
            return type != null && isAttachConfigTypeId(type.getIdentifier());
        }
        catch (CoreException e)
        {
            return false;
        }
    }

    /**
     * Returns a non-null, stable identifier for any EDT debug launch.
     *
     * <p>For runtime-client launches this is the real {@code ATTR_APPLICATION_ID}.
     * For Attach launches, {@code ATTR_APPLICATION_ID} may be absent; in that case
     * we fall back to {@code attach:<configName>} — stable across calls for the
     * same EDT launch configuration, and addressable via {@code debug_status}.
     * For any other EDT/1C debug launch (e.g. one a user started from the EDT UI —
     * a file-mode standalone server, or a runtime client that carries no
     * {@code ATTR_APPLICATION_ID}) we fall back to {@code launch:<configName>}. All
     * three forms are stable across calls for the same EDT launch configuration and
     * addressable via {@code debug_status}.
     *
     * @return applicationId (real or synthetic), or {@code null} only if the config
     *         is not in the 1C/EDT namespace at all.
     */
    public static String getApplicationIdFor(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return null;
        }
        String realId = readAttribute(config, ATTR_APPLICATION_ID, null);
        if (realId != null && !realId.isEmpty())
        {
            return realId;
        }
        if (isAttachConfig(config))
        {
            return ATTACH_APP_ID_PREFIX + config.getName();
        }
        // Any other EDT/1C debug launch (incl. UI-started "Debug As" sessions) still
        // gets a stable, addressable id so the suspend registry and debug tools can
        // track it. Non-1C launches (Java apps, Ant tasks, …) still return null.
        if (isEdtConfig(config))
        {
            return LAUNCH_APP_ID_PREFIX + config.getName();
        }
        return null;
    }

    /**
     * Returns {@code true} if the given applicationId is a synthetic id this plugin
     * minted rather than a real 1C {@code ATTR_APPLICATION_ID}: {@code attach:…} /
     * {@code launch:…} (minted by {@link #getApplicationIdFor(ILaunchConfiguration)})
     * or {@code ServerApplication.…} (minted by {@link DebugServerTargetSupport} for
     * 1C debug-server targets). Synthetic ids are addressable for debug tracking but
     * cannot be resolved through {@link com.e1c.g5.dt.applications.IApplicationManager}.
     * <p>
     * This is THE single authority for the synthetic-prefix classification — every
     * minted prefix must be known here, or a preflight that feeds an id into
     * {@code IApplicationManager} fails with "Application not found" for a perfectly
     * trackable session.
     *
     * @param applicationId the id to test (may be {@code null})
     * @return {@code true} if the id starts with a synthetic prefix
     */
    public static boolean isSyntheticApplicationId(String applicationId)
    {
        return applicationId != null
            && (applicationId.startsWith(ATTACH_APP_ID_PREFIX)
                || applicationId.startsWith(LAUNCH_APP_ID_PREFIX)
                || applicationId.startsWith(DebugServerTargetSupport.SERVER_APP_ID_PREFIX));
    }

    /**
     * Same as {@link #getApplicationIdFor(ILaunchConfiguration)} but takes a live
     * {@link ILaunch}.
     */
    public static String getApplicationIdFor(ILaunch launch)
    {
        if (launch == null)
        {
            return null;
        }
        return getApplicationIdFor(launch.getLaunchConfiguration());
    }

    /**
     * Finds the launch configuration of the given {@code configType} that matches
     * {@code project + applicationId} <em>exactly</em>. Returns {@code null} if
     * no exact match exists.
     *
     * <p>Historically this method also fell back to "first config for the same
     * project" which silently routed runs to an unrelated launch configuration.
     * That fallback has been removed — callers should either use this strict
     * lookup or {@link #findLaunchConfigByName(ILaunchManager, String)}.
     *
     * @param launchManager Eclipse launch manager (must not be null)
     * @param configType    1C runtime client config type (must not be null)
     * @param projectName   target project name
     * @param applicationId target application id
     * @return matching configuration, or {@code null} if none found
     */
    public static ILaunchConfiguration findLaunchConfig(ILaunchManager launchManager,
            ILaunchConfigurationType configType, String projectName, String applicationId)
    {
        try
        {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(configType))
            {
                if (matchesProjectAndApplication(config, projectName, applicationId))
                {
                    return config;
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error searching launch configurations", e); //$NON-NLS-1$
        }

        return null;
    }

    /**
     * Tells whether {@code config}'s {@code project + applicationId} attributes match the given
     * pair exactly. A {@link CoreException} while reading the attributes is logged and treated as
     * "no match" (so the caller skips this configuration and continues searching).
     *
     * @param config        launch configuration to inspect
     * @param projectName   target project name
     * @param applicationId target application id
     * @return {@code true} when both attributes match, {@code false} otherwise (including on read error)
     */
    private static boolean matchesProjectAndApplication(ILaunchConfiguration config,
            String projectName, String applicationId)
    {
        try
        {
            String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
            String configAppId = config.getAttribute(ATTR_APPLICATION_ID, ""); //$NON-NLS-1$

            return projectName.equals(configProject) && applicationId.equals(configAppId);
        }
        catch (CoreException e)
        {
            Activator.logError("Error reading launch configuration: " + config.getName(), e); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Resolves a launch configuration from a dual input: either an explicit
     * {@code launchConfigurationName} (searched across all EDT debug config
     * types) or a {@code projectName + applicationId} pair (strict match
     * against runtime-client configs only).
     *
     * <p>At least one of the two must be provided. When both are provided and
     * the named config doesn't match the given {@code projectName}/{@code applicationId},
     * the name wins — callers pre-resolve the config and can then cross-check.
     *
     * @return resolved config, or {@code null} if nothing matches.
     */
    public static ILaunchConfiguration resolveLaunchConfig(ILaunchManager launchManager,
            String launchConfigurationName, String projectName, String applicationId)
    {
        if (launchManager == null)
        {
            return null;
        }
        if (launchConfigurationName != null && !launchConfigurationName.isEmpty())
        {
            return findLaunchConfigByName(launchManager, launchConfigurationName);
        }
        if (projectName == null || projectName.isEmpty()
            || applicationId == null || applicationId.isEmpty())
        {
            return null;
        }
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE_ID);
        if (type == null)
        {
            return null;
        }
        return findLaunchConfig(launchManager, type, projectName, applicationId);
    }

    /**
     * Searches all EDT debug launch config types (runtime client + attach) for
     * a configuration with the given exact name.
     *
     * @param launchManager Eclipse launch manager (must not be null)
     * @param name          launch configuration name as shown in EDT UI
     * @return matching configuration, or {@code null} if none found
     */
    public static ILaunchConfiguration findLaunchConfigByName(ILaunchManager launchManager, String name)
    {
        if (launchManager == null || name == null || name.isEmpty())
        {
            return null;
        }
        for (String typeId : ALL_DEBUG_CONFIG_TYPE_IDS)
        {
            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(typeId);
            if (type == null)
            {
                continue;
            }
            try
            {
                for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(type))
                {
                    if (name.equals(config.getName()))
                    {
                        return config;
                    }
                }
            }
            catch (CoreException e)
            {
                Activator.logError("Error searching launch configurations of type " + typeId, e); //$NON-NLS-1$
            }
        }
        return null;
    }

    /**
     * Returns all launch configurations of the 1C runtime client type, or an empty array on error.
     */
    public static ILaunchConfiguration[] getAllRuntimeClientConfigs(ILaunchManager launchManager,
            ILaunchConfigurationType configType)
    {
        try
        {
            return launchManager.getLaunchConfigurations(configType);
        }
        catch (CoreException e)
        {
            Activator.logError("Error listing launch configurations", e); //$NON-NLS-1$
            return new ILaunchConfiguration[0];
        }
    }

    /**
     * Returns all debug-capable launch configurations (runtime client + attach)
     * known to the given launch manager.
     */
    public static List<ILaunchConfiguration> getAllDebugConfigs(ILaunchManager launchManager)
    {
        List<ILaunchConfiguration> result = new ArrayList<>();
        if (launchManager == null)
        {
            return result;
        }
        for (String typeId : ALL_DEBUG_CONFIG_TYPE_IDS)
        {
            ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(typeId);
            if (type == null)
            {
                continue;
            }
            try
            {
                for (ILaunchConfiguration config : launchManager.getLaunchConfigurations(type))
                {
                    result.add(config);
                }
            }
            catch (CoreException e)
            {
                Activator.logError("Error listing launch configurations of type " + typeId, e); //$NON-NLS-1$
            }
        }
        return result;
    }

    /**
     * Returns all 1C:EDT launch configurations — any config whose type id is
     * in the 1C namespace ({@code com._1c.} or {@code com.e1c.}). Covers runtime
     * client, attach (remote/local) and mobile types; ignores unrelated Eclipse
     * launches (Java apps, Ant tasks, etc.).
     */
    public static List<ILaunchConfiguration> getAllEdtConfigs(ILaunchManager launchManager)
    {
        List<ILaunchConfiguration> result = new ArrayList<>();
        if (launchManager == null)
        {
            return result;
        }
        try
        {
            for (ILaunchConfiguration config : launchManager.getLaunchConfigurations())
            {
                if (isEdtConfig(config))
                {
                    result.add(config);
                }
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Error listing launch configurations", e); //$NON-NLS-1$
        }
        return result;
    }

    /**
     * Returns {@code true} if the given launch configuration belongs to the 1C/EDT
     * namespace.
     */
    public static boolean isEdtConfig(ILaunchConfiguration config)
    {
        String typeId = getConfigTypeId(config);
        return typeId.startsWith("com._1c.") //$NON-NLS-1$
            || typeId.startsWith("com.e1c."); //$NON-NLS-1$
    }

    /**
     * Returns the launch configuration type id for a given launch, or the
     * empty string if it cannot be determined.
     */
    public static String getConfigTypeId(ILaunchConfiguration config)
    {
        if (config == null)
        {
            return ""; //$NON-NLS-1$
        }
        try
        {
            ILaunchConfigurationType type = config.getType();
            return type != null ? type.getIdentifier() : ""; //$NON-NLS-1$
        }
        catch (CoreException e)
        {
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Reads a string attribute from a launch configuration, returning {@code defaultValue} on error.
     */
    public static String readAttribute(ILaunchConfiguration config, String attribute, String defaultValue)
    {
        try
        {
            return config.getAttribute(attribute, defaultValue);
        }
        catch (CoreException e)
        {
            return defaultValue;
        }
    }

    /**
     * Convenience: returns the Eclipse launch manager or {@code null} if the debug
     * plugin is unavailable.
     */
    public static ILaunchManager getLaunchManager()
    {
        DebugPlugin plugin = DebugPlugin.getDefault();
        return plugin != null ? plugin.getLaunchManager() : null;
    }

    /**
     * Returns all live (non-terminated) EDT launches in the launch manager.
     *
     * <p>This is the exhaustive set of 1C processes that the current EDT instance
     * spawned (runtime-client) or attached to (Attach). Externally started 1C
     * clients never appear here — that is a constructive guarantee of the
     * Eclipse Debug Platform.
     *
     * @param launchManager Eclipse launch manager (must not be null)
     * @param projectFilter optional project name; when non-empty, only launches
     *                      whose configuration carries this {@code ATTR_PROJECT_NAME}
     *                      are returned
     * @return list of live launches (possibly empty)
     */
    public static List<ILaunch> getAllLiveLaunches(ILaunchManager launchManager, String projectFilter)
    {
        List<ILaunch> result = new ArrayList<>();
        if (launchManager == null)
        {
            return result;
        }
        for (ILaunch launch : launchManager.getLaunches()) // NOSONAR intentional multiple loop exits; restructuring with flags would reduce readability
        {
            if (launch == null || launch.isTerminated())
            {
                continue;
            }
            ILaunchConfiguration config = launch.getLaunchConfiguration();
            if (config == null || !isEdtConfig(config))
            {
                continue;
            }
            if (projectFilter != null && !projectFilter.isEmpty())
            {
                String project = readAttribute(config, ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                if (!projectFilter.equals(project))
                {
                    continue;
                }
            }
            result.add(launch);
        }
        return result;
    }

    /**
     * Finds the live (non-terminated) launch whose configuration has the given
     * exact name.
     *
     * @return matching launch, or {@code null} if no live launch carries that name
     */
    public static ILaunch findLiveLaunchByName(ILaunchManager launchManager, String name)
    {
        if (launchManager == null || name == null || name.isEmpty())
        {
            return null;
        }
        for (ILaunch launch : launchManager.getLaunches())
        {
            if (launch == null || launch.isTerminated())
            {
                continue;
            }
            ILaunchConfiguration config = launch.getLaunchConfiguration();
            // Filter to EDT/1C configs only — config names are not unique across
            // Eclipse launch types, so without this an unrelated Java/JUnit/etc.
            // launch with a matching name would be selected and (with force=true)
            // killed.
            if (config != null && isEdtConfig(config) && name.equals(config.getName()))
            {
                return launch;
            }
        }
        return null;
    }

}
