/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchManager;

import com.ditrix.edt.mcp.server.Activator;

/**
 * Shared helpers for searching and inspecting 1C:EDT launch configurations.
 *
 * Lookup order is consistent across all tools that need a launch configuration:
 * exact match by project name + application id, then a fallback to the first
 * configuration that matches the project name only.
 */
public final class LaunchConfigUtils
{
    /** 1C:EDT Runtime Client launch configuration type id. */
    public static final String LAUNCH_CONFIG_TYPE_ID = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; //$NON-NLS-1$

    /** Launch configuration attribute: target project name. */
    public static final String ATTR_PROJECT_NAME = "com._1c.g5.v8.dt.debug.core.ATTR_PROJECT_NAME"; //$NON-NLS-1$

    /** Launch configuration attribute: target application id. */
    public static final String ATTR_APPLICATION_ID = "com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"; //$NON-NLS-1$

    /** Launch configuration attribute: startup option string passed to 1cv8c.exe via /C. */
    public static final String ATTR_STARTUP_OPTION = "com._1c.g5.v8.dt.launching.core.ATTR_STARTUP_OPTION"; //$NON-NLS-1$

    private LaunchConfigUtils()
    {
        // utility class
    }

    /**
     * Finds the best matching launch configuration for the given project and application.
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
            ILaunchConfiguration[] allConfigs = launchManager.getLaunchConfigurations(configType);

            // 1. Exact match: project + application
            for (ILaunchConfiguration config : allConfigs)
            {
                try
                {
                    String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    String configAppId = config.getAttribute(ATTR_APPLICATION_ID, ""); //$NON-NLS-1$

                    if (projectName.equals(configProject) && applicationId.equals(configAppId))
                    {
                        return config;
                    }
                }
                catch (CoreException e)
                {
                    Activator.logError("Error reading launch configuration: " + config.getName(), e); //$NON-NLS-1$
                }
            }

            // 2. Fallback: any config for the same project
            for (ILaunchConfiguration config : allConfigs)
            {
                try
                {
                    String configProject = config.getAttribute(ATTR_PROJECT_NAME, ""); //$NON-NLS-1$
                    if (projectName.equals(configProject))
                    {
                        return config;
                    }
                }
                catch (CoreException e)
                {
                    // Skip unreadable config
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
}
