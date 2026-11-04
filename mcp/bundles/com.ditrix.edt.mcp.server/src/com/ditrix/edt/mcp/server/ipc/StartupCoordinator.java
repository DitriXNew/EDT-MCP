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
    private static final Gson JSON = new Gson();
    public record Started(long pid, String startTime) { }
    private record Claim(String requested, Started process) { }
    @FunctionalInterface public interface Starter { Started start() throws Exception; }
    private final EndpointRegistry registry;
    private final Starter starter;
    public StartupCoordinator(EndpointRegistry registry, Starter starter)
    { this.registry = registry; this.starter = starter; }

    public String ensureStarted() throws Exception
    {
        try (FileChannel channel = FileChannel.open(registry.lockPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        {
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException e) { return "EDT is starting. Retry the tool call shortly."; }
            if (acquired == null) { return "EDT is starting. Retry the tool call shortly."; }
            try (FileLock lock = acquired)
            {
                var endpoint = registry.read();
                if (endpoint != null && EndpointRegistry.live(endpoint))
                { return "EDT is running; waiting for its IPC listener. Retry the tool call shortly."; }
                if (Files.exists(registry.claimPath()))
                {
                    Claim claim;
                    try { claim = JSON.fromJson(Files.readString(registry.claimPath()), Claim.class); }
                    catch (RuntimeException e) { throw new IOException("Invalid EDT startup claim", e); }
                    boolean recent = claim != null && Instant.parse(claim.requested()).plusSeconds(180).isAfter(Instant.now());
                    boolean live = claim != null && claim.process() != null
                        && EndpointRegistry.sameProcess(claim.process().pid(), claim.process().startTime());
                    if (recent) { return "EDT is starting. Retry the tool call shortly."; }
                    if (live) { return "EDT is running but IPC is not ready. Ask the user to inspect the EDT window for a blocking dialog."; }
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
                Started process = starter.start();
                EndpointRegistry.atomicWrite(registry.claimPath(), JSON.toJson(new Claim(Instant.now().toString(), process)));
                return "EDT is starting. Retry the tool call shortly. This call has not executed.";
            }
        }
    }

    private static String occupied()
    {
        return "This workspace is already open, but its EDT MCP IPC listener is unavailable. Ask the user to inspect "
            + "Window > Preferences > MCP Server. This call has not executed.";
    }
}
