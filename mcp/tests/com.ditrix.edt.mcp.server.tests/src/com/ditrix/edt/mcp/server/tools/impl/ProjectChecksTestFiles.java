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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.spi.FileSystemProvider;
import java.nio.file.Path;
import java.util.Comparator;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.IPath;

/** Physical locations for repository stubs that exercise the final file-safety check. */
public final class ProjectChecksTestFiles implements AutoCloseable
{
    private final Path root;

    public ProjectChecksTestFiles() throws IOException
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

    public static Path locate(IFolder folder, ProjectChecksTestFiles temporary, String... savedProfiles) throws IOException
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
        for (String name : savedProfiles)
        {
            save(folder, name);
        }
        return settings;
    }

    /** Disk side of a repository stub's saved state, independent of its in-memory values. */
    public static void save(IFolder folder, String profile)
    {
        try
        {
            Files.writeString(folder.getLocation().toFile().toPath().resolve(profile + ".cset"), "saved profile");
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    static void remove(IFolder folder, String profile)
    {
        try
        {
            Files.deleteIfExists(folder.getLocation().toFile().toPath().resolve(profile + ".cset"));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
    }

    static Runnable mockTargetPath(IFolder folder, Path settings, String profile, boolean symbolic, Path realPath) throws IOException
    {
        return mockTargetPath(folder, settings, profile, symbolic, realPath, false);
    }

    static Runnable mockTargetPath(IFolder folder, Path settings, String profile, boolean symbolic, Path realPath,
        boolean dangling) throws IOException
    {
        Path target = mock(Path.class);
        FileSystem fileSystem = mock(FileSystem.class);
        FileSystemProvider provider = mock(FileSystemProvider.class, CALLS_REAL_METHODS);
        BasicFileAttributes attributes = mock(BasicFileAttributes.class);
        when(target.getFileSystem()).thenReturn(fileSystem);
        when(fileSystem.provider()).thenReturn(provider);
        when(provider.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS))
            .thenReturn(attributes);
        when(attributes.isSymbolicLink()).thenReturn(symbolic);
        when(attributes.isRegularFile()).thenReturn(true);
        if (dangling)
        {
            // A following existence check misses a dangling link; NOFOLLOW_LINKS must see it.
            doThrow(new NoSuchFileException(realPath.toString())).when(provider).checkAccess(target);
        }
        // Windows temp paths can contain a short user-directory spelling. Return a real parent,
        // so the symbolic-link test cannot pass just because another guard rejects that alias.
        Path resolved = realPath.getParent().toRealPath().resolve(realPath.getFileName());
        when(target.toRealPath()).thenReturn(resolved);
        // If the link check is removed, this still has a safe parent; that mutation must fail.
        when(target.getParent()).thenReturn(settings);
        IPath location = mock(IPath.class);
        java.io.File file = mock(java.io.File.class);
        when(location.toFile()).thenReturn(file);
        when(file.toPath()).thenReturn(target);
        IFile resource = folder.getFile(profile + ".cset");
        return () -> {
            when(resource.getLocation()).thenReturn(location);
            when(folder.getFile(profile + ".cset")).thenReturn(resource);
        };
    }

}
