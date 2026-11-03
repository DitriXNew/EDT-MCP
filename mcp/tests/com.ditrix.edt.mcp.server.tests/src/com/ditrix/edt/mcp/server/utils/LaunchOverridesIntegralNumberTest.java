/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Port echoes share canonical integer rendering and leave values beyond long verbatim. */
public class LaunchOverridesIntegralNumberTest
{
    @Test
    public void portEchoesWithinLongKeepTheirExistingRendering()
    {
        assertEquals("65536", LaunchOverrides.displayedPort("65536.0"));
        assertEquals("10000000", LaunchOverrides.displayedPort("1.0E7"));
        assertEquals("0", LaunchOverrides.displayedPort("-0.0"));
        assertEquals("+5", LaunchOverrides.displayedPort("+5"));
        assertEquals("007", LaunchOverrides.displayedPort("007"));
        assertEquals("2.5", LaunchOverrides.displayedPort("2.5"));
        assertNull(LaunchOverrides.displayedPort(null));
    }

    @Test
    public void portEchoBeyondLongStaysVerbatim()
    {
        assertEquals("9223372036854775808.0", LaunchOverrides.displayedPort("9223372036854775808.0"));
        assertEquals("9.3E18", LaunchOverrides.displayedPort("9.3E18"));
    }
}
