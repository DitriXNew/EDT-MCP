/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pins the route-map envelope rules checked by the live output-schema ratchet. */
public class BusinessProcessRouteMapOutputSchemaTest
{
    @Test
    public void testOnlySuccessIsRequiredInAPermissiveOutputEnvelope()
    {
        JsonObject schema = JsonParser.parseString(new BusinessProcessRouteMapTool().getOutputSchema())
            .getAsJsonObject();
        assertEquals("object", schema.get("type").getAsString());
        JsonObject properties = schema.getAsJsonObject("properties");
        assertTrue("The output envelope must declare properties", properties.size() > 0);
        assertTrue("The output envelope must declare success", properties.has("success"));
        assertEquals("boolean", properties.getAsJsonObject("success").get("type").getAsString());
        assertEquals("Only success is unconditional; action-specific fields must stay optional",
            JsonParser.parseString("[\"success\"]"), schema.get("required"));
        assertFalse("The output envelope must accept additional conditional fields",
            JsonParser.parseString("false").equals(schema.get("additionalProperties")));
    }
}
