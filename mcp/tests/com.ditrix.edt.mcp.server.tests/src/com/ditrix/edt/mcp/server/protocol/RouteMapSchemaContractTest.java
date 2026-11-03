/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool;
import com.google.gson.JsonParser;

public class RouteMapSchemaContractTest
{
    @Test
    public void testCrossFieldRequirementsSurviveToolsListCompaction()
    {
        var tool = new BusinessProcessRouteMapTool();
        var properties = InputSchemaCompactor.compact(tool.getName(), JsonParser.parseString(tool.getInputSchema()))
            .getAsJsonObject().getAsJsonObject("properties");
        assertTrue("replace requires both arrays even for an empty map", properties.getAsJsonObject("action")
            .has("description"));
        assertEquals("Operation; replace needs points and connections, even when empty.",
            properties.getAsJsonObject("action").get("description").getAsString());
        assertTrue("Condition sources require a branch", properties.getAsJsonObject("connections")
            .has("description"));
        assertTrue(properties.getAsJsonObject("connections").get("description").getAsString()
            .contains("Condition sources require branch='true' or 'false'"));
        assertFalse("Unrelated prose remains compacted", properties.getAsJsonObject("language").has("description"));
    }
}
