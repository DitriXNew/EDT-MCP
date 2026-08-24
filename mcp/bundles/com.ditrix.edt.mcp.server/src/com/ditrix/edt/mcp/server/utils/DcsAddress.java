/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Immutable address of a DCS root or one of its nodes. The part before the first {@code #} is the
 * metadata FQN; the optional fragment is an RFC-6901-style pointer whose segments are kept decoded.
 * This class is deliberately independent of EDT, EMF and the workspace.
 */
public final class DcsAddress
{
    private static final Set<String> NATURAL_KEY_COLLECTIONS = naturalKeyCollections();

    private static final Set<String> STRICT_INDEX_CONTEXTS = strictIndexContexts();

    private final String rootFqn;
    private final List<String> segments;
    private final List<SegmentKind> segmentKinds;
    private final String canonical;

    private DcsAddress(String rootFqn, List<String> segments, List<SegmentKind> segmentKinds)
    {
        this.rootFqn = rootFqn;
        this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        this.segmentKinds = Collections.unmodifiableList(new ArrayList<>(segmentKinds));
        this.canonical = render(rootFqn, segments);
    }

    /** Classification of one decoded pointer segment in its address context. */
    private enum SegmentKind
    {
        STRUCTURAL,
        NATURAL_KEY,
        INDEX
    }

    /** Stable category for a structured parse failure. */
    public enum FailureCode
    {
        MISSING_ADDRESS,
        MISSING_ROOT,
        ROOT_WHITESPACE,
        INVALID_POINTER,
        EMPTY_SEGMENT,
        INVALID_ESCAPE,
        INVALID_INDEX
    }

    /**
     * Parses {@code raw} without throwing. Check {@link ParseResult#isSuccess()} before reading the
     * address; a failure carries both a stable category and an actionable message.
     *
     * @param raw the root FQN with an optional pointer fragment
     * @return the structured parse outcome
     */
    public static ParseResult parse(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return failure(FailureCode.MISSING_ADDRESS, raw,
                "DCS address is missing. Pass an existing root FQN such as 'Report.Sales'."); //$NON-NLS-1$
        }

        int hash = raw.indexOf('#');
        String root = hash < 0 ? raw : raw.substring(0, hash);
        if (root.isEmpty())
        {
            return failure(FailureCode.MISSING_ROOT, root,
                "DCS address '" + raw + "' has an empty root FQN. Put the existing metadata FQN " //$NON-NLS-1$ //$NON-NLS-2$
                    + "before '#'."); //$NON-NLS-1$
        }
        if (!root.equals(root.trim()))
        {
            return failure(FailureCode.ROOT_WHITESPACE, root,
                "Root FQN '" + root + "' contains leading or trailing whitespace. Remove the " //$NON-NLS-1$ //$NON-NLS-2$
                    + "whitespace and pass the exact metadata FQN."); //$NON-NLS-1$
        }
        if (hash < 0)
        {
            return ParseResult.success(new DcsAddress(root, Collections.<String> emptyList(),
                Collections.<SegmentKind> emptyList()));
        }

        String pointer = raw.substring(hash + 1);
        if (!pointer.startsWith("/")) //$NON-NLS-1$
        {
            return failure(FailureCode.INVALID_POINTER, pointer,
                "Pointer '" + pointer + "' in DCS address '" + raw + "' must start with '/'. " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + "Use '#/...' for a node, or omit '#' for the root."); //$NON-NLS-1$
        }

        String[] encoded = pointer.substring(1).split("/", -1); //$NON-NLS-1$
        List<String> decoded = new ArrayList<>(encoded.length);
        for (int i = 0; i < encoded.length; i++)
        {
            if (encoded[i].isEmpty())
            {
                return failure(FailureCode.EMPTY_SEGMENT, encoded[i],
                    "Pointer '" + pointer + "' contains an empty segment at position " + i //$NON-NLS-1$ //$NON-NLS-2$
                        + ". Remove the doubled '/', and do not end a pointer with '/'."); //$NON-NLS-1$
            }
            String badEscape = invalidEscape(encoded[i]);
            if (badEscape != null)
            {
                return failure(FailureCode.INVALID_ESCAPE, encoded[i],
                    "Pointer segment '" + encoded[i] + "' contains invalid escape '" + badEscape //$NON-NLS-1$ //$NON-NLS-2$
                        + "'. Escape '~' as '~0' and '/' as '~1'."); //$NON-NLS-1$
            }
            // RFC 6901 requires this order: an encoded "~01" denotes the literal segment "~1".
            decoded.add(encoded[i].replace("~1", "/").replace("~0", "~")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }

        List<SegmentKind> kinds = new ArrayList<>(decoded.size());
        for (int i = 0; i < decoded.size(); i++)
        {
            boolean strictIndex = isStrictIndexPosition(decoded, i);
            OptionalInt index = parseIndex(decoded.get(i));
            if (strictIndex && !index.isPresent())
            {
                return invalidIndex(raw, decoded, i);
            }
            if (strictIndex || isFlexibleIndexPosition(decoded, i) && index.isPresent())
            {
                kinds.add(SegmentKind.INDEX);
            }
            else if (isNaturalKeyPosition(decoded, i))
            {
                kinds.add(SegmentKind.NATURAL_KEY);
            }
            else
            {
                kinds.add(SegmentKind.STRUCTURAL);
            }
        }
        return ParseResult.success(new DcsAddress(root, decoded, kinds));
    }

    /** @return the metadata FQN before the pointer fragment */
    public String rootFqn()
    {
        return rootFqn;
    }

    /** @return the ordered, decoded pointer segments (immutable) */
    public List<String> segments()
    {
        return segments;
    }

    /** @return whether this address has a pointer fragment */
    public boolean hasPointer()
    {
        return !segments.isEmpty();
    }

    /**
     * Tests whether the segment at {@code position} addresses a named node by its natural key. A
     * numeric data-set or field name is still a natural key because classification uses its collection
     * context, not only the segment's characters.
     *
     * @param position zero-based position in {@link #segments()}
     * @return whether the segment is a natural key; {@code false} for an invalid position
     */
    public boolean isNaturalKeySegment(int position)
    {
        return kindAt(position) == SegmentKind.NATURAL_KEY;
    }

    /**
     * Tests whether the segment at {@code position} is a zero-based index into an ordered collection.
     *
     * @param position zero-based position in {@link #segments()}
     * @return whether the segment is an index; {@code false} for an invalid position
     */
    public boolean isIndexSegment(int position)
    {
        return kindAt(position) == SegmentKind.INDEX;
    }

    /**
     * Returns an index segment's integer value.
     *
     * @param position zero-based position in {@link #segments()}
     * @return the index, or empty when the segment is not index-addressed
     */
    public OptionalInt indexAt(int position)
    {
        return isIndexSegment(position) ? parseIndex(segments.get(position)) : OptionalInt.empty();
    }

    /** @return whether any node in this address is selected by a positional index */
    public boolean isIndexAddressed()
    {
        return segmentKinds.contains(SegmentKind.INDEX);
    }

    /**
     * Tests the lexical form used for a zero-based list index.
     *
     * @param segment a decoded pointer segment
     * @return whether it is an integer in the range {@code 0..Integer.MAX_VALUE}
     */
    public static boolean isZeroBasedIndex(String segment)
    {
        return parseIndex(segment).isPresent();
    }

    /**
     * Renders a canonical address from a root FQN and decoded segments. Each {@code ~} is escaped
     * before each {@code /}, making {@link #parse(String)} round-trip both characters exactly.
     *
     * @param rootFqn the root metadata FQN
     * @param decodedSegments ordered decoded pointer segments
     * @return the canonical address
     */
    public static String render(String rootFqn, List<String> decodedSegments)
    {
        StringBuilder result = new StringBuilder(rootFqn == null ? "" : rootFqn); //$NON-NLS-1$
        if (decodedSegments == null || decodedSegments.isEmpty())
        {
            return result.toString();
        }
        result.append('#');
        for (String segment : decodedSegments)
        {
            result.append('/').append(escape(segment));
        }
        return result.toString();
    }

    /** @return this address in canonical root-plus-pointer form */
    @Override
    public String toString()
    {
        return canonical;
    }

    @Override
    public boolean equals(Object other)
    {
        if (this == other)
        {
            return true;
        }
        if (!(other instanceof DcsAddress))
        {
            return false;
        }
        DcsAddress that = (DcsAddress)other;
        return rootFqn.equals(that.rootFqn) && segments.equals(that.segments);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(rootFqn, segments);
    }

    private SegmentKind kindAt(int position)
    {
        return position >= 0 && position < segmentKinds.size()
            ? segmentKinds.get(position) : SegmentKind.STRUCTURAL;
    }

    private static ParseResult invalidIndex(String raw, List<String> segments, int position)
    {
        String value = segments.get(position);
        String collection = position == 0 ? "ordered collection" : "'" + segments.get(position - 1) + "'"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return failure(FailureCode.INVALID_INDEX, value,
            "Pointer segment '" + value + "' in DCS address '" + raw + "' must be a zero-based " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "index after " + collection + ". Use a non-negative integer from 0 to " //$NON-NLS-1$ //$NON-NLS-2$
                + Integer.MAX_VALUE + "."); //$NON-NLS-1$
    }

    private static ParseResult failure(FailureCode code, String badValue, String message)
    {
        return ParseResult.failure(new ParseFailure(code, badValue, message));
    }

    private static String invalidEscape(String encoded)
    {
        for (int i = 0; i < encoded.length(); i++)
        {
            if (encoded.charAt(i) == '~'
                && (i + 1 >= encoded.length()
                    || encoded.charAt(i + 1) != '0' && encoded.charAt(i + 1) != '1'))
            {
                return i + 1 < encoded.length() ? encoded.substring(i, i + 2) : "~"; //$NON-NLS-1$
            }
        }
        return null;
    }

    private static String escape(String decoded)
    {
        return decoded == null ? "" : decoded.replace("~", "~0").replace("/", "~1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    }

    private static OptionalInt parseIndex(String segment)
    {
        if (segment == null || segment.isEmpty())
        {
            return OptionalInt.empty();
        }
        for (int i = 0; i < segment.length(); i++)
        {
            if (!Character.isDigit(segment.charAt(i)))
            {
                return OptionalInt.empty();
            }
        }
        try
        {
            return OptionalInt.of(Integer.parseInt(segment));
        }
        catch (NumberFormatException e)
        {
            return OptionalInt.empty();
        }
    }

    private static boolean isNaturalKeyPosition(List<String> segments, int position)
    {
        if (position <= 0)
        {
            return false;
        }
        String collection = segments.get(position - 1);
        return NATURAL_KEY_COLLECTIONS.contains(collection)
            || "items".equals(collection) && !isStrictIndexPosition(segments, position); //$NON-NLS-1$
    }

    private static boolean isFlexibleIndexPosition(List<String> segments, int position)
    {
        return position > 0 && "items".equals(segments.get(position - 1)) //$NON-NLS-1$
            && !isStrictIndexPosition(segments, position);
    }

    private static boolean isStrictIndexPosition(List<String> segments, int position)
    {
        if (position <= 0)
        {
            return false;
        }
        String collection = segments.get(position - 1);
        if ("dataSetLinks".equals(collection) || "rows".equals(collection) //$NON-NLS-1$ //$NON-NLS-2$
            || "columns".equals(collection)) //$NON-NLS-1$
        {
            return true;
        }
        if (!"items".equals(collection)) //$NON-NLS-1$
        {
            return false;
        }
        for (int i = position - 2; i >= 0; i--)
        {
            String ancestor = segments.get(i);
            if (STRICT_INDEX_CONTEXTS.contains(ancestor))
            {
                return true;
            }
            if ("groupFields".equals(ancestor) || "dataParameters".equals(ancestor) //$NON-NLS-1$ //$NON-NLS-2$
                || "outputParameters".equals(ancestor) || "userFields".equals(ancestor)) //$NON-NLS-1$ //$NON-NLS-2$
            {
                return false;
            }
            if ("settings".equals(ancestor) || "defaultSettings".equals(ancestor) //$NON-NLS-1$ //$NON-NLS-2$
                || "listSettings".equals(ancestor)) //$NON-NLS-1$
            {
                return true;
            }
        }
        return false;
    }

    private static Set<String> naturalKeyCollections()
    {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, "dataSources", "dataSets", "fields", "parameters", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "calculatedFields", "totalFields", "variants"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> strictIndexContexts()
    {
        Set<String> result = new HashSet<>();
        Collections.addAll(result, "selection", "filter", "order", "conditionalAppearance"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return Collections.unmodifiableSet(result);
    }

    /** A structured, non-throwing parse outcome. */
    public static final class ParseResult
    {
        private final DcsAddress address;
        private final ParseFailure failure;

        private ParseResult(DcsAddress address, ParseFailure failure)
        {
            this.address = address;
            this.failure = failure;
        }

        private static ParseResult success(DcsAddress address)
        {
            return new ParseResult(address, null);
        }

        private static ParseResult failure(ParseFailure failure)
        {
            return new ParseResult(null, failure);
        }

        /** @return whether parsing produced an address */
        public boolean isSuccess()
        {
            return address != null;
        }

        /** @return the parsed address, or {@code null} on failure */
        public DcsAddress address()
        {
            return address;
        }

        /** @return the structured failure, or {@code null} on success */
        public ParseFailure failure()
        {
            return failure;
        }
    }

    /** A categorized parse failure with the offending value and an actionable correction. */
    public static final class ParseFailure
    {
        private final FailureCode code;
        private final String badValue;
        private final String message;

        private ParseFailure(FailureCode code, String badValue, String message)
        {
            this.code = code;
            this.badValue = badValue;
            this.message = message;
        }

        /** @return the stable failure category */
        public FailureCode code()
        {
            return code;
        }

        /** @return the bad input or segment, possibly {@code null} for a missing address */
        public String badValue()
        {
            return badValue;
        }

        /** @return an actionable English message naming the bad value and its correction */
        public String message()
        {
            return message;
        }
    }
}
