/**
 * MCP Server for EDT
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * #996: the matching rule of {@code write_module_source mode=replaceAll} - every whole-word occurrence, literal and
 * case-sensitive, left to right and non-overlapping; parts of longer identifiers untouched; other-case spellings
 * reported, never replaced.
 */
public class BslWholeWordReplaceTest
{
    private static String replaced(String content, String oldText, String newText)
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, oldText, newText);
        assertTrue("expected a rewrite of: " + content, result.replaced());
        return result.newContent();
    }

    // ==================== whole words ====================

    @Test
    public void everyWholeWordOccurrenceIsReplacedAndLongerIdentifiersAreNot()
    {
        String content = "OldName = 1;\nOldNameX = OldName + XOldName + Old_Name1 + OldName2;\nCall(OldName)\n";
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "OldName", "NewName");
        assertEquals("NewName = 1;\nOldNameX = NewName + XOldName + Old_Name1 + OldName2;\nCall(NewName)\n",
            result.newContent());
        assertEquals(3, result.replacements());
        assertEquals("1-3", result.replacedLinesText());
    }

    @Test
    public void matchesAtTheStartAndTheEndOfTheTextAreReplaced()
    {
        assertEquals("New", replaced("Old", "Old", "New"));
        assertEquals("New + New", replaced("Old + Old", "Old", "New"));
    }

    @Test
    public void punctuationNeighboursAreWordBoundaries()
    {
        assertEquals("NewName.Run(); (NewName) NewName;", replaced("OldName.Run(); (OldName) OldName;", "OldName",
            "NewName"));
    }

    /** Cyrillic identifiers have their boundary by construction: a following Cyrillic letter joins the name. */
    @Test
    public void cyrillicNamesKeepTheirWordBoundary()
    {
        String content = "СтароеИмя = 1;\n" // СтароеИмя = 1;
            + "СтароеИмяДлиннее = " // СтароеИмяДлиннее =
            + "СтароеИмя;\n"; // СтароеИмя;
        String result = replaced(content, "СтароеИмя",
            "НовоеИмя"); // НовоеИмя
        assertEquals("НовоеИмя = 1;\n"
            + "СтароеИмяДлиннее = "
            + "НовоеИмя;\n", result);
    }

    /** A name with the letter yo, and a Latin name glued to a Cyrillic letter (one identifier for BSL). */
    @Test
    public void aNameWithYoAndALatinNameFollowedByACyrillicLetter()
    {
        String yo = "ВсёГотово"; // ВсёГотово
        assertEquals("Done = Done;", replaced(yo + " = " + yo + ";", yo, "Done"));
        BslWholeWordReplace.Result glued = BslWholeWordReplace.replace("OldИмя = 1;", "Old", "New"); // OldИмя
        assertFalse("a Cyrillic letter continues the identifier", glued.replaced());
        assertEquals(1, glued.insideIdentifiers());
    }

    @Test
    public void stringLiteralsAndCommentsAreReplacedToo()
    {
        assertEquals("NewName(); // NewName here\nText = \"NewName\";",
            replaced("OldName(); // OldName here\nText = \"OldName\";", "OldName", "NewName"));
    }

    @Test
    public void aMultiLineFragmentIsReplaced()
    {
        assertEquals("A = 1;\nNew();\nB = 2;\nNew();\n",
            replaced("A = 1;\nOld1();\nOld2();\nB = 2;\nOld1();\nOld2();\n", "Old1();\nOld2();", "New();"));
    }

    /** A fragment with a punctuation edge is checked on its identifier edge only. */
    @Test
    public void aPunctuationEdgedFragmentIsCheckedOnItsIdentifierEdgeOnly()
    {
        // ".Run(" starts and ends with punctuation: no boundary is checked at all.
        assertEquals("Old.Go(); OldX.Go();", replaced("Old.Run(); OldX.Run();", ".Run(", ".Go("));
        // "Old.Run(" is checked on its left edge only: XOld.Run( keeps its prefix.
        assertEquals("New.Run(); XOld.Run();", replaced("Old.Run(); XOld.Run();", "Old.Run(", "New.Run("));
    }

    @Test
    public void matchesDoNotOverlap()
    {
        // "aa" as a word: inside "aaa" it is part of a longer identifier; the lone "aa" is replaced.
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("aaa a aa", "aa", "b");
        assertEquals("aaa a b", result.newContent());
        assertEquals(1, result.replacements());
        // "a.a" in "a.a.a": the first occurrence is taken, the overlapping second is not.
        BslWholeWordReplace.Result dotted = BslWholeWordReplace.replace("a.a.a", "a.a", "X");
        assertEquals("X.a", dotted.newContent());
        assertEquals(1, dotted.replacements());
    }

    @Test
    public void theReplacementIsNotScannedAgain()
    {
        assertEquals("AA + AA", replaced("A + A", "A", "AA"));
    }

    @Test
    public void anEmptySourceDeletesTheOccurrences()
    {
        assertEquals("Call(); Call();", replaced("Call(Old); Call(Old);", "Old", ""));
    }

    // ==================== nothing to replace ====================

    @Test
    public void noOccurrenceAtAllSteersToReadingAgain()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("A = 1;\n", "OldName", "NewName");
        assertFalse(result.replaced());
        assertNull(result.newContent());
        assertEquals("oldSource has no whole-word occurrence in the module. Read the module again with "
            + "read_module_source: oldSource must match the text exactly, letter case included. Nothing was written.",
            result.failureMessage());
    }

    @Test
    public void occurrencesOnlyInsideLongerIdentifiersAreNamedWithTheirLines()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("OldNameX = 1;\n\nY = XOldName;\n",
            "OldName", "NewName");
        assertFalse(result.replaced());
        assertEquals("oldSource has no whole-word occurrence in the module. It occurs 2 time(s) inside longer "
            + "identifiers (lines 1, 3); replaceAll never rewrites part of an identifier - use searchReplace for "
            + "those. Nothing was written.", result.failureMessage());
    }

    /** Overlapping occurrences inside one longer identifier are counted once, like the matches themselves. */
    @Test
    public void overlappingOccurrencesInsideOneIdentifierCountOnce()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("aaa", "aa", "b");
        assertFalse(result.replaced());
        assertEquals(1, result.insideIdentifiers());
    }

    @Test
    public void occurrencesOnlyInAnotherCaseAreNamedWithTheirSpelling()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("OLDNAME = 1;\noldname = 2;\noldname();\n",
            "OldName", "NewName");
        assertFalse(result.replaced());
        assertEquals("oldSource has no whole-word occurrence in the module. 3 occurrence(s) differ only by letter "
            + "case: 'OLDNAME' at line 1, 'oldname' at lines 2-3. BSL ignores case: repeat replaceAll with that "
            + "spelling if it is the same identifier. Nothing was written.", result.failureMessage());
    }

    // ==================== letter case ====================

    @Test
    public void anotherCaseIsReportedNeverReplaced()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("OldName = oldname + OldNameX + OLDNAME;",
            "OldName", "NewName");
        assertEquals("NewName = oldname + OldNameX + OLDNAME;", result.newContent());
        assertEquals(1, result.replacements());
        assertEquals(2, result.caseVariants());
        assertEquals("2 whole-word occurrence(s) of the old text in another letter case were left (BSL ignores "
            + "case): 'oldname' at line 1, 'OLDNAME' at line 1. Repeat replaceAll with that spelling if it is the "
            + "same identifier.", result.caseVariantWarning());
    }

    @Test
    public void aCyrillicSpellingInAnotherCaseIsReported()
    {
        String name = "Старое"; // Старое
        String lower = "старое"; // старое
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(name + "();\n" + lower + "();\n", name, "New");
        assertEquals("New();\n" + lower + "();\n", result.newContent());
        assertEquals(1, result.caseVariants());
    }

    @Test
    public void exactMatchesWinOverAnOverlappingOtherCaseMatch()
    {
        // "A.a" (other case) at 0 would overlap the exact "a.a" at 2; the exact one is replaced.
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("A.a.a", "a.a", "X");
        assertEquals("A.X", result.newContent());
        assertEquals(0, result.caseVariants());
    }

    @Test
    public void aFragmentWithoutLettersHasNoCaseVariants()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("1 + 1", "1", "2");
        assertEquals("2 + 2", result.newContent());
        assertNull(result.caseVariantWarning());
    }

    // ==================== lines and bounds ====================

    @Test
    public void replacedLinesCollapseRangesAndCountEachLineOnce()
    {
        String content = "Old\nOld Old\nOld\nx\nOld\n";
        assertEquals("1-3, 5", BslWholeWordReplace.replace(content, "Old", "New").replacedLinesText());
    }

    @Test
    public void replacedLinesStopAtFortyEntries()
    {
        StringBuilder content = new StringBuilder();
        for (int line = 0; line < 45; line++)
        {
            content.append("Old\nx\n"); // lines 1, 3, 5, ... 89: 45 entries, no range
        }
        String text = BslWholeWordReplace.replace(content.toString(), "Old", "New").replacedLinesText();
        assertTrue(text, text.startsWith("1, 3, 5, "));
        assertTrue(text, text.endsWith(", 79 (+5 more)"));
    }

    @Test
    public void collapseRendersSingleLinesAndRanges()
    {
        List<Integer> lines = new ArrayList<>(List.of(12, 40, 41, 42, 97));
        assertEquals("12, 40-42, 97", BslWholeWordReplace.collapse(lines));
        assertEquals("", BslWholeWordReplace.collapse(List.of()));
    }

    @Test
    public void aResultOverTheBoundIsRefused()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("a a a", "a", "bbbb", 10);
        assertFalse(result.replaced());
        assertEquals("replaceAll would make the module 14 characters long, more than the 10 allowed (3 occurrences). "
            + "Nothing was written.", result.failureMessage());
        assertTrue(BslWholeWordReplace.replace("a a a", "a", "bbbb", 14).replaced());
    }

    @Test
    public void theDefaultBoundIsTenMillionCharacters()
    {
        assertEquals(10_000_000, BslWholeWordReplace.MAX_RESULT_LENGTH);
    }
}
