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

    // ========== inlineCode ==========

    /**
     * The defect this exists against: text that is not ours, placed in a document, ending a line
     * and starting a block of its own. Everything below is one property of the result - one line,
     * fenced, nothing outside the fence - so that no test passes by naming a character.
     */
    @Test
    public void testInlineCodeNeverLeavesItsLine()
    {
        String rendered = MarkdownUtils.inlineCode("a\n\n# Injected\n\n| x | y |\nb");

        assertEquals("the result is ONE line: " + rendered, -1, rendered.indexOf('\n'));
        assertEquals(-1, rendered.indexOf('\r'));
    }

    @Test
    public void testInlineCodeNeutralisesEveryUnicodeLineTerminator()
    {
        // The set is defined by a property - "Unicode calls it a line terminator" - and not by a
        // guess at which ones a renderer might honour.
        String rendered = MarkdownUtils.inlineCode("a\u000Bb\u000Cc\u0085d\u2028e\u2029f");

        assertEquals("`a\uFFFDb\uFFFDc\uFFFDd\uFFFDe\uFFFDf`", rendered);
    }

    @Test
    public void testInlineCodeNeutralisesControlCharactersAndDelete()
    {
        assertEquals("`a\uFFFDb\uFFFDc`", MarkdownUtils.inlineCode("a\u0000b\u007Fc"));
    }

    /**
     * The delimiter is the OTHER way out of a code span, and it is closed by CommonMark's own
     * rule - a longer fence - rather than by removing anything from the text.
     */
    @Test
    public void testInlineCodeOutgrowsAnyRunOfBackticks()
    {
        // No padding here: the body neither starts nor ends with a backtick, so nothing could
        // run into the fence.
        assertEquals("``a`b``", MarkdownUtils.inlineCode("a`b"));
        assertEquals("````a```b````", MarkdownUtils.inlineCode("a```b"));
    }

    @Test
    public void testInlineCodeKeepsABacktickOffTheFence()
    {
        // Both ends padded together: CommonMark strips one space from each side only when both
        // are there, so padding one end would keep the other's space in the value.
        assertEquals("`` ` ``", MarkdownUtils.inlineCode("`"));
    }

    @Test
    public void testInlineCodeKeepsTheTextItIsGiven()
    {
        assertEquals("`Main_Other_Ancestor.xml`", MarkdownUtils.inlineCode("Main_Other_Ancestor.xml"));
        // The backslashes are Java escapes for ONE backslash each: a Windows path, with the
        // markup characters that used to be live in the heading beside it.
        assertEquals("`C:\\tmp\\rules.zip!a[b](c)*d*`",
            MarkdownUtils.inlineCode("C:\\tmp\\rules.zip!a[b](c)*d*"));
    }

    @Test
    public void testInlineCodeNeverSplitsASurrogatePair()
    {
        // U+1F600, whose low surrogate is U+DE00 - well above every character this replaces, so
        // there is no code unit here it can touch.
        String emoji = new String(Character.toChars(0x1F600));

        assertEquals("`" + emoji + "`", MarkdownUtils.inlineCode(emoji));
    }

    /**
     * A body that already begins and ends with a space needs the padding too - for the opposite
     * reason to a backtick. CommonMark strips one space from each end of such a span, so an
     * unpadded `` ` x ` `` renders as "x" and the report has quietly edited a value it is
     * reproducing.
     */
    @Test
    public void testInlineCodeKeepsTheSpacesAtBothEndsOfTheText()
    {
        assertEquals("`  x  `", MarkdownUtils.inlineCode(" x "));
    }

    /** A space at ONE end is not stripped, so padding there would ADD one. */
    @Test
    public void testInlineCodeDoesNotPadWhenOnlyOneEndIsASpace()
    {
        assertEquals("` x`", MarkdownUtils.inlineCode(" x"));
        assertEquals("`x `", MarkdownUtils.inlineCode("x "));
    }

    /** ...and a body of nothing but spaces is not stripped either, so it is not padded. */
    @Test
    public void testInlineCodeLeavesAnAllSpaceBodyAlone()
    {
        assertEquals("`   `", MarkdownUtils.inlineCode("   "));
    }

    @Test
    public void testInlineCodeRendersNothingAsAnEmptySpanRatherThanAsBareFences()
    {
        assertEquals("` `", MarkdownUtils.inlineCode(""));
        assertEquals("` `", MarkdownUtils.inlineCode(null));
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
