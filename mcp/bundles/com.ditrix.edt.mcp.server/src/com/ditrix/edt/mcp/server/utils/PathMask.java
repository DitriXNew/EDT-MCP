/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The {@code fileMask} filter of the full-text search tools ({@code search_in_code},
 * {@code search_in_dcs}): decides whether a {@code /}-separated path (the display path of
 * {@link ResourceTextSearch}, e.g. {@code CommonModules/Name/Module.bsl}) is selected.
 * Matching is case-insensitive in both modes.
 * <ul>
 * <li><b>Substring mode</b> - a mask without {@code *} and {@code ?}: the path must contain it,
 * exactly as before globs were accepted.</li>
 * <li><b>Glob mode</b> - a mask with {@code *} or {@code ?}. {@code *} is any run of characters
 * within one folder or file name, {@code ?} is one such character, {@code **} crosses folders
 * ({@code **}{@code /} is zero or more leading folders). A mask with a {@code /} at its start or
 * in its middle is matched from the start of the path; any other mask is matched against every
 * single name in the path ({@code *Name*} selects the folder {@code Name} and so every file under
 * it). A trailing {@code /} selects folders only. A mask that selects a folder selects everything
 * under it, so {@code CommonModules/*} keeps every common module. This is the {@code .gitignore}
 * reading of a pattern.</li>
 * </ul>
 * Switching on {@code *} and {@code ?} never changes an existing result: neither character can
 * occur in a 1C object name or in a Windows file name, so a substring containing one never
 * matched a path. A backslash in the mask is read as the folder separator, since the paths are
 * {@code /}-separated; a substring mask also keeps matching its raw text, so a path whose name
 * holds a literal {@code \} (legal on Linux) is selected as before.
 */
public final class PathMask
{
    /** The mask that selects every path. */
    private static final PathMask ALL = new PathMask("", null, null, null); //$NON-NLS-1$

    private final String text;
    private final String substring;
    /** The raw lower-cased substring when it differs from {@link #substring} (it held a backslash). */
    private final String rawSubstring;
    private final Pattern glob;

    private PathMask(String text, String substring, String rawSubstring, Pattern glob)
    {
        this.text = text;
        this.substring = substring;
        this.rawSubstring = rawSubstring;
        this.glob = glob;
    }

    /**
     * Parses a mask.
     *
     * @param mask the mask as the caller sent it; {@code null} or empty selects every path
     * @return the mask, never {@code null}
     */
    public static PathMask of(String mask)
    {
        if (mask == null || mask.isEmpty())
        {
            return ALL;
        }
        String normalized = mask.replace('\\', '/');
        if (normalized.indexOf('*') < 0 && normalized.indexOf('?') < 0)
        {
            String lower = normalized.toLowerCase(Locale.ROOT);
            String raw = mask.toLowerCase(Locale.ROOT);
            return new PathMask(mask, lower, raw.equals(lower) ? null : raw, null);
        }
        return new PathMask(mask, null, null, compileGlob(normalized));
    }

    /** @return whether this mask selects every path (no mask was given) */
    public boolean isEmpty()
    {
        return substring == null && glob == null;
    }

    /** @return whether the mask is read as a glob ({@code *} or {@code ?} in it) */
    public boolean isGlob()
    {
        return glob != null;
    }

    /** @return the mask as the caller sent it ({@code ""} when none) */
    public String text()
    {
        return text;
    }

    /**
     * @param path a {@code /}-separated path
     * @return whether the mask selects it
     */
    public boolean matches(String path)
    {
        if (glob != null)
        {
            return glob.matcher(path).matches();
        }
        if (substring != null)
        {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            return lowerPath.contains(substring) || rawSubstring != null && lowerPath.contains(rawSubstring);
        }
        return true;
    }

    /**
     * Translates a glob into an anchored, case-insensitive regex: the leading part ({@code (.../)?}
     * unless a {@code /} at the start or in the middle anchors the mask), the body, and a
     * {@code /...} tail so a folder match selects what is under it - optional, or required when a
     * trailing {@code /} limits the mask to folders. Whether the mask is anchored is decided
     * before its edge slashes are trimmed, as {@code .gitignore} does.
     */
    private static Pattern compileGlob(String mask)
    {
        int start = 0;
        int end = mask.length();
        while (start < end && mask.charAt(start) == '/')
        {
            start++;
        }
        while (end > start && mask.charAt(end - 1) == '/')
        {
            end--;
        }
        String body = mask.substring(start, end);
        boolean anchored = start > 0 || body.indexOf('/') >= 0;
        boolean foldersOnly = end < mask.length();

        StringBuilder regex = new StringBuilder();
        if (!anchored)
        {
            regex.append("(?:.*/)?"); //$NON-NLS-1$
        }
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < body.length())
        {
            char c = body.charAt(i);
            if (c != '*' && c != '?')
            {
                literal.append(c);
                i++;
                continue;
            }
            flushLiteral(regex, literal);
            if (c == '?')
            {
                regex.append("[^/]"); //$NON-NLS-1$
                i++;
                continue;
            }
            int stars = i;
            while (i < body.length() && body.charAt(i) == '*')
            {
                i++;
            }
            if (i - stars == 1)
            {
                regex.append("[^/]*"); //$NON-NLS-1$
            }
            else if (i < body.length() && body.charAt(i) == '/' && (stars == 0 || body.charAt(stars - 1) == '/'))
            {
                regex.append("(?:.*/)?"); //$NON-NLS-1$
                i++;
            }
            else
            {
                regex.append(".*"); //$NON-NLS-1$
            }
        }
        flushLiteral(regex, literal);
        regex.append(foldersOnly ? "/.*" : "(?:/.*)?"); //$NON-NLS-1$ //$NON-NLS-2$
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static void flushLiteral(StringBuilder regex, StringBuilder literal)
    {
        if (literal.length() > 0)
        {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }
}
