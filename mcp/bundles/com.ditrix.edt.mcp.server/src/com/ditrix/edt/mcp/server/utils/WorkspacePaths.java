/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Path;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;

/**
 * Helpers for relating filesystem paths to the Eclipse workspace, shared by the
 * tools that read/write arbitrary absolute paths (configuration XML
 * export/import).
 */
public final class WorkspacePaths
{
    private WorkspacePaths()
    {
    }

    /**
     * Returns true if {@code path} is not under the Eclipse workspace root. Used to
     * flag (not block) reads/writes to external locations. Deliberately fails OPEN
     * (returns {@code false} on any uncertainty): this is an advisory-only check, so
     * a false negative merely omits a warning and never rejects a legitimate path.
     *
     * @param path an absolute, normalized filesystem path
     * @return {@code true} only when the workspace root is known and {@code path}
     *         is outside it
     */
    public static boolean isOutsideWorkspace(Path path)
    {
        try
        {
            IPath loc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
            if (loc == null)
            {
                return false;
            }
            Path wsRoot = loc.toFile().toPath().toAbsolutePath().normalize();
            return !path.startsWith(wsRoot);
        }
        catch (Exception e)
        {
            return false; // cannot determine — do not flag
        }
    }

    /**
     * Asks the workspace whether {@code name} is a legal project name.
     *
     * @param name the proposed project name
     * @return {@code null} when it is legal, otherwise the workspace's reason
     */
    public static String projectNameProblem(String name)
    {
        IStatus status = ResourcesPlugin.getWorkspace().validateName(name, IResource.PROJECT);
        return status.isOK() ? null : status.getMessage();
    }

    /**
     * Uses the local file system's case rule, the same criterion Eclipse uses for
     * Workspace.caseSensitive and its CASE_VARIANT_EXISTS refusal.
     *
     * @return whether default project folders distinguish letter case
     */
    public static boolean projectFoldersAreCaseSensitive()
    {
        return EFS.getLocalFileSystem().isCaseSensitive();
    }

    /**
     * Compares default project folders using Eclipse's case rule. The CLI creates
     * each project at {@code <workspace root>/<name>}, with one root per session.
     * Eclipse's resource-variant check uses {@link String#equalsIgnoreCase(String)},
     * rather than a locale-dependent upper/lowercase normalization.
     *
     * @param first the first project name
     * @param second the second project name
     * @param caseSensitive whether the local file system distinguishes letter case
     * @return whether both names identify the same default project folder
     */
    public static boolean sameProjectFolder(String first, String second, boolean caseSensitive)
    {
        return caseSensitive ? first.equals(second) : first.equalsIgnoreCase(second);
    }

    /**
     * @param first the first project name
     * @param second the second project name
     * @return whether both names identify the same default folder on this platform
     */
    public static boolean sameProjectFolder(String first, String second)
    {
        return sameProjectFolder(first, second, projectFoldersAreCaseSensitive());
    }

    /**
     * Finds an existing workspace project with the requested folder identity.
     * Hidden projects participate just as they do in an exact {@code exists()} check.
     *
     * @param name the requested project name
     * @param caseSensitive whether default project folders distinguish letter case
     * @return the exact project or its case variant, or {@code null} if none exists
     */
    public static IProject existingProjectVariant(String name, boolean caseSensitive)
    {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject exact = root.getProject(name);
        if (exact.exists())
        {
            return exact;
        }
        if (!caseSensitive)
        {
            for (IProject project : root.getProjects(IContainer.INCLUDE_HIDDEN))
            {
                if (sameProjectFolder(name, project.getName(), caseSensitive))
                {
                    return project;
                }
            }
        }
        return null;
    }

    /**
     * @param name the requested project name
     * @return an existing project with the same default folder on this platform, or {@code null}
     */
    public static IProject existingProjectVariant(String name)
    {
        return existingProjectVariant(name, projectFoldersAreCaseSensitive());
    }

    /**
     * The folder a new project of this name gets by default: {@code <workspace root>/<name>}.
     *
     * @param name a legal project name
     * @return the folder, or {@code null} when the workspace root has no local location
     */
    public static Path defaultProjectFolder(String name)
    {
        IPath loc = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        return loc == null ? null : loc.toFile().toPath().resolve(name);
    }
}
