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

    /** Review #999 (2): a cased letter outside the BMP - Deseret capital/small long I - is seen by code point. */
    @Test
    public void aSupplementaryLetterInAnotherCaseIsReported()
    {
        String capital = "𐐀"; // U+10400 DESERET CAPITAL LETTER LONG I
        String small = "𐐨"; // U+10428 DESERET SMALL LETTER LONG I
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(small + " = 1;", capital, "X");
        assertFalse(result.replaced());
        assertEquals(1, result.caseVariants());
        BslWholeWordReplace.Result glued = BslWholeWordReplace.replace(capital + small + " = 1;", capital, "X");
        assertFalse("a following supplementary letter continues the identifier", glued.replaced());
        assertEquals(0, glued.caseVariants());
    }

    /**
     * Review #999 (3): a long fragment that nearly matches at every offset of a repetitive module is scanned in
     * linear time - the other-case scan compared almost the whole fragment at each offset.
     */
    @Test(timeout = 20_000)
    public void aLongNearMatchInAnotherCaseIsScannedInLinearTime()
    {
        String content = "a".repeat(2_000_000);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "A".repeat(200_000) + "B", "X");
        assertFalse(result.replaced());
        assertEquals(0, result.caseVariants());
    }

    /** Review #999 (3): the same for the exact scan - a fragment that matches all but its last character. */
    @Test(timeout = 20_000)
    public void aLongNearMatchIsScannedInLinearTime()
    {
        String content = "a".repeat(3_000_000);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "a".repeat(1_000_000) + "b", "X");
        assertFalse(result.replaced());
        assertEquals(0, result.insideIdentifiers());
    }

    /**
     * Local review of #999: the scans read the module in place - no char[] copy of it, no folded copy. Measured as
     * the bytes this thread allocates during a no-match scan of a 4,000,000-character module (a copy alone is 8 MB).
     */
    @Test
    public void aScanDoesNotCopyTheModule() throws Exception
    {
        Object threads = java.lang.management.ManagementFactory.getThreadMXBean();
        Class<?> extended;
        try
        {
            extended = ClassLoader.getPlatformClassLoader().loadClass("com.sun.management.ThreadMXBean"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            org.junit.Assume.assumeNoException("no per-thread allocation counter in this JVM", e); //$NON-NLS-1$
            return;
        }
        org.junit.Assume.assumeTrue("no per-thread allocation counter in this JVM", extended.isInstance(threads)); //$NON-NLS-1$
        java.lang.reflect.Method allocated = extended.getMethod("getCurrentThreadAllocatedBytes"); //$NON-NLS-1$
        String content = "x".repeat(4_000_000); //$NON-NLS-1$
        BslWholeWordReplace.replace("x y", "Old", "New"); // warm up the classes outside the measurement //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        long before = (Long) allocated.invoke(threads);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "Old", "New"); //$NON-NLS-1$ //$NON-NLS-2$
        long used = (Long) allocated.invoke(threads) - before;
        assertFalse(result.replaced());
        assertTrue("a no-match scan allocated " + used + " bytes", used < 2_000_000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Review #999 round 2 (1): many short matches are counted and listed in bounded memory - no boxed offset per match,
     * no line table of the module. 5,000,000 matches in a 10,000,000-character module; the result itself is 10 MB.
     */
    @Test
    public void manyMatchesAreCountedWithoutAnOffsetPerMatch() throws Exception
    {
        Object threads = java.lang.management.ManagementFactory.getThreadMXBean();
        Class<?> extended;
        try
        {
            extended = ClassLoader.getPlatformClassLoader().loadClass("com.sun.management.ThreadMXBean"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            org.junit.Assume.assumeNoException("no per-thread allocation counter in this JVM", e); //$NON-NLS-1$
            return;
        }
        org.junit.Assume.assumeTrue("no per-thread allocation counter in this JVM", extended.isInstance(threads)); //$NON-NLS-1$
        java.lang.reflect.Method allocated = extended.getMethod("getCurrentThreadAllocatedBytes"); //$NON-NLS-1$
        String content = "a\n".repeat(5_000_000); //$NON-NLS-1$
        BslWholeWordReplace.replace("a\na\n", "a", "b"); // warm up outside the measurement //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        long before = (Long) allocated.invoke(threads);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "a", "b"); //$NON-NLS-1$ //$NON-NLS-2$
        long used = (Long) allocated.invoke(threads) - before;
        assertEquals(5_000_000, result.replacements());
        assertEquals("1-5000000", result.replacedLinesText()); //$NON-NLS-1$
        assertTrue("5,000,000 matches allocated " + used + " bytes", used < 64_000_000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Local review of #999: a long fragment builds its failure table once, shared by every scan - here 3,000,000
     * characters, so one table is 12 MB and its two copies of the fragment 12 MB more.
     */
    @Test
    public void aLongFragmentBuildsItsFailureTableOnce() throws Exception
    {
        Object threads = java.lang.management.ManagementFactory.getThreadMXBean();
        Class<?> extended;
        try
        {
            extended = ClassLoader.getPlatformClassLoader().loadClass("com.sun.management.ThreadMXBean"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            org.junit.Assume.assumeNoException("no per-thread allocation counter in this JVM", e); //$NON-NLS-1$
            return;
        }
        org.junit.Assume.assumeTrue("no per-thread allocation counter in this JVM", extended.isInstance(threads)); //$NON-NLS-1$
        java.lang.reflect.Method allocated = extended.getMethod("getCurrentThreadAllocatedBytes"); //$NON-NLS-1$
        String fragment = "y".repeat(3_000_000); //$NON-NLS-1$
        BslWholeWordReplace.replace("x y", "y", "z"); // warm up outside the measurement //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        long before = (Long) allocated.invoke(threads);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("x = 1;", fragment, "z"); //$NON-NLS-1$ //$NON-NLS-2$
        long used = (Long) allocated.invoke(threads) - before;
        assertFalse(result.replaced());
        assertTrue("a 3,000,000-character fragment allocated " + used + " bytes", used < 30_000_000); //$NON-NLS-1$ //$NON-NLS-2$
    }
    /**
     * The other-case scan has its own failure table when the folded fragment differs from it: "A-a" folds to "a-a",
     * which overlaps itself, so the candidate after one rejected inside "xa-a" is still found.
     */
    @Test
    public void anOtherCaseMatchOverlappingARejectedOneIsFound()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("xa-a-A", "A-a", "Z");
        assertFalse(result.replaced());
        assertEquals(1, result.caseVariants());
    }

    /**
     * Local review of #999: other-case hits are compared with the spellings already recorded in place - a copy is
     * made only for a new spelling. 2,000,000 hits of one spelling in a 4,000,000-character module.
     */
    @Test
    public void manyOtherCaseHitsOfOneSpellingAreNotCopied() throws Exception
    {
        Object threads = java.lang.management.ManagementFactory.getThreadMXBean();
        Class<?> extended;
        try
        {
            extended = ClassLoader.getPlatformClassLoader().loadClass("com.sun.management.ThreadMXBean"); //$NON-NLS-1$
        }
        catch (ClassNotFoundException e)
        {
            org.junit.Assume.assumeNoException("no per-thread allocation counter in this JVM", e); //$NON-NLS-1$
            return;
        }
        org.junit.Assume.assumeTrue("no per-thread allocation counter in this JVM", extended.isInstance(threads)); //$NON-NLS-1$
        java.lang.reflect.Method allocated = extended.getMethod("getCurrentThreadAllocatedBytes"); //$NON-NLS-1$
        String content = "\u1E9E ".repeat(2_000_000); // capital sharp s
        BslWholeWordReplace.replace("\u1E9E \u1E9E", "\u00DF", "x"); // warm up outside the measurement
        long before = (Long) allocated.invoke(threads);
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace(content, "\u00DF", "x");
        long used = (Long) allocated.invoke(threads) - before;
        assertEquals(2_000_000, result.caseVariants());
        assertTrue("2,000,000 other-case hits allocated " + used + " bytes", used < 8_000_000); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /** Past five recorded spellings the further hits are counted, not named. */
    @Test
    public void hitsInSpellingsPastTheFifthAreCounted()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("Abc aBc abC ABc aBC AbC ABC ABC", "abc", "x"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(8, result.caseVariants());
        assertTrue(result.caseVariantWarning(), result.caseVariantWarning().contains(
            "'Abc' at line 1, 'aBc' at line 1, 'abC' at line 1, 'ABc' at line 1, 'aBC' at line 1 and 3 more in other spellings.")); //$NON-NLS-1$
    }
    /** Several spellings are still told apart, and those past the listed ones are counted. */
    @Test
    public void spellingsPastTheListedOnesAreCounted()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("aB Ab AB aB Ab", "ab", "x"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(5, result.caseVariants());
        assertTrue(result.caseVariantWarning(), result.caseVariantWarning().contains("'aB' at line 1, 'Ab' at line 1, 'AB' at line 1.")); //$NON-NLS-1$
    }
    /** The listing stays capped while the count covers every line: 45 separate lines, 40 listed. */
    @Test
    public void theListingIsCappedWhileTheCountCoversEveryLine()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("Old\nx\n".repeat(45), "Old", "New"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(45, result.replacements());
        assertTrue(result.replacedLinesText(), result.replacedLinesText().endsWith(", 79 (+5 more)")); //$NON-NLS-1$
    }

    /**
     * Review #999 round 2 (2): a letter whose case pair maps only one way - the small sharp s has no single-character
     * capital, the capital U+1E9E lowercases to it - is still reported in the other case.
     */
    @Test
    public void aReverseOnlyCaseMappingIsReported()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("ẞ = 1;", "ß", "X"); // ẞ / ß
        assertFalse(result.replaced());
        assertEquals(1, result.caseVariants());
    }

    /** Review #999 round 2 (3): a lone surrogate in oldSource never matches half of a valid pair in the module. */
    @Test
    public void aLoneSurrogateNeverSplitsAPair()
    {
        String letter = "𐐀"; // U+10400, one identifier letter in two chars
        BslWholeWordReplace.Result high = BslWholeWordReplace.replace(letter + " = 1;", "\uD801", "X");
        assertFalse("a high surrogate alone must not match the first half of a pair", high.replaced());
        BslWholeWordReplace.Result low = BslWholeWordReplace.replace(letter + " = 1;", "\uDC00", "X");
        assertFalse("a low surrogate alone must not match the second half of a pair", low.replaced());
        BslWholeWordReplace.Result ending = BslWholeWordReplace.replace("a" + letter + " = 1;", "a\uD801", "X");
        assertFalse("a fragment must not end inside a pair", ending.replaced());
        assertEquals(0, ending.insideIdentifiers() + ending.caseVariants());
    }

    /** An unpaired surrogate in the MODULE is a char like any other and can still be replaced. */
    @Test
    public void anUnpairedSurrogateInTheModuleIsReplaceable()
    {
        assertEquals("X = 1;", replaced("\uD801 = 1;", "\uD801", "X"));
    }

    /** An occurrence that starts inside one rejected as part of an identifier is still found. */
    @Test
    public void aMatchStartingInsideARejectedOneIsFound()
    {
        assertEquals("Xa.N", replaced("Xa.a.a", "a.a", "N"));
    }

    /** A fragment that overlaps itself is found right after a false start ("..x" at offset 1 of "...x"). */
    @Test
    public void aSelfOverlappingFragmentIsFoundAfterAFalseStart()
    {
        assertEquals(".N", replaced("...x", "..x", "N"));
    }

    /** Every offset of a repetitive text matching in another case still yields non-overlapping, whole-word spans. */
    @Test
    public void repetitiveOtherCaseMatchesDoNotOverlap()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("AA.AA.AA", "aa.aa", "X");
        assertFalse(result.replaced());
        assertEquals(1, result.caseVariants());
    }

    /** Review #999 (5): a bare carriage return ends a line, as it does for read_module_source and the write. */
    @Test
    public void bareCarriageReturnsSeparateLines()
    {
        BslWholeWordReplace.Result result = BslWholeWordReplace.replace("A = 1;\rOld = 2;\rx\rOld = 3;\r", "Old", "New");
        assertEquals("2, 4", result.replacedLinesText());
        BslWholeWordReplace.Result mixed = BslWholeWordReplace.replace("A\r\nB\nC\rOld\n", "Old", "New");
        assertEquals("4", mixed.replacedLinesText());
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
