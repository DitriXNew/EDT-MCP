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
 * ({@code **}{@code /} is zero or more leading folders). A mask without {@code /} is matched
 * against every single name in the path ({@code *Name*} selects the folder {@code Name} and so
 * every file under it); a mask with {@code /} is matched from the start of the path. A mask that
 * selects a folder selects everything under it, so {@code CommonModules/*} keeps every common
 * module. This is the {@code .gitignore} reading of a pattern.</li>
 * </ul>
 * Switching on {@code *} and {@code ?} never changes an existing result: neither character can
 * occur in a 1C object name or in a Windows file name, so a substring containing one never
 * matched a path. For the same reason a backslash in the mask is read as the folder separator:
 * the paths are {@code /}-separated, and a mask spelled with {@code \} could never match.
 */
public final class PathMask
{
    /** The mask that selects every path. */
    private static final PathMask ALL = new PathMask("", null, null); //$NON-NLS-1$

    private final String text;
    private final String substring;
    private final Pattern glob;

    private PathMask(String text, String substring, Pattern glob)
    {
        this.text = text;
        this.substring = substring;
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
            return new PathMask(mask, normalized.toLowerCase(Locale.ROOT), null);
        }
        return new PathMask(mask, null, compileGlob(normalized));
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
            return path.toLowerCase(Locale.ROOT).contains(substring);
        }
        return true;
    }

    /**
     * Translates a glob into an anchored, case-insensitive regex: the leading part ({@code (.../)?}
     * for a mask without {@code /}), the body, and an optional {@code /...} tail so a folder match
     * selects what is under it.
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

        StringBuilder regex = new StringBuilder();
        if (body.indexOf('/') < 0)
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
        regex.append("(?:/.*)?"); //$NON-NLS-1$
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
