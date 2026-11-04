/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Owner-only IPC storage. Never traverse symbolic links or Windows directory reparse points. */
public final class PrivatePaths
{
    private static volatile UserPrincipal currentOwner;
    private PrivatePaths() { }

    private static UserPrincipal owner(Path path) throws IOException
    {
        UserPrincipal result = currentOwner;
        if (result == null)
        {
            String account = ProcessHandle.current().info().user().orElseThrow(
                () -> new IOException("Cannot determine the current account"));
            result = path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
            currentOwner = result;
        }
        return result;
    }

    public static void noLinks(Path path) throws IOException
    {
        if (!path.isAbsolute() || !path.normalize().equals(path)) { throw new IOException("Private paths must be absolute and normalized"); }
        Path current = path.getRoot();
        for (Path component : path)
        {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) { continue; }
            BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || (attributes.isDirectory() && attributes.isOther()))
            { throw new IOException("Private paths must not contain links or reparse points"); }
            if (attributes.isDirectory() && !current.toRealPath().equals(current.toRealPath(LinkOption.NOFOLLOW_LINKS)))
            { throw new IOException("Private paths must not traverse reparse points"); }
        }
    }

    public static boolean posix(Path path)
    { return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null; }

    private static FileAttribute<?>[] attributes(Path parent, boolean directory) throws IOException
    {
        if (posix(parent))
        { return new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))}; }
        if (!directory) { return new FileAttribute<?>[0]; }
        var acl = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner(parent))
            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
            .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build());
        return new FileAttribute<?>[]{new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return acl; }
        }};
    }

    public static Path directory(Path path) throws IOException
    {
        noLinks(path);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            if (!Files.exists(path.getParent(), LinkOption.NOFOLLOW_LINKS)) { directory(path.getParent()); }
            try { Files.createDirectory(path, attributes(path.getParent(), true)); }
            catch (FileAlreadyExistsException e) { /* A competing owner must pass the same checks. */ }
        }
        verifyDirectory(path);
        if (posix(path)) { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        else
        {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) { throw new IOException("Private IPC storage requires POSIX permissions or Windows ACLs"); }
            @SuppressWarnings("unchecked") var entries = (List<AclEntry>)attributes(path, true)[0].value();
            acl.setAcl(entries);
        }
        return path;
    }

    public static void verifyDirectory(Path path) throws IOException
    {
        noLinks(path);
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
        { throw new IOException("Expected a private directory"); }
        verifyOwner(path);
    }

    public static void verifyOwner(Path path) throws IOException
    {
        if (!Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner(path)))
        { throw new IOException("IPC storage belongs to another account"); }
    }

    public static void verifyFile(Path path) throws IOException
    {
        noLinks(path);
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
        { throw new IOException("Expected a regular IPC file"); }
        verifyOwner(path);
        if (posix(path) && Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
            .anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
        { throw new IOException("IPC files must be accessible only to their owner"); }
    }

    public static FileChannel lockFile(Path path) throws IOException
    {
        verifyDirectory(path.getParent());
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { verifyFile(path); }
        FileChannel channel = FileChannel.open(path, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS), attributes(path.getParent(), false));
        try { verifyFile(path); return channel; }
        catch (IOException e) { channel.close(); throw e; }
    }

    public static void atomicWrite(Path destination, byte[] bytes) throws IOException
    {
        verifyDirectory(destination.getParent());
        noLinks(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { verifyFile(destination); }
        Path temporary = Files.createTempFile(destination.getParent(), "ipc-", ".tmp", attributes(destination.getParent(), false));
        try
        {
            Files.write(temporary, bytes);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }
}
