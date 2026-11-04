/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import com.ditrix.edt.mcp.server.utils.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.Platform;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;


/** Plugin lifecycle adapter and read-only client configuration export. */
public final class StdioPluginSupport
{
    private static volatile IpcListener listener;
    private StdioPluginSupport() { }

    public static synchronized void start()
    {
        if (listener != null) { return; }
        Path bootstrap = null;
        try { bootstrap = extractBootstrap(); }
        catch (Exception e) { Log.warning("Cannot extract the stdio bootstrap (" + e.getClass().getSimpleName() + "); HTTP is unaffected.", e); }
        try
        {
            Path workspace = Path.of(Platform.getInstanceLocation().getURL().toURI()).toRealPath();
            listener = new IpcListener(new EndpointRegistry(EndpointRegistry.privateDirectory(
                EndpointRegistry.userDirectory().resolve("ipc")), workspace),
                Activator.getDefault().getBundle().getVersion().toString(), bootstrap);
            Activator.logInfo("EDT MCP IPC listener started for " + workspace);
        }
        catch (Exception e) { Log.warning("Cannot start EDT MCP IPC listener (" + e.getClass().getSimpleName() + "); HTTP is unaffected.", e); }
    }

    public static boolean isIpcRunning()
    { IpcListener current = listener; return current != null && current.isRunning(); }

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
        Path root = EndpointRegistry.privateDirectory(EndpointRegistry.userDirectory().resolve("bootstrap"));
        Path directory = EndpointRegistry.privateDirectory(root.resolve(EndpointRegistry.workspaceKey(install)));
        return extractBootstrap(directory, embedded("edt-mcp-runtime.jar"), embedded("edt-mcp-bootstrap.jar"), Files::delete);
    }

    @FunctionalInterface
    interface RuntimeDelete { void delete(Path runtime) throws IOException; }

    static Path extractBootstrap(Path directory, byte[] runtime, byte[] entry, RuntimeDelete delete) throws IOException
    {
        Path destination = directory.resolve("edt-mcp-bootstrap.jar");
        try (var channel = PrivatePaths.lockFile(directory.resolve("extract.lock"));
             var lock = channel.lock())
        {
            String hash;
            try { hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(runtime)); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
            String current = hash + ".jar", previous = runtimePointer(directory.resolve("current"));
            // Re-extracting the same build must not discard the preceding generation.
            if (current.equals(previous)) { previous = runtimePointer(directory.resolve("previous")); }
            writeJar(directory.resolve(current), runtime);
            writeJar(destination, entry);
            EndpointRegistry.atomicWrite(directory.resolve("previous"), previous == null ? "" : previous + "\n");
            EndpointRegistry.atomicWrite(directory.resolve("current"), current + "\n");
            cleanupRuntimes(directory, current, previous, delete);
            return destination;
        }
    }

    private static String runtimePointer(Path pointer) throws IOException
    {
        if (!Files.exists(pointer, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { return null; }
        PrivatePaths.verifyFile(pointer);
        String name = Files.readString(pointer).trim();
        return name.matches("[a-f0-9]{64}\\.jar") ? name : null;
    }

    private static void cleanupRuntimes(Path directory, String current, String previous, RuntimeDelete delete)
    {
        try
        {
            PrivatePaths.verifyDirectory(directory);
            try (var files = Files.newDirectoryStream(directory))
            {
                for (Path file : files)
                {
                    String name = file.getFileName().toString();
                    if (!name.matches("[a-f0-9]{64}\\.jar") || name.equals(current) || name.equals(previous)) { continue; }
                    try { PrivatePaths.verifyFile(file); delete.delete(file); }
                    catch (IOException e)
                    { Log.debug("Cannot remove obsolete stdio runtime " + name + " (" + e.getClass().getSimpleName() + ")"); }
                }
            }
        }
        catch (IOException | java.nio.file.DirectoryIteratorException e)
        { Log.debug("Cannot enumerate obsolete stdio runtimes (" + e.getClass().getSimpleName() + ")"); }
    }

    private static byte[] embedded(String name) throws IOException
    {
        var entry = Activator.getDefault().getBundle().getEntry("bootstrap/" + name);
        if (entry == null) { throw new IOException("This plugin build has no embedded " + name); }
        try (var input = entry.openStream()) { return input.readAllBytes(); }
    }

    private static void writeJar(Path destination, byte[] bytes) throws IOException
    {
        PrivatePaths.noLinks(destination);
        if (Files.exists(destination))
        {
            PrivatePaths.verifyFile(destination);
            if (java.util.Arrays.equals(Files.readAllBytes(destination), bytes)) { return; }
        }
        try
        {
            PrivatePaths.atomicWrite(destination, bytes);
        }
        catch (java.nio.file.AccessDeniedException e)
        { throw new IOException("Close stdio clients before updating the bootstrap entry, then reopen Window > Preferences > MCP Server > General.", e); }
    }

    public static String clientConfiguration() throws Exception
    {
        Path install = Path.of(Platform.getInstallLocation().getURL().toURI()).toRealPath();
        Path workspace = Path.of(Platform.getInstanceLocation().getURL().toURI()).toRealPath();
        Path configuration = Path.of(Platform.getConfigurationLocation().getURL().toURI()).toRealPath();
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        var arguments = windows ? List.of("--launcher.ini", install.resolve("1cedt.ini").toString(), "--launcher.suppressErrors",
            "-nosplash", "-vm", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-configuration", configuration.toString(),
            "-startup", extractBootstrap().toString(), "-install", install.toString(), "-data", workspace.toString())
            : List.of("-jar", extractBootstrap().toString(), "-install", install.toString(), "-data", workspace.toString());
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(java.util.Map.of(
            "mcpServers", java.util.Map.of("edt", java.util.Map.of("type", "stdio",
                "command", windows ? install.resolve("1cedtc.exe").toString()
                    : Path.of(System.getProperty("java.home"), "bin", "java").toString(), "args", arguments))));
    }
}
