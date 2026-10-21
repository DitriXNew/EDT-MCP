/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ditrix.edt.mcp.server.protocol.JsonUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/**
 * The parsed {@code disableIndices} argument of {@code rename_metadata_object}: the change-point
 * indices that parsed as whole numbers within int, and HOW MANY entries did not. The transport may
 * render a JSON list as {@code [2.0,3.0]}; it is read by the same rules as comma-separated tokens.
 * <p>
 * The count exists because the refusal has to say that the caller supplied something which can never
 * address a preview row (#401). Parsing used to happen inline in the tool adapter and threw
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
 * An entry that can NEVER address a change point is refused before anything runs, through
 * {@link #validationError()} - #401's remaining half. The count above still exists because it is what
 * the refusal counts, and because the parse must survive to be asked: refusing at the parse itself
 * would throw away the very fact the message needs.
 * <p>
 * Refused, and not merely reported, because the cascade is destructive and the answer costs nothing to
 * give early: an entry that is not a whole number, or a negative one, cannot become an index however the
 * tree comes out, so accepting it would run a configuration-wide rename on a request the caller
 * demonstrably did not mean. A too-LARGE index is a different animal and stays accepted: the preview may
 * legitimately have fewer points than the caller remembers, and that is reported as an unknown index.
 */
public final class DisableRequest
{
    private static final DisableRequest EMPTY = new DisableRequest(Collections.emptySet(), 0);

    /** The lowest index a preview can print; anything below it addresses nothing, ever. */
    private static final int FIRST_INDEX = 0;

    private final Set<Integer> indices;
    private final int unparsedCount;

    private DisableRequest(Set<Integer> indices, int unparsedCount)
    {
        this.indices = indices;
        this.unparsedCount = unparsedCount;
    }

    /**
     * Reads the comma-separated argument or a transport-rendered JSON array into indices and a count
     * of entries that are not whole numbers within int. Never refuses: malformed entries are counted.
     * A malformed or non-JSON array falls back to comma splitting; empty tokens remain separator noise.
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
        String stripped = raw.strip();
        boolean arrayLike = stripped.startsWith("[") || stripped.endsWith("]"); //$NON-NLS-1$ //$NON-NLS-2$
        if (stripped.startsWith("[") && stripped.endsWith("]")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            try (JsonReader reader = new JsonReader(new StringReader(stripped)))
            {
                reader.setLenient(false);
                JsonElement parsed = new Gson().getAdapter(JsonElement.class).read(reader);
                if (parsed.isJsonArray() && reader.peek() == JsonToken.END_DOCUMENT)
                {
                    for (JsonElement entry : parsed.getAsJsonArray())
                    {
                        unparsed += entry.isJsonPrimitive() ? parseToken(entry.getAsString(), indices, true) : 1;
                    }
                    return new DisableRequest(Collections.unmodifiableSet(indices), unparsed);
                }
            }
            catch (IOException | JsonParseException e)
            {
                // Keep malformed array text on the original comma-separated path.
            }
        }
        for (String part : raw.split(",")) //$NON-NLS-1$
        {
            // Malformed array text retains the original strict comma-token grammar.
            unparsed += parseToken(part, indices, !arrayLike);
        }
        return new DisableRequest(Collections.unmodifiableSet(indices), unparsed);
    }

    /** Adds a whole-number token, returning only the count of an unreadable entry. */
    private static int parseToken(String part, Set<Integer> indices, boolean allowIntegralRendering)
    {
        // strip(), not trim(): a non-whitespace control is an entry, not separator noise. Tab and
        // newline are whitespace, so "1,\t,2" and "1,,2" both ignore the middle token.
        String token = part.strip();
        if (token.isEmpty())
        {
            return 0;
        }
        // The shared number parser trims text. Do not let that erase a control beside digits.
        if (!token.equals(token.trim()))
        {
            return 1;
        }
        Integer value;
        try
        {
            value = allowIntegralRendering ? JsonUtils.parseIntegralInt(token) : Integer.valueOf(token);
        }
        catch (NumberFormatException e)
        {
            return 1;
        }
        if (value == null)
        {
            return 1;
        }
        indices.add(value);
        return 0;
    }

    /** The change-point indices to skip. Immutable; iteration order is the order they were given. */
    public Set<Integer> indices()
    {
        return indices;
    }

    /**
     * How many entries are not whole numbers within int, including non-primitive array elements.
     * A negative value such as {@code -1} does parse and is rejected separately by
     * {@link #validationError()}, while {@code 2147483648} is counted because it is outside int.
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

    /**
     * Reports entries that cannot address a change point under ANY tree, so the caller is refused
     * before a configuration-wide cascade runs on a request they did not mean.
     * <p>
     * Two kinds qualify, and only two. An entry that is not a whole number within int cannot be an
     * index - a word, a fraction, or a number outside int. A NEGATIVE one parses, but preview indices
     * start at zero and count up, so no tree can ever produce it. A merely too-large index is
     * deliberately NOT here: the preview it came from may honestly have had more points than this one
     * does, which is a stale reference and is reported as an unknown index, not a malformed request.
     * <p>
     * The message names counts and numbers only. It never echoes the entry text - see the class
     * javadoc for the nine defects that cost.
     *
     * @return the actionable refusal, or {@code null} when every entry can address a change point
     */
    public String validationError()
    {
        List<String> problems = new ArrayList<>();
        if (unparsedCount > 0)
        {
            problems.add(unparsedCount + (unparsedCount == 1 ? " entry" : " entries") //$NON-NLS-1$ //$NON-NLS-2$
                + " could not be read as a change-point index"); //$NON-NLS-1$
        }
        List<Integer> negative = new ArrayList<>();
        for (Integer index : indices)
        {
            if (index.intValue() < FIRST_INDEX)
            {
                negative.add(index);
            }
        }
        if (!negative.isEmpty())
        {
            Collections.sort(negative);
            StringBuilder listed = new StringBuilder();
            for (Integer index : negative)
            {
                listed.append(listed.length() == 0 ? "" : ", ").append(index); //$NON-NLS-1$ //$NON-NLS-2$
            }
            problems.add((negative.size() == 1 ? "index " : "indices ") + listed //$NON-NLS-1$ //$NON-NLS-2$
                + " below the first preview index (" + FIRST_INDEX + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (problems.isEmpty())
        {
            return null;
        }
        return "disableIndices takes the '#' indices printed by the preview, as whole numbers, " //$NON-NLS-1$
            + "e.g. '2,3,5', but got " + String.join("; ", problems) //$NON-NLS-1$ //$NON-NLS-2$
            + ". Nothing was renamed. Call rename_metadata_object without confirm to get the " //$NON-NLS-1$
            + "current indices, then retry with those."; //$NON-NLS-1$
    }
}
