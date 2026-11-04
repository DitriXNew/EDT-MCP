/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;


import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IStartup;

import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/** Workbench startup adapter; core startup is shared with the windowless application. */
public class McpServerStartup implements IStartup
{
    /**
     * Env var: a list of project directories (separated by {@link java.io.File#pathSeparator})
     * to import into the workspace by reference on startup. Each must contain a
     * {@code .project}; the project stays at its on-disk location (e.g. a git checkout).
     * Already-present projects are only (re)opened. Empty/unset -> no import.
     */
    static final String ENV_IMPORT_PROJECTS = McpCoreStartup.ENV_IMPORT_PROJECTS; //$NON-NLS-1$

    /** Env var: {@code "true"} forces the server on regardless of the auto-start preference. */
    static final String ENV_AUTO_START = "EDT_MCP_AUTO_START"; //$NON-NLS-1$

    /** Env var: overrides the server port (otherwise the {@code PREF_PORT} preference). */
    static final String ENV_PORT = "EDT_MCP_PORT"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        com.ditrix.edt.mcp.server.utils.EdtDialogs.getInstance().ensureInstalled();
        new McpCoreStartup(this::getEnvironmentVariable).start();

        if (shouldStartHttp(Activator.getDefault().getPreferenceStore(), getEnvironmentVariable(ENV_AUTO_START),
            Boolean.getBoolean("edt.mcp.stdioOnly"))) //$NON-NLS-1$
        {
            int port = resolvePort();

            try
            {
                Activator.getDefault().getMcpServer().start(port);
                Activator.logInfo("MCP Server auto-started on port " + port); //$NON-NLS-1$
            }
            catch (Exception e)
            {
                Activator.logError("Failed to auto-start MCP Server", e); //$NON-NLS-1$
            }
        }

        // Schedule a background check for a new plugin release (after 60 s delay)
        UpdateChecker.getInstance().scheduleCheck();
    }

    /** The CI override wins over saved preferences; stdio launches still suppress auto-start. */
    static boolean shouldStartHttp(IPreferenceStore store, String environmentOverride, boolean stdioOnly)
    {
        return !stdioOnly && ("true".equalsIgnoreCase(environmentOverride) //$NON-NLS-1$
            || (store.getBoolean(PreferenceConstants.PREF_HTTP_ENABLED)
                && store.getBoolean(PreferenceConstants.PREF_AUTO_START)));
    }

    /** Reads launch overrides; package visibility allows startup tests without changing process env. */
    String getEnvironmentVariable(String name)
    {
        return System.getenv(name);
    }

    /** Port from the {@link #ENV_PORT} override, else the {@code PREF_PORT} preference. */
    private int resolvePort()
    {
        String envPort = getEnvironmentVariable(ENV_PORT);
        if (envPort != null && !envPort.isBlank())
        {
            try
            {
                return Integer.parseInt(envPort.trim());
            }
            catch (NumberFormatException e)
            {
                Activator.logError("Invalid " + ENV_PORT + " value: " + envPort, e); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return Activator.getDefault().getPreferenceStore().getInt(PreferenceConstants.PREF_PORT);
    }

}
