/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared helpers for searching and inspecting 1C:EDT launch configurations.
 *
 * <p>Covers two families:
 * <ul>
 *   <li>Runtime client configs ({@link #LAUNCH_CONFIG_TYPE_ID}) — launch a new
 *       1cv8c client and attach the debugger to it. Carry {@link #ATTR_PROJECT_NAME},
 *       and {@link #ATTR_APPLICATION_ID} when they were bound to an application —
 *       one created without that binding has the attribute EMPTY, which is why the
 *       tools that consume it must decide what an empty value means rather than
 *       assume it cannot happen.</li>
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

    /** How many links of a cause chain {@link #saveFailureLog} names; a chain can be cyclic. */
    private static final int MAX_LOGGED_CAUSE_DEPTH = 5;

    /** 1C:EDT Runtime Client launch configuration type id. */
    public static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Attach to 1C:Enterprise Debug Server (remote cluster debug server). */
    public static final String TYPE_REMOTE_RUNTIME = "com._1c.g5.v8.dt.debug.core.RemoteRuntime"; //$NON-NLS-1$

    /** Attach to a locally spawned debug server. */
    public static final String TYPE_LOCAL_RUNTIME = "com._1c.g5.v8.dt.debug.core.LocalRuntime"; //$NON-NLS-1$

    /** EDT standalone-server launch configuration type id. */
    public static final String STANDALONE_SERVER_LAUNCH_CONFIG_TYPE_ID =
        "com.e1c.g5.v8.dt.platform.standaloneserver.launchConfigurationType"; //$NON-NLS-1$

    /** All debug-launch config types understood by this plugin. */
    public static final List<String> ALL_DEBUG_CONFIG_TYPE_IDS = Collections.unmodifiableList(
        Arrays.asList(LAUNCH_CONFIG_TYPE_ID, TYPE_REMOTE_RUNTIME, TYPE_LOCAL_RUNTIME));

    /** Launch configuration attribute: target project name. */
    public static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    /** Launch configuration attribute: target application id. */
    public static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$

    /** Launch configuration attribute: startup option string passed to 1cv8c.exe via /C. */
    public static final String ATTR_STARTUP_OPTION = "com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION"; //$NON-NLS-1$

    /**
     * Launch attribute: the EXTERNAL OBJECTS project holding the object to run on startup.
     *
     * <p>Read by EDT's {@code RuntimeClientLaunchDelegate} together with
     * {@link #ATTR_EXTERNAL_OBJECT_NAME} and {@link #ATTR_EXTERNAL_OBJECT_TYPE}: it resolves the
     * object in that project, has the platform BUILD its {@code .epf}/{@code .erf} dump, and
     * passes the dump as {@code /Execute}. The source is therefore a PROJECT in the workspace,
     * never a path to a prebuilt file - there is no launch attribute for one, and a prebuilt file
     * would carry no sources for the debugger to map breakpoints onto.</p>
     *
     * <p>Only the runtime-client delegate reads these three; an Attach configuration ignores
     * them.</p>
     */
    public static final String ATTR_EXTERNAL_OBJECT_PROJECT_NAME =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_PROJECT_NAME"; //$NON-NLS-1$

    /** Launch attribute: name of the external object to run (see {@link #ATTR_EXTERNAL_OBJECT_PROJECT_NAME}). */
    public static final String ATTR_EXTERNAL_OBJECT_NAME =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_NAME"; //$NON-NLS-1$

    /**
     * Launch attribute: the external object's type, as EDT's {@code ExternalObjectHelper}
     * spells it - {@code externalObject.getClass().getName()}, i.e. the FQN of the EMF
     * IMPLEMENTATION class ({@code ...mdclass.impl.ExternalDataProcessorImpl}), NOT the EClass
     * name. The delegate re-resolves the object by comparing this string, so it must be produced
     * the same way; a caller is never asked for it.
     */
    public static final String ATTR_EXTERNAL_OBJECT_TYPE =
        "com._1c.g5.v8.dt.debug.core.ATTR_EXTERNAL_OBJECT_TYPE"; //$NON-NLS-1$

    /** Attach configs: infobase alias used by the cluster (e.g. "mr_tradev8"). */
    public static final String ATTR_DEBUG_INFOBASE_ALIAS = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_INFOBASE_ALIAS"; //$NON-NLS-1$

    /** Attach configs: infobase UUID (alternative to alias). */
    public static final String ATTR_INFOBASE_UUID = "com._1c.g5.v8.dt.debug.core.ATTR_INFOBASE_UUID"; //$NON-NLS-1$

    /** Remote attach: URL of the HTTP debug server (e.g. "http://localhost:1550"). */
    public static final String ATTR_DEBUG_SERVER_URL = "com._1c.g5.v8.dt.debug.core.ATTR_DEBUG_SERVER_URL"; //$NON-NLS-1$

    /**
     * Launch attribute: the infobase user the CLIENT connects as ("Пользователь информационной
     * базы" in the launch dialog). The client is a separate process from the designer agent and
     * takes its credentials from here, NOT from the infobase access settings.
     */
    public static final String ATTR_LAUNCH_USER_NAME =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_NAME"; //$NON-NLS-1$

    /** Launch attribute: the password that goes with {@link #ATTR_LAUNCH_USER_NAME}. */
    public static final String ATTR_LAUNCH_USER_PASSWORD =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_PASSWORD"; //$NON-NLS-1$

    /**
     * Launch attribute: "Использовать настройки доступа к информационной базе" - the first radio
     * of the launch dialog's client-user section. Mutually exclusive with an explicit user.
     */
    public static final String ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS"; //$NON-NLS-1$

    /** Launch attribute: "Использовать аутентификацию ОС" - the second radio of that section. */
    public static final String ATTR_LAUNCH_OS_INFOBASE_ACCESS =
        "com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_OS_INFOBASE_ACCESS"; //$NON-NLS-1$

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
     * <p>For runtime-client launches this is the real {@code ATTR_APPLICATION_ID} when it is
     * set and readable. The read is lenient (see {@link #readAttribute}), so a binding that
     * exists but cannot be read falls into the synthetic branch below exactly like an absent
     * one — a caller that must tell the two apart has to read the attribute itself.
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
     * Returns {@code true} if the given applicationId carries a prefix this plugin also
     * mints itself rather than reading it from a real 1C {@code ATTR_APPLICATION_ID}:
     * {@code attach:…} / {@code launch:…} (minted by
     * {@link #getApplicationIdFor(ILaunchConfiguration)}) or {@code ServerApplication.…}
     * (minted by {@link DebugServerTargetSupport} for 1C debug-server targets). All three
     * are addressable for debug tracking; this predicate exists so a preflight can SKIP
     * the {@link com.e1c.g5.dt.applications.IApplicationManager} lookup for them.
     * <p>
     * This is THE single authority for that skip classification — every minted prefix must
     * be known here, or a preflight that feeds an id into {@code IApplicationManager} fails
     * with "Application not found" for a perfectly trackable session.
     * <p>
     * <b>It is NOT a "this is not a real application id" test.</b> The two {@code :}-forms
     * never resolve through {@code IApplicationManager}, but {@code ServerApplication.} is
     * the literal prefix REAL 1C standalone-server applications carry in their own
     * {@link com.e1c.g5.dt.applications.IApplication#getId()} — the minted debug-server ids
     * mirror that form on purpose (see {@link DebugServerTargetSupport#SERVER_APP_ID_PREFIX}).
     * A diagnosis that tells the caller "this is not an application id" must therefore test
     * the two prefixes explicitly instead of calling this method, or it will mis-describe a
     * genuine — merely missing or stale — standalone-server application.
     *
     * @param applicationId the id to test (may be {@code null})
     * @return {@code true} if the id starts with one of the three prefixes above
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
            ILaunchConfiguration config = findLaunchConfigByTypeAndName(launchManager, typeId, name);
            if (config != null)
            {
                return config;
            }
        }
        return null;
    }

    /**
     * Searches one exact launch-configuration type for an exact configuration name.
     *
     * <p>This is deliberately separate from {@link #ALL_DEBUG_CONFIG_TYPE_IDS}: callers that only
     * support runtime-client/Attach configurations keep their existing search domain, while a
     * caller that needs to diagnose another known EDT type can look it up without hand-rolling the
     * Eclipse launch-manager traversal.
     *
     * @param launchManager Eclipse launch manager (must not be {@code null})
     * @param typeId exact launch-configuration type id
     * @param name exact launch-configuration name
     * @return the matching configuration, or {@code null}
     */
    public static ILaunchConfiguration findLaunchConfigByTypeAndName(ILaunchManager launchManager,
            String typeId, String name)
    {
        if (launchManager == null || typeId == null || typeId.isEmpty()
            || name == null || name.isEmpty())
        {
            return null;
        }
        ILaunchConfigurationType type = launchManager.getLaunchConfigurationType(typeId);
        if (type == null)
        {
            return null;
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

    /**
     * The client-user attributes a launch configuration must carry for the CLIENT process to
     * authenticate without the platform's "Доступ к информационной базе" prompt.
     *
     * <p>Kept separate from the write below so the mapping - which radio of the launch dialog's
     * client-user section ends up selected - is pinnable without a live launch configuration. The
     * three radios are mutually exclusive, so choosing one means clearing the others: an explicit
     * user must switch OFF "use the infobase access settings", or the client keeps reading the
     * settings that only the designer agent consumes (issue #359).
     *
     * @param user     the infobase user the client connects as (may be {@code null}/empty for OS auth)
     * @param password the user's password (may be {@code null}; an empty password is legitimate)
     * @param osAuth   {@code true} to select OS authentication instead of an explicit user
     * @return the attribute name/value pairs to write, never {@code null}
     */
    public static Map<String, Object> clientCredentialAttributes(String user, String password, boolean osAuth)
    {
        Map<String, Object> attributes = new LinkedHashMap<>();
        // Whichever mode is chosen, it is NOT "take them from the infobase access settings":
        // those are read by the designer agent, not by the launched client.
        attributes.put(ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS, Boolean.FALSE);
        attributes.put(ATTR_LAUNCH_OS_INFOBASE_ACCESS, Boolean.valueOf(osAuth));
        if (osAuth)
        {
            // OS authentication carries no user/password - leave nothing stale behind.
            attributes.put(ATTR_LAUNCH_USER_NAME, ""); //$NON-NLS-1$
            attributes.put(ATTR_LAUNCH_USER_PASSWORD, ""); //$NON-NLS-1$
        }
        else
        {
            attributes.put(ATTR_LAUNCH_USER_NAME, user == null ? "" : user); //$NON-NLS-1$
            attributes.put(ATTR_LAUNCH_USER_PASSWORD, password == null ? "" : password); //$NON-NLS-1$
        }
        return attributes;
    }

    /**
     * Refuses to put a SECRET into a launch configuration that is SHARED.
     *
     * <p>A launch configuration is either <em>local</em> — kept in the workspace metadata
     * ({@code .metadata/.plugins/org.eclipse.debug.core/.launches/*.launch}) — or <em>shared</em>, in
     * which case its {@code .launch} file is an ordinary resource inside a project and is therefore
     * normally committed to version control. The platform reads
     * {@link #ATTR_LAUNCH_USER_PASSWORD} back as a plain launch attribute
     * ({@code ILaunchConfiguration.getAttribute(String, String)}), so it is serialised into that file
     * in the clear: writing it onto a shared configuration would publish the infobase password to
     * everyone who clones the repository.
     *
     * <p>The refusal is scoped to the case where something secret would actually be written. OS
     * authentication stores no password at all, and an empty password (the demo-base case) is not a
     * secret — those keep working on a shared configuration, so the guard costs no legitimate use.
     * The user name is not treated as a secret: it is what a human puts in the very same section of
     * the launch dialog when sharing a configuration on purpose.
     *
     * @param config   the launch configuration about to be written; never {@code null}
     * @param password the password that would be written (may be {@code null})
     * @param osAuth   {@code true} when OS authentication was requested, so no password is written
     * @return the reason to refuse the write, or {@code null} when nothing secret would leave the
     *     workspace metadata
     */
    static String sharedSecretRefusal(ILaunchConfiguration config, String password, boolean osAuth)
    {
        if (osAuth || password == null || password.isEmpty())
        {
            // Nothing secret is written, so there is nothing to leak into a committed file.
            return null;
        }
        if (config.isLocal())
        {
            // Workspace metadata: still cleartext, but private to this workspace - the guide says so.
            return null;
        }
        IFile file = config.getFile();
        String where = file == null ? "" : " stored as '" + file.getFullPath() + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return "it is a SHARED launch configuration" + where + ", and the password would be written " //$NON-NLS-1$ //$NON-NLS-2$
            + "into that file in the clear - shared configurations live inside the project and are " //$NON-NLS-1$
            + "normally committed to version control (filling that section in by hand in the launch " //$NON-NLS-1$
            + "dialog writes the same password to the same file). Make the configuration local " //$NON-NLS-1$
            + "(launch dialog -> Common -> Local file), or use access='OS', or pass an empty " //$NON-NLS-1$
            + "password and let the client ask"; //$NON-NLS-1$
    }

    /**
     * Removes a secret from a platform message before it becomes part of a tool answer.
     *
     * <p>The messages reported below are the platform's own and normally name a resource, not an
     * attribute value — but nothing in the API guarantees that, and the tool's contract is that the
     * password is never returned. Cheap insurance on the one string that travels back to the caller.
     *
     * @param message the platform's message (may be {@code null})
     * @param secret  the value that must not appear in it ({@code null}/empty means nothing to hide)
     * @return the message with every occurrence of the secret masked
     */
    static String withoutSecret(String message, String secret)
    {
        if (message == null || secret == null || secret.isEmpty())
        {
            return message;
        }
        return message.replace(secret, "***"); //$NON-NLS-1$
    }

    /**
     * The EDT-log line for a failed credential write: what failed, and the exception TYPES behind
     * it — never any message the platform produced.
     *
     * <p>The failing call is a save of the launch attribute that HOLDS the infobase password, so
     * the platform's own text is exactly where that value can surface, and it does so by three
     * separate routes once the throwable is attached to a {@link org.eclipse.core.runtime.Status}:
     * Eclipse renders the throwable's stack trace (its {@code toString()}, i.e. the message, and
     * every {@code Caused by:} link), and for a {@link CoreException} it additionally writes the
     * statuses behind it — the exception's own {@link org.eclipse.core.runtime.IStatus} and that
     * status's children — as nested log entries. Masking cannot be relied on there: the workspace
     * log is a permanent file read by whoever opens the workspace, while the response the caller
     * gets is a one-off answer to the very agent that supplied the password, so the two are
     * scrubbed to different depths on purpose. Withholding the text closes all three routes at
     * once, and a class name can carry no attribute value.
     *
     * <p>The chain is walked by type and BOUNDED, because a cause chain can be cyclic.
     *
     * @param failure the exception the save threw (may be {@code null})
     * @return the message to log; it embeds no platform-produced text
     */
    static String saveFailureLog(Throwable failure)
    {
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_LOGGED_CAUSE_DEPTH; depth++)
        {
            if (depth > 0)
            {
                types.append(" <- "); //$NON-NLS-1$
            }
            types.append(current.getClass().getName());
            current = current.getCause();
        }
        return "Could not store client credentials on the launch configuration (" + types //$NON-NLS-1$
            + "); the failure's message is withheld because it can quote the attribute value that " //$NON-NLS-1$
            + "failed to save, which on this path is the infobase password"; //$NON-NLS-1$
    }

    /**
     * Writes {@link #clientCredentialAttributes} onto a launch configuration, so the launched
     * CLIENT authenticates by itself.
     *
     * <p>Refuses the write entirely when it would put a password into a SHARED configuration — see
     * {@link #sharedSecretRefusal}. All or nothing: a half-written client-user section (mode
     * switched, credentials missing) leaves the launch dialog in a state nobody asked for.
     *
     * @param config   the runtime-client launch configuration to update
     * @param user     the infobase user (may be {@code null}/empty for OS auth)
     * @param password the password (may be {@code null}; empty is legitimate)
     * @param osAuth   {@code true} for OS authentication
     * @return {@code null} on success, otherwise a human-readable reason the write failed
     */
    public static String applyClientCredentials(ILaunchConfiguration config, String user, String password,
        boolean osAuth)
    {
        if (config == null)
        {
            return "launch configuration is not available"; //$NON-NLS-1$
        }
        String sharedRefusal = sharedSecretRefusal(config, password, osAuth);
        if (sharedRefusal != null)
        {
            return sharedRefusal;
        }
        try
        {
            ILaunchConfigurationWorkingCopy copy = config.getWorkingCopy();
            for (Map.Entry<String, Object> attribute : clientCredentialAttributes(user, password, osAuth).entrySet())
            {
                Object value = attribute.getValue();
                if (value instanceof Boolean)
                {
                    copy.setAttribute(attribute.getKey(), ((Boolean)value).booleanValue());
                }
                else
                {
                    copy.setAttribute(attribute.getKey(), (String)value);
                }
            }
            copy.doSave();
            return null;
        }
        catch (CoreException e)
        {
            // Only the exception TYPES reach the EDT log (see saveFailureLog): the throwable itself
            // is NOT attached, because Eclipse would write its message, its cause chain and the
            // statuses behind a CoreException into a permanent workspace file - and the value that
            // failed to save here is the infobase password.
            Activator.logError(saveFailureLog(e), null);
            // The message is the platform's own and normally names the resource, not the value that
            // failed to save - but "normally" is not a guarantee, and this string goes back out to
            // the caller in a tool whose contract is that the password is never returned.
            return withoutSecret(e.getMessage(), password);
        }
    }

}
