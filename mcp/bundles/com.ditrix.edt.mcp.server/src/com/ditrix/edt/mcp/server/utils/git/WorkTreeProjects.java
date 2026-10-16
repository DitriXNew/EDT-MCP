/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IPath;

import com.ditrix.edt.mcp.server.utils.ProjectContext;

/**
 * The workspace projects that live inside a git work tree: the ones a command run from the work tree's root
 * changes, and so the ones a checkout there moves to another branch. Shared by {@code git} (which refreshes them
 * after a work-tree-changing command) and {@link GitCheckoutSupport} (which records them while a checkout that
 * outlived its wait is still running, #656).
 */
public final class WorkTreeProjects
{
    private WorkTreeProjects()
    {
        // Utility class
    }

    /**
     * The OPEN workspace projects whose location lies inside {@code workTree}.
     * <p>
     * Several Eclipse projects can share one git worktree, and a command runs from its ROOT: a
     * checkout or pull changes the siblings too, so refreshing only the addressed project would leave
     * their models stale for the next MCP call.
     *
     * @param workTree the repository work tree
     * @return the projects (never {@code null})
     */
    public static List<IProject> inside(File workTree)
    {
        List<IProject> inside = new ArrayList<>();
        Path root;
        try
        {
            root = workTree.toPath().toRealPath();
        }
        catch (IOException e)
        {
            root = workTree.toPath().toAbsolutePath().normalize();
        }
        for (IProject project : ProjectContext.allProjects())
        {
            IPath location = project.getLocation();
            if (!project.isOpen() || location == null)
            {
                continue;
            }
            Path projectPath = bestEffortRealPath(location.toFile().toPath());
            if (projectPath.startsWith(root))
            {
                inside.add(project);
            }
        }
        return inside;
    }

    /** @return the names of {@code projects} */
    public static List<String> names(Collection<IProject> projects)
    {
        List<String> names = new ArrayList<>();
        for (IProject project : projects)
        {
            names.add(project.getName());
        }
        return names;
    }

    /**
     * Canonicalizes as much of a path as still EXISTS, then appends the rest lexically.
     * <p>
     * A checkout can DELETE a project's directory - exactly the project whose model must be
     * refreshed - so plain canonicalization is not available. Resolving the nearest existing ancestor
     * first keeps a symlinked ancestor from smuggling an OUTSIDE location past the containment test,
     * which a purely lexical path would allow.
     *
     * @param path the path to resolve
     * @return the best canonical form available
     */
    private static Path bestEffortRealPath(Path path)
    {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path existing = absolute; existing != null; existing = existing.getParent())
        {
            try
            {
                Path real = existing.toRealPath();
                Path missing = existing.relativize(absolute);
                return missing.toString().isEmpty() ? real : real.resolve(missing);
            }
            catch (IOException e) // NOSONAR walk further up: this ancestor is gone too
            {
                continue;
            }
        }
        return absolute;
    }
}
