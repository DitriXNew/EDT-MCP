/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owner-only IPC storage. Never traverse symbolic links or Windows directory reparse points. */
public final class PrivatePaths
{
    private static volatile UserPrincipal currentOwner;
    private static Set<UserPrincipal> windowsPrivilegedAccounts;
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
        verifyOwnedDirectory(path);
        if (posix(path)) { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------")); }
        else
        {
            var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) { throw new IOException("Private IPC storage requires POSIX permissions or Windows ACLs"); }
            @SuppressWarnings("unchecked") var entries = (List<AclEntry>)attributes(path, true)[0].value();
            acl.setAcl(entries);
        }
        verifyDirectory(path);
        return path;
    }

    /** Creates a unique private child of an already private, verified parent. */
    public static Path temporaryDirectory(Path parent, String prefix) throws IOException
    {
        verifyDirectory(parent);
        Path temporary = Files.createTempDirectory(parent, prefix, attributes(parent, true));
        try { return directory(temporary); }
        catch (IOException | RuntimeException failure)
        {
            try { Files.delete(temporary); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static void verifyDirectory(Path path) throws IOException
    {
        verifyOwnedDirectory(path);
        verifyAccess(path);
    }

    private static void verifyOwnedDirectory(Path path) throws IOException
    {
        noLinks(path);
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
        { throw new IOException("Expected a private directory"); }
        verifyOwner(path);
    }

    public static void verifyOwner(Path path) throws IOException
    {
        UserPrincipal fileOwner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS), account = owner(path);
        Set<UserPrincipal> privileged = fileOwner.equals(account) || posix(path) ? Set.of() : windowsPrivilegedAccounts(path);
        if (!isTrustedOwner(fileOwner, account, privileged))
        { throw new IOException("IPC storage belongs to another account"); }
    }

    public static boolean isTrustedOwner(UserPrincipal fileOwner, UserPrincipal account, Set<UserPrincipal> privilegedAccounts)
    { return fileOwner.equals(account) || privilegedAccounts.contains(fileOwner); }

    public static void verifyFile(Path path) throws IOException
    {
        noLinks(path);
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
        { throw new IOException("Expected a regular IPC file"); }
        verifyOwner(path);
        verifyAccess(path);
    }

    private static void verifyAccess(Path path) throws IOException
    {
        if (posix(path))
        {
            if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream()
                .anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_")))
            { throw new IOException("IPC storage must be accessible only to its owner"); }
        }
        else { verifyWindowsAcl(path); }
    }

    private static void verifyWindowsAcl(Path path) throws IOException
    {
        var view = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) { throw new IOException("Private IPC storage requires POSIX permissions or Windows ACLs"); }
        var entries = view.getAcl();
        // Java also returns an empty list for an absent DACL, which grants everyone access.
        if (entries.isEmpty()) { throw new IOException("IPC storage requires an explicit private DACL"); }
        for (AclEntry entry : entries)
        {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner(path))
                && !windowsPrivilegedAccounts(path).contains(entry.principal()))
            { throw new IOException("IPC storage must not grant access to other accounts"); }
        }
    }

    private static synchronized Set<UserPrincipal> windowsPrivilegedAccounts(Path path) throws IOException
    {
        if (windowsPrivilegedAccounts != null) { return windowsPrivilegedAccounts; }
        // Resolve well-known SIDs, since localized or renamed account names are not stable.
        // Mismatched Windows owners and non-user ACEs share this cached lookup.
        String script = "$ErrorActionPreference='Stop';[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);"
            + "'S-1-5-18','S-1-5-32-544' | ForEach-Object {"
            + "([System.Security.Principal.SecurityIdentifier]::new($_)).Translate([System.Security.Principal.NTAccount]).Value}";
        Process process = new ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try
        {
            if (!process.waitFor(3, TimeUnit.SECONDS) || process.exitValue() != 0)
            { throw new IOException("Cannot resolve privileged Windows accounts for IPC storage validation"); }
            String[] names = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
            if (names.length != 2) { throw new IOException("Invalid privileged Windows account lookup result"); }
            var lookup = path.getFileSystem().getUserPrincipalLookupService();
            windowsPrivilegedAccounts = Set.of(lookup.lookupPrincipalByName(names[0]), lookup.lookupPrincipalByName(names[1]));
            return windowsPrivilegedAccounts;
        }
        catch (InterruptedException e)
        { Thread.currentThread().interrupt(); throw new IOException("Windows IPC storage validation interrupted"); }
        finally
        {
            if (process.isAlive()) { process.destroyForcibly(); }
            process.getInputStream().close(); process.getErrorStream().close(); process.getOutputStream().close();
        }
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
