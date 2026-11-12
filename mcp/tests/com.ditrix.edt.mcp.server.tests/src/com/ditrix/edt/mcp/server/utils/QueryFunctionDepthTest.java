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

    @Test public void plainParenthesesAndKeywordsAreNotCalls()
    {
        assertEquals(0, QueryFunctionDepth.maximum("SELECT (((1 + 1))) WHERE (1 IN (1,2)) AND NOT (1=2)"));
        assertEquals(0, QueryFunctionDepth.maximum("ВЫБРАТЬ (((1 + 1))) ГДЕ (1 В (1,2)) И НЕ (1=2)"));
        assertEquals(2, QueryFunctionDepth.maximum("SELECT (Upper((Lower(&S))))"));
        assertEquals(1, QueryFunctionDepth.maximum("SELECT DISTINCT (Upper(&S))"));
        assertEquals(1, QueryFunctionDepth.maximum("ВЫБРАТЬ РАЗЛИЧНЫЕ (ВРег(&S))"));
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
