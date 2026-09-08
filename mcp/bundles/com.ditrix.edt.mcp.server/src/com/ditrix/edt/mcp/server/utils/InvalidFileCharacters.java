/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the characters the 1C standard forbids in a source file with their plain-ASCII
 * equivalents.
 * <p>
 * This is the {@code InvalidCharacterInFile} rule (bsl-language-server): six typographic
 * look-alikes plus one invisible character that a text editor, a word processor or a language
 * model happily produces and that then sit in a {@code .bsl} file as a diagnostic nobody can see
 * - an em dash reads exactly like a minus, and a no-break space exactly like a space. Normalizing
 * them where the text ENTERS the module is the same treatment {@link MdNameNormalizer} gives the
 * letter "yo" in a metadata name, and for the same reason: the file is stored compliant instead
 * of the caller being handed a marker to clean up afterwards.
 * </p>
 * <p>
 * Every code point is written as a Unicode escape, never as a literal: the whole point here is
 * characters that are visually indistinguishable from ASCII, so a literal would be unreviewable
 * in a diff and could not survive a non-UTF-8 build byte-identically.
 * </p>
 */
public final class InvalidFileCharacters
{
    /** EN DASH (U+2013) - the "middle" dash. */
    private static final char EN_DASH = '\u2013';

    /** FIGURE DASH (U+2012) - the "digit" dash. */
    private static final char FIGURE_DASH = '\u2012';

    /** EM DASH (U+2014) - the "long" dash. */
    private static final char EM_DASH = '\u2014';

    /** HORIZONTAL BAR (U+2015) - the "horizontal line". */
    private static final char HORIZONTAL_BAR = '\u2015';

    /** MINUS SIGN (U+2212) - the typographic minus, not the ASCII one. */
    private static final char MINUS_SIGN = '\u2212';

    /** SOFT HYPHEN (U+00AD) - an invisible line-break hint. */
    private static final char SOFT_HYPHEN = '\u00AD';

    /** NO-BREAK SPACE (U+00A0) - looks exactly like a space, is not one. */
    private static final char NO_BREAK_SPACE = '\u00A0';

    /** ASCII HYPHEN-MINUS (U+002D), what every dash-shaped character above becomes. */
    private static final char HYPHEN_MINUS = '\u002D';

    /** ASCII SPACE (U+0020). */
    private static final char SPACE = '\u0020';

    /**
     * Removal marker. A soft hyphen carries no width and no meaning in code, so replacing it with
     * anything printable would ALTER the text; it is dropped instead.
     */
    private static final char REMOVE = '\0';

    /** Each forbidden character mapped to its replacement, or {@link #REMOVE} to drop it. */
    private static final Map<Character, Character> REPLACEMENTS;

    /** Each forbidden character mapped to the name a report shows for it. */
    private static final Map<Character, String> NAMES;

    static
    {
        Map<Character, Character> replacements = new LinkedHashMap<>();
        replacements.put(EN_DASH, HYPHEN_MINUS);
        replacements.put(FIGURE_DASH, HYPHEN_MINUS);
        replacements.put(EM_DASH, HYPHEN_MINUS);
        replacements.put(HORIZONTAL_BAR, HYPHEN_MINUS);
        replacements.put(MINUS_SIGN, HYPHEN_MINUS);
        replacements.put(SOFT_HYPHEN, REMOVE);
        replacements.put(NO_BREAK_SPACE, SPACE);
        REPLACEMENTS = Collections.unmodifiableMap(replacements);

        Map<Character, String> names = new LinkedHashMap<>();
        names.put(EN_DASH, "EN DASH (U+2013)"); //$NON-NLS-1$
        names.put(FIGURE_DASH, "FIGURE DASH (U+2012)"); //$NON-NLS-1$
        names.put(EM_DASH, "EM DASH (U+2014)"); //$NON-NLS-1$
        names.put(HORIZONTAL_BAR, "HORIZONTAL BAR (U+2015)"); //$NON-NLS-1$
        names.put(MINUS_SIGN, "MINUS SIGN (U+2212)"); //$NON-NLS-1$
        names.put(SOFT_HYPHEN, "SOFT HYPHEN (U+00AD)"); //$NON-NLS-1$
        names.put(NO_BREAK_SPACE, "NO-BREAK SPACE (U+00A0)"); //$NON-NLS-1$
        NAMES = Collections.unmodifiableMap(names);
    }

    private InvalidFileCharacters()
    {
        // Utility class
    }

    /**
     * Whether the text carries any character the rule forbids.
     *
     * @param text the text to inspect (may be {@code null})
     * @return {@code true} when at least one forbidden character is present
     */
    public static boolean contains(String text)
    {
        if (text == null)
        {
            return false;
        }
        for (int i = 0; i < text.length(); i++)
        {
            if (REPLACEMENTS.containsKey(text.charAt(i)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces every forbidden character with its ASCII equivalent (dropping the soft hyphen).
     * <p>
     * When there is nothing to replace the result carries the input instance unchanged and
     * reports {@link Result#changed()} as {@code false}, so a caller pays nothing on the common
     * path and reports nothing. A {@code null} input yields a {@code null} {@link Result#text()}.
     * </p>
     *
     * @param text the text to normalize (may be {@code null})
     * @return what was replaced and the text after replacement, never {@code null}
     */
    public static Result normalize(String text)
    {
        if (!contains(text))
        {
            return new Result(text, Collections.emptyMap());
        }
        StringBuilder out = new StringBuilder(text.length());
        Map<Character, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            Character replacement = REPLACEMENTS.get(c);
            if (replacement == null)
            {
                out.append(c);
                continue;
            }
            counts.merge(c, 1, Integer::sum);
            if (replacement.charValue() != REMOVE)
            {
                out.append(replacement.charValue());
            }
        }
        return new Result(out.toString(), counts);
    }

    /**
     * The result of NOT normalizing: the text as supplied, reported as unchanged. Lets a caller
     * with an opt-out flag keep one result type over both branches instead of a nullable one.
     *
     * @param text the text left untouched (may be {@code null})
     * @return a result carrying {@code text} and no replacements
     */
    public static Result unchanged(String text)
    {
        return new Result(text, Collections.emptyMap());
    }

    /**
     * What {@link #normalize(String)} did: the resulting text plus, per character, how many
     * occurrences were replaced.
     */
    public static final class Result
    {
        private final String text;
        private final Map<Character, Integer> counts;

        Result(String text, Map<Character, Integer> counts)
        {
            this.text = text;
            this.counts = counts;
        }

        /**
         * @return the normalized text (the original instance when nothing changed)
         */
        public String text()
        {
            return text;
        }

        /**
         * @return {@code true} when at least one character was replaced
         */
        public boolean changed()
        {
            return !counts.isEmpty();
        }

        /**
         * A human-readable summary of what was replaced, e.g.
         * {@code "EM DASH (U+2014) x2, NO-BREAK SPACE (U+00A0) x1"}, or {@code null} when nothing
         * was. Ordered by declaration, not by where the characters happened to occur, so the same
         * multiset of characters always reports the same string.
         *
         * @return the summary, or {@code null} when nothing changed
         */
        public String summary()
        {
            if (counts.isEmpty())
            {
                return null;
            }
            List<String> parts = new ArrayList<>();
            // Iterating the DECLARATION order (not the encounter order) keeps the summary stable
            // for a given multiset of characters, whatever order they appear in the text.
            for (Map.Entry<Character, String> entry : NAMES.entrySet())
            {
                Integer n = counts.get(entry.getKey());
                if (n != null)
                {
                    parts.add(entry.getValue() + " x" + n); //$NON-NLS-1$
                }
            }
            return String.join(", ", parts); //$NON-NLS-1$
        }
    }
}
