/**
 * MCP Server for EDT - Tests
 * Copyright (C) 2026 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.lang.reflect.Constructor;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.protocol.ToolResult;
import com.ditrix.edt.mcp.server.utils.MetadataTypeUtils;
import com.ditrix.edt.mcp.server.utils.SubsystemUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Malformed nested addresses must be refused before project or vendor-support resolution. */
public class DeleteMetadataToolSubsystemEntryValidationTest
{
    private static final String PROJECT_ERROR = "Project not found: Missing734";
    private static final String RUSSIAN_TOKEN =
        "\u041f\u043e\u0434\u0441\u0438\u0441\u0442\u0435\u043c\u0430";

    /** Observes the context seam and supplies a refusal if the entry gate lets it run. */
    private static final class ContextProbeTool extends DeleteMetadataTool
    {
        private int contextResolutions;

        @Override
        protected ProjectContext resolveProjectAndScope(String projectName, String fqn)
        {
            contextResolutions++;
            try
            {
                // The inherited final context has no public constructor; use a real instance.
                Constructor<ProjectContext> constructor = ProjectContext.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                ProjectContext context = constructor.newInstance();
                context.error = ToolResult.error(PROJECT_ERROR).toJson();
                return context;
            }
            catch (ReflectiveOperationException e)
            {
                throw new AssertionError("Could not construct the context probe", e);
            }
        }
    }

    private static void assertEntryRefusal(String fqn, boolean confirm, boolean force)
    {
        ContextProbeTool tool = new ContextProbeTool();
        // This is the narrowest entry seam containing normalization and context/support ordering.
        String json = tool.executeOnUiThread(Map.of("projectName", "Missing734", "fqn", fqn,
            "confirm", Boolean.toString(confirm), "force", Boolean.toString(force)));
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("malformed input must never reach project or vendor-support resolution",
            0, tool.contextResolutions);
        assertFalse(json, result.get("success").getAsBoolean());
        assertEquals(SubsystemUtils.malformedSegmentError(MetadataTypeUtils.normalizeFqn(fqn))
            + " Nothing was deleted.", result.get("error").getAsString());
        assertFalse(json, result.get("error").getAsString().contains(PROJECT_ERROR));
    }

    @Test
    public void testTrailingSeparatorIsRefusedBeforeProjectResolution()
    {
        assertEntryRefusal("Subsystem.A.Subsystem.B.", false, false);
    }

    @Test
    public void testPaddedNestedNameIsRefusedBeforeProjectResolution()
    {
        assertEntryRefusal("Subsystem.A.Subsystem. B ", true, false);
    }

    @Test
    public void testRussianNestedTokensAreValidatedBeforeForcedDeleteContext()
    {
        assertEntryRefusal(RUSSIAN_TOKEN + ".A." + RUSSIAN_TOKEN + ".B.", true, true);
    }

    @Test
    public void testPaddedRussianNameIsRefusedBeforeProjectResolution()
    {
        assertEntryRefusal("Subsystem.\u041f\u0440\u043e\u0434\u0430\u0436\u0438."
            + RUSSIAN_TOKEN + ". \u0417\u0430\u043a\u0430\u0437\u044b ", false, false);
    }

    @Test
    public void testWellFormedNestedAddressStillResolvesTheContext()
    {
        ContextProbeTool tool = new ContextProbeTool();
        String json = tool.executeOnUiThread(Map.of("projectName", "Missing734",
            "fqn", "Subsystem.A.Subsystem.B", "confirm", "true"));
        JsonObject result = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(1, tool.contextResolutions);
        assertFalse(json, result.get("success").getAsBoolean());
        assertEquals(PROJECT_ERROR, result.get("error").getAsString());
    }
}
