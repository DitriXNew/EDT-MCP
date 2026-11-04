/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;

/** Private, per-workspace discovery. All record replacement/removal holds the same OS file lock. */
public final class EndpointRegistry
{
    public static final int IPC_VERSION = 1;
    private static final Gson JSON = new Gson();
    private final Path directory;
    private final String key;
    private final Path workspace;
    public record Endpoint(String workspace, String generation, long pid, String startTime,
        String pluginVersion, int ipcVersion, String socket, String secret) { }

    /** The caller provisions a private directory before constructing the record store. */
    public EndpointRegistry(Path directory, Path workspace) throws IOException
    {
        if (!directory.isAbsolute() || !Files.isDirectory(directory) || Files.isSymbolicLink(directory))
        { throw new IOException("Endpoint registry requires an existing absolute private directory"); }
        this.directory = directory;
        this.workspace = workspace.toRealPath();
        key = workspaceKey(workspace);
    }

    public static Path userDirectory() throws IOException
    {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) { throw new IOException("LOCALAPPDATA is required on Windows"); }
        return privateDirectory(Path.of(local, "EDT-MCP"));
    }

    public static Path privateDirectory(Path directory) throws IOException
    {
        if (!directory.isAbsolute()) { throw new IOException("Private directory must be absolute"); }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) { throw new IOException("Private directory must not be a link"); }
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl != null)
        {
            String account = ProcessHandle.current().info().user().orElseThrow(
                () -> new IOException("Cannot determine the current Windows account"));
            var owner = directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
            if (!Files.getOwner(directory).equals(owner)) { throw new IOException("Private directory has another owner"); }
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
        else { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")); }
        return directory;
    }

    public static String workspaceKey(Path workspace) throws IOException
    {
        try
        {
            String identity = workspace.toRealPath().toString();
            if (System.getProperty("os.name").startsWith("Windows")) { identity = identity.toLowerCase(java.util.Locale.ROOT); }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public Path recordPath() { return directory.resolve(key + ".json"); }
    public Path claimPath() { return directory.resolve(key + ".starting.json"); }
    public Path lockPath() { return directory.resolve(key + ".lock"); }
    public Path directory() { return directory; }
    public Path workspace() { return workspace; }

    public Endpoint read() throws IOException
    {
        if (!Files.exists(recordPath())) { return null; }
        try
        {
            if (Files.size(recordPath()) > 16384) { throw new IOException("Oversized endpoint record"); }
            Endpoint record = JSON.fromJson(Files.readString(recordPath()), Endpoint.class);
            if (record == null || record.generation() == null || record.secret() == null
                || record.socket() == null || record.startTime() == null || record.workspace() == null)
            { throw new IOException("Incomplete endpoint record"); }
            return record;
        }
        catch (RuntimeException e) { throw new IOException("Invalid endpoint record", e); }
    }

    public void publish(Endpoint endpoint) throws IOException
    {
        try (FileChannel channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock())
        {
            Endpoint previous = read();
            if (previous != null && live(previous) && !previous.generation().equals(endpoint.generation()))
            { throw new IOException("Another live EDT owns the workspace endpoint"); }
            atomicWrite(recordPath(), JSON.toJson(endpoint));
            // Readiness supersedes the startup lease. Retaining it would delay a restart for
            // 180 seconds if this fully-started EDT crashed or closed soon after launch.
            Files.deleteIfExists(claimPath());
        }
    }

    public boolean remove(String generation) throws IOException
    {
        try (FileChannel channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.lock())
        {
            Endpoint current = read();
            if (current == null || !current.generation().equals(generation)) { return false; }
            return Files.deleteIfExists(recordPath());
        }
    }

    public static void atomicWrite(Path destination, String text) throws IOException
    {
        Path temporary = destination.resolveSibling(destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try
        {
            Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        finally { Files.deleteIfExists(temporary); }
    }

    public static boolean live(Endpoint endpoint)
    {
        return sameProcess(endpoint.pid(), endpoint.startTime());
    }

    public static boolean sameProcess(long pid, String startTime)
    {
        try
        {
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(p -> p.info().startInstant())
                .map(Instant::toString).filter(startTime::equals).isPresent();
        }
        catch (RuntimeException e) { return false; }
    }
}
