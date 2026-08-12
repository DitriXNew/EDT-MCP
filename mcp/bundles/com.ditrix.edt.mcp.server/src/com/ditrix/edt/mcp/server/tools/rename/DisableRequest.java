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

    /** The tokens that were not numbers, already made safe to echo. Immutable. */
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
     * YAML front matter AND into a Markdown sentence, and an unbounded one would let the request
     * dictate the size of the answer.
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
            sb.appendCodePoint(isEchoSafe(codePoint) ? codePoint : '?');
            kept++;
            i += Character.charCount(codePoint);
        }
        return sb.toString();
    }

    /**
     * Whether a code point may be echoed back as itself. Everything legible is - Cyrillic included,
     * since a mistyped 1C identifier is a likely thing to find here and mangling it would hide what
     * the caller actually typed. Refused are only the ones that would break the containers this text
     * goes into: control characters and non-characters (illegal in YAML), unpaired surrogates, and the
     * backtick that would close the Markdown code span the prose wraps the token in.
     */
    private static boolean isEchoSafe(int codePoint)
    {
        return !Character.isISOControl(codePoint)
            && Character.getType(codePoint) != Character.SURROGATE
            && (codePoint & 0xFFFE) != 0xFFFE
            && codePoint != '`';
    }
}
