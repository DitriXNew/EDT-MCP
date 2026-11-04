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
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import com.google.gson.Gson;

/** Serializes first-call starts across bootstrap processes. A durable claim survives the client. */
public final class StartupCoordinator
{
    public static final String STARTING = "EDT is starting. Retry the same call in about 15 seconds. No tool ran.";
    public static final String OPEN_WORKSPACE = "Automatic EDT startup is supported only on Windows. "
        + "Ask the user to open this workspace in EDT, then retry the same call. No tool ran.";
    private static final Gson JSON = new Gson();
    public record Started(long pid, String startTime) { }
    private record Claim(String requested, Started process) { }
    @FunctionalInterface public interface Starter
    {
        Started start() throws Exception;
        default boolean supportsStartup() { return true; }
        default String notReadyMessage() { return "EDT is running but IPC is not ready. Ask the user to inspect the EDT window for a blocking dialog."; }
    }
    private final EndpointRegistry registry;
    private final Starter starter;
    public StartupCoordinator(EndpointRegistry registry, Starter starter)
    { this.registry = registry; this.starter = starter; }

    public String ensureStarted() throws Exception
    { return ensureStarted(() -> { }); }

    public String ensureStarted(Runnable launching) throws Exception
    {
        if (!starter.supportsStartup()) { return OPEN_WORKSPACE; }
        try (FileChannel channel = PrivatePaths.lockFile(registry.lockPath()))
        {
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException e) { return STARTING; }
            if (acquired == null) { return STARTING; }
            try (FileLock lock = acquired)
            {
                var endpoint = registry.read();
                if (endpoint != null && EndpointRegistry.live(endpoint))
                { return "EDT is connecting. Retry the same call in about 2 seconds. No tool ran."; }
                if (Files.exists(registry.claimPath()))
                {
                    PrivatePaths.verifyFile(registry.claimPath());
                    Claim claim;
                    try { claim = JSON.fromJson(Files.readString(registry.claimPath()), Claim.class); }
                    catch (RuntimeException e) { throw new IOException("Invalid EDT startup claim", e); }
                    boolean recent = claim != null && Instant.parse(claim.requested()).plusSeconds(180).isAfter(Instant.now());
                    boolean live = claim != null && claim.process() != null
                        && EndpointRegistry.sameProcess(claim.process().pid(), claim.process().startTime());
                    if (recent && (claim.process() == null || live)) { return STARTING; }
                    if (live) { return starter.notReadyMessage(); }
                }
                // A missing listener must never cause a second EDT against an already-open workspace.
                var workspaceLock = registry.workspace().resolve(".metadata/.lock");
                if (Files.exists(workspaceLock))
                {
                    try (FileChannel wc = FileChannel.open(workspaceLock, StandardOpenOption.WRITE))
                    {
                        try (FileLock wl = wc.tryLock())
                        {
                            if (wl == null) { return occupied(); }
                        }
                    }
                    catch (IOException | OverlappingFileLockException e) { return occupied(); }
                }
                EndpointRegistry.atomicWrite(registry.claimPath(), JSON.toJson(new Claim(Instant.now().toString(), null)));
                // Keep the claim if the provider outcome is uncertain. Never blindly retry a launch.
                launching.run();
                Started process = starter.start();
                EndpointRegistry.atomicWrite(registry.claimPath(), JSON.toJson(new Claim(Instant.now().toString(), process)));
                return STARTING;
            }
        }
    }

    private static String occupied()
    {
        return "This workspace is already open, but its EDT MCP IPC listener is unavailable. Ask the user to inspect "
            + "Window > Preferences > MCP Server. This call has not executed.";
    }
}
