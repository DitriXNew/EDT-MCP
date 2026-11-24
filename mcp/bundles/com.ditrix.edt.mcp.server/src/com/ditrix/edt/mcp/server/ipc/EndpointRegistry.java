/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
        // Files.exists is false both for an absent record and for one whose state cannot be
        // determined; only the first means no host is registered. Anything else is an error the
        // caller must not read as "absent".
        if (!present(recordPath())) { return null; }
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

    /** True when the path exists; an absent file is false, an undeterminable state throws. */
    private static boolean present(Path path) throws IOException
    {
        try
        {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        }
        catch (NoSuchFileException e) { return false; }
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

    /** How long after it was requested a startup claim still describes a start in flight. */
    public static final Duration CLAIM_WINDOW = Duration.ofSeconds(180);

    /**
     * The durable startup claim as the bootstrap wrote it. Both the coordinator and the stop
     * command read occupancy from this one shape, so they cannot disagree about what a claim says.
     */
    public record StartupClaim(Instant requested, Long pid, String startTime)
    {
        /** Whether the claim is inside {@link #CLAIM_WINDOW} of its own requested time. */
        public boolean isRecent(Instant now)
        {
            return requested != null && !requested.isAfter(now)
                && Duration.between(requested, now).compareTo(CLAIM_WINDOW) < 0;
        }

        /** Whether the claim names a process that is still alive. */
        public boolean namesALiveProcess() { return pid != null && startTime != null && sameProcess(pid, startTime); }
    }

    /** Whether another process currently holds the workspace lock. */
    public enum LockState
    {
        /** The lock was taken and released by this probe: nothing owns the workspace. */
        FREE,
        /** A process holds the lock. */
        HELD,
        /** The lock could not be probed: its state is unknown, which is not the same as free. */
        UNKNOWN
    }

    /**
     * Reads the startup claim, or {@code null} when the workspace has none or its claim is
     * uninformative. Three states are told apart:
     * <ul>
     * <li><b>no claim</b> and <b>a readable but corrupt claim</b> return {@code null}, so the
     * caller falls through to the workspace lock, which is the authoritative signal.</li>
     * <li><b>a claim that exists and cannot be read</b> - a dangling link, a denied read - throws:
     * it proves nothing either way and must stay unknown, not become an invitation to start.</li>
     * </ul>
     *
     * @return the claim, or null when there is none or it carries no usable fields
     * @throws IOException when a claim exists and cannot be resolved
     */
    public StartupClaim readStartupClaim() throws IOException
    {
        Path claim = claimPath();
        try { Files.readAttributes(claim, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException e) { return null; }
        catch (IOException | SecurityException e) { throw new IOException("Cannot probe the EDT startup claim", e); }
        String body;
        try { body = Files.readString(claim); }
        catch (IOException | RuntimeException e) { throw new IOException("Cannot read the EDT startup claim", e); }
        JsonObject parsed;
        try { parsed = JSON.fromJson(body, JsonObject.class); }
        catch (RuntimeException e) { return null; }
        if (parsed == null) { return null; }
        Instant requested = null;
        try
        {
            if (parsed.has("requested") && parsed.get("requested").isJsonPrimitive()
                && parsed.getAsJsonPrimitive("requested").isString())
            {
                requested = Instant.parse(parsed.get("requested").getAsString());
            }
        }
        catch (RuntimeException e) { requested = null; }
        JsonObject process = parsed.has("process") && parsed.get("process").isJsonObject()
            ? parsed.getAsJsonObject("process") : null;
        if (process == null) { return new StartupClaim(requested, null, null); }
        // Strict shapes: Gson coerces, so a pid written as a string or a fractional number would
        // otherwise match a live process and hold every occupancy answer at unknown.
        Long pid = exactPid(process);
        String start = process.has("startTime") && process.get("startTime").isJsonPrimitive()
            && process.getAsJsonPrimitive("startTime").isString()
            ? process.get("startTime").getAsString() : null;
        return new StartupClaim(requested, pid, start);
    }

    /** The claim's pid as an exact nonnegative integer, or null when the token is not one. */
    private static Long exactPid(JsonObject process)
    {
        if (!process.has("pid") || !process.get("pid").isJsonPrimitive()
            || !process.getAsJsonPrimitive("pid").isNumber())
        {
            return null;
        }
        String token = process.get("pid").getAsString();
        if (!token.matches("0|[1-9][0-9]{0,18}")) { return null; }
        try { return Long.parseLong(token); }
        catch (NumberFormatException e) { return null; }
    }

    /**
     * Probes the workspace lock. The lock file is opened for writing and locked: taking the lock
     * proves no other process holds it, and an absent file proves nothing holds it either. Any
     * other failure - an unsearchable parent, a denying ACL - is unknown, never free.
     *
     * @return whether a process still holds the workspace lock
     */
    public LockState workspaceLockState() { return workspaceLockState(workspace); }

    /**
     * Probes a workspace lock without a registry, so a caller can ask whether a workspace is owned
     * before it has resolved the private registry at all.
     *
     * @param workspace the workspace directory
     * @return HELD, FREE, or UNKNOWN when the probe itself could not decide
     */
    public static LockState workspaceLockState(Path workspace)
    {
        Path lock = workspace.resolve(".metadata/.lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE))
        {
            try (FileLock held = channel.tryLock()) { return held == null ? LockState.HELD : LockState.FREE; }
        }
        catch (OverlappingFileLockException e) { return LockState.HELD; }
        catch (NoSuchFileException e)
        {
            // A lock that exists without following it never resolved, so its state is unknown. Only
            // a path that is really absent proves the workspace is free.
            return provablyAbsent(lock, LinkOption.NOFOLLOW_LINKS)
                ? LockState.FREE : LockState.UNKNOWN;
        }
        catch (IOException | SecurityException e) { return LockState.UNKNOWN; }
    }

    static boolean provablyAbsent(Path path, LinkOption... options)
    {
        // Windows also reports NoSuchFileException when a regular file obstructs the path.
        // Walk up to find the nearest component that exists: absence is proven only when that
        // component is an ordinary directory. A component that exists but is a link we cannot
        // follow, or whose followed chain does not resolve, leaves the state unknown - walking
        // past it would report a workspace behind a dangling link as merely missing.
        Path absolute = path.toAbsolutePath();
        for (Path current = absolute; current != null; current = current.getParent())
        {
            BasicFileAttributes attributes;
            try { attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS); }
            catch (NoSuchFileException e) { continue; }
            catch (IOException | SecurityException e) { return false; }
            // The path itself exists without following it - a link whose chain did not resolve -
            // so it is not absent, and its parent must not stand in for it.
            if (current.equals(absolute)) { return false; }
            // A component we cannot follow proves nothing either: the chain never resolved
            // through it, so the state stays unknown rather than reading as absent.
            if (attributes.isSymbolicLink() && options.length > 0) { return false; }
            try { return Files.readAttributes(current, BasicFileAttributes.class, options).isDirectory(); }
            catch (NoSuchFileException e) { return false; }
            catch (IOException | SecurityException e) { return false; }
        }
        return false;
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
