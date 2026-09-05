/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import java.util.Map;

/**
 * Utility methods for Markdown formatting.
 */
public final class MarkdownUtils
{
    private MarkdownUtils()
    {
        // Utility class - no instantiation
    }

    /**
     * Escapes special Markdown characters in text for use in tables.
     * Handles pipe characters and line breaks that would break table formatting.
     * 
     * @param text the text to escape
     * @return escaped text safe for Markdown tables
     */
    public static String escapeForTable(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        return text.replace("|", "\\|") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\n", " ") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("\r", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /**
     * Escapes special Markdown characters in text.
     * Useful for displaying text in Markdown without formatting issues.
     * 
     * @param text the text to escape
     * @return escaped text safe for Markdown
     */
    public static String escapeMarkdown(String text)
    {
        if (text == null)
        {
            return ""; //$NON-NLS-1$
        }
        // Escape common Markdown special characters
        return text.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("*", "\\*") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("_", "\\_") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("`", "\\`") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("[", "\\[") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("]", "\\]") //$NON-NLS-1$ //$NON-NLS-2$
            .replace("<", "\\<") //$NON-NLS-1$ //$NON-NLS-2$
            .replace(">", "\\>"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Wraps text so that a Markdown reader takes it as ONE inline code span and never as markup,
     * however the text is spelled.
     *
     * <h2>Why this shape rather than an escape list</h2>
     * {@link #escapeMarkdown(String)} names the characters it knows about, and a name-them-all
     * defence is only ever as complete as the list: this repository has already paid for that
     * lesson, with nine successive fixes to one echoed value (a delimiter that broke YAML, a
     * {@code trim()} that ate a control byte, an {@code x](url)} injection, a split surrogate
     * pair, a bidi override, line and paragraph separators, an invisible non-standalone mark, an
     * escape of our own that broke an emoji ZWJ sequence, and literal text that became
     * indistinguishable from a real control character). The answer there was not a tenth fix but a
     * smaller surface, and that is what this is.
     * <p>
     * A code span neutralises EVERY markup character by construction - there is no character
     * inside one that starts emphasis, a link, a heading or a table - so nothing has to be
     * enumerated. Exactly two things can break out of one, and both are closed here by a property
     * rather than by a blacklist:
     * <ul>
     * <li><b>a line break</b>, which ends the construct the span sits in (a heading, a list item,
     * a table row) and lets whatever follows be read as a new block. Every character Unicode
     * defines as a line terminator is replaced, along with the rest of the C0 controls and DEL:
     * {@code U+0000}-{@code U+001F}, {@code U+007F}, {@code U+0085}, {@code U+2028},
     * {@code U+2029}.</li>
     * <li><b>the delimiter itself</b>: a run of backticks equal to the fence closes the span. The
     * fence is therefore chosen one longer than the longest run in the text, which is CommonMark's
     * own rule and needs no character to be removed.</li>
     * </ul>
     *
     * <h2>What it does NOT promise</h2>
     * <ul>
     * <li><b>It is not a claim about how the text LOOKS.</b> Bidirectional overrides, joiners,
     * variation selectors and other format characters pass through untouched: they cannot end a
     * code span, so they cannot forge markup, but they can still reorder or hide what a human
     * reader sees. Neutralising them is a different problem - display - and pretending to solve it
     * here would start exactly the list this method exists to avoid.</li>
     * <li><b>The replacement is not reversible, and a text containing {@code U+FFFD} already is
     * indistinguishable from one where a control character was replaced.</b> The output is for
     * reading, not for feeding back into anything.</li>
     * <li><b>It is not an escape for other syntaxes.</b> The result is safe to place inside
     * Markdown; it says nothing about JSON, YAML, HTML attributes or a shell.</li>
     * <li><b>It never splits a surrogate pair</b> - it replaces whole characters, and every
     * character it replaces is below {@code U+FFFF} and outside the surrogate range - but it does
     * not otherwise normalise, so combining sequences arrive as they were sent.</li>
     * </ul>
     *
     * @param text the text to render; {@code null} is treated as empty
     * @return the code span, always non-empty and always on one line
     */
    public static String inlineCode(String text)
    {
        String source = text == null ? "" : text; //$NON-NLS-1$
        StringBuilder body = new StringBuilder(source.length());
        int longestRun = 0;
        int run = 0;
        for (int i = 0; i < source.length(); i++)
        {
            char c = source.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0x85 || c == 0x2028 || c == 0x2029)
            {
                // Written as an escape rather than as a literal character: a non-ASCII source
                // character is the corruption risk this repository escapes for everywhere else
                // (CLAUDE.md don't #7). The escape may not appear in a comment either - Java
                // translates those too, and an incomplete one there does not compile.
                body.append('\uFFFD');
                run = 0;
                continue;
            }
            body.append(c);
            run = c == '`' ? run + 1 : 0;
            longestRun = Math.max(longestRun, run);
        }
        // An empty span renders as two bare backticks, which reads as a mistake rather than as an
        // empty value; a space is a body, and the padding rule below then makes it visible.
        if (body.length() == 0)
        {
            body.append(' ');
        }
        StringBuilder fence = new StringBuilder();
        for (int i = 0; i <= longestRun; i++)
        {
            fence.append('`');
        }
        // CommonMark strips ONE leading and ONE trailing space from a span that has both, unless
        // the span is nothing but spaces. TWO bodies need the padding, and for opposite reasons:
        // one that touches the fence with a backtick (the padding is what keeps it out of the
        // delimiter), and one that already begins AND ends with a space (the padding is what
        // survives the stripping, so the text comes back as it was sent). Missing the second lost
        // both spaces of " x " - a quiet edit of a value this method promises to reproduce.
        char first = body.charAt(0);
        char last = body.charAt(body.length() - 1);
        boolean pad = first == '`' || last == '`'
            || (first == ' ' && last == ' ' && !isOnlySpaces(body));
        return fence + (pad ? " " : "") + body + (pad ? " " : "") + fence; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Whether every character is the space CommonMark's stripping rule counts - U+0020 and nothing
     * else, as the rule itself says.
     *
     * @param body the span's body, never empty
     * @return {@code true} when the body is spaces only
     */
    private static boolean isOnlySpaces(CharSequence body)
    {
        for (int i = 0; i < body.length(); i++)
        {
            if (body.charAt(i) != ' ')
            {
                return false;
            }
        }
        return true;
    }

    // ====================================================================
    // Table builders
    //
    // The single place that emits GitHub-flavoured Markdown table rows.
    // Every cell is run through escapeForTable, so a value containing '|'
    // or a newline can never break the table layout (the recurring bug in
    // hand-rolled table code — see CLAUDE.md don't #9). Callers that build
    // tables MUST go through these instead of concatenating '|' by hand.
    // ====================================================================

    /**
     * Builds a header row plus its separator line for a Markdown table.
     * Header labels are escaped. The result ends with a trailing newline,
     * so a caller can append data rows directly.
     *
     * <pre>
     * | Name | Value |
     * | --- | --- |
     * </pre>
     *
     * @param columns column header labels (must be non-empty)
     * @return the header line and separator line, newline-terminated
     * @throws IllegalArgumentException if columns is null or empty
     */
    public static String tableHeader(String... columns)
    {
        if (columns == null || columns.length == 0)
        {
            throw new IllegalArgumentException("a table needs at least one column"); //$NON-NLS-1$
        }
        StringBuilder header = new StringBuilder();
        StringBuilder separator = new StringBuilder();
        header.append("|"); //$NON-NLS-1$
        separator.append("|"); //$NON-NLS-1$
        for (String column : columns)
        {
            header.append(' ').append(escapeForTable(column)).append(" |"); //$NON-NLS-1$
            separator.append(" --- |"); //$NON-NLS-1$
        }
        header.append('\n');
        separator.append('\n');
        return header.append(separator).toString();
    }

    /**
     * Builds one data row for a Markdown table. Every cell is escaped, so a
     * value containing '|' or a line break cannot break the table. A null
     * cell renders as an empty cell.
     *
     * @param cells the cell values for this row
     * @return the row, newline-terminated
     * @throws IllegalArgumentException if cells is null or empty
     */
    public static String tableRow(String... cells)
    {
        if (cells == null || cells.length == 0)
        {
            throw new IllegalArgumentException("a table row needs at least one cell"); //$NON-NLS-1$
        }
        StringBuilder row = new StringBuilder("|"); //$NON-NLS-1$
        for (String cell : cells)
        {
            row.append(' ').append(escapeForTable(cell)).append(" |"); //$NON-NLS-1$
        }
        return row.append('\n').toString();
    }

    /**
     * Builds a complete two-column {@code Key | Value} table from the entries
     * of a map, preserving iteration order (pass a {@link java.util.LinkedHashMap}
     * for stable output). Keys and values are escaped.
     *
     * @param keyHeader header label for the key column
     * @param valueHeader header label for the value column
     * @param entries the rows; iteration order is preserved
     * @return the full table (header + separator + rows), newline-terminated
     */
    public static String keyValueTable(String keyHeader, String valueHeader, Map<String, String> entries)
    {
        StringBuilder table = new StringBuilder(tableHeader(keyHeader, valueHeader));
        if (entries != null)
        {
            for (Map.Entry<String, String> entry : entries.entrySet())
            {
                table.append(tableRow(entry.getKey(), entry.getValue()));
            }
        }
        return table.toString();
    }
}
