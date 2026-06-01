/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;

/**
 * Resolves an MCP {@code projectName} argument to a workspace {@link IProject}
 * and exposes the existence/open predicates that the project tools previously
 * inlined as
 * {@code ResourcesPlugin.getWorkspace().getRoot().getProject(name)} followed by
 * {@code exists()} / {@code isOpen()} checks.
 * <p>
 * Migrating a tool to this resolver is <b>behaviour-preserving</b>: each tool
 * keeps formatting its own "project not found" / "project is closed" message
 * (the wording and the response convention — bare {@code "Error: ..."} vs
 * {@link com.ditrix.edt.mcp.server.protocol.ToolResult}) differ across tools),
 * and keeps its own choice of which checks to apply. This class only removes the
 * duplicated lookup-and-check boilerplate.
 * <p>
 * This is the first, purely {@link IProject}-level increment of the shared
 * project resolver. TODO (card {@code introduce-project-context-resolver}):
 * extend with cached {@code IV8Project} + {@code Configuration} + BM
 * model-manager resolution so tools stop repeating that chain too. That part
 * works against the live BM model and must be introduced incrementally with
 * end-to-end validation, so it is intentionally left out here.
 *
 * @see ProjectStateChecker for the complementary readiness (building / derived
 *      data) check.
 */
public final class ProjectContext
{
    private final String projectName;
    private final IProject project;

    private ProjectContext(String projectName, IProject project)
    {
        this.projectName = projectName;
        this.project = project;
    }

    /**
     * Resolves a project handle by name. A {@code null}/empty name short-circuits
     * to an empty context (no workspace access) whose {@link #exists()} is
     * {@code false}; callers treat that the same as "not found".
     *
     * @param projectName the MCP project name argument (may be {@code null})
     * @return a context wrapping the resolved handle (never {@code null})
     */
    public static ProjectContext of(String projectName)
    {
        IProject resolved = (projectName == null || projectName.isEmpty())
            ? null
            : ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        return new ProjectContext(projectName, resolved);
    }

    /**
     * @return the resolved project handle; may be {@code null} (empty name) or a
     *         handle to a project that does not exist in the workspace
     */
    public IProject project()
    {
        return project;
    }

    /**
     * @return the name this context was resolved from (may be {@code null})
     */
    public String name()
    {
        return projectName;
    }

    /**
     * @return {@code true} when the project exists in the workspace
     */
    public boolean exists()
    {
        return project != null && project.exists();
    }

    /**
     * @return {@code true} when the project exists and is open
     */
    public boolean isOpen()
    {
        return exists() && project.isOpen();
    }
}
