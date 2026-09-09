/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
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

    @Test
    public void testExtractTextDeclarationAtLineOneIsNull()
    {
        // declarationLine1Based <= 1 → findDocCommentStartLine returns the line
        // itself, so there is no preceding comment block to extract
        List<String> lines = List.of(
            "// Doc above nothing",          // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");   // 2 //$NON-NLS-1$
        assertNull(BslModuleUtils.extractDocCommentText(lines, 1));
    }

    @Test
    public void testExtractTextTrailingSlashesAreKeptAfterFirstTwo()
    {
        // only the first "//" is stripped; an inner "//" stays in the text
        List<String> lines = List.of(
            "// see http://example",         // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");   // 2 //$NON-NLS-1$
        assertEquals("see http://example", BslModuleUtils.extractDocCommentText(lines, 2)); //$NON-NLS-1$
    }

    @Test
    public void testExtractTextEmptyCommentLineYieldsEmptyToken()
    {
        // a bare "//" with nothing after it contributes an empty token joined by space
        List<String> lines = List.of(
            "// First",                      // 1 //$NON-NLS-1$
            "//",                            // 2 (empty comment body) //$NON-NLS-1$
            "// Third",                      // 3 //$NON-NLS-1$
            "Procedure A() EndProcedure");   // 4 //$NON-NLS-1$
        assertEquals("First  Third", BslModuleUtils.extractDocCommentText(lines, 4)); //$NON-NLS-1$
    }

    // ========== findDocCommentStartLine — boundary branches ==========

    @Test
    public void testStartLineDeclarationLineZeroReturnedAsIs()
    {
        // declarationLine1Based <= 1 short-circuits and returns the input unchanged
        List<String> lines = List.of(
            "// Doc line",                  // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 2 //$NON-NLS-1$
        assertEquals(0, BslModuleUtils.findDocCommentStartLine(lines, 0));
    }

    @Test
    public void testStartLineNegativeDeclarationLineReturnedAsIs()
    {
        assertEquals(-3, BslModuleUtils.findDocCommentStartLine(null, -3));
    }

    @Test
    public void testStartLineNonCommentLineImmediatelyAboveExcluded()
    {
        // the line above the declaration is code, not a comment → block is empty
        List<String> lines = List.of(
            "// distant doc",               // 1 //$NON-NLS-1$
            "Var Counter;",                 // 2 (code, breaks adjacency) //$NON-NLS-1$
            "Procedure A() EndProcedure");  // 3 //$NON-NLS-1$
        assertEquals(3, BslModuleUtils.findDocCommentStartLine(lines, 3));
    }

    // ========== findMethodViaText / buildTextMethodNotFoundResponse ==========

    @Test
    public void testMethodEndPatternRequiresKeywordBoundary()
    {
        assertTrue(BslModuleUtils.METHOD_END_PATTERN.matcher("EndProcedure;").find()); //$NON-NLS-1$
        assertTrue(BslModuleUtils.METHOD_END_PATTERN.matcher(
            "\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438 // done").find()); //$NON-NLS-1$
        assertFalse(BslModuleUtils.METHOD_END_PATTERN.matcher("EndProcedureResult = 1;").find()); //$NON-NLS-1$
        assertFalse(BslModuleUtils.METHOD_END_PATTERN.matcher("EndFunctionValue = 1;").find()); //$NON-NLS-1$
        assertFalse(BslModuleUtils.METHOD_END_PATTERN.matcher(
            "\u041a\u043e\u043d\u0435\u0446\u041f\u0440\u043e\u0446\u0435\u0434\u0443\u0440\u044b\u0420\u0435\u0437\u0443\u043b\u044c\u0442\u0430\u0442 = 1;").find()); //$NON-NLS-1$
        assertFalse(BslModuleUtils.METHOD_END_PATTERN.matcher(
            "\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438\u0417\u043d\u0430\u0447\u0435\u043d\u0438\u0435 = 1;").find()); //$NON-NLS-1$
    }

    @Test
    public void testTerminatorMustOwnItsWholeLine()
    {
        assertTrue(BslModuleUtils.isMethodTerminatorLine(
            "EndProcedure", BslModuleUtils.PROCEDURE_END_PATTERN)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine(
            "\tEndProcedure;", BslModuleUtils.PROCEDURE_END_PATTERN)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine(
            "EndProcedure // done", BslModuleUtils.PROCEDURE_END_PATTERN)); //$NON-NLS-1$
        assertTrue(BslModuleUtils.isMethodTerminatorLine(
            "\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438 // done", //$NON-NLS-1$
            BslModuleUtils.FUNCTION_END_PATTERN));
        // BSL puts module-level statements after the methods, so this line is valid code in
        // which the terminator does NOT end the line - and a whole-line span would carry it.
        assertFalse(BslModuleUtils.isMethodTerminatorLine(
            "EndProcedure; ModuleValue = DangerousCall();", //$NON-NLS-1$
            BslModuleUtils.PROCEDURE_END_PATTERN));
        assertFalse(BslModuleUtils.isMethodTerminatorLine(
            "EndProcedureResult = 1;", BslModuleUtils.PROCEDURE_END_PATTERN)); //$NON-NLS-1$
        assertFalse(BslModuleUtils.isMethodTerminatorLine(
            null, BslModuleUtils.PROCEDURE_END_PATTERN));
    }

    @Test
    public void testMethodSpanStopsAtTheNextDeclaration()
    {
        List<String> lines = List.of(
            "Procedure Target()", //$NON-NLS-1$
            "// Other documentation", //$NON-NLS-1$
            "Procedure Other()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(lines);
        assertEquals(2, spans.size());
        assertFalse("an unterminated method must not borrow the next method's terminator", //$NON-NLS-1$
            spans.get(0).complete);
        assertEquals(1, spans.get(0).endLine);
        assertTrue(spans.get(1).complete);
        assertEquals(1, spans.get(1).startLine);
        assertEquals(3, spans.get(1).endLine);
    }

    @Test
    public void testAnnotationSeparatedByBlankLineStaysWithTheDeclaration()
    {
        // Whitespace is a hidden terminal in the BSL grammar and Procedure carries its
        // pragmas directly, so the directive still binds across the blank line.
        List<String> lines = List.of(
            "&AtClient", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        assertEquals(0, BslModuleUtils.findMethodSpansViaText(lines).get(0).startLine);
    }

    /**
     * Comments are hidden trivia too, so a comment between the directive and the blank line does
     * not detach the directive either: the whole group belongs to the declaration below it.
     */
    @Test
    public void testAnnotationAboveACommentAndABlankLineIsStillOwned()
    {
        List<String> lines = List.of(
            "&AtClient", //$NON-NLS-1$
            "// explanation", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        assertEquals(0, BslModuleUtils.findMethodSpansViaText(lines).get(0).startLine);
    }

    /**
     * Crossing a blank line reaches the ANNOTATION, not everything above it: a comment on the far
     * side of the blank reads as the previous method's trailing note just as easily, and claiming
     * it would let replaceMethod delete somebody else's line.
     */
    @Test
    public void testACommentAboveACrossedAnnotationIsNotClaimed()
    {
        List<String> lines = List.of(
            "// footer for the previous method", //$NON-NLS-1$
            "&AtClient", //$NON-NLS-1$
            "// explanation", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        assertEquals(1, BslModuleUtils.findMethodSpansViaText(lines).get(0).startLine);
    }

    /**
     * The terminator search stops at a declaration whose opening parenthesis sits on the NEXT
     * line: BSL hides the newline, so such a method is real, and an unterminated method above it
     * would otherwise borrow its terminator and report itself complete - the span that deletes a
     * whole neighbouring method.
     */
    @Test
    public void testSpanStopsAtADeclarationSplitAcrossLines()
    {
        List<String> lines = List.of(
            "Procedure Target()", //$NON-NLS-1$
            "Procedure Other", //$NON-NLS-1$
            "()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(lines);
        assertEquals(1, spans.size());
        assertFalse("an unterminated method must not borrow the split declaration's terminator", //$NON-NLS-1$
            spans.get(0).complete);
        assertEquals(0, spans.get(0).endLine);
    }

    @Test
    public void testBlankLineStillDetachesACommentBlock()
    {
        List<String> lines = List.of(
            "// detached documentation", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        assertEquals(2, BslModuleUtils.findMethodSpansViaText(lines).get(0).startLine);
    }

    @Test
    public void testBlankRunOverCodeDoesNotExtendThePreamble()
    {
        List<String> lines = List.of(
            "ModuleValue = 1;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        assertEquals(2, BslModuleUtils.findMethodSpansViaText(lines).get(0).startLine);
    }

    @Test
    public void testMethodSpanIncludesContiguousDocCommentAndAnnotations()
    {
        List<String> lines = List.of(
            "// unrelated", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "// owned documentation", //$NON-NLS-1$
            "&AtClient", //$NON-NLS-1$
            "&Before(\"Base\")", //$NON-NLS-1$
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        List<BslModuleUtils.MethodSpan> spans = BslModuleUtils.findMethodSpansViaText(lines);
        assertEquals(1, spans.size());
        BslModuleUtils.MethodSpan span = spans.get(0);
        assertEquals(2, span.startLine);
        assertEquals(5, span.declarationLine);
        assertEquals(6, span.endLine);
        assertTrue(span.complete);
    }

    @Test
    public void testMethodSpanDoesNotStopAtTerminatorPrefixIdentifier()
    {
        List<String> lines = List.of(
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedureResult = 1;", //$NON-NLS-1$
            "StillInside = 2;", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$

        BslModuleUtils.MethodSpan span = BslModuleUtils.findMethodSpansViaText(lines).get(0);
        assertTrue(span.complete);
        assertEquals(3, span.endLine);
    }

    @Test
    public void testMethodSpanReportsMissingRealTerminator()
    {
        List<String> lines = List.of(
            "Procedure Target()", //$NON-NLS-1$
            "EndProcedureResult = 1;"); //$NON-NLS-1$

        BslModuleUtils.MethodSpan span = BslModuleUtils.findMethodSpansViaText(lines).get(0);
        assertFalse(span.complete);
        assertEquals(1, span.endLine);
    }

    @Test
    public void testFindMethodViaTextLocatesFunctionWithDocComment()
    {
        List<String> lines = List.of(
            "Procedure Alpha()",            // 0 //$NON-NLS-1$
            "EndProcedure",                 // 1 //$NON-NLS-1$
            "",                             // 2 //$NON-NLS-1$
            "// Returns one.",              // 3 //$NON-NLS-1$
            "Function Beta() Export",       // 4 //$NON-NLS-1$
            "  Return 1;",                  // 5 //$NON-NLS-1$
            "EndFunction");                 // 6 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Beta"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertTrue(tm.isFunction);
        assertEquals("Beta", tm.matchedName); //$NON-NLS-1$
        assertEquals(3, tm.startLine); // doc-comment at line 3 included (0-indexed)
        assertEquals(6, tm.endLine);   // EndFunction
        assertEquals(2, tm.allMethodNames.size());
    }

    @Test
    public void testFindMethodViaTextProcedureNoDocComment()
    {
        List<String> lines = List.of(
            "Procedure Alpha()",            // 0 //$NON-NLS-1$
            "EndProcedure");                // 1 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Alpha"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertFalse(tm.isFunction);
        assertEquals(0, tm.startLine);
        assertEquals(1, tm.endLine);
    }

    @Test
    public void testFindMethodViaTextCaseInsensitive()
    {
        List<String> lines = List.of(
            "Procedure Alpha()",            //$NON-NLS-1$
            "EndProcedure");                //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "alpha"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertEquals("Alpha", tm.matchedName); //$NON-NLS-1$
    }

    @Test
    public void testFindMethodViaTextNotFoundCollectsNames()
    {
        List<String> lines = List.of(
            "Procedure Alpha()",            //$NON-NLS-1$
            "EndProcedure",                 //$NON-NLS-1$
            "Function Beta()",              //$NON-NLS-1$
            "EndFunction");                 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Missing"); //$NON-NLS-1$
        assertFalse(tm.found);
        assertEquals(2, tm.allMethodNames.size());
        assertEquals("Alpha", tm.allMethodNames.get(0)); //$NON-NLS-1$
        assertEquals("Beta", tm.allMethodNames.get(1)); //$NON-NLS-1$
    }

    @Test
    public void testFindMethodViaTextUnterminatedClampsToLastLine()
    {
        List<String> lines = List.of(
            "Procedure Alpha()",            // 0 //$NON-NLS-1$
            "  DoSomething();");            // 1 (no EndProcedure) //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Alpha"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertEquals(1, tm.endLine);
    }

    @Test
    public void testFindMethodViaTextRussianKeywordsAndExportFunction()
    {
        // exercises the Cyrillic \u0424\u0443\u043d\u043a\u0446\u0438\u044f/\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438 alternations and the Export flag
        List<String> lines = List.of(
            "\u0424\u0443\u043d\u043a\u0446\u0438\u044f \u0421\u0443\u043c\u043c\u0430(\u0410, \u0411) \u042d\u043a\u0441\u043f\u043e\u0440\u0442", // \u0424\u0443\u043d\u043a\u0446\u0438\u044f \u0421\u0443\u043c\u043c\u0430(\u0410, \u0411) \u042d\u043a\u0441\u043f\u043e\u0440\u0442 //$NON-NLS-1$
            "  \u0412\u043e\u0437\u0432\u0440\u0430\u0442 \u0410 + \u0411;", // \u0412\u043e\u0437\u0432\u0440\u0430\u0442 \u0410 + \u0411; //$NON-NLS-1$
            "\u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438"); // \u041a\u043e\u043d\u0435\u0446\u0424\u0443\u043d\u043a\u0446\u0438\u0438 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "\u0421\u0443\u043c\u043c\u0430"); // \u0421\u0443\u043c\u043c\u0430 //$NON-NLS-1$
        assertTrue(tm.found);
        assertTrue(tm.isFunction);
        assertEquals("\u0421\u0443\u043c\u043c\u0430", tm.matchedName); // \u0421\u0443\u043c\u043c\u0430 //$NON-NLS-1$
        assertEquals(0, tm.startLine);
        assertEquals(2, tm.endLine);
    }

    @Test
    public void testFindMethodViaTextMatchesSecondMethodNotFirst()
    {
        // the requested method is the second declaration: startLine must point at it,
        // not at the first method, and only its own End* terminates the block
        List<String> lines = List.of(
            "Procedure Alpha()",            // 0 //$NON-NLS-1$
            "EndProcedure",                 // 1 //$NON-NLS-1$
            "Function Beta() Export",       // 2 //$NON-NLS-1$
            "  Return 1;",                  // 3 //$NON-NLS-1$
            "EndFunction");                 // 4 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Beta"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertTrue(tm.isFunction);
        assertEquals(2, tm.startLine);
        assertEquals(4, tm.endLine);
        assertEquals(2, tm.allMethodNames.size());
    }

    @Test
    public void testFindMethodViaTextMultiContiguousDocCommentIncluded()
    {
        // a multi-line, blank-separated doc block: only the contiguous part is included
        List<String> lines = List.of(
            "// stray header",              // 0 //$NON-NLS-1$
            "",                             // 1 (blank breaks adjacency) //$NON-NLS-1$
            "// near one",                  // 2 //$NON-NLS-1$
            "// near two",                  // 3 //$NON-NLS-1$
            "Procedure Gamma()",            // 4 //$NON-NLS-1$
            "EndProcedure");                // 5 //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Gamma"); //$NON-NLS-1$
        assertTrue(tm.found);
        assertFalse(tm.isFunction);
        assertEquals(2, tm.startLine); // contiguous doc block begins at line index 2
        assertEquals(5, tm.endLine);
    }

    @Test
    public void testFindMethodViaTextEmptyInputNotFoundEmptyNames()
    {
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(List.of(), "Whatever"); //$NON-NLS-1$
        assertFalse(tm.found);
        assertEquals(-1, tm.startLine);
        assertEquals(-1, tm.endLine);
        assertNull(tm.matchedName);
        assertTrue(tm.allMethodNames.isEmpty());
    }

    @Test
    public void testBuildTextMethodNotFoundResponse()
    {
        String r = BslModuleUtils.buildTextMethodNotFoundResponse(
            "Foo", "CommonModules/X/Module.bsl", List.of("Alpha", "Beta")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(r.contains("Method 'Foo' not found in CommonModules/X/Module.bsl")); //$NON-NLS-1$
        assertTrue(r.contains("**Available methods** (2)")); //$NON-NLS-1$
        assertTrue(r.contains("- Alpha")); //$NON-NLS-1$
        assertTrue(r.contains("- Beta")); //$NON-NLS-1$
    }

    @Test
    public void testBuildTextMethodNotFoundResponseEmptyListReportsZero()
    {
        String r = BslModuleUtils.buildTextMethodNotFoundResponse(
            "Foo", "CommonModules/X/Module.bsl", List.of()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(r.contains("Method 'Foo' not found in CommonModules/X/Module.bsl")); //$NON-NLS-1$
        assertTrue(r.contains("**Available methods** (0)")); //$NON-NLS-1$
        // no method bullets when the list is empty
        assertFalse(r.contains("\n- ")); //$NON-NLS-1$
    }

    // ========== resolveModuleFile source-folder resolution (A27) ==========
    //
    // "src" is the EDT convention (and EDT's own SRC_FOLDER_NAME), so it is tried
    // first; resolution only scans other top-level folders when the module is not
    // under src/, and returns the conventional src/ handle when found nowhere.

    @Test
    public void testResolveModuleFileUsesSrcWhenPresent()
    {
        IProject project = mock(IProject.class);
        IFile srcFile = mock(IFile.class);
        when(project.getFile(any(IPath.class))).thenReturn(srcFile);
        when(srcFile.exists()).thenReturn(true);

        IFile result = BslModuleUtils.resolveModuleFile(project, "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$

        assertSame("must resolve under src/ when the file exists there", srcFile, result); //$NON-NLS-1$
    }

    @Test
    public void testResolveModuleFileFallsBackToOtherTopLevelFolder() throws Exception
    {
        IProject project = mock(IProject.class);
        IFile srcFile = mock(IFile.class);
        when(project.getFile(any(IPath.class))).thenReturn(srcFile);
        when(srcFile.exists()).thenReturn(false);

        IFolder otherFolder = mock(IFolder.class);
        when(otherFolder.getType()).thenReturn(IResource.FOLDER);
        when(otherFolder.getName()).thenReturn("source"); //$NON-NLS-1$
        IFile candidate = mock(IFile.class);
        when(otherFolder.getFile(any(IPath.class))).thenReturn(candidate);
        when(candidate.exists()).thenReturn(true);
        when(project.members()).thenReturn(new IResource[] {otherFolder});

        IFile result = BslModuleUtils.resolveModuleFile(project, "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$

        assertSame("must fall back to the non-src folder holding the module", candidate, result); //$NON-NLS-1$
    }

    @Test
    public void testResolveModuleFileReturnsSrcHandleWhenNotFoundAnywhere() throws Exception
    {
        IProject project = mock(IProject.class);
        IFile srcFile = mock(IFile.class);
        when(project.getFile(any(IPath.class))).thenReturn(srcFile);
        when(srcFile.exists()).thenReturn(false);
        when(project.members()).thenReturn(new IResource[0]);

        IFile result = BslModuleUtils.resolveModuleFile(project, "CommonModules/Foo/Module.bsl"); //$NON-NLS-1$

        assertSame("must return the conventional src/ handle when nothing matches", srcFile, result); //$NON-NLS-1$
    }

    @Test
    public void testResolveModuleFileNullModulePathIsNull()
    {
        // the empty/null modulePath guard returns before the project is dereferenced
        assertNull(BslModuleUtils.resolveModuleFile(null, null));
    }

    @Test
    public void testResolveModuleFileEmptyModulePathIsNull()
    {
        assertNull(BslModuleUtils.resolveModuleFile(null, "")); //$NON-NLS-1$
    }

    @Test
    public void testResolveModuleFileAbsolutePathNotInWorkspaceIsNull()
    {
        // an absolute path takes the workspace-lookup branch; a path that maps to no
        // workspace file yields an empty result array → null (no project access needed)
        IFile result = BslModuleUtils.resolveModuleFile(
            null, "C:\\nonexistent\\path\\does\\not\\exist\\Module.bsl"); //$NON-NLS-1$
        assertNull(result);
    }

    @Test
    public void testResolveModuleFileAbsolutePathWithNullProjectIsNull()
    {
        // the absolute-path branch is taken BEFORE the project null-check, so a null
        // project is tolerated when the path is absolute
        IFile result = BslModuleUtils.resolveModuleFile(
            null, "/var/tmp/missing/Module.bsl"); //$NON-NLS-1$
        assertNull(result);
    }

    @Test
    public void testResolveModuleFileNullProjectRelativePathIsNull()
    {
        // a relative path with a null project: the absolute heuristic fails, then the
        // project null-guard returns null
        assertNull(BslModuleUtils.resolveModuleFile(null, "CommonModules/Foo/Module.bsl")); //$NON-NLS-1$
    }

    // ========== looksLikeAbsolutePath ==========

    @Test
    public void testLooksLikeAbsolutePathNullIsFalse()
    {
        assertFalse(BslModuleUtils.looksLikeAbsolutePath(null));
    }

    @Test
    public void testLooksLikeAbsolutePathEmptyIsFalse()
    {
        assertFalse(BslModuleUtils.looksLikeAbsolutePath("")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathForwardSlashIsTrue()
    {
        assertTrue(BslModuleUtils.looksLikeAbsolutePath("/home/user/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathBackslashIsTrue()
    {
        assertTrue(BslModuleUtils.looksLikeAbsolutePath("\\\\server\\share\\Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathWindowsDriveIsTrue()
    {
        assertTrue(BslModuleUtils.looksLikeAbsolutePath("C:\\projects\\Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathRelativeIsFalse()
    {
        assertFalse(BslModuleUtils.looksLikeAbsolutePath("CommonModules/Foo/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathSingleCharIsFalse()
    {
        // length < 2 and not starting with a slash → cannot be a drive prefix
        assertFalse(BslModuleUtils.looksLikeAbsolutePath("C")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathColonAsSecondCharIsTrue()
    {
        // the heuristic only inspects the second char for a colon (drive-like)
        assertTrue(BslModuleUtils.looksLikeAbsolutePath("z:")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathLeadingBackslashIsTrue()
    {
        // a single leading backslash is enough (not only the UNC double form)
        assertTrue(BslModuleUtils.looksLikeAbsolutePath("\\relative\\Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testLooksLikeAbsolutePathTwoCharNonColonIsFalse()
    {
        // length >= 2 but the second char is not a colon → not drive-like
        assertFalse(BslModuleUtils.looksLikeAbsolutePath("ab")); //$NON-NLS-1$
    }

    // ========== extractModulePath ==========

    @Test
    public void testExtractModulePathNullReturnsUnknown()
    {
        assertEquals("Unknown module", BslModuleUtils.extractModulePath(null)); //$NON-NLS-1$
    }

    @Test
    public void testExtractModulePathStripsSrcPrefix()
    {
        assertEquals("CommonModules/Foo/Module.bsl", //$NON-NLS-1$
            BslModuleUtils.extractModulePath("MyProject/src/CommonModules/Foo/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModulePathReturnsAsIsWhenNoSrcMarker()
    {
        // no "/src/" segment → the path is returned unchanged
        assertEquals("CommonModules/Foo/Module.bsl", //$NON-NLS-1$
            BslModuleUtils.extractModulePath("CommonModules/Foo/Module.bsl")); //$NON-NLS-1$
    }

    @Test
    public void testExtractModulePathUsesFirstSrcMarker()
    {
        // indexOf finds the first "/src/"; the remainder (including a later src/) is kept
        assertEquals("a/src/b/Module.bsl", //$NON-NLS-1$
            BslModuleUtils.extractModulePath("Proj/src/a/src/b/Module.bsl")); //$NON-NLS-1$
    }

    // ========== findRegionForLine ==========

    @Test
    public void testFindRegionNullLinesIsNull()
    {
        assertNull(BslModuleUtils.findRegionForLine(null, 1));
    }

    @Test
    public void testFindRegionTargetLineBelowOneIsNull()
    {
        List<String> lines = List.of(
            "#Region Public",               // 1 //$NON-NLS-1$
            "#EndRegion");                  // 2 //$NON-NLS-1$
        assertNull(BslModuleUtils.findRegionForLine(lines, 0));
    }

    @Test
    public void testFindRegionCodeLineInsideSingleRegion()
    {
        List<String> lines = List.of(
            "#Region Public",               // 1 //$NON-NLS-1$
            "Procedure A() EndProcedure",   // 2 //$NON-NLS-1$
            "#EndRegion");                  // 3 //$NON-NLS-1$
        assertEquals("Public", BslModuleUtils.findRegionForLine(lines, 2)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionReturnsInnermostWhenNested()
    {
        List<String> lines = List.of(
            "#Region Outer",                // 1 //$NON-NLS-1$
            "#Region Inner",                // 2 //$NON-NLS-1$
            "SomeCode();",                  // 3 //$NON-NLS-1$
            "#EndRegion",                   // 4 //$NON-NLS-1$
            "#EndRegion");                  // 5 //$NON-NLS-1$
        assertEquals("Inner", BslModuleUtils.findRegionForLine(lines, 3)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionAfterInnerClosedReturnsOuter()
    {
        List<String> lines = List.of(
            "#Region Outer",                // 1 //$NON-NLS-1$
            "#Region Inner",                // 2 //$NON-NLS-1$
            "#EndRegion",                   // 3 (closes Inner) //$NON-NLS-1$
            "Code();",                      // 4 //$NON-NLS-1$
            "#EndRegion");                  // 5 //$NON-NLS-1$
        assertEquals("Outer", BslModuleUtils.findRegionForLine(lines, 4)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionLineOutsideAnyRegionIsNull()
    {
        List<String> lines = List.of(
            "Procedure A() EndProcedure",   // 1 (before any region) //$NON-NLS-1$
            "#Region Public",               // 2 //$NON-NLS-1$
            "#EndRegion");                  // 3 //$NON-NLS-1$
        assertNull(BslModuleUtils.findRegionForLine(lines, 1));
    }

    @Test
    public void testFindRegionTargetOnRegionStartLine()
    {
        List<String> lines = List.of(
            "#Region Outer",                // 1 //$NON-NLS-1$
            "#Region Inner",                // 2 (the region-start line itself) //$NON-NLS-1$
            "#EndRegion",                   // 3 //$NON-NLS-1$
            "#EndRegion");                  // 4 //$NON-NLS-1$
        assertEquals("Inner", BslModuleUtils.findRegionForLine(lines, 2)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionRussianDirectivesAndIndentation()
    {
        List<String> lines = List.of(
            "  #\u041e\u0431\u043b\u0430\u0441\u0442\u044c \u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441", // 1 #\u041e\u0431\u043b\u0430\u0441\u0442\u044c \u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441 //$NON-NLS-1$
            "Code();",                      // 2 //$NON-NLS-1$
            "  #\u041a\u043e\u043d\u0435\u0446\u041e\u0431\u043b\u0430\u0441\u0442\u0438"); // 3 #\u041a\u043e\u043d\u0435\u0446\u041e\u0431\u043b\u0430\u0441\u0442\u0438 //$NON-NLS-1$
        assertEquals("\u041f\u0440\u043e\u0433\u0440\u0430\u043c\u043c\u043d\u044b\u0439\u0418\u043d\u0442\u0435\u0440\u0444\u0435\u0439\u0441", //$NON-NLS-1$
            BslModuleUtils.findRegionForLine(lines, 2));
    }

    @Test
    public void testFindRegionTargetBeyondAllLinesIsNull()
    {
        // no line satisfies lineNum >= targetLine → the loop exits and returns null
        List<String> lines = List.of(
            "#Region Public",               // 1 //$NON-NLS-1$
            "#EndRegion");                  // 2 //$NON-NLS-1$
        assertNull(BslModuleUtils.findRegionForLine(lines, 99));
    }

    @Test
    public void testFindRegionTargetOnEndRegionLineReturnsClosingRegion()
    {
        // when the target lands on the #EndRegion line, the still-open region is reported
        List<String> lines = List.of(
            "#Region Public",               // 1 //$NON-NLS-1$
            "Code();",                      // 2 //$NON-NLS-1$
            "#EndRegion");                  // 3 (target) //$NON-NLS-1$
        assertEquals("Public", BslModuleUtils.findRegionForLine(lines, 3)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionStrayEndRegionWithEmptyStackBeforeTarget()
    {
        // a stray #EndRegion with an empty stack must not pop anything; a later code
        // line inside a real region still resolves correctly
        List<String> lines = List.of(
            "#EndRegion",                   // 1 (stray, empty stack) //$NON-NLS-1$
            "#Region Public",               // 2 //$NON-NLS-1$
            "Code();",                      // 3 (target) //$NON-NLS-1$
            "#EndRegion");                  // 4 //$NON-NLS-1$
        assertEquals("Public", BslModuleUtils.findRegionForLine(lines, 3)); //$NON-NLS-1$
    }

    @Test
    public void testFindRegionEmptyLinesIsNull()
    {
        // empty source: the loop never runs and the method returns null
        assertNull(BslModuleUtils.findRegionForLine(List.of(), 1));
    }

    // ---- async declarations (Async/\u0410\u0441\u0438\u043D\u0445 prefix) --------------------------------

    @Test
    public void testFindMethodViaTextAsyncProcedureEnglish()
    {
        List<String> lines = List.of(
            "Async Procedure Foo() Export", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Foo"); //$NON-NLS-1$
        assertTrue("an Async Procedure declaration must be discoverable", tm.found); //$NON-NLS-1$
    }

    @Test
    public void testFindMethodViaTextAsyncFunctionRussian()
    {
        List<String> lines = List.of(
            "\u0410\u0441\u0438\u043D\u0445 \u0424\u0443\u043D\u043A\u0446\u0438\u044F Bar() \u042D\u043A\u0441\u043F\u043E\u0440\u0442", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438"); //$NON-NLS-1$
        BslModuleUtils.TextMethod tm = BslModuleUtils.findMethodViaText(lines, "Bar"); //$NON-NLS-1$
        assertTrue("an async (Russian) Function declaration must be discoverable", tm.found); //$NON-NLS-1$
    }

    @Test
    public void testFuncKeywordPatternAcceptsAsyncPrefix()
    {
        // The classifier must agree with METHOD_START_PATTERN on async declarations, or an
        // `Async Function` found by the text fallback would be reported as a procedure.
        assertTrue(BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher("Async Function Foo(") //$NON-NLS-1$
            .find());
        assertTrue(BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher(
            "\u0410\u0441\u0438\u043D\u0445 \u0424\u0443\u043D\u043A\u0446\u0438\u044F Bar(") //$NON-NLS-1$
            .find());
        assertFalse("a procedure must still not classify as a function", //$NON-NLS-1$
            BslModuleUtils.FUNC_KEYWORD_PATTERN.matcher("Async Procedure Baz(").find()); //$NON-NLS-1$
    }
}
