/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;

/** Physical locations for repository stubs that exercise the final file-safety check. */
final class ProjectChecksTestFiles implements AutoCloseable
{
    private final Path root;

    ProjectChecksTestFiles() throws IOException
    {
        root = Files.createTempDirectory("project-checks-");
    }

    File getRoot()
    {
        return root.toFile();
    }

    File newFile(String name) throws IOException
    {
        return Files.createFile(root.resolve(name)).toFile();
    }

    @Override
    public void close() throws IOException
    {
        // Files.walk does not follow links, so cleanup never descends into a link target.
        try (var paths = Files.walk(root))
        {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
            {
                Files.deleteIfExists(path);
            }
        }
    }

    static Path locate(IFolder folder, ProjectChecksTestFiles temporary) throws IOException
    {
        Path settings = Files.createDirectory(temporary.root.resolve(".settings"));
        when(folder.getLocation()).thenReturn(org.eclipse.core.runtime.Path.fromOSString(settings.toString()));
        when(folder.getFile(anyString())).thenAnswer(call -> {
            IFile file = mock(IFile.class);
            String name = call.getArgument(0);
            when(file.getParent()).thenReturn(folder);
            when(file.getLocation()).thenAnswer(ignored -> org.eclipse.core.runtime.Path.fromOSString(
                settings.resolve(name).toString()));
            return file;
        });
        return settings;
    }
}
