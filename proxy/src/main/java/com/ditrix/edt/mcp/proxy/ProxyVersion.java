/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.proxy;

/**
 * The proxy's own version string, shared by everything that needs to report it: the MCP
 * {@code initialize} response ({@code McpProxyHandler}) and the {@code --version} CLI flag
 * ({@code CliCommands}).
 *
 * <p>Read from the running jar's manifest {@code Implementation-Version} entry, same as any
 * other {@code Package#getImplementationVersion()} lookup. Today's {@code proxy/pom.xml} does
 * not populate that entry (no {@code addDefaultImplementationEntries} / explicit manifest
 * entry is configured), so {@link #FALLBACK_VERSION} is what {@code --version} currently
 * reports even from the packaged shaded jar; wiring the manifest entry is a build-config change
 * outside this class's scope. Either way, the lookup degrades to {@link #FALLBACK_VERSION}
 * whenever the entry is absent - including when running from a test/IDE classpath with no jar
 * at all.
 */
public final class ProxyVersion
{
    private static final String FALLBACK_VERSION = "dev"; //$NON-NLS-1$

    private ProxyVersion()
    {
        // utility class
    }

    /**
     * Returns the proxy's version.
     *
     * @return the {@code Implementation-Version} manifest entry of the jar this class was
     *         loaded from, or {@value #FALLBACK_VERSION} when there is none (not running from a
     *         packaged jar)
     */
    public static String current()
    {
        String version = ProxyVersion.class.getPackage().getImplementationVersion();
        return version != null ? version : FALLBACK_VERSION;
    }
}
