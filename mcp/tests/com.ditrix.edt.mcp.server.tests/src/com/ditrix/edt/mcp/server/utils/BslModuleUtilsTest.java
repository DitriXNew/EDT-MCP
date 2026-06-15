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

    // ========== findMethodViaText / buildTextMethodNotFoundResponse ==========

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
    public void testBuildTextMethodNotFoundResponse()
    {
        String r = BslModuleUtils.buildTextMethodNotFoundResponse(
            "Foo", "CommonModules/X/Module.bsl", List.of("Alpha", "Beta")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue(r.contains("Method 'Foo' not found in CommonModules/X/Module.bsl")); //$NON-NLS-1$
        assertTrue(r.contains("**Available methods** (2)")); //$NON-NLS-1$
        assertTrue(r.contains("- Alpha")); //$NON-NLS-1$
        assertTrue(r.contains("- Beta")); //$NON-NLS-1$
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
            "  #Область ПрограммныйИнтерфейс", // 1 #Область ПрограммныйИнтерфейс //$NON-NLS-1$
            "Code();",                      // 2 //$NON-NLS-1$
            "  #КонецОбласти"); // 3 #КонецОбласти //$NON-NLS-1$
        assertEquals("ПрограммныйИнтерфейс", //$NON-NLS-1$
            BslModuleUtils.findRegionForLine(lines, 2));
    }
}
