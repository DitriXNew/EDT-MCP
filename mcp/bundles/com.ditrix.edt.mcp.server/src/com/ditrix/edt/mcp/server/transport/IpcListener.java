/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import com.ditrix.edt.mcp.server.HeadlessLifetime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.McpServer;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.IpcHandshake;
import com.ditrix.edt.mcp.server.ipc.JsonFrames;
import com.ditrix.edt.mcp.server.protocol.McpProtocolHandler;

/** Independently owned plugin-side listener. Starting/stopping HTTP cannot stop IPC. */
public final class IpcListener implements AutoCloseable
{
    private final EndpointRegistry registry;
    private final McpServer mcpServer;
    private final HeadlessLifetime lifetime;
    private final EndpointRegistry.Endpoint endpoint;
    private final ServerSocketChannel server;
    private final Set<JsonFrames.Socket> peers = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(8);
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "edt-mcp-ipc-deadlines"); t.setDaemon(true); return t;
    });

    public IpcListener(EndpointRegistry registry, String pluginVersion) throws IOException
    { this(registry, pluginVersion, null); }

    public IpcListener(EndpointRegistry registry, String pluginVersion, Path bootstrap) throws IOException
    { this(registry, pluginVersion, bootstrap, Activator.getDefault() != null
        ? Activator.getDefault().getMcpServer() : new McpServer()); }

    IpcListener(EndpointRegistry registry, String pluginVersion, Path bootstrap, McpServer mcpServer) throws IOException
    {
        this.registry = registry;
        this.mcpServer = mcpServer;
        this.lifetime = Activator.getDefault() == null ? null : Activator.getDefault().getHeadlessLifetime();
        String generation = UUID.randomUUID().toString();
        Path socket = registry.directory().resolve("s-" + generation.substring(0, 12));
        if (socket.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 100)
        { throw new IOException("Private IPC socket path exceeds 100 UTF-8 bytes; stdio is unavailable for this profile"); }
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        endpoint = new EndpointRegistry.Endpoint(registry.workspace().toString(), generation,
            ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().orElseThrow().toString(),
            pluginVersion, EndpointRegistry.IPC_VERSION, socket.toString(), Base64.getEncoder().encodeToString(secret),
            bootstrap == null ? null : bootstrap.toString());
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try
        {
            server.bind(UnixDomainSocketAddress.of(socket));
            if (com.ditrix.edt.mcp.server.ipc.PrivatePaths.posix(socket))
            { Files.setPosixFilePermissions(socket, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            registry.publish(endpoint);
        }
        catch (IOException | RuntimeException e)
        {
            server.close();
            try { registry.remove(endpoint.generation()); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            Files.deleteIfExists(socket); deadlines.shutdownNow(); throw e;
        }
        Thread acceptor = new Thread(this::accept, "edt-mcp-ipc-listener");
        acceptor.setDaemon(true); acceptor.start();
    }

    public boolean isRunning() { return server.isOpen(); }

    private void accept()
    {
        while (server.isOpen())
        {
            try
            {
                SocketChannel channel = server.accept();
                if (!slots.tryAcquire()) { channel.close(); continue; }
                JsonFrames.Socket peer = new JsonFrames.Socket(channel);
                var lease = lifetime == null ? null : lifetime.connect();
                if (lifetime != null && lease == null) { peer.close(); slots.release(); continue; }
                peers.add(peer);
                Thread thread = new Thread(() -> { try (lease) { serve(peer); } }, "edt-mcp-ipc-peer");
                thread.setDaemon(true); thread.start();
            }
            catch (IOException e) { return; }
        }
    }

    private void serve(JsonFrames.Socket peer)
    {
        Runnable notification = null;
        var timeout = deadlines.schedule(() -> closePeer(peer), 3, TimeUnit.SECONDS);
        try (peer)
        {
            if (!IpcHandshake.accepts(peer.read(), endpoint)) { return; }
            peer.write("{\"accepted\":true}");
            timeout.cancel(false);
            try (IpcConnection connection = new IpcConnection(mcpServer, new McpProtocolHandler(), json -> {
                try { peer.write(json); } catch (IOException e) { closePeer(peer); throw new UncheckedIOException(e); }
            }))
            {
                notification = connection::toolsChanged;
                ToolsChangedListeners.add(notification);
                String frame;
                while ((frame = peer.read()) != null) { connection.accept(frame); }
            }
        }
        catch (IOException | RuntimeException e) { /* A dropped connection never repeats a tool call. */ }
        finally
        {
            timeout.cancel(false);
            if (notification != null) { ToolsChangedListeners.remove(notification); }
            peers.remove(peer); slots.release();
        }
    }

    private static void closePeer(JsonFrames.Socket peer)
    { try { peer.close(); } catch (IOException ignored) { } }

    @Override public void close() throws IOException
    {
        server.close();
        peers.forEach(IpcListener::closePeer);
        deadlines.shutdownNow();
        // Each endpoint has a unique socket; never remove a replacement generation's record.
        registry.remove(endpoint.generation());
        Files.deleteIfExists(Path.of(endpoint.socket()));
    }
}
