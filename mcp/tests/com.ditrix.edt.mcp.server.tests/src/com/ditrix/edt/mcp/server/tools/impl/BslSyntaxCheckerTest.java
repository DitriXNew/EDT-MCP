/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 Diversus23 (https://github.com/Diversus23)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker.CheckResult;
import com.ditrix.edt.mcp.server.utils.BslSyntaxChecker;

/**
 * Tests for {@link BslSyntaxChecker}.
 */
public class BslSyntaxCheckerTest
{
    // ==================== Valid code ====================

    @Test
    public void testEmptyInput()
    {
        CheckResult result = BslSyntaxChecker.check(Collections.emptyList());
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    public void testSimpleProcedureEnglish()
    {
        List<String> lines = Arrays.asList(
            "Procedure DoSomething()", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testSimpleProcedureRussian()
    {
        List<String> lines = Arrays.asList(
            "\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u0421\u0434\u0435\u043B\u0430\u0442\u044C()", //$NON-NLS-1$
            "    \u0445 = 1;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testSimpleFunctionEnglish()
    {
        List<String> lines = Arrays.asList(
            "Function GetValue()", //$NON-NLS-1$
            "    Return 42;", //$NON-NLS-1$
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testSimpleFunctionRussian()
    {
        List<String> lines = Arrays.asList(
            "\u0424\u0443\u043D\u043A\u0446\u0438\u044F \u041F\u043E\u043B\u0443\u0447\u0438\u0442\u044C\u0417\u043D\u0430\u0447\u0435\u043D\u0438\u0435()", //$NON-NLS-1$
            "    \u0412\u043E\u0437\u0432\u0440\u0430\u0442 42;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testIfElsIfElseEndIf()
    {
        List<String> lines = Arrays.asList(
            "If x > 0 Then", //$NON-NLS-1$
            "    a = 1;", //$NON-NLS-1$
            "ElsIf x = 0 Then", //$NON-NLS-1$
            "    a = 2;", //$NON-NLS-1$
            "Else", //$NON-NLS-1$
            "    a = 3;", //$NON-NLS-1$
            "EndIf;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testIfElsIfRussian()
    {
        List<String> lines = Arrays.asList(
            "\u0415\u0441\u043B\u0438 \u0445 > 0 \u0422\u043E\u0433\u0434\u0430", //$NON-NLS-1$
            "    \u0430 = 1;", //$NON-NLS-1$
            "\u0418\u043D\u0430\u0447\u0435\u0415\u0441\u043B\u0438 \u0445 = 0 \u0422\u043E\u0433\u0434\u0430", //$NON-NLS-1$
            "    \u0430 = 2;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testWhileLoop()
    {
        List<String> lines = Arrays.asList(
            "While x < 10 Do", //$NON-NLS-1$
            "    x = x + 1;", //$NON-NLS-1$
            "EndDo;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testWhileLoopRussian()
    {
        List<String> lines = Arrays.asList(
            "\u041F\u043E\u043A\u0430 \u0445 < 10 \u0426\u0438\u043A\u043B", //$NON-NLS-1$
            "    \u0445 = \u0445 + 1;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testForLoop()
    {
        List<String> lines = Arrays.asList(
            "For i = 1 To 10 Do", //$NON-NLS-1$
            "    x = x + i;", //$NON-NLS-1$
            "EndDo;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testForEachLoop()
    {
        List<String> lines = Arrays.asList(
            "For Each item In collection Do", //$NON-NLS-1$
            "    Process(item);", //$NON-NLS-1$
            "EndDo;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testForEachLoopRussian()
    {
        List<String> lines = Arrays.asList(
            "\u0414\u043B\u044F \u041A\u0430\u0436\u0434\u043E\u0433\u043E \u044D\u043B\u0435\u043C\u0435\u043D\u0442 \u0418\u0437 \u043A\u043E\u043B\u043B\u0435\u043A\u0446\u0438\u044F \u0426\u0438\u043A\u043B", //$NON-NLS-1$
            "    \u041E\u0431\u0440\u0430\u0431\u043E\u0442\u0430\u0442\u044C(\u044D\u043B\u0435\u043C\u0435\u043D\u0442);", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0426\u0438\u043A\u043B\u0430;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testTryCatch()
    {
        List<String> lines = Arrays.asList(
            "Try", //$NON-NLS-1$
            "    DoSomething();", //$NON-NLS-1$
            "Except", //$NON-NLS-1$
            "    LogError();", //$NON-NLS-1$
            "EndTry;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testTryCatchRussian()
    {
        List<String> lines = Arrays.asList(
            "\u041F\u043E\u043F\u044B\u0442\u043A\u0430", //$NON-NLS-1$
            "    \u0421\u0434\u0435\u043B\u0430\u0442\u044C();", //$NON-NLS-1$
            "\u0418\u0441\u043A\u043B\u044E\u0447\u0435\u043D\u0438\u0435", //$NON-NLS-1$
            "    \u041B\u043E\u0433\u041E\u0448\u0438\u0431\u043A\u0438();", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u043E\u043F\u044B\u0442\u043A\u0438;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    // ==================== Nested structures ====================

    @Test
    public void testNestedBlocks()
    {
        List<String> lines = Arrays.asList(
            "Procedure Main()", //$NON-NLS-1$
            "    If condition Then", //$NON-NLS-1$
            "        For i = 1 To 10 Do", //$NON-NLS-1$
            "            Try", //$NON-NLS-1$
            "                DoWork();", //$NON-NLS-1$
            "            Except", //$NON-NLS-1$
            "                Log();", //$NON-NLS-1$
            "            EndTry;", //$NON-NLS-1$
            "        EndDo;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testMultipleProcedures()
    {
        List<String> lines = Arrays.asList(
            "Procedure First()", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Function Second()", //$NON-NLS-1$
            "    Return 2;", //$NON-NLS-1$
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    // ==================== Comments and skipped lines ====================

    @Test
    public void testCommentLinesAreSkipped()
    {
        List<String> lines = Arrays.asList(
            "// Procedure Fake()", //$NON-NLS-1$
            "Procedure Real()", //$NON-NLS-1$
            "    // EndProcedure", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testMultilineStringContinuation()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    text = \"first line", //$NON-NLS-1$
            "    |Procedure Fake()", //$NON-NLS-1$
            "    |EndProcedure\";", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testInlineCommentIgnored()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test() // some comment", //$NON-NLS-1$
            "    x = 1; // EndProcedure inside comment", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testEmptyLinesAreSkipped()
    {
        List<String> lines = Arrays.asList(
            "", //$NON-NLS-1$
            "Procedure Test()", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    ", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    // ==================== Case insensitivity ====================

    @Test
    public void testCaseInsensitiveEnglish()
    {
        List<String> lines = Arrays.asList(
            "PROCEDURE Test()", //$NON-NLS-1$
            "    IF x THEN", //$NON-NLS-1$
            "    ENDIF;", //$NON-NLS-1$
            "ENDPROCEDURE" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    // ==================== Error cases ====================

    @Test
    public void testUnclosedProcedure()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    x = 1;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Unclosed")); //$NON-NLS-1$
        assertTrue(result.getErrors().get(0).contains("line 1")); //$NON-NLS-1$
    }

    @Test
    public void testUnclosedFunction()
    {
        List<String> lines = Arrays.asList(
            "Function GetValue()", //$NON-NLS-1$
            "    Return 1;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Unclosed")); //$NON-NLS-1$
    }

    @Test
    public void testUnclosedIf()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    If x Then", //$NON-NLS-1$
            "        a = 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertFalse(result.getErrors().isEmpty());
    }

    @Test
    public void testUnclosedWhile()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    While x Do", //$NON-NLS-1$
            "        x = x - 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
    }

    @Test
    public void testUnclosedTry()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Try", //$NON-NLS-1$
            "        DoWork();", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
    }

    @Test
    public void testUnexpectedEndProcedure()
    {
        List<String> lines = Arrays.asList(
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Unexpected")); //$NON-NLS-1$
        assertTrue(result.getErrors().get(0).contains("no matching")); //$NON-NLS-1$
    }

    @Test
    public void testUnexpectedEndFunction()
    {
        List<String> lines = Arrays.asList(
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("Unexpected")); //$NON-NLS-1$
    }

    @Test
    public void testMismatchedProcedureEndFunction()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Mismatched")); //$NON-NLS-1$
    }

    @Test
    public void testMismatchedFunctionEndProcedure()
    {
        List<String> lines = Arrays.asList(
            "Function Test()", //$NON-NLS-1$
            "    Return 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("Mismatched")); //$NON-NLS-1$
    }

    @Test
    public void testMismatchedIfEndDo()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    If x Then", //$NON-NLS-1$
            "        a = 1;", //$NON-NLS-1$
            "    EndDo;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
    }

    @Test
    public void testMultipleErrors()
    {
        List<String> lines = Arrays.asList(
            "Procedure First()", //$NON-NLS-1$
            "    If x Then", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Procedure Second()" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().size() >= 2);
    }

    // ==================== ElsIf should not create new If ====================

    @Test
    public void testElsIfNotCountedAsNewIf()
    {
        List<String> lines = Arrays.asList(
            "If a Then", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "ElsIf b Then", //$NON-NLS-1$
            "    x = 2;", //$NON-NLS-1$
            "ElsIf c Then", //$NON-NLS-1$
            "    x = 3;", //$NON-NLS-1$
            "Else", //$NON-NLS-1$
            "    x = 4;", //$NON-NLS-1$
            "EndIf;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testElseIfVariant()
    {
        List<String> lines = Arrays.asList(
            "If a Then", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "ElseIf b Then", //$NON-NLS-1$
            "    x = 2;", //$NON-NLS-1$
            "EndIf;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testRussianElsIfNotCountedAsNewIf()
    {
        List<String> lines = Arrays.asList(
            "\u0415\u0441\u043B\u0438 \u0430 \u0422\u043E\u0433\u0434\u0430", //$NON-NLS-1$
            "    \u0445 = 1;", //$NON-NLS-1$
            "\u0418\u043D\u0430\u0447\u0435\u0415\u0441\u043B\u0438 \u0431 \u0422\u043E\u0433\u0434\u0430", //$NON-NLS-1$
            "    \u0445 = 2;", //$NON-NLS-1$
            "\u0418\u043D\u0430\u0447\u0435\u0415\u0441\u043B\u0438 \u0432 \u0422\u043E\u0433\u0434\u0430", //$NON-NLS-1$
            "    \u0445 = 3;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0415\u0441\u043B\u0438;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    // ==================== Complex real-world scenario ====================

    @Test
    public void testComplexModule()
    {
        List<String> lines = Arrays.asList(
            "Procedure ProcessData(Data)", //$NON-NLS-1$
            "    If Data = Undefined Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "    For Each Item In Data Do", //$NON-NLS-1$
            "        Try", //$NON-NLS-1$
            "            If Item.IsValid() Then", //$NON-NLS-1$
            "                While Item.HasNext() Do", //$NON-NLS-1$
            "                    Item.Process();", //$NON-NLS-1$
            "                EndDo;", //$NON-NLS-1$
            "            ElsIf Item.CanRetry() Then", //$NON-NLS-1$
            "                Item.Retry();", //$NON-NLS-1$
            "            Else", //$NON-NLS-1$
            "                Item.Skip();", //$NON-NLS-1$
            "            EndIf;", //$NON-NLS-1$
            "        Except", //$NON-NLS-1$
            "            LogError(ErrorDescription());", //$NON-NLS-1$
            "        EndTry;", //$NON-NLS-1$
            "    EndDo;", //$NON-NLS-1$
            "EndProcedure", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "Function Calculate(Value)", //$NON-NLS-1$
            "    If Value > 0 Then", //$NON-NLS-1$
            "        Return Value * 2;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "    Return 0;", //$NON-NLS-1$
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testOnlyComments()
    {
        List<String> lines = Arrays.asList(
            "// This is a comment", //$NON-NLS-1$
            "// Another comment", //$NON-NLS-1$
            "   // Indented comment" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testCodeWithoutBlocks()
    {
        List<String> lines = Arrays.asList(
            "x = 1;", //$NON-NLS-1$
            "y = 2;", //$NON-NLS-1$
            "z = x + y;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testLeadingWhitespace()
    {
        List<String> lines = Arrays.asList(
            "    Procedure Test()", //$NON-NLS-1$
            "        x = 1;", //$NON-NLS-1$
            "    EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testUnexpectedEndDoOnEmptyStack()
    {
        List<String> lines = Arrays.asList(
            "EndDo;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("Unexpected")); //$NON-NLS-1$
    }

    @Test
    public void testUnexpectedEndTryOnEmptyStack()
    {
        List<String> lines = Arrays.asList(
            "EndTry;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("Unexpected")); //$NON-NLS-1$
    }

    @Test
    public void testUnexpectedEndIfOnEmptyStack()
    {
        List<String> lines = Arrays.asList(
            "EndIf;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("Unexpected")); //$NON-NLS-1$
    }

    // ==================== Async procedures/functions (#287) ====================

    @Test
    public void testAsyncProcedureRussian()
    {
        List<String> lines = Arrays.asList(
            "\u0410\u0441\u0438\u043D\u0445 \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u0424(\u041A\u043E\u043C\u0430\u043D\u0434\u0430)", //$NON-NLS-1$
            "    \u0445 = 1;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testAsyncFunctionRussian()
    {
        List<String> lines = Arrays.asList(
            "\u0410\u0441\u0438\u043D\u0445 \u0424\u0443\u043D\u043A\u0446\u0438\u044F \u0424(\u041A\u043E\u043C\u0430\u043D\u0434\u0430)", //$NON-NLS-1$
            "    \u0412\u043E\u0437\u0432\u0440\u0430\u0442 1;", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u0424\u0443\u043D\u043A\u0446\u0438\u0438" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testAsyncProcedureEnglish()
    {
        List<String> lines = Arrays.asList(
            "Async Procedure DoSomethingAsync(Command)", //$NON-NLS-1$
            "    x = 1;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testAsyncFunctionEnglish()
    {
        List<String> lines = Arrays.asList(
            "Async Function GetValueAsync()", //$NON-NLS-1$
            "    Return 1;", //$NON-NLS-1$
            "EndFunction" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testAsyncProcedureWithAwaitInBody()
    {
        List<String> lines = Arrays.asList(
            "\u0410\u0441\u0438\u043D\u0445 \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u0417\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u044C(\u041A\u043E\u043C\u0430\u043D\u0434\u0430)", //$NON-NLS-1$
            "    \u0420\u0435\u0437\u0443\u043B\u044C\u0442\u0430\u0442 = \u0416\u0434\u0430\u0442\u044C \u041F\u043E\u043B\u0443\u0447\u0438\u0442\u044C\u0414\u0430\u043D\u043D\u044B\u0435\u0410\u0441\u0438\u043D\u0445();", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testAsyncProcedureWithClientAnnotation()
    {
        List<String> lines = Arrays.asList(
            "&\u041D\u0430\u041A\u043B\u0438\u0435\u043D\u0442\u0435", //$NON-NLS-1$
            "\u0410\u0441\u0438\u043D\u0445 \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u041E\u0431\u0440\u0430\u0431\u043E\u0442\u0430\u0442\u044C(\u041A\u043E\u043C\u0430\u043D\u0434\u0430)", //$NON-NLS-1$
            "    \u0416\u0434\u0430\u0442\u044C \u041F\u0430\u0443\u0437\u0430(1000);", //$NON-NLS-1$
            "\u041A\u043E\u043D\u0435\u0446\u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u044B" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testUnclosedAsyncProcedureStillInvalid()
    {
        List<String> lines = Arrays.asList(
            "\u0410\u0441\u0438\u043D\u0445 \u041F\u0440\u043E\u0446\u0435\u0434\u0443\u0440\u0430 \u0424()", //$NON-NLS-1$
            "    \u0445 = 1;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().get(0).contains("Unclosed")); //$NON-NLS-1$
    }

    // ==================== Multi-line string literals (#286) ====================

    @Test
    public void testMultilineStringWithCodeAfterClosingQuote()
    {
        // The reported trigger: a multi-line string's LAST continuation line also
        // carries post-string code with a block keyword (If). The old blunt "skip
        // any line starting with |" logic dropped this whole line - including the
        // If opener - while its EndIf on a later non-| line was still matched.
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Text = \"start", //$NON-NLS-1$
            "    |end\"; If X Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testMultilineNStrLiteralInsideTryBlock()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Try", //$NON-NLS-1$
            "        Message = NStr(\"ru = '\u0421\u0442\u0440\u043E\u043A\u0430 \u043F\u0435\u0440\u0432\u0430\u044F", //$NON-NLS-1$
            "        |\u0421\u0442\u0440\u043E\u043A\u0430 \u0432\u0442\u043E\u0440\u0430\u044F'; en = 'Line one", //$NON-NLS-1$
            "        |Line two'\");", //$NON-NLS-1$
            "        If Message <> \"\" Then", //$NON-NLS-1$
            "            Return;", //$NON-NLS-1$
            "        EndIf;", //$NON-NLS-1$
            "    Except", //$NON-NLS-1$
            "        Raise;", //$NON-NLS-1$
            "    EndTry;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testMultilineQueryTextWithPipeLines()
    {
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Query = New Query;", //$NON-NLS-1$
            "    Query.Text =", //$NON-NLS-1$
            "        \"SELECT", //$NON-NLS-1$
            "        |    Ref", //$NON-NLS-1$
            "        |FROM", //$NON-NLS-1$
            "        |    Catalog.Items", //$NON-NLS-1$
            "        |WHERE", //$NON-NLS-1$
            "        |    Ref.Code = &Code\";", //$NON-NLS-1$
            "    Result = Query.Execute();", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testDoubleSlashInsideMultilineStringIsNotTreatedAsComment()
    {
        // The "//" inside the URL (opened on the first line, still inside the
        // string on the continuation line) must not be mistaken for a comment
        // start, and the trailing If on the closing continuation line must still
        // be recognized.
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Url = \"https://example.com/", //$NON-NLS-1$
            "    |path\"; If Url <> \"\" Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testGenuinelyUnbalancedModuleWithMultilineStringStillInvalid()
    {
        // A correctly-masked multi-line string must not hide a REAL imbalance
        // elsewhere in the module (the If below is genuinely never closed).
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Text = \"start", //$NON-NLS-1$
            "    |end\";", //$NON-NLS-1$
            "    If X Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse(result.isValid());
    }

    @Test
    public void testDoubledQuoteInsideStringIsEscapedNotClosing()
    {
        // A doubled "" inside an already-open string is an escaped quote and must
        // NOT close the literal early; keyword-looking words inside the string
        // ("If", "EndIf") must stay masked so they cannot be mistaken for the real
        // If/EndIf block that follows on later lines.
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    Text = \"say \"\"hello\"\" to If EndIf\";", //$NON-NLS-1$
            "    If X Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testDoubleQuoteInsideSingleQuoteDateLiteralDoesNotOpenAString()
    {
        // A single-quote '...' date/token literal that happens to contain a double quote must
        // NOT flip the string state (otherwise the unmatched " sticks and masks the whole rest
        // of the module, hiding EndIf/EndProcedure and yielding a false "unclosed" - codex #286).
        List<String> lines = Arrays.asList(
            "Procedure Test()", //$NON-NLS-1$
            "    D = '2024\"0101';", //$NON-NLS-1$
            "    If X Then", //$NON-NLS-1$
            "        Return;", //$NON-NLS-1$
            "    EndIf;", //$NON-NLS-1$
            "EndProcedure" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertTrue(result.isValid());
    }

    @Test
    public void testUnclosedStringNotFollowedByContinuationDoesNotSwallowRealCode()
    {
        // An unclosed " opens a string, but the NEXT line is real code (not a '|' continuation),
        // so the literal was not a valid multi-line string. The checker must NOT keep masking:
        // the following (genuinely unclosed) If block must still be detected (codex #286 - without
        // the reset the sticky string masks the If and the module is wrongly reported valid).
        List<String> lines = Arrays.asList(
            "x = \"broken", //$NON-NLS-1$
            "If X Then", //$NON-NLS-1$
            "    Return;" //$NON-NLS-1$
        );
        CheckResult result = BslSyntaxChecker.check(lines);
        assertFalse("an unclosed If after a broken (non-continued) string must still be caught", //$NON-NLS-1$
            result.isValid());
    }
}
