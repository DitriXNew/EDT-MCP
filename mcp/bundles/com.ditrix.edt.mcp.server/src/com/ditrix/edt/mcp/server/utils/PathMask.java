/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Locale;

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
    private final Glob glob;

    private PathMask(String text, String substring, String rawSubstring, Glob glob)
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
        return new PathMask(mask, null, null, Glob.compile(normalized));
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
            return glob.matches(path);
        }
        if (substring != null)
        {
            String lowerPath = path.toLowerCase(Locale.ROOT);
            return lowerPath.contains(substring) || rawSubstring != null && lowerPath.contains(rawSubstring);
        }
        return true;
    }

    /**
     * A compiled glob, matched by a dynamic programme over (token, path position) - never by a
     * backtracking regex, which a mask of repeated {@code **}{@code /} drives exponential (review of #969).
     * Positions are Unicode code points, so {@code ?} is one character and case folding sees whole
     * characters. Tokens: a literal character (case-folded), {@code ?} (one character but {@code /}),
     * {@code *} (a run without {@code /}), {@code **} inside a name (any run), and {@code **}{@code /} at a
     * folder boundary (zero or more whole folders - empty, or any run that ends with {@code /}); an
     * unanchored mask starts with that last token. After the tokens, a {@code /...} tail selects what is
     * under a matched folder: optional, or required when a trailing {@code /} limits the mask to folders.
     * <p>
     * Cost: a path shorter than the characters the mask requires is rejected before the programme runs,
     * and adjacent star tokens are merged, so the tokens never outnumber a small multiple of the path's length -
     * the work per path is bounded by the square of its own length, whatever the mask's.
     */
    private static final class Glob
    {
        private static final byte LITERAL = 0;
        private static final byte ONE = 1;
        private static final byte NAME_RUN = 2;
        private static final byte ANY_RUN = 3;
        private static final byte FOLDERS = 4;

        private final byte[] kinds;
        private final int[] literals;
        private final int required;
        private final boolean foldersOnly;

        private Glob(byte[] kinds, int[] literals, int required, boolean foldersOnly)
        {
            this.kinds = kinds;
            this.literals = literals;
            this.required = required;
            this.foldersOnly = foldersOnly;
        }

        /**
         * Compiles a {@code /}-separated glob. Whether it is anchored is decided before its edge slashes
         * are trimmed, as {@code .gitignore} does: a {@code /} at its start or in its middle anchors it.
         */
        static Glob compile(String mask)
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
            int[] body = mask.substring(start, end).codePoints().toArray();
            boolean anchored = start > 0 || indexOf(body, '/', 0) >= 0;

            byte[] kinds = new byte[body.length + 1];
            int[] literals = new int[body.length + 1];
            int n = 0;
            int required = 0;
            if (!anchored)
            {
                kinds[n++] = FOLDERS;
            }
            int i = 0;
            while (i < body.length)
            {
                int c = body[i];
                if (c == '?')
                {
                    kinds[n++] = ONE;
                    required++;
                    i++;
                    continue;
                }
                if (c != '*')
                {
                    kinds[n] = LITERAL;
                    literals[n++] = fold(c);
                    required++;
                    i++;
                    continue;
                }
                int stars = i;
                while (i < body.length && body[i] == '*')
                {
                    i++;
                }
                byte kind;
                if (i - stars == 1)
                {
                    kind = NAME_RUN;
                }
                else if (i < body.length && body[i] == '/' && (stars == 0 || body[stars - 1] == '/'))
                {
                    kind = FOLDERS;
                    i++;
                }
                else
                {
                    kind = ANY_RUN;
                }
                // '**/**/' is '**/': repeated folder runs add no meaning, only work.
                if (!(kind == FOLDERS && n > 0 && kinds[n - 1] == FOLDERS))
                {
                    kinds[n++] = kind;
                }
            }
            return new Glob(java.util.Arrays.copyOf(kinds, n), java.util.Arrays.copyOf(literals, n), required,
                end < mask.length());
        }

        boolean matches(String path)
        {
            int[] cps = path.codePoints().toArray();
            int len = cps.length;
            if (required > len)
            {
                return false;
            }
            // next[i]: the tokens after the current one match cps[i..]; it starts as the tail.
            boolean[] next = new boolean[len + 1];
            for (int i = 0; i <= len; i++)
            {
                boolean slash = i < len && cps[i] == '/';
                next[i] = foldersOnly ? slash : i == len || slash;
            }
            boolean[] current = new boolean[len + 1];
            for (int t = kinds.length - 1; t >= 0; t--)
            {
                byte kind = kinds[t];
                boolean endsHere = false;
                for (int i = len; i >= 0; i--)
                {
                    boolean more = i < len;
                    int c = more ? cps[i] : 0;
                    switch (kind)
                    {
                        case LITERAL:
                            current[i] = more && fold(c) == literals[t] && next[i + 1];
                            break;
                        case ONE:
                            current[i] = more && c != '/' && next[i + 1];
                            break;
                        case NAME_RUN:
                            current[i] = next[i] || more && c != '/' && current[i + 1];
                            break;
                        case ANY_RUN:
                            current[i] = next[i] || more && current[i + 1];
                            break;
                        default:
                            // FOLDERS: empty, or a run ending with '/' and the rest matching after it.
                            endsHere = endsHere || more && c == '/' && next[i + 1];
                            current[i] = next[i] || endsHere;
                            break;
                    }
                }
                boolean[] swap = next;
                next = current;
                current = swap;
            }
            return next[0];
        }

        private static int indexOf(int[] cps, int c, int from)
        {
            for (int i = from; i < cps.length; i++)
            {
                if (cps[i] == c)
                {
                    return i;
                }
            }
            return -1;
        }

        /** The case fold of one character, as a case-insensitive Unicode regex compares it. */
        private static int fold(int c)
        {
            return Character.toLowerCase(Character.toUpperCase(c));
        }
    }
}