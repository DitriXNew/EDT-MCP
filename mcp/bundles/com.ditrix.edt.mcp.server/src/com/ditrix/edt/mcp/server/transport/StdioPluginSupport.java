/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;

/** Plugin lifecycle adapter and read-only client configuration export. */
public final class StdioPluginSupport
{
    private static IpcListener listener;
    private StdioPluginSupport() { }

    public static synchronized void start()
    {
        if (listener != null || !System.getProperty("os.name").startsWith("Windows")) { return; }
        try
        {
            extractBootstrap();
            Path workspace = Path.of(Platform.getInstanceLocation().getURL().toURI()).toRealPath();
            listener = new IpcListener(new EndpointRegistry(EndpointRegistry.privateDirectory(
                EndpointRegistry.userDirectory().resolve("ipc")), workspace),
                Activator.getDefault().getBundle().getVersion().toString());
            Activator.logInfo("EDT MCP IPC listener started for " + workspace);
        }
        catch (Exception e) { Activator.logError("Cannot start EDT MCP IPC listener", e); }
    }

    public static synchronized void stop()
    {
        if (listener != null)
        {
            try { listener.close(); } catch (IOException e) { Activator.logError("Cannot close EDT MCP IPC listener", e); }
            listener = null;
        }
    }

    public static synchronized Path extractBootstrap() throws IOException
    {
        Path install;
        try { install = Path.of(Platform.getInstallLocation().getURL().toURI()).toRealPath(); }
        catch (java.net.URISyntaxException e) { throw new IOException(e); }
        Path directory = EndpointRegistry.privateDirectory(EndpointRegistry.userDirectory().resolve("bootstrap")
            .resolve(EndpointRegistry.workspaceKey(install)));
        Path destination = directory.resolve("edt-mcp-bootstrap.jar");
        try (var channel = java.nio.channels.FileChannel.open(directory.resolve("extract.lock"),
            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock())
        {
            byte[] runtime = embedded("edt-mcp-runtime.jar");
            String hash;
            try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(runtime)); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            writeJar(directory.resolve(hash + ".jar"), runtime);
            writeJar(destination, embedded("edt-mcp-bootstrap.jar"));
            EndpointRegistry.atomicWrite(directory.resolve("current"), hash + ".jar\n");
            return destination;
        }
    }

    private static byte[] embedded(String name) throws IOException
    {
        var entry = Activator.getDefault().getBundle().getEntry("bootstrap/" + name);
        if (entry == null) { throw new IOException("This plugin build has no embedded " + name); }
        try (var input = entry.openStream()) { return input.readAllBytes(); }
    }

    private static void writeJar(Path destination, byte[] bytes) throws IOException
    {
        if (Files.exists(destination) && java.util.Arrays.equals(Files.readAllBytes(destination), bytes)) { return; }
        Path temporary = Files.createTempFile(destination.getParent(), "bootstrap-", ".jar");
        try
        {
            Files.write(temporary, bytes);
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AccessDeniedException e)
            { throw new IOException("Close stdio clients before updating the bootstrap entry, then reopen EDT Preferences > MCP Server > General.", e); }
        }
        finally { Files.deleteIfExists(temporary); }
    }

    public static String clientConfiguration() throws Exception
    {
        Path install = Path.of(Platform.getInstallLocation().getURL().toURI()).toRealPath();
        Path workspace = Path.of(Platform.getInstanceLocation().getURL().toURI()).toRealPath();
        Path configuration = Path.of(Platform.getConfigurationLocation().getURL().toURI()).toRealPath();
        var arguments = List.of("--launcher.ini", install.resolve("1cedt.ini").toString(), "--launcher.suppressErrors",
            "-nosplash", "-vm", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-configuration", configuration.toString(),
            "-startup", extractBootstrap().toString(), "-install", install.toString(), "-data", workspace.toString());
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(java.util.Map.of(
            "mcpServers", java.util.Map.of("edt", java.util.Map.of("type", "stdio",
                "command", install.resolve("1cedtc.exe").toString(), "args", arguments))));
    }
}
