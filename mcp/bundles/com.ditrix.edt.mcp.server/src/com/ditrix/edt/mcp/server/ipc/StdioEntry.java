/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.ipc;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stable launcher entry. Keep this tiny ABI independent of the versioned runtime and Gson. */
public final class StdioEntry
{
    private StdioEntry() { }
    public static void main(String[] args)
    {
        try
        {
            Path directory = Path.of(StdioEntry.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
            String name = Files.readString(directory.resolve("current"), StandardCharsets.UTF_8).trim();
            if (!name.matches("[a-f0-9]{64}\\.jar")) { throw new java.io.IOException("Invalid bootstrap runtime pointer"); }
            Path runtime = directory.resolve(name);
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{runtime.toUri().toURL()}, ClassLoader.getPlatformClassLoader()))
            {
                Class.forName("com.ditrix.edt.mcp.server.ipc.StdioBootstrap", true, loader)
                    .getMethod("main", String[].class).invoke(null, (Object)args);
            }
        }
        catch (Exception e)
        {
            System.err.println("EDT MCP entry: " + e.getMessage());
            System.exit(1);
        }
    }
}
