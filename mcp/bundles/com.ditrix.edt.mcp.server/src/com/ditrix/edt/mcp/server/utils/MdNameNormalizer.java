/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/**
 * Normalizes the Russian letter "ё"/"Ё" to "е"/"Е" in user-supplied identifier
 * and presentation text (names, synonyms, comments, presentations, titles).
 * <p>
 * The EDT validator {@code mdo-ru-name-unallowed-letter} (1C standard #std474)
 * raises an issue when a name, synonym or comment contains the letter "ё", so
 * the write tools normalize such text at the input stage. The result is then
 * stored already compliant, instead of leaving the caller to clean it up after
 * a validation pass.
 * <p>
 * Only the two "yo" code points are touched ({@code U+0451} and {@code U+0401});
 * every other character is preserved exactly, so this is safe to apply to any
 * text field. The transformation is a pure, side-effect-free string replacement.
 */
public final class MdNameNormalizer
{
    // The four Cyrillic code points are spelled as Unicode escapes on purpose:
    // raw Cyrillic literals are homoglyph-prone (a Latin 'e' or a mis-encoded
    // source file would silently change the mapping), while the escapes are
    // ASCII-only and survive any source-encoding mishap byte-identically.

    /** Lowercase Russian "yo", CYRILLIC SMALL LETTER IO (U+0451). */
    private static final char YO_LOWER = '\u0451';

    /** Uppercase Russian "yo", CYRILLIC CAPITAL LETTER IO (U+0401). */
    private static final char YO_UPPER = '\u0401';

    /** Lowercase Russian "ye", CYRILLIC SMALL LETTER IE (U+0435). */
    private static final char YE_LOWER = '\u0435';

    /** Uppercase Russian "ye", CYRILLIC CAPITAL LETTER IE (U+0415). */
    private static final char YE_UPPER = '\u0415';

    private MdNameNormalizer()
    {
        // Utility class
    }

    /**
     * Replaces every "ё" with "е" and every "Ё" with "Е" in the given text.
     * <p>
     * Returns {@code null} unchanged for a {@code null} input and the original
     * instance when no replacement is needed, so callers can cheaply detect
     * "nothing changed" by reference identity.
     *
     * @param text the text to normalize (may be {@code null})
     * @return the normalized text, or {@code null} when {@code text} is {@code null}
     */
    public static String normalizeYo(String text)
    {
        if (text == null || text.isEmpty())
        {
            return text;
        }
        if (text.indexOf(YO_LOWER) < 0 && text.indexOf(YO_UPPER) < 0)
        {
            // No "yo" anywhere: return the original instance untouched.
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++)
        {
            if (chars[i] == YO_LOWER)
            {
                chars[i] = YE_LOWER;
            }
            else if (chars[i] == YO_UPPER)
            {
                chars[i] = YE_UPPER;
            }
        }
        return new String(chars);
    }

    /**
     * @param text the text to inspect (may be {@code null})
     * @return {@code true} when {@code text} contains at least one "ё" or "Ё"
     */
    public static boolean containsYo(String text)
    {
        return text != null && (text.indexOf(YO_LOWER) >= 0 || text.indexOf(YO_UPPER) >= 0);
    }

    /**
     * Accumulates the names of the fields whose value was changed by a "ё"→"е"
     * normalization, so a tool can report exactly which inputs it rewrote.
     * <p>
     * Typical use at a tool's argument-parsing step:
     *
     * <pre>
     * MdNameNormalizer.Report report = new MdNameNormalizer.Report(normalizeYo);
     * synonym = report.apply("synonym", synonym);
     * comment = report.apply("comment", comment);
     * // ... later, when building the JSON result:
     * report.addTo(toolResult);
     * </pre>
     */
    public static final class Report
    {
        private final boolean enabled;
        private final List<String> normalizedFields = new ArrayList<>();

        /**
         * @param enabled when {@code false}, {@link #apply(String, String)} leaves
         *            the value untouched and records nothing (the {@code normalizeYo}
         *            parameter was turned off by the caller)
         */
        public Report(boolean enabled)
        {
            this.enabled = enabled;
        }

        /**
         * Normalizes {@code value} when this report is enabled and the value
         * actually contains a "yo"; records {@code fieldName} when a replacement
         * happened.
         *
         * @param fieldName the logical field name to report (e.g. {@code "synonym"})
         * @param value the user-supplied text (may be {@code null})
         * @return the normalized value, or the original when normalization is
         *         disabled or made no change
         */
        public String apply(String fieldName, String value)
        {
            if (!enabled || value == null)
            {
                return value;
            }
            if (!containsYo(value))
            {
                return value;
            }
            normalizedFields.add(fieldName);
            return normalizeYo(value);
        }

        /**
         * @return {@code true} when at least one field was normalized
         */
        public boolean hasChanges()
        {
            return !normalizedFields.isEmpty();
        }

        /**
         * @return an unmodifiable list of the field names that were normalized,
         *         in the order {@link #apply(String, String)} was called
         */
        public List<String> normalizedFields()
        {
            return Collections.unmodifiableList(normalizedFields);
        }

        /**
         * Adds the {@code normalized} report field (the list of rewritten field
         * names) to a tool result - only when the normalization actually rewrote
         * something, so an untouched call carries no noise field.
         *
         * @param result the tool result being built
         */
        public void addTo(ToolResult result)
        {
            if (hasChanges())
            {
                result.put("normalized", normalizedFields()); //$NON-NLS-1$
            }
        }
    }
}
