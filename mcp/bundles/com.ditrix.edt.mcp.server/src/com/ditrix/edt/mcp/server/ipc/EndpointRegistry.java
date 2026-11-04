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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
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
        String pluginVersion, int ipcVersion, String socket, String secret, String bootstrap)
    {
        public Endpoint(String workspace, String generation, long pid, String startTime,
            String pluginVersion, int ipcVersion, String socket, String secret)
        { this(workspace, generation, pid, startTime, pluginVersion, ipcVersion, socket, secret, null); }
        @Override public String toString() { return "Endpoint[generation=" + generation + ", pid=" + pid + ", ipcVersion=" + ipcVersion + "]"; }
    }

    /** The caller provisions a private directory before constructing the record store. */
    public EndpointRegistry(Path directory, Path workspace) throws IOException
    {
        PrivatePaths.verifyDirectory(directory);
        this.directory = directory;
        this.workspace = workspace.toRealPath();
        key = workspaceKey(workspace);
    }

    public static Path userDirectory() throws IOException
    {
        return privateDirectory(userPath(System.getProperty("os.name"), System.getenv(), Path.of(System.getProperty("user.home"))));
    }

    public static Path userPath(String os, java.util.Map<String, String> environment, Path home) throws IOException
    {
        String base = environment.get(os.startsWith("Windows") ? "LOCALAPPDATA" : "XDG_RUNTIME_DIR");
        if (base != null && !base.isBlank()) { return Path.of(base).resolve(os.startsWith("Windows") ? "EDT-MCP" : "edt-mcp"); }
        if (os.startsWith("Windows")) { throw new IOException("LOCALAPPDATA is required on Windows"); }
        base = environment.get("XDG_STATE_HOME");
        return (base != null && !base.isBlank() ? Path.of(base) : home.resolve(".local/state")).resolve("edt-mcp");
    }

    public static Path privateDirectory(Path directory) throws IOException
    {
        return PrivatePaths.directory(directory);
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
        PrivatePaths.verifyDirectory(directory);
        if (!Files.exists(recordPath(), LinkOption.NOFOLLOW_LINKS)) { return null; }
        PrivatePaths.verifyFile(recordPath());
        try
        {
            if (Files.size(recordPath()) > 16384) { throw new IOException("Oversized endpoint record"); }
            Endpoint record = JSON.fromJson(Files.readString(recordPath()), Endpoint.class);
            if (record == null || record.generation() == null || record.secret() == null
                || record.socket() == null || record.startTime() == null || record.workspace() == null)
            { throw new IOException("Incomplete endpoint record"); }
            if (!workspaceKey(Path.of(record.workspace())).equals(key)
                || !Path.of(record.socket()).isAbsolute()
                || !Path.of(record.socket()).getParent().equals(directory))
            { throw new IOException("Endpoint identity does not match this private workspace registry"); }
            PrivatePaths.noLinks(Path.of(record.socket()));
            if (Files.exists(Path.of(record.socket()), LinkOption.NOFOLLOW_LINKS)) { PrivatePaths.verifyOwner(Path.of(record.socket())); }
            return record;
        }
        catch (RuntimeException e) { throw new IOException("Invalid endpoint record"); }
    }

    public void publish(Endpoint endpoint) throws IOException
    {
        try (FileChannel channel = PrivatePaths.lockFile(lockPath());
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
        try (FileChannel channel = PrivatePaths.lockFile(lockPath());
             FileLock lock = channel.lock())
        {
            Endpoint current = read();
            if (current == null || !current.generation().equals(generation)) { return false; }
            return Files.deleteIfExists(recordPath());
        }
    }

    public static void atomicWrite(Path destination, String text) throws IOException
    {
        PrivatePaths.atomicWrite(destination, text.getBytes(StandardCharsets.UTF_8));
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
