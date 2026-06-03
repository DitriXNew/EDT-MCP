/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Tests for {@link ClientCapabilities}: parsing the client's initialize
 * capabilities object and the conservative gating predicates, especially the
 * no-regression default for structuredContent.
 */
public class ClientCapabilitiesTest
{
    private static JsonElement json(String text)
    {
        return JsonParser.parseString(text);
    }

    @Test
    public void testAbsentIsNotPresentAndPermissive()
    {
        ClientCapabilities caps = ClientCapabilities.ABSENT;
        assertFalse("ABSENT must not be present", caps.isPresent());
        assertNull("ABSENT exposes no raw object", caps.getRaw());
        assertTrue("ABSENT must allow structuredContent (default)",
            caps.allowsStructuredContent());
        assertFalse("ABSENT declares no capability", caps.has("roots"));
    }

    @Test
    public void testFromNullYieldsAbsent()
    {
        // A null element (no capabilities supplied) resolves to the shared ABSENT.
        assertSame("null capabilities must resolve to ABSENT",
            ClientCapabilities.ABSENT, ClientCapabilities.from(null));
    }

    @Test
    public void testFromNonObjectYieldsAbsent()
    {
        // A malformed (non-object) capabilities value resolves to ABSENT and never
        // throws, so a bad client cannot change the default behaviour.
        assertSame("a string capabilities value must resolve to ABSENT",
            ClientCapabilities.ABSENT, ClientCapabilities.from(json("\"oops\"")));
        assertSame("an array capabilities value must resolve to ABSENT",
            ClientCapabilities.ABSENT, ClientCapabilities.from(json("[1,2,3]")));
    }

    @Test
    public void testEmptyObjectIsPresentAndPermissive()
    {
        // The common case: clients send an empty capabilities object. It is present
        // but still permissive.
        ClientCapabilities caps = ClientCapabilities.from(json("{}"));
        assertTrue("an empty object is still present", caps.isPresent());
        assertNotNull("an empty object exposes a raw object", caps.getRaw());
        assertTrue("an empty object must allow structuredContent",
            caps.allowsStructuredContent());
    }

    @Test
    public void testHasReflectsDeclaredCapabilities()
    {
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"roots\":{\"listChanged\":true},\"elicitation\":{}}"));
        assertTrue("declared roots must be reported", caps.has("roots"));
        assertTrue("declared elicitation must be reported", caps.has("elicitation"));
        assertFalse("undeclared sampling must not be reported", caps.has("sampling"));
        assertFalse("a null name must be tolerated", caps.has(null));
    }

    @Test
    public void testAllowsStructuredContentDefaultsTrueWithUnrelatedCapabilities()
    {
        // Capabilities present but unrelated to structuredContent => still allowed.
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"roots\":{},\"sampling\":{}}"));
        assertTrue("unrelated capabilities must keep structuredContent allowed",
            caps.allowsStructuredContent());
    }

    @Test
    public void testExplicitOptOutSuppressesStructuredContent()
    {
        // The single, narrow opt-out: experimental.structuredContent == false.
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"experimental\":{\"structuredContent\":false}}"));
        assertFalse("an explicit false must suppress structuredContent",
            caps.allowsStructuredContent());
    }

    @Test
    public void testExplicitOptInAllowsStructuredContent()
    {
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"experimental\":{\"structuredContent\":true}}"));
        assertTrue("an explicit true must allow structuredContent",
            caps.allowsStructuredContent());
    }

    @Test
    public void testNonBooleanFlagFallsBackToAllow()
    {
        // A non-boolean flag is not a valid opt-out; default (allow) wins.
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"experimental\":{\"structuredContent\":\"no\"}}"));
        assertTrue("a non-boolean flag must keep structuredContent allowed",
            caps.allowsStructuredContent());
    }

    @Test
    public void testExperimentalNonObjectFallsBackToAllow()
    {
        // A malformed experimental block (not an object) must not suppress.
        ClientCapabilities caps =
            ClientCapabilities.from(json("{\"experimental\":42}"));
        assertTrue("a non-object experimental block must keep structuredContent allowed",
            caps.allowsStructuredContent());
    }
}
