/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.regex.Pattern;

import org.junit.Test;

/** Host validation must retain its language without a stack frame per dotted label. */
public class ServerInfobaseTargetSonarZeroTest
{
    @Test(timeout = 5000)
    public void veryLongDottedHostDoesNotOverflow()
    {
        String host = "label.".repeat(20000) + "last";
        assertNull(ServerInfobaseTarget.validationError(host, "base", null, true, false));
        assertNull(ServerInfobaseTarget.validationError(host + ".:65535", "base", null, true, false));
    }

    @Test(timeout = 5000)
    public void veryLongRejectedDottedHostDoesNotOverflow()
    {
        String host = "a.".repeat(20000) + "last";
        assertNotNull(ServerInfobaseTarget.validationError(host + "..", "base", null, true, false));
        assertNotNull(ServerInfobaseTarget.validationError(host + "!", "base", null, true, false));
    }

    @Test
    public void acceptedAndRejectedAddressesKeepTheirAnswers()
    {
        for (String host : new String[] {"a", "-", "_", "123", "host.example.", "a_b.-c:1",
            "\u0441\u0435\u0440\u0432\u0435\u0440.\u0431\u0430\u0437\u0430:1541",
            "[::1]:65535", "[::ffff:192.0.2.1]", "host:1541,other:1542"})
        {
            assertNull(host, ServerInfobaseTarget.validationError(host, "base", null, true, false));
        }
        for (String host : new String[] {".", ".host", "host..example", "host..", "host!", "host:0",
            "host:65536", "host:", "host:000001", "[gg::1]", "host,", "local host"})
        {
            assertNotNull(host, ServerInfobaseTarget.validationError(host, "base", null, true, false));
        }
    }

    @Test
    public void shortHostCorpusMatchesTheExactBaseExpression()
    {
        Pattern base = Pattern.compile("[\\p{L}\\p{N}_-]+(?:\\.[\\p{L}\\p{N}_-]+)*\\.?");
        String[] pieces = {"a", "09", "_", "-", ".", "..", "!", "\u044f", "\u00e9", "\u4e2d", "\u0661"};
        int checked = 0;
        for (String a : pieces)
        {
            for (String b : pieces)
            {
                for (String c : pieces)
                {
                    for (String tail : new String[] {"", ".", ".end", "..", "!"})
                    {
                        String host = a + b + c + tail;
                        assertEquals(host, base.matcher(host).matches(),
                            ServerInfobaseTarget.validationError(host, "base", null, true, false) == null);
                        checked++;
                    }
                }
            }
        }
        assertEquals(6655, checked);
    }
}
