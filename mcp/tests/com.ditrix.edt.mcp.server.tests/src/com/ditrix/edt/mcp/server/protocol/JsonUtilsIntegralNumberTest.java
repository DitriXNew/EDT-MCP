/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Exact integral text parsing, independent of the transport's number rendering. */
public class JsonUtilsIntegralNumberTest
{
    @Test
    public void everyWholeNumberRenderingIsAccepted()
    {
        for (String raw : new String[] {"2", "2.0", "2e0", " 2 ", "+2"})
        {
            assertEquals(raw, Long.valueOf(2), JsonUtils.parseIntegralLong(raw));
            assertEquals(raw, Integer.valueOf(2), JsonUtils.parseIntegralInt(raw));
        }
        assertEquals(Long.valueOf(10000000), JsonUtils.parseIntegralLong("1.0E7"));
        assertEquals(Integer.valueOf(10000000), JsonUtils.parseIntegralInt("1.0E7"));
        assertEquals(Long.valueOf(0), JsonUtils.parseIntegralLong("-0.0"));
        assertEquals(Integer.valueOf(0), JsonUtils.parseIntegralInt("-0.0"));
    }

    @Test
    public void fractionsNonFiniteAndNonNumericValuesAreRefused()
    {
        for (String raw : new String[] {"2.5", "NaN", "Infinity", "-Infinity", "abc", "", " ",
            "1d", "1f", "0x1.0p0", null})
        {
            assertNull(raw, JsonUtils.parseIntegralLong(raw));
            assertNull(raw, JsonUtils.parseIntegralInt(raw));
        }
    }

    @Test
    public void longBoundariesAreExact()
    {
        assertEquals(Long.valueOf(Long.MAX_VALUE), JsonUtils.parseIntegralLong("9223372036854775807.0"));
        assertEquals(Long.valueOf(Long.MIN_VALUE), JsonUtils.parseIntegralLong("-9223372036854775808.0"));
        assertNull(JsonUtils.parseIntegralLong("9223372036854775808"));
        assertNull(JsonUtils.parseIntegralLong("-9223372036854775809.0"));
        assertEquals(Long.valueOf(9007199254740993L), JsonUtils.parseIntegralLong("9007199254740993.0"));
    }

    @Test
    public void intBoundariesAreExact()
    {
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), JsonUtils.parseIntegralInt("2147483647.0"));
        assertEquals(Integer.valueOf(Integer.MIN_VALUE), JsonUtils.parseIntegralInt("-2147483648.0"));
        assertNull(JsonUtils.parseIntegralInt("2147483648.0"));
        assertNull(JsonUtils.parseIntegralInt("-2147483649.0"));
    }

    @Test
    public void canonicalTextRewritesOnlyDecimalAndExponentWholeNumbers()
    {
        String[][] cases = {{"65536.0", "65536"}, {"1.0E7", "10000000"}, {"2e0", "2"},
            {"-0.0", "0"}, {" 2.0 ", "2"}, {"+5", "+5"}, {"007", "007"}, {" 5 ", " 5 "},
            {"2.5", "2.5"}, {"abc", "abc"}, {"NaN", "NaN"}, {"Infinity", "Infinity"},
            {"9.3E18", "9.3E18"}, {"9223372036854775808.0", "9223372036854775808.0"},
            {"9223372036854775807.0", "9223372036854775807"}};
        for (String[] pair : cases)
        {
            assertEquals(pair[0], pair[1], JsonUtils.canonicalIntegerText(pair[0]));
        }
        assertNull(JsonUtils.canonicalIntegerText(null));
    }

    @Test(timeout = 2000)
    public void overlongTokensAreRefusedPromptlyAndEchoedVerbatim()
    {
        for (String raw : new String[] {"9".repeat(5000), "0".repeat(4999) + "2",
            "0." + "0".repeat(4998)})
        {
            assertNull(JsonUtils.parseIntegralLong(raw));
            assertNull(JsonUtils.parseIntegralInt(raw));
            assertEquals(raw, JsonUtils.canonicalIntegerText(raw));
        }
    }

    @Test
    public void lengthLimitAppliesToTrimmedText()
    {
        String boundary = "0".repeat(63) + "2";
        assertEquals(Long.valueOf(2), JsonUtils.parseIntegralLong("  " + boundary + "  "));
        assertEquals(Integer.valueOf(2), JsonUtils.parseIntegralInt("  " + boundary + "  "));
        assertNull(JsonUtils.parseIntegralLong("0" + boundary));
        assertNull(JsonUtils.parseIntegralInt("0" + boundary));
        String decimalBoundary = "0." + "0".repeat(62);
        assertEquals("0", JsonUtils.canonicalIntegerText(decimalBoundary));
        assertEquals(decimalBoundary + "0", JsonUtils.canonicalIntegerText(decimalBoundary + "0"));
    }

    @Test(timeout = 2000)
    public void extremeExponentsAreBounded()
    {
        assertNull(JsonUtils.parseIntegralLong("1e999999999"));
        assertNull(JsonUtils.parseIntegralInt("1e999999999"));
        assertEquals("1e999999999", JsonUtils.canonicalIntegerText("1e999999999"));
        assertNull(JsonUtils.parseIntegralLong("1e-999999999"));
        assertEquals("1e-999999999", JsonUtils.canonicalIntegerText("1e-999999999"));
    }
}
