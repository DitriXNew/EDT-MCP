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
import java.util.Locale;
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
     * <p>
     * The shipped resource is consulted FIRST, and that ordering is the whole design. When the
     * plugin ships a description the answer is yes whatever the override folder holds, because
     * {@link #load(String)} falls back to the shipped copy - and it is answered from a cached
     * map, touching no file. Only for a check with no shipped description does the override
     * decide, and there the file is actually READ, because "a regular file exists" is not the
     * same as "a body can be produced": a file that is not valid UTF-8 passes every existence
     * check and still cannot be decoded. Promising a body that {@code get_check_description}
     * then cannot return is the one failure this method must not have, and it is worth one read
     * of one small file in the rare override-only case to avoid it.
     * </p>
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
        if (shippedUrl(id) != null)
        {
            return true;
        }
        Path override = overrideFile(id);
        return override != null && readOverride(override, id) != null;
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
        Path override = overrideFile(id);
        if (override != null)
        {
            String overridden = readOverride(override, id);
            if (overridden != null)
            {
                return overridden;
            }
            // Not returned: an override that cannot be READ must not hide the description the
            // plugin ships. Failing here instead would turn a stray unreadable file into a hole
            // in the documentation for that check. Where there is no shipped copy to fall back
            // to, has() has already read this same file and answered false, so the two agree.
        }
        try
        {
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
            Path folderPath = folderPath(folder);
            if (folderPath == null)
            {
                return null;
            }
            // isRegularFile, not exists: a DIRECTORY named "<id>.md" in the override folder would
            // satisfy exists(), so has() would promise a description that readString then cannot
            // produce - get_project_errors would flag hasDocumentation and get_check_description
            // would answer "no description for" the same id. The two must never disagree.
            Path file = folderPath.resolve(id + ".md"); //$NON-NLS-1$
            if (Files.isRegularFile(file))
            {
                return file;
            }
            Path lower = folderPath.resolve(lowerCase(id) + ".md"); //$NON-NLS-1$
            return Files.isRegularFile(lower) ? lower : null;
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
     * The directory the preference names, or {@code null} when it names none.
     * <p>
     * The value is used EXACTLY as stored first, because a directory may legitimately have a
     * leading or trailing space on a POSIX filesystem and a folder picker returns the real name.
     * Only if that is not a directory is the trimmed form tried, which is what rescues the far
     * commoner case of a path pasted with a stray space. Both directions matter here: preferring
     * either form outright breaks the other one.
     * </p>
     *
     * @param folder the non-blank preference value
     * @return the directory, or {@code null} when neither form is one
     */
    private static Path folderPath(String folder)
    {
        Path asStored = directoryAt(folder);
        if (asStored != null)
        {
            return asStored;
        }
        String trimmed = folder.trim();
        return trimmed.equals(folder) ? null : directoryAt(trimmed);
    }

    /**
     * The directory at {@code value}, or {@code null} when there is none there.
     * <p>
     * Each candidate is asked separately BECAUSE one of them can be unaskable: Windows rejects a
     * padded path outright ({@code Paths.get} throws {@link java.nio.file.InvalidPathException}),
     * which is precisely the pasted-with-a-space value whose trimmed form is a perfectly good
     * directory. Letting that throw escape would have the stored form veto the trimmed one and
     * lose the override.
     * </p>
     *
     * @param value a candidate path
     * @return the directory, or {@code null} when the value names none or is not a path at all
     */
    private static Path directoryAt(String value)
    {
        try
        {
            Path path = Paths.get(value);
            return Files.isDirectory(path) ? path : null;
        }
        catch (RuntimeException notAPath)
        {
            return null;
        }
    }

    /**
     * The configured override folder as stored, or {@code null} when unset or blank.
     * <p>
     * Blankness is decided on the trimmed value, but the value itself is returned UNTRIMMED -
     * {@link #folderPath} needs the original to find a directory whose name really does end in a
     * space.
     * </p>
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
        if (folder == null || folder.trim().isEmpty())
        {
            return null;
        }
        return folder;
    }

    /**
     * Reads an override file, or {@code null} when it cannot be read.
     * <p>
     * Its own failure, kept separate from the shipped read so that a bad override degrades to
     * the shipped description instead of replacing it with nothing. Unreadable covers more than
     * permissions: {@link Files#readString} reports malformed input, so a file that is not valid
     * UTF-8 lands here too, and a regular file passes every existence check before it.
     * </p>
     *
     * @param file the override file
     * @param id the check id, for the log line
     * @return the body, or {@code null} when it could not be read
     */
    private static String readOverride(Path file, String id)
    {
        try
        {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException e)
        {
            Log.warning("check description override unreadable for '" + id //$NON-NLS-1$ //$NON-NLS-2$
                + "', using the shipped one: " + e.getMessage()); //$NON-NLS-1$
            return null;
        }
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
        String lower = lowerCase(id);
        return lower.equals(id) ? null : resolveShippedExact(lower);
    }

    /**
     * Lower-cases an id the way the FILE NAMES are spelled, not the way the operator's locale
     * spells things.
     * <p>
     * The default-locale {@code toLowerCase()} is a real trap here: under {@code tr_TR} it maps
     * {@code I} to U+0131, so {@code BEGIN-TRANSACTION} would look for {@code beg\u0131n-transaction.md}
     * and the shipped {@code begin-transaction.md} would read as undocumented - on that operator's
     * machine only.
     * </p>
     *
     * @param id the check id
     * @return the id lower-cased under {@link Locale#ROOT}
     */
    private static String lowerCase(String id)
    {
        return id.toLowerCase(Locale.ROOT);
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
