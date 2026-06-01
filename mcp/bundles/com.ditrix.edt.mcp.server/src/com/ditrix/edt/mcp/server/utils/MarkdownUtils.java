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
