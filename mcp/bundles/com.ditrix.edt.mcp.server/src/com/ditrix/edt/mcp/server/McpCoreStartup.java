// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2025-2026 DitriX
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
import java.util.function.Function;

import com.ditrix.edt.mcp.server.history.McpCallHistory;
import com.ditrix.edt.mcp.server.history.McpCallHistoryFileLog;

/** Workspace import, history and IPC startup shared by both host applications. */
public class McpCoreStartup
{

    static final String ENV_IMPORT_PROJECTS = "EDT_MCP_IMPORT_PROJECTS"; //$NON-NLS-1$

    private final Function<String, String> environment;

    public McpCoreStartup(Function<String, String> environment) { this.environment = environment; }

    public void start()
    {
        importConfiguredProjects();
        initializeHistory();
        startIpc();
    }

    void initializeHistory()
    {
        IPreferenceStore historyStore = Activator.getDefault().getPreferenceStore();
        McpCallHistory.getInstance().applyPreferences(historyStore);
        McpCallHistoryFileLog.reconfigure();
    }

    void startIpc() { com.ditrix.edt.mcp.server.transport.StdioPluginSupport.start(); }

    String getEnvironmentVariable(String name) { return environment.apply(name); }


    void importConfiguredProjects()
    {
        String spec = getEnvironmentVariable(ENV_IMPORT_PROJECTS);
        if (spec == null || spec.isBlank())
        {
            return;
        }

        String[] dirs = spec.split(Pattern.quote(File.pathSeparator));
        IWorkspace workspace = workspace();
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

    IWorkspace workspace() { return ResourcesPlugin.getWorkspace(); }

    void importProject(IWorkspace workspace, String dir, IProgressMonitor monitor)
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
