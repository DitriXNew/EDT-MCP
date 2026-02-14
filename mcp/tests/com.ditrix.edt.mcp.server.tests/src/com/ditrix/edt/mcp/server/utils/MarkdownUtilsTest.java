/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

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
}
