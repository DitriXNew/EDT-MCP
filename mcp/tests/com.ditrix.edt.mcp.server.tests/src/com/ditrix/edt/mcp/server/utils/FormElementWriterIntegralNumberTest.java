/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** Position numbers are read exactly and negative-number refusals quote canonical digits. */
public class FormElementWriterIntegralNumberTest
{
    private static final List<String> SIBLINGS = List.of("A", "B", "C");

    @Test
    public void decimalAndExponentPositionsAreIndices()
    {
        assertEquals(2, FormElementWriter.resolveMovePosition("2.0", SIBLINGS, "X"));
        assertEquals(2, FormElementWriter.resolveMovePosition("2e0", SIBLINGS, "X"));
        assertEquals(10, FormElementWriter.resolveMovePosition("1.0E1", SIBLINGS, "X"));
    }

    @Test
    public void zeroPaddedPositionHasNoLengthCap()
    {
        String raw = "0".repeat(69) + "2";
        assertEquals(70, raw.length());
        assertEquals(2, FormElementWriter.resolveMovePosition(raw, SIBLINGS, "X"));
        String decimal = "0".repeat(67) + "2.0";
        assertTrue(refusal(decimal).contains("Invalid position '" + decimal + "'"));
    }

    @Test
    public void negativeDecimalPositionEchoesTheInteger()
    {
        String message = refusal("-1.0");
        assertTrue(message, message.contains("index '-1'"));
        assertFalse(message, message.contains("-1.0"));
        assertTrue(message, message.contains("zero or positive"));
    }

    @Test
    public void fractionalAndOutOfRangePositionsStayRefusedVerbatim()
    {
        for (String raw : List.of("2.5", "2147483648.0", "NaN"))
        {
            assertTrue(refusal(raw).contains("Invalid position '" + raw + "'"));
        }
    }

    private static String refusal(String raw)
    {
        RuntimeException error = assertThrows(RuntimeException.class,
            () -> FormElementWriter.resolveMovePosition(raw, SIBLINGS, "X"));
        assertEquals(error.getMessage(), Refusals.messageOf(error));
        return error.getMessage();
    }
}
