/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Stable launcher entry. Keep this tiny ABI independent of the versioned runtime and Gson. */
public final class StdioEntry
{
    // Bump the ABI for incompatible launcher/runtime changes, not for every plugin release.
    private static final String ABI = "abi-1";
    public static final String CURRENT_POINTER = "current-" + ABI;
    public static final String PREVIOUS_POINTER = "previous-" + ABI;
    public static final String RUNTIME_PREFIX = ABI + "-";
    public static final String RUNTIME_NAME_PATTERN = RUNTIME_PREFIX + "[a-f0-9]{64}\\.jar";

    private StdioEntry() { }
    public static void main(String[] args)
    {
        try
        {
            Path directory = Path.of(StdioEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            // Both platforms use <private root>/bootstrap/<install hash>.
            for (Path path : new Path[]{directory, directory.getParent(), directory.getParent().getParent()})
            { PrivatePaths.verifyDirectory(path); }
            Path pointer = directory.resolve(CURRENT_POINTER);
            PrivatePaths.verifyFile(pointer);
            String name = Files.readString(pointer, StandardCharsets.UTF_8).trim();
            if (!name.matches(RUNTIME_NAME_PATTERN)) { throw new IOException("Invalid bootstrap runtime pointer"); }
            Path runtime = directory.resolve(name);
            PrivatePaths.verifyFile(runtime);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime)));
            if (!hash.equals(name.substring(RUNTIME_PREFIX.length(), RUNTIME_PREFIX.length() + 64))) { throw new IOException("Bootstrap runtime SHA-256 does not match its file name"); }
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{runtime.toUri().toURL()}, ClassLoader.getPlatformClassLoader()))
            {
                Class.forName("com.ditrix.edt.mcp.server.ipc.StdioBootstrap", true, loader)
                    .getMethod("main", String[].class).invoke(null, (Object)args);
            }
        }
        catch (Exception e)
        {
            System.err.println("EDT MCP entry: " + String.valueOf(e.getMessage()).replace('\r', ' ').replace('\n', ' '));
            System.exit(1);
        }
    }
}
