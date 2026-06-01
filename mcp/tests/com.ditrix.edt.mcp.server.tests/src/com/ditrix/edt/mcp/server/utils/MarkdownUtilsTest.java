/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tests for {@link MarkdownUtils}.
 * Verifies Markdown escaping for tables and general content.
 */
public class MarkdownUtilsTest
{
    // ========== escapeForTable ==========

    @Test
    public void testEscapeForTableNull()
    {
        assertEquals("", MarkdownUtils.escapeForTable(null));
    }

    @Test
    public void testEscapeForTableEmpty()
    {
        assertEquals("", MarkdownUtils.escapeForTable(""));
    }

    @Test
    public void testEscapeForTablePlainText()
    {
        assertEquals("Hello world", MarkdownUtils.escapeForTable("Hello world"));
    }

    @Test
    public void testEscapeForTablePipeCharacter()
    {
        assertEquals("column1 \\| column2", MarkdownUtils.escapeForTable("column1 | column2"));
    }

    @Test
    public void testEscapeForTableNewline()
    {
        assertEquals("line1 line2", MarkdownUtils.escapeForTable("line1\nline2"));
    }

    @Test
    public void testEscapeForTableCarriageReturn()
    {
        assertEquals("text", MarkdownUtils.escapeForTable("text\r"));
    }

    @Test
    public void testEscapeForTableCRLF()
    {
        assertEquals("line1 line2", MarkdownUtils.escapeForTable("line1\r\nline2"));
    }

    @Test
    public void testEscapeForTableMultiplePipes()
    {
        assertEquals("a \\| b \\| c", MarkdownUtils.escapeForTable("a | b | c"));
    }

    @Test
    public void testEscapeForTableCombined()
    {
        assertEquals("val \\| with space",
            MarkdownUtils.escapeForTable("val | with\nspace"));
    }

    // ========== escapeMarkdown ==========

    @Test
    public void testEscapeMarkdownNull()
    {
        assertEquals("", MarkdownUtils.escapeMarkdown(null));
    }

    @Test
    public void testEscapeMarkdownEmpty()
    {
        assertEquals("", MarkdownUtils.escapeMarkdown(""));
    }

    @Test
    public void testEscapeMarkdownPlainText()
    {
        assertEquals("Hello world", MarkdownUtils.escapeMarkdown("Hello world"));
    }

    @Test
    public void testEscapeMarkdownBackslash()
    {
        assertEquals("path\\\\to\\\\file", MarkdownUtils.escapeMarkdown("path\\to\\file"));
    }

    @Test
    public void testEscapeMarkdownAsterisk()
    {
        assertEquals("\\*bold\\*", MarkdownUtils.escapeMarkdown("*bold*"));
    }

    @Test
    public void testEscapeMarkdownUnderscore()
    {
        assertEquals("\\_italic\\_", MarkdownUtils.escapeMarkdown("_italic_"));
    }

    @Test
    public void testEscapeMarkdownBacktick()
    {
        assertEquals("\\`code\\`", MarkdownUtils.escapeMarkdown("`code`"));
    }

    @Test
    public void testEscapeMarkdownBrackets()
    {
        assertEquals("\\[link\\]", MarkdownUtils.escapeMarkdown("[link]"));
    }

    @Test
    public void testEscapeMarkdownAngleBrackets()
    {
        assertEquals("\\<html\\>", MarkdownUtils.escapeMarkdown("<html>"));
    }

    @Test
    public void testEscapeMarkdownAllSpecialChars()
    {
        String input = "\\*_`[]<>";
        String expected = "\\\\\\*\\_\\`\\[\\]\\<\\>";
        assertEquals(expected, MarkdownUtils.escapeMarkdown(input));
    }

    // ========== tableHeader ==========

    @Test
    public void testTableHeaderSingleColumn()
    {
        assertEquals("| Name |\n| --- |\n", MarkdownUtils.tableHeader("Name"));
    }

    @Test
    public void testTableHeaderMultipleColumns()
    {
        assertEquals("| Name | Value |\n| --- | --- |\n",
            MarkdownUtils.tableHeader("Name", "Value"));
    }

    @Test
    public void testTableHeaderEscapesLabels()
    {
        assertEquals("| a \\| b | c |\n| --- | --- |\n",
            MarkdownUtils.tableHeader("a | b", "c"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTableHeaderNullThrows()
    {
        MarkdownUtils.tableHeader((String[]) null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTableHeaderEmptyThrows()
    {
        MarkdownUtils.tableHeader();
    }

    // ========== tableRow ==========

    @Test
    public void testTableRowBasic()
    {
        assertEquals("| a | b |\n", MarkdownUtils.tableRow("a", "b"));
    }

    @Test
    public void testTableRowNullCellRendersEmpty()
    {
        assertEquals("| a |  |\n", MarkdownUtils.tableRow("a", null));
    }

    /** The core bug this card fixes: a cell containing '|' must not break the table. */
    @Test
    public void testTableRowEscapesPipe()
    {
        String row = MarkdownUtils.tableRow("a | b", "c");
        assertEquals("| a \\| b | c |\n", row);
        // exactly 3 unescaped column delimiters (leading, middle, trailing) — the
        // embedded pipe is escaped, so the row still has 2 logical columns.
        assertEquals(3, countUnescapedPipes(row));
    }

    @Test
    public void testTableRowEscapesNewline()
    {
        assertEquals("| line1 line2 | x |\n",
            MarkdownUtils.tableRow("line1\nline2", "x"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testTableRowEmptyThrows()
    {
        MarkdownUtils.tableRow();
    }

    // ========== keyValueTable ==========

    @Test
    public void testKeyValueTablePreservesOrderAndEscapes()
    {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Type", "Catalog");
        entries.put("Path", "a|b");
        String table = MarkdownUtils.keyValueTable("Property", "Value", entries);
        assertEquals(
            "| Property | Value |\n| --- | --- |\n| Type | Catalog |\n| Path | a\\|b |\n",
            table);
    }

    @Test
    public void testKeyValueTableEmptyMapIsHeaderOnly()
    {
        String table = MarkdownUtils.keyValueTable("K", "V", new LinkedHashMap<>());
        assertEquals("| K | V |\n| --- | --- |\n", table);
    }

    @Test
    public void testKeyValueTableNullMapIsHeaderOnly()
    {
        assertEquals("| K | V |\n| --- | --- |\n",
            MarkdownUtils.keyValueTable("K", "V", null));
    }

    /** Counts column-delimiter pipes (a backslash-escaped pipe does not count). */
    private static int countUnescapedPipes(String s)
    {
        int count = 0;
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) == '|' && (i == 0 || s.charAt(i - 1) != '\\'))
            {
                count++;
            }
        }
        return count;
    }
}
