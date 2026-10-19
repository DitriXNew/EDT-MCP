/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;

/**
 * A plain workspace project with a {@code src/} tree, for tests of the shared full-text search
 * ({@link ResourceTextSearch}) and the tools built on it. Not an EDT project: the search only
 * walks resources, so a plain project with UTF-8 files is the whole fixture.
 */
public final class SearchFixtureProject implements AutoCloseable
{
    private final IProject project;

    private SearchFixtureProject(IProject project)
    {
        this.project = project;
    }

    /**
     * @return a fresh, open project named {@code ZzSearch<random>} with UTF-8 as its charset
     * @throws CoreException when the workspace refuses
     */
    public static SearchFixtureProject create() throws CoreException
    {
        String name = "ZzSearch" + UUID.randomUUID().toString().replace("-", "").substring(0, 10); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
        project.create(new NullProgressMonitor());
        project.open(new NullProgressMonitor());
        project.setDefaultCharset(StandardCharsets.UTF_8.name(), new NullProgressMonitor());
        return new SearchFixtureProject(project);
    }

    /** @return the project */
    public IProject project()
    {
        return project;
    }

    /** @return the project name */
    public String name()
    {
        return project.getName();
    }

    /**
     * Writes a UTF-8 file, creating its folders.
     *
     * @param projectRelativePath e.g. {@code src/Reports/R/Templates/T/Template.dcs}
     * @param content the text
     * @return the file
     * @throws CoreException when the workspace refuses
     */
    public IFile file(String projectRelativePath, String content) throws CoreException
    {
        IFile file = project.getFile(new Path(projectRelativePath));
        ensureFolders(file.getParent());
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true,
            new NullProgressMonitor());
        return file;
    }

    /**
     * Deletes a file from the disk WITHOUT telling the workspace, so the resource tree still lists
     * it but its content can no longer be read - an unreadable file for the scanner.
     *
     * @param projectRelativePath the file
     */
    public void deleteOnDiskOnly(String projectRelativePath)
    {
        File onDisk = project.getFile(new Path(projectRelativePath)).getLocation().toFile();
        if (!onDisk.delete())
        {
            throw new IllegalStateException("could not delete " + onDisk); //$NON-NLS-1$
        }
    }

    private static void ensureFolders(IContainer container) throws CoreException
    {
        if (container instanceof IFolder && !container.exists())
        {
            ensureFolders(container.getParent());
            ((IFolder)container).create(true, true, new NullProgressMonitor());
        }
    }

    @Override
    public void close() throws CoreException
    {
        if (project.exists())
        {
            project.delete(true, true, new NullProgressMonitor());
        }
    }
}
