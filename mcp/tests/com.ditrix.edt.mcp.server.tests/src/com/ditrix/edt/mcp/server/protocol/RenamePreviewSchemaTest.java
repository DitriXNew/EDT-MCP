/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.protocol;

import static org.junit.Assert.*;

import java.lang.reflect.Method;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.RenameMetadataObjectTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RenamePreviewSchemaTest
{
    @Test
    public void testSchemaDeclaresWaitAndTextPolicyAndExplainsServerTimeout()
    {
        RenameMetadataObjectTool tool = new RenameMetadataObjectTool();
        JsonObject properties = JsonParser.parseString(tool.getInputSchema()).getAsJsonObject()
            .getAsJsonObject("properties");
        assertTrue(properties.has("waitSeconds"));
        assertEquals("integer", properties.getAsJsonObject("waitSeconds").get("type").getAsString());
        String wait = properties.getAsJsonObject("waitSeconds").get("description").getAsString();
        assertTrue(wait, wait.contains("default 45"));
        assertTrue(wait.contains("0..45"));
        assertEquals("[\"include\",\"exclude\"]",
            properties.getAsJsonObject("textOccurrences").get("enum").toString());
        assertTrue(properties.getAsJsonObject("timeout").get("description").getAsString()
            .contains("cannot keep the call alive"));
        assertTrue(tool.getDescription().contains("poll get_job_status"));
    }

    @Test
    public void testNewParameterDescriptionsSurviveCompaction()
    {
        // Synthetic descriptions make this independently red on the base compactor too.
        String schema = JsonSchemaBuilder.object()
            .integerProperty("waitSeconds", "Preview only, default 20, 0..45; poll get_job_status.")
            .enumProperty("textOccurrences", "include by default; exclude disables module text leaves.",
                "include", "exclude").build();
        JsonObject properties = InputSchemaCompactor.compact(RenameMetadataObjectTool.NAME,
            JsonParser.parseString(schema)).getAsJsonObject().getAsJsonObject("properties");
        assertTrue(properties.getAsJsonObject("waitSeconds").has("description"));
        assertTrue(properties.getAsJsonObject("textOccurrences").has("description"));
    }

    @Test
    public void testGuideExplainsJobRetrievalAndTextLeafLimitations()
    {
        String guide = new RenameMetadataObjectTool().getGuide();
        for (String phrase : new String[] {"waitSeconds", "cannot keep the call alive", "get_job_status",
            "textOccurrences", "referenceChanges", "textChanges", "textOccurrencesSkipped",
            "model-side", "enabled by default", "case-insensitive", "waitSeconds: 0"})
        {
            assertTrue("Guide must explain: " + phrase, guide.contains(phrase));
        }
    }

    @Test
    public void testErrorDetectorOnlyRecognizesExplicitBooleanFailure() throws Exception
    {
        Method detector = ToolResult.class.getMethod("isErrorPayload", String.class);
        assertEquals(true, detector.invoke(null, ToolResult.error("failure").toJson()));
        for (String payload : new String[] {null, "# Preview", "{broken", "[]", "null",
            "{\"success\":true,\"error\":\"diagnostic\"}", "{\"success\":\"false\"}",
            "{\"success\":0}", "{\"error\":\"diagnostic\"}"})
        {
            assertEquals("Must preserve payload: " + payload, false, detector.invoke(null, payload));
        }
    }
}
