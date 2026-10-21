/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.rename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Decimal/exponent indices and transport-rendered arrays retain the count-only refusal contract. */
public class DisableRequestIntegralNumberTest
{
    @Test
    public void decimalAndExponentTokensAreIndices()
    {
        assertIndices("2.0,3.0");
        assertIndices("2e0,3.0E0");
        assertNull(DisableRequest.parse("3.0").validationError());
    }

    @Test
    public void jsonNumberArrayIsTheSameRequest()
    {
        assertIndices("[2.0,3.0]");
    }

    @Test
    public void nonJsonArraySyntaxKeepsTheOriginalCommaCounts()
    {
        String[] inputs = {"[2;3]", "[2,,3]", "['2']", "[unquoted]", "[2,'3']",
            "[2,/*comment*/3]", "[2,]", "[2][3]"};
        int[] counts = {1, 2, 1, 1, 2, 2, 2, 1};
        for (int i = 0; i < inputs.length; i++)
        {
            DisableRequest parsed = DisableRequest.parse(inputs[i]);
            assertTrue(inputs[i], parsed.indices().isEmpty());
            assertEquals(inputs[i], counts[i], parsed.unparsedCount());
            String error = parsed.validationError();
            assertTrue(error, error.contains(counts[i] + " entr"));
            assertFalse(error, error.contains(inputs[i]));
        }
    }

    @Test
    public void jsonStringArrayIsTheSameRequest()
    {
        assertIndices("[\"2\",\"3\"]");
    }

    @Test
    public void nonPrimitiveArrayEntriesAreCounted()
    {
        DisableRequest nested = DisableRequest.parse("[2.0,[3]]");
        assertEquals(Set.of(2), nested.indices());
        assertEquals(1, nested.unparsedCount());
        DisableRequest mixed = DisableRequest.parse("[2.0,{},null,true]");
        assertEquals(Set.of(2), mixed.indices());
        assertEquals(3, mixed.unparsedCount());
    }

    @Test
    public void fractionalAndOutOfRangeEntriesAreCounted()
    {
        for (String raw : List.of("2.5", "2147483648.0", "NaN", "Infinity"))
        {
            DisableRequest parsed = DisableRequest.parse(raw);
            assertEquals(raw, 1, parsed.unparsedCount());
            assertTrue(raw, parsed.indices().isEmpty());
        }
        assertEquals(2, DisableRequest.parse("[2.0,3.0").unparsedCount());
        assertEquals(2, DisableRequest.parse("[2.0,{]").unparsedCount());
        DisableRequest originalFallback = DisableRequest.parse("[2,3");
        assertEquals(Set.of(3), originalFallback.indices());
        assertEquals(1, originalFallback.unparsedCount());
    }

    @Test
    public void negativeDecimalIndexIsParsedAndThenRefusedByCount()
    {
        DisableRequest parsed = DisableRequest.parse("-1.0");
        assertEquals(Set.of(-1), parsed.indices());
        assertEquals(0, parsed.unparsedCount());
        assertTrue(parsed.validationError(), parsed.validationError().contains("index -1 below the first preview index"));
        assertFalse(parsed.validationError(), parsed.validationError().contains("-1.0"));
    }

    @Test
    public void whitespaceAndEmptyTokensStayFormatting()
    {
        assertIndices(" 2.0,\t,,3.0,");
        assertIndices("[\" 2.0 \",\"\",\"3.0\"]");
        assertTrue(DisableRequest.parse("[]").isEmpty());
        assertEquals(1, DisableRequest.parse(String.valueOf((char)7)).unparsedCount());
        assertEquals(1, DisableRequest.parse("2" + (char)7).unparsedCount());
    }

    @Test
    public void duplicatesKeepTheOriginalOrderAndIndicesStayImmutable()
    {
        DisableRequest parsed = DisableRequest.parse("[3.0,2.0,3.0]");
        assertEquals(List.of(3, 2), new ArrayList<>(parsed.indices()));
        org.junit.Assert.assertThrows(UnsupportedOperationException.class, () -> parsed.indices().add(4));
    }

    @Test
    public void invalidArrayTextIsNeverEchoed()
    {
        String marker = "caller_secret_796";
        DisableRequest parsed = DisableRequest.parse("[2.0,\"" + marker + "\",{}]");
        assertEquals(2, parsed.unparsedCount());
        String error = parsed.validationError();
        assertTrue(error, error.contains("2 entries"));
        assertFalse(error, error.contains(marker));
        assertFalse(error, error.contains("2.0"));
        assertFalse(error, error.contains("{}"));
    }

    private static void assertIndices(String raw)
    {
        DisableRequest parsed = DisableRequest.parse(raw);
        assertEquals(raw, Set.of(2, 3), parsed.indices());
        assertEquals(raw, 0, parsed.unparsedCount());
        assertNull(raw, parsed.validationError());
    }
}
