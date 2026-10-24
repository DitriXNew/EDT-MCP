/** Copyright (c) 2026 DitriX */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.ditrix.edt.mcp.server.tools.impl.BusinessProcessRouteMapTool.MapPreview;
import com.ditrix.edt.mcp.server.utils.BuildUtils.ValidationState;
import com.ditrix.edt.mcp.server.utils.DestructiveConsentGate.ConsentDecision;
import com.ditrix.edt.mcp.server.utils.Refusals;
import com.ditrix.edt.mcp.server.utils.WrittenObjectMarkers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Regression pins for gate findings, using the tool's production boundaries. */
public class BusinessProcessRouteMapGateTest
{
    @Test
    public void testAbsentRemoveRefusesBeforeConsentEvenWhenConsentWouldBeRejected()
    {
        var tool = new BusinessProcessRouteMapTool((name, preview) -> {
            fail("An absent map must be refused before requesting consent");
            return ConsentDecision.REJECT;
        });
        var result = JsonParser.parseString(tool.consentAndWrite("remove", "BusinessProcess.Missing",
            new MapPreview(false, 0, 0, 0, List.of()), () -> {
                throw new AssertionError("An absent map cannot be written");
            })).getAsJsonObject();
        assertFalse(result.get("success").getAsBoolean());
        assertEquals("No route map exists on 'BusinessProcess.Missing'. Use action='replace' to create one.",
            result.get("error").getAsString());
    }

    @Test
    public void testTheSchemeSliceNoteAppendsToTheExistingValidationMessage()
    {
        JsonObject result = markerResult("replace");
        result.addProperty("message", "Operation completed. EDT validation is pending!");
        BusinessProcessRouteMapTool.markEmptySchemeSlice(result);
        assertEquals("Operation completed. EDT validation is pending! No markers were observed for the "
            + "route-map FQN; validation is incomplete. Re-check with get_project_errors or the EDT route-map editor.",
            result.get("message").getAsString());
        assertTrue(result.get("markersIncomplete").getAsBoolean());
        assertFalse(result.has("markerCount"));
    }

    @Test
    public void testDeletedSchemeDoesNotReceiveAnUnobservableValidationNote()
    {
        JsonObject result = markerResult("remove");
        result.addProperty("message", "EDT validation of the written object(s) finished: no markers.");
        JsonObject before = result.deepCopy();
        BusinessProcessRouteMapTool.markEmptySchemeSlice(result);
        assertEquals(before, result);
    }

    @Test
    public void testRemovalChecksOnlyTheSurvivingOwnerMarkerSlice()
    {
        var environment = mock(WrittenObjectMarkers.Environment.class);
        when(environment.resolveTopObjects(eq("TestConfiguration"), anyList(), anyLong()))
            .thenReturn(Map.of("BP", 1L, "BP.Flowchart", 2L));
        when(environment.awaitValidation(eq("TestConfiguration"), anyCollection(), anyLong()))
            .thenReturn(ValidationState.COMPLETE);
        when(environment.read(eq("TestConfiguration"), anyMap(), anyInt(), anyLong()))
            .thenReturn(new WrittenObjectMarkers.ProjectMarkers(0, List.of()));
        var tool = new BusinessProcessRouteMapTool()
        {
            @Override
            protected WrittenObjectMarkers.Environment markerEnvironment()
            {
                return environment;
            }
        };
        var result = JsonParser.parseString(tool.refreshAfterExportAwait(Map.of("projectName", "TestConfiguration"),
            "{\"success\":true,\"action\":\"remove\",\"persisted\":true,\"fqn\":\"BP\",\"routeMapFqn\":\"BP.Flowchart\"}", true))
            .getAsJsonObject();
        verify(environment).resolveTopObjects(eq("TestConfiguration"), eq(List.of("BP")), anyLong());
        assertFalse(result.get("markersIncomplete").getAsBoolean());
        assertEquals(0, result.get("markerCount").getAsInt());
        assertEquals("EDT validation of the written object(s) finished: no markers.",
            result.get("message").getAsString());
    }

    @Test
    public void testExpectedRefusalsBecomePlainErrors()
    {
        for (RuntimeException refusal : List.of(Refusals.argument("Invalid route map"),
            Refusals.state("Route-map services unavailable")))
        {
            var tool = failingTool(refusal);
            var result = JsonParser.parseString(tool.executeOnUiThread(parameters())).getAsJsonObject();
            assertFalse(result.get("success").getAsBoolean());
            assertEquals(refusal.getMessage(), result.get("error").getAsString());
        }
    }

    @Test
    public void testUnexpectedRuntimeFailuresReachTheBaseErrorLogger()
    {
        for (RuntimeException failure : List.of(new NullPointerException(),
            new UnsupportedOperationException("Platform API changed")))
        {
            var tool = failingTool(failure);
            assertSame(failure, assertThrows(failure.getClass(), () -> tool.executeOnUiThread(parameters())));
        }
    }

    private static BusinessProcessRouteMapTool failingTool(RuntimeException failure)
    {
        return new BusinessProcessRouteMapTool()
        {
            @Override
            protected ProjectContext resolveProjectAndScope(String projectName)
            {
                throw failure;
            }
        };
    }

    private static Map<String, String> parameters()
    {
        return Map.of("projectName", "TestConfiguration", "fqn", "BusinessProcess.Process", "action", "get");
    }

    private static JsonObject markerResult(String action)
    {
        var result = JsonParser.parseString("{\"routeMapFqn\":\"BP.Flowchart\",\"markerCount\":0,"
            + "\"markersIncomplete\":false,\"markers\":[]}").getAsJsonObject();
        result.addProperty("action", action);
        return result;
    }
}
