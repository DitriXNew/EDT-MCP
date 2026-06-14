/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.e1c.g5.dt.applications.IApplicationManager;

/**
 * Shared resolution for the application/infobase tools ({@code get_applications},
 * {@code update_database}, …): resolves an OPEN project plus the EDT
 * {@code IApplicationManager}, returning the same actionable errors those tools
 * used inline. The application-domain counterpart to
 * {@link ProjectContext#resolveConfiguration(String)}.
 */
public final class ApplicationSupport
{
    private ApplicationSupport()
    {
    }

    /**
     * Resolves an open project and the {@link IApplicationManager} service.
     *
     * @param projectName the MCP project name argument
     * @return a result carrying the project + manager on success, or the first
     *         matching error JSON (not found / closed / manager-unavailable)
     */
    public static ManagerResult resolveManager(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return new ManagerResult(null, null, ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return new ManagerResult(null, null,
                ToolResult.error("Project is closed: " + projectName).toJson()); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        IApplicationManager manager = Activator.getDefault().getApplicationManager();
        if (manager == null)
        {
            return new ManagerResult(project, null,
                ToolResult.error("IApplicationManager service is not available").toJson()); //$NON-NLS-1$
        }
        return new ManagerResult(project, manager, null);
    }

    /**
     * Outcome of resolving an open project + its {@link IApplicationManager}: either
     * the project and manager, or an actionable error JSON. Check {@link #ok()} first;
     * on failure return {@link #errorJson()} verbatim.
     */
    public static final class ManagerResult
    {
        private final IProject project;
        private final IApplicationManager manager;
        private final String errorJson;

        private ManagerResult(IProject project, IApplicationManager manager, String errorJson)
        {
            this.project = project;
            this.manager = manager;
            this.errorJson = errorJson;
        }

        /** @return {@code true} when the project and manager resolved (no error). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /** @return the resolved project (may be {@code null} on a not-found/closed error). */
        public IProject project()
        {
            return project;
        }

        /** @return the resolved application manager, or {@code null} on error. */
        public IApplicationManager manager()
        {
            return manager;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }
    }
}
