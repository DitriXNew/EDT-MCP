/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * </ul>
 * BSL ignores letter case, so a whole-word spelling that differs from the fragment only by case is the
 * same identifier: it is REPORTED ({@link Result#caseVariantWarning()}), never replaced - a literal
 * replace would otherwise leave it behind silently. Occurrences inside longer identifiers are counted for
 * the refusal when nothing matched.
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

        List<Integer> exact = new ArrayList<>();
        List<Integer> inside = new ArrayList<>();
        int insideEnd = 0;
        int acceptedEnd = 0;
        // Every occurrence in one linear pass (#999 review): String.indexOf compares the fragment afresh at each
        // offset, which is quadratic for a long fragment that nearly matches a repetitive module.
        Occurrences occurrences = new Occurrences(content, oldText.toCharArray(), false);
        for (int index = occurrences.next(); index >= 0; index = occurrences.next())
        {
            if (index < acceptedEnd)
            {
                continue;
            }
            if (isWholeWord(content, index, index + width, checkLeft, checkRight))
            {
                exact.add(index);
                acceptedEnd = index + width;
            }
            else if (index >= insideEnd)
            {
                inside.add(index);
                insideEnd = index + width;
            }
        }

        Map<String, List<Integer>> variants = new LinkedHashMap<>();
        List<Integer> variantStarts = caseVariants(content, oldText, exact, checkLeft, checkRight, variants);

        LineIndex lines = new LineIndex(content);
        Result result = new Result(exact.size(), lines.distinctLines(exact), inside.size(), lines.distinctLines(inside),
            variantStarts.size(), linesBySpelling(variants, lines));
        if (exact.isEmpty())
        {
            return result;
        }
        long resultLength = content.length() + (long) exact.size() * (newText.length() - width);
        if (resultLength > maxResultLength)
        {
            return result.tooLarge(resultLength, maxResultLength);
        }
        StringBuilder sb = new StringBuilder((int) resultLength);
        int copied = 0;
        for (int start : exact)
        {
            sb.append(content, copied, start).append(newText);
            copied = start + width;
        }
        sb.append(content, copied, content.length());
        return result.withNewContent(sb.toString());
    }

    /**
     * The whole-word matches of {@code oldText} that differ from it only by letter case and overlap no exact
     * match, left to right, non-overlapping. Fills {@code spellings} with each distinct spelling's starts.
     */
    private static List<Integer> caseVariants(String content, String oldText, List<Integer> exact, boolean checkLeft,
        boolean checkRight, Map<String, List<Integer>> spellings)
    {
        List<Integer> starts = new ArrayList<>();
        if (!hasCasedLetter(oldText))
        {
            return starts;
        }
        int width = oldText.length();
        int next = 0;
        int acceptedEnd = 0;
        // The case-insensitive candidates come from one linear pass over the case-folded texts (#999 review), not
        // from a comparison of the whole fragment at every offset. An occurrence spelled exactly like oldText is
        // never a candidate here: when it is a whole word it is an exact match or overlaps one.
        Occurrences occurrences = new Occurrences(content, fold(oldText), true);
        for (int index = occurrences.next(); index >= 0; index = occurrences.next())
        {
            if (index < acceptedEnd || !isWholeWord(content, index, index + width, checkLeft, checkRight))
            {
                continue;
            }
            while (next < exact.size() && exact.get(next) + width <= index)
            {
                next++;
            }
            if (next < exact.size() && exact.get(next) < index + width)
            {
                continue;
            }
            starts.add(index);
            spellings.computeIfAbsent(content.substring(index, index + width), key -> new ArrayList<>()).add(index);
            acceptedEnd = index + width;
        }
        return starts;
    }

    /** Whether the text holds a letter that has another case - by code point, so a letter outside the BMP counts. */
    private static boolean hasCasedLetter(String text)
    {
        return text.codePoints().anyMatch(codePoint -> Character.toUpperCase(codePoint) != codePoint
            || Character.toLowerCase(codePoint) != codePoint);
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

        Occurrences(String text, char[] pattern, boolean folded)
        {
            this.text = text;
            this.pattern = pattern;
            this.folded = folded;
            this.failure = new int[pattern.length];
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

    private static boolean isWholeWord(String content, int start, int end, boolean checkLeft, boolean checkRight)
    {
        if (checkLeft && start > 0 && BslModuleUtils.isIdentifierCharacter(content.codePointBefore(start)))
        {
            return false;
        }
        return !(checkRight && end < content.length()
            && BslModuleUtils.isIdentifierCharacter(content.codePointAt(end)));
    }

    private static Map<String, List<Integer>> linesBySpelling(Map<String, List<Integer>> starts, LineIndex lines)
    {
        Map<String, List<Integer>> byLine = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : starts.entrySet())
        {
            byLine.put(entry.getKey(), lines.distinctLines(entry.getValue()));
        }
        return byLine;
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
        StringBuilder sb = new StringBuilder();
        int entries = 0;
        int index = 0;
        while (index < lines.size())
        {
            if (entries == MAX_LISTED_LINES)
            {
                sb.append(" (+").append(lines.size() - index).append(" more)"); //$NON-NLS-1$ //$NON-NLS-2$
                break;
            }
            int first = lines.get(index);
            int last = first;
            while (index + 1 < lines.size() && lines.get(index + 1) == last + 1)
            {
                index++;
                last = lines.get(index);
            }
            if (entries > 0)
            {
                sb.append(", "); //$NON-NLS-1$
            }
            sb.append(first);
            if (last != first)
            {
                sb.append('-').append(last);
            }
            entries++;
            index++;
        }
        return sb.toString();
    }

    /** "line 7" or "lines 3, 5-6": the word agrees with the count. */
    private static String linesPhrase(List<Integer> lines)
    {
        return (lines.size() == 1 ? "line " : "lines ") + collapse(lines); //$NON-NLS-1$ //$NON-NLS-2$
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
     * Character offsets to 1-based line numbers of one text. A line ends at {@code \r\n}, {@code \n} or a bare
     * {@code \r} - the separators read_module_source and the write split on (#999 review).
     */
    private static final class LineIndex
    {
        private final int[] lineStarts;

        LineIndex(String content)
        {
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int offset = 0; offset < content.length(); offset++)
            {
                char ch = content.charAt(offset);
                boolean crlf = ch == '\r' && offset + 1 < content.length() && content.charAt(offset + 1) == '\n';
                if ((ch == '\n' || ch == '\r') && !crlf)
                {
                    starts.add(offset + 1);
                }
            }
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        int lineOf(int offset)
        {
            int low = 0;
            int high = lineStarts.length - 1;
            while (low < high)
            {
                int middle = (low + high + 1) >>> 1;
                if (lineStarts[middle] <= offset)
                {
                    low = middle;
                }
                else
                {
                    high = middle - 1;
                }
            }
            return low + 1;
        }

        /** The distinct lines of ascending offsets. */
        List<Integer> distinctLines(List<Integer> offsets)
        {
            List<Integer> lines = new ArrayList<>();
            for (int offset : offsets)
            {
                int line = lineOf(offset);
                if (lines.isEmpty() || lines.get(lines.size() - 1) != line)
                {
                    lines.add(line);
                }
            }
            return lines;
        }
    }

    /** The outcome of {@link BslWholeWordReplace#replace(String, String, String)}. */
    public static final class Result
    {
        private final int replacements;
        private final List<Integer> replacedLines;
        private final int insideIdentifiers;
        private final List<Integer> insideLines;
        private final int caseVariants;
        private final Map<String, List<Integer>> caseVariantLines;
        private String newContent;
        private long tooLargeLength = -1;
        private int maxResultLength;

        Result(int replacements, List<Integer> replacedLines, int insideIdentifiers, List<Integer> insideLines,
            int caseVariants, Map<String, List<Integer>> caseVariantLines)
        {
            this.replacements = replacements;
            this.replacedLines = replacedLines;
            this.insideIdentifiers = insideIdentifiers;
            this.insideLines = insideLines;
            this.caseVariants = caseVariants;
            this.caseVariantLines = caseVariantLines;
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
            return collapse(replacedLines);
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
                    .append(" time(s) inside longer identifiers (").append(linesPhrase(insideLines)) //$NON-NLS-1$
                    .append("); replaceAll never rewrites part of an identifier - use searchReplace for those."); //$NON-NLS-1$
            }
            if (caseVariants > 0)
            {
                sb.append(' ').append(caseVariants).append(" occurrence(s) differ only by letter case: ") //$NON-NLS-1$
                    .append(spellings()).append(". BSL ignores case: repeat replaceAll with that spelling if it is " //$NON-NLS-1$
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
                + "(BSL ignores case): " + spellings() //$NON-NLS-1$
                + ". Repeat replaceAll with that spelling if it is the same identifier."; //$NON-NLS-1$
        }

        /** {@code 'Spelling' (lines 3, 7), 'other' (line 9)}, at most {@link #MAX_LISTED_SPELLINGS} spellings. */
        private String spellings()
        {
            StringBuilder sb = new StringBuilder();
            int listed = 0;
            for (Map.Entry<String, List<Integer>> entry : caseVariantLines.entrySet())
            {
                if (listed == MAX_LISTED_SPELLINGS)
                {
                    sb.append(" and ").append(caseVariantLines.size() - listed).append(" more spelling(s)"); //$NON-NLS-1$ //$NON-NLS-2$
                    break;
                }
                if (listed > 0)
                {
                    sb.append(", "); //$NON-NLS-1$
                }
                sb.append(quoted(entry.getKey())).append(" at ").append(linesPhrase(entry.getValue())); //$NON-NLS-1$
                listed++;
            }
            return sb.toString();
        }
    }
}
