/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The parsed {@code disableIndices} argument of {@code rename_metadata_object}: the change-point
 * indices that parsed as numbers, and HOW MANY entries did not.
 * <p>
 * The count exists because the report has to be able to say that the caller asked for something which
 * produced no skip (#394 / #401). Parsing used to happen inline in the tool adapter and threw
 * non-numeric entries away on the spot, so by the time anything could report on them they no longer
 * existed: a call with {@code disableIndices: "abc"} returned a report byte for byte identical to a
 * call that passed no {@code disableIndices} at all.
 * <p>
 * A COUNT, deliberately, and not the entries themselves. Echoing the caller's text back was tried and
 * abandoned: it took nine defects over seven review rounds - ISO controls, non-characters, surrogates,
 * the backtick, the FORMAT category, line and paragraph separators, invisible grapheme joiners - and
 * the last round showed the cure doing harm, because escaping FORMAT characters broke ZWJ emoji into
 * pieces and made a literal backslash-u-0007 typed by the caller indistinguishable from a real control
 * character. Every round closed one class and revealed the next, which is the signature of a mechanism
 * that cannot be finished: arbitrary bytes cannot be shown inside a structured document without
 * inheriting every ambiguity of that document.
 * <p>
 * The count keeps what #401 was actually for. The point was that the caller LEARN of the typo, not that
 * they read its bytes back - and "2 entries could not be read as change-point indices" says that
 * completely, with no attack surface and no escaping to get wrong. Whoever wants the content back
 * should read the failure modes above first; they all return with it.
 * <p>
 * A non-numeric entry is still ACCEPTED rather than refused: it is accepted today, so refusing it would
 * break a caller that already sends one, and a false refusal costs more than a tolerated mistake.
 */
public final class DisableRequest
{
    private static final DisableRequest EMPTY = new DisableRequest(Collections.emptySet(), 0);

    private final Set<Integer> indices;
    private final int unparsedCount;

    private DisableRequest(Set<Integer> indices, int unparsedCount)
    {
        this.indices = indices;
        this.unparsedCount = unparsedCount;
    }

    /**
     * Splits the raw comma-separated argument into indices and a count of what was not an index. Never
     * throws and never refuses: an entry that is not an integer is counted, not rejected.
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
        int unparsed = 0;
        for (String part : raw.split(",")) //$NON-NLS-1$
        {
            // strip(), not trim(): trim() removes every character <= U+0020, so an entry made only of
            // a control character would come back empty and be dropped as separator noise instead of
            // being counted. The boundary that remains is deliberate: an entry that is empty or ONLY
            // whitespace is formatting - and Java counts tab and newline as whitespace - so "1,\t,2"
            // reports nothing about its middle entry. Nobody means a tab as a change-point index; it is
            // the same punctuation as the empty entry in "1,,2".
            String token = part.strip();
            if (token.isEmpty())
            {
                continue;
            }
            try
            {
                indices.add(Integer.valueOf(Integer.parseInt(token)));
            }
            catch (NumberFormatException e)
            {
                unparsed++;
            }
        }
        return new DisableRequest(Collections.unmodifiableSet(indices), unparsed);
    }

    /** The change-point indices to skip. Immutable; iteration order is the order they were given. */
    public Set<Integer> indices()
    {
        return indices;
    }

    /**
     * How many entries never became indices at all - anything {@code Integer.parseInt} refused, which
     * is not quite "not a whole number": {@code -1} parses (and shows up as an unknown index, since no
     * change point carries it) while {@code 2147483648} does not, being outside {@code int}.
     */
    public int unparsedCount()
    {
        return unparsedCount;
    }

    /** {@code true} when the caller asked for nothing at all - no indices and no stray entries. */
    public boolean isEmpty()
    {
        return indices.isEmpty() && unparsedCount == 0;
    }
}
