/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Loads a tool's extended how-to guide from a bundled Markdown resource
 * ({@code guides/<toolName>.md}) instead of an inline Java string.
 * <p>
 * This is the single source of truth for tool guides: the prose lives in
 * {@code guides/*.md} (one file per tool, packaged into the plugin jar via
 * {@code build.properties} {@code bin.includes}), and {@link com.ditrix.edt.mcp.server.tools.IMcpTool#getGuide()}
 * delegates here keyed by the tool name. Adding a guide is dropping a Markdown
 * file; no Java change is needed.
 * <p>
 * The lookup mirrors the {@code icons/} resource pattern: the bundle is resolved
 * via {@link FrameworkUtil} and the entry read with {@link Bundle#getEntry(String)},
 * which resolves for both an exploded dev/test bundle (project root) and a packaged
 * jar (jar root). When no bundle is available (non-OSGi context) it falls back to
 * the class loader. Results are cached; a missing or unreadable guide degrades to
 * an empty string so a guide is never a hard failure.
 */
public final class GuideLoader
{
    /** Resource folder (relative to the bundle root) that holds the guide Markdown files. */
    private static final String GUIDES_DIR = "guides/"; //$NON-NLS-1$

    /** Cache of toolName -> guide body (or "" when absent); guides are static at runtime. */
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private GuideLoader()
    {
        // Utility class - no instantiation
    }

    /**
     * Returns the Markdown guide body for the given tool, or {@code ""} when there
     * is no {@code guides/<toolName>.md} resource (or it could not be read).
     *
     * @param toolName the tool name (the guide file is {@code guides/<toolName>.md})
     * @return the guide body as Markdown, or {@code ""} when there is none
     */
    public static String load(String toolName)
    {
        if (toolName == null || toolName.isEmpty())
        {
            return ""; //$NON-NLS-1$
        }
        return CACHE.computeIfAbsent(toolName, GuideLoader::readGuide);
    }

    /**
     * Clears the in-memory cache. Intended for tests that swap guide resources.
     */
    public static void clearCache()
    {
        CACHE.clear();
    }

    /**
     * Reads {@code guides/<toolName>.md} from the bundle (or the class loader as a
     * fallback). Any failure is swallowed and reported as {@code ""}.
     *
     * @param toolName the tool name
     * @return the trimmed guide body, or {@code ""} when absent/unreadable
     */
    private static String readGuide(String toolName)
    {
        String path = GUIDES_DIR + toolName + ".md"; //$NON-NLS-1$
        try
        {
            URL url = null;
            Bundle bundle = FrameworkUtil.getBundle(GuideLoader.class);
            if (bundle != null)
            {
                url = bundle.getEntry(path);
            }
            if (url == null)
            {
                // Non-OSGi fallback (e.g. a plain-classpath unit test): the guides
                // folder is on the bundle classpath in a packaged jar.
                url = GuideLoader.class.getResource("/" + path); //$NON-NLS-1$
            }
            if (url == null)
            {
                return ""; //$NON-NLS-1$
            }
            try (InputStream in = url.openStream())
            {
                return read(in);
            }
        }
        catch (IOException | RuntimeException e)
        {
            Log.warning("guide resource unavailable for '" + toolName + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return ""; //$NON-NLS-1$
        }
    }

    /**
     * Reads an input stream as UTF-8 and returns it with trailing whitespace
     * trimmed (the renderer adds its own surrounding layout).
     *
     * @param in the stream to read (closed by the caller)
     * @return the file content, trailing-trimmed
     * @throws IOException on a read error
     */
    private static String read(InputStream in) throws IOException
    {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
        {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1)
            {
                sb.append(buf, 0, n);
            }
        }
        // Trim trailing whitespace/newlines so the rendered "## Guide\n<body>" is tidy;
        // keep leading content intact.
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1)))
        {
            end--;
        }
        return sb.substring(0, end);
    }
}
