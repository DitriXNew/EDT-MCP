/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A session number must pass argument parsing before the nonexistent log is located. */
public class GetEventLogIntegralNumberTest
{
    private static final String MISSING_LOG = "/tmp/no_such_1cv8log_796_unit";

    @Test
    public void decimalSessionPassesTheIntegerGuard()
    {
        assertAccepted("5.0");
    }

    @Test
    public void exponentSessionPassesTheIntegerGuard()
    {
        assertAccepted("1.0E7");
    }

    @Test
    public void zeroPaddedSessionHasNoLengthCap()
    {
        String raw = "0".repeat(69) + "2";
        assertEquals(70, raw.length());
        assertAccepted(raw);
        String decimal = "0".repeat(67) + "2.0";
        assertEquals("Invalid session: '" + decimal + "'. It must be an integer session number.", error(decimal));
    }

    @Test
    public void fractionalSessionIsRefusedVerbatim()
    {
        assertEquals("Invalid session: '5.5'. It must be an integer session number.", error("5.5"));
    }

    @Test
    public void overflowAndNonFiniteSessionsStayRefused()
    {
        for (String raw : new String[] {"9223372036854775808.0", "NaN", "Infinity"})
        {
            assertEquals("Invalid session: '" + raw + "'. It must be an integer session number.", error(raw));
        }
    }

    private static void assertAccepted(String raw)
    {
        String message = error(raw);
        assertFalse(message, message.contains("Invalid session"));
        assertTrue("the directory guard must answer instead: " + message, message.contains(Path.of(MISSING_LOG).toString()));
    }

    private static String error(String raw)
    {
        JsonObject result = JsonParser.parseString(new GetEventLogTool().execute(
            Map.of("logDir", MISSING_LOG, "session", raw))).getAsJsonObject();
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        return result.get("error").getAsString();
    }
}
