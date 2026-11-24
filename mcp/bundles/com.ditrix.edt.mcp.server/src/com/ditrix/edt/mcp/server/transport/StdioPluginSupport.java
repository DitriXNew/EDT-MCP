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
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
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
    static final Set<String> COMPATIBLE_ENTRY_HASHES = Set.of(
        "26b2d4c1384cee32e8c5c56cf78b988b9b18a5ae79612d6558e8dbd4f92f3e6b", // v2.26.1
        // The same launcher, built before the entry became reproducible: identical classes and
        // manifest, only the embedded license carried CRLF from a Windows checkout. A machine that
        // extracted it must be able to upgrade without closing its clients, or the normalization
        // would strand exactly the installs it was meant to help. Drop it when no such install is
        // expected to upgrade any more.
        "958937f8d1b548ce21698317ca0c17ef4a475e40a797de1dfeef72c6d19686f8"
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

    /** Replaces the fixed-name launcher. Injected so the refusal paths are testable on any OS. */
    @FunctionalInterface
    interface EntryReplace
    {
        /**
         * @param destination the fixed-name launcher path
         * @param bytes the launcher bytes to publish
         * @return an access denial from the final atomic move, or null when the entry was replaced
         * @throws IOException when staging or cleanup fails; the entry is then unchanged
         */
        AccessDeniedException replace(Path destination, byte[] bytes) throws IOException;
    }

    static Path extractBootstrap(Path directory, byte[] runtime, byte[] entry, RuntimeDelete delete) throws IOException
    { return extractBootstrap(directory, runtime, entry, delete, StdioPluginSupport::replaceEntry); }

    /**
     * Replaces the entry through a sibling staging file, so only the final move can be refused by a
     * client holding the entry open. Staying out of {@link PrivatePaths} keeps the packaged launcher
     * bytes stable: that class is compiled into the entry jar, and editing it would force every
     * Windows client to replace an entry whose behaviour did not change.
     */
    private static AccessDeniedException replaceEntry(Path destination, byte[] bytes) throws IOException
    {
        Path staging = destination.resolveSibling(destination.getFileName() + ".installing");
        PrivatePaths.noLinks(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { PrivatePaths.verifyFile(destination); }
        try
        {
            PrivatePaths.atomicWrite(staging, bytes);
            try
            {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return null;
            }
            catch (AccessDeniedException e) { return e; }
        }
        finally { Files.deleteIfExists(staging); }
    }

    static Path extractBootstrap(Path directory, byte[] runtime, byte[] entry, RuntimeDelete delete, EntryReplace replace)
        throws IOException
    {
        Path destination = directory.resolve("edt-mcp-bootstrap.jar");
        try (var channel = PrivatePaths.lockFile(directory.resolve("extract.lock"));
             var lock = channel.lock())
        {
            String current = sha256(runtime) + ".jar", previous = runtimePointer(directory.resolve("current"));
            // Re-extracting the same build must not discard the preceding generation.
            if (current.equals(previous)) { previous = runtimePointer(directory.resolve("previous")); }
            writeJar(directory.resolve(current), runtime);
            byte[] existing = existingJar(destination);
            boolean identical = Arrays.equals(existing, entry);
            boolean compatible = !identical && existing != null && COMPATIBLE_ENTRY_HASHES.contains(sha256(existing));
            // The runtime is what changes behaviour, so its pointer moves first - but only when the
            // entry that will stay behind can actually load it. The entry keeps a fixed name while
            // its bytes are versioned, so a connected stdio client holds it open on Windows and the
            // replacement fails with AccessDenied. A launcher outside the compatibility allowlist is
            // replaced BEFORE the pointer moves: publishing first would let it read a runtime it
            // cannot load, and the throw would no longer fail closed.
            if (identical || compatible)
            {
                publishRuntimePointer(directory, previous, current, delete);
                if (identical) { return destination; }
                var refusal = replace.replace(destination, entry);
                if (refusal != null)
                {
                    if (!Arrays.equals(existing, existingJar(destination)))
                    {
                        throw new IOException("The stdio entry changed while it was being replaced; close the stdio clients and retry.", refusal);
                    }
                    Log.warning("Cannot replace the stdio entry; the current runtime is published and the existing entry still starts it."
                        + " Close stdio clients before the next start to replace the entry.", refusal);
                }
                return destination;
            }
            var refusal = replace.replace(destination, entry);
            if (refusal != null)
            {
                // Nothing was published, so three generations must survive: the staged runtime for
                // a later retry, the runtime `current` still names, and the predecessor recorded in
                // `previous` - a launcher that read it just before `current` was published is still
                // within the documented one-update race. Everything else is dropped as usual.
                cleanupRuntimes(directory, keep(current, previous, runtimePointer(directory.resolve("previous"))), delete);
                throw new IOException("Close stdio clients before updating the bootstrap entry, then reopen Window > Preferences > MCP Server > General.", refusal);
            }
            publishRuntimePointer(directory, previous, current, delete);
            return destination;
        }
    }

    /** Publishes the new runtime, then drops generations no retained entry can still open. */
    private static void publishRuntimePointer(Path directory, String previous, String current, RuntimeDelete delete)
        throws IOException
    {
        EndpointRegistry.atomicWrite(directory.resolve("previous"), previous == null ? "" : previous + "\n");
        EndpointRegistry.atomicWrite(directory.resolve("current"), current + "\n");
        cleanupRuntimes(directory, keep(current, previous), delete);
    }

    /** The runtime names cleanup must not delete; a null name is simply not kept. */
    private static Set<String> keep(String... names)
    {
        Set<String> kept = new HashSet<>();
        for (String name : names) { if (name != null) { kept.add(name); } }
        return kept;
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

    private static void cleanupRuntimes(Path directory, Set<String> keep, RuntimeDelete delete)
    {
        try
        {
            PrivatePaths.verifyDirectory(directory);
            try (var files = Files.newDirectoryStream(directory))
            {
                for (Path file : files)
                {
                    String name = file.getFileName().toString();
                    if (!name.matches("[a-f0-9]{64}\\.jar") || keep.contains(name)) { continue; }
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
