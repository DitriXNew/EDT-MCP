/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Arrays;
import java.util.List;

/**
 * Replaces EVERY whole-word occurrence of a fragment in a module's text - the matching rule of
 * {@code write_module_source mode=replaceAll} (#996). Pure: no I/O, no model.
 * <ul>
 * <li>Literal and case-sensitive, on the text as given (the caller passes the {@code \n}-normalized
 * module, the text {@code searchReplace} matches and {@code search_in_code} finds).</li>
 * <li>Whole word: a match counts only when the code point before it is not an identifier character - or
 * the fragment does not start with one - and the code point after it is not one - or the fragment does
 * not end with one. "Identifier character" is {@link BslModuleUtils#isIdentifierCharacter(int)}: letters
 * of any script, digits and {@code _}, so a Cyrillic name has its boundary by construction. A fragment
 * with a punctuation edge ({@code Old.Run(}) is checked on its identifier edge only.</li>
 * <li>Left to right, non-overlapping; the replacement text is never scanned again.</li>
 * <li>Comments and string literals are text like any other and are replaced.</li>
 * <li>A match never starts or ends between the two halves of a surrogate pair: a lone surrogate in the fragment
 * cannot cut a letter outside the BMP in two.</li>
 * </ul>
 * BSL ignores letter case, so a whole-word spelling that differs from the fragment only by case is the
 * same identifier: it is REPORTED ({@link Result#caseVariantWarning()}), never replaced - a literal
 * replace would otherwise leave it behind silently. Occurrences inside longer identifiers are counted for
 * the refusal when nothing matched. What is written depends only on the exact, case-sensitive matches; the
 * case folding feeds the report alone.
 * <p>
 * Memory is bounded whatever the number of matches: the scans read the module in place, keep no offset per
 * match - they count and list lines as they go - and the text is built in a separate pass once it is known to fit.
 * </p>
 */
public final class BslWholeWordReplace
{
    /** The longest text a replaceAll may produce, in characters. */
    public static final int MAX_RESULT_LENGTH = 10_000_000;

    /** How many line entries a list of lines shows before it says how many more there are. */
    static final int MAX_LISTED_LINES = 40;

    /** How many distinct other-case spellings a message names. */
    private static final int MAX_LISTED_SPELLINGS = 5;

    /** How long a quoted spelling may get in a message before it is shortened. */
    private static final int MAX_SPELLING_LENGTH = 80;

    private BslWholeWordReplace()
    {
        // Utility class
    }

    /**
     * Replaces every whole-word occurrence of {@code oldText} in {@code content} with {@code newText}.
     *
     * @param content the module's text, never {@code null}
     * @param oldText the fragment to replace, non-empty
     * @param newText the replacement, may be empty
     * @return the outcome; {@link Result#replaced()} tells whether there is a new text
     */
    public static Result replace(String content, String oldText, String newText)
    {
        return replace(content, oldText, newText, MAX_RESULT_LENGTH);
    }

    /** {@link #replace(String, String, String)} with the result bound as a parameter. Package-visible for tests. */
    static Result replace(String content, String oldText, String newText, int maxResultLength)
    {
        int width = oldText.length();
        boolean checkLeft = BslModuleUtils.isIdentifierCharacter(oldText.codePointAt(0));
        boolean checkRight = BslModuleUtils.isIdentifierCharacter(oldText.codePointBefore(width));
        char[] pattern = oldText.toCharArray();
        // One failure table for every scan of the fragment (local review of #999): a long fragment costs its table once.
        int[] failure = failureTable(pattern);

        // Pass 1 counts the matches and lists their lines as it goes, holding no offset per match (#999 review).
        Lines replaced = new Lines(content);
        Lines inside = new Lines(content);
        ExactScan exact = new ExactScan(content, pattern, failure, checkLeft, checkRight, inside);
        for (int start = exact.next(); start >= 0; start = exact.next())
        {
            replaced.add(start);
        }
        Variants variants = caseVariants(content, oldText, pattern, failure, checkLeft, checkRight,
            replaced.occurrences() > 0);

        Result result = new Result(replaced, inside, variants);
        int replacements = replaced.occurrences();
        if (replacements == 0)
        {
            return result;
        }
        long resultLength = content.length() + (long) replacements * (newText.length() - width);
        if (resultLength > maxResultLength)
        {
            return result.tooLarge(resultLength, maxResultLength);
        }
        // Pass 2 builds the text from the same matches, found again.
        StringBuilder sb = new StringBuilder((int) resultLength);
        ExactScan again = new ExactScan(content, pattern, failure, checkLeft, checkRight, null);
        int copied = 0;
        for (int start = again.next(); start >= 0; start = again.next())
        {
            sb.append(content, copied, start).append(newText);
            copied = start + width;
        }
        sb.append(content, copied, content.length());
        return result.withNewContent(sb.toString());
    }

    /**
     * The whole-word matches of {@code oldText} that differ from it only by letter case and overlap no exact
     * match, left to right, non-overlapping. Always scanned: whether a fragment "has a case" is not a property of
     * its own characters - the small sharp s maps to no other character, yet the capital sharp s lowercases to it
     * (#999 review). A fragment with no case partner simply finds only its exact matches here, which are skipped.
     */
    private static Variants caseVariants(String content, String oldText, char[] pattern, int[] failure,
        boolean checkLeft, boolean checkRight, boolean anyExact)
    {
        Variants variants = new Variants(content);
        int width = oldText.length();
        // The exact matches, found again in step with this scan: an other-case candidate that overlaps one is not
        // reported (an occurrence spelled exactly like oldText is, when it is a whole word, an exact match or
        // overlaps one, so it never gets here).
        // With no exact match there is nothing to overlap, and no second walk of the module (local review of #999).
        ExactScan exact = anyExact ? new ExactScan(content, pattern, failure, checkLeft, checkRight, null) : null;
        int nextExact = exact == null ? -1 : exact.next();
        int acceptedEnd = 0;
        char[] folded = fold(oldText);
        int[] foldedFailure = Arrays.equals(folded, pattern) ? failure : failureTable(folded);
        Occurrences occurrences = new Occurrences(content, folded, foldedFailure, true);
        for (int index = occurrences.next(); index >= 0; index = occurrences.next())
        {
            int end = index + width;
            if (index < acceptedEnd || splitsPair(content, index, end)
                || !isWholeWord(content, index, end, checkLeft, checkRight))
            {
                continue;
            }
            while (nextExact >= 0 && nextExact + width <= index)
            {
                nextExact = exact.next();
            }
            if (nextExact >= 0 && nextExact < end)
            {
                continue;
            }
            variants.add(index, end);
            acceptedEnd = end;
        }
        variants.release();
        return variants;
    }

    /** Whether the span starts or ends between the two halves of a surrogate pair of the text. */
    private static boolean splitsPair(String content, int start, int end)
    {
        return isPairBoundary(content, start) || isPairBoundary(content, end);
    }

    /** Whether the offset falls between a high surrogate and the low surrogate that completes it. */
    private static boolean isPairBoundary(String content, int offset)
    {
        return offset > 0 && offset < content.length() && Character.isHighSurrogate(content.charAt(offset - 1))
            && Character.isLowSurrogate(content.charAt(offset));
    }

    /** The fragment case-folded char by char with {@link #foldedAt}. */
    private static char[] fold(String text)
    {
        char[] folded = new char[text.length()];
        for (int offset = 0; offset < folded.length; offset++)
        {
            folded[offset] = foldedAt(text, offset);
        }
        return folded;
    }

    /**
     * The UTF-16 char at {@code offset} of the text case-folded by code point the way
     * {@link String#regionMatches(boolean, int, String, int, int)} compares ignoring case
     * ({@code toLowerCase(toUpperCase(c))}), keeping the UTF-16 length, so an offset in the folded text is the same
     * offset in the text - computed in place, without a folded copy of the module (#999 review). A code point whose
     * fold would change its length is kept as it is.
     */
    private static char foldedAt(String text, int offset)
    {
        char ch = text.charAt(offset);
        if (Character.isHighSurrogate(ch) && offset + 1 < text.length()
            && Character.isLowSurrogate(text.charAt(offset + 1)))
        {
            int codePoint = Character.toCodePoint(ch, text.charAt(offset + 1));
            return Character.highSurrogate(foldedSupplementary(codePoint));
        }
        if (Character.isLowSurrogate(ch) && offset > 0 && Character.isHighSurrogate(text.charAt(offset - 1)))
        {
            int codePoint = Character.toCodePoint(text.charAt(offset - 1), ch);
            return Character.lowSurrogate(foldedSupplementary(codePoint));
        }
        int folded = Character.toLowerCase(Character.toUpperCase((int) ch));
        return Character.isBmpCodePoint(folded) ? (char) folded : ch;
    }

    private static int foldedSupplementary(int codePoint)
    {
        int folded = Character.toLowerCase(Character.toUpperCase(codePoint));
        return Character.isSupplementaryCodePoint(folded) ? folded : codePoint;
    }

    /**
     * The accepted exact matches, left to right and non-overlapping: whole words that cut no surrogate pair. The
     * occurrences inside longer identifiers, counted once per non-overlapping span, go to {@code inside} when given.
     */
    private static final class ExactScan
    {
        private final String content;
        private final int width;
        private final boolean checkLeft;
        private final boolean checkRight;
        private final Lines inside;
        private final Occurrences occurrences;
        private int acceptedEnd;
        private int insideEnd;

        ExactScan(String content, char[] pattern, int[] failure, boolean checkLeft, boolean checkRight, Lines inside)
        {
            this.content = content;
            this.width = pattern.length;
            this.checkLeft = checkLeft;
            this.checkRight = checkRight;
            this.inside = inside;
            // Every occurrence in one linear pass (#999 review): String.indexOf compares the fragment afresh at each
            // offset, which is quadratic for a long fragment that nearly matches a repetitive module.
            this.occurrences = new Occurrences(content, pattern, failure, false);
        }

        /** The start of the next accepted match, or -1. */
        int next()
        {
            for (int index = occurrences.next(); index >= 0; index = occurrences.next())
            {
                if (index < acceptedEnd || splitsPair(content, index, index + width))
                {
                    continue;
                }
                if (isWholeWord(content, index, index + width, checkLeft, checkRight))
                {
                    acceptedEnd = index + width;
                    return index;
                }
                if (inside != null && index >= insideEnd)
                {
                    inside.add(index);
                    insideEnd = index + width;
                }
            }
            return -1;
        }
    }

    /**
     * Every start offset of a pattern in a text, ascending, overlapping ones included - Knuth-Morris-Pratt, linear in
     * the text and the pattern whatever their content.
     */
    private static final class Occurrences
    {
        /** Read in place, char by char (folded on the fly when {@link #folded}): no copy of the module. */
        private final String text;
        private final char[] pattern;
        private final boolean folded;
        private final int[] failure;
        private int position;
        private int matched;

        Occurrences(String text, char[] pattern, int[] failure, boolean folded)
        {
            this.text = text;
            this.pattern = pattern;
            this.failure = failure;
            this.folded = folded;
        }

        /** The next start offset, or -1 when there is none. */
        int next()
        {
            while (position < text.length())
            {
                char ch = folded ? foldedAt(text, position) : text.charAt(position);
                position++;
                while (matched > 0 && pattern[matched] != ch)
                {
                    matched = failure[matched - 1];
                }
                if (pattern[matched] == ch)
                {
                    matched++;
                }
                if (matched == pattern.length)
                {
                    matched = failure[matched - 1];
                    return position - pattern.length;
                }
            }
            return -1;
        }
    }

    /** The Knuth-Morris-Pratt failure table of a pattern: read-only, shared by every scan of that pattern. */
    private static int[] failureTable(char[] pattern)
    {
        int[] failure = new int[pattern.length];
        int length = 0;
        for (int index = 1; index < pattern.length; index++)
        {
            while (length > 0 && pattern[index] != pattern[length])
            {
                length = failure[length - 1];
            }
            if (pattern[index] == pattern[length])
            {
                length++;
            }
            failure[index] = length;
        }
        return failure;
    }

    private static boolean isWholeWord(String content, int start, int end, boolean checkLeft, boolean checkRight)
    {
        if (checkLeft && start > 0 && BslModuleUtils.isIdentifierCharacter(content.codePointBefore(start)))
        {
            return false;
        }
        return !(checkRight && end < content.length()
            && BslModuleUtils.isIdentifierCharacter(content.codePointAt(end)));
    }

    /**
     * The 1-based line numbers as a list with consecutive lines collapsed into ranges ({@code 12, 40-42, 97}),
     * at most {@link #MAX_LISTED_LINES} entries, then {@code (+K more)} counting the lines not listed.
     *
     * @param lines distinct line numbers in ascending order
     * @return the rendered list, empty for no lines
     */
    static String collapse(List<Integer> lines)
    {
        Listing listing = new Listing();
        for (int line : lines)
        {
            listing.add(line);
        }
        return listing.text();
    }

    /** A spelling quoted for a one-line message: line breaks shown as {@code \n}, a long one shortened. */
    private static String quoted(String spelling)
    {
        String shown = spelling.replace("\r", "\\r").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (shown.length() > MAX_SPELLING_LENGTH)
        {
            shown = shown.substring(0, MAX_SPELLING_LENGTH) + "..."; //$NON-NLS-1$
        }
        return "'" + shown + "'"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Distinct ascending line numbers rendered as {@link #collapse}: the first {@link #MAX_LISTED_LINES} entries are
     * kept, every later line is only counted - bounded whatever the number of lines.
     */
    private static final class Listing
    {
        private final int[] firsts = new int[MAX_LISTED_LINES];
        private final int[] lasts = new int[MAX_LISTED_LINES];
        private int entries;
        private int beyond;
        private int lines;

        void add(int line)
        {
            lines++;
            if (beyond == 0 && entries > 0 && line == lasts[entries - 1] + 1)
            {
                lasts[entries - 1] = line;
            }
            else if (beyond == 0 && entries < MAX_LISTED_LINES)
            {
                firsts[entries] = line;
                lasts[entries] = line;
                entries++;
            }
            else
            {
                beyond++;
            }
        }

        int lines()
        {
            return lines;
        }

        String text()
        {
            StringBuilder sb = new StringBuilder();
            for (int entry = 0; entry < entries; entry++)
            {
                if (entry > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(firsts[entry]);
                if (lasts[entry] != firsts[entry])
                {
                    sb.append('-').append(lasts[entry]);
                }
            }
            if (beyond > 0)
            {
                sb.append(" (+").append(beyond).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return sb.toString();
        }

        /** "line 7" or "lines 3, 5-6": the word agrees with the count. */
        String phrase()
        {
            return (lines == 1 ? "line " : "lines ") + text(); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * The occurrences at ascending offsets of one text and their lines: a count and a {@link Listing}, the line of
     * each offset found by walking the text forward once. A line ends at {@code \r\n}, {@code \n} or a bare
     * {@code \r} - the separators read_module_source and the write split on (#999 review).
     */
    private static final class Lines
    {
        private String content;
        private int cursor;
        private int line = 1;
        private int lastLine;
        private int occurrences;
        private final Listing listing = new Listing();

        Lines(String content)
        {
            this.content = content;
        }

        void add(int offset)
        {
            occurrences++;
            while (cursor < offset)
            {
                char ch = content.charAt(cursor);
                boolean crlf = ch == '\r' && cursor + 1 < content.length() && content.charAt(cursor + 1) == '\n';
                if ((ch == '\n' || ch == '\r') && !crlf)
                {
                    line++;
                }
                cursor++;
            }
            if (line != lastLine)
            {
                listing.add(line);
                lastLine = line;
            }
        }

        /** Drops the text: a result keeps only the counts and the listing, not the module. */
        void release()
        {
            content = null;
        }

        int occurrences()
        {
            return occurrences;
        }

        Listing listing()
        {
            return listing;
        }
    }

    /**
     * The other-case occurrences: their count, the first {@link #MAX_LISTED_SPELLINGS} distinct spellings with their
     * lines, and how many occurrences are in further spellings.
     */
    private static final class Variants
    {
        private final String content;
        /** The recorded spellings and their lines, in order of first appearance - arrays, no per-hit allocation. */
        private final String[] spellings = new String[MAX_LISTED_SPELLINGS];
        private final Lines[] lines = new Lines[MAX_LISTED_SPELLINGS];
        private int recorded;
        private int occurrences;
        private int otherOccurrences;

        Variants(String content)
        {
            this.content = content;
        }

        /**
         * Records the occurrence at {@code [start, end)}. Its spelling is compared in place with the ones recorded
         * (at most {@link #MAX_LISTED_SPELLINGS}); the text is copied only for a new one (local review of #999), so a
         * million hits of one spelling allocate nothing.
         */
        void add(int start, int end)
        {
            occurrences++;
            int width = end - start;
            for (int index = 0; index < recorded; index++)
            {
                if (spellings[index].length() == width && content.regionMatches(start, spellings[index], 0, width))
                {
                    lines[index].add(start);
                    return;
                }
            }
            if (recorded < MAX_LISTED_SPELLINGS)
            {
                spellings[recorded] = content.substring(start, end);
                lines[recorded] = new Lines(content);
                lines[recorded].add(start);
                recorded++;
                return;
            }
            otherOccurrences++;
        }

        void release()
        {
            for (int index = 0; index < recorded; index++)
            {
                lines[index].release();
            }
        }

        /** {@code 'Spelling' at lines 3, 7, 'other' at line 9}, then how many occurrences are in other spellings. */
        String text()
        {
            StringBuilder sb = new StringBuilder();
            for (int index = 0; index < recorded; index++)
            {
                if (index > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(quoted(spellings[index])).append(" at ").append(lines[index].listing().phrase()); //$NON-NLS-1$
            }
            if (otherOccurrences > 0)
            {
                sb.append(" and ").append(otherOccurrences).append(" more in other spellings"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return sb.toString();
        }
    }

    /** The outcome of {@link BslWholeWordReplace#replace(String, String, String)}. */
    public static final class Result
    {
        private final int replacements;
        private final Listing replacedLines;
        private final int insideIdentifiers;
        private final Listing insideLines;
        private final int caseVariants;
        private final String caseVariantText;
        private String newContent;
        private long tooLargeLength = -1;
        private int maxResultLength;

        Result(Lines replaced, Lines inside, Variants variants)
        {
            replaced.release();
            inside.release();
            this.replacements = replaced.occurrences();
            this.replacedLines = replaced.listing();
            this.insideIdentifiers = inside.occurrences();
            this.insideLines = inside.listing();
            this.caseVariants = variants.occurrences;
            this.caseVariantText = variants.occurrences == 0 ? "" : variants.text(); //$NON-NLS-1$
        }

        Result withNewContent(String text)
        {
            this.newContent = text;
            return this;
        }

        Result tooLarge(long length, int max)
        {
            this.tooLargeLength = length;
            this.maxResultLength = max;
            return this;
        }

        /** Whether the text was rewritten: at least one whole-word occurrence, within the size bound. */
        public boolean replaced()
        {
            return newContent != null;
        }

        /** The rewritten text, or {@code null} when nothing was replaced. */
        public String newContent()
        {
            return newContent;
        }

        /** How many whole-word occurrences were (or, when too large, would have been) replaced. */
        public int replacements()
        {
            return replacements;
        }

        /** The 1-based lines of the original text where a replaced occurrence starts, collapsed into ranges. */
        public String replacedLinesText()
        {
            return replacedLines.text();
        }

        /** How many occurrences inside longer identifiers were left. */
        public int insideIdentifiers()
        {
            return insideIdentifiers;
        }

        /** How many whole-word occurrences in another letter case were left. */
        public int caseVariants()
        {
            return caseVariants;
        }

        /**
         * Why nothing was rewritten - no whole-word occurrence (naming the occurrences inside identifiers and in
         * another case, when there are), or a result over the size bound - or {@code null} after a rewrite.
         */
        public String failureMessage()
        {
            if (replaced())
            {
                return null;
            }
            if (tooLargeLength >= 0)
            {
                return "replaceAll would make the module " + tooLargeLength + " characters long, more than the " //$NON-NLS-1$ //$NON-NLS-2$
                    + maxResultLength + " allowed (" + replacements + " occurrences). Nothing was written."; //$NON-NLS-1$ //$NON-NLS-2$
            }
            StringBuilder sb = new StringBuilder("oldSource has no whole-word occurrence in the module."); //$NON-NLS-1$
            if (insideIdentifiers > 0)
            {
                sb.append(" It occurs ").append(insideIdentifiers) //$NON-NLS-1$
                    .append(" time(s) inside longer identifiers (").append(insideLines.phrase()) //$NON-NLS-1$
                    .append("); replaceAll never rewrites part of an identifier - use searchReplace for those."); //$NON-NLS-1$
            }
            if (caseVariants > 0)
            {
                sb.append(' ').append(caseVariants).append(" occurrence(s) differ only by letter case: ") //$NON-NLS-1$
                    .append(caseVariantText).append(". BSL ignores case: repeat replaceAll with that spelling if it is " //$NON-NLS-1$
                        + "the same identifier."); //$NON-NLS-1$
            }
            if (insideIdentifiers == 0 && caseVariants == 0)
            {
                sb.append(" Read the module again with read_module_source: oldSource must match the text exactly, " //$NON-NLS-1$
                    + "letter case included."); //$NON-NLS-1$
            }
            return sb.append(" Nothing was written.").toString(); //$NON-NLS-1$
        }

        /**
         * The warning a rewrite carries when whole-word occurrences in another letter case were left, or
         * {@code null} when there were none.
         */
        public String caseVariantWarning()
        {
            if (caseVariants == 0)
            {
                return null;
            }
            return caseVariants + " whole-word occurrence(s) of the old text in another letter case were left " //$NON-NLS-1$
                + "(BSL ignores case): " + caseVariantText //$NON-NLS-1$
                + ". Repeat replaceAll with that spelling if it is the same identifier."; //$NON-NLS-1$
        }
    }
}
