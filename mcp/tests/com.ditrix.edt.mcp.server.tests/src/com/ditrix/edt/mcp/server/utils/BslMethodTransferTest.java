/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * #978: the pure line operations behind copying named methods out of a module and deleting named methods -
 * the method boundaries are those of the method-targeted write modes, every refusal comes before any result,
 * and the copied text is the source's, line for line.
 */
public class BslMethodTransferTest
{
    private static final String SOURCE = "CommonModules/Calc/Module.bsl"; //$NON-NLS-1$

    private static List<String> lines(String... lines)
    {
        return new ArrayList<>(Arrays.asList(lines));
    }

    // ==================== validateNames ====================

    @Test
    public void namesAreRequired()
    {
        assertTrue(BslMethodTransfer.validateNames(null).contains("methodNames is required")); //$NON-NLS-1$
        assertTrue(BslMethodTransfer.validateNames(List.of()).contains("methodNames is required")); //$NON-NLS-1$
    }

    @Test
    public void upToAHundredNamesAreAcceptedAndOneMoreIsRefused()
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < BslMethodTransfer.MAX_METHOD_NAMES; i++)
        {
            names.add("M" + i); //$NON-NLS-1$
        }
        assertNull(BslMethodTransfer.validateNames(names));
        names.add("Extra"); //$NON-NLS-1$
        String error = BslMethodTransfer.validateNames(names);
        assertTrue(error, error.contains("at most 100 names, got 101")); //$NON-NLS-1$
    }

    @Test
    public void aNameRepeatedIgnoringCaseIsRefused()
    {
        String error = BslMethodTransfer.validateNames(List.of("Add", "Test", "ADD")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(error, error.contains("'ADD' twice")); //$NON-NLS-1$
        String russian = BslMethodTransfer.validateNames(List.of("Маркер", "маркер")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(russian, russian.contains("twice")); //$NON-NLS-1$
    }

    @Test
    public void aBlankNameIsRefused()
    {
        String error = BslMethodTransfer.validateNames(List.of("Add", "")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("empty name at position 2")); //$NON-NLS-1$
    }

    // ==================== extract ====================

    @Test
    public void theMethodsAreCopiedInRequestOrderWithTheirPreambleSeparatedByOneBlankLine()
    {
        List<String> source = lines(
            "// Adds two numbers.", //$NON-NLS-1$
            "&AtServer", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Function Add(A, B) Export", //$NON-NLS-1$
            "\tReturn A + B;  ", //$NON-NLS-1$
            "EndFunction", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Test() Export", //$NON-NLS-1$
            "\tResult = Add(1, 2);", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslMethodTransfer.Result result = BslMethodTransfer.extract(source, List.of("test", "add"), SOURCE); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(result.error);
        assertEquals(List.of("Test", "Add"), result.methods); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(lines(
            "Procedure Test() Export", //$NON-NLS-1$
            "\tResult = Add(1, 2);", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "&AtServer", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Function Add(A, B) Export", //$NON-NLS-1$
            "\tReturn A + B;  ", //$NON-NLS-1$
            "EndFunction"), result.lines); //$NON-NLS-1$
    }

    @Test
    public void aRussianMethodIsFoundByItsRussianName()
    {
        List<String> source = lines(
            "&НаСервере", //$NON-NLS-1$
            "Функция Маркер() Экспорт", //$NON-NLS-1$
            "\tВозврат 1;", //$NON-NLS-1$
            "КонецФункции"); //$NON-NLS-1$
        BslMethodTransfer.Result result = BslMethodTransfer.extract(source, List.of("маркер"), SOURCE); //$NON-NLS-1$
        assertNull(result.error);
        assertEquals(List.of("Маркер"), result.methods); //$NON-NLS-1$
        assertEquals(source, result.lines);
    }

    @Test
    public void theCopiedTextIsNotNormalized()
    {
        // An en dash and a no-break space: a source write would normalize them; a copy moves them as they are.
        List<String> source = lines("Procedure P()", "\tX = \"a–b c\";", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(source, BslMethodTransfer.extract(source, List.of("P"), SOURCE).lines); //$NON-NLS-1$
    }

    @Test
    public void everyMissingNameIsNamedAndTheAvailableMethodsAreListed()
    {
        List<String> source = lines("Procedure A()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        BslMethodTransfer.Result result = BslMethodTransfer.extract(source, List.of("B", "A", "C"), SOURCE); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.failed());
        assertTrue(result.error, result.error.startsWith("Methods 'B', 'C' not found in fromModulePath")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Nothing was written")); //$NON-NLS-1$
        assertTrue(result.error, result.error.contains("Available methods: A.")); //$NON-NLS-1$
        assertNull(result.lines);
    }

    @Test
    public void aNameDeclaredInTwoPreprocessorBranchesIsAmbiguous()
    {
        List<String> source = lines(
            "#If Server Then", //$NON-NLS-1$
            "Procedure P()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "#Else", //$NON-NLS-1$
            "Procedure P()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "#EndIf"); //$NON-NLS-1$
        String error = BslMethodTransfer.extract(source, List.of("P"), SOURCE).error; //$NON-NLS-1$
        assertTrue(error, error.contains("declared 2 times")); //$NON-NLS-1$
    }

    @Test
    public void anUnterminatedMethodIsRefused()
    {
        List<String> source = lines("Procedure P()", "\tX = 1;", "Procedure Q()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        String error = BslMethodTransfer.extract(source, List.of("P"), SOURCE).error; //$NON-NLS-1$
        assertTrue(error, error.contains("no matching terminator")); //$NON-NLS-1$
    }

    @Test
    public void aMethodInsideAnIfBlockIsRefusedInBothLanguages()
    {
        List<String> english = lines(
            "Procedure Out()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "#If Server Then", //$NON-NLS-1$
            "Procedure In()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "#EndIf"); //$NON-NLS-1$
        String error = BslMethodTransfer.extract(english, List.of("In"), SOURCE).error; //$NON-NLS-1$
        assertTrue(error, error.contains("#If block (opened at line 3)")); //$NON-NLS-1$
        assertNull("a method outside the block is copied", //$NON-NLS-1$
            BslMethodTransfer.extract(english, List.of("Out"), SOURCE).error); //$NON-NLS-1$
        List<String> russian = lines(
            "#Если Сервер Тогда", //$NON-NLS-1$
            "Процедура Внутри()", //$NON-NLS-1$
            "КонецПроцедуры", //$NON-NLS-1$
            "#КонецЕсли"); //$NON-NLS-1$
        String russianError = BslMethodTransfer.extract(russian, List.of("Внутри"), SOURCE).error; //$NON-NLS-1$
        assertTrue(russianError, russianError.contains("opened at line 1")); //$NON-NLS-1$
    }

    @Test
    public void aMethodWhoseBodyHoldsAWholeIfBlockIsCopied()
    {
        List<String> source = lines(
            "Procedure P()", //$NON-NLS-1$
            "#If Server Then", //$NON-NLS-1$
            "\tX = 1;", //$NON-NLS-1$
            "#EndIf", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        assertNull(BslMethodTransfer.extract(source, List.of("P"), SOURCE).error); //$NON-NLS-1$
    }

    @Test
    public void aMethodAfterAClosedIfBlockIsCopied()
    {
        List<String> source = lines(
            "#If Server Then", //$NON-NLS-1$
            "X = 1;", //$NON-NLS-1$
            "#EndIf", //$NON-NLS-1$
            "Procedure P()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        assertNull(BslMethodTransfer.extract(source, List.of("P"), SOURCE).error); //$NON-NLS-1$
    }

    @Test
    public void anInterceptionAnnotationIsRefusedInBothSpellings()
    {
        for (String annotation : List.of("&Before(\"Add\")", "&After(\"Add\")", "&Around(\"Add\")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "&ChangeAndValidate(\"Add\")", "&Перед(\"Add\")", "&После(\"Add\")", "&Вместо(\"Add\")", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "&ИзменениеИКонтроль(\"Add\")", "&around(\"Add\")")) //$NON-NLS-1$ //$NON-NLS-2$
        {
            List<String> source = lines(annotation, "Procedure ext_Add()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
            String error = BslMethodTransfer.extract(source, List.of("ext_Add"), SOURCE).error; //$NON-NLS-1$
            assertTrue(annotation + ": " + error, error != null && error.contains("interception annotation")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void anInterceptionAnnotationAcrossABlankLineIsStillSeen()
    {
        List<String> source = lines("&AtServer", "", "&After(\"Add\")", "", "Procedure ext_Add()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertTrue(BslMethodTransfer.extract(source, List.of("ext_Add"), SOURCE).failed()); //$NON-NLS-1$
    }

    @Test
    public void anOrdinaryAnnotationAndALookAlikeAreCopied()
    {
        List<String> source = lines("&AtServerNoContext", "&BeforeWrite", "Procedure P()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(BslMethodTransfer.extract(source, List.of("P"), SOURCE).error); //$NON-NLS-1$
    }

    @Test
    public void anUnaddressableSourceIsRefused()
    {
        List<String> source = lines("Procedure P", "()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String error = BslMethodTransfer.extract(source, List.of("P"), SOURCE).error; //$NON-NLS-1$
        assertTrue(error, error != null && error.contains("cannot address")); //$NON-NLS-1$
    }

    // ==================== targetConflict ====================

    @Test
    public void aNameTheTargetAlreadyDeclaresIsRefused()
    {
        List<String> target = lines("Function ADD(A, B)", "EndFunction"); //$NON-NLS-1$ //$NON-NLS-2$
        String error = BslMethodTransfer.targetConflict(target, List.of("Test", "Add")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(error, error.contains("cannot copy 'Add'")); //$NON-NLS-1$
        assertTrue(error, error.contains("already declares it")); //$NON-NLS-1$
        assertNull(BslMethodTransfer.targetConflict(target, List.of("Test"))); //$NON-NLS-1$
        assertNull(BslMethodTransfer.targetConflict(Collections.emptyList(), List.of("Add"))); //$NON-NLS-1$
    }

    // ==================== remove ====================

    @Test
    public void aDeletedMethodTakesItsPreambleAndTheSeamKeepsOneBlankLine()
    {
        List<String> module = lines(
            "Procedure A()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "// Doc of B.", //$NON-NLS-1$
            "&AtServer", //$NON-NLS-1$
            "Procedure B()", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure C()", //$NON-NLS-1$
            "EndProcedure"); //$NON-NLS-1$
        BslMethodTransfer.Result result = BslMethodTransfer.remove(module, List.of("b")); //$NON-NLS-1$
        assertNull(result.error);
        assertEquals(List.of("B"), result.methods); //$NON-NLS-1$
        assertEquals(lines("Procedure A()", "EndProcedure", "", "Procedure C()", "EndProcedure"), result.lines); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertTrue(result.stillCalled.isEmpty());
    }

    @Test
    public void deletingAtTheModuleEdgesLeavesNoBlankLineThere()
    {
        List<String> module = lines("Procedure A()", "EndProcedure", "", "Procedure B()", "EndProcedure", "", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "Procedure C()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(lines("Procedure B()", "EndProcedure"), //$NON-NLS-1$ //$NON-NLS-2$
            BslMethodTransfer.remove(module, List.of("A", "C")).lines); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(), BslMethodTransfer.remove(lines("Procedure A()", "EndProcedure", ""), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            List.of("A")).lines); //$NON-NLS-1$
    }

    @Test
    public void aRegionAroundTheDeletedMethodStays()
    {
        List<String> module = lines("#Region Public", "", "Procedure A()", "EndProcedure", "", "#EndRegion"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertEquals(lines("#Region Public", "", "#EndRegion"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            BslMethodTransfer.remove(module, List.of("A")).lines); //$NON-NLS-1$
    }

    @Test
    public void aDeletedNameStillCalledIsReportedOnceWithItsLine()
    {
        List<String> module = lines(
            "Функция Add(A, B) Экспорт", //$NON-NLS-1$
            "\tВозврат A + B;", //$NON-NLS-1$
            "КонецФункции", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Процедура Test() Экспорт", //$NON-NLS-1$
            "\t// Add(1) in a comment does not count", //$NON-NLS-1$
            "\tX = \"Add(2)\";", //$NON-NLS-1$
            "\tY = Other.Add(3);", //$NON-NLS-1$
            "\tРезультат = add (1, 2);", //$NON-NLS-1$
            "КонецПроцедуры"); //$NON-NLS-1$
        BslMethodTransfer.Result result = BslMethodTransfer.remove(module, List.of("Add")); //$NON-NLS-1$
        assertNull(result.error);
        assertEquals(List.of("Add (line 5)"), result.stillCalled); //$NON-NLS-1$
    }

    @Test
    public void aDeletedNameCalledOnlyQualifiedOrInTextIsNotReported()
    {
        List<String> module = lines("Procedure A()", "EndProcedure", "", "Procedure B()", "\tM.A();", "\t// A()", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            "\tAB = 1;", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(BslMethodTransfer.remove(module, List.of("A")).stillCalled.isEmpty()); //$NON-NLS-1$
    }

    @Test
    public void deletionRefusesAMissingOrAmbiguousNameAndKeepsTheModule()
    {
        List<String> module = lines("Procedure A()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$
        BslMethodTransfer.Result missing = BslMethodTransfer.remove(module, List.of("A", "Z")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(missing.error, missing.error.startsWith("Method 'Z' not found in the module")); //$NON-NLS-1$
        assertNull(missing.lines);
    }

    @Test
    public void deletionOfAnInterceptingMethodIsAllowed()
    {
        List<String> module = lines("&After(\"Add\")", "Procedure ext_Add()", "EndProcedure"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        BslMethodTransfer.Result result = BslMethodTransfer.remove(module, List.of("ext_Add")); //$NON-NLS-1$
        assertNull(result.error);
        assertEquals(List.of(), result.lines);
    }

    // ==================== BslSyntaxChecker.openConditionalLines ====================

    @Test
    public void openConditionalLinesTracksNestingAndElseBranches()
    {
        List<String> module = lines(
            "X = 1;", //$NON-NLS-1$
            "#If Server Then", //$NON-NLS-1$
            "A = 1;", //$NON-NLS-1$
            "#Region R", //$NON-NLS-1$
            "#If Client Then", //$NON-NLS-1$
            "B = 1;", //$NON-NLS-1$
            "#EndIf", //$NON-NLS-1$
            "#EndRegion", //$NON-NLS-1$
            "#Else", //$NON-NLS-1$
            "C = 1;", //$NON-NLS-1$
            "#EndIf", //$NON-NLS-1$
            "D = 1;"); //$NON-NLS-1$
        int[] open = BslSyntaxChecker.openConditionalLines(module);
        assertEquals(0, open[0]);
        assertEquals(0, open[1]);
        assertEquals(2, open[2]);
        assertEquals(5, open[5]);
        assertEquals(2, open[6]);
        assertEquals(2, open[9]);
        assertEquals(0, open[10]);
        assertEquals(0, open[11]);
        assertFalse(open.length != module.size());
    }
}
