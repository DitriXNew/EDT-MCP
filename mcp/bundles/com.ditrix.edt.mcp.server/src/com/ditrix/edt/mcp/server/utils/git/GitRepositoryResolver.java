/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.File;
import java.io.IOException;

import org.eclipse.core.resources.IProject;
import org.eclipse.egit.core.project.RepositoryMapping;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * Resolves the JGit {@link Repository} backing an EDT project, for the git-branch
 * tools ({@code list_git_branches} / {@code switch_git_branch}, issue #281).
 * <p>
 * Resolution order:
 * <ol>
 * <li>{@link RepositoryMapping#getMapping(IProject)} - the EGit team-provider
 * mapping, present when the project is EGit-<em>shared</em> (the normal case for a
 * project living inside a git working tree that has been "connected" to Git via
 * EDT/EGit). The returned {@link Repository} is EGit's own cached/reference-counted
 * instance and is therefore never closed here.</li>
 * <li>A {@link FileRepositoryBuilder#findGitDir(File)} fallback from the project's
 * filesystem location: a project that lives inside a git working tree but was never
 * explicitly EGit-shared can still be read/switched. This instance IS owned by the
 * caller and must be closed after use via {@link Resolution#closeIfOwned()}.</li>
 * </ol>
 * Neither path touches the BM model or opens a transaction - this is a pure
 * filesystem/JGit read, safe to call from any thread.
 */
public final class GitRepositoryResolver
{
    private GitRepositoryResolver()
    {
        // Utility class
    }

    /**
     * The outcome of resolving a project's git repository: either the repository
     * (plus the resolved project handle), or a ready {@link ToolResult} error JSON.
     */
    public static final class Resolution
    {
        private final IProject project;
        private final Repository repository;
        private final boolean owned;
        private final String errorJson;

        private Resolution(IProject project, Repository repository, boolean owned, String errorJson)
        {
            this.project = project;
            this.repository = repository;
            this.owned = owned;
            this.errorJson = errorJson;
        }

        /** @return {@code true} when the repository resolved (no error). */
        public boolean ok()
        {
            return errorJson == null;
        }

        /** @return the resolved project handle (may be {@code null} on error). */
        public IProject project()
        {
            return project;
        }

        /** @return the resolved repository (may be {@code null} on error). */
        public Repository repository()
        {
            return repository;
        }

        /** @return the error JSON to return from {@code execute}, or {@code null} on success. */
        public String errorJson()
        {
            return errorJson;
        }

        /**
         * Closes the repository IFF it is owned by this resolution (the
         * {@link FileRepositoryBuilder} discovery fallback). A repository borrowed
         * from {@link RepositoryMapping} is EGit's own cached instance and is left
         * alone - closing it would decrement a reference count this caller never
         * incremented. Safe to call on an error resolution (no-op).
         */
        public void closeIfOwned()
        {
            if (owned && repository != null)
            {
                repository.close();
            }
        }
    }

    /**
     * Resolves {@code projectName} to a workspace project (existence/open checks
     * first, via the shared {@link ProjectContext} conventions) and then to its git
     * repository.
     *
     * @param projectName the MCP {@code projectName} argument
     * @return the resolution: the repository, or an actionable error
     */
    public static Resolution resolve(String projectName)
    {
        ProjectContext ctx = ProjectContext.of(projectName);
        if (!ctx.exists())
        {
            return failed(ToolResult.error(ProjectContext.notFoundMessage(projectName)).toJson());
        }
        if (!ctx.isOpen())
        {
            return failed(ToolResult.error("Project is closed: " + projectName //$NON-NLS-1$
                + ". Open the project in EDT first.").toJson()); //$NON-NLS-1$
        }
        IProject project = ctx.project();

        Repository mapped = mappingRepository(project);
        if (mapped != null)
        {
            return new Resolution(project, mapped, false, null);
        }

        Repository discovered = discoverRepository(project);
        if (discovered != null)
        {
            return new Resolution(project, discovered, true, null);
        }

        return failed(ToolResult.error("No git repository found for project '" + projectName //$NON-NLS-1$
            + "'. The project is not inside a git working tree, or is not shared with the EGit " //$NON-NLS-1$
            + "team provider. Share the project with Git (Team -> Share Project) or verify its " //$NON-NLS-1$
            + "location is inside an existing git clone.").toJson()); //$NON-NLS-1$
    }

    /**
     * Looks up the EGit team-provider mapping for {@code project}, returning its
     * (borrowed, do-not-close) {@link Repository}, or {@code null} when the project
     * is not EGit-shared or the lookup fails.
     */
    private static Repository mappingRepository(IProject project)
    {
        try
        {
            RepositoryMapping mapping = RepositoryMapping.getMapping(project);
            return mapping != null ? mapping.getRepository() : null;
        }
        catch (RuntimeException e)
        {
            Activator.logError("git-branch tools: RepositoryMapping lookup failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Discovers a git repository by walking up from the project's filesystem
     * location looking for a {@code .git} directory, when the project is not
     * EGit-shared. The returned {@link Repository} (if any) is owned by the caller.
     */
    private static Repository discoverRepository(IProject project)
    {
        if (project.getLocation() == null)
        {
            return null;
        }
        File projectDir = project.getLocation().toFile();
        try
        {
            FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(projectDir);
            if (builder.getGitDir() == null)
            {
                // No .git directory found anywhere up the tree - not a git working tree.
                return null;
            }
            return builder.build();
        }
        catch (IOException | IllegalArgumentException e)
        {
            Activator.logError("git-branch tools: git-dir discovery failed for project '" //$NON-NLS-1$
                + project.getName() + "'", e); //$NON-NLS-1$
            return null;
        }
    }

    private static Resolution failed(String errorJson)
    {
        return new Resolution(null, null, false, errorJson);
    }
}
