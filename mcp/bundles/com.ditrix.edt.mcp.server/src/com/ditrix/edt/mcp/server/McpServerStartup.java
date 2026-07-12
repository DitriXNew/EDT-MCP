/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server;

import java.io.File;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.IStartup;

import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallHistoryFileLog;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Startup class for auto-starting MCP server on EDT startup.
 * <p>
 * It also carries an opt-in headless/CI bootstrap driven entirely by environment
 * variables, so a fresh, scripted workspace (e.g. a cloud CI runner) can come up
 * with the fixture projects imported and the server running, without a GUI import
 * or the auto-start preference set. With none of the env vars set the behavior is
 * unchanged: nothing is imported and auto-start follows the preference.
 */
public class McpServerStartup implements IStartup
{
    /**
     * Env var: a list of project directories (separated by {@link File#pathSeparator})
     * to import into the workspace by reference on startup. Each must contain a
     * {@code .project}; the project stays at its on-disk location (e.g. a git checkout).
     * Already-present projects are only (re)opened. Empty/unset -> no import.
     */
    static final String ENV_IMPORT_PROJECTS = "EDT_MCP_IMPORT_PROJECTS"; //$NON-NLS-1$

    /** Env var: {@code "true"} forces the server on regardless of the auto-start preference. */
    static final String ENV_AUTO_START = "EDT_MCP_AUTO_START"; //$NON-NLS-1$

    /** Env var: overrides the server port (otherwise the {@code PREF_PORT} preference). */
    static final String ENV_PORT = "EDT_MCP_PORT"; //$NON-NLS-1$

    @Override
    public void earlyStartup()
    {
        // Opt-in headless/CI bootstrap (env-gated; no-op without the env vars).
        importConfiguredProjects();

        // Seed the in-memory history recorder from persisted preferences so the record
        // toggle and buffer size take effect on the live singleton from startup (record
        // ON by default). Without this the recorder would keep its compile-time defaults
        // and the History preferences could never turn it off or change its size.
        IPreferenceStore historyStore = Activator.getDefault().getPreferenceStore();
        McpCallHistory.getInstance().applyPreferences(historyStore);

        // Install the optional JSONL file-log sink for the MCP call history from the
        // persisted preferences. No-op unless enabled; never blocks startup (all
        // failures swallowed). The sink is retained so a later preference change can
        // re-evaluate it and plugin shutdown can flush and close it.
        McpCallHistoryFileLog.reconfigure();

        // Auto-start when the preference is set OR the env override forces it.
        boolean autoStart = "true".equalsIgnoreCase(System.getenv(ENV_AUTO_START)) //$NON-NLS-1$
            || Activator.getDefault().getPreferenceStore().getBoolean(PreferenceConstants.PREF_AUTO_START);

        if (autoStart)
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

    /** Port from the {@link #ENV_PORT} override, else the {@code PREF_PORT} preference. */
    private int resolvePort()
    {
        String envPort = System.getenv(ENV_PORT);
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

    /**
     * Imports each project directory listed in {@link #ENV_IMPORT_PROJECTS} into the
     * workspace by reference. Batched in one workspace operation; per-project failures
     * are logged and never abort startup.
     */
    private void importConfiguredProjects()
    {
        String spec = System.getenv(ENV_IMPORT_PROJECTS);
        if (spec == null || spec.isBlank())
        {
            return;
        }

        String[] dirs = spec.split(Pattern.quote(File.pathSeparator));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRunnable op = monitor -> {
            for (String raw : dirs)
            {
                String dir = raw.trim();
                if (!dir.isEmpty())
                {
                    importProject(workspace, dir, monitor);
                }
            }
        };

        try
        {
            workspace.run(op, workspace.getRoot(), IWorkspace.AVOID_UPDATE, null);
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to import configured projects", e); //$NON-NLS-1$
        }
    }

    private void importProject(IWorkspace workspace, String dir, IProgressMonitor monitor)
    {
        try
        {
            IPath projectDir = IPath.fromOSString(dir);
            IPath descPath = projectDir.append(IProjectDescription.DESCRIPTION_FILE_NAME);
            if (!descPath.toFile().isFile())
            {
                Activator.logInfo("Skipping import - no .project at " + dir); //$NON-NLS-1$
                return;
            }

            IProjectDescription description = workspace.loadProjectDescription(descPath);
            IProject project = workspace.getRoot().getProject(description.getName());
            if (!project.exists())
            {
                // External location: the project lives outside the workspace (a git checkout).
                description.setLocation(projectDir);
                project.create(description, monitor);
                Activator.logInfo("Imported project " + description.getName() + " from " + dir); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (!project.isOpen())
            {
                project.open(monitor);
            }
        }
        catch (CoreException e)
        {
            Activator.logError("Failed to import project from " + dir, e); //$NON-NLS-1$
        }
    }
}
