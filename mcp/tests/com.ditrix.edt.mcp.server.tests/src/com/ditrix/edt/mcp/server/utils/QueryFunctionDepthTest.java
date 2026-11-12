/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import org.junit.Test;

public class QueryFunctionDepthTest
{
    @Test public void countsRussianAndEnglishCallsAndSiblingMaximum()
    {
        assertEquals(3, QueryFunctionDepth.maximum("ВЫБРАТЬ СтрЗаменить(ВРег(Подстрока(&S,1,2)),\".\",\" \"), ВРег(&S)"));
        assertEquals(3, QueryFunctionDepth.maximum("select StrReplace(Upper(Substring(&S,1,2)),\".\",\" \"), Upper(&S)"));
    }

    @Test public void ignoresStringsEscapedQuotesAndComments()
    {
        assertEquals(1, QueryFunctionDepth.maximum("SELECT Upper(\"literal \"\" f(f((\"), // f(f(f(\n Upper(&S)"));
        assertEquals(2, QueryFunctionDepth.maximum("Upper // ignored ) f(\r\n ( Lower (&S))"));
    }

    @Test public void leftAndRightAreCallsNotJoinKeywords()
    {
        assertEquals(17, QueryFunctionDepth.maximum("SELECT " + "Left(".repeat(17) + "&S" + ", 1)".repeat(17)));
        assertEquals(2, QueryFunctionDepth.maximum("SELECT Right(Left(&S, 2), 1)"));
        assertEquals(1, QueryFunctionDepth.maximum("SELECT 1 FROM A AS A LEFT JOIN (SELECT Upper(&S) AS F) AS B ON TRUE"));
        assertEquals(0, QueryFunctionDepth.maximum("SELECT 1 FROM A AS A RIGHT OUTER JOIN (SELECT 1 AS F) AS B ON TRUE"));
    }

    @Test public void plainParenthesesAndKeywordsAreNotCalls()
    {
        assertEquals(0, QueryFunctionDepth.maximum("SELECT (((1 + 1))) WHERE (1 IN (1,2)) AND NOT (1=2)"));
        assertEquals(0, QueryFunctionDepth.maximum("ВЫБРАТЬ (((1 + 1))) ГДЕ (1 В (1,2)) И НЕ (1=2)"));
        assertEquals(2, QueryFunctionDepth.maximum("SELECT (Upper((Lower(&S))))"));
        assertEquals(1, QueryFunctionDepth.maximum("SELECT DISTINCT (Upper(&S))"));
        assertEquals(1, QueryFunctionDepth.maximum("ВЫБРАТЬ РАЗЛИЧНЫЕ (ВРег(&S))"));
    }

    @Test public void likeBetweenAndEscapeDoNotCountAsCalls()
    {
        for (String condition : new String[] {
            "Description LIKE (\"pattern\") ESCAPE (\"!\")",
            "Description \u041f\u041e\u0414\u041e\u0411\u041d\u041e (\"pattern\") \u0421\u041f\u0415\u0426\u0421\u0418\u041c\u0412\u041e\u041b (\"!\")",
            "Amount BETWEEN (1 AND 2) AND (3)",
            "Amount \u041c\u0415\u0416\u0414\u0423 (1 \u0418 2) \u0418 (3)"})
        {
            assertEquals(condition, 0, QueryFunctionDepth.maximum("SELECT 1 WHERE " + condition));
        }
        for (String operator : new String[] {"LIKE", "\u041f\u041e\u0414\u041e\u0411\u041d\u041e", "BETWEEN", "\u041c\u0415\u0416\u0414\u0423", "ESCAPE", "\u0421\u041f\u0415\u0426\u0421\u0418\u041c\u0412\u041e\u041b"})
        {
            assertEquals(operator, 2, QueryFunctionDepth.maximum(
                "SELECT 1 WHERE Description " + operator + " (UPPER(UPPER(&S)))"));
        }
    }

    @Test public void groupingAndIndexSetsDoNotCountAsCalls()
    {
        for (String clause : new String[] {"GROUP BY GROUPING SETS ((A), ())",
            "\u0421\u0413\u0420\u0423\u041f\u041f\u0418\u0420\u041e\u0412\u0410\u0422\u042c \u041f\u041e \u0413\u0420\u0423\u041f\u041f\u0418\u0420\u0423\u042e\u0429\u0418\u041c \u041d\u0410\u0411\u041e\u0420\u0410\u041c ((A), ())",
            "INDEX BY SETS ((A), (B))", "\u0418\u041d\u0414\u0415\u041a\u0421\u0418\u0420\u041e\u0412\u0410\u0422\u042c \u041f\u041e \u041d\u0410\u0411\u041e\u0420\u0410\u041c ((A), (B))",
            "\u041d\u0410\u0411\u041e\u0420\u042b ((A), ())"})
        {
            assertEquals(clause, 0, QueryFunctionDepth.maximum("SELECT A " + clause));
        }
    }

    @Test public void totalsPeriodsDoNotCountAsCalls()
    {
        assertEquals(0, QueryFunctionDepth.maximum("SELECT A TOTALS BY A PERIODS (DAY, &Start, &End)"));
        assertEquals(0, QueryFunctionDepth.maximum("\u0412\u042b\u0411\u0420\u0410\u0422\u042c A \u0418\u0422\u041e\u0413\u0418 \u041f\u041e A \u041f\u0415\u0420\u0418\u041e\u0414\u0410\u041c\u0418 (\u0414\u0415\u041d\u042c, &Start, &End)"));
        assertEquals(2, QueryFunctionDepth.maximum("SELECT A TOTALS BY A PERIODS (DAY, Upper(Lower(&S)))"));
    }

    @Test public void dcsCharacteristicKeywordsDoNotCountAsCalls()
    {
        // QlDcs.ReadCharacteristic accepts nested queries or parenthesized field expressions.
        for (String keyword : new String[] {
            "CHARACTERISTICTYPES",
            "\u0412\u0418\u0414\u042b\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a",
            "LIST",
            "\u0421\u041f\u0418\u0421\u041e\u041a",
            "CHARACTERISTICVALUES",
            "\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a",
            "VALUES",
            "\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f",
            "KEYFIELD",
            "\u041f\u041e\u041b\u0415\u041a\u041b\u042e\u0427\u0410",
            "ID",
            "\u0418\u0414\u0415\u041d\u0422\u0418\u0424\u0418\u041a\u0410\u0422\u041e\u0420",
            "NAMEFIELD",
            "\u041f\u041e\u041b\u0415\u0418\u041c\u0415\u041d\u0418",
            "NAME",
            "\u0418\u041c\u042f",
            "VALUETYPEFIELD",
            "\u041f\u041e\u041b\u0415\u0422\u0418\u041f\u0410\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f",
            "OBJECTFIELD",
            "\u041f\u041e\u041b\u0415\u041e\u0411\u042a\u0415\u041a\u0422\u0410",
            "OBJECT",
            "\u041e\u0411\u042a\u0415\u041a\u0422",
            "TYPEFIELD",
            "\u041f\u041e\u041b\u0415\u0412\u0418\u0414\u0410",
            "CHARACTERISTIC",
            "\u0425\u0410\u0420\u0410\u041a\u0422\u0415\u0420\u0418\u0421\u0422\u0418\u041a\u0410",
            "VALUEFIELD",
            "\u041f\u041e\u041b\u0415\u0417\u041d\u0410\u0427\u0415\u041d\u0418\u042f"})
        {
            assertEquals(keyword, 0, QueryFunctionDepth.maximum(keyword + " (SELECT A)"));
            assertEquals(keyword, 2, QueryFunctionDepth.maximum(keyword + " (Upper(Lower(&S)))"));
        }
    }
    @Test public void castAndTypeConstructorsCountConservatively()
    {
        assertEquals(3, QueryFunctionDepth.maximum("SELECT CAST(CAST(&S AS STRING(100)) AS STRING(100))"));
        assertEquals(3, QueryFunctionDepth.maximum("ВЫБРАТЬ ВЫРАЗИТЬ(ВЫРАЗИТЬ(&S КАК СТРОКА(100)) КАК СТРОКА(100))"));
    }

    @Test public void depthRefusesBeforeAnyPlatformServiceIsTouched()
    {
        for (String function : new String[] {"ISNULL", "ЕСТЬNULL"})
        {
            String query = "SELECT " + (function + "(").repeat(42) + "&S" + ",0)".repeat(42);
            var result = new QueryValidationService().validate(null, query, true);
            assertFalse(result.available());
            assertEquals(QueryValidationService.State.PARTIAL, result.state());
            String message = result.toJson();
            assertTrue(message, message.contains("depth 42"));
            assertTrue(message, message.contains("limit 16"));
            assertTrue(message, message.contains("temporary tables or intermediate fields"));
            assertTrue(message, message.contains("Writes are refused"));
        }
    }
}
