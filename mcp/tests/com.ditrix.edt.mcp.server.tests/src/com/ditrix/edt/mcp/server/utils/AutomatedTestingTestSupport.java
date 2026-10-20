/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared assertions and compatibility seams for the issue #654 red-on-base build. */
public final class AutomatedTestingTestSupport
{
    public static final String MODE_ATTRIBUTE =
        "com._1c.g5.v8.dt.launching.core.ATTR_AUTOMATED_TESTING_MODE";

    public static final String PORT_ATTRIBUTE =
        "com._1c.g5.v8.dt.launching.core.ATTR_AUTOMATED_TESTING_PORT_NUMBER";

    private AutomatedTestingTestSupport()
    {
    }

    /**
     * On the base revision, exercise its actual override behavior with the new values ignored.
     * This keeps every regression test runnable, so the base build records assertion failures
     * rather than stopping at test compilation. The final implementation takes the five-value path.
     */
    public static LaunchOverrides overrides(String startupOption, String mode, Integer port) throws Exception
    {
        Method factory;
        try
        {
            factory = LaunchOverrides.class.getMethod("of", String.class, String.class, String.class,
                String.class, Integer.class);
        }
        catch (NoSuchMethodException e)
        {
            return LaunchOverrides.of(startupOption, null, null);
        }
        return (LaunchOverrides)factory.invoke(null, startupOption, null, null, mode, port);
    }

    public static String errorMessage(String json, String... fragments)
    {
        assertNotNull("Expected a structured refusal", json);
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(result.toString(), result.get("success").getAsBoolean());
        String message = result.get("error").getAsString();
        for (String fragment : fragments)
        {
            assertTrue(message, message.contains(fragment));
        }
        return message;
    }

    public static void assertTestingSchema(String schema)
    {
        JsonObject properties = JsonParser.parseString(schema).getAsJsonObject().getAsJsonObject("properties");
        assertTrue("Schema must declare automatedTestingMode", properties.has("automatedTestingMode"));
        assertTrue("Schema must declare automatedTestingPort", properties.has("automatedTestingPort"));
        JsonObject mode = properties.getAsJsonObject("automatedTestingMode");
        assertEquals("string", mode.get("type").getAsString());
        assertEquals(JsonParser.parseString("[\"TESTCLIENT\",\"TESTMANAGER\"]"), mode.get("enum"));
        assertEquals("integer", properties.getAsJsonObject("automatedTestingPort").get("type").getAsString());
    }
}
