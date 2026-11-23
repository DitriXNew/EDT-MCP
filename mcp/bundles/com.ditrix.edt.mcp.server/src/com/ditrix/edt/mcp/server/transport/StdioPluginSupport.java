/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.transport;

import com.ditrix.edt.mcp.server.utils.FileUrls;
import com.ditrix.edt.mcp.server.utils.Log;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.Platform;
import org.eclipse.osgi.service.datalocation.Location;
import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ipc.EndpointRegistry;
import com.ditrix.edt.mcp.server.ipc.PrivatePaths;


/** Plugin lifecycle adapter and read-only client configuration export. */
public final class StdioPluginSupport
{
    /** The running listener; replaced only under the class lock, read lock-free by {@link #isIpcRunning}. */
    private static final AtomicReference<IpcListener> LISTENER = new AtomicReference<>();
    // Whole-JAR fingerprints of inspected launchers: both read the sibling current pointer,
    // verify the hash-named runtime, and call StdioBootstrap.main(String[]). Trusting a manifest
    // or a class name alone would not prove this contract. Keep this list scoped to this ABI.
    private static final Set<String> COMPATIBLE_ENTRY_HASHES = Set.of(
        "26b2d4c1384cee32e8c5c56cf78b988b9b18a5ae79612d6558e8dbd4f92f3e6b", // v2.26.1
        "958937f8d1b548ce21698317ca0c17ef4a475e40a797de1dfeef72c6d19686f8"  // PR #4 base (a274801c)
    );
    private StdioPluginSupport() { }

    public static synchronized void start()
    {
        if (LISTENER.get() != null) { return; }
        Path bootstrap = null;
        try { bootstrap = extractBootstrap(); }
        catch (Exception e) { Log.warning("Cannot extract the stdio bootstrap (" + e.getClass().getSimpleName() + "); HTTP is unaffected.", e); }
        try
        {
            Path workspace = realLocation(Platform.getInstanceLocation(), "instance");
            LISTENER.set(new IpcListener(new EndpointRegistry(EndpointRegistry.privateDirectory(
                EndpointRegistry.userDirectory().resolve("ipc")), workspace),
                Activator.getDefault().getBundle().getVersion().toString(), bootstrap));
            Activator.logInfo("EDT MCP IPC listener started for " + workspace);
        }
        catch (Exception e) { Log.warning("Cannot start EDT MCP IPC listener (" + e.getClass().getSimpleName() + "); HTTP is unaffected.", e); }
    }

    public static boolean isIpcRunning()
    { IpcListener current = LISTENER.get(); return current != null && current.isRunning(); }

    public static synchronized void stop()
    {
        IpcListener current = LISTENER.getAndSet(null);
        if (current != null)
        {
            try { current.close(); } catch (IOException e) { Activator.logError("Cannot close EDT MCP IPC listener", e); }
        }
    }

    public static synchronized Path extractBootstrap() throws IOException
    {
        Path install = realLocation(Platform.getInstallLocation(), "install");
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
            String current = sha256(runtime) + ".jar", previous = runtimePointer(directory.resolve("current"));
            // Re-extracting the same build must not discard the preceding generation.
            if (current.equals(previous)) { previous = runtimePointer(directory.resolve("previous")); }
            writeJar(directory.resolve(current), runtime);
            // The runtime is what changes behaviour, so its pointer is published first. The entry
            // keeps a fixed name while its bytes are versioned, so a connected stdio client holds it
            // open on Windows and the replacement fails with AccessDenied. Publishing the pointer
            // before writing the entry lets a known compatible launcher load the new runtime.
            // Extraction retries the replacement on a later start, once the client is gone.
            EndpointRegistry.atomicWrite(directory.resolve("previous"), previous == null ? "" : previous + "\n");
            EndpointRegistry.atomicWrite(directory.resolve("current"), current + "\n");
            cleanupRuntimes(directory, current, previous, delete);
            byte[] existing = existingJar(destination);
            if (Arrays.equals(existing, entry)) { return destination; }
            var refusal = PrivatePaths.atomicWriteReportingMoveDenial(destination, entry);
            if (refusal != null)
            {
                if (existing == null || !COMPATIBLE_ENTRY_HASHES.contains(sha256(existing))
                    || !Arrays.equals(existing, existingJar(destination)))
                {
                    throw new IOException("Close stdio clients before updating the bootstrap entry, then reopen Window > Preferences > MCP Server > General.", refusal);
                }
                Log.warning("Cannot replace the stdio entry; the current runtime is published and the existing entry still starts it."
                    + " Close stdio clients before the next start to replace the entry.", refusal);
            }
            return destination;
        }
    }

    private static String sha256(byte[] bytes)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String runtimePointer(Path pointer) throws IOException
    {
        if (!Files.exists(pointer, LinkOption.NOFOLLOW_LINKS)) { return null; }
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

    private static byte[] existingJar(Path destination) throws IOException
    {
        PrivatePaths.noLinks(destination);
        try { PrivatePaths.verifyFile(destination); }
        catch (NoSuchFileException e) { return null; }
        return Files.readAllBytes(destination);
    }

    private static void writeJar(Path destination, byte[] bytes) throws IOException
    {
        if (!Arrays.equals(existingJar(destination), bytes)) { PrivatePaths.atomicWrite(destination, bytes); }
    }

    /** The real path of an Eclipse location; the URL may be raw (see {@link FileUrls}). */
    static Path realLocation(Location location, String name) throws IOException
    {
        URL url = location == null ? null : location.getURL();
        if (url == null) { throw new IOException("The EDT " + name + " location is not available"); }
        return FileUrls.toPath(url).toRealPath();
    }

    public static String clientConfiguration() throws Exception
    {
        Path install = realLocation(Platform.getInstallLocation(), "install");
        Path workspace = realLocation(Platform.getInstanceLocation(), "instance");
        Path configuration = realLocation(Platform.getConfigurationLocation(), "configuration");
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
