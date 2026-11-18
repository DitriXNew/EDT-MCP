/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** The central inputSchema check: lenient where the readers always were, refusing silent loss. */
public class ToolArgumentValidatorTest
{
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{" //$NON-NLS-1$
        + "\"name\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"limit\":{\"type\":\"integer\",\"minimum\":1}," //$NON-NLS-1$
        + "\"flag\":{\"type\":\"boolean\"}," //$NON-NLS-1$
        + "\"mode\":{\"type\":\"string\",\"enum\":[\"read\",\"write\"]}," //$NON-NLS-1$
        + "\"tags\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," //$NON-NLS-1$
        + "\"body\":{\"type\":\"object\",\"properties\":{\"at\":{\"type\":\"integer\",\"minimum\":1}}}," //$NON-NLS-1$
        + "\"rights\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"additionalProperties\":false," //$NON-NLS-1$
        + "\"required\":[\"object\"],\"properties\":{\"object\":{\"type\":\"string\"}," //$NON-NLS-1$
        + "\"value\":{\"type\":\"string\",\"enum\":[\"set\",\"clear\"]}," //$NON-NLS-1$
        + "\"rlsFields\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}," //$NON-NLS-1$
        + "\"interactive\":{\"type\":\"boolean\"}}}}}," //$NON-NLS-1$
        + "\"required\":[\"name\"]}"; //$NON-NLS-1$

    private static String check(Object... keyValues)
    {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            args.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ToolArgumentValidator.validate("demo", SCHEMA, args); //$NON-NLS-1$
    }

    private static Map<String, Object> map(Object... keyValues)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2)
        {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    @Test
    public void everyFormTheReadersAcceptedStillPasses()
    {
        assertNull(check("limit", 5.0, "flag", Boolean.TRUE, "mode", "write")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull(check("limit", "5", "flag", "yes", "mode", "WRITE")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertNull(check("limit", "2.0", "flag", "0", "tags", "a, b")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
        assertNull(check("flag", 1.0, "tags", "[\"a\",\"b\"]", "body", "{\"at\":2}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertNull("empty text and null mean omitted", //$NON-NLS-1$
            check("limit", "", "flag", " ", "mode", "", "body", null)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$ //$NON-NLS-8$
        assertNull("unknown and missing top-level arguments are the tool's call", //$NON-NLS-1$
            check("somethingElse", Arrays.asList(1.0))); //$NON-NLS-1$
        assertNull(check("rights", Arrays.asList(map("object", "Catalog.A", "value", "set", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "rlsFields", Arrays.asList("Owner"), "interactive", "true")))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertNull("a JSON-encoded array of objects is decoded", //$NON-NLS-1$
            check("rights", "[{\"object\":\"Catalog.A\",\"rlsFields\":null}]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void silentlyDroppedTopLevelValuesAreRefused()
    {
        assertRefused(check("limit", "abc"), "limit must be an integer; got the string 'abc'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(check("limit", 1.5), "limit must be an integer; got 1.5"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(check("limit", 0.0), "limit must be at least 1; got 0"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(check("flag", "maybe"), "flag must be true or false; got the string 'maybe'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(check("flag", 2.0), "flag must be true or false; got 2"); //$NON-NLS-1$ //$NON-NLS-2$
        assertRefused(check("mode", "delete"), "mode must be one of 'read', 'write'; got the string 'delete'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(check("name", map("a", "b")), "name must be a string; got an object"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused(check("body", "text"), "body must be an object; got the string 'text'"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(check("rights", "Catalog.A"), "rights must be an array of {object, value?, rlsFields?, interactive?} objects; got the string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertRefused(check("body", map("at", 0.0)), "body.at must be at least 1; got 0"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void nestedItemsAreCheckedAsDeclared()
    {
        String rls = check("rights", Arrays.asList(map("object", "Catalog.A", "rlsFields", "Owner"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
        assertRefused(rls, "rights[0].rlsFields must be an array; got the string 'Owner'"); //$NON-NLS-1$
        assertRefused(check("rights", Arrays.asList(map("value", "set"))), "rights[0].object is required."); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertRefused(check("rights", Arrays.asList(map("object", "A", "rlsField", Arrays.asList("x")))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "rights[0].rlsField is not a known key; accepted: object, value, rlsFields, interactive."); //$NON-NLS-1$
        assertRefused(check("rights", Arrays.asList(map("object", "A", "interactive", "yes"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "rights[0].interactive must be true or false"); //$NON-NLS-1$
        assertRefused(check("rights", Arrays.asList(map("object", "A", "value", "allow"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "rights[0].value must be one of 'set', 'clear'"); //$NON-NLS-1$
        assertRefused(check("rights", Arrays.asList("Catalog.A")), "rights[0] must be an object {object, value?, rlsFields?, interactive?}; got the string"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void everyViolationIsListedUpToTheCap()
    {
        List<Object> items = new java.util.ArrayList<>();
        for (int i = 0; i < ToolArgumentValidator.MAX_REPORTED + 3; i++)
        {
            items.add(map("value", "x")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        String message = check("rights", items, "limit", "abc"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(message, message.startsWith("Invalid arguments for 'demo'; nothing was run:")); //$NON-NLS-1$
        assertTrue(message, message.contains("- ... and ")); //$NON-NLS-1$
        assertTrue(message, message.endsWith("Fix the values to match the tool's inputSchema and call again.")); //$NON-NLS-1$
    }

    @Test
    public void anUnreadableSchemaChecksNothing()
    {
        Map<String, Object> args = map("limit", "abc"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ToolArgumentValidator.validate("demo", null, args)); //$NON-NLS-1$
        assertNull(ToolArgumentValidator.validate("demo", "not json", args)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ToolArgumentValidator.validate("demo", "{\"type\":\"object\"}", args)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static void assertRefused(String message, String expected)
    {
        assertFalse("expected a refusal containing: " + expected, message == null); //$NON-NLS-1$
        assertTrue(message, message.contains(expected));
    }
}
