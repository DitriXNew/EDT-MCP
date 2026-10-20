/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pin native EDT literals independently; reflection also avoids compile-time constant inlining. */
public class LaunchConfigAutomatedTestingConstantsTest
{
    @Test
    public void testModeAttributeMatchesEdt() throws Exception
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_AUTOMATED_TESTING_MODE",
            value("ATTR_AUTOMATED_TESTING_MODE"));
    }

    @Test
    public void testIntegerPortAttributeMatchesEdt() throws Exception
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_AUTOMATED_TESTING_PORT_NUMBER",
            value("ATTR_AUTOMATED_TESTING_PORT_NUMBER"));
    }

    @Test
    public void testWebClientIdAttributeMatchesEdt() throws Exception
    {
        assertEquals("com._1c.g5.v8.dt.launching.core.ATTR_AUTOMATED_TESTING_WEB_CLIENT_ID",
            value("ATTR_AUTOMATED_TESTING_WEB_CLIENT_ID"));
    }

    @Test
    public void testClientModeMatchesEdtExactString() throws Exception
    {
        assertEquals("TESTCLIENT", value("VALUE_AUTOMATED_TESTING_TEST_CLIENT"));
    }

    @Test
    public void testManagerModeMatchesEdtExactString() throws Exception
    {
        assertEquals("TESTMANAGER", value("VALUE_AUTOMATED_TESTING_TEST_MANAGER"));
    }

    @Test
    public void testDefaultPortMatchesEdtDelegate() throws Exception
    {
        assertEquals(1538, value("DEFAULT_AUTOMATED_TESTING_PORT"));
    }

    private static Object value(String name) throws Exception
    {
        return LaunchConfigUtils.class.getField(name).get(null);
    }
}
