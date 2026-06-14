/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.nio.file.Path;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;

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
}
