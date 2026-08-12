/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parsed {@code disableIndices} argument of {@code rename_metadata_object}: the change-point
 * indices that parsed as numbers, AND the tokens that did not.
 * <p>
 * The unparsed tokens are carried rather than dropped because the report has to be able to say what
 * the caller asked for that produced no skip (#394 / #401). Parsing used to happen inline in the tool
 * adapter and threw non-numeric entries away on the spot, so by the time anything could report on the
 * request they no longer existed: a call with {@code disableIndices: "abc"} returned a report byte for
 * byte identical to a call that passed no {@code disableIndices} at all. Making the discarded tokens
 * part of the parse RESULT is what stops that from being possible again - there is nowhere to drop
 * them.
 * <p>
 * A non-numeric token is still ACCEPTED rather than refused: it is accepted today, so refusing it
 * would break a caller that already sends one, and a false refusal costs more than a tolerated
 * mistake. It is reported instead.
 */
public final class DisableRequest
{
    /** Longest token echoed back verbatim, in CODE POINTS; a longer one is truncated with a marker. */
    private static final int MAX_TOKEN_LENGTH = 40;

    /** How many unparsed tokens the report names before it just counts the rest. */
    static final int MAX_TOKENS_REPORTED = 20;

    private static final DisableRequest EMPTY =
        new DisableRequest(Collections.emptySet(), Collections.emptyList());

    private final Set<Integer> indices;
    private final List<String> unparsedTokens;

    private DisableRequest(Set<Integer> indices, List<String> unparsedTokens)
    {
        this.indices = indices;
        this.unparsedTokens = unparsedTokens;
    }

    /**
     * Splits the raw comma-separated argument into indices and leftovers. Never throws and never
     * refuses: an entry that is not an integer becomes an unparsed token.
     *
     * @param raw the argument as the caller sent it; {@code null} or empty yields an empty request
     * @return the parsed request, never {@code null}
     */
    public static DisableRequest parse(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return EMPTY;
        }
        Set<Integer> indices = new LinkedHashSet<>();
        List<String> unparsed = new ArrayList<>();
        for (String part : raw.split(",")) //$NON-NLS-1$
        {
            // strip(), not trim(). trim() removes every character <= U+0020, so a token like a
            // lone BEL came back empty and was dropped as separator noise - reintroducing exactly the
            // silence this class exists to end. strip() removes Unicode WHITESPACE, which is a
            // narrower set: a BEL survives to be reported.
            //
            // The boundary, stated because it is a judgment and not an accident: an entry that is
            // empty or ONLY whitespace is still treated as formatting - and Java counts tab, newline
            // and CR as whitespace, so "1,\t,2" reports nothing about its middle entry. Nobody means
            // a tab as a change-point index; it is the same punctuation as the empty entry below. An
            // entry with any non-whitespace character in it is a token, and is reported.
            String token = part.strip();
            if (token.isEmpty())
            {
                // A stray separator ("1,,2" or a trailing comma) is punctuation, not a typo'd index:
                // it names nothing, so reporting it would be noise about the caller's formatting.
                continue;
            }
            try
            {
                indices.add(Integer.valueOf(Integer.parseInt(token)));
            }
            catch (NumberFormatException e)
            {
                unparsed.add(sanitizeToken(token));
            }
        }
        return new DisableRequest(Collections.unmodifiableSet(indices),
            Collections.unmodifiableList(unparsed));
    }

    /** The change-point indices to skip. Immutable; iteration order is the order they were given. */
    public Set<Integer> indices()
    {
        return indices;
    }

    /** The tokens that were not numbers, already rendered safe to echo. Immutable. */
    public List<String> unparsedTokens()
    {
        return unparsedTokens;
    }

    /** {@code true} when the caller asked for nothing at all - no indices and no stray tokens. */
    public boolean isEmpty()
    {
        return indices.isEmpty() && unparsedTokens.isEmpty();
    }

    /**
     * Makes a caller-supplied token safe to put back into the report: the token is echoed into the
     * YAML front matter AND into a Markdown sentence, so anything that cannot be printed as itself is
     * escaped rather than dropped, and an unbounded one would let the request dictate the size of the
     * answer.
     * <p>
     * Walks CODE POINTS rather than {@code char}s: cutting a string at a fixed number of UTF-16 units
     * can split a surrogate pair and put an unpaired surrogate - which is not legal YAML content - into
     * the report.
     */
    private static String sanitizeToken(String token)
    {
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        int i = 0;
        while (i < token.length())
        {
            if (kept == MAX_TOKEN_LENGTH)
            {
                return sb.append("...").toString(); //$NON-NLS-1$
            }
            int codePoint = token.codePointAt(i);
            appendEchoable(sb, codePoint);
            kept++;
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    /**
     * Appends one code point in a form that cannot affect the structure of the documents it lands in:
     * as itself when it is printable, and otherwise as a literal escape sequence - a backslash, the
     * letter u, and hex digits, nothing else.
     * <p>
     * This is where the safety lives, and it is deliberately NOT a rule about which characters are
     * forbidden. That rule was tried and it failed six times in a row: ISO controls, then non-characters,
     * then surrogates, then the backtick, then the FORMAT category, then the line/paragraph separators -
     * and the last two arrived AFTER the rule had been generalised to whole Unicode categories. A
     * denylist is a bet that the set of dangerous characters is closed, and Unicode keeps that set open;
     * every round found the next member. Escaping ends the argument, because there is nothing left to
     * forbid: whatever arrives is rendered as ASCII the containers cannot misread.
     * <p>
     * It also serves the point of the whole feature. A dropped character told the caller nothing about
     * what they had actually sent; an escape naming the exact code point tells them, which is the
     * point of #401.
     */
    private static void appendEchoable(StringBuilder sb, int codePoint)
    {
        if (isPrintableAsItself(codePoint))
        {
            sb.appendCodePoint(codePoint);
        }
        else if (codePoint <= 0xFFFF)
        {
            sb.append(String.format("\\u%04X", Integer.valueOf(codePoint))); //$NON-NLS-1$
        }
        else
        {
            sb.append(String.format("\\U%08X", Integer.valueOf(codePoint))); //$NON-NLS-1$
        }
    }

    /**
     * Whether a code point can stand for itself in the report. Everything legible can - Cyrillic,
     * diacritics, CJK, emoji, punctuation - because a mistyped 1C identifier is exactly what lands here
     * and escaping it would hide the thing the report exists to show. What cannot: anything outside the
     * legible categories (controls, formats, separators, surrogates, unassigned and private-use code
     * points), and the backtick, which would close the Markdown code span the prose wraps the token in.
     * <p>
     * Stated as an ALLOW-list on purpose. Not because a denylist could not be written correctly today,
     * but because it would have to be rewritten every time Unicode grows a new way to be invisible -
     * and the record above shows how that goes. Being wrong here is now merely ugly, not dangerous:
     * a code point wrongly judged unprintable comes out as an escape sequence instead of itself.
     */
    private static boolean isPrintableAsItself(int codePoint)
    {
        if (codePoint == '`')
        {
            return false;
        }
        if (codePoint == ' ')
        {
            return true;
        }
        switch (Character.getType(codePoint))
        {
        case Character.UPPERCASE_LETTER:
        case Character.LOWERCASE_LETTER:
        case Character.TITLECASE_LETTER:
        case Character.MODIFIER_LETTER:
        case Character.OTHER_LETTER:
        case Character.NON_SPACING_MARK:
        case Character.COMBINING_SPACING_MARK:
        case Character.ENCLOSING_MARK:
        case Character.DECIMAL_DIGIT_NUMBER:
        case Character.LETTER_NUMBER:
        case Character.OTHER_NUMBER:
        case Character.CONNECTOR_PUNCTUATION:
        case Character.DASH_PUNCTUATION:
        case Character.START_PUNCTUATION:
        case Character.END_PUNCTUATION:
        case Character.INITIAL_QUOTE_PUNCTUATION:
        case Character.FINAL_QUOTE_PUNCTUATION:
        case Character.OTHER_PUNCTUATION:
        case Character.MATH_SYMBOL:
        case Character.CURRENCY_SYMBOL:
        case Character.MODIFIER_SYMBOL:
        case Character.OTHER_SYMBOL:
            return true;
        default:
            return false;
        }
    }
}
