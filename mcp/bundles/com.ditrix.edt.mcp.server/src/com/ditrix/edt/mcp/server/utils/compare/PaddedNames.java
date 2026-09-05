/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils.compare;

import java.util.Locale;

/**
 * The ONE statement this plug-in makes about a name padded with whitespace, and the one way it
 * names the offending character.
 *
 * <h2>Why it is one statement and not one per caller</h2>
 * Two different doors ask the same question about two different vocabularies: a merge-rule node
 * key ({@link MergeRulesDocument#firstPaddedNameCharacter(String)}, whose names are separated by
 * {@code :}) and a metadata address ({@link ComparisonScopeBuilder#paddedNameCharacter(String)},
 * whose names are separated by {@code .}). What they share is the JUDGEMENT - which characters
 * count, where they are looked for, and which are deliberately out of scope - and that judgement
 * was measured once. Restating it beside each caller is how the two would drift apart, so each
 * caller supplies only its own component boundaries and asks this class the rest.
 *
 * <h2>A padded name is not a name</h2>
 * Both engines key by string equality and neither writes whitespace into a name it produces: a
 * merge-rule node is keyed by a model feature name or by three 1C names, and a comparison scope
 * entry becomes a symlink matched against a node's own. A name with whitespace against one of its
 * ends therefore matches NOTHING, in any comparison - which is not an error anywhere downstream,
 * merely an answer with no rows in it.
 *
 * <h2>Why it walked past every check around it</h2>
 * {@code String.isBlank} is Unicode-aware and {@code String.trim} is not - trim cuts only code
 * points at or below {@code U+0020}. A name of {@code Catalog.Products} followed by {@code U+2003}
 * is therefore not blank (it names something), not trimmed (the character is far above the cut)
 * and not malformed in any other way, so it reached the engine exactly as sent while the caller's
 * own screen showed the name they meant.
 *
 * <h2>What counts as whitespace here, and what deliberately does not</h2>
 * {@code Character.isWhitespace} - the predicate {@code isBlank} itself asks, so a gate and this
 * question cannot drift apart - OR {@code Character.isSpaceChar}, which adds the non-breaking
 * spaces ({@code U+00A0}, {@code U+2007}, {@code U+202F}): whitespace to every reader, and to
 * neither {@code trim} nor {@code strip}. A zero-width or format character ({@code U+200B},
 * {@code U+FEFF}) is neither, and is NOT reported here - it is legal in both vocabularies, it is
 * not whitespace in any Unicode sense, and whether the name it spells exists is a question only a
 * live model answers. That is the boundary, stated so the next unnamed character is a decision
 * rather than an omission.
 *
 * <h2>Asked per COMPONENT, and only about the ends</h2>
 * A compound name is several names, so an inner one has two ends of its own: every component is
 * looked at, not only the ends of the whole string. Whitespace INSIDE a name is left alone - it is
 * not padding, and whether a name may hold a space is a question about names, which only the model
 * can answer.
 *
 * <h2>The skip is narrower than the check, and that asymmetry is load-bearing</h2>
 * A component is skipped when {@code isBlank} says it names nothing, and that predicate is
 * {@code Character.isWhitespace} ALONE - narrower than the union asked one line below. So a
 * component of nothing but {@code U+00A0} is not skipped: it is reported here as padding. That
 * looks like an inconsistency and is the only safe way round. Widening the skip to the union would
 * hand such a component to whichever predicate reports an EMPTY component, and those ask
 * {@code isBlank} too and would answer that it names something - so the name would be accepted,
 * which is exactly the outcome this class exists to prevent. Narrowing the check to
 * {@code isBlank} instead would let every non-breaking space through.
 * <p>
 * <b>The skip is only safe where something DOES report the empty component</b>, and that is not
 * a property of this class - it is a property of the caller. A caller with no such predicate must
 * ask {@link #firstEmptyComponent(String, char)} as well, or it inherits a hole in the shape of
 * the very failure the padding rule removes.
 */
public final class PaddedNames
{
    private PaddedNames()
    {
        // Utility class
    }

    /**
     * Where a name compounded out of components separated by {@code separator} is padded.
     * <p>
     * Every component is asked, at its ends only. The separator itself is never a component
     * boundary character a caller has to strip: the scan works on indices into {@code text}, so
     * the offset it answers is an offset into {@code text} itself and into nothing else. Which
     * string that is - and therefore how a refusal may frame the number - is the caller's to
     * state, not this class's to assume.
     *
     * @param text the whole name (may be {@code null})
     * @param separator the character that separates one component from the next
     * @return the 0-based UTF-16 offset of the first whitespace character that begins or ends a
     *         component, or {@code -1} when no component is padded
     */
    public static int firstPaddedCharacter(String text, char separator)
    {
        if (text == null)
        {
            return -1;
        }
        int from = 0;
        while (from <= text.length())
        {
            int next = text.indexOf(separator, from);
            int to = next < 0 ? text.length() : next;
            int found = firstPaddedCharacter(text, from, to);
            if (found >= 0)
            {
                return found;
            }
            if (next < 0)
            {
                return -1;
            }
            from = next + 1;
        }
        return -1;
    }

    /**
     * Which component of a compound name NAMES NOTHING - it is empty, or holds only whitespace
     * that {@code isBlank} recognises.
     *
     * <h2>Why this is a second question and not a wider first one</h2>
     * {@link #firstPaddedCharacter(String, char)} SKIPS such a component, and that skip is only
     * safe where something else reports it. In a merge-rule key something does -
     * {@code MergeRulesDocument.emptyTopObjectKeySides} names the side to fill in - so the skip
     * there is a division of labour. A caller with no such second predicate has a hole instead:
     * {@code Catalog..Products} and {@code Catalog.Products.} would pass every check and build a
     * name that matches nothing, which is the exact failure the padding rule exists to stop.
     * <p>
     * The two questions stay separate rather than merging into one, because the answers are
     * different and the caller acts on them differently: padding is a whitespace character to
     * remove, an empty component is a name to supply. Merging them would also break the
     * asymmetry {@link #firstPaddedCharacter(String, int, int)} depends on - a component of
     * nothing but {@code U+00A0} is NOT blank, and must keep being reported as padding.
     *
     * @param text the whole name (may be {@code null})
     * @param separator the character that separates one component from the next
     * @return the 1-based ordinal of the first component that names nothing, or {@code -1} when
     *         every component names something
     */
    public static int firstEmptyComponent(String text, char separator)
    {
        if (text == null)
        {
            return -1;
        }
        int ordinal = 0;
        int from = 0;
        while (true)
        {
            ordinal++;
            int next = text.indexOf(separator, from);
            int to = next < 0 ? text.length() : next;
            if (text.substring(from, to).isBlank())
            {
                return ordinal;
            }
            if (next < 0)
            {
                return -1;
            }
            from = next + 1;
        }
    }

    /**
     * Where ONE component of a name is padded.
     *
     * @param text the whole name
     * @param from the first index of the component in it
     * @param to one past the component's last index
     * @return the offset of the whitespace character at either end of that component, or
     *         {@code -1} when the component is unpadded, empty, or names nothing at all
     */
    public static int firstPaddedCharacter(String text, int from, int to)
    {
        if (from >= to || text.substring(from, to).isBlank())
        {
            return -1;
        }
        if (isPaddingCharacter(text.charAt(from)))
        {
            return from;
        }
        if (isPaddingCharacter(text.charAt(to - 1)))
        {
            return to - 1;
        }
        return -1;
    }

    /**
     * @param character one character of a name
     * @return whether it is whitespace in either of Unicode's two senses - the union is wider than
     *         {@code trim} and wider than {@code strip}, which is the point
     */
    public static boolean isPaddingCharacter(char character)
    {
        return Character.isWhitespace(character) || Character.isSpaceChar(character);
    }

    /**
     * Names a character by its code point, so a refusal can point at it without carrying it.
     * <p>
     * Every refusal about a malformed name quotes the name back, and for this class of defect the
     * echo carries the offending character instead of naming it: what is wrong is a whitespace
     * character, and the echo does not say which of them it is. So the refusal states the code
     * point and its offset. The question is asked at every INNER boundary of a compound name,
     * which no caller's {@code trim} reaches, and at every boundary of a merge-rule key, which is
     * not trimmed at all - so an ordinary {@code U+0020} against one of those ends is reported
     * here exactly as {@code U+2003}, {@code U+00A0} and their kin are.
     * <p>
     * The unit is the UTF-16 offset rather than a code-point ordinal, so a name holding a
     * character above {@code U+FFFF} before the padding counts it as two where a person counting
     * on screen counts one. That is deliberate: the sibling refusal about an unwritable key points
     * at a LONE SURROGATE, which is not a code point at all and cannot be numbered any other way,
     * and one unit for both is worth more than a closer fit for one of them.
     *
     * @param character the character
     * @return {@code U+XXXX}
     */
    public static String codePointName(char character)
    {
        return String.format(Locale.ROOT, "U+%04X", (int)character); //$NON-NLS-1$
    }
}
