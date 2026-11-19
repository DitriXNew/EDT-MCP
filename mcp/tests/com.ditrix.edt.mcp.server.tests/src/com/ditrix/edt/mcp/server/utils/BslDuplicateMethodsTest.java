/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * #987: the repeated-method-name reader behind write_module_source, read_method_source and get_module_structure -
 * which repetitions are certain (one preprocessor context), which are excluded by #If arms and which depend on the
 * conditions, and what a write creates against what a module already had.
 */
public class BslDuplicateMethodsTest
{
    private static List<String> lines(String text)
    {
        return List.of(text.split("\n", -1)); //$NON-NLS-1$
    }

    // ==================== find ====================

    @Test
    public void twoTopLevelFunctionsOfOneNameAreACertainDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Function F(P)\n\tReturn P;\nEndFunction\n\nProcedure Other()\nEndProcedure\n\n" //$NON-NLS-1$
                + "Function F(P)\n\tReturn 2;\nEndFunction")); //$NON-NLS-1$
        assertEquals(1, groups.size());
        BslDuplicateMethods.Group group = groups.get(0);
        assertEquals("F", group.name); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, group.kind);
        assertEquals("1, 8", group.lines()); //$NON-NLS-1$
        assertEquals("1-3", group.declarations.get(0).range()); //$NON-NLS-1$
        assertEquals("8-10", group.declarations.get(1).range()); //$NON-NLS-1$
    }

    /** The Russian keywords and a different case of the name: still one name, the platform's rule. */
    @Test
    public void russianKeywordsAndAnotherCaseOfTheNameAreTheSameName()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Функция ВыборкаДанных(Параметр) Экспорт\n\tВозврат 1;\nКонецФункции\n\n" //$NON-NLS-1$
                + "функция выборкаданных(Параметр)\n\tВозврат 2;\nконецфункции")); //$NON-NLS-1$
        assertEquals(1, groups.size());
        assertEquals("ВыборкаДанных", groups.get(0).name); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
    }

    /** A Procedure and a Function of one name collide too: the platform's namespace is one. */
    @Test
    public void aProcedureAndAFunctionOfOneNameCollide()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Procedure Same()\nEndProcedure\nFunction Same()\nEndFunction")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
    }

    @Test
    public void declarationsInDifferentArmsOfOneIfBlockExcludeEachOther()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "#Если Сервер Тогда\nПроцедура Ф()\nКонецПроцедуры\n#Иначе\nПроцедура Ф()\nКонецПроцедуры\n#КонецЕсли")); //$NON-NLS-1$
        assertEquals(1, groups.size());
        assertEquals(BslDuplicateMethods.Kind.EXCLUSIVE, groups.get(0).kind);
        assertEquals(1, groups.get(0).branchBlockLine);
        assertTrue(groups.get(0).meaning(), groups.get(0).meaning().contains("opened at line 1")); //$NON-NLS-1$
    }

    @Test
    public void anElsIfArmExcludesTooInEnglish()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Procedure Top()\nEndProcedure\n#If Server Then\nProcedure P()\nEndProcedure\n" //$NON-NLS-1$
                + "#ElsIf Client Then\nProcedure P()\nEndProcedure\n#EndIf")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.EXCLUSIVE, groups.get(0).kind);
        assertEquals(3, groups.get(0).branchBlockLine);
    }

    /** Two separate #If blocks: a text scan cannot tell whether both conditions hold at once. */
    @Test
    public void separateIfBlocksAreOnlyAPossibleDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "#If Server Then\nProcedure P()\nEndProcedure\n#EndIf\n#If Client Then\nProcedure P()\nEndProcedure\n#EndIf")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.POSSIBLE, groups.get(0).kind);
    }

    /** Wherever the conditional one is compiled, the top-level one is compiled with it. */
    @Test
    public void aTopLevelDeclarationAndOneInsideAnIfBlockAreACertainDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Procedure P()\nEndProcedure\n#If Server Then\nProcedure P()\nEndProcedure\n#EndIf")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
    }

    @Test
    public void twoDeclarationsInTheSameArmAreACertainDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "#If Server Then\nProcedure P()\nEndProcedure\nProcedure P()\nEndProcedure\n#Else\n#EndIf")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
    }

    /** Nested blocks: different arms of the OUTER block exclude even when the inner paths differ. */
    @Test
    public void differentArmsOfAnOuterBlockExcludeAcrossNestedBlocks()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "#If Server Then\n#If ExternalConnection Then\nProcedure P()\nEndProcedure\n#EndIf\n" //$NON-NLS-1$
                + "#Else\nProcedure P()\nEndProcedure\n#EndIf")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.EXCLUSIVE, groups.get(0).kind);
        assertEquals(1, groups.get(0).branchBlockLine);
    }

    /** Different compilation directives: whether both compile together is the platform's call, not the text's. */
    @Test
    public void differentCompilationDirectivesAreOnlyAPossibleDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "&НаКлиенте\nПроцедура П()\nКонецПроцедуры\n\n&НаСервере\nПроцедура П()\nКонецПроцедуры")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.POSSIBLE, groups.get(0).kind);
    }

    /** One directive in two dialects is one directive; an interception annotation does not count. */
    @Test
    public void theSameDirectiveInTwoDialectsIsACertainDuplicate()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "&НаКлиенте\nПроцедура П()\nКонецПроцедуры\n\n&AtClient\n&Before(\"X\")\nProcedure п()\nEndProcedure")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
    }

    /** A worst pair decides the group: two excluded copies plus a third at top level is a certain duplicate. */
    @Test
    public void theWorstPairDecidesTheGroup()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "#If Server Then\nProcedure P()\nEndProcedure\n#Else\nProcedure P()\nEndProcedure\n#EndIf\n" //$NON-NLS-1$
                + "Procedure P()\nEndProcedure")); //$NON-NLS-1$
        assertEquals(BslDuplicateMethods.Kind.DUPLICATE, groups.get(0).kind);
        assertEquals(0, groups.get(0).branchBlockLine);
        assertEquals(3, groups.get(0).declarations.size());
    }

    /** A declaration inside a comment or a string is not one. */
    @Test
    public void aDeclarationInACommentOrAStringIsNotCounted()
    {
        assertTrue(BslDuplicateMethods.find(lines(
            "Procedure P()\nEndProcedure\n// Procedure P()\nText = \"\n|Procedure P()\";")).isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void groupOfIgnoresCase()
    {
        List<BslDuplicateMethods.Group> groups = BslDuplicateMethods.find(lines(
            "Procedure Abc()\nEndProcedure\nProcedure ABC()\nEndProcedure")); //$NON-NLS-1$
        assertNotNull(BslDuplicateMethods.groupOf(groups, "abc")); //$NON-NLS-1$
        assertNull(BslDuplicateMethods.groupOf(groups, "Other")); //$NON-NLS-1$
    }

    // ==================== conditionalBranches ====================

    @Test
    public void conditionalBranchesNameTheBlockAndTheArmOfEveryLine()
    {
        int[][] paths = BslSyntaxChecker.conditionalBranches(lines(
            "A\n#If Server Then\nB\n#If Client Then\nC\n#EndIf\n#ElsIf X Then\nD\n#Else\nE\n#EndIf\nF")); //$NON-NLS-1$
        assertArrayEquals(new int[0], paths[0]);
        assertArrayEquals(new int[0], paths[1]);
        assertArrayEquals(new int[] {2, 0}, paths[2]);
        assertArrayEquals(new int[] {2, 0, 4, 0}, paths[4]);
        assertArrayEquals(new int[] {2, 0}, paths[5]);
        assertArrayEquals(new int[] {2, 1}, paths[7]);
        assertArrayEquals(new int[] {2, 2}, paths[9]);
        assertArrayEquals(new int[0], paths[11]);
    }

    @Test
    public void separatingBlockTellsTheThreeRelationsApart()
    {
        assertEquals(0, BslDuplicateMethods.separatingBlock(new int[0], new int[] {3, 0}));
        assertEquals(0, BslDuplicateMethods.separatingBlock(new int[] {3, 1}, new int[] {3, 1}));
        assertEquals(3, BslDuplicateMethods.separatingBlock(new int[] {3, 0, 5, 0}, new int[] {3, 1}));
        assertEquals(-1, BslDuplicateMethods.separatingBlock(new int[] {3, 0}, new int[] {9, 0}));
    }

    // ==================== judgeWrite ====================

    private static final String BASE = "Function F(P)\n\tReturn P;\nEndFunction\n\nProcedure Other()\nEndProcedure"; //$NON-NLS-1$

    @Test
    public void aWriteThatAddsASecondDeclarationIsRefusedNamingBothLines()
    {
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(lines(BASE),
            lines(BASE + "\n\nFunction f(P)\n\tReturn 2;\nEndFunction")); //$NON-NLS-1$
        String refusal = verdict.refusal();
        assertNotNull(refusal);
        assertTrue(refusal, refusal.contains("'F' is declared 2 times (lines 1, 8 of the resulting text; " //$NON-NLS-1$
            + "the current module declares it at line 1).")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("Nothing was written")); //$NON-NLS-1$
        assertTrue(refusal, refusal.contains("mode=replaceMethod")); //$NON-NLS-1$
        assertNull(verdict.warning(false));
    }

    @Test
    public void aWriteThatKeepsEveryNameOnceIsNeitherRefusedNorWarned()
    {
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(lines(BASE),
            lines(BASE + "\n\nFunction G()\nEndFunction")); //$NON-NLS-1$
        assertNull(verdict.refusal());
        assertNull(verdict.warning(false));
    }

    /** A legacy module that already repeats a name stays editable: an unrelated edit only warns. */
    @Test
    public void aDuplicateTheModuleAlreadyHadOnlyWarnsOnAnUnrelatedEdit()
    {
        String legacy = BASE + "\n\nFunction F(P)\nEndFunction"; //$NON-NLS-1$
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(lines(legacy),
            lines(legacy.replace("Return P;", "Return P + 1;"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(verdict.refusal());
        String warning = verdict.warning(false);
        assertNotNull(warning);
        assertTrue(warning, warning.contains("already declared a method name twice before this write")); //$NON-NLS-1$
    }

    /** ...but one more copy of that name is refused. */
    @Test
    public void aThirdDeclarationOfAnAlreadyRepeatedNameIsRefused()
    {
        String legacy = BASE + "\n\nFunction F(P)\nEndFunction"; //$NON-NLS-1$
        assertNotNull(BslDuplicateMethods.judgeWrite(lines(legacy),
            lines(legacy + "\n\nFunction F(P)\nEndFunction")).refusal()); //$NON-NLS-1$
    }

    /** Moving one of two excluded copies out of its #If arm makes a certain duplicate: refused, count unchanged. */
    @Test
    public void movingAnExcludedCopyOutOfItsBranchIsRefused()
    {
        String before = "#If Server Then\nProcedure P()\nEndProcedure\n#Else\nProcedure P()\nEndProcedure\n#EndIf"; //$NON-NLS-1$
        String after = "#If Server Then\nProcedure P()\nEndProcedure\n#Else\n#EndIf\nProcedure P()\nEndProcedure"; //$NON-NLS-1$
        assertNotNull(BslDuplicateMethods.judgeWrite(lines(before), lines(after)).refusal());
    }

    @Test
    public void copiesInExcludingBranchesAreNeitherRefusedNorWarned()
    {
        String after = "#Если Сервер Тогда\nПроцедура Ф()\nКонецПроцедуры\n#Иначе\nПроцедура Ф()\nКонецПроцедуры\n#КонецЕсли"; //$NON-NLS-1$
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(List.of(), lines(after));
        assertNull(verdict.refusal());
        assertNull(verdict.warning(false));
    }

    @Test
    public void aPossibleDuplicateTheWriteCreatesIsWarnedNotRefused()
    {
        String after = "#If Server Then\nProcedure P()\nEndProcedure\n#EndIf\n#If Client Then\nProcedure P()\nEndProcedure\n#EndIf"; //$NON-NLS-1$
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(List.of(), lines(after));
        assertNull(verdict.refusal());
        String warning = verdict.warning(false);
        assertNotNull(warning);
        assertTrue(warning, warning.startsWith("Possible duplicate method name: 'P' is declared 2 times")); //$NON-NLS-1$
        // A possible duplicate the module already had says nothing on the next edit.
        assertNull(BslDuplicateMethods.judgeWrite(lines(after), lines(after + "\n")).warning(false)); //$NON-NLS-1$
    }

    @Test
    public void aCertainDuplicateWrittenWithTheCheckSkippedIsWarned()
    {
        BslDuplicateMethods.WriteVerdict verdict = BslDuplicateMethods.judgeWrite(lines(BASE),
            lines(BASE + "\nFunction F()\nEndFunction")); //$NON-NLS-1$
        String warning = verdict.warning(true);
        assertNotNull(warning);
        assertTrue(warning, warning.startsWith("skipSyntaxCheck=true wrote a duplicate method name: 'F'")); //$NON-NLS-1$
        assertNull("without the skip, the refusal says it", verdict.warning(false)); //$NON-NLS-1$
    }
}
