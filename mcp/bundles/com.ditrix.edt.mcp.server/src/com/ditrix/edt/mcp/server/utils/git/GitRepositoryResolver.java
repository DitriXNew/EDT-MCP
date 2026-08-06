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

        /**
         * Package-private test-only factory: builds a {@link Resolution} directly, bypassing
         * {@link #resolve(String)}'s {@code ProjectContext}/EDT-workspace dependency, so the pure
         * accessors and {@link #closeIfOwned()} are unit-testable against a real (non-EDT) JGit
         * {@link Repository} (issue #171 coverage). No behaviour change - mirrors the private
         * constructor exactly.
         */
        static Resolution forTest(IProject project, Repository repository, boolean owned, String errorJson)
        {
            return new Resolution(project, repository, owned, errorJson);
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

        return resolutionOf(project, projectName, discoverRepository(project));
    }

    /**
     * Turns the discovery step's outcome into a {@link Resolution}.
     * <p>
     * The three branches are three different answers, and the middle one is the reason this method
     * exists: a {@code .git} directory that could not be OPENED is not "no repository". Reporting it
     * as one would tell the caller to share a project that is already shared, and leave the broken
     * configuration - the actual fault - unmentioned.
     * <p>
     * Package-visible so all three are unit-testable: {@link #resolve} itself needs a live EDT
     * workspace.
     *
     * @param project the resolved project handle
     * @param projectName the project's name, for the message
     * @param discovery what {@link #discoverRepository} found
     * @return the repository, the configuration-repair error, or the not-a-git-project error
     */
    static Resolution resolutionOf(IProject project, String projectName, Discovery discovery)
    {
        if (discovery.repository() != null)
        {
            return new Resolution(project, discovery.repository(), true, null);
        }
        if (discovery.configUnreadable())
        {
            return failed(ToolResult.error(configUnreadableMessage(projectName)).toJson());
        }
        return failed(ToolResult.error("No git repository found for project '" + projectName //$NON-NLS-1$
            + "'. The project is not inside a git working tree, or is not shared with the EGit " //$NON-NLS-1$
            + "team provider. Share the project with Git (Team -> Share Project) or verify its " //$NON-NLS-1$
            + "location is inside an existing git clone.").toJson()); //$NON-NLS-1$
    }

    /**
     * The error for a repository that exists but could not be opened. It names the configuration,
     * because that is what {@link FileRepositoryBuilder#build()} loads and therefore what a failure
     * there is almost always about; and it withholds the failure's own message for the reason spelled
     * out on {@link #discoverFromLocation}.
     * <p>
     * The closing sentence is scoped to what THIS plug-in writes, and deliberately not wider: JGit
     * logs a malformed user configuration through its own logger before it throws, and that entry is
     * the platform's. Promising "it is nowhere in any log" would be a claim this code cannot keep.
     *
     * @param projectName the project's name
     * @return the actionable, leak-free message
     */
    private static String configUnreadableMessage(String projectName)
    {
        return "The git repository for project '" + projectName + "' could not be opened. Opening " //$NON-NLS-1$ //$NON-NLS-2$
            + "it reads the repository, user and system git configuration, so a malformed one of " //$NON-NLS-1$
            + "those is the usual cause: check them in a terminal, repair the broken file and retry. " //$NON-NLS-1$
            + "This plug-in logs only the failure's exception types - the message itself is withheld, " //$NON-NLS-1$
            + "here and in what it logs, because it can quote the configuration, credentials " //$NON-NLS-1$
            + "included."; //$NON-NLS-1$
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
            // Sanitized for the reason spelled out on discoverFromLocation: this lookup can open a
            // repository too, and JGit's configuration parser quotes the offending line.
            Activator.logError(GitFailureLog.typesOnly(
                "git-branch tools: RepositoryMapping lookup failed for project '" //$NON-NLS-1$
                    + project.getName() + "'", e), null); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Discovers a git repository by walking up from the project's filesystem
     * location looking for a {@code .git} directory, when the project is not
     * EGit-shared. The discovered {@link Repository} (if any) is owned by the caller.
     */
    private static Discovery discoverRepository(IProject project)
    {
        if (project.getLocation() == null)
        {
            return Discovery.none();
        }
        return discoverFromLocation(project.getLocation().toFile(), project.getName());
    }

    /**
     * The discovery step's outcome. A {@link Repository}-or-{@code null} return cannot express it:
     * "no {@code .git} anywhere up the tree" and "a {@code .git} that could not be OPENED" call for
     * different errors ({@link #resolutionOf}), and collapsing the second into the first hides a
     * broken configuration behind "share the project with Git".
     */
    static final class Discovery
    {
        private static final Discovery NONE = new Discovery(null, false);

        private static final Discovery UNREADABLE = new Discovery(null, true);

        private final Repository repository;

        private final boolean configUnreadable;

        private Discovery(Repository repository, boolean configUnreadable)
        {
            this.repository = repository;
            this.configUnreadable = configUnreadable;
        }

        /** @return the outcome for "this location is not inside a git working tree". */
        static Discovery none()
        {
            return NONE;
        }

        /** @return the outcome for "a repository is there, but opening it failed". */
        static Discovery unreadable()
        {
            return UNREADABLE;
        }

        /**
         * @param repository the opened, caller-owned repository
         * @return the outcome carrying it
         */
        static Discovery of(Repository repository)
        {
            return new Discovery(repository, false);
        }

        /** @return the discovered, caller-owned repository, or {@code null} when there is none. */
        Repository repository()
        {
            return repository;
        }

        /** @return {@code true} when a repository was found but could not be opened. */
        boolean configUnreadable()
        {
            return configUnreadable;
        }
    }

    /**
     * The discovery step WITH its failure handling, and without an {@link IProject}: opens the
     * repository under {@code location}, or logs the failure - message withheld - and reports it as
     * {@link Discovery#unreadable()}, which {@link #resolutionOf} turns into its own error rather
     * than into "no git repository found".
     * <p>
     * Package-visible so the failure branch is testable at all. It is not cosmetic: opening a
     * repository loads the repository, user and system configuration, so a configuration that is
     * already malformed when the first call arrives fails HERE - before any check the caller runs on
     * the opened repository. What it throws names the file at fault, and when that file is the USER
     * config it still carries the offending line itself
     * ({@code Invalid line in config file: include.notpath=https://user:...}) in its cause chain.
     * Handing that throwable to {@link Activator#logError} would write a credential into the
     * permanent EDT error log, so only the exception types are logged ({@link GitFailureLog}, whose
     * javadoc says what that does and does not promise).
     *
     * @param location the project's filesystem location
     * @param projectName the project's name, for the log line
     * @return the discovered, caller-owned repository; {@link Discovery#none()} when there is none;
     *         {@link Discovery#unreadable()} when there is one but it could not be opened
     */
    static Discovery discoverFromLocation(File location, String projectName)
    {
        try
        {
            Repository discovered = discoverFromDirectory(location);
            return discovered == null ? Discovery.none() : Discovery.of(discovered);
        }
        catch (IOException | IllegalArgumentException e)
        {
            Activator.logError(GitFailureLog.typesOnly(
                "git-branch tools: git-dir discovery failed for project '" //$NON-NLS-1$
                    + projectName + "'", e), null); //$NON-NLS-1$
            return Discovery.unreadable();
        }
    }

    /**
     * Pure JGit {@code .git} directory discovery from a filesystem location, with NO {@code IProject}/EDT
     * dependency - the mechanics behind {@link #discoverRepository}, extracted so it is directly
     * unit-testable against a real temp git repository (issue #171 coverage). Package-visible for exactly
     * that reason; no behaviour change - {@link #discoverRepository} still catches and logs exactly as
     * before, just one level up.
     *
     * @param dir the filesystem directory to search upward from
     * @return the discovered, caller-owned repository, or {@code null} when no {@code .git} directory is
     *         found anywhere up the tree
     * @throws IOException propagated from {@link FileRepositoryBuilder#build()}, which loads the
     *             repository, user and system configuration - a malformed one fails here, as an
     *             {@link IOException} or an {@link IllegalArgumentException} depending on which file
     *             it was, which is why {@link #discoverFromLocation} catches both and withholds the
     *             message when it logs
     */
    static Repository discoverFromDirectory(File dir) throws IOException
    {
        FileRepositoryBuilder builder = new FileRepositoryBuilder().findGitDir(dir);
        if (builder.getGitDir() == null)
        {
            // No .git directory found anywhere up the tree - not a git working tree.
            return null;
        }
        return builder.build();
    }

    private static Resolution failed(String errorJson)
    {
        return new Resolution(null, null, false, errorJson);
    }
}
