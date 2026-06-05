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
 * Besides resolution, this class owns the <b>standard "project not found"
 * message</b> via {@link #notFoundMessage(String)} — a single actionable wording
 * (names the value AND points at {@code list_projects}) that tools use instead of
 * inlining {@code "Project not found: " + name}, so the not-found error reads the
 * same everywhere. A tool still chooses WHICH checks to apply ({@link #exists()}
 * vs {@link #isOpen()}) and its own wording for the distinct "project is closed"
 * case; only the not-found message and the lookup-and-check boilerplate are shared.
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
     * Returns a handle to every project in the workspace (open or closed). This is
     * the shared replacement for an inlined
     * {@code ResourcesPlugin.getWorkspace().getRoot().getProjects()} used by tools
     * that scan across all projects (e.g. a workspace-wide marker scan) rather than
     * one named project. The caller applies its own {@link IProject#isOpen()} /
     * existence filtering, exactly as the inlined form required.
     * <p>
     * TODO (card {@code introduce-project-context-resolver}): the remaining
     * {@code tools/impl} tools that still inline the workspace-root enumeration
     * (see {@code ProjectContextAdoptionRatchetTest}) can migrate onto this.
     *
     * @return all projects in the workspace (never {@code null}; possibly empty)
     */
    public static IProject[] allProjects()
    {
        return ResourcesPlugin.getWorkspace().getRoot().getProjects();
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

    /**
     * The standard, actionable "project not found" error MESSAGE for an unresolved
     * {@code projectName}: it names the offending value AND points the caller at the
     * sibling discovery tool. Wrap it in {@code ToolResult.error(...)} instead of
     * inlining {@code "Project not found: " + projectName}, so every tool surfaces the
     * same actionable not-found error.
     *
     * @param projectName the unresolved project name (the value the caller passed)
     * @return the message naming the value and suggesting {@code list_projects}
     */
    public static String notFoundMessage(String projectName)
    {
        return "Project not found: " + projectName //$NON-NLS-1$
            + ". Use list_projects to see available projects."; //$NON-NLS-1$
    }
}
