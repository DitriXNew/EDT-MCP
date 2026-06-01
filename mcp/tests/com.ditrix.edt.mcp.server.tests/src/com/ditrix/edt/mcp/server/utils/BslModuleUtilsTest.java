/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/**
 * Tests for the pure, static doc-comment boundary helpers in
 * {@link BslModuleUtils}.
 * <p>
 * The unified policy is ADJACENCY: a documentation comment must be contiguous
 * and immediately precede the declaration; the first blank line (or any
 * non-comment line) ends the doc block.
 */
public class BslModuleUtilsTest
{
    // ========== findDocCommentStartLine ==========

    @Test
    public void testStartLineCommentImmediatelyAboveIsIncluded()
    {
        List<String> lines = List.of(
            "// Doc line",                  // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 2 //$NON-NLS-1$
        // declaration on line 2 → comment block starts at line 1
        assertEquals(1, BslModuleUtils.findDocCommentStartLine(lines, 2));
    }

    @Test
    public void testStartLineBlankLineSeparatesCommentIsExcluded()
    {
        List<String> lines = List.of(
            "// Doc line",                  // 1 //$NON-NLS-1$
            "",                             // 2 (blank) //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 3 //$NON-NLS-1$
        // blank line ends the block → no adjacent comment → returns declaration line
        assertEquals(3, BslModuleUtils.findDocCommentStartLine(lines, 3));
    }

    @Test
    public void testStartLineMultipleContiguousCommentLines()
    {
        List<String> lines = List.of(
            "// Line one",                  // 1 //$NON-NLS-1$
            "// Line two",                  // 2 //$NON-NLS-1$
            "// Line three",                // 3 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 4 //$NON-NLS-1$
        assertEquals(1, BslModuleUtils.findDocCommentStartLine(lines, 4));
    }

    @Test
    public void testStartLineBlankInMiddleOfBlockKeepsOnlyContiguousPart()
    {
        List<String> lines = List.of(
            "// Far away",                  // 1 //$NON-NLS-1$
            "",                             // 2 (blank breaks the block) //$NON-NLS-1$
            "// Near one",                  // 3 //$NON-NLS-1$
            "// Near two",                  // 4 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 5 //$NON-NLS-1$
        // only the part contiguous to the declaration (lines 3-4) counts
        assertEquals(3, BslModuleUtils.findDocCommentStartLine(lines, 5));
    }

    @Test
    public void testStartLineNoCommentReturnsDeclarationLine()
    {
        List<String> lines = List.of(
            "Procedure Before() EndProcedure", // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");      // 2 //$NON-NLS-1$
        assertEquals(2, BslModuleUtils.findDocCommentStartLine(lines, 2));
    }

    @Test
    public void testStartLineDeclarationAtLineOneReturnsDeclarationLine()
    {
        List<String> lines = List.of(
            "Procedure A() EndProcedure");  // 1 //$NON-NLS-1$
        assertEquals(1, BslModuleUtils.findDocCommentStartLine(lines, 1));
    }

    @Test
    public void testStartLineNullSourceReturnsDeclarationLine()
    {
        assertEquals(5, BslModuleUtils.findDocCommentStartLine(null, 5));
    }

    @Test
    public void testStartLineIndentedCommentIsTrimmedThenMatched()
    {
        List<String> lines = List.of(
            "    // Indented doc",          // 1 (leading whitespace) //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 2 //$NON-NLS-1$
        assertEquals(1, BslModuleUtils.findDocCommentStartLine(lines, 2));
    }

    // ========== extractDocCommentText ==========

    @Test
    public void testExtractTextSingleCommentStripsSlashesAndSpace()
    {
        List<String> lines = List.of(
            "// Hello world",               // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 2 //$NON-NLS-1$
        assertEquals("Hello world", BslModuleUtils.extractDocCommentText(lines, 2));
    }

    @Test
    public void testExtractTextMultiLineJoinsWithSingleSpace()
    {
        List<String> lines = List.of(
            "// Line one",                  // 1 //$NON-NLS-1$
            "// Line two",                  // 2 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 3 //$NON-NLS-1$
        assertEquals("Line one Line two", BslModuleUtils.extractDocCommentText(lines, 3));
    }

    @Test
    public void testExtractTextBlankSeparatedCommentIsNull()
    {
        List<String> lines = List.of(
            "// Doc line",                  // 1 //$NON-NLS-1$
            "",                             // 2 (blank) //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 3 //$NON-NLS-1$
        assertNull(BslModuleUtils.extractDocCommentText(lines, 3));
    }

    @Test
    public void testExtractTextBlankInMiddleKeepsOnlyContiguousPart()
    {
        List<String> lines = List.of(
            "// Far away",                  // 1 //$NON-NLS-1$
            "",                             // 2 (blank) //$NON-NLS-1$
            "// Near one",                  // 3 //$NON-NLS-1$
            "// Near two",                  // 4 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 5 //$NON-NLS-1$
        assertEquals("Near one Near two", BslModuleUtils.extractDocCommentText(lines, 5));
    }

    @Test
    public void testExtractTextNoCommentIsNull()
    {
        List<String> lines = List.of(
            "Procedure A() EndProcedure");  // 1 //$NON-NLS-1$
        assertNull(BslModuleUtils.extractDocCommentText(lines, 1));
    }

    @Test
    public void testExtractTextCommentWithoutLeadingSpacePreserved()
    {
        List<String> lines = List.of(
            "//NoSpace",                    // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 2 //$NON-NLS-1$
        // only the "//" is stripped; no optional space to drop
        assertEquals("NoSpace", BslModuleUtils.extractDocCommentText(lines, 2));
    }

    @Test
    public void testExtractTextNullSourceIsNull()
    {
        assertNull(BslModuleUtils.extractDocCommentText(null, 5));
    }
}
