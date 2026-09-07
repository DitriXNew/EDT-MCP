/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jface.preference.IPreferenceStore;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;

/**
 * Resolves the Markdown description of an EDT validation check, by its symbolic dash-cased id.
 * <p>
 * The descriptions SHIP WITH THE PLUGIN as {@code checks/<id>.md} bundle resources, so
 * get_check_description answers - and get_project_errors reports {@code hasDocumentation} -
 * out of the box, with nothing to download and nothing to configure (#31). The
 * {@code mcpChecksFolder} preference is kept as an OVERRIDE for operators who write their own
 * descriptions or translate them: a folder is consulted FIRST and wins per file, so overriding
 * one check does not hide the other 167.
 * </p>
 * <p>
 * The bundle lookup mirrors {@link GuideLoader} (and the {@code icons/} pattern): the bundle is
 * resolved via {@link FrameworkUtil} and the entry read with {@link Bundle#getEntry(String)},
 * which resolves for both an exploded dev/test bundle and a packaged jar; without a bundle
 * (a plain-classpath unit test) it falls back to the class loader.
 * </p>
 */
public final class CheckDescriptionLoader
{
    /** Resource folder (relative to the bundle root) holding the shipped description files. */
    private static final String CHECKS_DIR = "checks/"; //$NON-NLS-1$

    /**
     * Cache of checkId -&gt; whether the plugin ships a description for it.
     * <p>
     * Only PRESENCE is cached, never the body: {@link #has(String)} is called once per marker by
     * get_project_errors, which on a real configuration means thousands of calls over a few dozen
     * distinct ids, while a body is read one at a time by an explicit tool call. Caching URLs
     * instead of bodies keeps that hot path free of both I/O and a megabyte of retained Markdown.
     * </p>
     * <p>
     * The shipped set cannot change while the plugin is running, so this needs no invalidation.
     * The OVERRIDE folder is deliberately NOT cached - it is a live directory an operator edits.
     * </p>
     */
    private static final ConcurrentHashMap<String, Boolean> SHIPPED = new ConcurrentHashMap<>();

    private CheckDescriptionLoader()
    {
        // Utility class - no instantiation
    }

    /**
     * Whether a description is available for {@code checkId} - from the override folder or from
     * the plugin's own {@code checks/} resources.
     *
     * @param checkId the symbolic dash-cased check id (may be {@code null})
     * @return {@code true} when {@link #load(String)} would return a body
     */
    public static boolean has(String checkId)
    {
        String id = sanitize(checkId);
        if (id == null)
        {
            return false;
        }
        return overrideFile(id) != null || shippedUrl(id) != null;
    }

    /**
     * Reads the Markdown description for {@code checkId}, or {@code null} when there is none.
     *
     * @param checkId the symbolic dash-cased check id (may be {@code null})
     * @return the Markdown body, or {@code null} when no description exists or it is unreadable
     */
    public static String load(String checkId)
    {
        String id = sanitize(checkId);
        if (id == null)
        {
            return null;
        }
        try
        {
            Path override = overrideFile(id);
            if (override != null)
            {
                return Files.readString(override, StandardCharsets.UTF_8);
            }
            URL url = shippedUrl(id);
            if (url == null)
            {
                return null;
            }
            try (InputStream in = url.openStream())
            {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        catch (IOException | RuntimeException e)
        {
            Log.warning("check description unreadable for '" + id + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }

    /**
     * Whether the operator configured an override folder at all. Reported by get_server_status;
     * it says a folder is in play, never which one.
     *
     * @return {@code true} when the checks-folder preference holds a non-blank value
     */
    public static boolean hasOverrideFolder()
    {
        return overrideFolder() != null;
    }

    /**
     * Clears the shipped-presence cache. Intended for tests that swap check resources.
     */
    public static void clearCache()
    {
        SHIPPED.clear();
    }

    /**
     * The check id reduced to the characters a file name may contain, or {@code null} when it
     * carried anything else.
     * <p>
     * Rejecting rather than silently stripping is what stops path traversal: {@code ../../etc/passwd}
     * sanitizes to something that is not equal to the input, so it never reaches a lookup at all.
     * </p>
     *
     * @param checkId the raw id from the caller (may be {@code null})
     * @return the id when it is already safe, otherwise {@code null}
     */
    private static String sanitize(String checkId)
    {
        if (checkId == null || checkId.isEmpty())
        {
            return null;
        }
        String stripped = checkId.replaceAll("[^a-zA-Z0-9_-]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        return stripped.equals(checkId) ? checkId : null;
    }

    /**
     * The override folder's file for {@code id}, or {@code null} when no folder is configured, it
     * does not exist, or it holds no such description. The lower-cased name is tried as well, as
     * the pre-#31 lookup did.
     *
     * @param id an already-sanitized check id
     * @return an existing file in the override folder, or {@code null}
     */
    private static Path overrideFile(String id)
    {
        String folder = overrideFolder();
        if (folder == null)
        {
            return null;
        }
        try
        {
            Path folderPath = Paths.get(folder);
            if (!Files.isDirectory(folderPath))
            {
                return null;
            }
            Path file = folderPath.resolve(id + ".md"); //$NON-NLS-1$
            if (Files.exists(file))
            {
                return file;
            }
            Path lower = folderPath.resolve(id.toLowerCase() + ".md"); //$NON-NLS-1$
            return Files.exists(lower) ? lower : null;
        }
        catch (RuntimeException e)
        {
            // A malformed preference value must not break the lookup: fall through to the
            // shipped descriptions, which is exactly what an unset folder does. InvalidPathException
            // (what Paths.get throws on a bad path) is a RuntimeException, so it is covered here.
            Log.warning("checks-folder override unusable: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * The configured override folder, trimmed, or {@code null} when unset or blank.
     * <p>
     * A missing Activator or store (headless, or a plain-classpath unit test) reads as "no
     * override" rather than throwing, so the shipped descriptions still answer there.
     * </p>
     *
     * @return the folder path, or {@code null}
     */
    private static String overrideFolder()
    {
        Activator activator = Activator.getDefault();
        IPreferenceStore store = activator != null ? activator.getPreferenceStore() : null;
        if (store == null)
        {
            return null;
        }
        String folder = store.getString(PreferenceConstants.PREF_CHECKS_FOLDER);
        if (folder == null)
        {
            return null;
        }
        folder = folder.trim();
        return folder.isEmpty() ? null : folder;
    }

    /**
     * The URL of the shipped {@code checks/<id>.md} resource, or {@code null} when the plugin
     * ships no description for that id. Cached, since the shipped set is fixed at runtime.
     *
     * @param id an already-sanitized check id
     * @return the resource URL, or {@code null}
     */
    private static URL shippedUrl(String id)
    {
        // Presence is what the cache holds; the URL is re-resolved on the rare read path so a
        // stale URL can never outlive the bundle it came from.
        Boolean present = SHIPPED.computeIfAbsent(id, key -> resolveShipped(key) != null);
        return Boolean.TRUE.equals(present) ? resolveShipped(id) : null;
    }

    /**
     * Resolves {@code checks/<id>.md} against the bundle, then the class loader.
     *
     * @param id an already-sanitized check id
     * @return the resource URL, or {@code null} when absent
     */
    private static URL resolveShipped(String id)
    {
        URL url = resolveShippedExact(id);
        if (url != null)
        {
            return url;
        }
        // Same lower-case fallback the override folder gets, so a caller that upper-cases an id
        // is answered identically from either source.
        String lower = id.toLowerCase();
        return lower.equals(id) ? null : resolveShippedExact(lower);
    }

    /**
     * Resolves one exact {@code checks/<name>.md} resource.
     *
     * @param name the file base name (already sanitized)
     * @return the resource URL, or {@code null} when absent
     */
    private static URL resolveShippedExact(String name)
    {
        String path = CHECKS_DIR + name + ".md"; //$NON-NLS-1$
        try
        {
            Bundle bundle = FrameworkUtil.getBundle(CheckDescriptionLoader.class);
            URL url = bundle != null ? bundle.getEntry(path) : null;
            if (url != null)
            {
                return url;
            }
            // Non-OSGi fallback (a plain-classpath unit test): checks/ is on the bundle classpath.
            return CheckDescriptionLoader.class.getResource("/" + path); //$NON-NLS-1$
        }
        catch (RuntimeException e)
        {
            Log.warning("check resource lookup failed for '" + name + "': " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return null;
        }
    }
}
